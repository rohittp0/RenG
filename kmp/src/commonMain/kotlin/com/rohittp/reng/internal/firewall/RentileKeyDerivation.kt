package com.rohittp.reng.internal.firewall

import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceLocator
import com.rohittp.reng.internal.identity.CanonicalBytes
import com.rohittp.reng.internal.identity.ResourceKeyDeriver
import com.rohittp.reng.internal.identity.Sha256Function
import com.rohittp.reng.internal.resource.RentilePrivateKey
import com.rohittp.reng.internal.resource.RentilePrivateKeyResolver
import com.rohittp.rentile.ResourceClass as RentileResourceClass

/**
 * Derives the private key ADR 0016's firewall latches Rentile requests under, for real, against the
 * actual Rentile 0.2.0/0.3.0 derivation -- the placeholder
 * [com.rohittp.reng.DeterministicRentilePrivateKeyResolver] this replaces only ever needed process-local
 * determinism, never agreement with Rentile's own cache keys.
 *
 * Rentile's raw-store paths (`VectorResourceAcquirer`, `RasterResourceAcquirer`,
 * `TileJsonResourceAcquirer`, `GeoJsonResourceAcquirer`, `SpriteResourceAcquirer`, all of which back
 * onto `RasterResourceAcquirer` for `DEM_TILE`) key every entry by
 * `sha256Hex(url.withRedactedAuthenticationQuery())` paired with Rentile's own [RentileResourceClass] --
 * verified byte-identical between the commit ADR 0016's respike measured and the 0.2.0 this build pins
 * (`docs/research/2026-08-19-rentile-030-counting-stub-respike.md`). RenG must reproduce that exact
 * derivation for the seven [ResourceClass] values Rentile itself fetches and keys, or RenG's own
 * diffing and eviction bookkeeping silently stops matching Rentile's actual cache entries: **a
 * permanent, unannounced cache miss, never a thrown failure** -- there is no failure surface for a
 * wrong-but-well-formed key to trip.
 *
 * The remaining four classes never reach Rentile's transport at all: [ResourceClass.BASEMAP_STYLE] has
 * no Rentile raw-store write (ADR 0016 -- style compiles privately and is never Rentile-cache-keyed),
 * and [ResourceClass.STICKER_IMAGE], [ResourceClass.MODEL_GLB], and [ResourceClass.MODEL_TEXTURE] are
 * fetched through RenG's own configured `Transport`/`Store`, never through Rentile's shared cache. For
 * those four, matching Rentile's derivation would be actively wrong: two resources differing only in a
 * credential (e.g. two stickers with different signed tokens) are distinct RenG resources, and
 * Rentile's redaction would collapse them into one key, corrupting preparation with
 * `AMBIGUOUS_RESOURCE_ROUTE`. Those four instead derive from RenG's own canonical resource identity
 * ([ResourceKeyDeriver.external]), which is already proven injective in locator and class.
 *
 * Rentile 0.3.0's ninth class, `GLYPH_RANGE`, is deliberately absent from [engineKeyedResourceClassOf]:
 * it is reachable only through `acquireLabelCandidates`, which RenG never calls this cycle. The
 * exhaustive `when` below fails to compile rather than silently routing a future class through the
 * wrong branch, matching the respike's own fail-closed instruction.
 */
internal class ProductionRentilePrivateKeyResolver(
    private val sha256: Sha256Function,
) : RentilePrivateKeyResolver {
    private val ownIdentityDeriver = ResourceKeyDeriver(sha256)

    override fun resolve(
        locator: ResourceLocator,
        resourceClass: ResourceClass,
    ): RentilePrivateKey {
        val engineClassName = engineKeyedResourceClassOf(resourceClass)?.name
        val token = if (engineClassName != null) {
            val redactedUrl = redactAuthenticationQuery(locator.value)
            "$engineClassName:${sha256Hex(redactedUrl)}"
        } else {
            val ownStableId = ownIdentityDeriver.external(resourceClass, locator).identity.digest.lowercaseHex
            "${resourceClass.name}:$ownStableId"
        }
        return RentilePrivateKey(token)
    }

    private fun sha256Hex(value: String): String =
        sha256.digest(CanonicalBytes(value.encodeToByteArray())).lowercaseHex
}

