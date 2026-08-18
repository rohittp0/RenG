# Cycle C Resource Layer Design

## Outcome and scope

Cycle C makes Cycle B's pure resource decisions real. It executes the actions `ResourceOperationStateMachine`
emits by calling the consumer's injected `Transport` and `Store`, by driving Rentile behind the ADR 0016
firewall for the eight basemap classes, and by decoding and parsing RenG's own three. It adds the resident
cache with refcounted generations, the reload-on-access path that makes freeing safe, and cancellation of
everything in flight.

It produces no pixels, no GL call, no shader, no context, and no public renderer construction. There is still
no factory: a consumer cannot obtain a `Renderer`, so nothing in this cycle is reachable through the public
API. Cycle C is gated by host tests against fake transport and store adapters plus a real Rentile instance,
exactly as `docs/decomposition.md` states.

Everything remains inside the single published `:kmp` module and package `com.rohittp.reng`. Rentile stays an
`implementation` dependency and no Rentile or platform type appears in public ABI.

The public surface grows by exactly four declarations, each justified in "Public surface growth" below and
each reviewed as an explicit `checkKotlinAbi` diff: two `ResourceLimits` fields, one `RenGErrorCode` entry,
one `PipelineStage` entry, and one `ResourceKind` entry. Nothing else public changes.

## Measured basemap-engine behaviour

ADR 0016 asserted per-class Store ordering in detail from one traced path in a disassembled artifact. A
counting-stub spike has now driven the published `0.1.5` artifact through `prepare`, `prepareBatch` and
`render` to real PNG output with all eight classes exercised, so the firewall below rests on measurement.
ADR 0016's write-ordering, private-`remove`, style, sprite and DEM claims are all confirmed.

The spike also established provenance: the published `0.1.5` sources jar is byte-identical to the local
Rentile checkout's `commonMain`, apart from one generated file, so source reading and artifact measurement
agree for common code.

Three measurements changed the design rather than confirming it, and each is applied below.

**`accept` is not always null.** The research document records that Rentile never sets request metadata.
That is true for `ifNoneMatch` and `ifModifiedSince` on every class, and **false for `accept`**, which is
`application/json` on `BASEMAP_SPRITE_JSON` and `image/png` on `BASEMAP_SPRITE_IMAGE`. The firewall must
therefore not assert request metadata universally empty.

**A poisoned sprite record is terminal inside the engine.** Every other store-backed class recovers from a
corrupt stored record by removing it and refetching. The sprite acquirer validates only byte size and digest
consistency on a store hit and never parses before returning, so a record whose digest matches its bytes but
whose content is not valid sprite JSON or PNG is returned, never removed, never refetched, and fails
preparation permanently — confirmed to survive a second `prepare` on the same rasterizer.

**Store reads are unbounded.** Only the transport call sits inside the engine's concurrency permits. A
256-tile batch issued 252 simultaneously in-flight `read` calls against the injected store.

Still provisional, and deliberately unexercised: `retryExact` and tile substitution, which RenG keeps
disabled; the label and terrain acquisition entry points; and everything outside `macosArm64`, since worker
counts and thread migration are target-specific even though the dispatcher shape comes from common code.

## The concurrency substrate

Cycle C declares `org.jetbrains.kotlinx:kotlinx-coroutines-core` under ADR 0019, with the version in RenG's
own version catalogue and `tools/check_repository_policy.py` amended to permit that one coordinate. The
policy's forbidden-dependency rule otherwise stands unchanged.

A prepare invocation runs its resource operations inside one `coroutineScope`, so structured concurrency
binds every child to the caller's job: caller cancellation reaches RenG's own work without RenG observing it
specially, and no child outlives the invocation. Concurrency is bounded by a `Semaphore` sized from
`RendererConfiguration.maximumConcurrentResourceOperations`, which is what makes the **Preparation Budget**'s
promise of "at most `maximumConcurrentResourceOperations` independent resource operations" true rather than
aspirational.

RenG selects no dispatcher. Work runs on whatever context the caller supplies, because RenG owns no thread
pool and the consumer's adapters already impose their own. Decode and parse are CPU-bound and long enough to
matter on a caller's main dispatcher, so this is stated in the public documentation as the caller's choice,
not silently corrected by a `withContext`.

