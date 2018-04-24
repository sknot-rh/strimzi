/*
 * Copyright 2017-2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.controller.cluster.operator.assembly;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.extensions.Deployment;
import io.fabric8.kubernetes.api.model.extensions.StatefulSet;
import io.strimzi.controller.cluster.Reconciliation;
import io.strimzi.controller.cluster.model.AssemblyType;
import io.strimzi.controller.cluster.model.KafkaCluster;
import io.strimzi.controller.cluster.model.Labels;
import io.strimzi.controller.cluster.model.Storage;
import io.strimzi.controller.cluster.model.TopicController;
import io.strimzi.controller.cluster.model.ZookeeperCluster;
import io.strimzi.controller.cluster.operator.resource.ConfigMapOperator;
import io.strimzi.controller.cluster.operator.resource.DeploymentOperator;
import io.strimzi.controller.cluster.operator.resource.EventOperator;
import io.strimzi.controller.cluster.operator.resource.KafkaSetOperator;
import io.strimzi.controller.cluster.operator.resource.PvcOperator;
import io.strimzi.controller.cluster.operator.resource.ReconcileResult;
import io.strimzi.controller.cluster.operator.resource.ServiceOperator;
import io.strimzi.controller.cluster.operator.resource.ZookeeperSetOperator;
import io.vertx.core.AsyncResult;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static io.strimzi.controller.cluster.model.TopicController.topicControllerName;

/**
 * <p>Assembly operator for a "Kafka" assembly, which manages:</p>
 * <ul>
 *     <li>A ZooKeeper cluster StatefulSet and related Services</li>
 *     <li>A Kafka cluster StatefulSet and related Services</li>
 *     <li>Optionally, a TopicController Deployment</li>
 * </ul>
 */
public class KafkaAssemblyOperator extends AbstractAssemblyOperator {
    private static final Logger log = LoggerFactory.getLogger(KafkaAssemblyOperator.class.getName());

    private final long operationTimeoutMs;

    private final ZookeeperSetOperator zkSetOperations;
    private final KafkaSetOperator kafkaSetOperations;
    private final ServiceOperator serviceOperations;
    private final PvcOperator pvcOperations;
    private final DeploymentOperator deploymentOperations;

    /**
     * @param vertx The Vertx instance
     * @param isOpenShift Whether we're running with OpenShift
     * @param configMapOperations For operating on ConfigMaps
     * @param serviceOperations For operating on Services
     * @param zkSetOperations For operating on StatefulSets
     * @param pvcOperations For operating on PersistentVolumeClaims
     * @param deploymentOperations For operating on Deployments
     */
    public KafkaAssemblyOperator(Vertx vertx, boolean isOpenShift,
                                 long operationTimeoutMs,
                                 ConfigMapOperator configMapOperations,
                                 ServiceOperator serviceOperations,
                                 ZookeeperSetOperator zkSetOperations,
                                 KafkaSetOperator kafkaSetOperations,
                                 PvcOperator pvcOperations,
                                 DeploymentOperator deploymentOperations,
                                 EventOperator eventOperations) {
        super(vertx, isOpenShift, AssemblyType.KAFKA, configMapOperations, eventOperations);
        this.operationTimeoutMs = operationTimeoutMs;
        this.zkSetOperations = zkSetOperations;
        this.serviceOperations = serviceOperations;
        this.pvcOperations = pvcOperations;
        this.deploymentOperations = deploymentOperations;
        this.kafkaSetOperations = kafkaSetOperations;
    }

    @Override
    public void createOrUpdate(Reconciliation reconciliation, ConfigMap assemblyCm, Handler<AsyncResult<Void>> handler) {
        Future<Void> f = Future.<Void>future().setHandler(handler);
        createOrUpdateZk(reconciliation, assemblyCm)
            .compose(i -> createOrUpdateKafka(reconciliation, assemblyCm))
            .compose(i -> createOrUpdateTopicController(reconciliation, assemblyCm))
            .compose(ar -> f.complete(), f);
    }

