# Rentile 0.2.0 → 0.4.0: what an upgrade costs, and what map labels would require

**Date:** 2026-08-21. **Branch read:** `feat/cycle-e-basemap`.
**Question asked:** should RenG move its Rentile pin, and when do labels land?
**Answer, in one line:** upgrade to `0.4.0` now — nothing RenG reproduces changed, two things break and both
are one-line fixes — and keep labels as their own cycle, because the label surface breaks the firewall's
central assumption and is not a tail-end addition to any existing cycle.

This is a research document, not a decision record and not a plan. Nothing here was changed in either
repository; every claim below is either **read** from source at a named commit, **measured** by a command
quoted in place, or explicitly flagged **unverified**.

---

## 0. Provenance

| Input | Value |
|---|---|
| RenG worktree | `/Users/rohittp/Data/Other/RenG`, branch `feat/cycle-e-basemap`, HEAD `e0e932e` ("merge: resolve the DEM validation duplicate the two branches planned for"). Nothing modified. |
| Pin under review | `gradle/libs.versions.toml`: `rentile = "0.2.0"` |
| Rentile checkout | `/Users/rohittp/Data/Other/rentile`, branch `main`, HEAD `005ac14` ("feat: new tile resolution strategy"), clean tree, `VERSION_NAME=0.4.0` |
| Rentile `0.2.0` commit | `2d0a5bf` ("chore: release 0.2.0"), `VERSION_NAME=0.2.0` |
| Rentile `0.3.0` commit | `f865320` ("docs(label): record the glyph-range ceiling rationale, reconcile docs, cut 0.3.0") |
| Published versions (measured) | `curl https://maven.rohittp.com/com/rohittp/rentile/kmp/maven-metadata.xml` → `0.1.4, 0.1.5, 0.2.0, 0.3.0, 0.4.0`; `<release>0.4.0`, `lastUpdated 20260819215645` |
| Six-target availability at `0.4.0` (measured) | `kmp-android`, `kmp-iosarm64`, `kmp-iossimulatorarm64`, `kmp-macosarm64`, `kmp-linuxx64`, `kmp-linuxarm64` POMs all return HTTP `200` anonymously |

The whole `0.2.0 → 0.4.0` range is **20 commits**, of which `0.3.0` is the label release (18 commits) and
`0.4.0` is a **single commit**, `005ac14`.

This document deliberately overlaps with, and does not restate,
`docs/research/2026-08-19-rentile-030-counting-stub-respike.md`, which measured `0.3.0`'s *runtime*
behaviour against a live counting stub. That document's own "what remains unverified" section says
"`0.2.0` was not examined … if `0.2.0` introduced and then `0.3.0` altered something in between, that
history is invisible here." **This document closes exactly that gap**, by source diff rather than by
runtime measurement.

---

## 1. The concrete diff, in the surfaces RenG depends on

### 1.1 The headline: every file RenG reproduces is byte-identical

RenG's byte-for-byte reproduction of Rentile's private URL composition lives in
`kmp/src/commonMain/kotlin/com/rohittp/reng/internal/basemap/BasemapStyleManifest.kt` and
`.../internal/firewall/RentileKeyDerivation.kt`. Every Rentile source file those two port from was
SHA-compared between `2d0a5bf` and `main`:

```
for f in <21 files>; do
  a=$(git show 2d0a5bf:$f | shasum); b=$(git show main:$f | shasum)
  [ "$a" = "$b" ] && echo "SAME $f" || echo "DIFF $f"
done
```

| Rentile file | What RenG reproduces from it | Result |
|---|---|---|
| `internal/ContentIdentity.kt` | `sha256Hex`, `withRedactedAuthenticationQuery` (RenG: `redactAuthenticationQuery`) | **SAME** |
| `internal/mvt/VectorSource.kt` | `sampleFor` (`min(z, maxZoom)`), `tileUrl` (`31/17` template hash, `{-y}` vs TMS) | **SAME** |
| `internal/raster/RasterResource.kt` | same pair for raster/DEM, plus `neighbor` (the 3×3 DEM neighbourhood) | **SAME** |
| `internal/metadata/TileJsonResourceAcquirer.kt` | `resolveHttpReference` — the bespoke non-RFC-3986 join | **SAME** |
| `internal/mvt/VectorSubstitution.kt`, `internal/raster/RasterSubstitution.kt` | (unreachable under `TileSubstitutionPolicy.Disabled`; RenG deliberately emits none of their URLs) | **SAME** |
| `internal/mvt/VectorResourceAcquirer.kt`, `internal/raster/RasterResourceAcquirer.kt`, `internal/geojson/GeoJsonResourceAcquirer.kt` | acquisition/validation ordering the firewall's write gate assumes | **SAME** |
| `Exceptions.kt` | the `RentileErrorCode` set `EngineFailureClassification` exhausts with no `else` | **SAME** |
| `internal/SecretContext.kt`, `internal/TileRequestRetry.kt`, `internal/ResourceWorkCoordinator.kt`, `internal/SingleFlight.kt`, `internal/Observability.kt` | latch/retry/concurrency shape ADR 0016 rests on | **SAME** |
| `internal/style/{StyleFilter,StyleProperty,CssColor,GroundLight}.kt`, `internal/SyntheticPngProbe.kt` | — | **SAME** |

Two more, checked at function granularity because their enclosing files did change:

