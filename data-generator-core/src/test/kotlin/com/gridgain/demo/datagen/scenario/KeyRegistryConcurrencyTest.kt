package com.gridgain.demo.datagen.scenario

import org.assertj.core.api.Assertions.assertThat
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test

/**
 * A scenario running with `concurrency > 1` has every worker thread registering the keys it
 * writes into one shared [KeyRegistry], so that any worker can later sample a key another worker
 * wrote for a read operation. That makes the registry the runner's most contended shared
 * structure.
 *
 * Its backing `mutableMapOf`/`mutableListOf` are plain `HashMap`/`ArrayList`. Concurrent
 * `register` on those does not merely lose updates — a racing `HashMap` resize can corrupt the
 * table outright — so this is tested as a hard invariant rather than left to chance.
 */
class KeyRegistryConcurrencyTest {

    private val threads = 8
    private val keysPerThread = 2_000

    @Test
    fun `concurrent registers lose no keys`() {
        val registry = KeyRegistry()
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val failure = AtomicReference<Throwable?>(null)

        (0 until threads).forEach { t ->
            Thread({
                try {
                    start.await()
                    // Disjoint key ranges, exactly as the partition stripe gives each worker.
                    (0 until keysPerThread).forEach { i ->
                        registry.register("customer", (t * keysPerThread + i).toLong())
                    }
                } catch (e: Throwable) {
                    failure.compareAndSet(null, e)
                } finally {
                    done.countDown()
                }
            }, "registrar-$t").apply { isDaemon = true }.start()
        }

        start.countDown()
        assertThat(done.await(30, TimeUnit.SECONDS))
            .describedAs("registration deadlocked or spun — a corrupted HashMap can loop forever")
            .isTrue()
        assertThat(failure.get()).isNull()

        assertThat(registry.size("customer"))
            .describedAs("every distinct key registered by every worker must be present exactly once")
            .isEqualTo(threads * keysPerThread)
        assertThat(registry.snapshot().single().keys)
            .describedAs("and the snapshot written to state.yaml must agree with the live size")
            .hasSize(threads * keysPerThread)
    }

    @Test
    fun `sampling while other workers register never observes a torn list`() {
        val registry = KeyRegistry()
        registry.register("customer", 0L)
        val stop = AtomicReference(false)
        val failure = AtomicReference<Throwable?>(null)
        val done = CountDownLatch(2)

        Thread({
            try {
                (1..50_000).forEach { registry.register("customer", it.toLong()) }
            } catch (e: Throwable) {
                failure.compareAndSet(null, e)
            } finally {
                stop.set(true); done.countDown()
            }
        }, "writer").apply { isDaemon = true }.start()

        Thread({
            val random = Random(1)
            try {
                while (!stop.get()) {
                    // A read op samples a key a different worker may be appending right now.
                    assertThat(registry.sample("customer", random)).isNotNull()
                }
            } catch (e: Throwable) {
                failure.compareAndSet(null, e)
            } finally {
                done.countDown()
            }
        }, "sampler").apply { isDaemon = true }.start()

        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue()
        assertThat(failure.get())
            .describedAs("sampling mid-append must not throw IndexOutOfBounds or return null")
            .isNull()
    }
}
