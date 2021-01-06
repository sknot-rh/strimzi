/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.common.operator.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.kubernetes.api.model.Status;
import io.fabric8.kubernetes.api.model.apiextensions.v1beta1.CustomResourceDefinition;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.client.CustomResourceList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.fabric8.kubernetes.client.dsl.base.OperationSupport;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.strimzi.operator.common.Util;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;

@SuppressFBWarnings(value = "RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE",
        justification = "Erroneous on Java 11: https://github.com/spotbugs/spotbugs/issues/756")
public class CrdOperator<C extends KubernetesClient,
            T extends CustomResource,
            L extends CustomResourceList<T>>
        extends AbstractWatchableStatusedResourceOperator<C, T, L, Resource<T>> {

    private final Class<T> cls;
    private final Class<L> listCls;
    protected final String plural;
    protected final CustomResourceDefinition crd;


    /**
     * Constructor
     * @param vertx The Vertx instance
     * @param client The Kubernetes client
     * @param cls The class of the CR
     * @param listCls The class of the list.
     * @param crd The CustomResourceDefinition of the CR
     */
    public CrdOperator(Vertx vertx, C client, Class<T> cls, Class<L> listCls, CustomResourceDefinition crd) {
        super(vertx, client, crd.getSpec().getNames().getKind());
        this.cls = cls;
        this.listCls = listCls;
        this.plural = crd.getSpec().getNames().getPlural();
        this.crd = crd;
    }

    @Override
    protected MixedOperation<T, L, Resource<T>> operation() {
        return client.customResources(CustomResourceDefinitionContext.fromCrd(crd), cls, listCls);
    }

    /**
     * The selfClosingWatch does not work for Custom Resources. Therefore we override the method and delete custom
     * resources without it.
     *
     * @param namespace Namespace of the resource which should be deleted
     * @param name Name of the resource which should be deleted
     * @param cascading Defines whether the delete should be cascading or not (e.g. whether a STS deletion should delete pods etc.)
     *
     * @return A future which will be completed on the context thread
     *         once the resource has been deleted.
     */
    @Override
    protected Future<ReconcileResult<T>> internalDelete(String namespace, String name, boolean cascading) {
        Resource<T> resourceOp = operation().inNamespace(namespace).withName(name);

        Future<Void> watchForDeleteFuture = Util.waitFor(vertx,
            String.format("%s resource %s", resourceKind, name),
            "deleted",
            1_000,
            deleteTimeoutMs(),
            () -> resourceOp.get() != null);

        Future<Void> deleteFuture = resourceSupport.deleteAsync(resourceOp.withPropagationPolicy(cascading ? DeletionPropagation.FOREGROUND : DeletionPropagation.ORPHAN).withGracePeriod(-1L));

        return CompositeFuture.join(watchForDeleteFuture, deleteFuture).map(ReconcileResult.deleted());
    }

    public Future<T> patchAsync(T resource) {
        return patchAsync(resource, true);
    }

    public Future<T> patchAsync(T resource, boolean cascading) {
        Promise<T> blockingPromise = Promise.promise();

        vertx.createSharedWorkerExecutor("kubernetes-ops-pool").executeBlocking(future -> {
            String namespace = resource.getMetadata().getNamespace();
            String name = resource.getMetadata().getName();
            try {
                T result = operation().inNamespace(namespace).withName(name).withPropagationPolicy(cascading ? DeletionPropagation.FOREGROUND : DeletionPropagation.ORPHAN).patch(resource);
                log.debug("{} {} in namespace {} has been patched", resourceKind, name, namespace);
                future.complete(result);
            } catch (Exception e) {
                log.debug("Caught exception while patching {} {} in namespace {}", resourceKind, name, namespace, e);
                future.fail(e);
            }
        }, true, blockingPromise);

        return blockingPromise.future();
    }

    public Future<T> updateStatusAsync(T resource) {
        Promise<T> blockingPromise = Promise.promise();

        vertx.createSharedWorkerExecutor("kubernetes-ops-pool").executeBlocking(future -> {
            try {

                OkHttpClient client = this.client.adapt(OkHttpClient.class);
                RequestBody postBody = RequestBody.create(OperationSupport.JSON, new ObjectMapper().writeValueAsString(resource));

                Request request = new Request.Builder().put(postBody).url(
                        this.client.getMasterUrl().toString() + "apis/" + resource.getApiVersion() + "/namespaces/" + resource.getMetadata().getNamespace()
                                + "/" + this.plural + "/" + resource.getMetadata().getName() + "/status").build();

                String method = request.method();
                Response response = client.newCall(request).execute();
                T returnedResource = null;
                try {
                    final int code = response.code();

                    if (code == 422)    {
                        Status status = OperationSupport.createStatus(response);

                        if (status != null
                                && status.getDetails() != null
                                && status.getDetails().getCauses() != null
                                && status.getDetails().getCauses().size() > 0
                                && status.getDetails().getCauses().stream().filter(cause -> "FieldValueInvalid".equals(cause.getReason()) && "apiVersion".equals(cause.getField())).findAny().orElse(null) != null)  {
                            log.debug("Got semi-expected {} status code {}: {}", method, code, status);
                            log.warn("Cannot update status of resource {} named {}. The resource needs to be updated to newer apiVersion first.", resource.getKind(), resource.getMetadata().getName());
                        } else {
                            log.debug("Got unexpected {} status code {}: {}", method, code, status);
                            throw OperationSupport.requestFailure(request, status);
                        }
                    } else if (code != 200) {
                        Status status = OperationSupport.createStatus(response);
                        log.debug("Got unexpected {} status code {}: {}", method, code, status);
                        throw OperationSupport.requestFailure(request, status);
                    } else {
                        // Success!
                        if (response.body() != null) {
                            try (InputStream bodyInputStream = response.body().byteStream()) {
                                returnedResource = Serialization.unmarshal(bodyInputStream, cls, Collections.emptyMap());
                            }
                        }
                    }
                } finally {
                    // Only messages with body should be closed
                    if (response.body() != null) {
                        response.close();
                    }
                }
                future.complete(returnedResource);
            } catch (IOException | RuntimeException e) {
                log.debug("Updating status failed", e);
                future.fail(e);
            }
        }, true, blockingPromise);

        return blockingPromise.future();
    }
}
