# RenG

RenG provides the vocabulary for a Kotlin Multiplatform 3D renderer that draws worlds on top of
[Rentile](https://rohittp.com/rentile/) basemap tiles into a surface the consumer owns. RenG draws
frames; it owns no window, no render loop, no capture path, and no encoder.

## Language

### The frame

**Frame Plan**:
A complete, self-contained definition of one frame's content, drawn by whichever renderer prepared it.
Its required non-negative `frameIndex` orders strict, history-aware preparation within that renderer;
`drawBasemap`, defaulting to true, may suppress ground for that frame.
_Avoid_: Scene graph, render command, frame delta, mutation batch

**Camera**:
The view of a **Frame Plan**, with geographic latitude, unwrapped longitude, zoom, bearing, and pitch.
Latitude and longitude are finite degrees; latitude lies in `[-90, 90]`, while longitude preserves the
selected world copy and is never wrapped or clamped by RenG. Zoom is a finite fractional value in
`[0, 22]`; Mercator has `512` logical pixels at zoom zero and scales by `2^zoom`, independently of tile
image resolution. Bearing is finite clockwise degrees from true north in `[0, 360)`. Pitch is finite degrees
in `[0, 90)`, where `0` looks straight down and increasing values tilt toward the horizon. RenG uses a
fixed `45`-degree vertical field of view. Map-occluded projection uses a fixed one-logical-pixel near
plane and reverse-Z infinite-far depth.
_Avoid_: Viewport, viewpoint, map camera, view state

**Projection Mode**:
How a **Frame Plan** projects geographic state, either `MERCATOR` or `GLOBE`. If a renderer does not yet
support the selected mode, preparation fails before acquisition or drawing; RenG never substitutes a
different mode.
_Avoid_: Anchoring mode, coordinate space, projection fallback

**Frame History**:
The renderer's clearable record of its last successfully prepared frame index, **Frame Plan**, and selected
basemap tile LOD. Only one prepare invocation runs at a time. A batch's indices must be strictly increasing
and above history; planning and LOD selection proceed in index order while independent resource work runs
in parallel. History commits only when the whole batch succeeds, and returned prepared frames preserve
input order. Structural diffing uses the last successfully prepared plan as the first baseline and each
immediately preceding input plan as the next baseline within a batch; missing history is an empty baseline.
A diff may reuse content-keyed work, but every **Prepared Frame** leases the complete resource set derived
from its own plan. Failure or cancellation exposes no partial history, though valid acquired content may
remain cached. For zoom `n + f`, an upward transition occurs at `f >= 0.75` and a downward transition at
`f < 0.25`; without history, the nearest integer LOD is selected with midpoint ties downward. The
context-free `clearFrameHistory()` clears the structural-diff and LOD baseline, permits a new sequence,
and neither frees resources nor invalidates prepared frames. Drawing never changes history.
_Avoid_: Cache, draw order, partial batch commit, previous-frame mutation

**Tile Budget**:
The renderer's maximum number of unwrapped basemap tile instances in one prepared frame at its selected
LOD. It defaults to `512`, is configurable from `1` through `4096`, and fails preparation
before acquisition when exceeded; RenG never drops required tiles.
_Avoid_: Cache size, tile limit fallback, resource retry budget

**Renderer Configuration**:
The renderer-lifetime values fixed at setup: required output pixel size, **Transport**, and **Store**;
optional **Basemap Style**, defaulting to none; maximum basemap tile instances, defaulting to `512`;
maximum preparation batch size, defaulting to `256`; maximum concurrent resource operations, defaulting to
`8`; and a non-throwing **Diagnostic** sink, defaulting to none. The already-current **Render Context** is
passed separately to renderer creation and setup does not suspend; style acquisition occurs during frame
preparation.
_Avoid_: Frame Plan, render options, mutable settings

**Preparation Budget**:
The renderer's strict ordered-batch bounds: at most `maximumPreparationBatchSize` frames, default `256`
and configurable from `1` through `4096`, with at most `maximumConcurrentResourceOperations` independent
resource operations, default `8` and configurable from `1` through `64`.
_Avoid_: Coroutine dispatcher, unbounded async, transport retry count

**Basemap Style**:
The style document identified by one **Resource Locator**, acquired through the configured adapters, and
drawn by a renderer for its whole lifetime. It is fixed for that renderer and never named by a
**Frame Plan**; a renderer configured with no style draws no ground. A styled renderer also draws no ground
for a plan whose `drawBasemap` is false.
_Avoid_: Map style, theme, basemap, style profile

**Prepared Frame**:
A network-free, GL-free rendering input holding every resource one **Frame Plan** needs, produced
before any drawing occurs and owned by the renderer that prepared it. It may be drawn repeatedly until
its idempotent, context-free `close()` releases its resource leases; closing it performs no immediate GL
deletion.
_Avoid_: Frame buffer, render pass, draw queue, prepared batch

**Render Target**:
A renderer-minted identity for the caller-owned framebuffer RenG composites one finished frame into. It
publicly carries only `FramebufferName(UInt)`; its dimensions are the renderer's configured output size,
not a property of the target. Hidden renderer and context-generation identity make foreign and stale
targets invalid.
_Avoid_: Surface, canvas, viewport, swapchain

**Render Context**:
The caller's OpenGL context, already current on the calling thread, that RenG issues every GL call
against. A renderer is affine to the exact context identity captured at setup, not merely to a share
group or to whichever context is current. GL-bound operations may move between threads with that exact
context, but the consumer serializes them. Preparation invocations are serialized for strict frame-index
history; one batch still performs independent resource work concurrently.
_Avoid_: Surface, device, GL session, EGL context

**GPU Object Loss**:
The consumer's declaration that the renderer's GL objects no longer exist. `notifyGpuObjectsGone()` is
context-free, forgets handles without deleting them, retains CPU state, and invalidates prior render
targets. Further GL work requires explicit adoption of an already-current replacement **Render Context**.
_Avoid_: Free, close, context switch, automatic recovery

**Renderer Close**:
The idempotent non-suspending terminal operation that rejects new work, releases CPU state, and deletes
live GL objects. Active preparation makes close fail without changing state; the consumer first uses the
suspending cancellation barrier. Close requires the exact current **Render Context** only while live
handles exist; after **GPU Object Loss** or an earlier close, it is context-free.
_Avoid_: Free resources, GPU object loss, frame close, reset

**Preparation Cancellation**:
The idempotent suspending snapshot barrier requested by `cancelPreparations()`. It cancels only the prepare
invocation active when called and returns after that invocation terminates; no active invocation is a no-op.
Cancellation propagates unchanged, commits no **Frame History**, and is not a persistent cancellation mode.
_Avoid_: Renderer close, cancel latch, frame close, partial batch commit

### Placement

**Vector3**:
A finite three-component numeric value named `x`, `y`, and `z`; the owning property and its
**Anchoring Mode** define what those components mean. Construction canonicalizes negative zero to
positive zero and rejects every non-finite component.
_Avoid_: Vector2, point, tuple, coordinate

**Placement**:
The resolved position, rotation, and scale of one drawn thing, each property carrying its own
**Anchoring Mode**.
_Avoid_: Transform, matrix, pose

**Anchoring Mode**:
Whether one placement property is resolved against the map (`MAP`) or against the screen (`SCREEN`).
_Avoid_: Coordinate space, reference frame, projection mode

**Rotation**:
A finite `Vector3` of canonical degrees in `[-180, 180)`, applied as right-handed intrinsic X, then Y,
then Z rotations. Map-anchored rotation starts from east/north/up axes; screen-anchored rotation starts
from screen-right/screen-up/toward-viewer axes.
_Avoid_: Quaternion, orientation matrix, extrinsic rotation

**Scale**:
A finite non-negative scalar converting asset-local units. Map-anchored scale is metres per local unit;
screen-anchored scale is output pixels per local unit. Sticker local dimensions are encoded-image pixels,
and Model local dimensions are GLB coordinates. Zero scale is valid.
_Avoid_: Size, zoom, density-independent scale

**Screen Anchoring**:
Resolution against continuous output-pixel screen space. The output bounds are `[0, width]` by
`[0, height]`, and pixel centres lie at half-integer coordinates. `position.x` and `position.y` locate
the object's local origin from the top-left, with positive x rightward and positive y downward. `position.z` is a
compositing z-index rather than depth; greater values composite on top. Equal z-index values use stable
plan order: stickers in list order, followed by models in list order, with later entries on top.
_Avoid_: Overlay mode, 2D mode, billboard

**Map Anchoring**:
Resolution against map space, in which the drawn thing is depth-tested and occluded by the 3D scene.
A map position's `(x, y, z)` components mean `(latitude degrees, unwrapped longitude degrees, WGS84
ellipsoidal altitude metres)`. Altitude accepts any finite value, but frame planning rejects values that
cannot remain finite and representable through camera-relative Double and GPU-bound Float conversion.
_Avoid_: World mode, 3D mode, geo mode

**Draw Regime**:
One of the two distinct ordering-and-occlusion rules a frame draws under — screen-composited or
map-occluded.
_Avoid_: Layer, pass, stage

### Drawn things

**Sticker**:
A **Placement** paired with one PNG image identified by a **Resource Locator**, drawn as a centred local XY
quad whose width and height are the image's pixel dimensions, with +x right, +y up, and +z normal.
_Avoid_: Marker, pin, decal, sprite

**Model**:
A **Placement** paired with one GLB mesh locator, an optional base-colour texture override, and its
**Animation Track** states. Its authored GLB origin, axes, and local units are preserved unchanged before
Placement. GLB buffers and images must be embedded; external URI references are rejected. Without an
override it uses GLB-authored material colours and embedded textures; an override replaces every
rendered primitive's base-colour texture while preserving other material properties.
_Avoid_: Mesh, asset, actor, entity

**Animation Selector**:
Either the exact non-blank name or non-negative index of one animation in a GLB. Preparation rejects a
missing name, an out-of-range index, or different selectors that resolve to the same animation.
_Avoid_: Animation id, clip key, nullable name/index pair

**Animation Track**:
An **Animation Selector** together with one finite non-negative `timeSeconds` selected for this
**Frame Plan**. A model applies its animation tracks in list order; after parsing, positive-duration
animations sample `timeSeconds % durationSeconds`, while zero-duration animations sample time zero.
Missing selectors raise rather than substitute.
_Avoid_: Clip, timeline, animation state, keyframe, frame index, frames per second

**Geometry**:
A shader-painted geographic rectangle defined by opposite `topLeft` and `bottomRight` `Vector3` corners.
Top-left latitude is strictly north of bottom-right latitude; its unwrapped longitude is strictly west,
with a span no greater than `360` degrees. The northern edge uses `topLeft.z` altitude, the southern edge
uses `bottomRight.z`, and altitude interpolates north-to-south. A **Geometry** carries no **Placement**.
_Avoid_: Layer, overlay, custom layer, primitive

**Shader Pair**:
The `vertexSource` and `fragmentSource` shader sources a **Geometry** is painted with.
_Avoid_: Program, material, effect

**Shader Profile**:
The single accepted shader source dialect — a GLSL ES 3.00 body — that RenG adapts to a desktop
**Render Context** by substituting `#version 330 core` for `#version 300 es` and changing nothing else.
_Avoid_: Shader language, GLSL version, compatibility profile