| Function | File | Result |
|---|---|---|
| `appendSpriteExtension` | `internal/sprite/SpriteResourceAcquirer.kt` | **SAME** (RenG ports it verbatim, doubled-extension bug included) |
| `resolveRequiredSpriteAtlas` / `resolveOptionalSpriteAtlas` | `internal/style/StyleCompiler.kt` | **SAME** |
| `planRasterResources` / `planVectorResources` | `internal/DefaultBasemapRasterizer.kt` | **SAME** |

**Verdict: nothing RenG reproduces changed.** The earlier `ContentIdentity.kt` finding recorded in
`RentileKeyDerivation.kt`'s KDoc still holds, and it now generalises to the entire reproduced surface.
The two comments in `RentileKeyDerivation.kt` claiming this ("verified by diffing Rentile's
`ContentIdentity.kt` directly … byte-identical") are accurate and remain accurate at `0.4.0`.

### 1.2 The full public-ABI delta

`git diff 2d0a5bf..main -- kmp/api/kmp.klib.api` is 50 KB, but it contains **exactly two removed lines**,
both from `ResourceLimits`. Everything else is addition.

**Removals (2):**

| Removed | Replaced by | Effect on RenG |
|---|---|---|
| `ResourceLimits.<init>` 14-arg | 16-arg (two `Long`/`Int` fields **appended**) | **None.** RenG never constructs Rentile's `ResourceLimits` — it has its own `com.rohittp.reng.ResourceLimits`, and `BasemapEngineHost` leaves the engine's at its default. Binary-incompatible, source-compatible; irrelevant here. |
| `ResourceLimits.copy` 14-arg | 16-arg | **None**, same reason. |

**Enum additions (7, no removals):**

```
diff <(git show 2d0a5bf:kmp/api/kmp.klib.api | grep -oE "enum entry [A-Z_]+" | sort) \
     <(git show main:kmp/api/kmp.klib.api     | grep -oE "enum entry [A-Z_]+" | sort)
```

| Enum | New constant | Verdict for RenG |
|---|---|---|
| `ResourceClass` | `GLYPH_RANGE` | **Breaks one test, nothing else.** See §1.5. |
| `ResourceAccessMode` | `CACHE_SUBSTITUTE_THEN_NETWORK` | **Leaves alone.** `BasemapEngineHost.engineAccessModeOf` switches on **RenG's own** `ResourceAccessMode`, a three-value enum, and maps forward. A new engine constant is unreachable from RenG and does not affect exhaustiveness. |
| `DiagnosticCode` | `COMPLEX_SCRIPT_LABEL_EXCLUDED`, `GLYPH_RANGE_UNAVAILABLE`, `LABEL_FEATURE_SKIPPED`, `LINE_PLACEMENT_LABEL_EXCLUDED`, `UNSUPPORTED_TEXT_CONSTRUCT` | **Leaves alone.** All five are `INFO`. RenG reads Rentile's diagnostics nowhere — `EngineFailureClassification`'s KDoc states "its `diagnostics` are never read", and grepping `BasemapEngineHost.kt` confirms every `Diagnostic` symbol it touches is `com.rohittp.reng.internal.*`. Two of the five (`UNSUPPORTED_TEXT_CONSTRUCT`, `LINE_PLACEMENT_LABEL_EXCLUDED`) *are* reachable during `prepare()`, so they will appear in `PreparedStyle.diagnostics` — a list RenG discards. |
| `RentileErrorCode` | **none** | **Important negative.** `EngineFailureClassification.classifyEngineFailure` switches on `RentileErrorCode` in a `when` with no `else`, deliberately so that a new code fails compilation. `diff` over `Exceptions.kt`'s enum body reports no change: the classifier still compiles unchanged. |

**Interface additions (2 abstract members on `BasemapRasterizer`):**

`labelCandidateRequestKey(style, tiles): String` and
`acquireLabelCandidates(style, tiles, resourceAccess = NORMAL): LabelCandidateBatch`.

RenG **does not implement** `BasemapRasterizer` — it calls `Rentile.create(...)` and holds the returned
instance (`BasemapEngineHost.kt:104`). Adding abstract members to an interface breaks *implementers*, and
RenG implements only `ResourceTransport` (`FirewallTransport`) and `RawResourceStore` (`FirewallStore`),
neither of which changed. **Leaves alone.**

**New public types (8):** `LabelBox`, `LabelCandidate`, `LabelCandidateBatch`, `LabelGlyphAtlas`,
`LabelGlyphEntry`, `LabelGlyphQuad`, `LabelIconRef`, `LabelLayerStyle`. Purely additive.

**New ABI-visible Wire codegen (2):** `com.rohittp.rentile.internal.glyph/{Glyph,Glyphs}`, extending
`com.squareup.wire/Message` and referencing `okio/ByteString`. This is **not new in kind** — `0.2.0`
already exposed `com.rohittp.rentile.internal.mvt/{Tile,…}` the same way — and it adds **no new
dependency**: `git diff 2d0a5bf..main -- kmp/build.gradle.kts gradle/libs.versions.toml build.gradle.kts
settings.gradle.kts` is **empty**, and wire/okio were already `implementation`, not `api`, at `0.2.0`. So
nothing new reaches RenG's compile classpath and RenG's repository-policy dependency gate is unaffected.

### 1.3 Acquisition ordering: which classes are fetched when

This is the property RenG's exact-URL preregistration actually depends on, so it is stated separately from
the ABI.

| Phase | `0.2.0` fetches | `0.4.0` fetches | Verdict |
|---|---|---|---|
| `prepare()` (style compile) | style bytes (`STYLE`, or none for `Prefetched`), then the sprite pair (`SPRITE_JSON` + `SPRITE_IMAGE`) when a layer requires or desires one | **identical** | unchanged |
| `prepareBatch()` | `TILE_JSON` (for `url`-form sources), `VECTOR_TILE`, `RASTER_TILE`, `DEM_TILE`, `GEO_JSON` | **identical** | unchanged |
| `render()` | nothing | **identical** | unchanged |
| `acquireLabelCandidates()` | *(did not exist)* | label layers' `VECTOR_TILE` + N × `GLYPH_RANGE` | **new, and RenG does not call it** |

Three specific checks behind that table:

1. `glyphs` is now resolved during style compilation (`StyleCompiler.kt`, new `glyphsTemplate` block) but
   **nothing is acquired there** — the code comment says so and the acquisition site confirms it
   (`DefaultBasemapRasterizer.acquireLabelCandidates` is the only reader of `compiledStyle.glyphsTemplate`).
2. The `labelLayers` set is unchanged: `isAuxiliaryLabelLayer` is untouched, and the new
   `LabelLayerDescriptor` is built and appended **unconditionally**, exactly as before — the new
   `textProgram` is a nullable sibling field that degrades to `null` rather than removing the layer. This
   matters because `labelLayers` feeds `externalMetadataDigests`, which feeds `PreparedStyle.digest`.
3. `prepareBatch`'s body was refactored (the `supervisorScope`/`async` pair moved into `planResources` /
   `resolveResources`) but for `NORMAL`, `CACHE_ONLY` and `RELOAD` the call sequence is
   plan → `validateSubstitutionAllowance` → resolve, exactly as before.