The basemap engine does not honour RenG's concurrency context, and this is measured rather than assumed.
Every engine operation runs on the engine's own scope over its default dispatcher, so the caller's
`CoroutineContext` and thread never reach an adapter, and a single adapter call may enter on one worker and
return on another across a suspension point. RenG's firewall adapters are therefore written to be
thread-safe and free of thread-affine state, and they carry the active preparation invocation explicitly
rather than through coroutine context.

Only the engine's transport call sits inside its concurrency permits. Store reads do not: a 256-tile batch
was measured issuing 252 simultaneously in-flight reads. The firewall absorbs that fan-out by answering from
the joined route sample rather than forwarding each one, which is what keeps RenG's own promise of at most
one consumer exchange and one consumer read per structural identity. This also means ADR 0016's
"256 tiles at concurrency eight" proof describes RenG's boundary and must not be read as a bound on the
engine's internal fan-out.

Renderer state that outlives one invocation — the resident cache, lease counts, the frame history, the
rasterizer handle — is guarded by one `Mutex` per renderer. `CONTEXT.md` already fixes the linearization
points: drawing may overlap GL-free preparation, and resource query, free, and Prepared Frame close linearize
at renderer state boundaries. The mutex is held for state transitions only and never across an adapter call,
a decode, or a parse, because an adapter is consumer code of unbounded duration.

## The resource driver

The driver is the loop that turns Cycle B's emitted actions into real work and supplied outcomes back into
events. It owns no policy. Every decision — which route runs, what request metadata is sent, whether content
is fresh, which gate runs next, whether a write is required, when visibility installs — was made in Cycle B
and is replayed here without reinterpretation.

For each `ResourceOperationAction` the driver performs exactly one operation and feeds back exactly one
event:

| Action | Real work | Event |
|---|---|---|
| `SampleClock` | one system epoch-millisecond sample | `ClockSampled` |
| `ObserveResident` | resident cache lookup for the key's current generation | `ResidentObserved` |
| `ReadStore` | `Store.read(rawKey)` | `StoreReadCompleted` |
| `CallTransport` | `Transport.execute(request)` | `TransportCompleted` |
| `ReplayLatchedTransport` | no adapter call; returns the latched outcome | `LatchedTransportReplayCompleted` |
| `ValidateResourceClass` | the class gate, per "Class gate execution" | `ResourceClassValidationCompleted` |
| `WriteStore` | `Store.write(rawKey, resource)` | `StoreWriteCompleted` |
| `InstallVisibility` | install the generation and take the owner's lease | `VisibilityInstallCompleted` |
| `ValidateSpritePair` | joint sprite JSON + PNG validation | `SpritePairValidationCompleted` |
| `WriteSpriteMember` / `InstallSpriteVisibility` | as above, per member and per group | matching completion events |
| `ValidateBasemapStyle` / `CompileBasemapStyle` / `WriteBasemapStyle` / `InstallBasemapStyleVisibility` | style validation, Rentile compilation, write, install | matching completion events |

The three advancement events — `AdvancePendingClassGates`, `AdvancePendingSpriteCommit`,
`AdvancePendingStyleCommit` — are emitted by the driver, not by an adapter. They exist to preserve Cycle B's
zero-action lookup boundary and carry no observation.

A single supplied outcome is fed per action. An adapter throwing a non-cancellation `Throwable` becomes
`SuppliedCallOutcome.Failed` with a sanitized code and stage and the adapter's message and cause discarded; a
`CancellationException` becomes `SuppliedCallOutcome.Cancelled` carrying the opaque selection identifier and
is never translated into a RenG failure. The driver performs no retry, repair, redirect, status fallback, or
byte range, because the state machine emits no action for any of them.

## The Rentile firewall, made real

One renderer owns one long-lived `BasemapRasterizer`, created at renderer setup from a
`RentileConfiguration` whose `transport` and `rawResourceStore` are the firewall's fixed adapters and whose
`credentialProvider` and `sessionProvider` are `None`. The artifact shows `credentialFor` and `sessionFor` are
never invoked in `0.1.5`; credentials reach the network inside style-document URLs and whatever the injected
transport adds. RenG therefore does not report that it supports Rentile credential providers.

`ExecutionPolicy` and the nine `ResourceLimits` fields RenG cannot express stay at Rentile's defaults. RenG's
own limits bound what crosses RenG's boundary; Rentile's bound Rentile's internal decode safety, and inventing
RenG configuration for them would publish a surface RenG does not otherwise own.

