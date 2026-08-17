# Cycle B Public API and Pure Core Design

## Outcome and scope

Cycle B publishes RenG's immutable consumer vocabulary, adapter contracts, renderer protocol, lifecycle and
failure types, plus the network-free and GL-free core that validates, transforms, plans, diffs, and identifies
Frame Plans. It implements no acquisition, decoding, GL calls, pixels, or public renderer construction.
Consumers can construct plans, configuration, transport/store response values, and selectors, inspect reports
and diagnostics, and compile against the final renderer protocol. Cycle B exposes no public renderer
construction or factory, so there is no executable public entry point that can obtain a `Renderer`,
`PreparedFrame`, or `RenderTarget`; construction lands only after the resource and GL seams can honor this
contract.

Everything remains inside the single published `:kmp` module and package `com.rohittp.reng`. Rentile stays an
`implementation` dependency and no Rentile or platform-GL type appears in public ABI. All six targets remain
`android`, `iosArm64`, `iosSimulatorArm64`, `macosArm64`, `linuxX64`, and `linuxArm64`.

## Public value surface

The declarations below are the normative names, property types, defaults, nullability, and constructor
visibility for Cycle B. All plan, resource, adapter-DTO, selector, diagnostic, and report values implement
structural `equals`/`hashCode`; regular classes do so manually over their immutable snapshots.
`RendererConfiguration`, adapter/sink implementations, `RenGException`, and the
`Renderer`/`PreparedFrame`/`RenderTarget` ownership protocols retain identity semantics.
`Vector3`, `Camera`, `Placement`, and `AnimationTrack` are regular classes because their constructors
canonicalize floating arguments before assignment. Negative zero is canonicalized before storage for every
public floating value.

A class receiving `List` snapshots it into private backing state. Every public list getter returns a new
defensive copy, and structural equality, hashing, and canonical encoding read the private snapshot rather than
a previously returned list. A platform caller may mutate a returned copy, but that cannot mutate the value,
its identity, or renderer state. `ByteArray` properties follow the same snapshot-and-copy-on-read rule. A
successful `prepareBatch` likewise returns a fresh same-order list that is not retained as renderer backing
state; platform mutation of that list changes neither history nor ownership of its Prepared Frames.

```kotlin
package com.rohittp.reng

public enum class ProjectionMode { MERCATOR, GLOBE }
public enum class AnchoringMode { MAP, SCREEN }

public class ResourceLocator(public val value: String)

public data class OutputPixelSize(
    public val width: Int,
    public val height: Int,
)

public class Vector3(x: Double, y: Double, z: Double) {
    public val x: Double
    public val y: Double
    public val z: Double
}

public class Camera(
    latitude: Double,
    unwrappedLongitude: Double,
    zoom: Double,
    bearing: Double,
    pitch: Double,
) {
    public val latitude: Double
    public val unwrappedLongitude: Double
    public val zoom: Double
    public val bearing: Double
    public val pitch: Double
}

public class Placement(
    public val positionMode: AnchoringMode,
    public val position: Vector3,
    public val rotationMode: AnchoringMode,
    public val rotation: Vector3,
    public val scaleMode: AnchoringMode,
    scale: Double,
) {
    public val scale: Double
}

public data class Sticker(
    public val placement: Placement,
    public val image: ResourceLocator,
)

public sealed interface AnimationSelector {
    public data class Name(public val value: String) : AnimationSelector
    public data class Index(public val value: Long) : AnimationSelector
}

public class AnimationTrack(
    public val animation: AnimationSelector,
    timeSeconds: Double,
) {
    public val timeSeconds: Double
}

public class Model(
    public val placement: Placement,
    public val glb: ResourceLocator,
    public val texture: ResourceLocator? = null,
    animationTracks: List<AnimationTrack> = emptyList(),
) {
    private val animationTrackSnapshot: List<AnimationTrack> = animationTracks.toList()
    public val animationTracks: List<AnimationTrack> get() = animationTrackSnapshot.toList()
}

public data class ShaderPair(
    public val vertexSource: String,
    public val fragmentSource: String,
)

public data class Geometry(
    public val topLeft: Vector3,
    public val bottomRight: Vector3,
    public val shaderPair: ShaderPair,
)

public class FramePlan(
    public val frameIndex: Long,
    public val camera: Camera,
    public val projectionMode: ProjectionMode = ProjectionMode.MERCATOR,
    public val drawBasemap: Boolean = true,
    stickers: List<Sticker> = emptyList(),
    models: List<Model> = emptyList(),
    geometries: List<Geometry> = emptyList(),
) {
    private val stickerSnapshot: List<Sticker> = stickers.toList()
    private val modelSnapshot: List<Model> = models.toList()
    private val geometrySnapshot: List<Geometry> = geometries.toList()
    public val stickers: List<Sticker> get() = stickerSnapshot.toList()
    public val models: List<Model> get() = modelSnapshot.toList()
    public val geometries: List<Geometry> get() = geometrySnapshot.toList()
}
```

`ResourceLocator` requires nonblank exact text, compares and hashes that exact text, and renders only
`ResourceLocator(<redacted>)` from `toString()`. `OutputPixelSize` requires positive dimensions and an exact
`Long` product no greater than `Int.MAX_VALUE`. Every `Vector3` component is finite. `Camera` requires
latitude in `[-90, 90]`, zoom in `[0, 22]`, bearing in `[0, 360)`, and pitch in `[0, 90)`; longitude is any
finite unwrapped value. Mercator-specific latitude and world-copy checks occur when a plan is validated for
that projection rather than in the projection-neutral camera constructor.

Rotation components are finite canonical degrees in `[-180, 180)`. Scale is finite and non-negative.
Animation names are nonblank exact Unicode scalar sequences, indices are non-negative `Long` values, and times
are finite and non-negative. Resource locators, animation names, and shader sources reject isolated UTF-16
surrogates; RenG performs no Unicode normalization. Shader sources are nonblank and exact. Geometry requires
top-left latitude strictly greater than bottom-right latitude, top-left unwrapped longitude strictly less than
bottom-right longitude, longitude span at most 360 degrees, and both latitudes in `[-90, 90]`. Frame index is
non-negative. The four floating-bearing regular classes validate and canonicalize every constructor argument
before assigning any public property. Constructor violations throw `IllegalArgumentException` with
non-sensitive, implementation-defined messages; callers must not inspect constructor exception text.

Shader-profile validation occurs at the full-batch planning barrier, not in the `ShaderPair` constructor and
not at driver compilation. The scanner operates on the exact Kotlin `String` after constructor-level surrogate
validation. The only line terminators are LF (`\n`), CRLF (`\r\n`, treated as one terminator), and bare CR
(`\r`). A `//` comment ends immediately before any of those terminators or at end of source. A `/*` comment
ends at the first following `*/`, may span lines, and does not nest; another `/*` inside it is comment text. An
unterminated block comment is invalid.

Every physical line before the directive must, outside comments, contain only ASCII space or tab and must end
with one accepted line terminator. Comments may share those prefix lines with other comments and ASCII
space/tab. A comment may not share the directive's physical line: after trimming only leading and trailing
ASCII space/tab, the first non-prefix physical line must equal exactly `#version 300 es`. Thus
`/* comment */ #version 300 es`, `#version 300 es // comment`, a trailing token, a non-ASCII whitespace
character, and a leading U+FEFF byte-order mark are all invalid. End of source may terminate the directive line.
A violation is the profile row in the failure matrix below.

