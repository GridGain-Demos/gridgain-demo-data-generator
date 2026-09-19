package com.gridgain.demo.datagen.config

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/**
 * Which broker a `metrics:` or `control:` channel talks to (v9+).
 *
 * Two disjoint ways of saying it, and the document states which it means — this is **not** a value
 * with a fallback. A reader never chooses between them and never guesses: the schema rejects a
 * block matching neither, and Jackson rejects an unrecognised `kind`.
 *
 * - [ElementBrokerRef] names a `message_brokers` element the demo toolkit deployed. The address is
 *   resolved at launch from the `broker-endpoints.yaml` the toolkit wrote, so no address is copied
 *   into this file and a broker redeployed at a new address is picked up on the next run. This is
 *   the form to prefer whenever the toolkit owns the broker.
 * - [AddressBrokerRef] carries a literal bootstrap address, for a broker this demo does **not**
 *   own — an operator's laptop, a pre-existing lab broker, a managed service. It is load-bearing
 *   rather than merely symmetrical: the generator must not depend on the plugin, and without this
 *   branch a standalone run could not use `metrics:`/`control:` at all.
 *
 * Replaces v8's `kafka_bootstrap: <address>`, which [MigrateOpsV8toV9] rewrites into
 * [AddressBrokerRef] — the v8 literal *is* the address branch, so nothing is interpreted.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes(
    JsonSubTypes.Type(value = ElementBrokerRef::class, name = "element"),
    JsonSubTypes.Type(value = AddressBrokerRef::class, name = "address"),
)
sealed class BrokerRef

/**
 * A `message_brokers` element by name, resolved from `broker-endpoints.yaml` at launch.
 *
 * [name] is the key under `message_brokers` in the toolkit's demo-config.yaml. A broker only
 * appears in the directory once it has been **deployed**, so naming a configured-but-undeployed
 * broker fails at launch rather than silently disabling the channel.
 */
data class ElementBrokerRef(val name: String) : BrokerRef()

/**
 * A literal bootstrap address, resolved from the **generator's** vantage point.
 *
 * In-cluster that is cluster-internal DNS a laptop cannot dial; on hosts it is a routable address.
 * A consumer elsewhere (the demo UI, say) resolves its own address to the same broker and does not
 * reuse this value.
 */
data class AddressBrokerRef(
    @JsonProperty("bootstrap_servers") val bootstrapServers: String,
) : BrokerRef()