`MetricsSink` stays `None`. `RentileClock` stays `System`: RenG's freshness decisions use their own single
epoch sample per operation, and giving Rentile a different clock would make two components disagree about now.

### Rasterizer and prepared-style lifetime

Setup constructs the rasterizer and nothing else — it performs no I/O and does not suspend, which is what lets
`RendererConfiguration` stay non-suspending under ADR 0004.

The `PreparedStyle` is compiled lazily, inside the first preparation that needs ground, through the firewall's
style route. This is not a deviation from ADR 0004: the style is still fixed at setup as a `ResourceLocator`;
only its acquisition and compilation are deferred to a point where a preparation invocation exists to own the
route registration, which ADR 0016 requires. Cycle B already encodes exactly this — `BASEMAP_STYLE` is a
static route in every plan's traversal, `StyleCommitState` tracks compilation, and `requiresStyleCompilation`
returns false for `RESIDENT` provenance, so a compiled style is reused for every later preparation.

The compiled `PreparedStyle` is bound to the style's current resident generation. Freeing the style retires
that generation and its `PreparedStyle`; the next preparation reloads and recompiles into a new generation.

`Renderer.close()` closes the rasterizer and awaits `awaitClosed()`. Rentile's close is not GL-scoped, so it
is ordered after ADR 0017's owner-wide terminal transition and is independent of ADR 0015's exact-context
requirement — a renderer with no live GL handles still closes its rasterizer.

### Adapter translation

The firewall's `ResourceTransport` receives a Rentile `TransportRequest` and matches its URL against a
preregistered route. An unrecognised URL is not forwarded: it is a route that RenG never planned, and
forwarding it would be an unplanned consumer exchange.

Field mapping, with the decisions that are not mechanical called out:

- Rentile's eight `ResourceClass` values map onto RenG's eight `BASEMAP_`-prefixed classes. RenG's
  `STICKER_IMAGE`, `MODEL_GLB`, and `MODEL_TEXTURE` never arrive from Rentile and are never produced in this
  direction.
- `maxResponseBytes` is not trusted. The route's ceiling comes from `ResourceLimits.maximumBytesFor` and is
  part of the route key, so a disagreeing Rentile number is a route mismatch rather than a new request.
- `ifNoneMatch` and `ifModifiedSince` are asserted empty on every class, measured. Every conditional
  validator is RenG's, derived from the route and the Store lookup.
- `accept` is **not** asserted empty. Rentile sends `application/json` on sprite JSON and `image/png` on
  sprite image, and null on the other six classes. The firewall accepts a non-null `accept` on a sprite route
  without treating it as a route mismatch, and RenG's own `ResourceClass.acceptValue` for the two sprite
  classes must either agree with those literals or override them deliberately. This corrects the research
  document, which recorded all three as always null.
- On the response, `contentType`, `etag`, `lastModified` pass through, and `expiresAtEpochMillis` carries
  RenG's `freshUntilEpochMillis`. `cacheControl` and `vary` are `null` and empty because RenG's protocol has
  no equivalent and RenG has already decided freshness. `redirectLocation` is `null` because RenG fails every
  redirect before this point. `wireByteCount` is `null`.
- `retryAfterMillis` is `null` **deliberately**, and this is stated rather than left to oversight. It is the
  only input to Rentile's retry delay, so a null makes that delay zero — which is moot, because the firewall
  replays a latched outcome instead of performing a second consumer exchange. The adapter must not synthesise
  a value here. The engine's extra attempt is measured to occur only for vector, raster and DEM tiles, on a
  thrown error or status 408, 429, or 5xx, and never for style, TileJSON, sprite, or GeoJSON.

Two call-pattern facts constrain the firewall. The engine holds no renderer-lifetime raw-resource cache:
every `prepare` and every `prepareBatch` re-reads the consumer store, so the firewall's per-invocation route
registry is doing real work rather than duplicating an engine cache. And `render` over an already-prepared
batch performs **zero** adapter calls, so all acquisition is complete before pixels are produced.

Access mode needs one clarification the artifact could not give. Style, TileJSON, both sprite classes, and
GeoJSON are acquired inside the engine's `prepare`, which takes no access-mode argument at all, so those
four never observe a mode from the engine. This does not weaken ADR 0016's rule — the firewall binds mode
from the explicit outer preparation invocation and never reconstructs it from an engine key — but it is why
that rule is necessary rather than merely tidy. Under `RELOAD` the engine additionally skips its store read
entirely for the tile classes.