GLES compilation receives the complete original source unchanged. For desktop compilation, the replaced
UTF-16 code-unit span starts immediately after the preceding line terminator (or at source start) and ends
immediately before the directive line terminator (or at end of source). RenG replaces that whole span—including
permitted leading/trailing ASCII space/tab—with exactly `#version 330 core`, preserving the existing line
terminator and every other code unit. The original source—not the substituted source—enters canonical identity.

At model-parse time, exact animation names receive no Unicode normalization, and duplicate nonblank animation
names make the catalog invalid. Name and zero-based index must resolve uniquely, and track selectors resolving
to the same animation are duplicate errors. Positive-duration animation samples use
`timeSeconds % durationSeconds`; zero-duration animation uses time zero. GLB buffers and images must be embedded.
A texture override replaces every rendered primitive's base-colour texture while preserving other material
properties.

## Resource and adapter surface

```kotlin
public enum class ResourceClass {
    BASEMAP_STYLE,
    BASEMAP_TILE_JSON,
    BASEMAP_VECTOR_TILE,
    BASEMAP_RASTER_TILE,
    BASEMAP_DEM_TILE,
    BASEMAP_SPRITE_JSON,
    BASEMAP_SPRITE_IMAGE,
    BASEMAP_GEO_JSON,
    STICKER_IMAGE,
    MODEL_GLB,
    MODEL_TEXTURE,
}

public enum class ResourceKind {
    EXTERNAL,
    GEOMETRY_PROGRAM,
    INTERNAL_PIPELINE,
    OFFSCREEN_SURFACE,
}

public enum class ResourceAccessMode { NORMAL, CACHE_ONLY, RELOAD }

public data class ResourceLimits(
    public val maximumBasemapStyleBytes: Long = 8L * 1024L * 1024L,
    public val maximumBasemapMetadataBytes: Long = 4L * 1024L * 1024L,
    public val maximumBasemapTileBytes: Long = 32L * 1024L * 1024L,
    public val maximumBasemapSpriteImageBytes: Long = 32L * 1024L * 1024L,
    public val maximumBasemapGeoJsonBytes: Long = 64L * 1024L * 1024L,
    public val maximumStickerImageBytes: Long = 32L * 1024L * 1024L,
    public val maximumModelGlbBytes: Long = 256L * 1024L * 1024L,
    public val maximumModelTextureBytes: Long = 32L * 1024L * 1024L,
)

public class TransportRequestMetadata internal constructor(
    public val ifNoneMatch: String? = null,
    public val ifModifiedSince: String? = null,
    public val accept: String? = null,
)

public class TransportRequest internal constructor(
    public val locator: ResourceLocator,
    public val resourceClass: ResourceClass,
    public val maximumResponseBytes: Long,
    public val metadata: TransportRequestMetadata = TransportRequestMetadata(),
)

public class TransportResponseMetadata(
    public val contentType: String? = null,
    public val etag: String? = null,
    public val lastModified: String? = null,
    public val freshUntilEpochMillis: Long? = null,
)

public class TransportResponse(
    public val statusCode: Int,
    body: ByteArray,
    public val metadata: TransportResponseMetadata = TransportResponseMetadata(),
) {
    private val bodyBytes: ByteArray = body.copyOf()
    public val body: ByteArray get() = bodyBytes.copyOf()
}

public fun interface Transport {
    public suspend fun execute(request: TransportRequest): TransportResponse
}

public data class RawResourceKey internal constructor(
    public val stableId: String,
    public val resourceClass: ResourceClass,
)

public class StoredRawResourceMetadata(
    public val contentType: String? = null,
    public val etag: String? = null,
    public val lastModified: String? = null,
    public val freshUntilEpochMillis: Long? = null,
    public val storedAtEpochMillis: Long,
)

public class StoredRawResource(
    bytes: ByteArray,
    public val contentDigest: String,
    public val metadata: StoredRawResourceMetadata,
) {
    private val storedBytes: ByteArray = bytes.copyOf()
    public val bytes: ByteArray get() = storedBytes.copyOf()
}

public interface Store {
    public suspend fun read(key: RawResourceKey): StoredRawResource?
    public suspend fun write(key: RawResourceKey, resource: StoredRawResource)
}
```

Every `ResourceLimits` value is in `[1, Int.MAX_VALUE]`. RenG alone constructs requests. Request metadata
contains only the three declared fields and at most one of `ifNoneMatch` and `ifModifiedSince`; a validated
stale record may retain both validators, but NORMAL prefers ETag when constructing its one conditional. The
exact `accept` value is `application/json` for style, TileJSON, sprite JSON, and GeoJSON;
`application/vnd.mapbox-vector-tile` for vector tiles; `image/png` for raster/DEM tiles, sprite images,
stickers, and model textures; and `model/gltf-binary` for GLB.

Consumer-created `TransportResponseMetadata`, `TransportResponse`, `StoredRawResourceMetadata`, and
`StoredRawResource` constructors deliberately validate no status, body shape, digest, metadata text, or epoch
value. They snapshot byte arrays, implement structural equality over their copied values, and redact bytes and
all metadata values from `toString()`. This permits RenG—not an adapter-side constructor—to translate malformed
consumer data to `INVALID_TRANSPORT_RESPONSE / TRANSPORT_VALIDATION` or
`STORE_INTEGRITY_FAILED / STORE_VALIDATION`. Request and request-metadata `toString()` methods disclose only
presence, class, and limits while redacting locator and metadata values.

During operation validation, optional metadata text must be a nonblank Unicode scalar sequence containing
neither CR nor LF; isolated UTF-16 surrogates are invalid. Epoch values must be non-negative. Transport response
validation uses this fixed precedence: (1) validate every metadata value; (2) branch on status; (3) for `200`,
require a nonempty body, then compare its size with the selected limit; (4) for `304`, require an exactly empty
body without applying the response-body limit, then require a conditional NORMAL request and its valid stale
baseline; (5) reject every other status. Therefore malformed metadata wins over status/body faults, an empty
`200` is `INVALID_TRANSPORT_RESPONSE`, an oversized nonempty `200` is `RESOURCE_LIMIT_EXCEEDED`, and every
nonempty `304` is `INVALID_TRANSPORT_RESPONSE` regardless of its size. Class-format validation follows those
generic checks and precedes the class write/visibility boundary.

A stored record must be nonempty, within its class limit, have an exact 64-character lowercase hexadecimal
SHA-256 digest matching its copied bytes, valid metadata, and class-format-valid content. Store has no remove
API.

## Diagnostics, failures, reports, and selectors

