package com.gridgain.demo.datagen.scenario

import java.util.concurrent.atomic.AtomicLong

/**
 * The shared pacing cursor every [RateLimiter] reserves its slots from.
 *
 * One generator process paces all of its worker threads from a single limiter, so the cursor is
 * shared mutable state under real contention. Two properties have to hold at once, and getting
 * either wrong is silent:
 *
 *  - **Each acquire claims its own slot.** If two threads read the same cursor value they wait for
 *    the same instant and fire together, and the process emits a multiple of the requested rate —
 *    a limiter that has stopped limiting while still reporting its target.
 *  - **The waiting happens outside the reservation.** Reserving under a lock held across the wait
 *    turns the limiter into a queue: workers stall on each other rather than on the clock, and the
 *    process paces as though it were single-threaded however many threads it has.
 *
 * A compare-and-set gives both. Retrying a lost race is correct rather than merely safe: the winner
 * has already moved the cursor past the instant this caller was about to take, so the right answer
 * is to take the next one.
 *
 * Extracted because there were four pacing implementations and only one of them — the override
 * branch of [ControllableRateLimiter] — had these properties. The other three were serialised
 * behind a lock by their wrapper, which is what a four-machine fleet measured as 128 worker threads
 * holding roughly 50 operations in flight.
 */
internal class PacingCursor(
    private val nanoTime: () -> Long,
    private val waiter: (Long) -> Unit,
) {
    private val nextAllowedNanos = AtomicLong(nanoTime())

    /**
     * Start pacing again from now, discarding any slots already reserved.
     *
     * For a rate change: without it, a long pause or a step down from a high rate leaves the cursor
     * far in the future and the first moments after the change are spent waiting out slots reserved
     * under the old rate — or, stepping the other way, produce a catch-up burst.
     */
    fun reset() {
        nextAllowedNanos.set(nanoTime())
    }

    /**
     * Reserve the next slot and wait for it.
     *
     * [intervalNanosAt] is asked for the pacing interval of a slot *starting at a given instant*
     * rather than told one, because a ramped or stepped schedule's interval depends on when the
     * slot falls — and the slot is only known once it has been won.
     */
    fun acquire(intervalNanosAt: (Long) -> Long) {
        while (true) {
            val reserved = nextAllowedNanos.get()
            val now = nanoTime()
            val deadline = maxOf(reserved, now)
            if (nextAllowedNanos.compareAndSet(reserved, deadline + intervalNanosAt(deadline))) {
                val wait = deadline - now
                if (wait > 0) waiter(wait)
                return
            }
        }
    }
}
