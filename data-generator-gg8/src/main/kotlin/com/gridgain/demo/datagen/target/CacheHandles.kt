package com.gridgain.demo.datagen.target

import java.util.concurrent.ConcurrentHashMap

/**
 * One cache handle per schema, resolved on first use and kept.
 *
 * Exists because [Gg8KvTarget] used to call `IgniteClient.getOrCreateCache(name)` inside every
 * `putRow` and every `read`. On a thin client that is **not** a local map lookup: it is a remote
 * cache-lifecycle round trip to the cluster, and it sat inside the section of each operation the
 * generator times and reports as its latency.
 *
 * Measured on the Power lab 2026-09-20, standalone probe against the same cluster, 64 blocking
 * threads on one connection:
 *
 *     handle resolved once per schema   165,993 ops/s   0.385 ms
 *     handle resolved per operation      95,294 ops/s   0.671 ms
 *
 * A ~40% throughput tax for a value that does not change. (The same probe showed this is *not*
 * the generator's larger concurrency collapse above ~16 operations in flight, which is still
 * open — so do not read a fix for that into this one.)
 *
 * [get] is `computeIfAbsent`, not get-then-put, which is what makes it exactly once rather than
 * merely eventually cached: every worker thread of a run touches a schema for the first time at
 * the same moment, and a racy version would fire the round trip once per thread — precisely the
 * cost this removes.
 *
 * @param resolve how to obtain a handle for a schema; called at most once per schema per [clear].
 */
internal class CacheHandles<T : Any>(private val resolve: (String) -> T) {

    private val bySchema = ConcurrentHashMap<String, T>()

    operator fun get(schemaName: String): T = bySchema.computeIfAbsent(schemaName, resolve)

    /**
     * Forgets every handle.
     *
     * The handles belong to one `IgniteClient`. When the target closes that client they are dead,
     * and keeping them would hand a later client a cache bound to a closed one.
     */
    fun clear() = bySchema.clear()
}