```kotlin
public enum class PipelineStage {
    CONFIGURATION,
    FRAME_PLANNING,
    FRAME_PREPARATION,
    RESOURCE_LOOKUP,
    STORE_READ,
    STORE_VALIDATION,
    TRANSPORT,
    TRANSPORT_VALIDATION,
    STORE_WRITE,
    RESOURCE_DECODING,
    RESOURCE_PARSING,
    SHADER_COMPILATION,
    GPU_RESOURCE,
    RENDER_TARGET,
    DRAW,
    RESOURCE_FREE,
    RENDERER_CLOSE,
    CONTEXT_ADOPTION,
}

public enum class RenGErrorCode {
    INVALID_VALUE,
    RESOURCE_LIMIT_EXCEEDED,
    UNSUPPORTED_PROJECTION_MODE,
    PREPARATION_ORDER_VIOLATION,
    PREPARATION_IN_PROGRESS,
    RENDERER_CLOSED,
    RENDER_CONTEXT_ADOPTION_REQUIRED,
    NO_CURRENT_RENDER_CONTEXT,
    DIFFERENT_CURRENT_RENDER_CONTEXT,
    UNSUPPORTED_RENDER_CONTEXT,
    FOREIGN_PREPARED_FRAME,
    PREPARED_FRAME_CLOSED,
    FOREIGN_RENDER_TARGET,
    STALE_RENDER_TARGET,
    INVALID_RENDER_TARGET,
    AMBIGUOUS_RESOURCE_ROUTE,
    RESOURCE_UNAVAILABLE,
    TRANSPORT_EXECUTION_FAILED,
    INVALID_TRANSPORT_RESPONSE,
    STORE_READ_FAILED,
    STORE_WRITE_FAILED,
    STORE_INTEGRITY_FAILED,
    RESOURCE_DECODE_FAILED,
    RESOURCE_PARSE_FAILED,
    UNSUPPORTED_RESOURCE_FEATURE,
    SHADER_COMPILE_FAILED,
    SHADER_LINK_FAILED,
    GPU_OPERATION_FAILED,
    IDENTITY_COLLISION,
}

public enum class DiagnosticSeverity { INFO, WARNING, ERROR }
public enum class DiagnosticCode {
    RESOURCE_RELOADED_AFTER_FREE,
    FAILURE_CONTEXT,
}

public data class Diagnostic internal constructor(
    public val code: DiagnosticCode,
    public val severity: DiagnosticSeverity,
    public val stage: PipelineStage,
    public val fieldName: String? = null,
    public val resourceClass: ResourceClass? = null,
    public val resourceKey: ResourceKey? = null,
    public val statusCode: Int? = null,
    public val limit: Long? = null,
    public val actual: Long? = null,
)

public fun interface DiagnosticSink {
    public fun emit(diagnostic: Diagnostic)

    public companion object {
        public val None: DiagnosticSink = DiagnosticSink { }
    }
}

public class RenGException internal constructor(
    public val code: RenGErrorCode,
    public val stage: PipelineStage,
    diagnostics: List<Diagnostic> = emptyList(),
) : RuntimeException("RenG failure: $code at $stage") {
    private val diagnosticSnapshot: List<Diagnostic> = diagnostics.toList()
    public val diagnostics: List<Diagnostic> get() = diagnosticSnapshot.toList()
}

public data class ResourceKey internal constructor(
    public val kind: ResourceKind,
    public val stableId: String,
    public val resourceClass: ResourceClass?,
)

public sealed interface ResourceSelector {
    public data object All : ResourceSelector
    public data class ByKind(public val kind: ResourceKind) : ResourceSelector
    public data class ByClass(public val resourceClass: ResourceClass) : ResourceSelector
    public data class ByKey(public val key: ResourceKey) : ResourceSelector
}

public data class ResourceUsage internal constructor(
    public val rawBytes: Long,
    public val decodedCpuBytes: Long,
    public val knownGpuBytes: Long?,
    public val hasUnknownGpuBytes: Boolean,
)

public data class ResourceReportEntry internal constructor(
    public val key: ResourceKey,
    public val residentGenerationCount: Int,
    public val retiredGenerationCount: Int,
    public val leaseCount: Int,
    public val reloadRequired: Boolean,
    public val usage: ResourceUsage,
)

public class ResourceReport internal constructor(
    entries: List<ResourceReportEntry>,
    public val totals: ResourceUsage,
) {
    private val entrySnapshot: List<ResourceReportEntry> = entries.toList()
    public val entries: List<ResourceReportEntry> get() = entrySnapshot.toList()
}

public data class ResourceFreeResult internal constructor(
    public val matchedKeys: Int,
    public val fullyFreedKeys: Int,
    public val deferredKeys: Int,
    public val alreadyFreeKeys: Int,
)
```

`RenGException.message` is the fixed string `"RenG failure: <CODE> at <STAGE>"`; its cause is always null.
Adapter messages, causes, locators, shader text, validators, and arbitrary metadata never enter exceptions or
diagnostics. A consumer-thrown `CancellationException` is never translated to `RenGException`; Kotlin stack
recovery may return a copy whose immediate cause is the original, so referential identity is not promised.
Diagnostic callbacks are serialized per renderer, occur outside renderer locks, may run on any calling thread,
and swallow sink failures.

`RESOURCE_RELOADED_AFTER_FREE` is always `WARNING / RESOURCE_LOOKUP`, carries the credential-free resource key
and its class exactly when the key is external, has every other optional field null, and is emitted once when a
fresh generation is first accessed after free. `FAILURE_CONTEXT` is always severity `ERROR`, is carried only in
`RenGException` (never emitted to the sink), and has the same stage as that exception. An exception carries
zero or one such diagnostic. The only nonnull fields are the ones named by the failure matrix below.

The complete `fieldName` allowlist is: `plans`, `frameIndex`, `projectionMode`, `camera.latitude`,
`camera.unwrappedLongitude`, `mapPosition.latitude`, `mapPosition.unwrappedLongitude`,
`mapPosition.altitude`, `screenPosition.x`, `screenPosition.y`, `placement.scale`, `geometry.latitude`,
`geometry.unwrappedLongitude`, `geometry.altitude`, `basemapTileInstances`, `responseBodyBytes`, `resource`,
`frameIdentity`, `animationSelector`, `shaderPair`, and `renderTarget`. No indexed path, locator, consumer key,
validator, or free-form field name is permitted. `statusCode` is present only for a received Transport status;
`limit` and `actual` are present together only for a numeric limit failure; resource class/key are present only
when that credential-free identity has already been established.

`ResourceSelector.ByClass` selects external keys only. Free categorizes each matched logical key exactly once:
`deferredKeys` if any generation remains leased, `fullyFreedKeys` if at least one held generation was released
and none remains leased, otherwise `alreadyFreeKeys`; their sum equals `matchedKeys`.

An external `ResourceKey` has nonnull class; every other kind has null class. Stable ids are lowercase
64-character SHA-256. Report entries are sorted by `(kind wire value, resource class wire value when present,
stableId)`. Byte counts are exact and non-negative. `knownGpuBytes` is zero when no GPU allocation exists,
null only when GPU allocations exist but none is measurable, and remains a known partial sum when measurable
and unmeasurable allocations coexist; `hasUnknownGpuBytes` carries that distinction.

## Renderer protocol and lifecycle

```kotlin
@JvmInline
public value class FramebufferName(public val value: UInt)

public sealed interface PreparedFrame : AutoCloseable {
    public val frameIndex: Long
    override fun close()
}

public sealed interface RenderTarget {
    public val framebufferName: FramebufferName
}

public class RendererConfiguration(
    public val outputPixelSize: OutputPixelSize,
    public val transport: Transport,
    public val store: Store,
    public val basemapStyle: ResourceLocator? = null,
    public val resourceLimits: ResourceLimits = ResourceLimits(),
    public val maximumBasemapTileInstances: Int = 512,
    public val maximumPreparationBatchSize: Int = 256,
    public val maximumConcurrentResourceOperations: Int = 8,
    public val diagnosticSink: DiagnosticSink = DiagnosticSink.None,
)

public sealed interface Renderer : AutoCloseable {
    public suspend fun prepare(
        plan: FramePlan,
        accessMode: ResourceAccessMode = ResourceAccessMode.NORMAL,
    ): PreparedFrame

    public suspend fun prepareBatch(
        plans: List<FramePlan>,
        accessMode: ResourceAccessMode = ResourceAccessMode.NORMAL,
    ): List<PreparedFrame>

    public suspend fun cancelPreparations()
    public fun clearFrameHistory()
    public fun queryResources(selector: ResourceSelector = ResourceSelector.All): ResourceReport
    public fun freeResources(selector: ResourceSelector = ResourceSelector.All): ResourceFreeResult
    public fun notifyGpuObjectsGone()
    public fun adoptCurrentRenderContext()
    public fun mintRenderTarget(framebufferName: FramebufferName): RenderTarget
    public fun draw(preparedFrame: PreparedFrame, renderTarget: RenderTarget)
    override fun close()
}
```