    private final Future<Void> createOrUpdateKafka(Reconciliation reconciliation, ConfigMap assemblyCm) {
        String namespace = assemblyCm.getMetadata().getNamespace();
        String name = assemblyCm.getMetadata().getName();
        log.info("{}: create/update kafka {}", reconciliation, name);
        KafkaCluster kafka;
        try {
            kafka = KafkaCluster.fromConfigMap(assemblyCm);
        } catch (Exception e) {
            createEventForModelError(assemblyCm);
            return Future.failedFuture(e);
        }
        Service service = kafka.generateService();
        Service headlessService = kafka.generateHeadlessService();
        ConfigMap metricsConfigMap = kafka.generateMetricsConfigMap();
        StatefulSet statefulSet = kafka.generateStatefulSet(isOpenShift);

        Future<Void> chainFuture = Future.future();
        kafkaSetOperations.scaleDown(namespace, kafka.getName(), kafka.getReplicas())
                .compose(scale -> serviceOperations.reconcile(namespace, kafka.getName(), service))
                .compose(i -> serviceOperations.reconcile(namespace, kafka.getHeadlessName(), headlessService))
                .compose(i -> configMapOperations.reconcile(namespace, kafka.getMetricsConfigName(), metricsConfigMap))
                .compose(i -> kafkaSetOperations.reconcile(namespace, kafka.getName(), statefulSet))
                .compose(diffs -> {
                    if (diffs instanceof ReconcileResult.Patched
                            && ((ReconcileResult.Patched<Boolean>) diffs).differences()) {
                        return kafkaSetOperations.rollingUpdate(namespace, kafka.getName());
                    } else {
                        return Future.succeededFuture();
                    }
                })
                .compose(i -> kafkaSetOperations.scaleUp(namespace, kafka.getName(), kafka.getReplicas()))
                .compose(scale -> serviceOperations.endpointReadiness(namespace, service, 1_000, operationTimeoutMs))
                .compose(i -> serviceOperations.endpointReadiness(namespace, headlessService, 1_000, operationTimeoutMs))
                .compose(chainFuture::complete, chainFuture);

        return chainFuture;
    };

    private final Future<CompositeFuture> deleteKafka(Reconciliation reconciliation) {
        String namespace = reconciliation.namespace();
        String name = reconciliation.assemblyName();
        log.info("{}: delete kafka {}", reconciliation, name);
        StatefulSet ss = kafkaSetOperations.get(namespace, KafkaCluster.kafkaClusterName(name));

        final KafkaCluster kafka = ss == null ? null : KafkaCluster.fromAssembly(ss, namespace, name);
        boolean deleteClaims = kafka != null && kafka.getStorage().type() == Storage.StorageType.PERSISTENT_CLAIM
            && kafka.getStorage().isDeleteClaim();
        List<Future> result = new ArrayList<>(4 + (deleteClaims ? kafka.getReplicas() : 0));

        result.add(configMapOperations.reconcile(namespace, KafkaCluster.metricConfigsName(name), null));
        result.add(serviceOperations.reconcile(namespace, KafkaCluster.kafkaClusterName(name), null));
        result.add(serviceOperations.reconcile(namespace, KafkaCluster.headlessName(name), null));
        result.add(kafkaSetOperations.reconcile(namespace, KafkaCluster.kafkaClusterName(name), null));

        if (deleteClaims) {
            for (int i = 0; i < kafka.getReplicas(); i++) {
                result.add(pvcOperations.reconcile(namespace,
                        kafka.getPersistentVolumeClaimName(i), null));
            }
        }

        return CompositeFuture.join(result);
    };

    private final Future<Void> createOrUpdateZk(Reconciliation reconciliation, ConfigMap assemblyCm) {
        String namespace = assemblyCm.getMetadata().getNamespace();
        String name = assemblyCm.getMetadata().getName();
        log.info("{}: create/update zookeeper {}", reconciliation, name);
        ZookeeperCluster zk;
        try {
            zk = ZookeeperCluster.fromConfigMap(assemblyCm);
        } catch (Exception e) {
            createEventForModelError(assemblyCm);
            return Future.failedFuture(e);
        }
        Service service = zk.generateService();
        Service headlessService = zk.generateHeadlessService();
        Future<Void> chainFuture = Future.future();
        zkSetOperations.scaleDown(namespace, zk.getName(), zk.getReplicas())
                .compose(scale -> serviceOperations.reconcile(namespace, zk.getName(), service))
                .compose(i -> serviceOperations.reconcile(namespace, zk.getHeadlessName(), headlessService))
                .compose(i -> configMapOperations.reconcile(namespace, zk.getMetricsConfigName(), zk.generateMetricsConfigMap()))
                .compose(i -> zkSetOperations.reconcile(namespace, zk.getName(), zk.generateStatefulSet(isOpenShift)))
                //.compose(i -> zkSetOperations.rollingUpdate(namespace, zk.getName()))
                .compose(diffs -> {
                    if (diffs instanceof ReconcileResult.Patched
                            && ((ReconcileResult.Patched<Boolean>) diffs).differences()) {
                        return zkSetOperations.rollingUpdate(namespace, zk.getName());
                    } else {
                        return Future.succeededFuture();
                    }
                })
                .compose(i -> zkSetOperations.scaleUp(namespace, zk.getName(), zk.getReplicas()))
                .compose(scale -> serviceOperations.endpointReadiness(namespace, service, 1_000, operationTimeoutMs))
                .compose(i -> serviceOperations.endpointReadiness(namespace, headlessService, 1_000, operationTimeoutMs))
                .compose(chainFuture::complete, chainFuture);
        return chainFuture;
    }

