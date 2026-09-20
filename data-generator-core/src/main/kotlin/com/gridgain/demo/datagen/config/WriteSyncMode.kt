package com.gridgain.demo.datagen.config

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * When a write is allowed to return, relative to the copies it must reach.
 *
 * Declared here rather than reusing GridGain's `CacheWriteSynchronizationMode` because core must
 * stay free of the GG8 and GG9 client libraries — each target maps this to its own enum.
 *
 * The distinction is the whole point of asking for [SchemaSpec.backups]: with `PRIMARY_SYNC` a
 * `put` returns as soon as the primary has the row, so the backup is updated on the primary's own
 * time and the write latency shows none of the cost of replication. [FULL_SYNC] is what makes a
 * write wait for its replicas, which is what a demo of a replicated cluster is trying to show —
 * and it is the only mode under which the measured put latency means what a reader will assume.
 */
enum class WriteSyncMode {
    /** Return once the primary has the row; backups catch up behind the call. */
    @JsonProperty("primary_sync") PRIMARY_SYNC,

    /** Return once every backup has acknowledged. The honest mode for a replicated demo. */
    @JsonProperty("full_sync") FULL_SYNC,

    /** Return without waiting for anything. Fastest and least truthful; included for contrast. */
    @JsonProperty("full_async") FULL_ASYNC,
}