Configuration requires tile limit `1..4096`, batch limit `1..4096`, and resource concurrency `1..64`.
Renderer construction, when added after Cycles C and D, captures the caller's already-current exact context.
`adoptCurrentRenderContext()` explicitly captures the already-current replacement after declared object loss;
calling it while already `LIVE` fails `INVALID_VALUE / CONTEXT_ADOPTION` without querying or changing the
current context, so it can never act as an implicit context switch. RenG never creates, switches, presents, or
destroys the consumer's context or framebuffer.

Only one prepare invocation is active. `prepare` is a singleton atomic batch. Batch input is defensively
snapshotted, nonempty, within limit, strictly increasing by frame index, and entirely above committed history.
Before any resident lookup, Store/Transport call, decode/parse, Rentile call, or cache mutation, RenG validates
and purely plans every snapshotted item in input order: canonical segments, projection/transforms, LOD, tile
instances and budget, structural diff, and statically discoverable resource references. Any failure at this
full-batch planning barrier performs zero resource work. Content-discovered basemap children are necessarily
registered later, but before the first Rentile operation that can name them.

After the planning barrier, independent resource operations run at configured bounded concurrency. The
resource firewall below admits deterministic discovery frontiers in batch/resource traversal order and joins
equal prelookup Route Keys at first registration; completion order does not affect output. Return order equals
input order. The whole history commits only after every resource operation and every Prepared Frame lease
succeeds; failure or cancellation commits none, although fully validated content may remain cached. First diff
uses committed plan; later batch items use their immediate predecessor. Every Prepared Frame leases its
complete resource set.

`cancelPreparations()` snapshots only the active invocation, requests cancellation, and returns after it
terminates; with none active it is an idempotent no-op. `clearFrameHistory()` clears ordering, diff, and LOD
baseline without freeing or invalidating. Prepared Frames draw repeatedly and in any order until their
idempotent context-free close. Drawing never changes history.

Renderer states are `LIVE`, `AWAITING_CONTEXT_ADOPTION`, and terminal `CLOSED`. Object-loss notification is
context-free and idempotent, forgets handles without deleting, increments context generation, invalidates all
prior targets, and retains CPU state and Prepared Frames. Preparation/query/free/history/cancellation remain
available while awaiting adoption. Mint and draw fail adoption-required. Explicit adoption returns to live.

Renderer close fails without state change while preparation is active. Otherwise it invalidates all frames
and targets, clears history/resources, and deletes live handles only with the exact current context. After
object loss it is context-free. After closure, repeated close, cancellation, object-loss notification,
resource query/free, and Prepared Frame close are harmless; query/free return empty results. Other operations
fail renderer-closed.

Framebuffer name zero is valid. On mint and again before each draw, RenG temporarily binds the named
framebuffer while preserving the documented host state, accepts zero as the default framebuffer, requires a
nonzero name to identify an existing framebuffer object, and requires
`glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE`. A deleted, nonexistent, or incomplete
object fails `INVALID_RENDER_TARGET / RENDER_TARGET`; RenG does not infer or validate its dimensions.

Drawing may overlap GL-free preparation. Query and Prepared Frame close may overlap preparation or drawing;
draw holds a temporary lease. The consumer serializes GL-bound calls. Object-loss notification serializes
with an in-flight GL call before forgetting handles. Error precedence is active-preparation interference,
closed state, adoption-required state, Prepared Frame owner/closed state, Render Target owner/generation,
exact current context, deferred deletion, then operation-specific work.

The operation/state matrix is total; “fail” means no owner-state transition:

| Operation | `LIVE` | `AWAITING_CONTEXT_ADOPTION` | `CLOSED` |
|---|---|---|---|
| `prepare` / `prepareBatch` | allowed | allowed | fail `RENDERER_CLOSED / FRAME_PREPARATION` |
| `cancelPreparations` | snapshot barrier/no-op | snapshot barrier/no-op | no-op |
| `clearFrameHistory` | allowed unless prepare active | allowed unless prepare active | fail `RENDERER_CLOSED / FRAME_PLANNING` |
| `queryResources` | snapshot | snapshot | empty report |
| `freeResources` | exact current context required; active prepare rejected | context-free; active prepare rejected | empty result |
| `notifyGpuObjectsGone` | transition to awaiting after GL-call serialization | no-op | no-op |
| `adoptCurrentRenderContext` | fail `INVALID_VALUE / CONTEXT_ADOPTION` | validate and adopt current context | fail `RENDERER_CLOSED / CONTEXT_ADOPTION` |
| `mintRenderTarget` | exact context, queue drain, framebuffer validation | fail adoption-required | fail renderer-closed |
| `draw` | provenance, exact context, queue drain, target validation, draw | fail adoption-required | fail renderer-closed |
| `PreparedFrame.close` | idempotent context-free release | same | harmless no-op |
| `Renderer.close` | active prepare rejected; otherwise exact context while handles exist | context-free terminal close | no-op |

The failure mapping is likewise fixed. Every listed diagnostic is one `FAILURE_CONTEXT`; `—` means the
exception carries none. Unless a row says otherwise, the operation fails without Frame History or renderer
state change.

