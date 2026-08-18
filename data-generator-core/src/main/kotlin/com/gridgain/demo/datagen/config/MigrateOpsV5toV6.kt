package com.gridgain.demo.datagen.config

/**
 * v5 -> v6: the `metrics:` block gains the whole-run latency histogram's bounds,
 * `histogram_highest_ms` and `histogram_significant_digits`, both required.
 *
 * Unlike every migration before it this one rewrites values, because the two keys are required and
 * a v5 file that declares `metrics:` has neither. The values written are the ones the JSONSchema
 * records as recommended: a 60s ceiling (an operation slower than a minute is pathological for a
 * load test) and three significant digits (HdrHistogram's standard 0.1% precision).
 *
 * Writing them here is a *migration* value, not a parse-time fallback — which is the distinction the
 * comprehensive-configuration-file policy turns on. After this runs the file states both bounds
 * explicitly, and [MetricsSpec] still refuses to deserialize a document that omits them.
 *
 * A file with no `metrics:` block is left alone: absence means no live export, and inventing a block
 * would start publishing to a broker nobody configured. An explicitly-present bound is preserved, so
 * hand-tuning ahead of the migration is not reverted.
 */
class MigrateOpsV5toV6 : ConfigMigration {
    override val fromVersion: Int = 5
    override val toVersion: Int = 6
    override val description: String =
        "add required latency-histogram bounds to the metrics block"

    override fun migrate(yaml: MutableMap<String, Any>): MutableMap<String, Any> {
        @Suppress("UNCHECKED_CAST")
        val metrics = yaml["metrics"] as? MutableMap<String, Any> ?: return yaml
        metrics.putIfAbsent("histogram_highest_ms", DEFAULT_HIGHEST_MS)
        metrics.putIfAbsent("histogram_significant_digits", DEFAULT_SIGNIFICANT_DIGITS)
        return yaml
    }

    private companion object {
        /** Keep in step with the `default` recorded in `schema/ops/v6.schema.json`. */
        const val DEFAULT_HIGHEST_MS = 60_000
        const val DEFAULT_SIGNIFICANT_DIGITS = 3
    }
}
