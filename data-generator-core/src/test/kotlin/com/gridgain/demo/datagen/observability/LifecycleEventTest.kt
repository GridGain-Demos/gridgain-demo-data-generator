package com.gridgain.demo.datagen.observability

import org.assertj.core.api.Assertions.assertThat
import java.nio.file.Paths
import kotlin.test.Test

class LifecycleEventTest {

    @Test fun `four spec-named events expose the expected name+attributes shape`() {
        val started = LifecycleEvent.ScenarioStarted("customer-load", "gg8")
        assertThat(started.name()).isEqualTo("scenario.started")
        assertThat(started.toAttributes())
            .containsEntry("scenario", "customer-load").containsEntry("target", "gg8")

        val stopped = LifecycleEvent.ScenarioStopped("customer-load", "count reached", 200, 0)
        assertThat(stopped.name()).isEqualTo("scenario.stopped")
        assertThat(stopped.toAttributes()).containsEntry("reason", "count reached")
            .containsEntry("success_count", "200").containsEntry("error_count", "0")

        val provisioned = LifecycleEvent.ProvisioningApplied("gg8", "apply", 0, 2, 1)
        assertThat(provisioned.name()).isEqualTo("provisioning.applied")
        assertThat(provisioned.toAttributes()).containsEntry("flavor", "gg8")
            .containsEntry("mode", "apply").containsEntry("created_count", "2")
            .containsEntry("existed_count", "1")

        val persisted = LifecycleEvent.StatePersisted(Paths.get("/tmp/state.yaml"), 1, 10, 1)
        assertThat(persisted.name()).isEqualTo("state.persisted")
        assertThat(persisted.toAttributes()).containsEntry("state_file", "/tmp/state.yaml")
            .containsEntry("sequence_count", "1").containsEntry("key_count", "10")
    }
}