| Condition | Error and stage | Nonnull diagnostic fields | Additional effect |
|---|---|---|---|
| second prepare invocation | `PREPARATION_IN_PROGRESS / FRAME_PREPARATION` | — | no work starts |
| active prepare blocks history clear, free, or renderer close | `PREPARATION_IN_PROGRESS` at `FRAME_PLANNING`, `RESOURCE_FREE`, or `RENDERER_CLOSE` | — | no work starts |
| operation disallowed after close | `RENDERER_CLOSED` at the operation stage from the state matrix | — | none |
| adoption requested while already live | `INVALID_VALUE / CONTEXT_ADOPTION` | — | current context is not queried |
| mint/draw while awaiting adoption | `RENDER_CONTEXT_ADOPTION_REQUIRED` at `RENDER_TARGET` or `DRAW` | — | none |
| no current exact context | `NO_CURRENT_RENDER_CONTEXT` at `CONTEXT_ADOPTION`, `RENDER_TARGET`, `DRAW`, `RESOURCE_FREE`, or `RENDERER_CLOSE` | — | deletion queue untouched |
| a different context is current | `DIFFERENT_CURRENT_RENDER_CONTEXT` at `RENDER_TARGET`, `DRAW`, `RESOURCE_FREE`, or `RENDERER_CLOSE` | — | deletion queue untouched |
| current context cannot honor the GL contract | `UNSUPPORTED_RENDER_CONTEXT / CONTEXT_ADOPTION` (or `/ CONFIGURATION` at future construction) | — | no adoption/construction |
| foreign or closed Prepared Frame | `FOREIGN_PREPARED_FRAME / DRAW` or `PREPARED_FRAME_CLOSED / DRAW` | — | none |
| foreign, stale-generation, or invalid framebuffer target | `FOREIGN_RENDER_TARGET`, `STALE_RENDER_TARGET`, or `INVALID_RENDER_TARGET`, all at `RENDER_TARGET` | `fieldName=renderTarget` only for invalid target | none |
| empty batch | `INVALID_VALUE / FRAME_PLANNING` | `fieldName=plans` | planning barrier fails |
| batch-size or tile-instance budget exceeded | `RESOURCE_LIMIT_EXCEEDED / FRAME_PLANNING` | `fieldName=plans` or `basemapTileInstances`; `limit`, `actual` | planning barrier fails |
| frame index not strictly increasing/above history | `PREPARATION_ORDER_VIOLATION / FRAME_PLANNING` | `fieldName=frameIndex` | planning barrier fails |
| Globe selected in Cycle B | `UNSUPPORTED_PROJECTION_MODE / FRAME_PLANNING` | `fieldName=projectionMode` | planning barrier fails |
| Mercator coordinate-domain or GPU-representability violation discovered during planning | `INVALID_VALUE / FRAME_PLANNING` | exact field mapping: camera latitude/copy use `camera.latitude`/`camera.unwrappedLongitude`; map latitude/copy/altitude use `mapPosition.latitude`/`mapPosition.unwrappedLongitude`/`mapPosition.altitude`; screen GPU x/y use `screenPosition.x`/`screenPosition.y`; converted scale uses `placement.scale`; Geometry latitude/copy/altitude use `geometry.latitude`/`geometry.unwrappedLongitude`/`geometry.altitude` | planning barrier fails |
| same digest names different canonical frame or resource bytes | `IDENTITY_COLLISION / FRAME_PLANNING` or `/ RESOURCE_LOOKUP` | `fieldName=frameIdentity`, or `resource` plus established class/key | existing entry retained unchanged |
| exact routes collapse to one private Rentile key | `AMBIGUOUS_RESOURCE_ROUTE / RESOURCE_LOOKUP` | only `fieldName=resource`; class/key null | no call for the new route; completed parent calls remain |
| required valid content absent under the selected mode | `RESOURCE_UNAVAILABLE / RESOURCE_LOOKUP` | `fieldName=resource` plus class/key | no fallback |
| Store read throws non-cancellation | `STORE_READ_FAILED / STORE_READ` | class/key | adapter cause/message discarded; no Transport |
| stored record fails any validation | `STORE_INTEGRITY_FAILED / STORE_VALIDATION` | `fieldName=resource` plus class/key | no Transport/write/remove |
| Transport throws non-cancellation | `TRANSPORT_EXECUTION_FAILED / TRANSPORT` | class/key | latched sanitized failure; no retry |
| response has invalid metadata, unsupported status, empty `200`, nonempty `304`, or invalid `304` request/baseline relation | `INVALID_TRANSPORT_RESPONSE / TRANSPORT_VALIDATION` | class/key and received `statusCode`; `fieldName=responseBodyBytes` only for body shape | no write/fallback |
| accepted-status `200` body exceeds its byte ceiling after metadata/body-shape validation | `RESOURCE_LIMIT_EXCEEDED / TRANSPORT_VALIDATION` | `fieldName=responseBodyBytes`, class/key, received `statusCode`, `limit`, `actual` | no decode/write |
| Store write throws non-cancellation | `STORE_WRITE_FAILED / STORE_WRITE` | class/key | content not visible; a prior sprite-pair write may remain orphaned |
| encoded bytes cannot decode | `RESOURCE_DECODE_FAILED / RESOURCE_DECODING` | `fieldName=resource`, class/key | no write/use |
| encoded structure cannot parse or selector cannot resolve uniquely | `RESOURCE_PARSE_FAILED / RESOURCE_PARSING` | `fieldName=resource` or `animationSelector`, class/key when applicable | no write/use |
| valid format uses an unsupported feature | `UNSUPPORTED_RESOURCE_FEATURE / RESOURCE_PARSING` | `fieldName=resource`, class/key | no fallback |
| Geometry source lacks the exact accepted profile directive | `INVALID_VALUE / FRAME_PLANNING` | `fieldName=shaderPair` | planning barrier fails before resource work |
| geometry shader compile or link fails | `SHADER_COMPILE_FAILED` or `SHADER_LINK_FAILED` at `SHADER_COMPILATION` | `fieldName=shaderPair`, geometry-program key | source and driver text omitted |
| GL allocation/draw/deferred deletion fails | `GPU_OPERATION_FAILED` at `GPU_RESOURCE`, `RENDER_TARGET`, `DRAW`, `RESOURCE_FREE`, or `RENDERER_CLOSE` | credential-free key when one exists | no primary work after a drain failure; already deleted queued handles remain deleted |

A consumer `CancellationException` bypasses this table unchanged and commits no history. Constructor validation
throws the non-sensitive `IllegalArgumentException` described above, not `RenGException`; its message is not a
caller contract.

Free retires leased generations and immediately frees unleased generations. A subsequent access creates a
fresh generation and emits one reload warning. A point-in-time free result reports deferred if free linearizes
before the last lease release and fully freed if release linearizes first. Last release of a retired generation
queues GL deletion; exact-context mint, draw, free, and live-handle close drain that queue before primary work.
Object loss forgets queued handles. Deletion failure aborts the invoking operation before primary work.

## Pure transform and tile core

All geographic, camera, matrix, clipping, footprint, and rebasing math remains `Double`. Formula angles are
radians unless marked as degrees, column vectors use `p' = M p`, and composed transforms apply right-to-left.
Mercator render-local space is right-handed east/north/up; view space is right-handed output-right/output-up/
toward-viewer, with the camera looking along view `-z`. Matrix rows below are mathematical rows regardless of
upload layout.

### Mercator coordinates and WGS84 basis

The constants are:

```text
a = 6378137.0
f = 1 / 298.257223563
e² = f(2 - f)
C = 2πa = 40075016.68557849 metres
φmax = atan(sinh(π)) = 85.0511287798066 degrees
```

The displayed decimal values of `a`, `C`, and `φmax` are the normative binary64 constants; runtime validation
uses those literals rather than recomputing boundary values through platform `libm`.

Mercator preparation accepts camera, map-position, and Geometry-corner latitude only in `[-φmax, φmax]`.
`GLOBE` remains a valid public value but Cycle B planning fails `UNSUPPORTED_PROJECTION_MODE` before resource
work; Globe implementation belongs to Cycle G. For latitude `φ` and unwrapped longitude `λu` in degrees and
ellipsoidal altitude `h` metres:

```text
x = (λu + 180) / 360
φr = radians(φ)
y = 0                                           when φ = +φmax
    1                                           when φ = -φmax
    (1 - asinh(tan(φr)) / π) / 2                otherwise
z = h / (C cos(φr))
copyIndex = floor(x) = floor((λu + 180) / 360)
```

The endpoint definitions avoid binary64 drift and are not clamping. `copyIndex` must be in
`[-16384, 16384]`. Mercator `x` is non-periodic: `x(λu + 360n) = x(λu) + n`; RenG never wraps it or chooses a
nearest periodic delta. The closed planning support is `x ∈ [-16384, 16385]`, where the upper edge only closes
copy 16384 and does not admit copy 16385, and `y ∈ [0, 1]`. Logical world size at fractional camera zoom `zc`
is `S = 512 × 2^zc`; encoded Rentile image dimensions do not affect it.

WGS84 flattening defines altitude and local orientation, not an ellipsoidal correction to Mercator `x`, `y`,
or scale. For exact local-basis construction only, canonicalize longitude as
`k = floor((λu + 180) / 360)`, `λ0d = λu - 360k`, `λ0r = radians(λ0d)`, then:

```text
ν = a / sqrt(1 - e² sin²φr)
ECEF = ((ν+h) cosφr cosλ0r,
        (ν+h) cosφr sinλ0r,
        ((1-e²)ν+h) sinφr)
East  = (-sinλ0r,       cosλ0r,      0)
North = (-sinφr cosλ0r, -sinφr sinλ0r, cosφr)
Up    = ( cosφr cosλ0r,  cosφr sinλ0r, sinφr)
```

ECEF subtraction is forbidden for Mercator horizontal camera-to-point displacement because it would erase
world copies. For map point `p` relative to the camera's altitude-zero ground anchor `c`:

```text
eastPixels  = (xp - xc) S
northPixels = (yc - yp) S
upPixels    = zp S = hp S / (C cos(φp))
pixelsPerMetre(φ) = S / (C cosφ)
metresPerPixel(φ) = C cosφ / S
```

Only finite camera-relative map/Geometry values that remain finite after binary32 conversion may cross the GPU
boundary. Screen-position x/y and the final converted MAP or SCREEN scale must likewise remain finite after
binary32 conversion; screen-position z stays a CPU-side `Double` compositing key and is not converted for
positioning. The failure matrix fixes the exact diagnostic field for each rejected latitude, world copy,
altitude, screen coordinate, or converted scale. Absolute Mercator world coordinates never convert to `Float`,
and Float values never feed planning, history, culling, footprint, or tile selection.

### Camera, projection, rays, and finite footprint

Let bearing `b` be clockwise from north, pitch `p`, output width/height be positive `W`/`H`, aspect `A=W/H`,
vertical field of view `θv=π/4`, and `F=1/tan(θv/2)=1+sqrt(2)`. In east/north/up coordinates:

```text
mapForward   = (sin b, cos b, 0)
right        = (cos b, -sin b, 0)
cameraForward = sin p mapForward - cos p Up
cameraUp      = cos p mapForward + sin p Up
cameraBack    = -cameraForward
D = H F / 2
EYE = D cameraBack
```

`right × cameraUp = cameraBack`; at zero bearing output top is north, and at zero pitch the camera looks
straight down. For camera-relative point `r`:

```text
xv = right · r
yv = cameraUp · r
zv = cameraBack · r - D
depth d = -zv = D - cameraBack · r
```

Near distance is exactly `n=1.0` logical pixel and there is no finite far plane. The reverse-Z OpenGL
`[-1,1]` clip projection is:

```text
P = [ F/A  0   0   0  ]
    [ 0    F   0   0  ]
    [ 0    0   1   2n ]
    [ 0    0  -1   0  ]

wclip = d
zclip = 2n - d
zNdc = 2n/d - 1
windowDepth = n/d
```

Thus `d=n` maps to depth 1 and `d→∞` approaches 0; `d<n` is near-clipped and `d<=0` is behind the eye.
The downstream GL contract clears depth to zero and uses reverse comparison, never a finite-far fallback.

For physical pixel centre `(sx,sy)=(i+0.5,j+0.5)`:

```text
ξ = 2sx/W - 1
η = 1 - 2sy/H
u = Aξ/F
v = η/F
direction = u right + v cameraUp + cameraForward
q = cos p - v sin p
```

`q<=0` is horizon/sky and selects no ground. For `q>0`, intersect the altitude-zero Mercator ground:

```text
t = D cos p / q
rightG = u t
forwardG = D v / q
eastG = cos b rightG + sin b forwardG
northG = -sin b rightG + cos b forwardG
xG = xc + eastG/S
yG = yc - northG/S
```

Rows with `t<n` are near-clipped; finite Mercator support is applied to the footprint below. Let `J` be output
rows whose pixel-centre rays have `q>0` and `t>=n`. `J` is contiguous; if empty, the footprint is empty.
Otherwise project the four corners of the closed pixel-centre rectangle
`[0.5,W-0.5] × [min(J)+0.5,max(J)+0.5]`, then clip its closed point, segment, or convex polygon against
`x ∈ [-16384,16385]`, `y ∈ [0,1]`. Degenerate one-pixel dimensions remain valid. Above-horizon, near-clipped,
and out-of-support regions select no tile. Cycle B plans altitude-zero planar ground; terrain displacement
requires a separately proven planner.

### LOD and closed-cell tile selection

Without history, selected integer LOD is `clamp(ceil(zoom - 0.5), 0, 22)`, which gives nearest-integer with
midpoint ties downward. With prior integer `L`, repeatedly increment while `zoom >= L+0.75` and `L<22`, then
repeatedly decrement while `zoom < L-0.75` and `L>0`. Every successfully prepared Mercator plan advances
provisional LOD history, even with no configured Basemap Style or with `drawBasemap=false`; those cases produce
no footprint, budget use, or acquisition. Within a batch each plan uses its predecessor's provisional LOD,
and only the final whole-batch commit makes it history.

For `N=2^L`, tile coordinates are `tileX=xG N`, `tileY=yG N`. Admissible unwrapped instances have
`unwrappedX ∈ [-16384N, 16385N-1]` and integer `tileY ∈ [0,N-1]`; each owns mathematical closed cell
`[unwrappedX,unwrappedX+1] × [tileY,tileY+1]`. All cells intersecting the closed footprint are selected, so a
shared edge selects both adjacent admissible cells. Predicates expand cells by
`CELL_EPSILON=1e-10` tile-coordinate units. For footprint bounds `(xmin,xmax,ymin,ymax)`, inclusive candidate
bounds are:

```text
qx0 = ceil(xmin - 1 - ε)
qx1 = floor(xmax + ε)
qy0 = ceil(ymin - 1 - ε)
qy1 = floor(ymax + ε)
```

clamped to the admissible ranges. Intersection is inclusive when a footprint vertex is in the expanded cell,
an expanded-cell corner is in the convex footprint, or edges intersect; point and segment footprints use the
same predicates. Instances are ordered `(tileY,unwrappedX)`. For each:

```text
instanceCopy = floor(unwrappedX / N)
canonicalX = unwrappedX - N floor(unwrappedX / N)    // [0,N-1], including negatives
```

The Tile Budget counts unwrapped instances before acquisition or canonical deduplication; excess fails
planning with zero acquisition. Canonical Rentile resources are deduplicated as `(L,canonicalX,tileY)` and
ordered `(L,tileY,canonicalX)`.

### Placement resolution

Position anchoring selects draw regime. Map position uses latitude/unwrapped-longitude/WGS84 ellipsoidal
altitude and is depth-tested. Screen position uses continuous top-left physical-pixel coordinates,
half-integer pixel centres, and z as compositing index. Greater z is later; equal z preserves plan order,
stickers then models, later entries on top.

Rotation resolves independently as right-handed fixed-axis X, then fixed-axis Y, then fixed-axis Z, equivalently
extrinsic XYZ, with column-vector matrix `Q=Rz(rotation.z) Ry(rotation.y) Rx(rotation.x)`. SCREEN rotation starts
from screen-right/screen-up/toward-viewer. MAP rotation starts from WGS84 east/north/up at the drawn thing's map
position when `positionMode=MAP`; when `positionMode=SCREEN`, it uses
`(camera.latitude,camera.unwrappedLongitude,0m)` and never reinterprets screen coordinates as geography.

For any geographic point `q`, let `B(q)` be the ECEF matrix whose columns are exactly `[East(q), North(q),
Up(q)]` from the formulas above. Let `a` be the geographic anchor selected for a MAP rotation or MAP scale
property and `c` the camera ground anchor. For MAP rotation, a local model direction reaches camera ENU as
`vc = B(c)ᵀ B(a) Q vlocal`; the camera view basis then maps it as `vview = V vc`, where `V` has mathematical
rows `[rightᵀ, cameraUpᵀ, cameraBackᵀ]`. Thus the complete direction transform is `V B(c)ᵀ B(a) Q`;
world-copy-equivalent anchors have equal bases, while geographically different anchors do not. This basis
conversion affects orientation only—Mercator position displacement still uses the non-periodic projected
subtraction above and never ECEF subtraction.

Scale resolves independently. SCREEN scale is output pixels per local unit. MAP scale is metres per local unit
at the same geographic anchor and converts to `scale × S/(C cos(anchorLatitude))` logical pixels per local
unit. Rotation and scale anchoring never change the draw regime chosen by position. Every mode combination is
valid. Geometry has no Placement.

