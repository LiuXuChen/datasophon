package com.datasophon.api.service.impl;

import akka.actor.ActorSelection;
import akka.actor.ActorSystem;
import akka.pattern.Patterns;
import akka.util.Timeout;
import com.datasophon.api.master.ActorUtils;
import com.datasophon.api.service.*;
import com.datasophon.dao.entity.*;
import com.datasophon.api.service.*;
import com.datasophon.api.utils.ProcessUtils;
import com.datasophon.common.Constants;
import com.datasophon.common.cache.CacheUtils;
import com.datasophon.common.command.GetLogCommand;
import com.datasophon.common.enums.CommandType;
import com.datasophon.common.utils.ExecResult;
import com.datasophon.common.utils.PlaceholderUtils;
import com.datasophon.common.utils.Result;
import com.datasophon.dao.enums.NeedRestart;
import com.datasophon.dao.enums.RoleType;
import com.datasophon.dao.enums.ServiceRoleState;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;


import com.datasophon.dao.mapper.ClusterServiceRoleInstanceMapper;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;


@Service("clusterServiceRoleInstanceService")
public class ClusterServiceRoleInstanceServiceImpl extends ServiceImpl<ClusterServiceRoleInstanceMapper, ClusterServiceRoleInstanceEntity> implements ClusterServiceRoleInstanceService {
    private static final Logger logger = LoggerFactory.getLogger(ClusterServiceRoleInstanceServiceImpl.class);

    @Autowired
    ClusterInfoService clusterInfoService;

    @Autowired
    FrameServiceRoleService frameServiceRoleService;

    @Autowired
    FrameServiceService frameService;

    @Autowired
    ClusterServiceRoleInstanceService roleInstanceService;

    @Autowired
    private ClusterServiceCommandService commandService;

    @Autowired
    private ClusterServiceInstanceRoleGroupService roleGroupService;

    @Autowired
    private ClusterServiceRoleInstanceMapper roleInstanceMapper;

    @Autowired
    private ClusterAlertHistoryService alertHistoryService;

    @Override
    public List<ClusterServiceRoleInstanceEntity> getServiceRoleListByHostnameAndClusterId(String hostname, Integer clusterId) {
        return this.list(new QueryWrapper<ClusterServiceRoleInstanceEntity>()
                .eq(Constants.CLUSTER_ID, clusterId)
                .eq(Constants.HOSTNAME, hostname));
    }

    @Override
    public List<ClusterServiceRoleInstanceEntity> getServiceRoleInstanceListByServiceIdAndRoleState(Integer serviceId, ServiceRoleState stop) {
        return this.list(new QueryWrapper<ClusterServiceRoleInstanceEntity>()
                .eq(Constants.SERVICE_ID, serviceId)
                .eq(Constants.SERVICE_ROLE_STATE, stop));
    }

    @Override
    public ClusterServiceRoleInstanceEntity getOneServiceRole(String name, String hostname, Integer id) {
        List<ClusterServiceRoleInstanceEntity> list = this.list(new QueryWrapper<ClusterServiceRoleInstanceEntity>()
                .eq(Constants.SERVICE_ROLE_NAME, name)
                .eq(StringUtils.isNotBlank(hostname), Constants.HOSTNAME, hostname)
                .eq(Constants.CLUSTER_ID, id));
        if(Objects.nonNull(list) && list.size() >0){
            return list.get(0);
        }
        return null;
    }

