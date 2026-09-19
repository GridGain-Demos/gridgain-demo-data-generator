package com.gridgain.demo.datagen.brokers

import com.gridgain.demo.datagen.config.AddressBrokerRef
import com.gridgain.demo.datagen.config.ElementBrokerRef
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Resolving `broker: { kind: element, name: ... }` against the `broker-endpoints.yaml` the toolkit
 * writes when it deploys a message broker.
 *
 * The failures matter more than the happy path. A run that cannot resolve its metrics broker must
 * say so loudly and early — a silently disabled channel looks exactly like a working demo that
 * happens to report nothing, which is the failure this whole mechanism exists to remove.
 */
class BrokerDirectoryTest {

    @TempDir
    lateinit var tempDir: Path

    private fun write(text: String): Path {
        val f = tempDir.resolve("broker-endpoints.yaml")
        Files.writeString(f, text.trimIndent())
        return f
    }

    private fun validFile(): Path = write(
        """
        schema_version: 1
        brokers:
        - name: audit-bus
          deployment_kind: hosts
          type: kafka
          bootstrap_servers: 10.30.0.9:9092
        - name: payments-bus
          deployment_kind: hosts
          type: kafka
          bootstrap_servers: 10.30.0.4:9092
        """
    )

    // ---------------------------------------------------------------- address refs

    @Test
    fun `a literal address resolves without any directory at all`() {
        // The standalone case: no toolkit, no endpoints file. This branch is what keeps the
        // generator's independence from the plugin a real property rather than a stated one.
        val resolved = BrokerDirectory.notSupplied()
            .bootstrapServersFor(AddressBrokerRef("192.168.1.5:9092"), channel = "metrics")

        assertThat(resolved).isEqualTo("192.168.1.5:9092")
    }

    @Test
    fun `a literal address is used verbatim even when a directory is present`() {
        val resolved = BrokerDirectory.load(BrokerEndpointsSource.At(validFile()))
            .bootstrapServersFor(AddressBrokerRef("192.168.1.5:9092"), channel = "control")

        assertThat(resolved).isEqualTo("192.168.1.5:9092")
    }

    // ---------------------------------------------------------------- element refs

    @Test
    fun `an element ref resolves to the deployed broker's address`() {
        val resolved = BrokerDirectory.load(BrokerEndpointsSource.At(validFile()))
            .bootstrapServersFor(ElementBrokerRef("payments-bus"), channel = "metrics")

        assertThat(resolved).isEqualTo("10.30.0.4:9092")
    }

    @Test
    fun `an element ref with no directory supplied names the flag and both remedies`() {
        assertThatThrownBy {
            BrokerDirectory.notSupplied()
                .bootstrapServersFor(ElementBrokerRef("payments-bus"), channel = "metrics")
        }
            .hasMessageContaining("metrics")
            .hasMessageContaining("payments-bus")
            .hasMessageContaining("--broker-endpoints")
            .hasMessageContaining("kind: address")
    }

    @Test
    fun `an unknown name says a broker appears here only once deployed, and lists what is`() {
        assertThatThrownBy {
            BrokerDirectory.load(BrokerEndpointsSource.At(validFile()))
                .bootstrapServersFor(ElementBrokerRef("ghost-bus"), channel = "control")
        }
            .hasMessageContaining("control")
            .hasMessageContaining("ghost-bus")
            .hasMessageContaining("deployed")
            // Listing what is present turns "why can't it find it" into "I misspelled it".
            .hasMessageContaining("audit-bus")
            .hasMessageContaining("payments-bus")
    }

    @Test
    fun `an empty directory still names the broker rather than reporting an empty list`() {
        val empty = write("schema_version: 1\nbrokers: []")

        assertThatThrownBy {
            BrokerDirectory.load(BrokerEndpointsSource.At(empty))
                .bootstrapServersFor(ElementBrokerRef("payments-bus"), channel = "metrics")
        }
            .hasMessageContaining("payments-bus")
            .hasMessageContaining("(none)")
    }

    @Test
    fun `a broker of the wrong product is refused by name, not handed to a Kafka client`() {
        val pulsar = write(
            """
            schema_version: 1
            brokers:
            - name: payments-bus
              deployment_kind: hosts
              type: pulsar
              bootstrap_servers: 10.30.0.4:6650
            """
        )

        assertThatThrownBy {
            BrokerDirectory.load(BrokerEndpointsSource.At(pulsar))
                .bootstrapServersFor(ElementBrokerRef("payments-bus"), channel = "metrics")
        }
            .hasMessageContaining("payments-bus")
            .hasMessageContaining("pulsar")
            .hasMessageContaining("kafka")
    }

    // ---------------------------------------------------------------- loading

    @Test
    fun `a future schema version is refused by version, naming both`() {
        val future = write(
            """
            schema_version: 2
            brokers: []
            """
        )

        assertThatThrownBy { BrokerDirectory.load(BrokerEndpointsSource.At(future)) }
            .hasMessageContaining("schema_version=2")
            .hasMessageContaining("schema_version=1")
    }

    @Test
    fun `a path that does not exist is refused at load, not at first lookup`() {
        // Eager, because the toolkit only passes the flag when it wrote the file. A missing path
        // means the two disagree, and that is worth saying before the run does any work.
        assertThatThrownBy {
            BrokerDirectory.load(BrokerEndpointsSource.At(tempDir.resolve("absent.yaml")))
        }.hasMessageContaining("absent.yaml")
    }

    @Test
    fun `a corrupted file names itself rather than failing on a cast`() {
        val bad = write("brokers: [ this is not: valid: yaml")

        assertThatThrownBy { BrokerDirectory.load(BrokerEndpointsSource.At(bad)) }
            .hasMessageContaining("broker-endpoints.yaml")
    }

    @Test
    fun `declaredNames reports what the directory holds, sorted`() {
        assertThat(BrokerDirectory.load(BrokerEndpointsSource.At(validFile())).declaredNames())
            .containsExactly("audit-bus", "payments-bus")
        assertThat(BrokerDirectory.notSupplied().declaredNames()).isEmpty()
    }
}