**Basemap Tile**:
One canonical PNG tile acquired from Rentile and drawn as the ground beneath a frame. A canonical tile
may back multiple unwrapped world-copy draw instances when repeated Mercator worlds intersect the output.
_Avoid_: Output tile, source tile, map tile, raster

### Resources

**Frame Identity**:
An internal versioned canonical encoding of one **Frame Plan**, identified as
`reng-frame-v1:<lowercase SHA-256>`. RenG compares canonical bytes before sharing and fails closed if one
digest names different bytes. The key is not public API.
_Avoid_: Public frame id, cache key, caller id

**Resource Locator**:
A non-blank, opaque caller-supplied string identifying encoded resource bytes. RenG preserves its exact
text, redacts it from diagnostics, and gives it only to the injected **Transport** and **Store**; RenG
never parses it as a URL or opens it as a file path.
_Avoid_: URL, file path, URI, resource key

**Raw Resource**:
The exact encoded bytes of one thing RenG needs — a basemap resource proxied to Rentile, a sticker
image, a GLB mesh, or a model texture — before any decode or parse.
_Avoid_: Asset, blob, cache entry, decoded resource

**Resource Class**:
One of the eleven closed raw-resource kinds: `BASEMAP_STYLE`, `BASEMAP_TILE_JSON`,
`BASEMAP_VECTOR_TILE`, `BASEMAP_RASTER_TILE`, `BASEMAP_DEM_TILE`, `BASEMAP_SPRITE_JSON`,
`BASEMAP_SPRITE_IMAGE`, `BASEMAP_GEO_JSON`, `STICKER_IMAGE`, `MODEL_GLB`, or `MODEL_TEXTURE`.
The basemap classes map to Rentile only behind RenG's public boundary.
_Avoid_: Resource type, MIME type, content type