## Canonical identity and diff

Canonical roots follow ADR 0018. Every root begins bytes `52 4e 47 43 01 KK`. A field is
`tag:u16be | payloadLength:u32be | payload`, with strictly increasing unique tags. Strings are exact UTF-8 and
the encoder rejects isolated UTF-16 surrogates instead of replacing them; enums use the declared nonzero
`u16be` wire values; booleans are one byte `0` or `1`; `frameIndex` and `AnimationSelector.Index.value` are
`u64be`; finite canonical binary64 values are eight-byte IEEE-754 big-endian; lists are a `u32be` count plus
`u32be`-length-prefixed elements; and optional values carry one-byte presence `0` or `1`. No other integer
width is implicit. Ordered lists and duplicates remain content.

Wire values are: projection MERCATOR=1/GLOBE=2; anchoring MAP=1/SCREEN=2; selector INDEX=1/NAME=2;
resource kinds EXTERNAL=1, GEOMETRY_PROGRAM=2, INTERNAL_PIPELINE=3, OFFSCREEN_SURFACE=4; resource classes in
the declaration order above, numbered 1 through 11. `ResourceLocator` has no nested field stream: every
locator-valued field payload is the exact UTF-8 encoding of `ResourceLocator.value`. For an optional locator,
the field payload is presence byte `0`, or presence byte `1` followed immediately by that exact UTF-8; the
outer field length delimits it, so no second string length is present.

Frame root kind is 1 and has tags: 1 frameIndex, 2 camera, 3 projection, 4 drawBasemap, 5 stickers, 6 models,
7 geometries. Camera tags are latitude=1, longitude=2, zoom=3, bearing=4, pitch=5. Vector tags are x=1, y=2,
z=3. Placement tags are positionMode=1, position=2, rotationMode=3, rotation=4, scaleMode=5, scale=6.
Sticker is placement=1/image=2. Model is placement=1/glb=2/optional texture=3/tracks=4. Track is selector=1/time=2;
selector is kind=1/value=2. Geometry is topLeft=1/bottomRight=2/shaderPair=3; Shader Pair is vertex=1/fragment=2.

Frame identity is `reng-frame-v1:` plus lowercase SHA-256. External resource root kind 2 contains kind=1,
class=2, exact locator=3. Geometry-program root kind 3 contains kind=1, shader-profile wire version=2 encoded
as `u16be` value `1`, vertex source=3, and fragment source=4. Pipeline and offscreen roots are domain-separated
kinds 4 and 5; their owning cycles must freeze descriptor tags before creating those resource entries.
Canonical bytes are retained beside each hash; different bytes under one digest fail `IDENTITY_COLLISION`
without replacing or sharing. SHA-256 is dependency-free common Kotlin and must retain standard known-answer
tests.

Diff compares canonical segment bytes, never hashes alone. Its fixed segment table is the frame-root tag order:
`frameIndex`, `camera`, `projectionMode`, `drawBasemap`, `stickers`, `models`, `geometries`; each segment is that
field's canonical payload, so list order and duplicate drawn things remain observable.

A plan's logical resource traversal is: active basemap subtree; sticker images in sticker order; for each model
in model order, GLB then optional texture; geometry programs in geometry order. The basemap subtree is:
configured style; sprite JSON then sprite image when referenced; style source ids sorted by unsigned
lexicographic exact UTF-8 bytes, with each source's TileJSON or GeoJSON before its tiles; then canonical tile
resources deduplicated from the selected unwrapped instances and sorted by `(lod, tileY, canonicalX)`. JSON
object member order never determines traversal; arrays retain document order. Within a validated parent, any
other resource children use their declared array order, or the same UTF-8 key order for object members.

Previous and current traversal lists are independently deduplicated by `ResourceKey` at first occurrence.
`retain` is current-list order filtered to keys in the previous set; `acquire` is current-list order filtered to
keys absent from the previous set; `release` is previous-list order filtered to keys absent from the current
set. These diff lists are deterministic planning/reuse facts, not lease mutations: an earlier Prepared Frame's
complete lease remains until that frame closes. After all batch resource operations succeed, RenG installs
complete frame leases in batch order and each frame's traversal order. A later failure or cancellation rolls
back only leases installed by that invocation, in exact reverse installation order.

## Resource-operation firewall

One preparation invocation owns one operation registry. A prelookup **Route Key** is `(access mode, exact
locator, exhaustive Resource Class, selected response limit)`. Every equal Route Key in that invocation joins
one registry record and ultimately the same resolution, including one freshness sample, resident decision, at
most one Store read, and any Transport outcome. Static preregistration records join/collision identity only; it
does not assign execution order, sample time, or permission to start work. After lookup determines conditionals,
the final Transport-latch key is the Route Key plus all three exact request metadata values. No route resolution
or latch survives the invocation or crosses access modes.

The resource scheduler is a bounded FIFO over the concatenated batch/resource traversal. Static direct-route
occurrences are preregistered during the full-batch planning barrier for joining and static collision detection,
but remain unordered and ineligible until the traversal releases them. A node whose validated bytes can
discover children is a **discovery frontier**: every occurrence after that node in depth-first traversal—including
later basemap siblings, stickers, models, geometries, and later plans—is withheld until the node has validated
and registered its complete child set in the deterministic order above. A closed frontier of leaf occurrences
is released in that order and may execute concurrently up to the configured bound. When a plan's basemap
subtree closes, its direct-resource segments are released; when that plan's complete graph closes, the next plan
may be released even while already enqueued leaves finish. This makes dynamic discovery executable without
pretending that unknown routes were in the pure planning barrier.

The eligibility walk, not preregistration, assigns a monotonically increasing **route ordinal**. When the first
logically eligible occurrence of a distinct Route Key reaches the FIFO, its shared registry record receives the
next ordinal and may start; later occurrences join that ordinal. If an earlier dynamic occurrence matches a
static route preregistered from a later segment, the dynamic occurrence activates the shared record and receives
the earlier ordinal. Frontier withholding guarantees that no still-unknown occurrence can precede an ordinal
already assigned. Execution completion may be out of order, but route outcomes are retired in ordinal order. A
completed later outcome is buffered until every lower assigned ordinal has retired. The first non-success
outcome retired in that order is the invocation's route-terminal outcome. Once a failure is buffered, no
not-yet-started higher ordinal begins; lower ordinals continue. Once that failure or route-level cancellation is
selected, active higher ordinals are cancelled and their cleanup outcomes cannot replace it. This fixes failure
selection independently of worker completion timing while retaining bounded concurrency.

An adapter-thrown `CancellationException` is a route outcome at that route's ordinal; if selected, it is
re-thrown under the unwrapped cancellation rule. Cancellation of the caller coroutine or a request captured by
`cancelPreparations()` races through one atomic invocation-terminal slot against in-order route retirement: an
external cancellation that claims the slot first wins, while a failure already selected first remains the
outcome. Cancellations generated only to clean up higher ordinals after terminal selection never participate in
arbitration.

Every exact route is registered before the first Store, Transport, or Rentile adapter work for that route.
Store callbacks match it through Rentile's sanitized private key. If a distinct Route Key collapses to an
already registered private key, preparation fails `AMBIGUOUS_RESOURCE_ROUTE / RESOURCE_LOOKUP`; its one
`FAILURE_CONTEXT` has only `fieldName=resource`, with resource class/key null rather than choosing one of the
two routes. A static collision detected at the planning barrier makes zero consumer calls. A dynamically
discovered collision cannot undo completed parent calls, but performs no Store/Transport call for the new
route, marks the private key unusable for either route for the rest of the invocation, and makes no later
consumer call through it.

