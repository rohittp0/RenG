# The Rentile integration surface Cycle C must drive

Cycle C is the first cycle that calls Rentile for real. `CLAUDE.md` points at the author's local Rentile
checkout, which does not exist on this machine, so everything below was read out of the published
`com.rohittp.rentile:kmp:0.1.5` artifact rather than out of Rentile's source. That distinction matters for
how much weight each claim can carry, and this document keeps it visible: statements marked as measured
come with the command that produced them, statements marked as inferred are reasoning over signatures and
bytecode, and the closing sections list what the artifact cannot answer at all.

Two artifacts carry the whole surface. The Kotlin/Native `.klib` gives the declared API — including
`internal` declarations, because a klib publishes internal metadata for the whole module — but no
behaviour. The Android `.aar`'s `classes.jar` gives compiled JVM bytecode for the same common sources, so
`javap -c` exposes control flow, string literals, exception tables, and default-argument masks. Where a
behavioural claim appears below, it came from the bytecode, not from the klib.

## Provenance

The dependency was already resolved into the Gradle module cache; nothing was downloaded to obtain the API.
Only the HTTP target checks in the last section went over the network.

| Artifact | Cache path | SHA-256 |
|---|---|---|
| `kmp-linuxX64Main-0.1.5.klib` | `/root/.gradle/caches/modules-2/files-2.1/com.rohittp.rentile/kmp-linuxx64/0.1.5/c1c8d6219560d60bad8ce97012fe42f18528ee75/` | `d46fa964ee2639098aab28e4978ae2db704362211b8977c0313331f93274a652` |
| `kmp.aar` (Android) | `/root/.gradle/caches/modules-2/files-2.1/com.rohittp.rentile/kmp-android/0.1.5/cba3fc6a8f8f8cc68224618383b5095d7c563520/` | `85faf647d09076948417f4d5ee58a86bf46cfb3717a4e49ac60760d2d758ac3d` |

The klib was dumped with the prebuilt Kotlin/Native tool named in the environment, and the AAR was
unzipped and disassembled with the JDK's `javap`. Both dumps live in the session scratchpad, not in the
repository.

```
~/.konan/kotlin-native-prebuilt-linux-x86_64-2.3.21/bin/klib dump-metadata <klib>
unzip -q kmp.aar && unzip -q classes.jar -d classes && javap [-v] [-p] [-c] -classpath classes <class>
```

## Verified facts