Rentile never sees a `304`. RenG's response rules accept only `200` and `304`, and a `304` is merged with its
validated stale baseline into a full response before it crosses the firewall.

The firewall's `RawResourceStore` answers `read` from the joined route sample rather than issuing a second
consumer read, permits `write` only after RenG has verified the record against the latched response and its
stricter rules, and **absorbs `remove`**: private, terminal, no consumer mutation, no repair, no follow-on
exchange. RenG's `Store` has no `remove`, and mapping it onto anything consumer-visible would break the
published contract.

`remove` is measured to occur only on the stored-record path, never on the fetch path and never for style.
It fires on a digest mismatch for all six store-backed classes, and on a parse failure for all of them
**except sprite**. That exception is load-bearing for RenG: the sprite acquirer validates only byte size and
digest consistency on a store hit and never parses, so a stored sprite record whose digest matches its bytes
but whose content is not valid sprite JSON or PNG is returned, never removed, never refetched, and fails
preparation permanently. RenG therefore **fully validates a sprite record's content before answering a
Rentile read with it**, rather than relying on the engine to self-heal — sprite is the one class where a bad
record RenG lets through is unrecoverable downstream.

A Rentile `RawResourceKey` is never translated field by field. Its `stableId` is Rentile's digest of a
redacted URL; RenG's is an ADR 0018 canonical-identity digest. They are different digests of different inputs
and both are `String`, which is precisely why the boundary is dangerous. The adapter looks Rentile's key up as
an opaque token — Cycle B's `RentilePrivateKey` — and answers with the RenG key already bound to that route.
A Rentile key object never reaches a RenG diagnostic, because `RawResourceKey.toString()` prints its
`stableId` in the clear while every other Rentile DTO redacts.

### Private key derivation

The production `RentilePrivateKeyResolver` splits by whether Rentile keys the class at all.

For the seven classes Rentile keys — TileJSON, vector tile, raster tile, DEM tile, sprite JSON, sprite image,
GeoJSON — the token reproduces Rentile's own derivation exactly: the mapped Rentile class paired with
`sha256Hex(withRedactedAuthenticationQuery(finalRequestedUrl))` over the exact URL Rentile requests — the
already-templated tile URL, so coordinates enter only through the URL itself — where the redaction rewrites
any query parameter whose
lowercased name is one of `access_token`, `apikey`, `api_key`, `key`, `mtsid`, `session`, `session_id`, or
`token` to `<name>=<redacted>` and rejoins with `&`. Reading Rentile's source confirms all five acquirers
derive `stableId` identically, with no class prefix and no coordinate suffix. The spike confirms an exact
match on all seven store-backed classes against the published artifact, with an authentication parameter
present: the parameter name survives, its value becomes the literal `<redacted>`, and unrelated query
parameters survive untouched. Style has no store key at all, but its `sanitizedResourceId` uses the same
derivation, so the resolver is uniform.

For `BASEMAP_STYLE`, `STICKER_IMAGE`, `MODEL_GLB`, and `MODEL_TEXTURE` the token derives from RenG's own
canonical resource identity, which is injective in locator and class. Rentile constructs no `RawResourceKey`
for any of them — style has no raw-store write at all, and the other three never reach Rentile — so no real
collision can be missed, while a uniform Rentile-shaped derivation would raise a false
`AMBIGUOUS_RESOURCE_ROUTE` for two stickers whose locators differ only in an authentication token. That is a
plan RenG must serve, and a locator is exact opaque text RenG never parses as a URL.

Collision detection itself stays uniform over routes, exactly as Cycle B implements it. Only the derivation
differs by class.

### Class gate execution

`ordinaryResourceClassGates` names the gates; this cycle decides who runs them.

For the classes Rentile validates, the gate outcome is **observed**, not recomputed. ADR 0016 states Rentile
reaches its raw-store write only after its own bounded parser or decoder validation, so a write callback means
validated and the `remove` callback the firewall already traps means it failed. `PARSE_TILEJSON`,
`DECODE_VECTOR_TILE`, `PARSE_GEOJSON`, and `DECODE_PNG` on `BASEMAP_RASTER_TILE` are satisfied this way. RenG
gains no MVT, TileJSON, or GeoJSON parser, which is the boundary the Rentile research draws.