/**
 * The seven [ResourceClass] values Rentile itself fetches, stores, and keys, mapped to the exact
 * [RentileResourceClass] Rentile pairs with its `sha256Hex(withRedactedAuthenticationQuery(url))`
 * stable id. Referencing Rentile's own enum constants (rather than duplicating its member names as
 * literal strings) means a future rename or removal in Rentile fails this file's compilation instead of
 * silently deriving a key Rentile never asks for.
 *
 * [ResourceClass.BASEMAP_STYLE], [ResourceClass.STICKER_IMAGE], [ResourceClass.MODEL_GLB], and
 * [ResourceClass.MODEL_TEXTURE] return `null`: the engine never keys them, so [resolve] falls through
 * to RenG's own canonical identity for those four instead.
 *
 * `internal` rather than file-private because [OperationRegistry][com.rohittp.reng.internal.firewall.OperationRegistry]
 * (Cycle E basemap task 17) reuses this exact table to know which classes Rentile's own
 * `RawResourceStore` ever keys at all -- a store-side answer must fail closed for the four classes
 * this returns `null` for, since the engine's `RawResourceStore` never sees them.
 */
internal fun engineKeyedResourceClassOf(resourceClass: ResourceClass): RentileResourceClass? =
    when (resourceClass) {
        ResourceClass.BASEMAP_TILE_JSON -> RentileResourceClass.TILE_JSON
        ResourceClass.BASEMAP_VECTOR_TILE -> RentileResourceClass.VECTOR_TILE
        ResourceClass.BASEMAP_RASTER_TILE -> RentileResourceClass.RASTER_TILE
        ResourceClass.BASEMAP_DEM_TILE -> RentileResourceClass.DEM_TILE
        ResourceClass.BASEMAP_SPRITE_JSON -> RentileResourceClass.SPRITE_JSON
        ResourceClass.BASEMAP_SPRITE_IMAGE -> RentileResourceClass.SPRITE_IMAGE
        ResourceClass.BASEMAP_GEO_JSON -> RentileResourceClass.GEO_JSON
        ResourceClass.BASEMAP_STYLE,
        ResourceClass.STICKER_IMAGE,
        ResourceClass.MODEL_GLB,
        ResourceClass.MODEL_TEXTURE,
        -> null
    }

private val AUTHENTICATION_QUERY_PARAMETER_NAMES: Set<String> = setOf(
    "access_token",
    "apikey",
    "api_key",
    "key",
    "mtsid",
    "session",
    "session_id",
    "token",
)

/**
 * Rewrites every query parameter whose **lowercased** name is one of Rentile's eight authentication
 * parameter names to `<name>=<redacted>` (preserving the parameter's original-case name), leaves every
 * other parameter and the fragment untouched, and returns the url unchanged when it carries no query
 * component at all. Byte-for-byte the same rewrite as Rentile's private
 * `com.rohittp.rentile.internal.withRedactedAuthenticationQuery` -- confirmed byte-identical source
 * between the respike's measurement commit and the 0.2.0 Rentile release this build pins. Any
 * divergence here changes the hash input for all seven engine-keyed classes and silently breaks their
 * key agreement with Rentile.
 */
internal fun redactAuthenticationQuery(url: String): String {
    val queryStart = url.indexOf('?')
    if (queryStart < 0) return url

    val fragmentStart = url.indexOf('#', startIndex = queryStart + 1).let { if (it < 0) url.length else it }
    val prefix = url.substring(0, queryStart + 1)
    val query = url.substring(queryStart + 1, fragmentStart)
    val suffix = url.substring(fragmentStart)

    val redactedQuery = query.split('&').joinToString("&") { parameter ->
        val separator = parameter.indexOf('=')
        val name = if (separator < 0) parameter else parameter.substring(0, separator)
        if (name.lowercase() in AUTHENTICATION_QUERY_PARAMETER_NAMES) "$name=<redacted>" else parameter
    }

    return prefix + redactedQuery + suffix
}