| Fact | Command | Result |
|---|---|---|
| The `linuxX64` klib dumps cleanly and declares 215 classes across `com.rohittp.rentile` and `com.rohittp.rentile.internal*` | `klib dump-metadata kmp-linuxX64Main-0.1.5.klib` | exit 0, 6742 lines, 215 `// class name:` entries |
| The `macosArm64` klib declares the identical public class list | `diff` of `grep '// class name: com/rohittp/rentile/[A-Z]'` over both dumps | no differences |
| Rentile's consumer-facing injection points are `ResourceTransport` (one method) and `RawResourceStore` (three methods) | `klib dump-metadata`, lines 1510 and 1602 | see the declarations below |
| Both are `suspend`, and on JVM/Android compile to `Continuation`-taking methods | `javap -classpath classes com.rohittp.rentile.ResourceTransport com.rohittp.rentile.RawResourceStore` | `execute(TransportRequest, Continuation)`, `read/write/remove(RawResourceKey[, StoredRawResource], Continuation)` |
| `RawResourceStore` has a `remove` that RenG's `Store` has no counterpart for | same `javap`, and `kmp/src/commonMain/kotlin/com/rohittp/reng/ResourceAdapters.kt` | Rentile: `read`, `write`, `remove`; RenG: `read`, `write` |
| `TransportResponse` copies its body on construction and again on every `body` read, and rejects a status outside `100..599` | `javap -c com.rohittp.rentile.TransportResponse` | `Arrays.copyOf` in both the constructor and `getBody`; `bipush 100` / `sipush 600` guard throwing `IllegalArgumentException` |
| `StoredRawResource` copies its bytes on construction and on every `bytes` read, and validates nothing else | `javap -c com.rohittp.rentile.StoredRawResource` | `Arrays.copyOf` twice; only `Intrinsics.checkNotNullParameter` calls |
| `RawResourceMetadata` validates nothing | `javap -c com.rohittp.rentile.RawResourceMetadata` | constructor body is field stores only |
| Rentile's own `toString` implementations redact URLs and secrets — except `RawResourceKey`, which prints its `stableId` verbatim | `javap -v` constant pools of the DTOs | `TransportRequest(... url=<redacted>)`, `ProviderCredential(origin=<redacted>, queryParameterName=\1, value=<redacted>)`, `MapSession(value=<redacted>, ...)`, but `RawResourceKey(stableId=\1, resourceClass=\1)` |
| `RawResourceKey.stableId` is `sha256Hex(withRedactedAuthenticationQuery(url))` | `javap -c com.rohittp.rentile.internal.metadata.TileJsonResourceAcquirer` | `withRedactedAuthenticationQuery` then `sha256Hex` then `new RawResourceKey` |
| The eight query-parameter names Rentile redacts for identity are `access_token`, `apikey`, `api_key`, `key`, `mtsid`, `session`, `session_id`, `token`, replaced as `<name>=<redacted>` | `javap -v -c com.rohittp.rentile.internal.ContentIdentityKt` | the `authenticationQueryNames` static initialiser builds exactly those eight; format literal `\1=<redacted>` |
| The same sha256 hex is what Rentile passes as `sanitizedResourceId` on the TileJSON path | `javap -c ...TileJsonResourceAcquirer` | the digest local is the third argument to `parseOrNull(bytes, baseUrl, sanitizedId)` and the `sanitizedResourceId` of the thrown `ResourceAcquisitionException` |
| A stored record whose recomputed `sha256Hex(bytes)` differs from its `contentDigest`, or which fails to parse, triggers `removeStore(key)` and then a fetch | `javap -c ...TileJsonResourceAcquirer` `acquire` | digest compare at offset 226, `parseOrNull` at 237, both falling through to `removeStore` at 350 and then the `RAW_CACHE_MISS` path |
| Rentile never sets request metadata: `ifNoneMatch`, `ifModifiedSince` and `accept` are always the defaults | `javap -c 'com.rohittp.rentile.internal.metadata.TileJsonResourceAcquirer$fetchParseAndStore$response$1'` | the `TransportRequest` constructor is called with `aconst_null` for `metadata` and default-mask `8` |
| A transport throw is converted into `ResourceAcquisitionException` with `cause` defaulted away, and `CancellationException` is rethrown unchanged | same `javap`, exception table | two handlers: `CancellationException` → `athrow`; `Throwable` → `new ResourceAcquisitionException(...)` with default-mask `248`, which defaults `cause` |
| Only the raster and vector acquirers use the retry helper | `grep -rl executeTileRequestWithRetry` over `classes/` | `VectorResourceAcquirer`, `RasterResourceAcquirer`, and the helper's own classes |
| The helper retries **at most once**, on a thrown non-cancellation `Throwable` or on status `408`, `429`, or `500..599`, delaying `retryAfterMillis` coerced into `0..5000` ms | `javap -c com.rohittp.rentile.internal.TileRequestRetryKt` | a single boolean `first` flag; `isTransientTileFailure` compares `408`, `429`, `500..599`; `RangesKt.coerceIn(retryAfter, 0, 5000)` then `DelayKt.delay` only when positive |
| `CredentialProvider.credentialFor` and `MapSessionProvider.sessionFor` are never called anywhere in the artifact | `grep -rl 'credentialFor\|sessionFor'` over `classes/` | only the interfaces themselves and their `Companion.None` implementations |
| Rentile rasterizes with Skia and ships native Skiko payloads | aggregate POM; `ls jni` in the AAR | `org.jetbrains.skiko:skiko:0.148.2` (strict), `libskiko-android-arm64.so`, `libskiko-android-x64.so` |
| All seven publications RenG could resolve exist at `0.1.5`, plus a `kmp-jvm` RenG does not use | `curl -s -o /dev/null -w '%{http_code}'` on each POM | `kmp`, `kmp-android`, `kmp-iosarm64`, `kmp-iossimulatorarm64`, `kmp-macosarm64`, `kmp-linuxx64`, `kmp-linuxarm64`, `kmp-jvm` → all `200` |

## The published surface RenG drives

The whole entry point is one factory and one interface:

```kotlin
public object Rentile { public fun create(configuration: RentileConfiguration): BasemapRasterizer }
```

`RentileConfiguration` is a data class taking `transport: ResourceTransport` and
`rawResourceStore: RawResourceStore` with no defaults, then `sessionProvider`, `credentialProvider`,
`clock: RentileClock`, `metricsSink`, `diagnosticSink`, `executionPolicy`, and `resourceLimits`, all
defaulted. `BasemapRasterizer` is `AutoCloseable` and declares, in order: `prepare(StyleInput,
CompatibilityPolicy): PreparedStyle` (suspend), `outputRequestKey(PreparedStyle, TileId, RenderOptions):
String` (not suspend), `prepareBatch(PreparedStyle, List<TileId>, RenderOptions, ResourceAccessMode,
TileSubstitutionPolicy): PreparedBatch` (suspend), `retryExact(PreparedBatch): ExactRecoveryResult`
(suspend), `labelLayerDescriptors(PreparedStyle): List<LabelLayerDescriptor>` (not suspend),
`acquireLabelTiles(PreparedStyle, List<TileId>, ResourceAccessMode): List<ValidatedMvtTile>` (suspend),
`terrainSourceDescriptor(PreparedStyle): TerrainSourceDescriptor?` and
`groundRadianceDescriptor(PreparedStyle): GroundRadianceDescriptor?` (not suspend),
`acquireTerrainTiles(PreparedStyle, List<TileId>, ResourceAccessMode): List<ValidatedDemTile>` (suspend),
two `render` overloads — one over a `PreparedBatch`, one over a `PreparedStyle` — returning `RenderBatch`
(suspend), then `close()` and `awaitClosed()` (suspend).