After request metadata is finalized, the first consumer Transport outcome is latched as a defensive response,
sanitized failure, or unwrapped cancellation. Rentile's private retry may call its adapter again, but the
consumer Transport is called at most once and no retry delay is honored.

Each Route Key resolution captures exactly one non-negative `Clock.System` epoch-millisecond sample before its
first freshness decision and reuses it for every decision and record it writes. A record is fresh only when
nonnull `freshUntilEpochMillis > sample`; absence, equality, or an earlier value is stale. Held resident records
are valid by construction. Lookup order is exact:

- `NORMAL`: use a fresh resident and do not read Store. With no resident or a stale resident, read Store exactly
  once. A nonnull invalid Store record is terminal even when a valid stale resident exists. A valid Store record
  supersedes the stale resident for this operation: use it immediately when fresh, or retain it as the stale
  baseline. If Store returns null, retain the stale resident as baseline. When that baseline has an ETag, send
  it; otherwise, when it has last-modified, send that. A stale baseline with neither usable validator causes an
  unconditional request that accepts only `200`; its bytes are not a fallback and a `304` is invalid. With no
  baseline, likewise request an unconditional `200`. A new Store/fetched value creates a new resident
  generation; existing leased generations remain valid.
- `CACHE_ONLY`: use a valid resident regardless of freshness and do not read Store. Without a resident, read
  Store exactly once, validate it, and use it regardless of freshness; missing content fails unavailable.
  Transport is never called.
- `RELOAD`: skip resident and Store reads, send no validator, and accept only a full `200`.

A conditional NORMAL request sends baseline ETag first, otherwise last-modified. A valid empty `304` preserves
the baseline bytes and content digest; for content type, ETag, last-modified, and fresh-until independently, a
nonnull response value overrides the baseline and null retains it. The merged record sets
`storedAtEpochMillis` to the Route Key's sample and passes complete record/class validation. A full `200`
computes its digest and likewise uses that sample as stored-at. No mode follows redirects, ranges,
substitutions, retries, repair, or fallbacks.

Every consumer stored record is checked for copied-byte shape, exact digest, metadata, class limit, and class
format before Rentile sees it. Every JSON resource rejects duplicate object member names rather than retaining
first or last. Invalid content is terminal with no Transport/write/remove. Rentile's private remove callback
records the sanitized integrity failure and mutates no consumer state.

Write timing is class-specific and “visible to preparation” means installed in the successful item's resource
graph or reachable by a returned Prepared Frame—not temporary validation input. A record selected directly
from a resident generation or Store is never redundantly written; it still passes the mode's validation and any
required decode, parse, compilation, or joint validation before new item visibility. The write rules below apply
to Transport-produced `200` records and metadata-refreshed records produced by a merged `304`.

TileJSON, vector, raster, and GeoJSON may write from a matched Rentile callback after parser/decoder validation.
DEM additionally passes terrain encoding validation. Sprite JSON and PNG are staged and jointly validated,
including atlas bounds, before either sequential write; if the second fails, the first content-addressed record
may remain as an unused orphan, but no atlas becomes visible. Direct sticker/GLB/texture content is decoded or
parsed before write. Every required write succeeds before content becomes visible.

`BASEMAP_STYLE` is the deliberate exception to write-before-compilation. A selected resident style uses its
already compiled resident generation and performs no Store write. Valid Store-sourced style bytes are privately
compiled; after every referencing batch item completes all other work, the compiled result may be installed
without rewriting the record that was read. Transport-produced full or `304`-merged style bytes may enter a
private staged Rentile input solely to obtain `Prefetched`; staging is not residency, Store write, item
completion, or Prepared Frame visibility. After successful compilation and after every referencing batch item
has completed all other work, RenG performs exactly one required style write. Only that successful write
installs the compiled style into those items; failure discards the staged compiled result and fails the batch.
No Store-sourced or resident style can become newly visible unless the same compilation/whole-batch success
conditions hold. Tile substitution is disabled and `retryExact` is never called.

## Cycle B implementation boundary and gates

Cycle B implements public constructors/validation, defensive values, the protocol types, canonical encoding
and SHA-256, structural diff, projection/transform/tile planning, plus three internal production decision
engines. These engines are common Kotlin product code that later cycles drive with real adapters; they perform
no I/O or GL work themselves:

- `OrderedPreparationStateMachine` owns batch snapshots, ordering/history/LOD transitions, pure-planning
  barriers, same-order result assembly, and reverse lease-install rollback from supplied resource outcomes.
- `ResourceOperationStateMachine` owns Route Key registration/joining, discovery-frontier release, route
  ordinals and failure arbitration, lookup decisions from supplied resident/Store observations, exact request
  metadata, generic Transport-response validation and `304` merging, content provenance, and the required
  validation/write/visibility action sequence. It emits immutable actions and consumes supplied outcomes; it
  never calls `Transport`, `Store`, Rentile, a decoder, or a parser and owns no resident cache.
- `RendererLifecycleStateMachine` owns the three owner states and the total operation/error precedence from
  supplied facts such as active preparation, handle presence, context relation, frame/target provenance,
  generation, and framebuffer validity. It never queries a platform context, binds a framebuffer, or deletes a
  GL object.

Internal protocol fakes exercise those engines without exposing a renderer factory or obtaining a public
`Renderer`. Cycles C and D connect real resource/cache and GL/context observations and execute the emitted
actions. Cycles E and F add shader compilation and pixels. Cycle B therefore implements neither acquisition nor
resource residency, decode/parse, Rentile calls, GL bindings, context discovery, framebuffer operations,
shaders, or pixels.

Required gates are:

- `checkKotlinAbi` with a reviewed complete public dump and no Rentile/platform types;
- Android host, `linuxX64Test`, and `macosArm64Test` for common pure-core behavior;
- compile gates for both iOS targets and Linux ARM64;
- deterministic cross-runtime canonical bytes, SHA-256 known answers, structural equality, validation/redaction,
  and list/byte defensive-copy tests, including mutation through Android/JVM platform interop;
- projection controls for copy-preserving `x(λ+360n)`, both accepted copy bounds and rejected input copies
  -16385/16385, exact Mercator endpoints, ECEF/ENU at 0/90 degrees and copy-equivalent longitudes,
  anchor-to-camera basis, reverse-Z with normative `n=1`, horizon `q=0`, near boundary `t=n`, one-pixel
  degenerate footprints, shared closed tile edges, complete tile-coverage and deterministic-budget controls,
  MAP-property camera fallback, suppressed-basemap LOD thresholds/multi-level jumps, and every
  altitude/screen-coordinate/scale GPU-representability diagnostic;
- `OrderedPreparationStateMachine` tests for the complete planning barrier, history commit, rollback, same-order
  results, and cancellation;
- `ResourceOperationStateMachine` tests using supplied fake observations/outcomes for static preregistration,
  discovery-frontier release, a dynamic child activating a route preregistered from a later static segment,
  route joining, completion-order-independent failure arbitration, external and route cancellation precedence,
  resident/Store precedence, stale baselines without validators, ordered Transport validation, exact `304`
  merge, content provenance, and class write/visibility actions. These tests invoke no consumer adapter and no
  decoder/parser;
- `RendererLifecycleStateMachine` tests using supplied context/target classifications for every state transition
  and error/diagnostic precedence, with no platform context or GL call;
- repository policy and the existing local publication plus fresh six-target consumer smoke before merge.

Cycle B produces no pixels, no resource decoder/parser, no production cache, no consumer adapter call, no
Rentile acquisition, no GL call, no platform context, no factory, no capture, no encoder, and no new Gradle
subproject.