**Resource Kind**:
Which renderer-held allocation a lifecycle entry represents: `EXTERNAL`, `GEOMETRY_PROGRAM`,
`INTERNAL_PIPELINE`, or `OFFSCREEN_SURFACE`. Only `EXTERNAL` entries also carry a **Resource Class**.
_Avoid_: Resource Class, GL object type, cache tier

**Resource Key**:
A credential-free logical resource identity containing its **Resource Kind** and a lowercase SHA-256
stable id. External keys also retain their **Resource Class**. A key selects all resident or retired
generations of that logical resource and never reveals its **Resource Locator**.
_Avoid_: Resource Locator, URL hash label, generation handle

**Resource Selector**:
An immutable request selecting all renderer-held resources, one **Resource Kind**, one external
**Resource Class**, or one **Resource Key** for query or free operations.
_Avoid_: Cache predicate, mutable filter, locator query

**Resource Report**:
An immutable point-in-time account aggregated by **Resource Key**, with resident-generation count,
retired-generation count, lease count, and reload-required flag. Each entry and the report totals contain
exact `rawBytes` and `decodedCpuBytes`, nullable `knownGpuBytes`, and `hasUnknownGpuBytes`. Known GPU
bytes sum every exactly knowable allocation and are zero when no GPU allocation exists; they are null only
when GPU allocations exist but none is measurable. When known and unknown allocations coexist, the known
sum remains present and `hasUnknownGpuBytes` is true. Driver and program overhead is never estimated.
Free-operation totals count logical keys as matched, fully freed, deferred, or already free.
_Avoid_: Singular aggregate state, cache dump, resource map, diagnostic log