`RenderedTile` carries `pngBytes` and a `contentKey`; `PreparedBatch` exposes `tiles`, `contentKeys`,
`diagnostics`, `substitutions`, and `close()`. `RenderOptions` has exactly one field, `outputSizePx`,
defaulting to 512 with a `SUPPORTED_OUTPUT_SIZES` set. `TileId` is `(z, x, y)` as `Int`s.

Cycle C's basemap acquisition therefore reduces to: build one `RentileConfiguration` with the firewall's
fixed adapters, `create` one rasterizer per renderer, `prepare` the configured style once (ADR 0004 already
fixes the style at setup, which lines up with `PreparedStyle` being a long-lived handle), then per prepared
frame call `prepareBatch` and `render` for the tiles the camera needs, and read `RenderedTile.pngBytes`.

### What RenG must not duplicate

Rentile owns style-document compilation, TileJSON resolution, MVT decoding, raster and DEM validation,
GeoJSON parsing, sprite atlas compilation, icon collision placement, and PNG encoding of the finished tile.
None of that is exposed: `PreparedStyle` publishes only `digest`, `policy`, and `diagnostics`, and the
compiled style, the sprite atlas, and the decoded vector tiles are `internal`. RenG receives encoded PNG
tiles and decodes those; it must not reimplement any basemap-side parse. `outputRequestKey` is Rentile's own
content key for a rendered tile and is the natural cache key for RenG's basemap texture residency, so RenG
should not invent a parallel tile identity.

Two capabilities exist for callers who want to draw the parts Rentile deliberately does not:
`labelLayerDescriptors` plus `acquireLabelTiles` hand back label layer JSON and validated MVT bytes, and
`terrainSourceDescriptor` / `groundRadianceDescriptor` plus `acquireTerrainTiles` hand back DEM bytes with
their encoding. Both are optional for Cycle C's mercator ground and both would put a parser back inside
RenG, so they are decisions, not requirements.

**There is no glyph or font surface at all.** Grepping the klib for `glyph` or `font` returns nothing, and
the diagnostic codes include `TEXT_ONLY_LAYER_EXCLUDED`, `TEXT_COMPONENT_REMOVED_ICON_RETAINED`, and
`TEXT_COUPLED_ICON_LAYER_EXCLUDED`. Rentile 0.1.5 does not fetch glyph PBFs and does not draw text; the
`acquireLabelTiles` path exists so a caller can. Sprite atlases are likewise not exposed — sprite JSON and
sprite PNG are fetched and compiled internally and baked into the rendered tile. RenG's `ResourceClass`
already enumerates `BASEMAP_SPRITE_JSON` and `BASEMAP_SPRITE_IMAGE` because the firewall sees those
requests pass through, not because RenG ever gets an atlas back.

## The two injection interfaces, against RenG's own

Rentile's declarations, verbatim from the klib:

```kotlin
public fun interface ResourceTransport {
    public suspend fun execute(request: TransportRequest): TransportResponse
}

public interface RawResourceStore {
    public suspend fun read(key: RawResourceKey): StoredRawResource?
    public suspend fun write(key: RawResourceKey, resource: StoredRawResource)
    public suspend fun remove(key: RawResourceKey)
}
```

RenG's, from `kmp/src/commonMain/kotlin/com/rohittp/reng/ResourceAdapters.kt`:

```kotlin
public fun interface Transport {
    public suspend fun execute(request: TransportRequest): TransportResponse
}

public interface Store {
    public suspend fun read(key: RawResourceKey): StoredRawResource?
    public suspend fun write(key: RawResourceKey, resource: StoredRawResource)
}
```

The shapes rhyme, which is why the field-level differences are the dangerous part. The adapter the firewall
installs into `RentileConfiguration` implements Rentile's two interfaces and translates as follows.

### Transport request

