package com.rohittp.reng.internal.firewall

import com.rohittp.rentile.ResourceTransport as EngineResourceTransport
import com.rohittp.rentile.TransportRequest as EngineTransportRequest
import com.rohittp.rentile.TransportResponse as EngineTransportResponse

/**
 * ADR 0016's firewall, from Rentile's own side: the [EngineResourceTransport] implementation a
 * long-lived Rentile engine instance is constructed with. Every call multiplexes through
 * [registry] -- the one active preparation invocation's operation-scoped state -- rather than
 * forwarding straight to a consumer [com.rohittp.reng.Transport]. See [OperationRegistry] for the
 * actual join/latch/validate logic; this class is deliberately a one-line adapter over it.
 */
internal class FirewallTransport(private val registry: OperationRegistry) : EngineResourceTransport {
    override suspend fun execute(request: EngineTransportRequest): EngineTransportResponse =
        registry.executeTransport(request)
}
