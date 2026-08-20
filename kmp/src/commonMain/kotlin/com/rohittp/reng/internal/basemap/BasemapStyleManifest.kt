package com.rohittp.reng.internal.basemap

import com.rohittp.reng.ResourceAccessMode
import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceLimits
import com.rohittp.reng.ResourceLocator
import com.rohittp.reng.internal.freshListCopy
import com.rohittp.reng.internal.json.JsonParse
import com.rohittp.reng.internal.json.JsonReject
import com.rohittp.reng.internal.json.JsonValue
import com.rohittp.reng.internal.json.parseJson
import com.rohittp.reng.internal.maximumBytesFor
import com.rohittp.reng.internal.planning.CanonicalBasemapTile
import com.rohittp.reng.internal.resource.ResourceRouteKey
import com.rohittp.reng.internal.resource.StyleFailureKind

/*
 * Reads a basemap style document and derives, purely, the exact resource routes the Rentile engine
 * will later request while compiling that style and while preparing a set of tiles from it.
 *
 * **Why this exists at all.** Rentile's `PreparedStyle` exposes only `digest`, `policy` and
 * `diagnostics` (`Api.kt:239-243` at the pinned `0.2.0` release commit `2d0a5bf`); tile URL templates,
 * zoom ranges, scheme and bounds are deliberately private. RenG therefore cannot ask the engine what it
 * is about to fetch, and ADR 0016's firewall matches an engine request by **exact string equality**
 * against preregistered routes (`OperationRegistry.executeTransport`). Everything below is consequently
 * a re-implementation of private Rentile logic, kept in agreement with it by nothing but a pinned
 * dependency version and this file's tests. The failure mode of a disagreement is loud
 * (`AMBIGUOUS_RESOURCE_ROUTE`) rather than silent, which is the better of the two available outcomes --
 * but it presents as a total outage, so *reproducing* Rentile matters far more than being independently
 * correct. Nothing here may be "improved".
 *
 * **The version-pinned assumption.** At `2d0a5bf` no credential and no session is composed into any
 * url: `RentileConfiguration.sessionProvider`/`credentialProvider` have no reader in the whole engine,
 * and `BasemapEngineHost` additionally passes `None` for both. Every url below is therefore a pure
 * function of the style document and the style's own base URI. A future Rentile that started composing
 * a session parameter would break every preregistered route at once.
 *
 * **Supported subset: inline `tiles` only.** A source may declare its tile templates inline
 * (`"tiles": [...]`) or by reference (`"url": ".../tiles.json"`, a TileJSON document). Only the inline
 * form is supported. The `url` form's templates, scheme, zoom range and bounds live in a TileJSON
 * document RenG never fetches, so supporting it means observing TileJSON bytes at the firewall and
 * reproducing a second layer of Rentile's private semantics. A `url`-form source is therefore recorded
 * as `SOURCE_TILE_JSON_URL_UNSUPPORTED` on `BasemapStyleManifest.underivableSources` and contributes no
 * route, rather than being dropped without trace or answered with a guessed url.
 *
 * **Over-registration, deliberately.** Which layers are active at a zoom, whether the sprite is fetched
 * at all, and which sources a retained layer actually references are all decided inside Rentile's own
 * layer compilation. RenG models none of that: it registers the sprite pair whenever `sprite` is a
 * string and every routable source in `sources`, for every tile. An unused preregistered route is never
 * answered and costs one SHA-256 in `OperationRegistry.preregister`; a missing one fails closed. Source
 * `bounds` are, for the same reason, deliberately not read: they only ever *suppress* requests.
 *
 * **A source RenG cannot derive is deferred, not fatal.** Eleven per-source conditions -- a `url`-form
 * TileJSON reference first among them -- record a `BasemapSourceUnderivableReason` against that source
 * and contribute no route, instead of rejecting the whole style. Deferring costs nothing when the
 * source is unused: `StyleCompiler` has no loop over `sources` at all, and `compileVectorSource` /
 * `compileRasterSource` are reached only per referencing layer, so an unreferenced source is never
 * compiled and never fetched. When the source *is* used, the firewall refuses it at the moment it is
 * actually needed, and that refusal is not silent: `OperationRegistry.executeTransport` throws
 * `ambiguousRouteFailure()` with no fallback branch, which Rentile wraps and `classifyEngineFailure`
 * maps to `RESOURCE_UNAVAILABLE` at `RESOURCE_LOOKUP` -- naming a class this cycle preregisters no
 * route for, which makes it an attributable fingerprint rather than an anonymous outage.
 *
 * Rejecting the whole style upfront was strictly worse on both counts. It is no more precise:
 * `styleFailure` names the *style's* own key and class, never the offending source. And for a style
 * served from the consumer's own cache it is actively misleading, because `styleFailure`'s first line
 * collapses STORE-provenance content into `STORE_INTEGRITY_FAILED` at `STORE_VALIDATION` -- so one
 * unused `url` source would present as cache corruption and blame the consumer's store. The rule the
 * firewall already follows applies here too: be strict only where strictness cannot break a working
 * flow (`writeStore` is strict about a missing latch precisely because Rentile 0.2.0 cannot produce
 * one; `removeStore` is permissive about an unregistered key).
 *
 * Document-level faults stay fatal, because a document that will not parse yields no routes at all and
 * deferring would turn a precise parse failure into a total outage under a worse code. So does a
 * malformed `terrain` block, which is not a divergence at all: `compileTerrainSource`
 * (`StyleCompiler.kt:474-495`) throws on both of those conditions unconditionally.
 *
 * **Where RenG's reader is stricter than Rentile's.** Every known divergence runs in one direction --
 * RenG refuses a document Rentile would have read -- so each can only produce a loud typed failure,
 * never a wrong url. This list is *not* claimed exhaustive; it is what has actually been checked:
 *  - **Duplicate member names.** RenG rejects; kotlinx keeps the last occurrence. Reported under its own
 *    `STYLE_JSON_DUPLICATE_MEMBER_NAME` so it does not read as a corrupt download.
 *  - **Invalid UTF-8.** Rentile decodes with `bytes.decodeToString()` (`StyleCompiler.kt:84`), the
 *    *replacing* variant, so a malformed sequence becomes U+FFFD and the style still compiles. RenG's
 *    five `UTF8_*` reject codes fail the document instead.
 *  - **Number spelling.** kotlinx keeps an unquoted number token as an opaque literal, so
 *    `"maxzoom": 014` reads as `14` there while RenG's reader rejects it as `LEADING_ZERO`. The same
 *    holds for `BAD_FRACTION`, `BAD_EXPONENT` and `NON_FINITE_NUMBER`.
 *  - **Nesting depth.** RenG bounds it at `STYLE_JSON_MAXIMUM_DEPTH`; kotlinx imposes no ceiling.
 *
 * **A second version-and-configuration-pinned assumption**, alongside the credential one above.
 * `RasterSample.immediateChildren` and `RasterSample.ancestor` (`raster/RasterResource.kt:81-105`)
 * compose tile urls this file never emits. They are unreachable only because `BasemapEngineHost` passes
 * `TileSubstitutionPolicy.Disabled`, which makes `validateSubstitutionAllowance`
 * (`DefaultBasemapRasterizer.kt:663-681`) throw as soon as any plan failure exists, before
 * `substituteRaster` / `substituteVector` (`:786`, `:846`) can run. Enabling substitution would put
 * live urls outside this file's coverage, and would have to extend the derivation in the same change.
 */