| Rentile `TransportRequest` | RenG `TransportRequest` | What the adapter does |
|---|---|---|
| `url: String` | `locator: ResourceLocator` | Wrap Rentile's URL string. `ResourceLocator` rejects blanks and non-scalar text, so a malformed Rentile URL becomes a RenG validation failure rather than a consumer call. The firewall matches this URL against a preregistered route; it does not forward an unrecognised one. |
| `resourceClass: ResourceClass` (8 values) | `resourceClass: ResourceClass` (11 values) | Map Rentile's `STYLE`, `TILE_JSON`, `VECTOR_TILE`, `RASTER_TILE`, `DEM_TILE`, `SPRITE_JSON`, `SPRITE_IMAGE`, `GEO_JSON` onto RenG's `BASEMAP_`-prefixed eight. RenG's `STICKER_IMAGE`, `MODEL_GLB`, and `MODEL_TEXTURE` never arrive from Rentile and must never be produced by this direction. |
| `maxResponseBytes: Long` | `maximumResponseBytes: Long` | Name differs only. The firewall does not trust Rentile's number: the route's own limit comes from `ResourceLimits.maximumBytesFor(resourceClass)` and is part of the route key, so a Rentile limit that disagrees is a route mismatch, not a new request. |
| `metadata: TransportRequestMetadata(ifNoneMatch, ifModifiedSince, accept)` | same three fields | **Measured: Rentile always sends all three as null.** Every conditional header and every `Accept` is RenG's, derived from the route and the store lookup (`ResourceClass.acceptValue` in `internal/ValueSupport.kt`). Rentile's request metadata is therefore not an input to translate; it is a value to assert empty. |
| — | constructor is `internal` | Consumers cannot fabricate a RenG `TransportRequest`; only RenG builds one. Nothing to do, but it means the adapter is the only writer. |

### Transport response

| Rentile `TransportResponse` | RenG `TransportResponse` | What the adapter does |
|---|---|---|
| `statusCode: Int`, required `100..599` | `statusCode: Int`, no range check in the value class | RenG's response rules accept only `200` and `304`; everything else is `INVALID_TRANSPORT_RESPONSE`. So by the time bytes reach Rentile the status is always `200` — a `304` has already been merged with its validated stale baseline into a full response (`resolveNotModifiedResponse`, `ContentProvenance.TRANSPORT_304_MERGED`). Rentile never sees a `304`. |
| `body: ByteArray`, copied in and out | `body: ByteArray`, `freshCopy()` in and out | Both sides defensively copy on construct and on read. A tile's bytes are therefore copied up to four times per exchange: consumer → RenG in, RenG out, Rentile in, Rentile out. That is a real per-tile cost at basemap sizes and a legitimate thing for Cycle C to measure rather than assume away. |
| `metadata.contentType`, `.etag`, `.lastModified` | same three | Direct pass-through of the validated values. |
| `metadata.expiresAtEpochMillis: Long?` | `metadata.freshUntilEpochMillis: Long?` | Same concept, different name and different origin: RenG's is an absolute epoch instant the consumer supplies; Rentile's field name says the same thing. Pass through. |
| `metadata.cacheControl: String?` | absent | RenG's protocol has no `Cache-Control` at all — freshness is only the absolute instant. The adapter passes `null`; Rentile's own freshness reasoning loses nothing it can act on, because RenG has already made the freshness decision. |
| `metadata.vary: List<String>` | absent | Pass empty. RenG keys by route, not by varying request dimensions. |
| `metadata.retryAfterMillis: Long?` | absent | **This is the single most load-bearing omission.** It is the only input to Rentile's retry delay. Passing `null` makes the delay zero, so the retry — if the firewall ever let it reach the consumer — would be immediate. Since the firewall replays a latched outcome instead, the value is moot; but the adapter must pass `null` deliberately, not by oversight, and Cycle C's spec should say so. |
| `metadata.redirectLocation: String?` | absent | RenG fails every redirect status before this point, so pass `null`. |
| `metadata.wireByteCount: Long?` | absent | Observability only. Pass `null`, or a RenG-side count if the spec wants Rentile's metrics to be meaningful. |

### Store key and record

