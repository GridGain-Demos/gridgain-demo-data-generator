package com.gridgain.demo.datagen.target

import org.assertj.core.api.Assertions.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test

/**
 * Resolving a cache handle once per schema instead of once per operation.
 *
 * `Gg8KvTarget.putRow` and `.read` both called `IgniteClient.getOrCreateCache(name)` on **every
 * operation**. On a thin client that is not a map lookup — it is a remote cache-lifecycle round
 * trip to the cluster, and it sat inside the timed section of every generator op.
 *
 * Measured on the Power lab 2026-09-20 with a standalone probe against the same cluster, 64
 * blocking threads on one connection: resolving the handle once gave **165,993 ops/s at 0.385 ms**;
 * resolving it per operation gave **95,294 ops/s at 0.671 ms**. A ~40% throughput tax for a value
 * that never changes.
 *
 * (That probe also proved the per-op lookup is *not* the generator's much larger concurrency
 * collapse — 52,000 ops/s to 70 above ~16 operations in flight — which is still open. Fixing this
 * is worth doing on its own merits; it is not that fix.)
 */
class CacheHandlesTest {

    @Test
    fun `a schema is resolved once, however many times it is asked for`() {
        val resolutions = AtomicInteger()
        val handles = CacheHandles<String> { name -> resolutions.incrementAndGet(); "cache:$name" }

        repeat(1_000) { assertThat(handles["customer"]).isEqualTo("cache:customer") }

        assertThat(resolutions.get())
            .describedAs("one remote getOrCreateCache, not a thousand")
            .isEqualTo(1)
    }

    @Test
    fun `each schema gets its own handle`() {
        val handles = CacheHandles<String> { name -> "cache:$name" }

        assertThat(handles["customer"]).isEqualTo("cache:customer")
        assertThat(handles["address"]).isEqualTo("cache:address")
    }

    @Test
    fun `concurrent callers still resolve a schema exactly once`() {
        // The real caller is every worker thread of the run at once, on first touch of a schema.
        // `computeIfAbsent` is what makes this exactly-once rather than merely eventually-cached;
        // a get-then-put would let 64 threads each fire the round trip this exists to avoid.
        val resolutions = AtomicInteger()
        val handles = CacheHandles<String> { name ->
            resolutions.incrementAndGet()
            Thread.sleep(20)  // a remote call takes time; that is when the race happens
            "cache:$name"
        }
        val threads = 32
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)

        val results = (1..threads).map {
            pool.submit<String> { start.await(); handles["customer"] }
        }
        start.countDown()
        val values = results.map { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        assertThat(resolutions.get()).isEqualTo(1)
        assertThat(values).containsOnly("cache:customer")
    }

    @Test
    fun `clearing forgets every handle, so a new client is not handed a stale one`() {
        // The handles belong to one IgniteClient. When the target closes that client the handles
        // are dead, and holding them would hand a later client a cache bound to a closed one.
        val resolutions = AtomicInteger()
        val handles = CacheHandles<String> { name -> resolutions.incrementAndGet(); "cache:$name" }

        handles["customer"]
        handles.clear()
        handles["customer"]

        assertThat(resolutions.get()).isEqualTo(2)
    }
}