### 1.4 One risk checked and cleared: `RENDERER_SEMANTIC_VERSION` did not move

`PreparedStyle.digest` is `sha256Hex(RENDERER_SEMANTIC_VERSION ‖ policy.id ‖ redacted baseUri ‖
redactedForIdentity(root).canonicalJson() ‖ externalMetadataDigests ‖ spriteAtlas.contentDigest)`. The
formula is byte-identical between `2d0a5bf` and `main`, and `RENDERER_SEMANTIC_VERSION` is
`"rentile-renderer-3"` at **both**. RenG's own rendered-tile identity, `basemapTileKey(style.digest, tile,
outputSize)`, therefore does not change on upgrade.

That is only safe if `0.3.0`/`0.4.0` changed no rendered pixel, and one commit made it look like it might
have — `c71afbe` "fix(label): carry icon-translate and share the marker-offset arithmetic", which replaced
the icon pass's inline arithmetic with a shared `spriteAnchoring()` helper. Reading both:

```
0.2.0: centerX = anchor.x + anchorShift.first + offset[0] * size + translate[0]
0.4.0: centerX = anchor.x + anchoring.centerShiftX
       where centerShiftX = anchorShiftX + offsetX + translateX
             offsetX      = offset[0] * size          (scaled)
             translateX   = translate[0]              (not scaled)
```

**Algebraically identical.** Likewise `evaluateIconImageName`'s extraction into
`String.withExpandedFeatureTokens` preserves both the early return and the empty-string coercion. So the
refactor is output-preserving and *not* bumping the renderer version was correct — a caller's output-tile
cache stays valid. This was the single most plausible silent-alteration hazard in the range and it does
not fire.

### 1.5 What actually breaks on upgrade

Exactly two things. Both are mechanical.

**(a) One Kotlin test assertion.**
`kmp/src/commonTest/.../firewall/EngineFailureClassificationTest.kt:273`,
`mapsEveryEngineResourceClassOntoARenGResourceClass`:

```kotlin
val mapped = RentileResourceClass.entries.associateWith { rengResourceClassOf(it) }
assertEquals(
    emptyList(),
    mapped.filterValues { it == null }.keys.toList(),
    "every Rentile 0.2.0 resource class has a RenG counterpart; an unmapped one must fail closed",
)
```

At `0.4.0` this list is `[GLYPH_RANGE]` and the assertion **fails**. Note the message already says what to
do about it — "an unmapped one must fail closed" — and `RentileKeyDerivation.kt` already documents
`GLYPH_RANGE`'s intended `null` in two places. So the *behaviour* is already the intended one; only the
test's totality claim is stale. The sibling assertion on line 281 (`entries.size == values.toSet().size`)
passes by accident, because `null` occupies the ninth slot in the value set — worth tightening in the same
edit rather than leaving as a coincidence.

Nothing else in RenG iterates `RentileResourceClass.entries`: `rengResourceClassOf` iterates RenG's own
eleven-value enum, and `BasemapEngineHostTest:496`'s `assertEquals(7, engineKeyed.size, "Rentile 0.2.0
fetches and keys exactly seven basemap classes")` is derived from RenG's enum too and is unaffected (its
*message* is stale but its arithmetic is not).

**(b) The repository-policy gate, in three coupled places.**
`HANDOFF.md:286` already records this: "A version now lives in three coupled places, discovered while
bumping to `0.2.0` … All three must move together in one commit or `check_repository_policy.py` fails
closed."

| Place | Change |
|---|---|
| `gradle/libs.versions.toml` | `rentile = "0.2.0"` → `"0.4.0"` |
| `tools/check_repository_policy.py`, `_EXPECTED_PRODUCTION_BUILD_FINGERPRINTS["gradle/libs.versions.toml"]` | add the new whole-file SHA-256 (two hashes are currently listed) |
| `tools/check_repository_policy.py`, `_dependency_name_policy_token`'s `base_versions["rentile"]` | `"0.2.0"` → `"0.4.0"` |