**Resource Access Mode**:
Preparation policy selected once for a whole prepare invocation: `NORMAL`, `CACHE_ONLY`, or `RELOAD`.
`NORMAL` uses valid fresh resident or stored content and otherwise performs at most one exchange,
conditionally when valid stale content has a validator. `CACHE_ONLY` accepts valid resident or stored
content regardless of freshness and performs no exchange; missing content fails unavailable. `RELOAD`
skips resident and Store reads, sends no conditional metadata, accepts only a full-body success, and writes
that content before use. No mode adds retries, repairs, redirects, status fallbacks, or byte ranges, and
single-flight work is shared only within the same mode.
_Avoid_: Request header, retry policy, cache flag

**Transport**:
The consumer-supplied adapter RenG performs every bounded network exchange through, for its own resources
and for the ones it proxies to Rentile. Requests carry the exact **Resource Locator**, **Resource Class**,
response-byte limit, and optional ETag or last-modified condition, while their textual representation
redacts the locator. Responses carry status, defensively copied bytes, and optional `contentType`, `etag`,
`lastModified`, and `freshUntilEpochMillis`. A `200` response must provide a bounded nonempty body. A
`304` is valid only for a conditional `NORMAL` exchange with valid stale baseline content; it reuses those
bytes, merges allowlisted metadata, and writes the refreshed record before use. Every other status,
including redirects and a `304` without its baseline, fails. Byte ranges and arbitrary headers are outside
the contract.
_Avoid_: HTTP client, fetcher, network layer

