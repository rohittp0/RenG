package com.rohittp.reng.internal.firewall

import com.rohittp.rentile.RawResourceKey as EngineRawResourceKey
import com.rohittp.rentile.RawResourceStore as EngineRawResourceStore
import com.rohittp.rentile.StoredRawResource as EngineStoredRawResource

/**
 * ADR 0016's firewall, from Rentile's own side: the [EngineRawResourceStore] implementation a
 * long-lived Rentile engine instance is constructed with. [remove] is private and terminal --
 * RenG's own [com.rohittp.reng.Store] has no remove operation at all, so there is nothing to forward
 * even in principle. See [OperationRegistry] for the actual join/latch/validate logic; this class is
 * deliberately a one-line adapter over it.
 */
internal class FirewallStore(private val registry: OperationRegistry) : EngineRawResourceStore {
    override suspend fun read(key: EngineRawResourceKey): EngineStoredRawResource? = registry.readStore(key)

    override suspend fun write(key: EngineRawResourceKey, resource: EngineStoredRawResource) {
        registry.writeStore(key, resource)
    }

    override suspend fun remove(key: EngineRawResourceKey) {
        registry.removeStore(key)
    }
}
