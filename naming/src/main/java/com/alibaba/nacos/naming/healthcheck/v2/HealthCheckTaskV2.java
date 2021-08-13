/*
 * Copyright 1999-2020 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.nacos.naming.healthcheck.v2;

import com.alibaba.nacos.common.task.AbstractExecuteTask;
import com.alibaba.nacos.naming.core.v2.client.impl.IpPortBasedClient;
import com.alibaba.nacos.naming.core.v2.metadata.ClusterMetadata;
import com.alibaba.nacos.naming.core.v2.metadata.NamingMetadataManager;
import com.alibaba.nacos.naming.core.v2.metadata.ServiceMetadata;
import com.alibaba.nacos.naming.core.v2.pojo.InstancePublishInfo;
import com.alibaba.nacos.naming.core.v2.pojo.Service;
import com.alibaba.nacos.naming.healthcheck.HealthCheckReactor;
import com.alibaba.nacos.naming.healthcheck.NacosHealthCheckTask;
import com.alibaba.nacos.naming.healthcheck.v2.processor.HealthCheckProcessorV2Delegate;
import com.alibaba.nacos.naming.misc.Loggers;
import com.alibaba.nacos.naming.misc.SwitchDomain;
import com.alibaba.nacos.sys.utils.ApplicationUtils;
import org.apache.commons.lang3.RandomUtils;

import java.util.Optional;

/**
 * Health check task for v2.x.
 * 连接状态相关的健康检查任务
 * <p>Current health check logic is same as v1.x. TODO refactor health check for v2.x.
 *
 * @author nacos
 */
public class HealthCheckTaskV2 extends AbstractExecuteTask implements NacosHealthCheckTask {

    /**
     * 客户端
     */
    private final IpPortBasedClient client;
    /**
     * 任务ID
     */
    private final String taskId;

    private final SwitchDomain switchDomain;


    private final NamingMetadataManager metadataManager;

    private long checkRtNormalized = -1;
    /**
     * 最佳响应时间
     */
    private long checkRtBest = -1;
    /**
     * 最差响应时间
     */
    private long checkRtWorst = -1;
    /**
     * 上次响应事件
     */
    private long checkRtLast = -1;
    /**
     * 上上次响应事件
     */
    private long checkRtLastLast = -1;
    
    private long startTime;
    
    private volatile boolean cancelled = false;
    
    public HealthCheckTaskV2(IpPortBasedClient client) {
        this.client = client;
        this.taskId = client.getResponsibleId();
        this.switchDomain = ApplicationUtils.getBean(SwitchDomain.class);
        this.metadataManager = ApplicationUtils.getBean(NamingMetadataManager.class);
        initCheckRT();
    }

    /**
     * 初始化响应时间值
     */
    private void initCheckRT() {
        // first check time delay
        checkRtNormalized =
                2000 + RandomUtils.nextInt(0, RandomUtils.nextInt(0, switchDomain.getTcpHealthParams().getMax()));
        checkRtBest = Long.MAX_VALUE;
        checkRtWorst = 0L;
    }
    
    public IpPortBasedClient getClient() {
        return client;
    }
    
    @Override
    public String getTaskId() {
        return taskId;
    }

    /**
     * 线程执行的逻辑
     */
    @Override
    public void doHealthCheck() {
        try {
            for (Service each : client.getAllPublishedService()) {
                if (switchDomain.isHealthCheckEnabled(each.getGroupedServiceName())) {
                    InstancePublishInfo instancePublishInfo = client.getInstancePublishInfo(each);
                    ClusterMetadata metadata = getClusterMetadata(each, instancePublishInfo);
                    ApplicationUtils.getBean(HealthCheckProcessorV2Delegate.class).process(this, each, metadata);
                    if (Loggers.EVT_LOG.isDebugEnabled()) {
                        Loggers.EVT_LOG.debug("[HEALTH-CHECK-V2] schedule health check task: {}", client.getClientId());
                    }
                }
            }
        } catch (Throwable e) {
            Loggers.SRV_LOG.error("[HEALTH-CHECK-V2] error while process health check for {}", client.getClientId(), e);
        } finally {
            if (!cancelled) {
                HealthCheckReactor.scheduleCheck(this);
                // worst == 0 means never checked
                if (this.getCheckRtWorst() > 0) {
                    // TLog doesn't support float so we must convert it into long
                    long checkRtLastLast = getCheckRtLastLast();
                    this.setCheckRtLastLast(this.getCheckRtLast());
                    if (checkRtLastLast > 0) {
                        long diff = ((this.getCheckRtLast() - this.getCheckRtLastLast()) * 10000) / checkRtLastLast;
                        if (Loggers.CHECK_RT.isDebugEnabled()) {
                            Loggers.CHECK_RT.debug("{}->normalized: {}, worst: {}, best: {}, last: {}, diff: {}",
                                    client.getClientId(), this.getCheckRtNormalized(), this.getCheckRtWorst(),
                                    this.getCheckRtBest(), this.getCheckRtLast(), diff);
                        }
                    }
                }
            }
        }
    }

    /**
     * 没被拦截时执行健康检查
     */
    @Override
    public void passIntercept() {
        doHealthCheck();
    }

    /**
     * 被拦截，执行完intercept之后，放入线程池等待下次执行
     */
    @Override
    public void afterIntercept() {
        if (!cancelled) {
            HealthCheckReactor.scheduleCheck(this);
        }
    }

    /**
     *
     */
    @Override
    public void run() {
        doHealthCheck();
    }
    
    private ClusterMetadata getClusterMetadata(Service service, InstancePublishInfo instancePublishInfo) {
        Optional<ServiceMetadata> serviceMetadata = metadataManager.getServiceMetadata(service);
        if (!serviceMetadata.isPresent()) {
            return new ClusterMetadata();
        }
        String cluster = instancePublishInfo.getCluster();
        ClusterMetadata result = serviceMetadata.get().getClusters().get(cluster);
        return null == result ? new ClusterMetadata() : result;
    }
    
    public long getCheckRtNormalized() {
        return checkRtNormalized;
    }
    
    public long getCheckRtBest() {
        return checkRtBest;
    }
    
    public long getCheckRtWorst() {
        return checkRtWorst;
    }
    
    public void setCheckRtWorst(long checkRtWorst) {
        this.checkRtWorst = checkRtWorst;
    }
    
    public void setCheckRtBest(long checkRtBest) {
        this.checkRtBest = checkRtBest;
    }
    
    public void setCheckRtNormalized(long checkRtNormalized) {
        this.checkRtNormalized = checkRtNormalized;
    }
    
    public boolean isCancelled() {
        return cancelled;
    }
    
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }
    
    public long getStartTime() {
        return startTime;
    }
    
    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }
    
    public long getCheckRtLast() {
        return checkRtLast;
    }
    
    public void setCheckRtLast(long checkRtLast) {
        this.checkRtLast = checkRtLast;
    }
    
    public long getCheckRtLastLast() {
        return checkRtLastLast;
    }
    
    public void setCheckRtLastLast(long checkRtLastLast) {
        this.checkRtLastLast = checkRtLastLast;
    }
}