/** A source's tile-Y direction. Rentile: `internal.style.TileScheme`. */
internal enum class BasemapTileScheme { XYZ, TMS }

/**
 * The four style source types RenG routes, each fixing both the [ResourceClass] its tiles carry and the
 * shape of the sampling. Every other declared source type (`image`, `video`, `raster-array`, ...) is
 * ignored outright: Rentile refuses to compile a layer that references one, so it never fetches one.
 */
internal enum class BasemapSourceKind { VECTOR, RASTER, RASTER_DEM, GEO_JSON }

/**
 * Every **document-level** reason a style yields no manifest at all, paired with the [StyleFailureKind]
 * the resource layer already maps onto a sanitized RenG failure -- `PARSE` becomes
 * `RESOURCE_PARSE_FAILED` and `UNSUPPORTED_FEATURE` becomes `UNSUPPORTED_RESOURCE_FEATURE`, both at
 * `RESOURCE_PARSING` (`ResourceOperationStateMachine.styleFailure`). `PARSE` means "this is not a style
 * document RenG can read"; `UNSUPPORTED_FEATURE` means "it reads, and uses something outside RenG's
 * supported subset".
 *
 * These are fatal because none of them leaves RenG with a document to derive *any* route from, so
 * deferring one would convert a precise parse failure into a total outage under a worse code. A fault
 * confined to a single source is not here -- see [BasemapSourceUnderivableReason].
 */
internal enum class BasemapStyleReject(val kind: StyleFailureKind) {
    STYLE_JSON_MALFORMED(StyleFailureKind.PARSE),

    /** RenG's reader is stricter than Rentile's here; see this file's header. */
    STYLE_JSON_DUPLICATE_MEMBER_NAME(StyleFailureKind.PARSE),
    STYLE_ROOT_NOT_OBJECT(StyleFailureKind.PARSE),
    SOURCES_NOT_OBJECT(StyleFailureKind.PARSE),
    STYLE_VERSION_UNSUPPORTED(StyleFailureKind.UNSUPPORTED_FEATURE),

    /** Not a divergence: `StyleCompiler.compileTerrainSource` (`:474-495`) throws on both of these. */
    TERRAIN_NOT_OBJECT(StyleFailureKind.PARSE),
    TERRAIN_SOURCE_NOT_STRING(StyleFailureKind.PARSE),
}