RenG executes the gates Rentile does not cover: `VALIDATE_DEM_TERRAIN_ENCODING`, because ADR 0016 says DEM
reaches the write after generic bounded image validation only; the joint sprite-pair validation, because it
spans two resources; and every gate for `STICKER_IMAGE`, `MODEL_TEXTURE`, and `MODEL_GLB`, which Rentile never
sees.

Failure mapping is already fixed by Cycle B and is not reopened: a failed gate on `ContentProvenance.STORE`
reports `STORE_INTEGRITY_FAILED / STORE_VALIDATION` regardless of which gate failed, so a corrupt stored
resource and an unsupported stored resource report identically — both mean the stored record cannot be
trusted. The parse-versus-feature distinction is therefore observable for fresh network content only.

The observed per-class ordering, measured on the published artifact, is what the firewall reads:

| Class | Observed sequence | Write follows |
|---|---|---|
| `BASEMAP_STYLE` | `execute` only | no store read and no store write, ever |
| `BASEMAP_TILE_JSON`, `BASEMAP_GEO_JSON` | `read` → optional `remove` → `execute` → `write` | its parse |
| `BASEMAP_VECTOR_TILE` | `read` → optional `remove` → `execute` ×1–2 → `write` | MVT decode |
| `BASEMAP_RASTER_TILE` | as vector | image decode and dimension/byte limits |
| `BASEMAP_DEM_TILE` | as raster, same acquirer | generic image validation **only**, no terrain check |
| `BASEMAP_SPRITE_JSON`, `BASEMAP_SPRITE_IMAGE` | `read` → `execute` → `write` → joint atlas validation | nothing — written **before** validation |

That confirms ADR 0016 for all eight classes with no contradiction. A fetched-but-invalid body was proven to
reach `write` for both sprite classes and never to reach `write` for TileJSON, vector, raster, DEM, or
GeoJSON, which is exactly the asymmetry the ADR describes and the reason RenG's own sprite handling
prevalidates the pair jointly before either member is written.

### Error classification

Every Rentile failure is classified into RenG's closed vocabulary rather than wrapped. `RenGException` has no
`cause` parameter, so forwarding is structurally impossible, which is the right outcome.

| Rentile | RenG |
|---|---|
| `STYLE_PREPARATION_FAILED` | `RESOURCE_PARSE_FAILED` or `UNSUPPORTED_RESOURCE_FEATURE`, via Cycle B's `StyleFailureKind` |
| `RESOURCE_ACQUISITION_FAILED` | the route's already-latched RenG outcome; Rentile is seeing RenG's own failure replayed |
| `RESOURCE_DECODE_FAILED` | `RESOURCE_DECODE_FAILED` |
| `RESOURCE_STORE_FAILED` | `STORE_READ_FAILED`, `STORE_WRITE_FAILED`, or `STORE_INTEGRITY_FAILED` by operation |
| `SAFETY_LIMIT_EXCEEDED` | `RESOURCE_LIMIT_EXCEEDED` |
| `RASTERIZATION_FAILED`, `PNG_ENCODING_FAILED` | `BASEMAP_RENDER_FAILED` at `BASEMAP_RENDER` |
| `BATCH_RENDER_FAILED` | unwrapped to its primary failure, then classified |
| `RASTERIZER_CLOSED`, `PREPARED_BATCH_CLOSED`, `FOREIGN_PREPARED_STYLE`, `FOREIGN_PREPARED_BATCH`, `INVALID_TILE_ID`, `TILE_NOT_IN_PREPARED_BATCH` | `BASEMAP_RENDER_FAILED`; these can only arise if RenG mismanaged a handle it wholly owns, and RenG still fails closed rather than letting an untyped throwable escape |
| `TILE_SUBSTITUTION_FAILED`, `TILE_SUBSTITUTION_LIMIT_EXCEEDED` | unreachable; substitution stays `Disabled` and `retryExact` is never called |

Rentile's `RenderDiagnostic.message` and `details` are free-form text and are **dropped**, never forwarded.
Its `DiagnosticCode` and `PipelineStage` values map onto RenG's own or are dropped; no Rentile literal reaches
a RenG `Diagnostic`, which admits only an allowlisted field name, resource class, credential-free key, status,
limit, or actual value.

