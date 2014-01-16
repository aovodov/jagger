package com.griddynamics.jagger.engine.e1.collector.testgroup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/** Listener, that executes before and after test-group execution.
 * @author Gribov Kirill
 * @n
 * @par Details:
 * @details
 * @n
 *
 * @ingroup */
public interface TestGroupListener {

    /** Executes before test-group starts
     * @author Gribov Kirill
     * @n
     *
     * @param infoStart - describes test-group start information */
    void onStart(TestGroupInfo infoStart);

    /** Executes after test-group stops
     * @author Gribov Kirill
     * @n
     *
     * @param infoStop - describes test-group stop information */
    void onStop(TestGroupInfo infoStop);

    public static class Composer implements TestGroupListener{
        private static Logger log = LoggerFactory.getLogger(Composer.class);

        private List<TestGroupListener> listenerList;

        private Composer(List<TestGroupListener> listenerList){
            this.listenerList = listenerList;
        }

        @Override
        public void onStart(TestGroupInfo testGroupInfo) {
            for (TestGroupListener listener : listenerList){
                try{
                    listener.onStart(testGroupInfo);
                }catch (RuntimeException ex){
                    log.error("Failed to call on start in {} test-group-listener", listener.toString(), ex);
                }
            }
        }

        @Override
        public void onStop(TestGroupInfo testGroupInfo) {
            for (TestGroupListener listener : listenerList){
                try{
                    listener.onStop(testGroupInfo);
                }catch (RuntimeException ex){
                    log.error("Failed to call on stop in {} test-group-listener", listener.toString(), ex);
                }
            }
        }

        public static TestGroupListener compose(List<TestGroupListener> listeners){
            return new Composer(listeners);
        }
    }
}