| Rentile | RenG | What the adapter does |
|---|---|---|
| `RawResourceKey(stableId: String, resourceClass: ResourceClass)`, public constructor, no validation, `toString` prints `stableId` | `RawResourceKey(stableId, resourceClass)` with `internal` constructor and `toString() = "RawResourceKey(resourceClass=...)"` | **Do not translate the key by field.** Rentile's `stableId` is its own sha256 of the redacted URL; RenG's is its canonical-identity digest (`resourceKey.stableId == canonicalIdentity.digest.lowercaseHex`, asserted in `FramePlanningCore`). They are different digests of different inputs. The adapter looks Rentile's key up as an opaque token — Cycle B's `RentilePrivateKey` — and answers with the RenG key already bound to that route. |
| `StoredRawResource(bytes, contentDigest, metadata)` | `StoredRawResource(bytes, contentDigest, metadata)` | Same three fields. RenG additionally requires a lowercase 64-hex `contentDigest` over non-empty bytes within the route limit (`copyValidStoredResource`); Rentile validates nothing on construction but does recompute `sha256Hex(bytes)` and compare it against `contentDigest` after reading. The digest conventions agree, so a record RenG accepts is a record Rentile accepts. |
| `RawResourceMetadata(contentType, etag, lastModified, freshUntilEpochMillis, storedAtEpochMillis)` | `StoredRawResourceMetadata(contentType, etag, lastModified, freshUntilEpochMillis, storedAtEpochMillis)` | Field-for-field identical, including the non-null `storedAtEpochMillis`. Only the type name differs. This is the one clean mapping in the whole boundary. |
| `read(key): StoredRawResource?` | `read(key): StoredRawResource?` | The firewall answers from the joined route sample; it does not issue a second consumer read for a route that already read. |
| `write(key, resource)` | `write(key, resource)` | Permitted only after RenG has verified the record against the latched response and its stricter rules. ADR 0016 enumerates the class-specific ordering, including the sprite pair being prevalidated jointly and style bytes staging until compilation succeeds. |
| `remove(key)` | **no such method** | The hard mismatch. Rentile calls `remove` on a digest mismatch or a parse failure — measured — and RenG's `Store` cannot express it. The firewall absorbs the call: private, terminal, no consumer mutation, no repair, no follow-on exchange. An adapter that instead "mapped" `remove` onto anything consumer-visible would break RenG's published contract. |

### Cross-cutting mismatches

- **Naming.** `ResourceTransport`/`RawResourceStore` versus `Transport`/`Store`; `maxResponseBytes` versus
  `maximumResponseBytes`; `expiresAtEpochMillis` versus `freshUntilEpochMillis`; `RawResourceMetadata`
  versus `StoredRawResourceMetadata`. Mechanical, but each one is a place a hand-written adapter can pass
  the wrong field silently because the types match.
- **Resource identity.** Two incompatible digests, described above. Nothing in the boundary makes the
  mistake of treating them as interchangeable loud, because both are `String`.
- **Access mode.** RenG's `ResourceAccessMode` and Rentile's have the same three names — `NORMAL`,
  `CACHE_ONLY`, `RELOAD` — but Rentile carries mode as a `prepareBatch` / `acquire*` argument and never
  puts it in a `TransportRequest` or a `RawResourceKey`. ADR 0016 already requires binding mode from the
  outer preparation invocation for exactly this reason.
- **Byte-array ownership.** Both sides copy defensively at both ends, so no aliasing bug is possible, at
  the cost of the copy count above.
- **Cancellation.** Both sides treat cancellation as an unwrapped coroutine cancellation, and Rentile
  rethrows `CancellationException` before its own wrapping — measured at the transport call site and in the
  retry helper. This is the one semantic where the two contracts already agree.
- **Error signalling.** Rentile throws sealed `RentileException` subclasses carrying `code`, `stage`,
  `diagnostics`, `affectedTiles`, and a `cause`. RenG throws `RenGException(code, stage, diagnostics)` —
  whose constructor has no `cause` parameter at all and passes `null` to `RuntimeException`, and which
  permits at most one diagnostic. RenG structurally cannot forward a Rentile cause, which is the right
  outcome, but it also means every Rentile failure must be *classified* into a `RenGErrorCode` rather than
  wrapped.
- **Limits.** Rentile's `ResourceLimits` has fourteen fields; RenG's has eight and only five of them are
  basemap byte limits. RenG has no counterpart for `maxRasterDimensionPx`, `maxDecodedRasterBytes`, the five
  MVT limits, or `maxRedirects`. Those govern Rentile-internal decode safety, so leaving them at Rentile's
  defaults is defensible — but it is a choice, and RenG's own `ResourceLimits` cannot currently express it.
- **Sinks and clock.** `MetricsSink`, `DiagnosticSink`, and `RentileClock` are non-suspending single-method
  interfaces with `None` / `System` defaults. RenG has its own `DiagnosticSink` with a different
  `Diagnostic` type. Bridging Rentile diagnostics into RenG's sink means mapping fourteen
  `DiagnosticCode`s and eight `PipelineStage`s onto RenG's, and `RenderDiagnostic.message` and `details`
  are free-form Rentile text — safe to drop, not safe to forward verbatim without deciding they are
  Rentile's literals and not adapter echoes.

## Error, retry, and cancellation semantics

`RentileException` is a sealed `Exception` with sixteen concrete subclasses and a matching sixteen-value
`RentileErrorCode`. Every subclass carries `code`, `stage: PipelineStage`, `diagnostics`, and
`affectedTiles`; `ResourceAcquisitionException` adds `resourceClass`, `sanitizedResourceId`,
`statusCode: Int?`, and `retryAfterMillis: Long?`; `SafetyLimitException` adds `limitName`, `limit`, and
`observed`; `TileSubstitutionException` and `TileSubstitutionLimitException` carry a `primaryFailure` and
nested failures; `BatchRenderException` carries a `primaryFailure` plus `concurrentFailures`.

