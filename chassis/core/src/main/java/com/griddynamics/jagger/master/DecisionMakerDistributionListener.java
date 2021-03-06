package com.griddynamics.jagger.master;

import com.griddynamics.jagger.coordinator.NodeContext;
import com.griddynamics.jagger.coordinator.NodeId;
import com.griddynamics.jagger.dbapi.entity.DecisionPerMetricEntity;
import com.griddynamics.jagger.dbapi.entity.DecisionPerTaskEntity;
import com.griddynamics.jagger.dbapi.entity.MetricDescriptionEntity;
import com.griddynamics.jagger.dbapi.entity.TaskData;
import com.griddynamics.jagger.engine.e1.ProviderUtil;
import com.griddynamics.jagger.engine.e1.collector.limits.*;
import com.griddynamics.jagger.engine.e1.collector.testgroup.TestGroupDecisionMakerInfo;
import com.griddynamics.jagger.engine.e1.scenario.WorkloadTask;
import com.griddynamics.jagger.engine.e1.services.DataService;
import com.griddynamics.jagger.engine.e1.services.DefaultDataService;
import com.griddynamics.jagger.engine.e1.services.JaggerPlace;
import com.griddynamics.jagger.engine.e1.services.data.service.MetricEntity;
import com.griddynamics.jagger.engine.e1.services.data.service.MetricSummaryValueEntity;
import com.griddynamics.jagger.engine.e1.services.data.service.TestEntity;
import com.griddynamics.jagger.util.Decision;
import com.griddynamics.jagger.engine.e1.collector.testgroup.TestGroupDecisionMakerListener;
import com.griddynamics.jagger.engine.e1.sessioncomparation.WorstCaseDecisionMaker;
import com.griddynamics.jagger.master.configuration.Task;
import org.hibernate.*;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.hibernate3.HibernateCallback;
import org.springframework.orm.hibernate3.support.HibernateDaoSupport;

import java.sql.SQLException;
import java.util.*;

public class DecisionMakerDistributionListener extends HibernateDaoSupport implements DistributionListener {
    private static final Logger log = LoggerFactory.getLogger(DecisionMakerDistributionListener.class);

    private NodeContext nodeContext;
    private WorstCaseDecisionMaker worstCaseDecisionMaker = new WorstCaseDecisionMaker();

    public DecisionMakerDistributionListener() {}

    public void setNodeContext(NodeContext nodeContext) {
        this.nodeContext = nodeContext;
    }

    @Override
    public void onDistributionStarted(String sessionId, String taskId, Task task, Collection<NodeId> capableNodes) {
        //do nothing
    }

