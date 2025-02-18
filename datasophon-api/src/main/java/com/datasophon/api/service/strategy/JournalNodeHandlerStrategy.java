package com.datasophon.api.service.strategy;

import com.datasophon.api.utils.ProcessUtils;
import com.datasophon.common.Constants;
import com.datasophon.common.cache.CacheUtils;
import com.datasophon.common.model.ServiceConfig;

import java.util.List;
import java.util.Map;

public class JournalNodeHandlerStrategy implements ServiceRoleStrategy{
    @Override
    public void handler(Integer clusterId, List<String> hosts) {
        Map<String,String> globalVariables = (Map<String, String>) CacheUtils.get("globalVariables"+ Constants.UNDERLINE+clusterId);
        if(hosts.size() >= 3 ){
            ProcessUtils.generateClusterVariable(globalVariables,clusterId,"${journalNode1}",hosts.get(0));
            ProcessUtils.generateClusterVariable(globalVariables,clusterId,"${journalNode2}",hosts.get(1));
            ProcessUtils.generateClusterVariable(globalVariables,clusterId,"${journalNode3}",hosts.get(2));
        }
    }

    @Override
    public void handlerConfig(Integer clusterId, List<ServiceConfig> list) {

    }
}