    @Override
    public Result listAll(Integer serviceInstanceId, String hostname, Integer serviceRoleState, String serviceRoleName, Integer roleGroupId, Integer page, Integer pageSize) {
        Integer offset = (page - 1) * pageSize;
        List<ClusterServiceRoleInstanceEntity> list = this.list(new QueryWrapper<ClusterServiceRoleInstanceEntity>()
                .eq(Constants.SERVICE_ID, serviceInstanceId)
                .eq(serviceRoleState != null, Constants.SERVICE_ROLE_STATE, serviceRoleState)
                .eq(StringUtils.isNotBlank(serviceRoleName), Constants.SERVICE_ROLE_NAME, serviceRoleName)
                .eq(roleGroupId != null, Constants.ROLE_GROUP_ID, roleGroupId)
                .like(StringUtils.isNotBlank(hostname), Constants.HOSTNAME, hostname)
                .last("limit " + offset + "," + pageSize));
        int count = this.count(new QueryWrapper<ClusterServiceRoleInstanceEntity>()
                .eq(Constants.SERVICE_ID, serviceInstanceId)
                .eq(serviceRoleState != null, Constants.SERVICE_ROLE_STATE, serviceRoleState)
                .eq(StringUtils.isNotBlank(serviceRoleName), Constants.SERVICE_ROLE_NAME, serviceRoleName)
                .eq(roleGroupId != null, Constants.ROLE_GROUP_ID, roleGroupId)
                .like(StringUtils.isNotBlank(hostname), Constants.HOSTNAME, hostname));
        for (ClusterServiceRoleInstanceEntity roleInstanceEntity : list) {
            ClusterServiceInstanceRoleGroup roleGroup = roleGroupService.getById(roleInstanceEntity.getRoleGroupId());
            roleInstanceEntity.setRoleGroupName(roleGroup.getRoleGroupName());
            roleInstanceEntity.setServiceRoleStateCode(roleInstanceEntity.getServiceRoleState().getValue());
        }
        return Result.success(list).put(Constants.TOTAL, count);
    }

    @Override
    public Result getLog(Integer serviceRoleInstanceId) throws Exception {
        ClusterServiceRoleInstanceEntity roleInstance = this.getById(serviceRoleInstanceId);
        ClusterInfoEntity clusterInfo = clusterInfoService.getById(roleInstance.getClusterId());
        FrameServiceRoleEntity serviceRole = frameServiceRoleService.getServiceRoleByFrameCodeAndServiceRoleName(clusterInfo.getClusterFrame(), roleInstance.getServiceRoleName());
        Map<String, String> globalVariables = (Map<String, String>) CacheUtils.get("globalVariables" + Constants.UNDERLINE + roleInstance.getClusterId());
        //        String serviceRoleJson = serviceRole.getServiceRoleJson();
//        ServiceRoleInfo serviceRoleInfo = JSONObject.parseObject(serviceRoleJson, ServiceRoleInfo.class);
        if (serviceRole.getServiceRoleType() == RoleType.CLIENT) {
            return Result.success("client does not have any log");
        }
        FrameServiceEntity frameServiceEntity = frameService.getById(serviceRole.getServiceId());
        String logFile = serviceRole.getLogFile();
        if (StringUtils.isNotBlank(logFile)) {
            logFile = PlaceholderUtils.replacePlaceholders(logFile, globalVariables, Constants.REGEX_VARIABLE);
            logger.info("logFile is {}", logFile);
        }
        GetLogCommand command = new GetLogCommand();
        command.setLogFile(logFile);
        command.setDecompressPackageName(frameServiceEntity.getDecompressPackageName());
        logger.info("start to get {} log from {}", serviceRole.getServiceRoleName(), roleInstance.getHostname());

        ActorSelection configActor = ActorUtils.actorSystem.actorSelection("akka.tcp://datasophon@" + roleInstance.getHostname() + ":2552/user/worker/logActor");
        Timeout timeout = new Timeout(Duration.create(60, "seconds"));
        Future<Object> logFuture = Patterns.ask(configActor, command, timeout);
        ExecResult logResult = (ExecResult) Await.result(logFuture, timeout.duration());
        if (Objects.nonNull(logResult) && logResult.getExecResult()) {
            return Result.success(logResult.getExecOut());
        }
        return Result.success();
    }

    @Override
    public List<ClusterServiceRoleInstanceEntity> getServiceRoleInstanceListByServiceId(int id) {
        return this.list(new QueryWrapper<ClusterServiceRoleInstanceEntity>()
                .eq(Constants.SERVICE_ID, id));
    }

    @Override
    public void deleteServiceRole(List<String> idList) {
        for (String idStr : idList) {
            int id = Integer.parseInt(idStr);
            ClusterServiceRoleInstanceEntity roleInstanceEntity = this.getById(id);
            if (roleInstanceEntity.getServiceRoleState().equals(ServiceRoleState.RUNNING)) {
                continue;
            } else {
                //删除对应的告警
                alertHistoryService.removeAlertByRoleInstanceId(id);
                this.removeById(id);
            }
        }

    }

    @Override
    public List<ClusterServiceRoleInstanceEntity> getServiceRoleInstanceListByClusterIdAndRoleName(Integer clusterId, String roleName) {
        List<ClusterServiceRoleInstanceEntity> list = this.list(new QueryWrapper<ClusterServiceRoleInstanceEntity>()
                .eq(Constants.CLUSTER_ID, clusterId).eq(Constants.SERVICE_ROLE_NAME, roleName));
        return list;
    }

