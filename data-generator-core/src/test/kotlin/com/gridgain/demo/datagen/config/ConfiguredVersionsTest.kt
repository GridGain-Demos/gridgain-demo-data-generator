package com.gridgain.demo.datagen.config

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class ConfiguredVersionsTest {
    @Test fun `state schema version is pinned at 2`() {
        assertThat(CURRENT_STATE_SCHEMA_VERSION).isEqualTo(2)
    }
    @Test fun `data and ops versions remain at 2`() {
        assertThat(CURRENT_DATA_SCHEMA_VERSION).isEqualTo(2)
        assertThat(CURRENT_OPS_SCHEMA_VERSION).isEqualTo(2)
    }
}
