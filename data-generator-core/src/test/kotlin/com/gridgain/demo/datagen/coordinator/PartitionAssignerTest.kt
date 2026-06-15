package com.gridgain.demo.datagen.coordinator

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class PartitionAssignerTest {

    @Test
    fun `assigns every partition to exactly one worker`() {
        val assignment = PartitionAssigner.assign(partitionCount = 16, workers = setOf("w1", "w2", "w3", "w4"))
        val allPartitions = assignment.values.flatten()
        assertThat(allPartitions).containsExactlyInAnyOrderElementsOf(0 until 16)
        assertThat(allPartitions).hasSize(16)
    }

    @Test
    fun `balances assignments evenly when partition_count divides cleanly`() {
        val assignment = PartitionAssigner.assign(partitionCount = 16, workers = setOf("w1", "w2", "w3", "w4"))
        assertThat(assignment.values.map { it.size }).containsOnly(4)
    }

    @Test
    fun `stragglers pick up the remainder when partitions divide unevenly`() {
        val assignment = PartitionAssigner.assign(partitionCount = 10, workers = setOf("a", "b", "c"))
        val sizes = assignment.values.map { it.size }.sorted()
        // 10 / 3 = 3 with remainder 1. Sizes are 3, 3, 4 (or some permutation).
        assertThat(sizes).containsExactly(3, 3, 4)
    }

    @Test
    fun `single worker gets all partitions`() {
        val assignment = PartitionAssigner.assign(partitionCount = 5, workers = setOf("sole"))
        assertThat(assignment).hasSize(1)
        assertThat(assignment["sole"]).containsExactlyInAnyOrderElementsOf(0 until 5)
    }

    @Test
    fun `empty worker set yields empty assignment map`() {
        val assignment = PartitionAssigner.assign(partitionCount = 4, workers = emptySet())
        assertThat(assignment).isEmpty()
    }

    @Test
    fun `assignment is deterministic for the same input set`() {
        val first = PartitionAssigner.assign(partitionCount = 12, workers = setOf("alpha", "beta", "gamma"))
        val second = PartitionAssigner.assign(partitionCount = 12, workers = setOf("gamma", "alpha", "beta"))
        // Same set, different iteration order at the call site -> still identical output.
        assertThat(first).isEqualTo(second)
    }

    @Test
    fun `rejects partition_count less than worker count`() {
        // Defense in depth: cross-element validation already enforces partition_count >= replicas,
        // but the runtime assigner should also fail closed.
        val ex = runCatching {
            PartitionAssigner.assign(partitionCount = 2, workers = setOf("w1", "w2", "w3"))
        }.exceptionOrNull()
        assertThat(ex).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(ex!!.message).contains("partition_count").contains("workers")
    }
}
