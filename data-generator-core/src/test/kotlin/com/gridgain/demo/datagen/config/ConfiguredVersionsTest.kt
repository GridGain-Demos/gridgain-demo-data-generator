package com.gridgain.demo.datagen.config

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class ConfiguredVersionsTest {
    @Test fun `state schema version is pinned at 2`() {
        assertThat(CURRENT_STATE_SCHEMA_VERSION).isEqualTo(2)
    }
    @Test fun `data version is at 3 and ops version is at 9`() {
        // data v3 added per-schema `backups` and `write_synchronization_mode`.
        assertThat(CURRENT_DATA_SCHEMA_VERSION).isEqualTo(3)
        assertThat(CURRENT_OPS_SCHEMA_VERSION).isEqualTo(9)
    }
}
