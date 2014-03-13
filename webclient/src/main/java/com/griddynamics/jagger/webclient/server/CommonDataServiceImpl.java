package com.griddynamics.jagger.webclient.server;

import com.griddynamics.jagger.webclient.client.CommonDataService;
import com.griddynamics.jagger.webclient.client.data.WebClientProperties;

import java.util.List;
import java.util.Map;

public class CommonDataServiceImpl implements CommonDataService {

    private CommonDataProvider commonDataProvider;

    @Override
    public WebClientProperties getWebClientProperties() {
        return commonDataProvider.getWebClientProperties();
    }

    @Override
    public Map<String,List<String>> getDefaultMonitoringParameters() {
        return commonDataProvider.getDefaultMonitoringParameters();
    }

    public CommonDataProvider getCommonDataProvider() {
        return commonDataProvider;
    }

    public void setCommonDataProvider(CommonDataProvider commonDataProvider) {
        this.commonDataProvider = commonDataProvider;
    }
}