/**
 * Every reason one declared source contributes no route, while the rest of the style derives normally.
 *
 * None of these is a failure. RenG cannot tell a referenced source from an unreferenced one without
 * modelling layer retention, which it deliberately does not do, and Rentile never touches an
 * unreferenced source -- so rejecting the style over one would break a flow that works. When the source
 * *is* referenced, the firewall refuses the url at the moment the engine asks for it, loudly and
 * attributably; see this file's header for the whole argument. The reason is carried on
 * [BasemapStyleManifest.underivableSources] rather than discarded so that a later task can surface it
 * as a diagnostic, and so that the eventual firewall refusal has a documented, greppable cause.
 */
internal enum class BasemapSourceUnderivableReason {
    SOURCE_NOT_OBJECT,

    /** The TileJSON-reference form of a source. RenG supports inline `tiles` only. */
    SOURCE_TILE_JSON_URL_UNSUPPORTED,
    SOURCE_TILES_NOT_STRINGS,
    SOURCE_TILES_EMPTY,
    SOURCE_REFERENCE_UNRESOLVABLE,
    SOURCE_SCHEME_UNSUPPORTED,
    SOURCE_ZOOM_NOT_INTEGER,
    SOURCE_ZOOM_RANGE_INVALID,
    SOURCE_TILE_SIZE_NOT_INTEGER,
    SOURCE_TILE_SIZE_UNSUPPORTED,
    GEO_JSON_DATA_NOT_STRING,
}

/** One declared source RenG could not derive an exact url for, and why. */
internal data class UnderivableBasemapSource(
    val sourceId: String,
    val reason: BasemapSourceUnderivableReason,
)

/**
 * One source tile a style source will be asked for. Rentile's `RasterSample`/`VectorTileSample` carry
 * the output tile and the child offsets too; only these three fields take part in url composition, so
 * only these three are reproduced.
 */
internal data class BasemapTileSample(val sourceZ: Int, val sourceX: Int, val sourceY: Int)

/** One routable source of a style, with every field url composition needs already resolved. */
internal class BasemapStyleSource(
    val sourceId: String,
    val kind: BasemapSourceKind,
    tileTemplates: List<String>,
    /** The resolved absolute GeoJSON document url; non-null exactly for [BasemapSourceKind.GEO_JSON]. */
    val geoJsonReference: String?,
    val scheme: BasemapTileScheme,
    val minZoom: Int,
    val maxZoom: Int,
    /** `null` for a vector or GeoJSON source; 64, 256 or 512 for a raster or DEM one. */
    val tileSizePixels: Int?,
) {
    private val templateSnapshot: List<String> = freshListCopy(tileTemplates)

    /** Absolute, already resolved against the style's base URI, in declaration order. */
    val tileTemplates: List<String> get() = freshListCopy(templateSnapshot)

    internal val templateCount: Int get() = templateSnapshot.size

    internal fun templateAt(index: Int): String = templateSnapshot[index]

    /** Redacted: a tile template is a url and may carry a credential. */
    override fun toString(): String =
        "BasemapStyleSource(kind=$kind, scheme=$scheme, minZoom=$minZoom, " +
            "maxZoom=$maxZoom, templates=${templateSnapshot.size})"
}

/** Everything a style document says that decides which routes the engine will ask for. */
internal class BasemapStyleManifest(
    /** The style's own locator; every relative reference in the document resolves against it. */
    val baseUri: String,
    /**
     * The resolved sprite base, before [appendSpriteExtension] adds `.json`/`.png`. `null` when the
     * style declares no `sprite`, declares it in array form, or declares a relative reference that
     * cannot be resolved -- all three are cases in which Rentile fetches no sprite at all.
     */
    val spriteBase: String?,
    sources: List<BasemapStyleSource>,
    underivableSources: List<UnderivableBasemapSource>,
    /** `root["terrain"]["source"]`, or `null` when the style declares no terrain. */
    val terrainSourceId: String?,
) {
    private val sourceSnapshot: List<BasemapStyleSource> = freshListCopy(sources)
    private val underivableSnapshot: List<UnderivableBasemapSource> = freshListCopy(underivableSources)

    /** In declaration order, restricted to the four [BasemapSourceKind]s RenG routes. */
    val sources: List<BasemapStyleSource> get() = freshListCopy(sourceSnapshot)

    /**
     * The declared sources RenG could not derive an exact url for, in declaration order, and why. Each
     * contributes no route; none of them fails the style. Empty for a style RenG derives completely.
     */
    val underivableSources: List<UnderivableBasemapSource> get() = freshListCopy(underivableSnapshot)

    /** Redacted: [baseUri] and [spriteBase] are urls and may carry a credential. */
    override fun toString(): String =
        "BasemapStyleManifest(sources=${sourceSnapshot.size}, " +
            "underivable=${underivableSnapshot.size}, sprite=${spriteBase != null}, " +
            "terrain=${terrainSourceId != null})"
}

