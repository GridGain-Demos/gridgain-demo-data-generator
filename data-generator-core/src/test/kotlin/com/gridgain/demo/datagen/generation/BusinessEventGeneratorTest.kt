package com.gridgain.demo.datagen.generation

import com.gridgain.demo.datagen.config.ColumnSpec
import com.gridgain.demo.datagen.config.CohortBucket
import com.gridgain.demo.datagen.config.DataConfig
import com.gridgain.demo.datagen.config.DataFakerSpec
import com.gridgain.demo.datagen.config.ParentFkRefSpec
import com.gridgain.demo.datagen.config.SchemaSpec
import com.gridgain.demo.datagen.config.SequenceSpec
import net.datafaker.Faker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test

class BusinessEventGeneratorTest {

    private fun col(name: String, vs: com.gridgain.demo.datagen.config.ValueSourceSpec) =
        ColumnSpec(name = name, nullRate = 0.0, valueSource = vs)

    @Test
    fun `emits a parent row with cohort-distributed children`(@TempDir dir: Path) {
        val customer = SchemaSpec("customer", 0.0, listOf(
            col("id", SequenceSpec(1, 1)),
            col("name", DataFakerSpec("#{name.firstName}")),
        ))
        val order = SchemaSpec("order", 0.0, listOf(
            col("customer_id", ParentFkRefSpec("customer", "id", listOf(
                CohortBucket(share = 0.10, multiplier = 100),
                CohortBucket(share = 0.40, multiplier = 10),
                CohortBucket(share = 0.50, multiplier = 1),
            ))),
            col("amount", DataFakerSpec("#{number.numberBetween '1' '1000'}")),
        ))
        val data = DataConfig(schemaVersion = 2, schemas = listOf(customer, order))
        val factory = ValueSourceFactory(yamlDataRoot = dir, seed = 42L)
        val gen = BusinessEventGenerator(
            data = data,
            rootSchemaName = "customer",
            factory = factory,
            faker = Faker(),
            cohortSeed = 7L,
        )

        var totalOrders = 0
        var whales = 0
        var mids = 0
        var guppies = 0
        repeat(1000) {
            val event = gen.next()
            assertThat(event.parentRow["id"]).isInstanceOf(Long::class.javaObjectType)
            val orders = event.childrenBySchema["order"]!!
            totalOrders += orders.size
            when (orders.size) {
                100 -> whales++
                10 -> mids++
                1 -> guppies++
            }
            for (orow in orders) {
                assertThat(orow["customer_id"]).isEqualTo(event.parentRow["id"])
            }
        }
        assertThat(whales).isBetween(80, 120)
        assertThat(mids).isBetween(370, 430)
        assertThat(guppies).isBetween(470, 530)
        assertThat(totalOrders).isBetween(13_000, 17_000)
    }

    @Test
    fun `event for a leaf schema produces no children`(@TempDir dir: Path) {
        val customer = SchemaSpec("customer", 0.0, listOf(
            col("id", SequenceSpec(1, 1)),
        ))
        val data = DataConfig(schemaVersion = 2, schemas = listOf(customer))
        val gen = BusinessEventGenerator(
            data = data,
            rootSchemaName = "customer",
            factory = ValueSourceFactory(yamlDataRoot = dir, seed = 1L),
            faker = Faker(),
            cohortSeed = 1L,
        )
        val event = gen.next()
        assertThat(event.childrenBySchema).isEmpty()
        assertThat(event.parentRow["id"]).isEqualTo(1L)
    }

    @Test
    fun `unknown root schema is rejected`(@TempDir dir: Path) {
        val customer = SchemaSpec("customer", 0.0, listOf(col("id", SequenceSpec(1, 1))))
        val data = DataConfig(schemaVersion = 2, schemas = listOf(customer))
        org.assertj.core.api.Assertions.assertThatThrownBy {
            BusinessEventGenerator(
                data = data,
                rootSchemaName = "unknown",
                factory = ValueSourceFactory(yamlDataRoot = dir, seed = 1L),
                faker = Faker(),
                cohortSeed = 1L,
            )
        }
            .isInstanceOf(com.gridgain.demo.datagen.errors.MisconfigurationException::class.java)
            .hasMessageContaining("unknown")
    }
}
