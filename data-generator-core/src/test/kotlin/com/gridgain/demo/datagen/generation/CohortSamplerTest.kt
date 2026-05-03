package com.gridgain.demo.datagen.generation

import com.gridgain.demo.datagen.config.CohortBucket
import com.gridgain.demo.datagen.errors.MisconfigurationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import kotlin.test.Test

class CohortSamplerTest {

    @Test
    fun `assigns counts that match bucket shares within tolerance`() {
        val buckets = listOf(
            CohortBucket(share = 0.10, multiplier = 100),
            CohortBucket(share = 0.40, multiplier = 10),
            CohortBucket(share = 0.50, multiplier = 1),
        )
        val counts = CohortSampler(seed = 7L).assign(parentCount = 1000, buckets = buckets)
        val whales = counts.count { it == 100 }
        val mids = counts.count { it == 10 }
        val guppies = counts.count { it == 1 }
        assertThat(whales + mids + guppies).isEqualTo(1000)
        assertThat(whales).isBetween(80, 120)
        assertThat(mids).isBetween(370, 430)
        assertThat(guppies).isBetween(470, 530)
    }

    @Test
    fun `multiplier of zero produces zero children for that bucket`() {
        val counts = CohortSampler(seed = 1L)
            .assign(parentCount = 100, buckets = listOf(CohortBucket(share = 1.0, multiplier = 0)))
        assertThat(counts.toSet()).containsExactly(0)
    }

    @Test
    fun `single bucket assigns all parents to it`() {
        val counts = CohortSampler(seed = 1L)
            .assign(parentCount = 50, buckets = listOf(CohortBucket(share = 1.0, multiplier = 7)))
        assertThat(counts.toSet()).containsExactly(7)
        assertThat(counts.size).isEqualTo(50)
    }

    @Test
    fun `bucket shares not summing to one are rejected`() {
        assertThatThrownBy {
            CohortSampler(seed = 1L).assign(
                parentCount = 10,
                buckets = listOf(
                    CohortBucket(share = 0.30, multiplier = 1),
                    CohortBucket(share = 0.30, multiplier = 5),
                ),
            )
        }
            .isInstanceOf(MisconfigurationException::class.java)
            .hasMessageContaining("0.6")
    }

    @Test
    fun `same seed produces identical assignment`() {
        val buckets = listOf(
            CohortBucket(share = 0.5, multiplier = 10),
            CohortBucket(share = 0.5, multiplier = 1),
        )
        val a = CohortSampler(seed = 99L).assign(parentCount = 50, buckets = buckets)
        val b = CohortSampler(seed = 99L).assign(parentCount = 50, buckets = buckets)
        assertThat(a).isEqualTo(b)
    }
}
