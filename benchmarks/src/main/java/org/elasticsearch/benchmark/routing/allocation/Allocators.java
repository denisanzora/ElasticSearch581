/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */
package org.elasticsearch.benchmark.routing.allocation;

import org.elasticsearch.Version;
import org.elasticsearch.cluster.ClusterModule;
import org.elasticsearch.cluster.EmptyClusterInfoService;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodeRole;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.cluster.routing.allocation.AllocationService;
import org.elasticsearch.cluster.routing.allocation.FailedShard;
import org.elasticsearch.cluster.routing.allocation.RoutingAllocation;
import org.elasticsearch.cluster.routing.allocation.allocator.BalancedShardsAllocator;
import org.elasticsearch.cluster.routing.allocation.decider.AllocationDecider;
import org.elasticsearch.cluster.routing.allocation.decider.AllocationDeciders;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.common.util.set.Sets;
import org.elasticsearch.gateway.GatewayAllocator;
import org.elasticsearch.snapshots.EmptySnapshotsInfoService;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public final class Allocators {
    private static class NoopGatewayAllocator extends GatewayAllocator {
        public static final NoopGatewayAllocator INSTANCE = new NoopGatewayAllocator();

        @Override
        public void applyStartedShards(final List<ShardRouting> startedShards, final RoutingAllocation allocation) {
            // noop
        }

        @Override
        public void applyFailedShards(final List<FailedShard> failedShards, final RoutingAllocation allocation) {
            // noop
        }

        @Override
        public void allocateUnassigned(
            final ShardRouting shardRouting,
            final RoutingAllocation allocation,
            final UnassignedAllocationHandler unassignedAllocationHandler
        ) {
            // noop
        }
    }

    private Allocators() {
        throw new AssertionError("Do not instantiate");
    }

    public static AllocationService createAllocationService(final Settings settings) {
        return Allocators.createAllocationService(settings, new ClusterSettings(Settings.EMPTY, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS));
    }

    public static AllocationService createAllocationService(final Settings settings, final ClusterSettings clusterSettings) {
        return new AllocationService(
            Allocators.defaultAllocationDeciders(settings, clusterSettings),
            NoopGatewayAllocator.INSTANCE,
            new BalancedShardsAllocator(settings),
            EmptyClusterInfoService.INSTANCE,
            EmptySnapshotsInfoService.INSTANCE
        );
    }

    public static AllocationDeciders defaultAllocationDeciders(final Settings settings, final ClusterSettings clusterSettings) {
        final Collection<AllocationDecider> deciders = ClusterModule.createAllocationDeciders(settings, clusterSettings, Collections.emptyList());
        return new AllocationDeciders(deciders);
    }

    private static final AtomicInteger portGenerator = new AtomicInteger();

    public static DiscoveryNode newNode(final String nodeId, final Map<String, String> attributes) {
        return new DiscoveryNode(
            "",
            nodeId,
            new TransportAddress(TransportAddress.META_ADDRESS, Allocators.portGenerator.incrementAndGet()),
            attributes,
            Sets.newHashSet(DiscoveryNodeRole.MASTER_ROLE, DiscoveryNodeRole.DATA_ROLE),
            Version.CURRENT
        );
    }
}