Measured behaviour at the boundary, from the TileJSON transport call site:

- A `CancellationException` thrown by the injected transport propagates unchanged. Rentile catches it
  explicitly and rethrows before its general handler.
- Any other `Throwable` is replaced by `ResourceAcquisitionException("TileJSON transport failed",
  TILE_JSON, sanitizedId)` with `cause` left at its default of `null`. Rentile drops the adapter's message
  and cause on its own. RenG must still not pass an adapter throwable in — but if a Rentile exception does
  escape, its `message` is Rentile's literal and its `sanitizedResourceId` is a hex digest, so the leak risk
  is in `RawResourceKey.toString()` and in `RenderDiagnostic.message`/`details`, not in the exception text.

Measured retry behaviour, from `TileRequestRetryKt`:

- Only `RasterResourceAcquirer` and `VectorResourceAcquirer` route their exchange through
  `executeTileRequestWithRetry`. Style, TileJSON, sprite, and GeoJSON call `ResourceTransport.execute`
  directly, exactly once per acquisition attempt. DEM tiles go through the raster acquirer, so they inherit
  the retry.
- The helper holds a single boolean "first attempt" flag. On a non-cancellation `Throwable`, or on a
  response whose status is `408`, `429`, or in `500..599`, it retries once and only once, then returns or
  rethrows whatever the second attempt produced.
- The delay before the retry is `retryAfterMillis` coerced into `0..5000` and is skipped entirely when that
  is zero or absent. `MAX_TILE_RETRY_DELAY_MILLIS` is `5000L`.
- `CancellationException` is never retried.

So `CLAUDE.md`'s note that "Rentile's private retry calls replay the operation's latched outcome" describes
RenG's side of a real, bounded second call: **at most one additional `ResourceTransport.execute` per raster,
DEM, or vector tile exchange**. That is now measured rather than remembered. What the artifact still cannot
show is whether the second call is the only source of duplicate exchanges in practice — `SingleFlight`
joins, `ResourceWorkCoordinator` permits, and the substitution paths all interact, and only a running spike
counts the actual calls.

Measured store-repair behaviour: a stored record failing either the digest recomputation or the class
parser causes `removeStore(key)` followed by the fetch path, in the TileJSON acquirer. Every other acquirer
declares the same private `readStore`/`writeStore`/`removeStore` trio and its own `SingleFlight`, so the
same shape is very likely — but only TileJSON's control flow was actually traced, and the others should be
traced or spiked before Cycle C's spec asserts them.

RenG's reconciliation is already settled by contract and needs no new decision: at most one consumer
exchange per logical resource operation, corruption terminal without consumer mutation, no retries, no
repairs, no fallbacks, cancellation unwrapped. What Cycle C must add is the classification table from
`RentileErrorCode` to `RenGErrorCode`, which does not exist yet and is not a one-to-one map — RenG has no
code for rasterization failure, PNG encoding failure, or tile substitution, and Rentile has none for
RenG's GL and lifecycle codes.

## The private key and credentials

Cycle B carries an opaque `RentilePrivateKey(token: String)` per external resource, redacted in `toString`,
resolved through an `internal fun interface RentilePrivateKeyResolver.resolve(locator, resourceClass)`.
`FramePlanningCore.staticResourceTraversal` memoises one key per `(locator, resourceClass)` pair, and
`ResourceOperationStateMachine` keeps a `claimIndexByPrivateKey` map so a collision is detected rather than
silently joined. Cycle B's tests supply fakes such as `RentilePrivateKey("${resourceClass.name}|${locator.value}")`.

The artifact says what the real resolver must compute. Rentile builds its store key as
`RawResourceKey(sha256Hex(withRedactedAuthenticationQuery(url)), resourceClass)`, measured in
`TileJsonResourceAcquirer.acquire`, and `withRedactedAuthenticationQuery` rewrites any query parameter
whose lowercased name is one of `access_token`, `apikey`, `api_key`, `key`, `mtsid`, `session`,
`session_id`, or `token` to `<name>=<redacted>`, rejoining with `&`. So Cycle C's real resolver is a
sha256 over that same redacted-URL string, paired with the mapped Rentile `ResourceClass` — reproducing
Rentile's derivation rather than inventing a token. That is the whole point of the type being opaque and
redacted: it is Rentile's identity for the resource, and it is a URL-derived string even after redaction,
which is why the redacted `toString` matters. Note the measured hazard: Rentile's own
`RawResourceKey.toString()` prints `stableId` in the clear, so a Rentile key object must never reach a RenG
diagnostic or log.