**Store**:
The consumer-supplied persistent adapter RenG reads and writes **Raw Resource** bytes through; RenG
owns no persistent cache of its own. `RawResourceKey` contains only a credential-free stable id and
**Resource Class**. `StoredRawResource` contains defensively copied bytes, a SHA-256 digest, and optional
content type, ETag, last-modified, and fresh-until epoch milliseconds plus required stored-at epoch
milliseconds. A record is fresh only while `freshUntilEpochMillis > now`; equality, an absent freshness
value, or an earlier value is stale. The **Store** exposes suspending read and write only. Malformed or
digest-invalid records fail integrity validation and are never removed, bypassed, or repaired; valid stale
records follow the selected **Resource Access Mode**, and every required write must succeed before content
is used.
_Avoid_: Cache, disk cache, repository

### Errors and diagnostics

**RenG Failure**:
One typed `RenGException` carrying a closed error code, granular pipeline stage, and immutable redacted
diagnostics. Its message is stable, its cause is always absent, and a `CancellationException` is never
wrapped as a **RenG Failure**. A non-increasing frame index or one not above committed history is
`PREPARATION_ORDER_VIOLATION` at `FRAME_PLANNING`. A second prepare is `PREPARATION_IN_PROGRESS` at
`FRAME_PREPARATION`; before any context validation, the same code rejects active-preparation interference
from history clearing, resource freeing, or renderer close at that operation's stage. Empty or oversized batches and invalid configuration
use `INVALID_VALUE` or `RESOURCE_LIMIT_EXCEEDED` with only allowlisted `limit` and `actual` values.
Unexpected transport statuses, integrity failures, and deferred GPU-deletion failures remain distinct
errors and never trigger fallback work.
_Avoid_: Adapter exception, cause chain, message-based recovery

**Diagnostic**:
An immutable event containing only an allowlisted field name, **Resource Class**, credential-free resource
key, status code, limit, or actual value. A non-throwing consumer sink receives diagnostics, serialized
per renderer without thread affinity or a total order across otherwise concurrent operations; callbacks
run outside renderer locks and sink failures are swallowed. `RESOURCE_RELOADED_AFTER_FREE` is a warning, and no diagnostic contains a
**Resource Locator** or adapter text.
_Avoid_: Log message, arbitrary metadata, exception cause

## Relationships

- One renderer draws exactly one **Basemap Style**, or none at all, at exactly one output pixel size
- One **Frame Plan** carries exactly one **Camera** and one **Projection Mode**
- `prepare(plan, accessMode = NORMAL)` is equivalent to one nonempty atomic batch item
- `prepareBatch(plans, accessMode = NORMAL)` snapshots a nonempty input List, applies one access mode to
  the whole invocation, enforces the configured batch limit and strict frame-index order, and returns one
  same-order immutable List only after complete success
- Equal **Frame Plan** values may produce independent leases after history is cleared; `frameIndex` orders
  and identifies a plan but creates no resource dependency by itself