    @Override
    public List<ClusterServiceRoleInstanceEntity> getRunningServiceRoleInstanceListByServiceId(Integer serviceInstanceId) {
        return this.list(new QueryWrapper<ClusterServiceRoleInstanceEntity>()
                .eq(Constants.SERVICE_ID, serviceInstanceId)
                .eq(Constants.SERVICE_ROLE_STATE, ServiceRoleState.RUNNING));
    }

    @Override
    public Result restartObsoleteService(Integer roleGroupId) {
        ClusterServiceInstanceRoleGroup roleGroup = roleGroupService.getById(roleGroupId);
        List<ClusterServiceRoleInstanceEntity> list = this.list(new QueryWrapper<ClusterServiceRoleInstanceEntity>()
                .eq(Constants.ROLE_GROUP_ID, roleGroupId)
                .eq(Constants.NEET_RESTART,NeedRestart.YES));
        if(Objects.nonNull(list) && list.size() >0){
            List<String> ids = list.stream().map(e -> e.getId() + "").collect(Collectors.toList());
            commandService.generateServiceRoleCommand(roleGroup.getClusterId(), CommandType.RESTART_SERVICE, roleGroup.getServiceInstanceId(), ids);
        }else{
            return Result.error("该角色组没有过时服务");
        }
        return Result.success();
    }

    @Override
    public Result decommissionNode(String serviceRoleInstanceIds,String serviceName) throws Exception {
        TreeSet<String> hosts = new TreeSet<String>();
        Integer serviceInstanceId = null;
        String serviceRoleName = "";
        for (String str : serviceRoleInstanceIds.split(",")) {
            int serviceRoleInstanceId = Integer.parseInt(str);
            ClusterServiceRoleInstanceEntity roleInstanceEntity = this.getById(serviceRoleInstanceId);
            if("DataNode".equals(roleInstanceEntity.getServiceRoleName()) || "NodeManager".equals(roleInstanceEntity.getServiceRoleName())){
                hosts.add(roleInstanceEntity.getHostname());
                serviceInstanceId = roleInstanceEntity.getServiceId();
                serviceRoleName = roleInstanceEntity.getServiceRoleName();
                roleInstanceEntity.setServiceRoleState(ServiceRoleState.DECOMMISSIONING);
                this.updateById(roleInstanceEntity);
            }
        }
        //查询已退役节点
        List<ClusterServiceRoleInstanceEntity> list = this.list(new QueryWrapper<ClusterServiceRoleInstanceEntity>()
                .eq(Constants.SERVICE_ROLE_STATE, ServiceRoleState.DECOMMISSIONING)
                .in(Constants.ID,serviceRoleInstanceIds));
        //添加已退役节点到黑名单
        for (ClusterServiceRoleInstanceEntity roleInstanceEntity : list) {
            hosts.add(roleInstanceEntity.getHostname());
        }
        String type = "blacklist";
        String roleName = "NameNode";
        if("nodemanager".equals(serviceRoleName.toLowerCase())){
            type = "nmexclude";
            roleName = "ResourceManager";
        }
        if(hosts.size() > 0){
            ProcessUtils.hdfsECMethond(serviceInstanceId,this,hosts,"blacklist",roleName);
        }
        return Result.success();
    }

    @Override
    public void updateToNeedRestart(Integer roleGroupId) {
        roleInstanceMapper.updateToNeedRestart(roleGroupId);
    }

    @Override
    public List<ClusterServiceRoleInstanceEntity> getObsoleteService(Integer serviceInstanceId) {
        return this.list(new QueryWrapper<ClusterServiceRoleInstanceEntity>()
                    .eq(Constants.SERVICE_ID,serviceInstanceId)
                    .eq(Constants.NEET_RESTART, NeedRestart.YES));
    }

    @Override
    public List<ClusterServiceRoleInstanceEntity> getStoppedRoleInstanceOnHost(Integer clusterId, String hostname, ServiceRoleState state) {
        return roleInstanceService.list(new QueryWrapper<ClusterServiceRoleInstanceEntity>()
                .eq(Constants.CLUSTER_ID, clusterId)
                .eq(Constants.HOSTNAME, hostname)
                .eq(Constants.SERVICE_ROLE_STATE, state));
    }
}
