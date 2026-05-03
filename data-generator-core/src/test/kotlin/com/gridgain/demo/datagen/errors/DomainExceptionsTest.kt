package com.gridgain.demo.datagen.errors

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

class DomainExceptionsTest {

    @Test
    fun `MisconfigurationException carries message and cause`() {
        val cause = IllegalStateException("root")
        val ex = MisconfigurationException("bad config", cause)
        assertThat(ex).isInstanceOf(DomainException::class.java)
        assertThat(ex.message).isEqualTo("bad config")
        assertThat(ex.cause).isSameAs(cause)
    }

    @Test
    fun `CorruptedStateException carries message and cause`() {
        val ex = CorruptedStateException("state.yaml is corrupt")
        assertThat(ex).isInstanceOf(DomainException::class.java)
        assertThat(ex.message).isEqualTo("state.yaml is corrupt")
        assertThat(ex.cause).isNull()
    }

    @Test
    fun `DomainException is a RuntimeException`() {
        val ex = DomainException("anything")
        assertThat(ex).isInstanceOf(RuntimeException::class.java)
    }
}