Two caveats that the artifact makes explicit rather than leaving to guesswork.

First, this derivation was traced on the TileJSON path only. Every acquirer references
`withRedactedAuthenticationQuery`, so the shape is consistent, but whether every class hashes exactly the
redacted URL and nothing else — no class prefix, no tile coordinate suffix — was verified for TileJSON
alone. Reproducing a digest RenG computes independently and having Rentile fail to match it would be a
silent cache miss, not an error, so this must be proven per class before Cycle C relies on it.

Second, and more consequentially: **`CredentialProvider` and `MapSessionProvider` are declared but never
invoked anywhere in Rentile 0.1.5.** Nothing in the compiled artifact calls `credentialFor` or `sessionFor`.
Credentials in 0.1.5 reach the network because they are already present in the URLs of the style document:
`StyleCompiler` wraps each resource URL in an internal `ProtectedResourceUrl(canonicalUrl, secretContext,
index)`, where `canonicalUrl` is the redacted form and `SecretContext` holds the real strings behind an
atomic reference with a `clear()`. The rasterizer tracks those contexts and clears them when the prepared
style dies. `ProviderCredential.toString()` and `MapSession.toString()` do redact their secrets, so the
types are secret-aware — they simply are not wired to anything yet.

For Cycle C that means: pass `CredentialProvider.None` and `MapSessionProvider.None` (the defaults) and
document that credentialing is the consumer's business, carried in the style document's URLs and in
whatever the injected transport adds. Do not build RenG machinery around either provider on the assumption
that Rentile consults it, and do not report to a consumer that RenG "supports" Rentile credential
providers.

## Version and target risk

`0.1.5` is the current release (`maven-metadata.xml` lists `0.1.4` and `0.1.5`, with `<release>0.1.5`).
Every POM RenG could need returns HTTP 200 anonymously:

| Coordinate | POM status |
|---|---|
| `com.rohittp.rentile:kmp:0.1.5` | 200 |
| `kmp-android` | 200 |
| `kmp-iosarm64` | 200 |
| `kmp-iossimulatorarm64` | 200 |
| `kmp-macosarm64` | 200 |
| `kmp-linuxx64` | 200 |
| `kmp-linuxarm64` | 200 |
| `kmp-jvm` | 200 (published; RenG has no `jvm` target and does not resolve it) |

The aggregate Gradle module metadata confirms the same set structurally: variants redirect to
`kmp-android`, `kmp-iosarm64`, `kmp-iossimulatorarm64`, `kmp-jvm`, `kmp-linuxarm64`, `kmp-linuxx64`, and
`kmp-macosarm64`, with native target attributes `ios_arm64`, `ios_simulator_arm64`, `linux_arm64`,
`linux_x64`, and `macos_arm64`. No `macosX64` and no `iosX64` — matching RenG's own six-target commitment
under ADR 0010, so RenG never asks Rentile for a target Rentile lacks. `CLAUDE.md`'s statement that Rentile
publishes every target RenG needs as of `0.1.5` holds.

The transitive graph is the risk worth naming, not the target list. Rentile's `linuxX64ApiElements`
requires `com.squareup.wire:wire-runtime:6.4.5` and `org.jetbrains.skiko:skiko:0.148.2` as **strict**
versions, plus `kotlin-stdlib:2.3.21`, `kotlinx-coroutines-core:1.11.0`,
`kotlinx-serialization-json:1.11.0`, and `okio:3.18.1`. Skiko is a native dependency — the Android AAR
ships `libskiko-android-arm64.so` and `libskiko-android-x64.so` — so Rentile drags a Skia runtime into every
RenG consumer on every target. Two consequences for Cycle C: a strict Skiko constraint will hard-conflict
with any consumer pinning a different Skiko, and the decomposition's open question about how RenG decodes
PNG on six targets ("Skiko is proven on these targets but heavy") is partly answered — Skiko is already in
the graph transitively, so choosing it for RenG's own decode adds no new coordinate, only an `implementation`
dependency and an explicit decision.

Rentile is declared `implementation(libs.rentile.kmp)` in `kmp/build.gradle.kts` with the version in
`gradle/libs.versions.toml`, so none of these types can reach RenG's public ABI, and ADR 0003's reasoning
holds unchanged.

## What the artifact cannot tell you

A klib publishes declarations. Bytecode publishes one compilation's control flow. Neither publishes
behaviour under concurrency, timing, or real network conditions. These remain open and must not be written
into a spec as if measured:

- **Thread and dispatcher affinity.** Whether `ResourceTransport.execute` and the three `RawResourceStore`
  methods may be called concurrently, from which dispatchers, and on Kotlin/Native from which worker.
  `ResourceWorkCoordinator` holds semaphores sized by `ExecutionPolicy` and each acquirer holds a
  `SingleFlight` over a `CoroutineScope`, so concurrent adapter calls plainly happen — but the actual
  parallelism, ordering, and reentrancy are not derivable from signatures.