    @Override
    public void onTaskDistributionCompleted(String sessionId, String taskId, Task task) {

        if (task instanceof CompositeTask) {
            TestGroupDecisionMakerListener decisionMakerListener = TestGroupDecisionMakerListener.Composer.compose(ProviderUtil.provideElements(((CompositeTask) task).getDecisionMakerListeners(),
                    sessionId,
                    taskId,
                    nodeContext,
                    JaggerPlace.TEST_GROUP_DECISION_MAKER_LISTENER));

            DataService dataService = new DefaultDataService(nodeContext);

            // Get tests in test group
            CompositeTask compositeTask = (CompositeTask) task;
            List<WorkloadTask> workloadTasks = new ArrayList<WorkloadTask>();
            for (CompositableTask compositableTask : compositeTask.getAttendant()) {
                if (compositableTask instanceof WorkloadTask) {
                    workloadTasks.add((WorkloadTask) compositableTask);
                }
            }
            for (CompositableTask compositableTask : compositeTask.getLeading()) {
                if (compositableTask instanceof WorkloadTask) {
                    workloadTasks.add((WorkloadTask) compositableTask);
                }
            }

            // Make decision per tests
            Set<DecisionPerTest> decisionsPerTest = new HashSet<DecisionPerTest>();

            for (WorkloadTask workloadTask : workloadTasks) {
                if (workloadTask.getLimits() != null) {
                    String testName = workloadTask.getName();

                    // Get data for current session
                    TestEntity testEntity = dataService.getTestByName(sessionId,testName);
                    Set<MetricEntity> metricEntitySet = dataService.getMetrics(testEntity);
                    Map<MetricEntity,MetricSummaryValueEntity> metricValues = dataService.getMetricSummary(metricEntitySet);

                    Map<String,MetricEntity> idToEntity = new HashMap<String, MetricEntity>();
                    for (MetricEntity metricEntity : metricValues.keySet()) {
                        idToEntity.put(metricEntity.getMetricId(),metricEntity);
                    }

                    // Get relation limit <-> metrics
                    boolean needBaselineSessionValue = false;
                    Map<Limit,Set<MetricEntity>> limitToEntity = new HashMap<Limit, Set<MetricEntity>>();
                    for (Limit limit : workloadTask.getLimits().getLimits()) {
                        limitToEntity.put(limit,getMetricsForLimit(limit, idToEntity));
                        if (!limitToEntity.get(limit).isEmpty()) {
                            if (limit.getRefValue() == null) {
                                needBaselineSessionValue = true;
                            }
                        }
                    }

                    // Get data for baseline session
                    //todo ??? JFG_745 check that tests match when getting baseline values
                    Map<String,Double> metricIdToValuesBaseline = new HashMap<String, Double>();
                    if (needBaselineSessionValue) {
                        String baselineId = workloadTask.getLimits().getBaselineId();
                        TestEntity testEntityBaseline = dataService.getTestByName(baselineId, testName);
                        if (testEntityBaseline != null) {
                            Set<MetricEntity> metricEntitySetBaseline = dataService.getMetrics(testEntityBaseline);
                            Map<MetricEntity,MetricSummaryValueEntity> metricValuesBaseline = dataService.getMetricSummary(metricEntitySetBaseline);
                            for (Map.Entry<MetricEntity,MetricSummaryValueEntity> entry : metricValuesBaseline.entrySet()) {
                                metricIdToValuesBaseline.put(entry.getKey().getMetricId(),entry.getValue().getValue());
                            }
                        }
                        else {
                            log.error("Was not able to find test {} in baseline session {}",testName,baselineId);
                        }
                    }

                    log.info("Making decision for test: {} (baseline session: {})",testName,workloadTask.getLimits().getBaselineId());

                    // Compare
                    Set<DecisionPerLimit> decisionsPerLimit = new HashSet<DecisionPerLimit>();
                    Set<MetricEntity> duplicatedMetrics = new HashSet<MetricEntity>();
                    for (Limit limit : workloadTask.getLimits().getLimits()) {
                        DecisionPerLimit decisionPerLimit = compareMetricsToLimit(limit,
                                limitToEntity.get(limit),
                                duplicatedMetrics,
                                metricValues,metricIdToValuesBaseline,
                                workloadTask.getLimits().getLimitSetConfig());

                        log.debug(decisionPerLimit.toString());

                        decisionsPerLimit.add(decisionPerLimit);
                    }

                    // decisionPetTest = worst case decisionPerLimit
                    Decision decisionPerTest;
                    List<Decision> decisions = new ArrayList<Decision>();
                    for (DecisionPerLimit decisionPerLimit : decisionsPerLimit) {
                        decisions.add(decisionPerLimit.getDecisionPerLimit());
                    }
                    decisionPerTest = worstCaseDecisionMaker.getDecision(decisions);

                    DecisionPerTest resultForTest = new DecisionPerTest(testEntity, decisionsPerLimit, decisionPerTest);
                    log.info("\n{}",resultForTest.toString());

                    decisionsPerTest.add(resultForTest);
                }
            }

            // Save decisions per metric if there were any
            if (decisionsPerTest.size() > 0) {
                saveDecisionsForMetrics(sessionId, taskId, decisionsPerTest);
            }

            // Call test group listener with basic or customer code
            TestGroupDecisionMakerInfo testGroupDecisionMakerInfo =
                    new TestGroupDecisionMakerInfo((CompositeTask)task,sessionId,decisionsPerTest);
            Decision decisionPerTestGroup = decisionMakerListener.onDecisionMaking(testGroupDecisionMakerInfo);

            log.info("\n\nDecision for test group {} - {}\n",task.getTaskName(),decisionPerTestGroup);

            // Save decisions per test group
            saveDecisionsForTestGroup(sessionId, taskId, decisionPerTestGroup);
        }
    }


