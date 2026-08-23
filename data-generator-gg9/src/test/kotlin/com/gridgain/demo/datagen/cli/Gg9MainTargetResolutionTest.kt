package com.gridgain.demo.datagen.cli

import com.gridgain.demo.client.EndpointsFileLocator
import com.gridgain.demo.datagen.config.Gg9KvTargetSpec
import com.gridgain.demo.datagen.errors.MisconfigurationException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/**
 * Exercises [resolveTargetClusterOrThrow] directly rather than `main()`, which calls
 * `exitProcess` (see [Gg9MainProvisioningEventTest]'s note). Both cases here are testable
 * without a live cluster: resolution fails while reading `client-endpoints.yaml`, before any
 * network I/O is attempted.
 */
class Gg9MainTargetResolutionTest {

    private val savedEndpointsProp: String? = System.getProperty(EndpointsFileLocator.SYSTEM_PROPERTY)

    @AfterEach
    fun restoreProps() {
        if (savedEndpointsProp == null) {
            System.clearProperty(EndpointsFileLocator.SYSTEM_PROPERTY)
        } else {
            System.setProperty(EndpointsFileLocator.SYSTEM_PROPERTY, savedEndpointsProp)
        }
    }

    @Test
    fun `an unknown target cluster is wrapped as a MisconfigurationException naming the flag`(
        @TempDir dir: Path,
    ) {
        System.setProperty(EndpointsFileLocator.SYSTEM_PROPERTY, writeFixture(dir).toString())

        assertThatThrownBy { resolveTargetClusterOrThrow(Gg9KvTargetSpec("missing-cluster")) }
            .isInstanceOf(MisconfigurationException::class.java)
            .hasMessageContaining("--target-cluster")
            .hasMessageContaining("No cluster named 'missing-cluster'")
            .hasMessageContaining("known-gg9-cluster")
    }

    @Test
    fun `a cluster of the wrong GridGain major version is wrapped as a MisconfigurationException`(
        @TempDir dir: Path,
    ) {
        System.setProperty(EndpointsFileLocator.SYSTEM_PROPERTY, writeFixture(dir).toString())

        // known-gg8-cluster is declared major version 8; Gg9KvTargetSpec's finder requires 9.
        assertThatThrownBy { resolveTargetClusterOrThrow(Gg9KvTargetSpec("known-gg8-cluster")) }
            .isInstanceOf(MisconfigurationException::class.java)
            .hasMessageContaining("--target-cluster")
            .hasMessageContaining("gridgain_major_version=8")
            .hasMessageContaining("gridgain_major_version=9")
    }

    private fun writeFixture(dir: Path): Path {
        val file = dir.resolve("client-endpoints.yaml")
        Files.writeString(
            file,
            """
            schema_version: 2
            clusters:
              - name: known-gg9-cluster
                deployment_kind: k8s
                namespace: demo
                gridgain_major_version: 9
                contexts:
                  local:
                    addresses:
                      - localhost:10942
              - name: known-gg8-cluster
                deployment_kind: k8s
                namespace: demo
                gridgain_major_version: 8
                contexts:
                  local:
                    addresses:
                      - localhost:10800
            """.trimIndent(),
        )
        return file
    }
}