- **Actual exchange counts per logical resource.** The retry helper's bound is measured; how it composes
  with `SingleFlight` joins, `prepareBatch` fan-out, and substitution retries is not. ADR 0016 records
  proofs at 256 tiles and concurrency eight; Cycle C should re-establish those counts against `0.1.5`
  itself rather than inherit them.
- **Per-class store ordering.** ADR 0016 states class-specific write ordering in detail. Only the TileJSON
  ordering was traced here. The other seven classes' read/validate/write/remove sequences are asserted by
  the ADR, not by this document.
- **Freshness semantics.** Whether Rentile consults `RentileClock` and `freshUntilEpochMillis` before
  returning a stored record, and whether it ever revalidates. The TileJSON path references
  `nowEpochMillis` once and never constructs request metadata, which suggests no conditional revalidation
  from Rentile at all — but that is an inference from a single reference, and the firewall owns freshness
  anyway.
- **Cancellation propagation depth.** That the transport call site and the retry helper rethrow
  `CancellationException` unchanged is measured. That every path from `prepareBatch` and `render` down to
  the adapter does so, and that `close()` / `awaitClosed()` interact correctly with a cancelled
  preparation, is not.
- **Whether `sanitizedResourceId` is always a hex digest.** Measured for TileJSON. If any class puts a URL
  fragment there, forwarding it would violate RenG's redaction rule.
- **Whether the sprite pair's write really precedes atlas validation**, and whether style has no raw-store
  write at all, as ADR 0016 asserts. The sprite acquirer's method list is consistent with it; the control
  flow was not traced.
- **Anything about `retryExact`, `TileSubstitutionPolicy`, or `ResourceSubstitution` in practice.** RenG
  keeps substitution `Disabled` and never calls `retryExact`, so this is deliberately unexplored.

The way to close these is a real spike, not more static reading: a small `linuxX64` or `macosArm64`
executable that builds a `RentileConfiguration` over counting, recording adapter stubs, drives `prepare`,
`prepareBatch`, and `render` against fixture bytes, and asserts the exact call sequence per resource class,
the concurrency observed, the cancellation shape, and the digests Rentile asks for. That spike belongs in
the scratch tree, and its findings belong in Cycle C's spec. If the author's Rentile checkout becomes
available, reading `Resources.kt`, `ContentIdentity.kt`, `TileRequestRetry.kt`, and the six acquirers
answers most of the list directly and much faster — but the spike is still what proves the published
`0.1.5` behaves as the source says.

## What Cycle C's spec must decide

- The per-class `RentilePrivateKey` derivation, proven against the artifact for all eight basemap classes,
  and what happens when a claim collides.
- The `RentileErrorCode` → `RenGErrorCode` classification table, including the codes RenG currently lacks
  (rasterization failure, PNG encoding failure, substitution) and whether to add codes or fold them.
- The `RenderDiagnostic` → RenG `Diagnostic` mapping, and the explicit rule that Rentile's `message` and
  `details` are dropped rather than forwarded.
- Which `TransportResponseMetadata` fields the adapter synthesises versus nulls, named field by field, with
  `retryAfterMillis` called out as deliberately `null`.
- Whether Rentile's `ExecutionPolicy` and the nine `ResourceLimits` fields RenG cannot express stay at
  Rentile defaults or become RenG configuration, and whether RenG's `ResourceLimits` grows.
- Whether `MetricsSink` and `RentileClock` are wired to RenG's clock and observability or left at
  `None` / `System`, given that RenG's freshness decisions already need a clock.
- That `CredentialProvider.None` and `MapSessionProvider.None` are used, with the artifact finding recorded
  so nobody later assumes Rentile applies credentials.
- Renderer-to-rasterizer lifetime: one `BasemapRasterizer` per renderer, when `prepare` runs relative to
  ADR 0004's setup-time style, and how `close()` / `awaitClosed()` fold into ADR 0017's owner-wide terminal
  transition and ADR 0015's context requirement — noting that Rentile's close is not GL-scoped at all.
- Whether `outputRequestKey` becomes RenG's basemap tile cache key, and how `PreparedBatch.close()` and
  `contentKeys` interact with refcounted residency across concurrently live prepared frames.
- Whether Cycle C or a later cycle takes `acquireLabelTiles` and `acquireTerrainTiles`, both of which put a
  parser back inside RenG.
- Whether RenG's own PNG decode uses the Skiko already in the graph, and what the strict-version conflict
  story is for a consumer that pins Skiko differently.
- What the spike must measure before any of the above is asserted, and which assertions are provisional
  until it runs.
