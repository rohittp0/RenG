# Cycle E (basemap half) — drawing the Rentile ground

**Status:** drafted 2026-08-20 from a grilling session; awaiting owner approval.

This cycle draws the map. It decodes Rentile's rendered tiles, uploads them, and draws them as the ground
beneath everything Cycle F-1 already draws. It also absorbs the five Cycle C tasks that turned out to be
basemap work when the cycles were reordered for the MVP.

Terrain, map labels, models and the globe projection are all out of scope and ship later.

## What ships

The Rentile firewall and its production key resolver; engine failure classification; the basemap engine host
owning one rasterizer per renderer; sprite-pair and style commits; tile decode, upload and draw; and bounded
GPU texture residency.

**Five Cycle C task plans are inherited rather than rewritten** — tasks 14 (sprite pair and basemap style
commits), 16 (the production private-key resolver), 17 (the firewall adapters), 18 (engine failure
classification) and 19 (rasterizer lifetime, style compilation, rendered-tile identity). They were written
against Cycle C's context and must be preflight-scanned before execution: Cycle C's plan carried defects
that execution caught, including a self-contradiction between two of its own sections and a latch-replay
test whose fixture structurally could not exercise replay.

## Public API additions

One field on `ResourceLimits`: a **byte budget for resident GPU texture memory**. Everything else this cycle
adds is `internal`.

## Decisions

### Map labels are deferred, and this costs nothing visually

Labels ship in the cycle after this one. That is not a compromise, because `CONTEXT.md` already specifies
that a basemap tile carries no map text at all: "the basemap engine excludes text-only layers and strips
text components rather than drawing them, and RenG does not draw them either, because text baked into a
ground texture is perspective-distorted under a pitched **Camera**."

So the map draws label-free by existing design. Deferring labels means the map has no text — never distorted
text, which is the failure this rule exists to prevent.

**Two errata are owed on `CONTEXT.md`**, both pre-existing and neither to be fixed by rewriting in place:

- That same passage ends "and neither exists yet", referring to engine-side label primitives and RenG-side
  screen placement. The engine half **now exists** — Rentile 0.3.0 ships `acquireLabelCandidates` with
  `LabelPrimitive` and `LabelGlyphAtlas`, which RenG itself requested.
- ADR 0016 and the Cycle C design spec both say "eight basemap classes". Rentile 0.3.0 made it **nine**:
  `GLYPH_RANGE`, reachable only through `acquireLabelCandidates`, carrying `accept = application/x-protobuf`,
  writing before decode validation, and recovering from corruption by remove-then-refetch rather than being
  terminal. Measured and recorded in `docs/research/2026-08-19-rentile-030-counting-stub-respike.md`.

`GLYPH_RANGE` stays **out of this cycle's firewall**, because RenG still does not call the entry point that
reaches it. The respike's own warning binds here: enumerating the class without adopting the entry point, or
the reverse, recreates the exact failure the respike exists to rule out. The firewall fails closed on an
unrecognised class rather than listing one it cannot handle.

### Tiles draw beneath everything map-anchored

`CONTEXT.md` defines a basemap tile as "drawn as the ground beneath a frame", and ADR 0024 fixes the regime
order: the map regime draws first, depth-tested, then the screen regime composites on top as one stack. The
ground therefore draws first within the map regime. No new decision was required.

### The Tile Budget is already settled and must not be softened

`CONTEXT.md` fixes it: the maximum unwrapped tile instances in one prepared frame at its selected LOD,
defaulting to 512, configurable from 1 through 4096, which "fails preparation before acquisition when
exceeded; RenG never drops required tiles."

That is a per-frame **acquisition** limit and is unrelated to residency below. Preparation failing is the
specified behaviour; drawing a partial map is not an available fallback.

### GPU texture residency is bounded by bytes, and lives on `GlObjectRegistry`

Cycle B's lease machinery answers **what must stay resident** — a tile leased by a live `PreparedFrame`
cannot be evicted. It does not answer **what may stay**, and that gap is this cycle's to close. A tile with
zero leases is *evictable*, not evicted.

Unleased tiles remain resident up to an explicit **byte budget**, evicted least-recently-used beyond it.
Bytes rather than a count, because memory is what actually runs out and a count means something different at
every tile size and on every device. The budget is **not** derived from the Tile Budget: 512 tiles at 512²
RGBA is roughly a gigabyte, which is unremarkable on a desktop and fatal on a phone, and a derived multiple
would hide that.

Evicting on last lease release was rejected — panning moves tiles out of view and back constantly, and each
round trip would mean a full re-decode and re-upload. Cycle F-1 spent a whole task establishing that a
per-frame `genTextures` is unacceptable; trading that cliff for another is no improvement.

**Residency lives on `GlObjectRegistry`, not on `ResidentCache`.** After this cycle two caches share a
`ResourceKey` with different lifetimes: `ResidentCache` holds decoded CPU content and survives context loss
intact per ADRs 0007 and 0015, while GL handles do not. `GlObjectRegistry` already owns GL handles and
already handles context loss correctly through `forgetEverything()`.

Giving `ResidentCache` a GPU dimension would mean modelling two lifetimes in one structure, and threading a
second lifetime through `forgetWithoutDeleting()` is exactly where the cross-dialect invalidation guarantee
would most likely break — a reviewer proved its three call sites exhaustive against the state machine, and
that proof should not be casually re-opened.

The consequence is the right one: a lost context costs a **re-upload**, not a re-fetch, because the decoded
image is still leased in `ResidentCache`.

### Basemap is verified by analytical readback, departing from Cycle F-1

Cycle F-1 defers all pixel verification to Cycle J by owner decision. **This cycle does not**, and the
reason is that the two cycles fail differently.

F-1's failure modes are loud — a misplaced sticker, a wrong colour, nothing on screen. Basemap's are quiet
and plausible. Mercator selection "traces physical output-pixel-centre rays, excludes non-downward
sky/horizon samples, and selects every closed tile cell intersecting the conservative closed finite ground
footprint", with inclusive edges and world-copy deduplication applied only after unwrapped instances are
determined. An off-by-one there yields a map that looks correct except for one missing tile at one edge under
one camera angle; a wrong LOD yields a map that is merely blurrier than it should be; a dedup error appears
only when panning across the antimeridian.

None of those announce themselves, and **none is catchable by call-log assertion** — every draw call looks
right. Tile selection is also the most intricate pure computation in the project, which is why Cycle B's
spikes put 2.6 million ray samples through it.

So: draw a known camera over known tiles, read back specific pixels, and assert relationships — this tile's
texture appears in this screen region, the antimeridian seam is continuous, a pitched camera still covers the
near ground. Sample well inside solid regions rather than near edges where filtering legitimately differs,
and assert relationships where absolute values would be fragile.

Golden images remain Cycle J's, including the renderer-string keying the handoff requires — a hosted macOS
runner renders through a software renderer while a developer's machine renders through Metal, so keying a
baseline by target would fail on the first run on new hardware.

## Carried in from Cycle F-1

Whatever remains open from F-1 Task 9b — texture lifetime, the `PreparedFrame` snapshot point, sticker quad
sizing — must be closed before this cycle builds on it. This cycle's residency work extends the same
`GlObjectRegistry` that 9b wires, so starting before 9b lands would mean building against a moving base.
That is the mistake this project has already paid for five times.