    private Set<MetricEntity> getMetricsForLimit(Limit limit, Map<String, MetricEntity> idToEntity) {
        String metricId = limit.getMetricName();
        Set<MetricEntity> metricsForLimit = new HashSet<MetricEntity>();

        // Strict matching
        if (idToEntity.keySet().contains(metricId)) {
            metricsForLimit.add(idToEntity.get(metricId));
        }
        else {
            // Matching to regex (f.e. agent name(s) or aggregator name(s) omitted)
            String regex = "^" + metricId + ".*";
            for (String id : idToEntity.keySet()) {
                if (id.matches(regex)) {
                    metricsForLimit.add(idToEntity.get(id));
                }
            }
        }

        return metricsForLimit;
    }

    private DecisionPerLimit compareMetricsToLimit(Limit limit,
                                                   Set<MetricEntity> metricsPerLimit,
                                                   Set<MetricEntity> duplicatedMetrics,
                                                   Map<MetricEntity, MetricSummaryValueEntity> metricValues,
                                                   Map<String, Double> metricValuesBaseline,
                                                   LimitSetConfig limitSetConfig) {

        Set<DecisionPerMetric> decisionsPerMetric = new HashSet<DecisionPerMetric>();
        List<Decision> allDecisions = new ArrayList<Decision>();
        Decision decisionWhenMetricWasAlreadyCompared = Decision.OK;
        for (MetricEntity metricEntity : metricsPerLimit) {
            Double refValue = limit.getRefValue();
            Double value = metricValues.get(metricEntity).getValue();
            Decision decision = Decision.OK;

            //todo ??? JFG-744 docu for decision making with use of limits
            // !!! mention in docu

            // if metric entity already was used to take decision we will not use it
            // 'limit to metric' relation should be 'one to many' or 'one to one'
            if (duplicatedMetrics.contains(metricEntity)) {
                String errorText = "Several limits are matching same metric. Decision for this metric was already taken and will be not overwritten. Metric: {},\n" +
                        "Decision due to error case: {}";
                switch (limitSetConfig.getDecisionWhenSeveralLimitsMatchSingleMetric()) {
                    case OK:
                        decisionWhenMetricWasAlreadyCompared = Decision.OK;
                        log.info(errorText,metricEntity.toString(), decisionWhenMetricWasAlreadyCompared);
                        break;
                    case WARNING:
                        decisionWhenMetricWasAlreadyCompared = Decision.WARNING;
                        log.warn(errorText, metricEntity.toString(), decisionWhenMetricWasAlreadyCompared);
                        break;
                    default:
                        decisionWhenMetricWasAlreadyCompared = Decision.FATAL;
                        log.error(errorText, metricEntity.toString(), decisionWhenMetricWasAlreadyCompared);
                        break;
                }

                // case when several limits match single metrics should also influence final decision per limit
                allDecisions.add(decisionWhenMetricWasAlreadyCompared);

                continue;
            }
            duplicatedMetrics.add(metricEntity);


            // if null - we are comparing to baseline
            if (refValue == null) {
                if (metricValuesBaseline.containsKey(metricEntity.getMetricId())) {
                    refValue = metricValuesBaseline.get(metricEntity.getMetricId());
                }
            }

            if (refValue == null) {
                String errorText = "Reference value for comparison of metric vs baseline was not found. Metric: {},\n" +
                        "Decision per metric: {}";
                switch (limitSetConfig.getDecisionWhenNoBaselineForMetric()) {
                    case OK:
                        decision = Decision.OK;
                        log.info(errorText,metricEntity.toString(), decision);
                        break;
                    case WARNING:
                        decision = Decision.WARNING;
                        log.warn(errorText, metricEntity.toString(), decision);
                        break;
                    default:
                        decision = Decision.FATAL;
                        log.error(errorText, metricEntity.toString(), decision);
                        break;
                }
            }
            else {
                if ((refValue > 0.0) || (refValue.equals(0D))) {
                    if (value < limit.getLowerErrorThreshold()*refValue) {
                        decision = Decision.FATAL;
                    }
                    else if (value < limit.getLowerWarningThreshold()*refValue) {
                        decision = Decision.WARNING;
                    }
                    else if (value < limit.getUpperWarningThreshold()*refValue) {
                        decision = Decision.OK;
                    }
                    else if (value < limit.getUpperErrorThreshold()*refValue) {
                        decision = Decision.WARNING;
                    }
                    else {
                        decision = Decision.FATAL;
                    }
                }
                else {
                    if (value < limit.getUpperErrorThreshold()*refValue) {
                        decision = Decision.FATAL;
                    }
                    else if (value < limit.getUpperWarningThreshold()*refValue) {
                        decision = Decision.WARNING;
                    }
                    else if (value < limit.getLowerWarningThreshold()*refValue) {
                        decision = Decision.OK;
                    }
                    else if (value < limit.getLowerErrorThreshold()*refValue) {
                        decision = Decision.WARNING;
                    }
                    else {
                        decision = Decision.FATAL;
                    }
                }
            }

            decisionsPerMetric.add(new DecisionPerMetric(metricEntity, value, refValue, decision));
        }

        // decisionPerLimit = worst case decisionPerLimit
        Decision decisionPerLimit;
        for (DecisionPerMetric decisionPerMetric : decisionsPerMetric) {
            allDecisions.add(decisionPerMetric.getDecisionPerMetric());
            log.info("\n{}",decisionPerMetric.toString());
        }
        if (allDecisions.isEmpty()) {
            String errorText = "Limit doesn't have any matching metric in current session. Limit {},\n" +
                    "Decision per limit: {}";
            switch (limitSetConfig.getDecisionWhenNoMetricForLimit()) {
                case OK:
                    decisionPerLimit = Decision.OK;
                    log.debug(errorText,limit, decisionPerLimit);
                    break;
                case WARNING:
                    decisionPerLimit = Decision.WARNING;
                    log.warn(errorText,limit, decisionPerLimit);
                    break;
                default:
                    decisionPerLimit = Decision.FATAL;
                    log.error(errorText,limit, decisionPerLimit);
                    break;
            }
        }
        else {
            decisionPerLimit = worstCaseDecisionMaker.getDecision(allDecisions);
        }

        return new DecisionPerLimit(limit,decisionsPerMetric,decisionPerLimit);
    }

