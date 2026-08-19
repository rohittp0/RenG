# Re-running the counting-stub spike against Rentile 0.3.0

ADR 0016 rests on a counting-stub spike that drove the published `com.rohittp.rentile:kmp:0.1.5`
artifact through `prepare`, `prepareBatch` and `render` to real PNG output, with all eight basemap
classes exercised (`docs/superpowers/specs/2026-08-18-cycle-c-resource-layer-design.md`, "Measured
basemap-engine behaviour"). Rentile has since published `0.3.0`, adding label primitives and a glyph
atlas — a feature RenG itself requested (`docs/research/2026-08-18-rentile-label-primitives-request.md`)
and one that plausibly touches the resource-acquisition surface the firewall depends on. Cycle C's
Task 17 ("The firewall adapters") is next, so this document re-runs the measurement against `0.3.0`
before that task is written, rather than after.

Everything below separates three kinds of claim exactly as the prior research documents do.
**Measured** claims came out of a real, running counting-stub spike quoted below. **Read** claims came
from reading the local Rentile checkout's `commonMain` source, cross-checked against the published
artifact's provenance (see below) so that source reading and artifact behaviour are known to agree.
**Unverified** is stated plainly rather than implied.

## Provenance

| Input | Value |
|---|---|
| RenG worktree | `/Users/rohittp/Data/Other/reng-c-bench`, branch `feat/cycle-c-bench`, HEAD `4b7b1ba110d5fd8978be59c777369050c5ce5dac` (unchanged by this spike; nothing in `kmp/` was touched) |
| Rentile checkout read | `/Users/rohittp/Data/Other/rentile`, HEAD `53fb719775b14f9edfdb9b3dcbb89c7aa7901575`, clean working tree, `VERSION_NAME=0.3.0` |
| Rentile coordinate measured | `com.rohittp.rentile:kmp-jvm:0.3.0`, resolved anonymously from `https://maven.rohittp.com`. No `mavenLocal()`, no `-SNAPSHOT`. |
| `maven-metadata.xml` versions | `0.1.4`, `0.1.5`, `0.2.0`, `0.3.0`; `<release>0.3.0`. `0.2.0` was not independently examined — this document jumps `0.1.5 → 0.3.0` exactly as the task framed it. |

**Sources-jar provenance**, reproducing the original spike's check at the new version:

```
curl -sL https://maven.rohittp.com/com/rohittp/rentile/kmp/0.3.0/kmp-0.3.0-sources.jar
sha256: 84466003be78bcbec135e88797bc5423e021469653216b3cb70eeab3c2418afe
diff -rq <local checkout kmp/src/commonMain/kotlin/com> <sources-jar commonMain/com>
```

Result: the only differences are three files present in the sources jar and absent from the checked-in
tree — `internal/glyph/Glyph.kt`, `internal/glyph/Glyphs.kt`, `internal/mvt/Tile.kt` — all three Wire
codegen output from `kmp/src/commonMain/proto/{glyphs,vector_tile}.proto`, which are committed as
`.proto` schemas rather than generated `.kt` files. Every other file that exists in both trees is
byte-identical (`diff -rq` reported no "differ" lines at all). This is the same shape of finding the
original spike recorded for `0.1.5` ("byte-identical … apart from one generated file"), just with three
generated files instead of one, because `0.3.0` added a second `.proto` schema (`glyphs.proto`) for the
label work. **Source reading and artifact measurement still agree for `0.3.0`.**

## How this was run

A standalone, throwaway Gradle project at `build/cycle-c-spikes/rentile-030-respike/` (gitignored,
never committed, matching the pattern the original spike set). It is a plain Kotlin/JVM
`application`, not a Kotlin Multiplatform module, and not part of RenG's own build:

- `implementation("com.rohittp.rentile:kmp-jvm:0.3.0")`, resolved with `exclusiveContent` scoped to
  `com.rohittp.rentile` from `https://maven.rohittp.com`; everything else from Central and the
  JetBrains Compose dev repository (for the Skiko AWT runtime, exactly as `consumer-smoke` and
  Rentile's own `jvmTest` source set do).
- `org.jetbrains.skiko:skiko-awt-runtime-macos-arm64:0.148.2` added explicitly, because Rentile's own
  build only adds this to its *test* source sets — a JVM consumer must add it itself.
- No Wire dependency. RenG's own repository policy forbids declaring a protobuf runtime, and this
  spike measures the published artifact rather than exercising a decoder, so MVT tiles and glyph-range
  payloads are built with a 60-line hand-rolled protobuf writer matching
  `commonMain/proto/{vector_tile,glyphs}.proto` field-for-field, mirroring the exact fixture shapes
  Rentile's own `commonTest/kotlin/.../RentileRuntimeTest.kt` uses (`placeNameVectorTile`,
  `testGlyphRange`) — read, not imported, since those are `internal` test fixtures in a different
  compilation.
- Counting/recording `ResourceTransport` and `RawResourceStore` implementations logging every call's
  sequence number, resource class, URL, `TransportRequestMetadata` (`accept`, `ifNoneMatch`,
  `ifModifiedSince`), and byte limit; the store additionally tracks in-flight concurrency and exposes
  test-only corruption helpers (digest-mismatch, and self-consistent-but-invalid content).
- One long-lived `BasemapRasterizer` drives most scenarios (matching ADR 0016's "one long-lived
  Rentile instance"); the DEM/GeoJSON and 256-tile concurrency scenarios use their own instance each,
  since they are structurally independent measurements.
- Driven through `prepare`, `prepareBatch`, `render`, `acquireLabelCandidates`,
  `terrainSourceDescriptor`, and `acquireTerrainTiles` — the first three named in the task, the latter
  three because `GLYPH_RANGE` and `DEM_TILE` are unreachable from `prepare`/`prepareBatch`/`render`
  alone (see "What changed" below).

Full source: `build/cycle-c-spikes/rentile-030-respike/src/main/kotlin/Spike.kt`. Full captured run
output: `build/cycle-c-spikes/rentile-030-respike/run-output.txt`. Neither is committed.

Real PNG output was produced and verified by magic bytes for both a vector-sourced render (683 bytes)
and a raster-sourced render (760 bytes) — this is genuine Skia rasterization through the published
artifact, not a stub.

## ADR 0016's claims, re-verified

| Claim (ADR 0016) | Status at 0.3.0 | Evidence |
|---|---|---|
| Rentile never carries `ResourceAccessMode` in `TransportRequest` or `RawResourceKey` | **Unchanged** | Both types' fields are unchanged in `Resources.kt`; no access-mode field on either. |
| Style has no Rentile raw-store write | **Unchanged, measured** | Across every scenario, `ResourceClass.STYLE` appears only in the transport log, never once in the store log (no read/write/remove). |
| TileJSON, vector-tile, raster-tile, and GeoJSON write only after their own parse/decode validation | **Unchanged, measured for TileJSON/vector/raster/geojson** | Source-confirmed unchanged call order in `TileJsonResourceAcquirer`, `VectorResourceAcquirer`, `RasterResourceAcquirer`, `GeoJsonResourceAcquirer`; each still calls its `writeStore` only after a successful `parseOrThrow`/`decode`/`validateRasterOrThrow`. Runtime log shows one read (miss) then one write per class, consistent. |
| DEM reaches the store after generic bounded image validation, not terrain-encoding semantic validation | **Unchanged, measured** | DEM and raster tiles share `RasterResourceAcquirer`; `validateRasterOrThrow` only decodes as a generic image (Skia `Image.makeFromEncoded` plus dimension/byte-count bounds) — no Mapbox/Terrarium elevation check anywhere in that path. `acquireTerrainTiles` returned a validated 848-byte DEM tile from a plain solid-colour PNG with no elevation semantics at all. |
| Sprite JSON and image are written before their joint atlas validation | **Unchanged, measured** | Store log: `WRITE SPRITE_IMAGE` and `WRITE SPRITE_JSON` both appear before `prepare()` returns; `compile()` (the joint validation) runs only after both async fetches complete, so both writes necessarily precede it. |
| A poisoned sprite record (digest-consistent, content invalid) is terminal — no removal, no repair, survives a second `prepare()` | **Unchanged, measured** | See "Original design-changing measurements" below — reproduced exactly. |
| A Rentile `remove` request is private and terminal from RenG's perspective | **Unchanged, measured** (as a Rentile-side fact — RenG's own firewall does not exist yet to consume it) | `removeStore` call sites are unchanged in TileJSON/vector/raster/geojson/sprite acquirers, and `GLYPH_RANGE` (new) has one too — see below. |
| Rentile's own store-vs-transport concurrency shape (store reads unbounded, transport bounded by `ExecutionPolicy`) | **Unchanged, and more starkly measured** | 256-tile batch: transport peak concurrency **6** (within the configured `ExecutionPolicy.maxConcurrentExchanges = 8`); store peak concurrency **256 of 256** — literally every read was in flight at once. The original spike measured 252/256; this run's number is cleaner but the qualitative claim ("store reads are unbounded") is the same and, if anything, stronger evidence for it. |

## The three original design-changing measurements — all three still hold

**1. `accept` is not universally null — now on three classes, not two.** Across every one of the 20
real transport calls logged, `ifNoneMatch` and `ifModifiedSince` were `null` on every single call, no
exceptions. `accept` was `null` on `STYLE`, `TILE_JSON`, `VECTOR_TILE`, `RASTER_TILE`, `DEM_TILE`, and
`GEO_JSON`, and non-null on exactly three classes:

| Class | `accept` |
|---|---|
| `SPRITE_JSON` | `application/json` |
| `SPRITE_IMAGE` | `image/png` |
| `GLYPH_RANGE` (new) | `application/x-protobuf` |

The design spec's sentence "`accept` … is `application/json` on `BASEMAP_SPRITE_JSON` and `image/png`
on `BASEMAP_SPRITE_IMAGE`" is no longer a complete list — the firewall must also assert a class-specific
non-null `accept` for the new class.

**2. A poisoned sprite record is still terminal.** Reproduced exactly: after a successful `prepare()`
wrote a valid `SPRITE_JSON` record, the stored record was replaced with garbage bytes whose recomputed
digest was made to match the stored `contentDigest` (so the cheap digest check that gates a hit passes,
exactly as the sprite acquirer's own comment describes — "validates only byte size and digest
consistency on a store hit and never parses before returning"). Two consecutive `prepare()` calls on
the *same* rasterizer both failed identically:

```
first re-prepare:  FAILED ResourceDecodeException code=RESOURCE_DECODE_FAILED stage=RESOURCE_DECODING
second re-prepare: FAILED ResourceDecodeException code=RESOURCE_DECODE_FAILED stage=RESOURCE_DECODING
```

The store log for both calls shows only `READ SPRITE_JSON hit=true` / `READ SPRITE_IMAGE hit=true` —
no `REMOVE`, no repair, no follow-on fetch. Confirmed unchanged at `0.3.0`.

**3. Store reads are unbounded; only the transport call sits inside Rentile's concurrency permits.**
Reconfirmed above with a cleaner number (256/256 vs. the original 252/256). Both runs support the same
conclusion the design spec already draws from it: the firewall must answer from a joined route sample
rather than forwarding every store read, and ADR 0016's own "256 tiles at concurrency eight" language
describes RenG's boundary, not a ceiling Rentile itself imposes internally.

## What changed: the new acquisition surface

**`ResourceClass` gained a ninth value, `GLYPH_RANGE`.** The full enum at `0.3.0`, in declaration
order, is `STYLE, TILE_JSON, VECTOR_TILE, RASTER_TILE, DEM_TILE, SPRITE_JSON, SPRITE_IMAGE, GEO_JSON,
GLYPH_RANGE` (`kmp/src/commonMain/kotlin/com/rohittp/rentile/Resources.kt:4-14`). Every place ADR 0016
and the design spec say "eight basemap classes" is now off by one.

**`GLYPH_RANGE` is reachable only through a new, fourth acquisition entry point — never through
`prepare`, `prepareBatch`, or `render`.** The public method is
`acquireLabelCandidates(style, tiles, resourceAccess)`, alongside a new
`labelCandidateRequestKey(style, tiles)` for its cache key. This is a sibling of the already-declared
(and, per the original spike, deliberately unexercised) `acquireLabelTiles` and `acquireTerrainTiles` —
so `0.3.0` does not change the shape of the surface RenG must drive, it just makes one previously-inert
sibling method live and load-bearing. **A firewall built only against `prepare`/`prepareBatch`/`render`
will never see `GLYPH_RANGE` at all, by construction, not by oversight** — Task 17 needs an explicit
decision about whether `acquireLabelCandidates` is in scope.

**Measured `GLYPH_RANGE` behaviour**, from `GlyphResourceAcquirer.kt` and the live run:

| Property | Value | Evidence |
|---|---|---|
| Request metadata | `accept = "application/x-protobuf"`, `ifNoneMatch = null`, `ifModifiedSince = null` | Transport log, every `GLYPH_RANGE` call |
| Byte limit | `ResourceLimits.maxGlyphRangeBytes`, default 1 MiB | `maxResponseBytes=1048576` in every logged call |
| Write ordering | Written to the store immediately after a generic byte-limit check, **before** `GlyphRangeDecoder.decode` validates the payload as a well-formed SDF protobuf block | Source: `acquireRaw` writes, then returns to `acquire()`, which calls `decode(bytes, …)` afterward. This is the same "generic-bound write, semantic validation deferred" shape as DEM, and unlike TileJSON/vector/raster/geojson's "validate then write." |
| Corruption recovery | **Not terminal** — a plain digest mismatch triggers `removeStore` then a fresh fetch and a fresh write, exactly like TileJSON/vector/raster/geojson | Measured: after flipping one byte of the stored `GLYPH_RANGE` record (digest now stale), the second `acquireLabelCandidates` call logged `READ GLYPH_RANGE hit=true → REMOVE GLYPH_RANGE → (transport fetch) → WRITE GLYPH_RANGE`, and the batch recovered its candidate successfully. This is the deliberate contrast with sprite's terminal poisoning, worth stating explicitly since it means the firewall's "terminal on stored corruption" posture is *not* uniform across classes — it already wasn't (sprite was always the outlier), and glyph confirms it sits with the majority, not with sprite. |
| Identity | `SingleFlight<String, …>` keyed by `sha256Hex(withRedactedAuthenticationQuery(url))`, same redaction and hashing convention as every other class | Source read, consistent with the resolver Cycle C's spec already requires for the other eight classes |

**A same-URL cache-reuse nuance, incidental but worth flagging for Task 17.** In the combined
vector+label scenario, the same `(source, tile)` vector tile was requested twice — once by
`prepareBatch` (for drawing) and once by `acquireLabelCandidates` (to decode symbol features) — as two
*separate* rasterizer method calls, not one preparation invocation. The second request was answered
from Rentile's own store consultation (`READ VECTOR_TILE hit=true`) with **no second transport call**.
This is Rentile's own internal cache doing its job, independent of anything RenG's firewall does — it
means a double-fetch risk across two separate public-method calls (e.g. `prepareBatch` then
`acquireLabelCandidates` for the same tiles) is lower than reasoning about the firewall's
per-invocation route registry alone would suggest, *provided* RenG's injected `Store` answers
consistently for the same raw key across both calls — which it must, since RenG owns that adapter.

**`ResourceLimits` grew from fourteen fields to sixteen**, both appended at the end rather than
inserted, specifically to avoid a positional-argument hazard: Rentile's own doc comment
(`Api.kt:440-448`) explains that inserting the two new fields between existing ones would have made a
caller's pre-existing positional six-argument call silently reassign `maxRasterDimensionPx`'s intended
`4096` to `maxGlyphRangeBytes` instead — a same-type, compiling, silently-wrong assignment. The two new
fields: `maxGlyphRangeBytes` (default 1 MiB) and `maxGlyphRangesPerBatch` (default 64, sized from "15
glyph ranges observed at Tokyo z14, dense CJK" in Rentile's own rolling corpus). **Consequence**: the
design spec's existing sentence "the nine `ResourceLimits` fields RenG cannot express" is now eleven
unless Cycle C decides to expose a glyph-range limit of its own.

**Incidental, unrelated finding**: the local checkout's `GeoJsonResourceAcquirer` rejects a `Polygon`
GeoJSON geometry outright — `"Only LineString GeoJSON geometry is supported by this profile"` — a
constraint this spike had to route around when building its GeoJSON fixture. It is unrelated to the
glyph work and was not investigated further; flagged here only because the earlier `0.1.5` research
document did not record it and this respike incidentally discovered it.

## What remains unverified

- **Only the JVM target (`kmp-jvm`) was measured**, not `macosArm64`, Linux, iOS, or Android. Every
  behaviour measured here lives in `commonMain` with no `expect`/`actual` seam this spike's code paths
  touch, so there is good reason to expect it is platform-independent — but that is an inference, not a
  measurement, and the design spec's own caveat ("everything outside macosArm64 … worker counts and
  thread migration are target-specific") still stands untouched by this respike.
- **`0.2.0` was not examined.** This document jumps `0.1.5 → 0.3.0` as the task framed it; if `0.2.0`
  introduced and then `0.3.0` altered something in between, that history is invisible here. The
  provenance check above is against `0.3.0`'s exact published bytes, so this does not weaken the
  `0.3.0` findings themselves.
- **`retryExact`, `TileSubstitutionPolicy`, and label overlap/placement policy nuances** were not
  exercised, matching the original spike's own declared scope (RenG keeps substitution disabled and
  never calls `retryExact`).
- **The per-class `RentilePrivateKey` derivation for `GLYPH_RANGE`** was read from source
  (`sha256Hex(withRedactedAuthenticationQuery(url))`, same convention as every other class) but not
  independently re-derived and byte-compared in the running spike the way the original TileJSON
  derivation was traced from bytecode.
- **Live public-provider styles/glyph endpoints were deliberately not used.** This respike used
  synthetic counting-stub fixtures throughout rather than Rentile's own rolling public-catalog corpus
  (`https://dashboard.lascade.com/travel_animator/v0/maps/`), to keep the measurement offline,
  reproducible, and free of third-party credentials — consistent with "counting stub," not with a live
  network proof. Real-world glyph-provider response quirks (missing ranges, non-BMP fallback, etc.) are
  therefore unexercised here; Rentile's own corpus gate covers that ground separately.
- **`GLYPH_RANGE_UNAVAILABLE`, the diagnostic code the API doc promises for a style with no `glyphs`
  template**, was not directly triggered in this run (the run that hit `acquireLabelCandidates`
  always had a `glyphs` template configured); only `TEXT_ONLY_LAYER_EXCLUDED` was observed, on the
  icon+text symbol layer, which is expected pre-existing behaviour, not new.

## Consequence for RenG's firewall (Task 17)

1. **"Eight basemap classes" is now nine everywhere it is asserted.** ADR 0016's closing paragraph
   ("proved … across all eight basemap resource classes") and the design spec's "Measured
   basemap-engine behaviour" section ("all eight classes exercised") both describe the `0.1.5` count
   and now understate Rentile's actual `ResourceClass` surface. Per the task's instruction, this
   document does not edit either file — flagging the staleness is the deliverable, correcting it is the
   owner's call.
2. **`GLYPH_RANGE` needs an explicit scope decision, not a silent enumeration.** CLAUDE.md still states
   plainly that RenG draws no map text. If Task 17's firewall is built only against
   `prepare`/`prepareBatch`/`render`, it will never route a `GLYPH_RANGE` request, and that is
   consistent with RenG's current scope — but it means a `ResourceClass.BASEMAP_GLYPH_RANGE` (if one is
   added to RenG's own enum) would exist without ever being exercised, unless and until a later cycle
   adopts labels. Adding the class preemptively without adopting `acquireLabelCandidates` would be
   exactly the "class RenG does not enumerate" failure mode this respike was launched to rule out — so
   the honest options are: don't add it yet, or add it and adopt the entry point in the same cycle.
3. **If glyphs are adopted, the firewall's per-class table needs a `GLYPH_RANGE` row** with:
   `accept = "application/x-protobuf"`; write-before-decode-validation (DEM-like, not sprite-like);
   non-terminal digest-mismatch recovery (remove-then-refetch, like the majority of classes, not like
   sprite); and its own `ResourceLimits` ceiling if RenG chooses to expose one.
4. **Every other per-class claim in ADR 0016 — write ordering, private terminal remove, the
   256-tile/concurrency-eight shape, and the sprite-poisoning outlier — is unchanged at `0.3.0`.** This
   respike found one additive fact, not a contradiction of anything already decided.