Plus `tools/tests/test_check_repository_policy.py:271` and `:1249`, which embed `rentile = "0.2.0"` in a
fixture catalog and a mutation test.

**Nothing else.** No production Kotlin file fails to compile, no `when` loses exhaustiveness, no
implemented interface gains a member, no dependency is added.

### 1.6 Prose that goes stale on upgrade (no behaviour, but reviewers will trip on it)

These are comments and doc lines that name `0.2.0` as the pinned version. They are *not* wrong today, and
each states a fact that this document confirms still holds — but leaving them unedited after a bump makes
the next reader re-derive the whole comparison:

`BasemapStyleManifest.kt:22, :32, :70`; `RentileKeyDerivation.kt:14, :22–23, :152`;
`BasemapEngineHost.kt:342`; `EngineFailureClassification.kt:45`; `OperationRegistry.kt:280, :471, :644`;
`BasemapStyleManifestTest.kt:18, :166`; `FirewallTest.kt:196`;
`EngineFailureClassificationTest.kt:279`; `BasemapEngineHostTest.kt:496`;
`docs/superpowers/plans/2026-08-20-cycle-e-basemap.md:228–231`; `HANDOFF.md:279`.

Every one of them is a *sentence*, not a constant. Rewriting `0.2.0` to `0.4.0` in them is safe **because
of §1.1**, and would be unsafe without it.

---

## 2. What "new tile resolution strategy" actually does

`005ac14`, the single commit that is `0.4.0`, touches 8 files: two ADRs, `gradle.properties`, the two ABI
dumps, `Api.kt` (+14 lines), `DefaultBasemapRasterizer.kt` (+163/−14) and its test (+235).

**It adds one opt-in `ResourceAccessMode` constant and nothing else.**

`ResourceAccessMode.CACHE_SUBSTITUTE_THEN_NETWORK` — recorded as Rentile ADR 0027, "Prefer cached tile
substitution before network", with ADR 0014 amended to name it. Its implementation
(`prepareCacheSubstituteThenNetwork`) does, in order:

1. plan every tile with `CACHE_ONLY`;
2. if a cache failure is *not* substitution-eligible, throw immediately (no network);
3. resolve exact cache hits under `CACHE_ONLY`;
4. for each missed tile, in **caller tile order**, try cache-only child/ancestor synthesis against a
   one-tile `TileSubstitutionPolicy`, consuming the caller's substitution allowance;
5. whatever still misses goes through the ordinary `NORMAL` path with the remaining allowance;
6. merge.

**Answers to the specific questions asked:**

| Question | Answer |
|---|---|
| Does it change composed URLs? | **No.** `VectorSource.kt` and `RasterResource.kt` — the only two places a tile URL is composed — are byte-identical to `0.2.0`. `005ac14` does not touch them. |
| Does it change zoom clamping? | **No.** `sampleFor`'s `min(z, maxZoom)` is in those same untouched files. |
| Does it change substitution behaviour? | Only *when* substitution is attempted, never *how*. It reuses the existing `validateSubstitutionAllowance` / `substituteRaster` / `substituteVector` machinery unmodified; ADR 0027 is explicit that a cached substitute "carries the same provenance and content identity as every other substitute". |
| Does it change which tiles are requested for a viewport? | **No** for RenG. The mode changes which *resources* back a requested tile, never the tile set — and it is unreachable without opting in. |
| Is it reachable from RenG? | **No, twice over.** ADR 0027: "For operations without tile substitution the mode is equivalent to `NORMAL`", and `BasemapEngineHost` passes `TileSubstitutionPolicy.Disabled` at every call site — deliberately, per its own KDoc: "a future Rentile release must not be able to turn one on for us by changing a default." Separately, `engineAccessModeOf` maps only RenG's three modes, so the constant can never be selected. |

The one incidental consequence worth recording: `BasemapStyleManifest.kt`'s "second version-and-
configuration-pinned assumption" — that `RasterSample.immediateChildren` / `RasterSample.ancestor` compose
URLs RenG never emits, and are unreachable only because substitution is disabled — is now load-bearing
against **one more** entry point than before. The assumption itself is unchanged and still holds. But if
RenG ever enables substitution, it must now extend its route derivation for **both** `NORMAL` failure-time
substitution and this new cache-first substitution, in the same change.

---

## 3. The label surface

### 3.1 The entry point

```kotlin
public fun labelCandidateRequestKey(style: PreparedStyle, tiles: List<TileId>): String
public suspend fun acquireLabelCandidates(
    style: PreparedStyle,
    tiles: List<TileId>,
    resourceAccess: ResourceAccessMode = ResourceAccessMode.NORMAL,
): LabelCandidateBatch
```

`labelCandidateRequestKey` is pure, pre-network, and folds `"label-candidates-1"` (a semantics version),
`compiledStyle.digest`, and the sorted de-duplicated tile list. It deliberately does **not** canonicalise
`x`, so antimeridian world copies key apart. It omits the glyph closure, because which glyph ranges a tile
set needs is unknowable before the tiles are decoded — a fact that turns out to be the crux for RenG (§3.4).

`acquireLabelCandidates` does, in order:

1. read `compiledStyle.glyphsTemplate`; if `null`, record `GLYPH_RANGE_UNAVAILABLE` and return an **empty
   batch** — a style with no `glyphs` key is legitimate, not an error;