internal sealed interface BasemapStyleManifestOutcome {
    class Derived(val manifest: BasemapStyleManifest) : BasemapStyleManifestOutcome

    data class Rejected(val reason: BasemapStyleReject) : BasemapStyleManifestOutcome {
        val kind: StyleFailureKind get() = reason.kind
    }
}

/**
 * Parses [styleBytes] -- the style document RenG already acquired under its own `BASEMAP_STYLE` route --
 * into the manifest [styleTimeRoutes] and [tileTimeRoutes] derive from. Pure: no I/O, no clock, no
 * randomness, and never throws for any input.
 *
 * [baseUri] is the style's own locator (`RendererConfiguration.basemapStyle`), which is what Rentile
 * receives as `StyleInput.Prefetched.baseUri` and what it resolves every relative reference against.
 *
 * Rejects only the document-level faults in [BasemapStyleReject]. A fault confined to one source is
 * recorded on [BasemapStyleManifest.underivableSources] and the rest of the style derives normally.
 */
internal fun deriveBasemapStyleManifest(styleBytes: ByteArray, baseUri: String): BasemapStyleManifestOutcome =
    try {
        BasemapStyleManifestOutcome.Derived(readStyleManifest(styleBytes, baseUri))
    } catch (signal: StyleRejectSignal) {
        BasemapStyleManifestOutcome.Rejected(signal.reason)
    }

/**
 * The routes the engine fetches while *compiling* the style: the sprite JSON and image pair, and one
 * document per GeoJSON source. Rentile touches exactly these classes inside `prepare`
 * (`StyleCompiler.compile` -> `SpriteResourceAcquirer.acquire` at `:126-133`, and
 * `compileVectorSource`'s GeoJSON branch at `:1007-1010`).
 *
 * No `BASEMAP_TILE_JSON` route is ever produced: the TileJSON reference form is outside the supported
 * subset and is rejected at [deriveBasemapStyleManifest] instead.
 */
internal fun styleTimeRoutes(
    manifest: BasemapStyleManifest,
    accessMode: ResourceAccessMode,
    limits: ResourceLimits,
): List<ResourceRouteKey> {
    val routes = ArrayList<ResourceRouteKey>()
    manifest.spriteBase?.let { base ->
        routes += routeFor(appendSpriteExtension(base, ".json"), ResourceClass.BASEMAP_SPRITE_JSON, accessMode, limits)
        routes += routeFor(appendSpriteExtension(base, ".png"), ResourceClass.BASEMAP_SPRITE_IMAGE, accessMode, limits)
    }
    manifest.sources.forEach { source ->
        source.geoJsonReference?.let { reference ->
            routes += routeFor(reference, ResourceClass.BASEMAP_GEO_JSON, accessMode, limits)
        }
    }
    return routes.distinct()
}

/**
 * The raster, vector and DEM tile routes for [tiles]. Rentile touches exactly these classes inside
 * `prepareBatch` (`DefaultBasemapRasterizer.planRasterResources` at `:540-572` and `planVectorResources`
 * at `:574-609`); `render` performs no adapter call at all.
 *
 * Every `raster-dem` source is expanded over its 3x3 neighbourhood, because a hillshade layer samples
 * one (`:552-557` -> `RasterResource.neighbor`) and RenG does not model whether the style has one. The
 * centre sample is a member of that neighbourhood, so a DEM source reached only through `terrain`
 * (which samples the centre alone) is covered by the same expansion.
 *
 * [tiles] are `CanonicalBasemapTile`s from `selectBasemapTiles`, whose LOD range is `0..22` and whose
 * `canonicalX` is already canonical -- the `floorMod` below is Rentile's own and is therefore an
 * identity for such a tile, kept only so this stays a literal port.
 */
internal fun tileTimeRoutes(
    manifest: BasemapStyleManifest,
    tiles: List<CanonicalBasemapTile>,
    accessMode: ResourceAccessMode,
    limits: ResourceLimits,
): List<ResourceRouteKey> {
    val routes = ArrayList<ResourceRouteKey>()
    tiles.forEach { tile ->
        manifest.sources.forEach { source ->
            val resourceClass = tileResourceClassOf(source.kind) ?: return@forEach
            val centre = basemapTileSampleFor(source, tile) ?: return@forEach
            demNeighbourhoodOrSelf(source, centre).forEach { sample ->
                routes += routeFor(basemapTileUrl(source, sample), resourceClass, accessMode, limits)
            }
        }
    }
    return routes.distinct()
}

