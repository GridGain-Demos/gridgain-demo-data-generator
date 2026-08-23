package com.gridgain.demo.datagen.config

/**
 * The cluster a run writes to, in the flavour of the entry point that built it.
 *
 * No longer deserialized from ops.yaml: v7 removed `targets:`, and the cluster now arrives on the
 * CLI as `--target-cluster`. Each `Main` constructs the one variant it can serve — `Gg8Main` a
 * [Gg8KvTargetSpec], `Gg9Main` a [Gg9KvTargetSpec] — which is why the old `kind` discriminator
 * carried no information the entry point did not already have.
 *
 * The sealed hierarchy is retained deliberately, per the workspace rule against collapsing
 * hierarchies: further target kinds are expected, and each will need flavour-specific fields
 * beyond the cluster name. Today no consumer takes a `TargetSpec` parameter — each `Main` builds
 * its variant and reads `clusterName` — so the hierarchy carries future shape, not present
 * behaviour.
 */
sealed class TargetSpec {
    abstract val clusterName: String
}

data class Gg8KvTargetSpec(override val clusterName: String) : TargetSpec()

data class Gg9KvTargetSpec(override val clusterName: String) : TargetSpec()
