package com.gridgain.demo.datagen.metrics

import java.nio.ByteBuffer
import java.util.Base64
import org.HdrHistogram.AbstractHistogram
import org.HdrHistogram.Histogram

/**
 * The wire form of a latency histogram: HdrHistogram's compressed encoding, base64'd so it rides
 * inside the JSON [MetricsSnapshot] on the Kafka metrics topic.
 *
 * The **encoding** is the contract between the generator and its consumers, not the histogram
 * class — which is why this is the only place either side base64s or compresses. The UI mirrors
 * [decode] rather than sharing this file, for the same reason it mirrors [MetricsSnapshot]: the two
 * projects deploy independently.
 *
 * Percentiles do not compose, so a consumer merges the decoded histograms rather than averaging
 * published percentiles — that is why the histogram itself is on the wire and no scalar p90/p99 is.
 */
object HistogramCodec {

    /**
     * Base64 of [histogram]'s compressed encoding.
     *
     * The buffer this allocates scales with the histogram's significant-digits precision (see
     * [LatencyHistogramBounds]), not with how many samples it recorded. At the recommended 3
     * digits the buffer is about 156 KB — cheap enough to call on every metrics tick. At the
     * configurable maximum of 5 digits it grows to about 11.8 MB per call to hold ~280 KB of
     * actual output — still safe, but no longer "cheap"; that is one more reason 3 is the
     * recommended value, not just the default.
     */
    fun encode(histogram: AbstractHistogram): String {
        val buffer = ByteBuffer.allocate(histogram.neededByteBufferCapacity)
        histogram.encodeIntoCompressedByteBuffer(buffer)
        buffer.flip()
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return Base64.getEncoder().encodeToString(bytes)
    }

    /**
     * The inverse of [encode].
     *
     * Throws [IllegalArgumentException] on anything that is not a histogram this codec wrote.
     * Failing loudly is the point: a consumer that treats garbage as an empty histogram would
     * report a p90 of zero as though it had measured it.
     */
    fun decode(encoded: String): Histogram {
        val bytes = try {
            Base64.getDecoder().decode(encoded)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException(
                "Latency histogram is not valid base64 (${e.message}). Expected the base64 of an " +
                    "HdrHistogram compressed encoding, as written by HistogramCodec.encode.",
                e,
            )
        }
        return try {
            Histogram.decodeFromCompressedByteBuffer(ByteBuffer.wrap(bytes), 0L)
        } catch (e: Exception) {
            // Not classified further: the exception type doesn't reliably distinguish these causes
            // (an empty/absent field and a truncated message both surface as a null-message
            // BufferUnderflowException or ArrayIndexOutOfBoundsException), so naming all three
            // candidates is more honest than guessing which one happened.
            val detail = e.message ?: e::class.java.simpleName
            throw IllegalArgumentException(
                "Latency histogram base64 decoded, but its ${bytes.size} bytes are not an " +
                    "HdrHistogram compressed encoding ($detail). Likely causes: the histogram field " +
                    "was empty or missing before encoding, the message was truncated in transit, or " +
                    "the publisher and consumer are on different HdrHistogram majors.",
                e,
            )
        }
    }
}
