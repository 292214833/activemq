/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.broker.region.policy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;

import org.apache.activemq.broker.Broker;
import org.apache.activemq.broker.ConnectionContext;
import org.apache.activemq.broker.region.Destination;
import org.apache.activemq.broker.region.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abort slow consumers when they reach the configured threshold of slowness,
 *
 * default is that a consumer that has not Ack'd a message for 30 seconds is slow.
 *
 * @org.apache.xbean.XBean
 */
public class AbortSlowAckConsumerStrategy extends AbortSlowConsumerStrategy {

    private static final Logger LOG = LoggerFactory.getLogger(AbortSlowAckConsumerStrategy.class);

    private final List<Destination> destinations = new LinkedList<Destination>();
    private long maxTimeSinceLastAck = 30*1000;
    private boolean ignoreIdleConsumers = true;
    private boolean ignoreNetworkConsumers = true;

    public AbortSlowAckConsumerStrategy() {
        this.name = "AbortSlowAckConsumerStrategy@" + hashCode();
    }

    @Override
    public void setBrokerService(Broker broker) {
        super.setBrokerService(broker);

        // Task starts right away since we may not receive any slow consumer events.
        if (taskStarted.compareAndSet(false, true)) {
            scheduler.executePeriodically(this, getCheckPeriod());
        }
    }

    @Override
    public void slowConsumer(ConnectionContext context, Subscription subs) {
        // Ignore these events, we just look at time since last Ack.
    }

    @Override
    public void run() {

        if (maxTimeSinceLastAck < 0) {
            // nothing to do
            LOG.info("no limit set, slowConsumer strategy has nothing to do");
            return;
        }

        if (getMaxSlowDuration() > 0) {
            // For subscriptions that are already slow we mark them again and check below if
            // they've exceeded their configured lifetime.
            for (SlowConsumerEntry entry : slowConsumers.values()) {
                entry.mark();
            }
        }

        List<Destination> disposed = new ArrayList<Destination>();

        for (Destination destination : destinations) {
            if (destination.isDisposed()) {
                disposed.add(destination);
                continue;
            }

            // Not explicitly documented but this returns a stable copy.
            List<Subscription> subscribers = destination.getConsumers();

            updateSlowConsumersList(subscribers);
        }

        // Clean up an disposed destinations to save space.
        destinations.removeAll(disposed);

        abortAllQualifiedSlowConsumers();
    }

