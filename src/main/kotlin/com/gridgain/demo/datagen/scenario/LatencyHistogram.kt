package com.gridgain.demo.datagen.scenario

class LatencyHistogram {

    private val samples: MutableList<Long> = mutableListOf()

    fun record(nanos: Long) { samples.add(nanos) }

    fun quantile(p: Double): Long? {
        require(p in 0.0..1.0) { "quantile p must be in [0, 1]; got $p" }
        if (samples.isEmpty()) return null
        val sorted = samples.toLongArray().also { it.sort() }
        val rank = (p * (sorted.size - 1)).toInt()
        return sorted[rank]
    }
}