2. `source.sampleFor(tile)` over every distinct label-layer vector source × requested tile, then
   `vectorAcquirer.acquire(...)` for each — **the same `sampleFor` and the same acquirer `prepareBatch`
   uses**, so these are the ordinary `VECTOR_TILE` URLs;
3. `LabelCandidateAssembler.plan(...)`: decode MVT, evaluate the compiled text program per feature,
   collect `GlyphRangeRequest(fontStack, rangeStart)` per codepoint;
4. acquire every required range through `GlyphResourceAcquirer`;
5. `plan.assemble(...)`: pack the ranges into one atlas, lay every label out, emit the batch.

Tile substitution is deliberately not applied. Acquisition is **all-or-error**.

### 3.2 `GLYPH_RANGE` — what it is

A ninth `ResourceClass`. One request is one 256-codepoint block of one font stack, as protobuf
(`glyphs.proto`, fontnik/Mapbox `Glyphs`/`Fontstack`/`Glyph` shape).

| Property | Value |
|---|---|
| URL | `template.replace("{fontstack}", pct-encoded).replace("{range}", "$start-${start+255}")` |
| Font-stack encoding | RFC 3986 unreserved **plus `,`** preserved; everything else percent-encoded over UTF-8 bytes (because `text-font` can be data-driven, so an MVT feature property reaches the URL) |
| Template origin | style root `glyphs`, resolved through the same `resolveHttpReference` for a relative reference, then `secretContext.protectUrl(...)` — it carries the provider credential |
| `accept` | `application/x-protobuf` (the third non-null `accept`, after sprite JSON/PNG) |
| Byte limit | `ResourceLimits.maxGlyphRangeBytes`, default 1 MiB |
| Batch limit | `ResourceLimits.maxGlyphRangesPerBatch`, default 64 |
| Store key | `sha256Hex(withRedactedAuthenticationQuery(url))` + `GLYPH_RANGE`, same convention as every other class |
| Write ordering | written after the generic byte check, **before** `GlyphRangeDecoder.decode` — DEM-like, not TileJSON-like |
| Corruption | non-terminal: read → digest mismatch → `remove` → refetch → write |
| Coverage ceiling | BMP only (`servesCodepoint(cp) = cp <= 0xFFFF`); astral codepoints are dropped from the label, not requested |

All of this matches the `0.3.0` respike's measurements and is unchanged at `0.4.0`.

### 3.3 What a caller gets, and what it must do with it

`LabelCandidateBatch(candidates, layerStyles, atlas, contentKey, diagnostics)`.

