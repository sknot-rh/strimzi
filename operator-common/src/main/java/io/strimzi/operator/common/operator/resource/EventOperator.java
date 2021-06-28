/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.common.operator.resource;

import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.EventList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.vertx.core.Vertx;

/**
 * Operations for {@code Event}s.
 */
public class EventOperator extends AbstractResourceOperator<KubernetesClient, Event, EventList, Resource<Event>> {
    /**
     * Constructor
     *
     * @param vertx The Vertx instance
     * @param client The OpenShift client
     */
    public EventOperator(Vertx vertx, KubernetesClient client) {
        super(vertx, client, "Event");
    }

    @Override
    protected MixedOperation<Event, EventList, Resource<Event>> operation() {
        return client.v1().events();
    }

    /*public void create(Event event) {
        operation().createOrReplace(event);
    }*/
}