/**
 * Rentile's `resolveHttpReference` (`metadata/TileJsonResourceAcquirer.kt:292-320`), ported verbatim.
 *
 * **This is not RFC 3986 and must not be made to be.** It strips the base's query *and* fragment before
 * joining, splices only the scheme for a `//`-prefixed reference (ignoring the base's own origin),
 * returns an already-absolute `http(s)` reference completely unnormalised, and drops every empty path
 * segment -- so a trailing slash disappears and a `//host` reference with no path gains one. Rewriting
 * any of that "correctly" silently breaks every relative reference in a style.
 *
 * Returns `null` when [baseUrl] carries no `://`, or begins with one; Rentile treats that as an
 * unresolvable reference.
 */
internal fun resolveHttpReference(baseUrl: String, reference: String): String? {
    if (reference.startsWith("https://") || reference.startsWith("http://")) return reference
    val schemeEnd = baseUrl.indexOf("://")
    if (schemeEnd <= 0) return null
    val originStart = schemeEnd + 3
    val pathStart = baseUrl.indexOf('/', originStart).let { if (it < 0) baseUrl.length else it }
    val origin = baseUrl.substring(0, pathStart).substringBefore('?')
    val basePath = baseUrl.substring(pathStart).substringBefore('?').substringBefore('#')
    val combined = when {
        reference.startsWith("//") -> baseUrl.substring(0, schemeEnd + 1) + reference
        reference.startsWith('/') -> origin + reference
        else -> origin + basePath.substringBeforeLast('/', missingDelimiterValue = "") + "/" + reference
    }
    val absoluteSchemeEnd = combined.indexOf("://")
    val absolutePathStart = combined.indexOf('/', absoluteSchemeEnd + 3).let { if (it < 0) combined.length else it }
    val absoluteOrigin = combined.substring(0, absolutePathStart)
    val pathAndQuery = combined.substring(absolutePathStart)
    val path = pathAndQuery.substringBefore('?').substringBefore('#')
    val suffix = pathAndQuery.removePrefix(path)
    val normalized = mutableListOf<String>()
    for (segment in path.split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> if (normalized.isNotEmpty()) normalized.removeAt(normalized.lastIndex)
            else -> normalized += segment
        }
    }
    return absoluteOrigin + "/" + normalized.joinToString("/") + suffix
}

/**
 * Rentile's `appendSpriteExtension` (`sprite/SpriteResourceAcquirer.kt:279-287`), ported verbatim: the
 * extension is inserted before the query and the fragment, locating `#` first and `?` second.
 *
 * It **appends unconditionally** -- it does not drop an existing extension -- so a `sprite` base already
 * ending in `.json` yields `....json.json`, and that doubled url is the one the engine requests.
 */
internal fun appendSpriteExtension(baseUrl: String, extension: String): String {
    val fragmentIndex = baseUrl.indexOf('#').let { if (it < 0) baseUrl.length else it }
    val withoutFragment = baseUrl.substring(0, fragmentIndex)
    val fragment = baseUrl.substring(fragmentIndex)
    val queryIndex = withoutFragment.indexOf('?').let { if (it < 0) withoutFragment.length else it }
    val path = withoutFragment.substring(0, queryIndex)
    val query = withoutFragment.substring(queryIndex)
    return path + extension + query + fragment
}

/**
 * Rentile's `CompiledRasterSource.sampleFor` / `CompiledVectorSource.sampleFor`
 * (`raster/RasterResource.kt:34-54`, `mvt/VectorSource.kt:73-93` -- byte-identical to each other),
 * ported verbatim, minus the `bounds` test RenG deliberately does not model.
 *
 * The trap here is `sourceZ = min(tile.z, maxZoom)`: past a source's maxzoom the url's zoom is **not**
 * the LOD RenG selected, and neither are its x and y -- they are divided by the child scale. Getting it
 * wrong preregisters a route the engine never asks for and omits the one it does.
 */
internal fun basemapTileSampleFor(source: BasemapStyleSource, tile: CanonicalBasemapTile): BasemapTileSample? {
    if (source.templateCount == 0) return null
    if (tile.lod < source.minZoom) return null
    val outputDimension = 1L shl tile.lod
    val canonicalOutputX = tile.canonicalX.toLong().floorModOf(outputDimension)
    val sourceZ = minOf(tile.lod, source.maxZoom)
    val zoomDelta = tile.lod - sourceZ
    val childScale = 1 shl zoomDelta
    return BasemapTileSample(
        sourceZ = sourceZ,
        sourceX = (canonicalOutputX / childScale).toInt(),
        sourceY = tile.tileY / childScale,
    )
}

/**
 * Rentile's `RasterSample.tileUrl` / `VectorTileSample.tileUrl` (`raster/RasterResource.kt:56-69`,
 * `mvt/VectorSource.kt:95-108`), ported verbatim.
 *
 * Two traps. `templateIndex` is a **hash round-robin**, `floorMod(sourceZ * 31 + sourceX * 17 +
 * sourceY, templates.size)` -- any other distribution picks the wrong host from a multi-template source
 * and every tile fails closed. And `{y}` and `{-y}` are **two different substitutions**: `{y}` is the
 * scheme-dependent request y, `{-y}` is always the flipped y, so under `xyz` they differ. The
 * substitution order below is Rentile's own and is preserved.
 */