    private void saveDecisionsForMetrics(String sessionId, String testGroupTaskId, Collection<DecisionPerTest> decisionsPerTest) {

        final List<DecisionPerMetricEntity> decisionPerMetricEntityList = new ArrayList<DecisionPerMetricEntity>();

        Set<MetricDescriptionEntity> metricDescriptionEntitiesPerTest = null;
        Set<MetricDescriptionEntity> metricDescriptionEntitiesPerTestGroup = null;
        Long testGroupId = null;

        for (DecisionPerTest decisionPerTest : decisionsPerTest) {
            Long testId = decisionPerTest.getTestEntity().getId();
            metricDescriptionEntitiesPerTest = getMetricDescriptionEntitiesPerTest(testId);

            for (DecisionPerLimit decisionPerLimit : decisionPerTest.getDecisionsPerLimit()) {
                for (DecisionPerMetric decisionPerMetric : decisionPerLimit.getDecisionsPerMetric()) {

                    String metricId = decisionPerMetric.getMetricEntity().getMetricId();
                    MetricDescriptionEntity metricDescriptionEntity = findMetricDescriptionByMetricId(metricId,metricDescriptionEntitiesPerTest);

                    // metric description belongs to parent (test-group) of the test
                    if (metricDescriptionEntity == null) {
                        if (testGroupId == null) {
                            testGroupId = getTestGroupId(testGroupTaskId,sessionId);
                            metricDescriptionEntitiesPerTestGroup = getMetricDescriptionEntitiesPerTest(testGroupId);
                        }
                        metricDescriptionEntity = findMetricDescriptionByMetricId(metricId, metricDescriptionEntitiesPerTestGroup);
                    }

                    if (metricDescriptionEntity != null) {
                        decisionPerMetricEntityList.add(new DecisionPerMetricEntity(metricDescriptionEntity,
                                decisionPerMetric.getDecisionPerMetric().toString()));
                    }
                    else {
                        log.error("\nUnable to create MetricDescriptionEntity for metricId " + metricId +
                                ", testId " + testId +
                                ", testGroupId " + testGroupId +
                                "\nMetric will influence decision making, but decision for this metric will be not saved to DB");
                    }
                }
            }
        }

        getHibernateTemplate().execute(new HibernateCallback<Void>() {
            @Override
            public Void doInHibernate(Session session) throws HibernateException, SQLException {
                for (DecisionPerMetricEntity decisionPerMetricEntity : decisionPerMetricEntityList) {
                    session.persist(decisionPerMetricEntity);
                }
                session.flush();
                return null;
            }
        });

    }