## PNG decode

Per ADR 0020, RenG owns the container in `commonMain` and delegates only streaming inflate and CRC-32 to an
internal `expect`/`actual` seam with two implementations: `platform.zlib` on the five native targets and
`java.util.zip.Inflater` with `java.util.zip.CRC32` on Android.

The accepted subset is colour types 0, 2, 3, 4 and 6 at bit depth 8 only. Sixteen-bit samples, sub-byte
palette depths, and Adam7 interlace are rejected with `UNSUPPORTED_RESOURCE_FEATURE`. Everything decodes to
one canonical form: tightly packed RGBA8, unpremultiplied, no row padding, palette and grayscale widened
losslessly. One form means one upload path for Cycle D and E, one figure in the report's decoded-byte
accounting, byte-comparable goldens across all six targets, and no endianness rule.

The rejection list is exhaustive because RenG never repairs: wrong signature; `IHDR` not first or not 13
bytes; `IEND` not last; any trailing byte after `IEND`; a chunk length exceeding the remaining bytes; any
chunk CRC mismatch; a compression method other than 0; a filter method other than 0; a scanline filter byte
above 4; an interlace method other than 0; missing `PLTE` for colour type 3; `PLTE` present for colour types 0
and 4; a zlib preset dictionary; an Adler-32 mismatch; a deflate stream ending before or continuing past the
exact expected raw size; zero width or height; and dimensions whose decoded size exceeds the configured
ceiling. Unknown critical chunks are rejected and unknown ancillary chunks are skipped with their CRC still
validated, so an APNG decodes as its base frame rather than failing by accident. `gAMA`, `iCCP`, `sRGB`, and
`cHRM` are ignored and no colour transform is ever applied — precisely where RenG differs from a platform
decoder, and precisely what a Terrain-RGB tile requires.

Malformed bytes map to `RESOURCE_DECODE_FAILED`, well-formed out-of-subset features to
`UNSUPPORTED_RESOURCE_FEATURE`, and oversize to `RESOURCE_LIMIT_EXCEEDED`. The decoded-size ceiling is decided
from `IHDR` before any pixel buffer is allocated, never after inflating.

Both inflate actuals are gated by one shared vector suite of byte literals in `commonTest`, so
`linuxX64Test`, `macosArm64Test`, and `testAndroidHostTest` run the same assertions.

## GLB parse and the JSON reader

Per ADR 0021, which fixes the accept and reject table, the container rules, the two-gate split, and the JSON
reader's strictness. That ADR is normative and is not restated here. Three points bind implementation.

`PARSE_GLB` must tolerate anything the specification permits even when `VALIDATE_GLB_FEATURES` will refuse it.
An accessor with no `bufferView` is legal, means all zeros, and is the signature of a Draco asset; rejecting
it at parse time would report corruption for a file whose real problem is an unsupported extension.

RenG writes its own JSON reader because it cannot borrow one: Rentile publishes none, and
`kotlinx-serialization-json`, though transitively resolved, is compile-visible only on the native targets, and
the repository policy forbids declaring it. Numbers are classified by spelling so every field the
specification types as an integer is read only from an integer form; duplicate member names, lone surrogates
at both the escape and UTF-8 layers, a byte order mark, and any replacement-character substitution are
rejected.

The JSON chunk carries its own configurable ceiling separate from the whole-GLB ceiling, because a boxed value
tree was measured at roughly 27× its text. Accessor arithmetic is checked in `Long` before any array exists.
JSON nesting depth and node-hierarchy depth are fixed non-configurable constants.

A non-throwing Unicode-scalar predicate is extracted from `requireUnicodeScalars` in `internal/ValueSupport.kt`
and the existing function refactored to call it. That is a planned, reviewed touch of a Cycle B file, not an
incidental edit: the scanning logic is shared, but its failure mode is not — `IllegalArgumentException` is
reserved for public value-constructor violations, whereas a malformed GLB is a `RenGException` with a stable
code and a redacted diagnostic.

## Terrain

Cycle C takes Rentile's `terrainSourceDescriptor`, `groundRadianceDescriptor`, and `acquireTerrainTiles`. DEM
tiles are encoded PNG in the `MAPBOX` or `TERRARIUM` encoding, both eight-bit RGB, so they need no decoder
beyond the one this cycle already builds and no parser at all.