internal fun basemapTileUrl(source: BasemapStyleSource, sample: BasemapTileSample): String {
    val dimension = 1L shl sample.sourceZ
    val templateIndex = (sample.sourceZ.toLong() * 31L + sample.sourceX * 17L + sample.sourceY)
        .floorModOf(source.templateCount.toLong())
        .toInt()
    val template = source.templateAt(templateIndex)
    val requestY = when (source.scheme) {
        BasemapTileScheme.XYZ -> sample.sourceY
        BasemapTileScheme.TMS -> (dimension - 1L - sample.sourceY).toInt()
    }
    return template
        .replace("{z}", sample.sourceZ.toString())
        .replace("{x}", sample.sourceX.toString())
        .replace("{y}", requestY.toString())
        .replace("{-y}", (dimension - 1L - sample.sourceY).toString())
}

/**
 * Rentile's `RasterSample.neighbor` (`raster/RasterResource.kt:71-79`), ported verbatim: y is
 * **clipped** to the zoom's tile range (a neighbour off the top or bottom of the world does not exist),
 * x is **wrapped** with `floorMod` (the world is cylindrical).
 */
internal fun basemapTileSampleNeighbour(
    sample: BasemapTileSample,
    deltaX: Int,
    deltaY: Int,
): BasemapTileSample? {
    val dimension = 1L shl sample.sourceZ
    val neighbourY = sample.sourceY.toLong() + deltaY
    if (neighbourY !in 0 until dimension) return null
    return sample.copy(
        sourceX = (sample.sourceX.toLong() + deltaX).floorModOf(dimension).toInt(),
        sourceY = neighbourY.toInt(),
    )
}

// ---- internals -----------------------------------------------------------------------------------

/** Internal control-flow signal, unwound by [deriveBasemapStyleManifest]; never seen outside this file. */
private class StyleRejectSignal(val reason: BasemapStyleReject) : RuntimeException()

private fun rejectStyle(reason: BasemapStyleReject): Nothing = throw StyleRejectSignal(reason)

/**
 * Internal control-flow signal for a fault confined to one source, unwound by [readSources] into an
 * [UnderivableBasemapSource]. Never escapes that loop, and never reaches [deriveBasemapStyleManifest]'s
 * own catch: unlike [StyleRejectSignal] it is not a failure at all.
 */
private class SourceUnderivableSignal(val reason: BasemapSourceUnderivableReason) : RuntimeException()

private fun skipSource(reason: BasemapSourceUnderivableReason): Nothing = throw SourceUnderivableSignal(reason)

/**
 * A style document nests only as deep as its expressions do. Rentile's kotlinx reader imposes no
 * ceiling at all, so any ceiling here is RenG's own; it is set well past anything a hand-written or
 * generated style reaches so that it bounds a hostile document rather than a real one.
 */
private const val STYLE_JSON_MAXIMUM_DEPTH: Int = 256

private val SUPPORTED_TILE_SIZES: Set<Int> = setOf(64, 256, 512)

private const val VECTOR_DEFAULT_MAXIMUM_ZOOM: Int = 22
private const val RASTER_DEFAULT_MAXIMUM_ZOOM: Int = 30
private const val MAXIMUM_SOURCE_ZOOM: Int = 30
private const val DEFAULT_RASTER_TILE_SIZE: Int = 512

private fun readStyleManifest(styleBytes: ByteArray, baseUri: String): BasemapStyleManifest {
    val root = when (val parsed = parseJson(styleBytes, 0, styleBytes.size, STYLE_JSON_MAXIMUM_DEPTH)) {
        is JsonParse.Failed -> rejectStyle(
            if (parsed.reason == JsonReject.DUPLICATE_MEMBER_NAME) {
                BasemapStyleReject.STYLE_JSON_DUPLICATE_MEMBER_NAME
            } else {
                BasemapStyleReject.STYLE_JSON_MALFORMED
            },
        )

        is JsonParse.Parsed -> parsed.value as? JsonValue.Obj
            ?: rejectStyle(BasemapStyleReject.STYLE_ROOT_NOT_OBJECT)
    }
    if (!declaresStyleVersionEight(root.members["version"])) {
        rejectStyle(BasemapStyleReject.STYLE_VERSION_UNSUPPORTED)
    }
    val declaredSources = readSources(root, baseUri)
    return BasemapStyleManifest(
        baseUri = baseUri,
        // Only the string form resolves: Rentile ignores the array form (StyleCompiler.kt:1646, :1687),
        // and an unresolvable relative reference leaves its atlas unresolved rather than composing a url.
        spriteBase = (root.members["sprite"] as? JsonValue.Text)?.value?.let { resolveHttpReference(baseUri, it) },
        sources = declaredSources.routable,
        underivableSources = declaredSources.underivable,
        terrainSourceId = readTerrainSourceId(root),
    )
}

