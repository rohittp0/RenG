package com.rohittp.reng.internal.resource

import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceLocator
import com.rohittp.reng.internal.requireUnicodeScalars

internal class RentilePrivateKey(token: String) {
    private val token: String = requireUnicodeScalars(token, "privateRentileKey", nonBlank = true)

    override fun equals(other: Any?): Boolean =
        other is RentilePrivateKey && token == other.token

    override fun hashCode(): Int = token.hashCode()

    override fun toString(): String = "RentilePrivateKey(<redacted>)"
}

internal fun interface RentilePrivateKeyResolver {
    fun resolve(
        locator: ResourceLocator,
        resourceClass: ResourceClass,
    ): RentilePrivateKey
}
