package com.gridgain.demo.datagen.coordinator

/**
 * Pure, deterministic partition-to-worker assignment. Lives outside the coordinator's
 * k8s plumbing so the algorithm is unit-testable on its own. The coordinator calls
 * [assign] whenever the live worker set changes (join, leave, lease handoff) and writes
 * the result into the shared assignment ConfigMap.
 *
 * The algorithm sorts workers by instance id and lays partitions across them
 * round-robin. With N workers and P >= N partitions, the worker at sorted index `i`
 * receives every partition `p` where `p % N == i`. This balances assignments to within
 * one partition and keeps results stable across calls with the same input.
 */
object PartitionAssigner {

    fun assign(partitionCount: Int, workers: Set<String>): Map<String, Set<Int>> {
        if (workers.isEmpty()) return emptyMap()
        require(partitionCount >= workers.size) {
            "partition_count=$partitionCount is less than workers=${workers.size}; " +
                "every worker must receive at least one partition. " +
                "Raise partition_count or lower replicas."
        }
        val sorted = workers.sorted()
        val result: MutableMap<String, MutableSet<Int>> = sorted.associateWith { mutableSetOf<Int>() }.toMutableMap()
        for (p in 0 until partitionCount) {
            val worker = sorted[p % sorted.size]
            result.getValue(worker).add(p)
        }
        return result.mapValues { it.value.toSet() }
    }
}