/**
 * Rentile reads `version` as `root["version"]?.asPrimitive()?.intOrNull != 8` (`StyleCompiler.kt:94-96`),
 * and kotlinx's `intOrNull` parses the primitive's *content* -- so a string-spelled `"8"` passes there
 * and must pass here. A fractional or exponential token never does, because its content does not parse
 * as an `Int`.
 */
private fun declaresStyleVersionEight(value: JsonValue?): Boolean = intPrimitiveOrNull(value) == 8

/** Rentile's `JsonPrimitive.intOrNull`, over RenG's own parsed tree. */
private fun intPrimitiveOrNull(value: JsonValue?): Int? = when (value) {
    is JsonValue.Integer -> if (value.value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
        value.value.toInt()
    } else {
        null
    }

    is JsonValue.Text -> value.value.toIntOrNull()
    else -> null
}

private class DeclaredSources(
    val routable: List<BasemapStyleSource>,
    val underivable: List<UnderivableBasemapSource>,
)

/**
 * Reads every declared source, partitioning them into the ones RenG can compose exact urls for and the
 * ones it cannot. A per-source fault is caught here and recorded; only a `sources` member that is not
 * an object at all is fatal, because that leaves no source to record a reason against.
 */
private fun readSources(root: JsonValue.Obj, baseUri: String): DeclaredSources {
    val declared = root.members["sources"] ?: return DeclaredSources(emptyList(), emptyList())
    val sources = declared as? JsonValue.Obj ?: rejectStyle(BasemapStyleReject.SOURCES_NOT_OBJECT)
    val routable = ArrayList<BasemapStyleSource>()
    val underivable = ArrayList<UnderivableBasemapSource>()
    sources.members.forEach { (sourceId, value) ->
        try {
            readSource(sourceId, value, baseUri)?.let { routable += it }
        } catch (signal: SourceUnderivableSignal) {
            underivable += UnderivableBasemapSource(sourceId, signal.reason)
        }
    }
    return DeclaredSources(routable, underivable)
}

private fun readSource(sourceId: String, value: JsonValue, baseUri: String): BasemapStyleSource? {
    val source = value as? JsonValue.Obj ?: skipSource(BasemapSourceUnderivableReason.SOURCE_NOT_OBJECT)
    return when ((source.members["type"] as? JsonValue.Text)?.value) {
        "vector" -> readTileSource(sourceId, source, baseUri, BasemapSourceKind.VECTOR, VECTOR_DEFAULT_MAXIMUM_ZOOM)
        "raster" -> readTileSource(sourceId, source, baseUri, BasemapSourceKind.RASTER, RASTER_DEFAULT_MAXIMUM_ZOOM)
        "raster-dem" -> readTileSource(
            sourceId,
            source,
            baseUri,
            BasemapSourceKind.RASTER_DEM,
            RASTER_DEFAULT_MAXIMUM_ZOOM,
        )

        "geojson" -> readGeoJsonSource(sourceId, source, baseUri)
        else -> null
    }
}

private fun readTileSource(
    sourceId: String,
    source: JsonValue.Obj,
    baseUri: String,
    kind: BasemapSourceKind,
    defaultMaximumZoom: Int,
): BasemapStyleSource {
    // Rentile reads the reference as a *string* primitive, so a non-string `url` is simply absent to it
    // (StyleCompiler.kt:1024, :1299) and must be absent here too.
    if (source.members["url"] is JsonValue.Text) skipSource(BasemapSourceUnderivableReason.SOURCE_TILE_JSON_URL_UNSUPPORTED)

    val declaredTemplates = (source.members["tiles"] as? JsonValue.Arr)?.elements.orEmpty()
    val templates = declaredTemplates.map { element ->
        val template = (element as? JsonValue.Text)?.value ?: skipSource(BasemapSourceUnderivableReason.SOURCE_TILES_NOT_STRINGS)
        resolveHttpReference(baseUri, template) ?: skipSource(BasemapSourceUnderivableReason.SOURCE_REFERENCE_UNRESOLVABLE)
    }
    if (templates.isEmpty()) skipSource(BasemapSourceUnderivableReason.SOURCE_TILES_EMPTY)

    val scheme = when (val declaredScheme = source.members["scheme"]) {
        null -> BasemapTileScheme.XYZ
        else -> when ((declaredScheme as? JsonValue.Text)?.value) {
            "xyz" -> BasemapTileScheme.XYZ
            "tms" -> BasemapTileScheme.TMS
            else -> skipSource(BasemapSourceUnderivableReason.SOURCE_SCHEME_UNSUPPORTED)
        }
    }

    val minZoom = maxOf(declaredZoom(source, "minzoom") ?: 0, 0)
    val maxZoom = minOf(declaredZoom(source, "maxzoom") ?: defaultMaximumZoom, defaultMaximumZoom)
    if (minZoom !in 0..MAXIMUM_SOURCE_ZOOM || maxZoom !in minZoom..MAXIMUM_SOURCE_ZOOM) {
        skipSource(BasemapSourceUnderivableReason.SOURCE_ZOOM_RANGE_INVALID)
    }

    return BasemapStyleSource(
        sourceId = sourceId,
        kind = kind,
        tileTemplates = templates,
        geoJsonReference = null,
        scheme = scheme,
        minZoom = minZoom,
        maxZoom = maxZoom,
        tileSizePixels = if (kind == BasemapSourceKind.VECTOR) null else readTileSize(source),
    )
}

