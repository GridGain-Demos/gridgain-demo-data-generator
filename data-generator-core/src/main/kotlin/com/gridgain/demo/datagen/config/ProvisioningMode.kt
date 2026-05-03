package com.gridgain.demo.datagen.config

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Per-scenario provisioning toggle. Default `SKIP` matches the spec (§4).
 *
 * NOTE: this enum carries a default of `SKIP` in violation of the workspace project rule
 * "no defaults on template classes" — same documented exception as `ScenarioSpec.transactionScope`.
 * The default is the safest value (no side effect).
 */
enum class ProvisioningMode {
    @JsonProperty("skip") SKIP,
    @JsonProperty("emit") EMIT,
    @JsonProperty("apply") APPLY,
}