    private void updateSlowConsumersList(List<Subscription> subscribers) {
        for (Subscription subscriber : subscribers) {

            if (subscriber.getConsumerInfo().isNetworkSubscription()) {
                if (slowConsumers.remove(subscriber) != null) {
                    LOG.info("sub: {} is no longer slow", subscriber.getConsumerInfo().getConsumerId());
                    continue;
                }
            }

            if (isIgnoreIdleConsumers() && subscriber.getDispatchedQueueSize() == 0) {
                // Not considered Idle so ensure its cleared from the list
                if (slowConsumers.remove(subscriber) != null) {
                    LOG.info("sub: {} is no longer slow", subscriber.getConsumerInfo().getConsumerId());
                    continue;
                } else {
                    if (LOG.isTraceEnabled()) {
                        LOG.trace("Not ignoring idle Consumer {}", subscriber.getConsumerInfo().getConsumerId());
                    }
                }
            }

            long lastAckTime = subscriber.getTimeOfLastMessageAck();
            long timeDelta = System.currentTimeMillis() - lastAckTime;

            if (timeDelta > maxTimeSinceLastAck) {
                if (!slowConsumers.containsKey(subscriber)) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("sub: {} is now slow", subscriber.getConsumerInfo().getConsumerId());
                    }
                    slowConsumers.put(subscriber, new SlowConsumerEntry(subscriber.getContext()));
                } else if (getMaxSlowCount() > 0) {
                    slowConsumers.get(subscriber).slow();
                }
            } else {
                if (slowConsumers.remove(subscriber) != null) {
                    LOG.info("sub: {} is no longer slow", subscriber.getConsumerInfo().getConsumerId());
                }
            }
        }
    }

    private void abortAllQualifiedSlowConsumers() {
        HashMap<Subscription, SlowConsumerEntry> toAbort = new HashMap<Subscription, SlowConsumerEntry>();
        for (Entry<Subscription, SlowConsumerEntry> entry : slowConsumers.entrySet()) {
            if (getMaxSlowDuration() > 0 &&
                (entry.getValue().markCount * getCheckPeriod() > getMaxSlowDuration()) ||
                getMaxSlowCount() > 0 && entry.getValue().slowCount > getMaxSlowCount()) {

                if (LOG.isTraceEnabled()) {
                    LOG.trace("Remove consumer {} from slow list: " +
                              "slow duration = " + entry.getValue().markCount * getCheckPeriod() + ", " +
                              "slow count = " + entry.getValue().slowCount,
                              entry.getKey().getConsumerInfo().getConsumerId());
                }

                toAbort.put(entry.getKey(), entry.getValue());
                slowConsumers.remove(entry.getKey());
            } else {
                if (LOG.isTraceEnabled()) {
                    LOG.trace("Not yet time to abot consumer {}: " +
                              "slow duration = " + entry.getValue().markCount * getCheckPeriod() + ", " +
                              "slow count = " + entry.getValue().slowCount,
                              entry.getKey().getConsumerInfo().getConsumerId());
                }
            }
        }

        // Now if any subscriptions made it into the aborts list we can kick them.
        abortSubscription(toAbort, isAbortConnection());
    }

    @Override
    public void addDestination(Destination destination) {
        this.destinations.add(destination);
    }

    /**
     * Gets the maximum time since last Ack before a subscription is considered to be slow.
     *
     * @return the maximum time since last Ack before the consumer is considered to be slow.
     */
    public long getMaxTimeSinceLastAck() {
        return maxTimeSinceLastAck;
    }

    /**
     * Sets the maximum time since last Ack before a subscription is considered to be slow.
     *
     * @param maxTimeSinceLastAck
     *      the maximum time since last Ack (mills) before the consumer is considered to be slow.
     */
    public void setMaxTimeSinceLastAck(long maxTimeSinceLastAck) {
        this.maxTimeSinceLastAck = maxTimeSinceLastAck;
    }

    /**
     * Returns whether the strategy is configured to ignore consumers that are simply idle, i.e
     * consumers that have no pending acks (dispatch queue is empty).
     *
     * @return true if the strategy will ignore idle consumer when looking for slow consumers.
     */
    public boolean isIgnoreIdleConsumers() {
        return ignoreIdleConsumers;
    }

    /**
     * Sets whether the strategy is configured to ignore consumers that are simply idle, i.e
     * consumers that have no pending acks (dispatch queue is empty).
     *
     * When configured to not ignore idle consumers this strategy acks not only on consumers
     * that are actually slow but also on any consumer that has not received any messages for
     * the maxTimeSinceLastAck.  This allows for a way to evict idle consumers while also
     * aborting slow consumers.
     *
     * @param ignoreIdleConsumers
     *      Should this strategy ignore idle consumers or consider all consumers when checking
     *      the last ack time verses the maxTimeSinceLastAck value.
     */
    public void setIgnoreIdleConsumers(boolean ignoreIdleConsumers) {
        this.ignoreIdleConsumers = ignoreIdleConsumers;
    }

    /**
     * Returns whether the strategy is configured to ignore subscriptions that are from a network
     * connection.
     *
     * @return true if the strategy will ignore network connection subscriptions when looking
     *         for slow consumers.
     */
    public boolean isIgnoreNetworkSubscriptions() {
        return ignoreNetworkConsumers;
    }

    /**
     * Sets whether the strategy is configured to ignore consumers that are part of a network
     * connection to another broker.
     *
     * When configured to not ignore idle consumers this strategy acts not only on consumers
     * that are actually slow but also on any consumer that has not received any messages for
     * the maxTimeSinceLastAck.  This allows for a way to evict idle consumers while also
     * aborting slow consumers however for a network subscription this can create a lot of
     * unnecessary churn and if the abort connection option is also enabled this can result
     * in the entire network connection being torn down and rebuilt for no reason.
     *
     * @param ignoreNetworkConsumers
     *      Should this strategy ignore subscriptions made by a network connector.
     */
    public void setIgnoreNetworkConsumers(boolean ignoreNetworkConsumers) {
        this.ignoreNetworkConsumers = ignoreNetworkConsumers;
    }

}