- Many **Prepared Frame**s may exist at once and be drawn in any order
- Successful atomic preparation updates **Frame History**; cancellation, failure, and drawing do not
- A consumer may clear **Frame History** without freeing resources or invalidating prepared frames
- One **Prepared Frame** draws into any number of **Render Target**s, and drawing does not consume it
- One **Frame Plan** holds ordered lists of **Sticker**s, **Model**s, and **Geometry**s
- List order is frame content, and each repeated equal value is a distinct drawn thing
- Each **Sticker** and **Model** carries exactly one **Placement**
- A **Geometry** carries no **Placement**; its geographic bounds establish its map-space footprint
- A **Placement** resolves position, rotation, and scale independently, each under its own **Anchoring Mode**
- Position anchoring selects the whole drawn thing's **Draw Regime**
- Rotation and scale anchoring select their transform basis and units without changing the **Draw Regime**
- Every position/rotation/scale anchoring combination is valid
- Map-anchored rotation and scale use the drawn thing's map position when present, otherwise the
  **Camera** location; screen-anchored rotation is camera-facing and screen-anchored scale is constant
  in output pixels
- One **Model** carries an ordered list of zero or more **Animation Track**s, whose selectors must resolve
  to distinct GLB animations
- A violated uniqueness rule is an error; RenG never silently removes or repairs duplicates
- Numeric construction canonicalizes only negative zero; non-finite, out-of-range, and malformed values
  are errors and are never clamped, wrapped, or repaired
- Renderer-held resources remain resident without automatic LRU eviction until explicit free or
  **Renderer Close**
- Free retires leased generations without invalidating live **Prepared Frame**s; new preparation reloads a
  fresh generation and emits one warning
- Closing the last lease of a retired generation queues its live handles for deferred deletion. After
  exact-context validation and before operation-specific work, Render Target minting, drawing, resource
  freeing, and live-handle **Renderer Close** drain that queue; validation failure changes nothing
- Resource query, preparation, history clearing, cancellation, Prepared Frame close, and **GPU Object
  Loss** never drain deferred deletion. GPU Object Loss forgets queued handles without deletion, and a
  replacement context cannot delete handles from the lost context
- Deferred deletion failure is `GPU_OPERATION_FAILED` at the invoking operation's stage and aborts that
  operation before its primary work
- Every **Geometry** carries exactly one **Shader Pair**, written in the **Shader Profile**

## Example dialogue

> **Dev:** "If I prepare frames 1 through 30 up front, does preparing frame 30 evict what frame 1
> needs?"
> **Domain expert:** "No. A **Prepared Frame** holds every resource its **Frame Plan** needs for as
> long as it is alive, and identical resources are shared between them rather than duplicated."
>
> **Dev:** "So can I draw frame 12 before frame 5?"
> **Domain expert:** "Yes. A **Prepared Frame** is independent — nothing about it depends on the
> order frames were prepared or drawn in."
>
> **Dev:** "This **Sticker** has a `MAP` position and a `SCREEN` rotation. Which **Draw Regime**
> does it belong to?"
> **Domain expert:** "The map-occluded regime, because position anchoring selects the regime. Its
> screen-anchored rotation changes only its orientation, producing an occluded billboard."

## Flagged ambiguities

- "surface" was used for three distinct things — the caller's framebuffer (**Render Target**), the
  caller's GL context (**Render Context**), and the platform window handle (which RenG never
  touches). Resolved: RenG's API names the first two and has no term for the third.
- "frame" was used for both a rendered image and an animation sample. Resolved: `FramePlan.frameIndex`
  orders strict preparation, while an **Animation Track** selects `timeSeconds`; animation has no frame
  index or FPS contract.
- CLAUDE.md describes the render loop as taking "only a `FramePlan`". Resolved: the render loop
  takes a **Prepared Frame**; see ADR 0002. Preparation ordering is superseded by ADR 0014.
- CLAUDE.md describes geometry shaders as "plain, fully self-contained OpenGL shaders" with no
  RenG-injected preamble. Resolved: RenG substitutes the version directive and nothing else; see
  ADR 0008.
- "free" was used for both "release these resources, reload them on next access" and "these GPU
  objects no longer exist". Resolved: these are separate operations, because the first deletes GL
  objects and the second must not; see ADR 0007. Exact-context deletion enforcement is superseded by
  ADR 0015.
