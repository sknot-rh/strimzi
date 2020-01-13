/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.operator.resource;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.strimzi.operator.common.AdminClientProvider;
import io.strimzi.operator.common.BackOff;
import io.strimzi.operator.common.model.Labels;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.DescribeConfigsResult;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.function.Function;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Specialization of {@link StatefulSetOperator} for StatefulSets of Kafka brokers
 */
public class KafkaSetOperator extends StatefulSetOperator {

    private static final Logger log = LogManager.getLogger(KafkaSetOperator.class);

    private final AdminClientProvider adminClientProvider;

    /**
     * Constructor
     *
     * @param vertx  The Vertx instance
     * @param client The Kubernetes client
     * @param operationTimeoutMs The timeout.
     * @param adminClientProvider A provider for the AdminClient.
     */
    public KafkaSetOperator(Vertx vertx, KubernetesClient client, long operationTimeoutMs,
                            AdminClientProvider adminClientProvider) {
        super(vertx, client, operationTimeoutMs);
        this.adminClientProvider = adminClientProvider;
    }

    @Override
    protected boolean shouldIncrementGeneration(StatefulSetDiff diff) {
        return !diff.isEmpty() && needsRollingUpdate(diff);
    }

    public static boolean needsRollingUpdate(StatefulSetDiff diff) {
        if (diff.changesLabels()) {
            log.debug("Changed labels => needs rolling update");
            return true;
        }
        if (diff.changesSpecTemplate()) {
            log.debug("Changed template spec => needs rolling update");
            return true;
        }
        if (diff.changesVolumeClaimTemplates()) {
            log.debug("Changed volume claim template => needs rolling update");
            return true;
        }
        if (diff.changesVolumeSize()) {
            log.debug("Changed size of the volume claim template => no need for rolling update");
            return false;
        }
        return false;
    }

    @Override
    public Future<Void> maybeRollingUpdate(StatefulSet sts, Function<Pod, String> podNeedsRestart,
                                           Secret clusterCaCertSecret, Secret coKeySecret) {
        return new KafkaRoller(vertx, podOperations, 1_000, operationTimeoutMs,
            () -> new BackOff(250, 2, 10), sts, clusterCaCertSecret, coKeySecret, adminClientProvider)
                .rollingRestart(podNeedsRestart);
    }

    /**
     * @param sts Stateful set to which kafka pod belongs
     * @param pod Specific kafka pod
     * @return a future which contains map with all kafka properties including their values
     */
    public Future<Map<ConfigResource, Config>> getCurrentConfig(StatefulSet sts, Pod pod) {
        String cluster = sts.getMetadata().getLabels().get(Labels.STRIMZI_CLUSTER_LABEL);
        String namespace = sts.getMetadata().getNamespace();
        Future<Secret> clusterCaKeySecretFuture = secretOperations.getAsync(
                namespace, KafkaResources.clusterCaCertificateSecretName(cluster));
        Future<Secret> coKeySecretFuture = secretOperations.getAsync(
                namespace, ClusterOperator.secretName(cluster));
        int podId = Integer.parseInt(pod.getMetadata().getName().substring(pod.getMetadata().getName().lastIndexOf("-") + 1));
        return CompositeFuture.join(clusterCaKeySecretFuture, coKeySecretFuture).compose(compositeFuture -> {
            Secret clusterCaKeySecret = compositeFuture.resultAt(0);
            if (clusterCaKeySecret == null) {
                return Future.failedFuture(missingSecretFuture(namespace, KafkaCluster.clusterCaKeySecretName(cluster)));
            }
            Secret coKeySecret = compositeFuture.resultAt(1);
            if (coKeySecret == null) {
                return Future.failedFuture(missingSecretFuture(namespace, ClusterOperator.secretName(cluster)));
            }
            return getCurrentConfig(podId, namespace, cluster, clusterCaKeySecret, coKeySecret);
        });
    }

    public Future<Map<ConfigResource, Config>> getCurrentConfig(int podId, String namespace, String cluster,
                                         Secret clusterCaCertSecret, Secret coKeySecret) {
        Promise<Map<ConfigResource, Config>> futRes = Promise.promise();
        String hostname = KafkaCluster.podDnsName(namespace, cluster, KafkaCluster.kafkaPodName(cluster, podId)) + ":" + KafkaCluster.REPLICATION_PORT;
        AdminClient ac = adminClientProvider.createAdminClient(hostname, clusterCaCertSecret, coKeySecret);
        ConfigResource resource = new ConfigResource(ConfigResource.Type.BROKER, String.valueOf(podId));
        DescribeConfigsResult configs = ac.describeConfigs(Collections.singletonList(resource));
        ac.close();
        Map<ConfigResource, Config> config = null;
        try {
            config = configs.all().get(1000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            e.printStackTrace();
        }
        Properties result = new Properties();
        config.forEach((key, value) -> {
            value.entries().forEach(entry -> {
                String val = entry.value() == null ? "null" : entry.value();
                result.put(entry.name(), val);
            });
        });
        futRes.complete(config);
        return futRes.future();
    }

}