`VALIDATE_DEM_TERRAIN_ENCODING` runs on decoded samples and admits exactly those two encodings; no RenG source
names them today. Decoded terrain must be bit-exact, with no premultiplication, scaling, or colour transform,
because any of those silently change elevations.

Nothing consumes elevation in this cycle. Cycle E displaces the mercator ground with it, together with ground
radiance, which Rentile evaluates from the style and returns as a literal.

Cycle C does **not** take `acquireLabelTiles`. It returns encoded MVT, which would put a protobuf reader and a
style-expression evaluator inside RenG, and text baked into a ground texture is perspective-distorted under a
pitched camera anyway. RenG draws no map text, and this specification says so plainly rather than leaving a
silent gap; `docs/research/2026-08-18-rentile-label-primitives-request.md` carries the proposal that would
change it.

## The resident cache

One entry per `ResourceKey`, holding zero or more generations and a freed marker.

A **generation** is one concrete loaded instance: its exact raw bytes and record metadata, its decoded or
parsed product, and later its GPU allocations. Raw bytes are retained, not dropped after decode, because
Cycle B's `NORMAL` rules use a stale resident as a `304` baseline and `ObserveResident` is typed to answer
with a `StoredRawResource`; dropping them would make the resident path dead code and force a Store read on
every revalidation.

Exactly one generation is current, and only the current generation satisfies a new resource operation. A
generation superseded by different content stays usable while leased and is dropped when its last lease
closes. There is no automatic LRU eviction; residency ends only at explicit free or renderer close.

A **lease** is one live claim, held by a Prepared Frame for every distinct resource its plan needs and by an
in-flight draw for its own duration. Leases are counted, so many prepared frames share one generation, and
equal plans prepared after frame history is cleared take independent leases. A generation is never deleted
while any lease is open.

**Free** retires every generation of the matched keys and sets the entry's freed marker. Generations with no
lease go immediately; the rest wait for their last lease, and the free result reports them deferred. A retired
generation is never resurrected, even when identical bytes return, so a free result stays a truthful account
of the instant it was taken. Free and resource query share one snapshot boundary, which is what makes the
documented race resolution — deferred if free wins, fully freed if the last release wins — well defined.

**Reload** is what makes freeing safe. The freed marker is what distinguishes a freed key from one never
loaded; without it, free would have to delete the entry and a later preparation could not tell the two apart.
A preparation naming a freed key acquires it again, installs a new current generation, and emits exactly one
`RESOURCE_RELOADED_AFTER_FREE` warning for that key. Reload obeys the same access-mode rules as any other
operation and may be satisfied from the Store with no network exchange; the warning reports that RenG had to
reload, not that it refetched. Markers live until renderer close, bounded by the distinct keys the consumer
has ever used.

`ResourceReport` is derived from this structure directly: `residentGenerationCount`, `retiredGenerationCount`,
`leaseCount`, and `reloadRequired` per entry, with `rawBytes` and `decodedCpuBytes` exact,
`knownGpuBytes` zero and `hasUnknownGpuBytes` false throughout Cycle C because no GPU allocation exists yet.

A rendered basemap tile is a resource with no `ResourceLocator`, so it is keyed under the new
`ResourceKind.BASEMAP_TILE` with a stable id over an ADR 0018 canonical root containing `PreparedStyle.digest`
— which Rentile computes over the compiled style and its sprite atlas — the tile id, and the output size.
Rentile's `outputRequestKey` joins Rentile's own batch and is never RenG's identity, so a Rentile release that
changes its key derivation cannot silently invalidate RenG's cache.

## Cancellation

Caller cancellation arrives through structured concurrency and is never translated into a RenG failure.
`CancellationException` propagates unwrapped; Kotlin stack recovery may copy it while retaining the original
as its immediate cause, which is the one shape `CONTEXT.md` already permits.

`cancelPreparations()` cancels the invocation's scope. Cycle B's arbitration already decides precedence:
caller or explicit cancellation wins only when it claims the invocation's terminal slot before the in-order
route outcome, and cleanup cancellation never replaces an already selected outcome. The driver supplies the
observation; it does not re-decide.

In-flight adapter calls are cancelled cooperatively — RenG cannot abort a consumer's suspend function, only
cancel the coroutine awaiting it. Content acquired before cancellation may remain resident, which
`CONTEXT.md` already permits: failure or cancellation exposes no partial history, though valid acquired
content may remain cached.

