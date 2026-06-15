package com.gridgain.demo.datagen.coordinator

import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

@EnableKubernetesMockClient(crud = true)
class CoordinatorTest {

    private lateinit var server: KubernetesMockServer
    private lateinit var client: KubernetesClient

    @Test
    fun `parseAssignment round-trips a multi-line worker=parts string`() {
        // Use a Coordinator instance just for access to the internal parser. No start().
        val coord = Coordinator(
            client = client,
            namespace = "default", scenarioName = "test",
            instanceId = "pod-1", partitionCount = 4,
        )
        val rendered = """
            pod-a=0,1,2
            pod-b=3,4,5
            pod-c=6
        """.trimIndent()
        val parsed = coord.parseAssignment(rendered)
        assertThat(parsed.keys).containsExactlyInAnyOrder("pod-a", "pod-b", "pod-c")
        assertThat(parsed["pod-a"]).containsExactlyInAnyOrder(0, 1, 2)
        assertThat(parsed["pod-b"]).containsExactlyInAnyOrder(3, 4, 5)
        assertThat(parsed["pod-c"]).containsExactlyInAnyOrder(6)
    }

    @Test
    fun `parseAssignment skips blank lines and malformed entries`() {
        val coord = Coordinator(
            client = client,
            namespace = "default", scenarioName = "test",
            instanceId = "pod-1", partitionCount = 4,
        )
        val rendered = """

            pod-a=0,1
            this-line-has-no-equals
            =leading-empty-key,1
            pod-b=2,3
        """.trimIndent()
        val parsed = coord.parseAssignment(rendered)
        assertThat(parsed.keys).containsExactlyInAnyOrder("pod-a", "pod-b")
    }

    @Test
    fun `publishOwnStatus writes a labeled status ConfigMap with the instanceId`() {
        // Exercise the publish path directly without spinning up the executor / lease loop.
        val coord = Coordinator(
            client = client,
            namespace = "default",
            scenarioName = "smoke",
            instanceId = "pod-A",
            partitionCount = 2,
        )
        coord.publishOwnStatus()

        val cm = client.configMaps().inNamespace("default")
            .withName("dg-smoke-status-pod-A").get()
        assertThat(cm).`as`("status CM should have been created").isNotNull
        assertThat(cm.data["instanceId"]).isEqualTo("pod-A")
        assertThat(cm.data["lastSeen"]).`as`("lastSeen timestamp present").isNotNull
        assertThat(cm.metadata.labels)
            .containsEntry("app", "data-generator")
            .containsEntry("scenario", "smoke")
            .containsEntry("role", "status")
    }

    @Test
    fun `leader publishes an assignment ConfigMap once a worker status is visible`() {
        // Seed a status CM as if this pod had already published one heartbeat.
        val seedCoord = Coordinator(
            client = client,
            namespace = "default", scenarioName = "leader-seed",
            instanceId = "pod-self", partitionCount = 4,
        )
        seedCoord.publishOwnStatus()

        val coord = Coordinator(
            client = client,
            namespace = "default", scenarioName = "leader-seed",
            instanceId = "pod-self", partitionCount = 4,
        )
        // Force-leader path without spinning up Fabric8's LeaderElector (which would add
        // multi-second flake to a unit test). Poking the internal AtomicBoolean keeps the
        // test fast and deterministic; the production code reaches this state via
        // LeaseHolder's onBecameLeader callback.
        Coordinator::class.java.getDeclaredField("leaderFlag").apply { isAccessible = true }
            .let { (it.get(coord) as java.util.concurrent.atomic.AtomicBoolean).set(true) }
        coord.refreshAssignment()

        val cm = client.configMaps().inNamespace("default")
            .withName("dg-leader-seed-assignment").get()
        assertThat(cm).`as`("assignment CM should exist after leader refresh").isNotNull
        assertThat(cm.data["leader"]).isEqualTo("pod-self")
        assertThat(cm.data["assignment"]).contains("pod-self=0,1,2,3")
    }
}