`LabelGlyphAtlas` is `pngBytes` + `width`/`height` + `contentKey` + `entries`. The PNG is **RGBA8
unpremultiplied**, RGB forced to opaque white, **alpha = the signed distance field** (`GlyphAtlasPacker`'s
own comment: "matching the convention SDF sprites already use … so a consumer has one sampling rule for
both atlases"). RenG's `decodePng` accepts colour type 6 and yields canonical RGBA8, so **no new decoder is
needed**.

`LabelCandidate` carries a **geographic anchor** (`longitude`, `latitude`) and everything else in
**label-local** coordinates: `glyphs: List<LabelGlyphQuad>` (cell corners, with the 3-px SDF buffer already
compensated and `text-anchor`/`text-offset` already applied), `boundingBox: LabelBox` (union of quad cells
expanded by `text-padding`), plus `allowOverlap`, `ignorePlacement`, `padding`, `sortKey`, `opacity`,
`haloWidth`, `haloBlur`, `translateX/Y` (`text-translate`, pixel, **not** size-scaled, applied to the
*projected* anchor), `layerStyleIndex` and an optional `icon: LabelIconRef`.

`LabelLayerStyle` carries `layerId`, `zoom`, `priority` (style layer order, ascending — larger wins a
placement conflict), `color` and `haloColor` as packed `0xAARRGGBB`.

**The division of labour is exactly the one RenG asked for** in
`docs/research/2026-08-18-rentile-label-primitives-request.md`: Rentile owns *what the style says* (decode,
filters, expressions, text formatting, font stack, size, anchor, offset, justify, line breaking, glyph
metrics and layout); RenG owns *where it lands* (projection, collision, priority, occlusion, drawing).
Rentile did more than the request sketched — it ships **laid-out quads**, not just strings — which removes
text shaping and line breaking from RenG's side entirely.

### 3.4 What RenG would have to build

Sorted by whether it is mechanical or genuinely open.

**Mechanical, reusing what already exists:**

| Work | Reuses |
|---|---|
| Decode the atlas PNG | `internal/image/decodePng` (already hardened by five adversarial passes) |
| Upload it once per `LabelGlyphAtlas.contentKey` | `internal/gl/GlTextureUpload`, `ResidentCache` leases, the tile-residency budget |
| Draw quads in the screen regime | `internal/gl/StickerPipeline`'s `screenAnchored` path already sorts by composite z and draws depth-test-off after the map regime, exactly per ADR 0024 |
| Project the anchor | `internal/projection/{MercatorProjection, CameraMatrices}` |
| Cache-key the batch across frames | `labelCandidateRequestKey` before the call; `LabelCandidateBatch.contentKey` after |

**New, and each a real piece of work:**

1. **An SDF text shader pair.** `StickerPipeline`'s fragment shader is `texture(...)` straight out. SDF
   text needs a `smoothstep` around the 0.5 iso-line, a second band for the halo, `haloWidth`/`haloBlur`
   as uniforms, and `color`/`haloColor` unpacked from `0xAARRGGBB`. This is where `opacity` multiplies in.
   It also needs a scale-aware antialias width, since `LabelGlyphQuad.scale` (= `text-size / 24`) varies
   per label. This is a genuinely new pipeline, not a `StickerPipeline` parameterisation.
2. **Screen-space collision and priority across the whole viewport.** This is the reason labels exist as
   RenG's job at all (per RenG's own request document: "two labels in different tiles can overlap on
   screen while never overlapping in tile space, and whether they collide depends on the camera"). Inputs
   are all present — `boundingBox`, `padding`, `allowOverlap`, `ignorePlacement`, `sortKey`,
   `LabelLayerStyle.priority` — but the *policy* (sort order across sortKey vs. priority, whether
   `ignorePlacement` labels still block others, hysteresis between frames so labels do not flicker as the
   camera moves) is undesigned and is ADR-worthy. Note that RenG's purity contract makes frame-to-frame
   hysteresis awkward: a `FramePlan` is a complete definition of on-screen state, so any placement memory
   is internal reuse, not caller-visible state — the same category as texture caching, but with *visible*
   consequences.
3. **A per-label screen quad batch.** Currently `drawStickers` issues one draw per sticker. A dense
   viewport is thousands of glyph quads; one draw call per glyph is not viable and one per label is
   marginal. A batched vertex buffer keyed on the atlas texture is the natural shape, and it is new.
4. **Occlusion against the 3D scene.** Labels are screen-regime (depth-test off, per ADR 0024), but a
   label anchored to a point behind a hill should not draw. That needs a depth *read* at the projected
   anchor, or an equivalent, and ADR 0024 does not cover it — the screen regime currently has "no depth
   test against the map regime at all".
5. **Resolving `LabelIconRef.imageName` to pixels.** **Rentile exposes no public sprite atlas** — grepping
   `Api.kt` for a public sprite accessor returns nothing; `LabelIconRef` names an image in an atlas the
   consumer cannot obtain through Rentile. RenG *can* resolve it, because RenG already fetches, jointly
   validates, and commits the sprite pair itself (`ResourceClass.BASEMAP_SPRITE_JSON` /
   `BASEMAP_SPRITE_IMAGE`, and `OperationRegistry`'s joint pair check already parses every entry's
   `x`/`y`/`width`/`height`/`pixelRatio`). So this is feasible without a Rentile change — but it means
   RenG must *retain* the parsed sprite manifest, which today it parses only to gate a write. Worth
   confirming with Rentile's owner whether a public accessor is preferable to RenG re-deriving it.
6. **Which draw regime, precisely.** Labels are screen-space upright and constant-size, so they belong to
   the screen regime — but their `z` is not a caller-supplied z-index, it is derived from collision
   priority and (item 4) scene depth. ADR 0024 fixes map-then-screen ordering; it does not say where a
   third, engine-derived stack sits relative to consumer-supplied screen-anchored stickers. That is a real
   open question and, on ADR 0024's own reasoning about silent-failure modes, deserves an ADR.

**And one architectural blocker, which is the reason this cannot be a tail-end task:**

7. **The firewall cannot preregister glyph routes.** ADR 0016's firewall works by preregistering the
   **exact** `(accessMode, url, resourceClass, maxBytes)` tuples an invocation may need, *before* the
   engine runs, and failing closed on anything else (`OperationRegistry.executeTransport` throws
   `ambiguousRouteFailure()` with no fallback branch). Glyph-range URLs are **not knowable before the
   call**: the range set is `{(fontStack, codepoint/256*256)}` over the decoded MVT text, and
   `compileFontProperty` explicitly admits a **data-driven** `text-font` (`["get","fontProperty"]` is
   handled as an expression, not a literal stack), so even the font-stack half is unbounded from the
   outside. Rentile's own `labelCandidateRequestKey` documents the same fact: it "deliberately omits …
   the glyph closure: which Glyph Ranges a tile set needs is not knowable until its features are
   decoded".

   The available shapes, none free:
   - **Pattern routes.** Admit `GLYPH_RANGE` requests matching the template RenG itself resolved from the
     style (RenG already has `resolveHttpReference` and can read `glyphs` in `BasemapStyleManifest`), with
     `{range}` constrained to the 256 legal BMP blocks and `{fontstack}` matched as a wildcard segment.
     This weakens exact-string matching to a template match for exactly one class — a real dilution of
     ADR 0016's central guarantee, and it needs its own ADR rather than a code change.
   - **Two-phase acquisition.** Call `acquireLabelCandidates` once under `CACHE_ONLY` to learn the range
     set, then preregister and call again. This does not work: `CACHE_ONLY` throws on the first uncached
     range rather than reporting the closure, and it would double the vector-tile work.
   - **A Rentile change.** Ask for a `labelGlyphClosure(style, tiles)`-shaped call, or for
     `acquireLabelCandidates` to be splittable into "decode and report required ranges" then "acquire and
     assemble". This is the cleanest fit for RenG's firewall and is the natural successor to the
     `2026-08-18` label-primitives request, which Rentile's owner took.

   Whichever is chosen, it is a **design decision recorded in an ADR before any code**, and it is the
   single largest reason labels are a cycle and not a task.

### 3.5 Free constraints worth knowing before designing

- `ScriptSupport` excludes 23 Unicode ranges outright (Hebrew, Arabic and its supplements, Syriac,
  Thaana, NKo/Samaritan/Mandaic, Devanagari→Sinhala, Thai, Lao, Tibetan, Myanmar, Khmer, Mongolian and
  the Brahmic/SE-Asian abugidas, plus presentation forms and Adlam/Hanifi Rohingya). Rentile lays out by
  accumulating advances and never shapes, so these are reported as `COMPLEX_SCRIPT_LABEL_EXCLUDED` and
  never reach RenG. **RenG inherits that limitation and cannot fix it downstream** — it receives no glyph
  quads for those labels at all.
- `symbol-placement: line` and `line-center` produce no candidates (`LINE_PLACEMENT_LABEL_EXCLUDED`), so
  no label follows a road. Point anchors only.
- `text-overlap: cooperative` collapses to `allowOverlap = false` and is **not recoverable** from the
  public field.
- `text-translate-anchor: viewport` is excluded (`UNSUPPORTED_TEXT_CONSTRUCT`); everything RenG receives
  is map-anchored, so `translateX/Y` rotates with the map under bearing.
- Acquisition is **all-or-error**: one un-fetchable range fails the whole batch. With `maxGlyphRangesPerBatch
  = 64` and a measured worst case of 15 ranges for a single dense-CJK tile at Tokyo z14, a wide
  multi-font-stack viewport is the case that will find that ceiling first.
- Astral codepoints (emoji in OSM `name` tags, CJK Ext-B) are dropped per-character, silently by design.

---

## 4. The cycle-order discrepancy, precisely located

Three documents. Two of them agree with each other and omit labels entirely; the third promises labels
next.

**(a) `docs/decomposition.md`, "## Order" (line 15).** The chain diagram, lines 24–27:

```
A skeleton ──► B core ──┬──► C resources ──┐
                        └──► D gl foundation┘──► F-1 (MVP) ──► release ──► E-basemap ──► release
                                                            ──► F-2 models ──► release ──► E-terrain
                                                            ──► G globe ──► H platforms ──► I harness ──► J corpus
```

and the table at lines 33–47, whose rows after `E-basemap` are `F-2`, `E-terrain`, `G`, `H`, `I`, `J`.
**The string "label" does not occur anywhere in `docs/decomposition.md`** (`grep -n -i label` returns
nothing). Line 31 asserts "Everything from F-1 onward is a chain" — i.e. the list is claimed complete.

**(b) `HANDOFF.md`, lines 455–456**, "## Cycles E through J":

> Execution order is now: C → D → **F-1 → MVP release** → E-basemap → release → F-2 → release → E-terrain
> → G → H → I → J.

Also no labels cycle. `HANDOFF.md` mentions labels only to say RenG does not do them (lines 203–207,
268, 282).

**(c) `docs/superpowers/specs/2026-08-20-cycle-e-basemap-design.md`**, in two places:

- line 9: "Terrain, map labels, models and the globe projection are all out of scope and **ship later**."
- lines 31–33, heading "### Map labels are deferred, and this costs nothing visually":
  "**Labels ship in the cycle after this one.**"

**The discrepancy is exact.** Under (a) and (b), "the cycle after this one" is **F-2, models with textures
and animation** — a cycle whose own description in both documents says nothing about labels. Under (c),
either F-2 is wrong about its own contents, or an unnumbered labels cycle exists that neither planning
document lists.

Note also that (c) is the *newest* of the three (drafted 2026-08-20; the reorder in (a) and (b) is dated
2026-08-19), and that it is marked "awaiting owner approval" while (a) and (b) are the standing record.

**A second, smaller erratum in the same spec.** Line 45 says Rentile 0.3.0 ships `acquireLabelCandidates`
"with `LabelPrimitive` and `LabelGlyphAtlas`". `LabelGlyphAtlas` is real; **`LabelPrimitive` does not exist
in Rentile at any version** — `grep -rn LabelPrimitive` over the Rentile checkout returns nothing. The name
comes from RenG's own request sketch
(`docs/research/2026-08-18-rentile-label-primitives-request.md:107`); Rentile shipped `LabelCandidate` in
a `LabelCandidateBatch`. Worth correcting wherever the spec is next revised, because the two names differ
in more than spelling: `LabelCandidate` carries laid-out quads, which the request's `LabelPrimitive` sketch
did not.

**What each reading costs.** This is for the owner to settle; both are stated, neither is chosen.

| Reading | Cost |
|---|---|
| **The spec is right — labels are the next cycle, before F-2** | Delays models, which `HANDOFF.md:492` says have "consumers waiting" (the same reason terrain was pushed behind F-2). Labels are also the cycle with the largest *undesigned* surface of anything remaining: §3.4 lists an ADR for the firewall's glyph routing, an ADR for the collision/priority policy, and an open question on regime ordering. Slotting the least-designed cycle immediately after E-basemap is the option most likely to slip. It does have one real advantage: it lands while the basemap firewall is freshly in hand, and it makes the pin upgrade a prerequisite with a purpose rather than housekeeping. |
| **The decomposition is right — labels are unscheduled** | The spec's sentence is then a promise nothing keeps, and it is the kind that decays quietly: a reader in three cycles' time reads "labels ship in the cycle after this one", looks at F-2, and finds no labels and no record of the change. The fix is cheap now (one sentence in the spec, one row in the decomposition table) and expensive later. It also leaves a real product gap — a map with no place names — unscheduled and un-owned, which is exactly the failure mode `HANDOFF.md` records for `FramePlan` serialization ("an unowned prerequisite … nothing has settled it"). |

A third possibility the documents allow but nobody has written down: labels are a **split**, the way E and
F were split. The glyph-atlas upload and SDF text draw are close in kind to F-1's sticker work and could
ride with a rendering cycle; the firewall's glyph routing is close in kind to E-basemap's firewall work.
Splitting would put the two ADRs in different cycles, which may be a feature or a hazard depending on
whether they turn out to be independent. This is raised as an option, not a recommendation.

---

## 5. Recommendation on the pin

**Upgrade to `0.4.0` now, in its own commit, before the labels question is settled.**

### Why now

1. **The re-verification is done and it came back clean.** The stated cost of an upgrade — re-proving a
   byte-for-byte reproduction — is the work in §1.1, and it has now been performed at file granularity
   over 21 files plus 6 named functions. Every one is byte-identical. That result is perishable: it is
   true of `0.2.0 → 0.4.0` and will have to be redone for whatever range follows. Spending it is better
   than banking it.
2. **The break surface is two items, both mechanical.** One test assertion whose own failure message
   already prescribes the fix, and three coupled version literals `HANDOFF.md:286` already documents as
   moving together. No production Kotlin file changes.
3. **The existing test suite re-proves the reproduction at runtime, for free.** RenG's three native tests
   (`RendererBasemapTileTest`, `RendererBasemapStyleRenderTest`,
   `internal/firewall/BasemapEngineRenderTest`) drive a **real** Rentile engine through `prepare` →
   `prepareBatch` → `render` behind the firewall and assert exact composed URL strings — 43 `@Test`
   methods over 128 `https://`-bearing lines in `BasemapStyleManifestTest` alone, plus
   `BasemapEngineHostTest.requestsExactlyTheUrlItPreregistered`.
   A divergence in URL composition presents as `AMBIGUOUS_RESOURCE_ROUTE` on every tile, which these
   suites catch loudly on `macosArm64Test` and `linuxX64Test`. So the upgrade's verification is a
   *test run*, not a re-audit — provided it is done while the basemap work is fresh.
4. **Doing it later is strictly more expensive.** Deferring to the labels cycle bundles a version bump
   with the one feature whose firewall story is unsolved (§3.4 item 7). If a route ever fails closed in
   that cycle, the first question will be "is it the new version or the new feature?" — and the answer
   will cost a bisect that this upgrade, done alone, makes unnecessary. `HANDOFF.md:280` says bumping
   "is a separate decision that needs this respike's evidence, not a reflex". This document is that
   evidence, and it points the same way.
5. **`0.4.0` is published and complete.** All six targets RenG needs resolve anonymously (measured, §0).

### What upgrading does *not* buy, stated plainly

Nothing functional. RenG calls no new entry point, gains no capability, and renders identically —
`RENDERER_SEMANTIC_VERSION` is unchanged and §1.4 shows the icon pass is algebraically unchanged, so not
one pixel moves. The value is entirely in **collapsing the gap** between the pinned version and the version
the reproduction has actually been verified against, so that the next question about Rentile is a question
about one commit rather than about twenty.

### The one argument against, and why it does not carry

*"Do not touch a working byte-for-byte reproduction while E-basemap is in review."* This has real weight —
E-basemap is implemented and awaiting integration review, and a dependency bump mid-review adds a variable.
The mitigation is sequencing, not deferral: **land the upgrade as a separate commit after E-basemap's
review closes and before the next cycle opens**, with only the four files of §1.5 touched and the full
`macosArm64Test` + `linuxX64Test` suites green. If review is expected to run long, the upgrade waits for
review — not for the labels cycle.

### Explicit non-recommendations

- **Do not** enumerate `GLYPH_RANGE` in `engineKeyedResourceClassOf` as part of the bump.
  `RentileKeyDerivation.kt:40–50` and ADR 0016's erratum both say why: enumerating a class without
  adopting the entry point that reaches it is precisely the failure the `0.3.0` respike was run to rule
  out. Fix the test's totality claim; leave the mapping's `null` exactly where it is.
- **Do not** adopt `CACHE_SUBSTITUTE_THEN_NETWORK`. RenG's contract forbids fallbacks
  (`CLAUDE.md`: "RenG performs no repeated consumer exchanges, retries, repairs, or fallbacks"), and
  `BasemapEngineHost` passes `TileSubstitutionPolicy.Disabled` on purpose.
- **Do not** bundle any label work with the bump.

---

## 6. What this document did not verify

- **No build was run and no test was executed.** The break analysis in §1.5 is by source reading. The
  claim "no production Kotlin file fails to compile" rests on: no `RentileErrorCode` change (diffed), no
  removed or renamed public symbol other than the two `ResourceLimits` overloads (ABI-diffed), no
  implemented interface gaining a member (grepped), and no `when` over a Rentile enum in production code
  (grepped — the only such `when` is over RenG's own `ResourceAccessMode`). It is a strong inference, not
  a green build.
- **No `0.4.0` runtime measurement.** The `0.3.0` counting-stub respike measured behaviour; this document
  measured *source identity* from `0.2.0` forward. The two together cover the range, but nothing here
  re-ran a stub against `0.4.0`.
- **`acquireLabelTiles`, `terrainSourceDescriptor`, `acquireTerrainTiles`, `retryExact`, `outputRequestKey`**
  were checked only for ABI change (none). Their behaviour was not re-read.
- **Rentile's test suite was not read**, beyond noting that `RentileRuntimeTest.kt` grew by 1,656 lines
  and `005ac14` added 235 of them.
- **The label collision/priority policy is not designed here**, only scoped. Nothing in §3.4 items 2, 4
  and 6 is more than a statement of what is open.
- **The three-way split option in §4** is raised without analysis of whether the two ADRs it separates are
  actually independent.
