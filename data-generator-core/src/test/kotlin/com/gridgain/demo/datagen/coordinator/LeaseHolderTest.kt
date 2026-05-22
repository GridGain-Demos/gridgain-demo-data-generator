package com.gridgain.demo.datagen.coordinator

import io.fabric8.kubernetes.api.model.coordination.v1.Lease
import io.fabric8.kubernetes.api.model.coordination.v1.LeaseBuilder
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.fabric8.kubernetes.client.KubernetesClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@EnableKubernetesMockClient(crud = true)
class LeaseHolderTest {

    private lateinit var server: KubernetesMockServer
    private lateinit var client: KubernetesClient

    @Test
    fun `acquires the lease when no other holder is present and fires onBecameLeader`() {
        val latch = CountDownLatch(1)
        val isLeader = AtomicBoolean(false)
        val holder = LeaseHolder(
            client = client,
            namespace = "default",
            leaseName = "data-generator-test-leader",
            identity = "pod-1",
            leaseDuration = Duration.ofSeconds(15),
            renewDeadline = Duration.ofSeconds(10),
            retryPeriod = Duration.ofSeconds(2),
            onBecameLeader = { isLeader.set(true); latch.countDown() },
            onLostLeadership = { isLeader.set(false) },
        )
        try {
            holder.start()
            assertThat(latch.await(10, TimeUnit.SECONDS)).`as`("onBecameLeader within 10s").isTrue
            assertThat(isLeader.get()).isTrue
        } finally {
            holder.stop()
        }
    }

    @Test
    fun `onNewLeader fires with the holder identity when an existing lease is held by another pod`() {
        // Pre-create a Lease held by some other pod with plenty of remaining duration. Our
        // LeaseHolder should observe that on its first poll and call onNewLeader without
        // attempting to take over.
        val now = java.time.ZonedDateTime.now()
        val existing: Lease = LeaseBuilder()
            .withNewMetadata().withName("data-generator-existing-leader").withNamespace("default").endMetadata()
            .withNewSpec()
                .withHolderIdentity("other-pod")
                .withLeaseDurationSeconds(60)
                .withAcquireTime(now)
                .withRenewTime(now)
                .withLeaseTransitions(1)
            .endSpec()
            .build()
        client.resource(existing).inNamespace("default").create()

        val observedLeader = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)
        val holder = LeaseHolder(
            client = client,
            namespace = "default",
            leaseName = "data-generator-existing-leader",
            identity = "pod-self",
            leaseDuration = Duration.ofSeconds(15),
            renewDeadline = Duration.ofSeconds(10),
            retryPeriod = Duration.ofSeconds(2),
            onBecameLeader = { /* must not fire */ },
            onLostLeadership = { /* must not fire */ },
            onNewLeader = { id -> observedLeader.set(id); latch.countDown() },
        )
        try {
            holder.start()
            assertThat(latch.await(10, TimeUnit.SECONDS)).`as`("onNewLeader within 10s").isTrue
            assertThat(observedLeader.get()).isEqualTo("other-pod")
        } finally {
            holder.stop()
        }
    }
}