    private final Future<CompositeFuture> deleteZk(Reconciliation reconciliation) {
        String namespace = reconciliation.namespace();
        String name = reconciliation.assemblyName();
        log.info("{}: delete zookeeper {}", reconciliation, name);
        StatefulSet ss = zkSetOperations.get(namespace, ZookeeperCluster.zookeeperClusterName(name));
        ZookeeperCluster zk = ss == null ? null : ZookeeperCluster.fromAssembly(ss, namespace, name);
        boolean deleteClaims = zk != null && zk.getStorage().type() == Storage.StorageType.PERSISTENT_CLAIM
                && zk.getStorage().isDeleteClaim();
        List<Future> result = new ArrayList<>(4 + (deleteClaims ? zk.getReplicas() : 0));

        result.add(configMapOperations.reconcile(namespace, ZookeeperCluster.zookeeperMetricsName(name), null));
        result.add(serviceOperations.reconcile(namespace, ZookeeperCluster.zookeeperClusterName(name), null));
        result.add(serviceOperations.reconcile(namespace, ZookeeperCluster.zookeeperHeadlessName(name), null));
        result.add(zkSetOperations.reconcile(namespace, ZookeeperCluster.zookeeperClusterName(name), null));

        if (deleteClaims) {
            for (int i = 0; i < zk.getReplicas(); i++) {
                result.add(pvcOperations.reconcile(namespace, zk.getPersistentVolumeClaimName(i), null));
            }
        }

        return CompositeFuture.join(result);
    };

    private final Future<ReconcileResult<Void>> createOrUpdateTopicController(Reconciliation reconciliation, ConfigMap assemblyCm) {
        String namespace = assemblyCm.getMetadata().getNamespace();
        String name = assemblyCm.getMetadata().getName();
        log.info("{}: create/update topic controller {}", reconciliation, name);
        TopicController topicController;
        try {
            topicController = TopicController.fromConfigMap(assemblyCm);
        } catch (Exception e) {
            createEventForModelError(assemblyCm);
            return Future.failedFuture(e);
        }
        Deployment deployment = topicController != null ? topicController.generateDeployment() : null;
        return deploymentOperations.reconcile(namespace, topicControllerName(name), deployment);
    };

    private final Future<ReconcileResult<Void>> deleteTopicController(Reconciliation reconciliation) {
        String namespace = reconciliation.namespace();
        String name = reconciliation.assemblyName();
        log.info("{}: delete topic controller {}", reconciliation, name);
        return deploymentOperations.reconcile(namespace, topicControllerName(name), null);
    };

    @Override
    protected void delete(Reconciliation reconciliation, Handler<AsyncResult<Void>> handler) {
        Future<Void> f = Future.<Void>future().setHandler(handler);
        deleteTopicController(reconciliation)
                .compose(i -> deleteKafka(reconciliation))
                .compose(i -> deleteZk(reconciliation))
                .compose(ar -> f.complete(), f);
    }

    @Override
    protected List<HasMetadata> getResources(String namespace) {
        List<HasMetadata> result = new ArrayList<>();
        result.addAll(kafkaSetOperations.list(namespace, Labels.forType(AssemblyType.KAFKA)));
        result.addAll(zkSetOperations.list(namespace, Labels.forType(AssemblyType.KAFKA)));
        result.addAll(deploymentOperations.list(namespace, Labels.forType(AssemblyType.KAFKA)));
        result.addAll(serviceOperations.list(namespace, Labels.forType(AssemblyType.KAFKA)));
        result.addAll(configMapOperations.list(namespace, Labels.forType(AssemblyType.KAFKA)));
        return result;
    }
}