Cancellation through Rentile is measured and agrees with RenG's contract in both directions: a caller
cancelling mid-acquisition sees an unwrapped `CancellationException` with zero adapter calls afterwards, an
adapter throwing `CancellationException` has it propagate out unchanged with its message and null cause
intact, and when two callers join one exchange, cancelling the first leaves the survivor unstarved with no
extra exchange.

One asymmetry must be handled explicitly. Closing the rasterizer surfaces to work **already in flight** as a
plain `CancellationException`, and only to operations started **afterwards** as a typed closed-rasterizer
failure. RenG must therefore not classify the first case as a RenG failure — under RenG's own rules it is an
unwrapped cancellation, and ADR 0017's owner-wide terminal transition has to treat it as such.

## Public surface growth

Four additions, each an explicit reviewed `checkKotlinAbi` diff.

```kotlin
public data class ResourceLimits(
    // ... the existing eight encoded-byte ceilings, unchanged ...
    public val maximumDecodedImageBytes: Long = 64L * 1024L * 1024L,
    public val maximumModelJsonChunkBytes: Long = 16L * 1024L * 1024L,
)

public enum class RenGErrorCode { /* ... */ BASEMAP_RENDER_FAILED }
public enum class PipelineStage { /* ... */ BASEMAP_RENDER }
public enum class ResourceKind { /* ... */ BASEMAP_TILE }
```

`maximumDecodedImageBytes` is enforced from `IHDR` before allocation at every `DECODE_PNG` gate.
`maximumModelJsonChunkBytes` bounds a GLB's JSON chunk independently of `maximumModelGlbBytes`, so a consumer
raising the model ceiling for a large mesh does not silently raise their amplification exposure. Both are
bytes in `[1, 2147483647]`, matching the existing eight.

`BASEMAP_RENDER_FAILED` at `BASEMAP_RENDER` says something true and actionable — RenG asked the basemap engine
for the ground and did not get it — without pretending a decode or a GL call failed.

`ResourceKind.BASEMAP_TILE` makes `ResourceSelector.ByKind` meaningful for rendered tiles, which Cycle E needs
in order to make basemap textures queryable and freeable. Only `EXTERNAL` entries carry a `ResourceClass`, and
`BASEMAP_TILE` is not `EXTERNAL`, so that invariant is unchanged.

## Implementation boundary and gates

Cycle C implements the driver, the firewall and its adapters, the private-key resolver, the PNG decoder and
its two inflate actuals, the GLB parser and JSON reader, terrain acquisition and validation, and the resident
cache with generations, leases, reload, and free.

It does not implement any GL call, context handling, shader compilation, framebuffer, texture upload, pixel,
capture, encoder, renderer factory, or public runtime entry point. It adds no Gradle subproject.

Required gates:

- `checkKotlinAbi` with a reviewed dump showing exactly the four additions above and no Rentile or platform
  type;
- Android host, `linuxX64Test`, and `macosArm64Test` for the driver, decoder, parser, cache, and firewall
  against fake transport and store adapters;
- compile gates for both iOS targets and Linux ARM64;
- one shared PNG vector suite of byte literals run on every target, gating both inflate actuals identically;
- GLB container and JSON reader batteries covering the accept and reject table, including the malformed
  fixtures the research classified and a run over the Khronos sample models counting what the subset rejects
  and why;
- firewall tests proving at most one consumer exchange per structural identity, corruption terminal without
  consumer mutation, `remove` absorbed, and no retry, repair, redirect, or fallback action;
- cache tests for generation supersession, lease counting across concurrently live prepared frames, free with
  and without live leases, the deferred-versus-fully-freed race, and exactly one reload warning per key;
- **a scale benchmark over a realistic multi-hundred-tile frame, landed as the first task touching the
  driver.** `HANDOFF.md` records a measured Θ(events × (routes + occurrences)) scheduler cost with roughly
  nine events per route, an O(owners × occurrences) style-owner barrier, and per-event full-payload hashing —
  extrapolating to seconds of pure CPU at the shipped default tile budget. It must be measured before it is
  optimised, and optimised before Cycle E draws anything;
- repository policy, amended for the one coroutines coordinate, plus local publication and the fresh
  six-target consumer smoke before merge.

Cycle C produces no pixels, no GL call, no factory, and no public renderer construction.