    private void saveDecisionsForTestGroup(String sessionId, String testGroupTaskId, Decision decisionsPerTestGroup) {
        TaskData taskData = getTaskData(testGroupTaskId,sessionId);
        final DecisionPerTaskEntity decisionPerTaskEntity = new DecisionPerTaskEntity(taskData,decisionsPerTestGroup.toString());

        getHibernateTemplate().execute(new HibernateCallback<Void>() {
            @Override
            public Void doInHibernate(Session session) throws HibernateException, SQLException {
                session.persist(decisionPerTaskEntity);
                session.flush();
                return null;
            }
        });

    }

    private TaskData getTaskData(final String taskId, final String sessionId) {
        return getHibernateTemplate().execute(new HibernateCallback<TaskData>() {
            @Override
            public TaskData doInHibernate(org.hibernate.Session session) throws HibernateException, SQLException {
                return (TaskData) session.createQuery("select t from TaskData t where sessionId=? and taskId=?")
                        .setParameter(0, sessionId)
                        .setParameter(1, taskId)
                        .uniqueResult();
            }
        });
    }

    private Long getTestGroupId(final String taskId, final String sessionId) {
        return getTaskData(taskId, sessionId).getId();
    }

    private MetricDescriptionEntity findMetricDescriptionByMetricId (String metricId, Collection<MetricDescriptionEntity> metricDescriptionEntities) {

        if (metricDescriptionEntities != null) {
            for (MetricDescriptionEntity metricDescriptionEntity : metricDescriptionEntities) {
                if (metricId.equals(metricDescriptionEntity.getMetricId())) {
                    return metricDescriptionEntity;
                }
            }
        }

        return null;
    }

    private Set<MetricDescriptionEntity> getMetricDescriptionEntitiesPerTest(final Long taskId) {
        return getHibernateTemplate().execute(new HibernateCallback<Set<MetricDescriptionEntity>>() {
            @Override
            public Set<MetricDescriptionEntity> doInHibernate(org.hibernate.Session session) throws HibernateException, SQLException {
                Set<MetricDescriptionEntity> result = new HashSet<MetricDescriptionEntity>();
                result.addAll(session.createQuery("select m from MetricDescriptionEntity m where taskData.id=?")
                        .setParameter(0, taskId)
                        .list());

                return result;
            }
        });
    }

}