private fun declaredZoom(source: JsonValue.Obj, member: String): Int? {
    val declared = source.members[member] ?: return null
    return intPrimitiveOrNull(declared) ?: skipSource(BasemapSourceUnderivableReason.SOURCE_ZOOM_NOT_INTEGER)
}

private fun readTileSize(source: JsonValue.Obj): Int {
    val declared = source.members["tileSize"] ?: source.members["tile-size"] ?: return DEFAULT_RASTER_TILE_SIZE
    val tileSize = intPrimitiveOrNull(declared) ?: skipSource(BasemapSourceUnderivableReason.SOURCE_TILE_SIZE_NOT_INTEGER)
    if (tileSize !in SUPPORTED_TILE_SIZES) skipSource(BasemapSourceUnderivableReason.SOURCE_TILE_SIZE_UNSUPPORTED)
    return tileSize
}

private fun readGeoJsonSource(sourceId: String, source: JsonValue.Obj, baseUri: String): BasemapStyleSource {
    // An inline `"data": { ... }` GeoJSON document is legitimate MapLibre, and Rentile refuses it
    // (StyleCompiler.kt:1007-1008). RenG says so with its own code rather than emitting no route.
    val reference = (source.members["data"] as? JsonValue.Text)?.value
        ?: skipSource(BasemapSourceUnderivableReason.GEO_JSON_DATA_NOT_STRING)
    return BasemapStyleSource(
        sourceId = sourceId,
        kind = BasemapSourceKind.GEO_JSON,
        tileTemplates = emptyList(),
        geoJsonReference = resolveHttpReference(baseUri, reference)
            ?: skipSource(BasemapSourceUnderivableReason.SOURCE_REFERENCE_UNRESOLVABLE),
        // A GeoJSON source produces no tile url at all (StyleCompiler.kt:1013-1020); these three are the
        // values Rentile compiles it with, carried only so the value stays total.
        scheme = BasemapTileScheme.XYZ,
        minZoom = 0,
        maxZoom = VECTOR_DEFAULT_MAXIMUM_ZOOM,
        tileSizePixels = null,
    )
}

private fun readTerrainSourceId(root: JsonValue.Obj): String? {
    val declared = root.members["terrain"] ?: return null
    val terrain = declared as? JsonValue.Obj ?: rejectStyle(BasemapStyleReject.TERRAIN_NOT_OBJECT)
    return (terrain.members["source"] as? JsonValue.Text)?.value
        ?: rejectStyle(BasemapStyleReject.TERRAIN_SOURCE_NOT_STRING)
}

private fun tileResourceClassOf(kind: BasemapSourceKind): ResourceClass? = when (kind) {
    BasemapSourceKind.VECTOR -> ResourceClass.BASEMAP_VECTOR_TILE
    BasemapSourceKind.RASTER -> ResourceClass.BASEMAP_RASTER_TILE
    BasemapSourceKind.RASTER_DEM -> ResourceClass.BASEMAP_DEM_TILE
    BasemapSourceKind.GEO_JSON -> null
}

/**
 * The 3x3 neighbourhood a hillshade layer samples, in Rentile's own iteration order (`deltaY` outer,
 * `deltaX` inner -- `DefaultBasemapRasterizer.kt:552-557`), or the single centre sample for every other
 * source kind.
 */
private fun demNeighbourhoodOrSelf(
    source: BasemapStyleSource,
    centre: BasemapTileSample,
): List<BasemapTileSample> =
    if (source.kind != BasemapSourceKind.RASTER_DEM) {
        listOf(centre)
    } else {
        (-1..1).flatMap { deltaY ->
            (-1..1).mapNotNull { deltaX -> basemapTileSampleNeighbour(centre, deltaX, deltaY) }
        }
    }

private fun routeFor(
    url: String,
    resourceClass: ResourceClass,
    accessMode: ResourceAccessMode,
    limits: ResourceLimits,
): ResourceRouteKey = ResourceRouteKey(
    accessMode = accessMode,
    locator = ResourceLocator(url),
    resourceClass = resourceClass,
    maximumResponseBytes = limits.maximumBytesFor(resourceClass),
)

private fun Long.floorModOf(divisor: Long): Long {
    val remainder = this % divisor
    return if (remainder < 0) remainder + divisor else remainder
}
