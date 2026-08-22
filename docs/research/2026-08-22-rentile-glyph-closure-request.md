# Handoff: report the glyph closure before fetching it

**For:** Rentile.
**From:** RenG, which consumes Rentile through a containment boundary that this API is needed to keep intact.
**Status:** request, to argue with.
**Precedent:** `docs/research/2026-08-18-rentile-label-primitives-request.md`, which asked for label
primitives and got back more than was asked for — `LabelCandidate` carries laid-out glyph quads, so
text shaping and line breaking never became RenG's problem. This request is smaller.

## The one-sentence ask

Let a caller learn **which `(fontStack, rangeStart)` pairs a label acquisition will need** before that
acquisition fetches them.

## Why this exists

RenG draws 3D content over Rentile basemaps. It performs no network I/O and owns no cache: the
consumer injects a transport and a store, and RenG proxies them down to Rentile. ADR 0016 governs that
proxy as a **firewall** — for every resource the engine may fetch, RenG preregisters an **exact URL**
before the engine runs, and any request that does not match a preregistered route fails closed without
reaching the consumer's adapter at all.

That works for every basemap resource today because every URL is derivable from the style document
before acquisition begins. RenG reproduces Rentile's composition byte-for-byte to do it — the
`min(z, maxZoom)` clamp, the `floorMod(sourceZ*31 + sourceX*17 + sourceY, templates.size)` template
hash, `{-y}` versus the TMS flip, the 3×3 DEM neighbourhood.

**Glyph ranges break the derivation, and they are the only resource class that does.** The
`(fontStack, rangeStart)` set falls out of text inside *decoded vector tiles*, which RenG never sees —
decoding them is Rentile's, behind the firewall. RenG cannot know the URLs, so it cannot preregister
them, so every glyph fetch fails closed and no label can ever be drawn.

## Why the workarounds are worse than the API

Measured against the 34 map styles Rentile is verified for, not hypothesised:

- **Pattern-match one class.** Allow `GLYPH_RANGE` to match a URL pattern instead of an exact string.
  This carves an exception into the single invariant the firewall rests on, and it is the one class
  whose URLs are attacker-influenced in the sense that matters: the range set comes from tile content.
- **Preregister the whole space.** The URL is one static template with two substitutions, and there
  are exactly 256 ranges (65536 / 256), so the space is bounded. But those 34 styles carry **82
  distinct font stacks, up to 17 in a single style** — 17 × 256 = **4352 routes**, each costing two
  SHA-256 digests at preregistration, and ADR 0016 binds a registry to one preparation invocation, so
  it recurs every frame. Against a measured ~6.6 ms for 1636 routes that is ~17 ms per frame, over the
  whole 60 fps budget, to cover a set of which a handful are ever fetched.
- **Two-phase call.** Ask for labels under `CACHE_ONLY` first and read the closure off the failure.
  Does not work: `CACHE_ONLY` throws rather than reporting what it would have needed.

## What Rentile already has

This is the part that makes the ask small. Rentile **already computes exactly this list**, internally,
on the path in question:

- `internal/glyph/LabelCandidateAssembler.kt:103` — `internal data class GlyphRangeRequest(val fontStack: String, val rangeStart: Int)`
- `internal/glyph/LabelCandidateAssembler.kt:487` — `val required = ranges.sortedWith(compareBy(GlyphRangeRequest::fontStack, GlyphRangeRequest::rangeStart))`
- `internal/DefaultBasemapRasterizer.kt:574` — the fetch, `glyphAcquirer.acquire(glyphsTemplate.resolve(), request.fontStack, request.rangeStart, resourceAccess)`

So the closure exists as a sorted, deduplicated list at line 487 and is consumed at line 574. The ask
is to make it observable between those two points.

## API sketch, to argue with

Something shaped like a split of `acquireLabelCandidates`:

```kotlin
public data class GlyphClosure(
    public val fontStack: String,
    public val rangeStart: Int,
)

/** The glyph ranges [acquireLabelCandidates] would need for these tiles, without fetching any. */
public suspend fun glyphClosureFor(
    style: PreparedStyle,
    tiles: List<TileId>,
    options: RenderOptions = RenderOptions(),
): List<GlyphClosure>
```

Design questions we do **not** have an opinion on, and would rather you settled:

- Whether it is a separate call or a mode of `acquireLabelCandidates`.
- Whether it returns composed URLs or the `(fontStack, rangeStart)` pairs. **Pairs are enough** — RenG
  can compose the URL itself from the style's `glyphs` template, and it already reproduces Rentile's
  composition elsewhere. Returning URLs would be more convenient and would couple us harder.
- Whether it must be exact or may over-approximate. **Over-approximation is fine and cheap for us** —
  a superfluous preregistered route is never fetched. Under-approximation is fatal: a missing route
  fails closed and the label silently does not draw.

## Constraints that must hold

- **It must not fetch.** The whole point is to learn the closure before any transport call. If
  computing it requires the vector tiles, it must be satisfiable from tiles already acquired for the
  same batch — which is where `acquireLabelCandidates` already sits.
- **It must be deterministic** for the same style, tiles and options. RenG preregisters from it and
  then fails closed on anything else; a nondeterministic closure presents as an intermittent
  total outage.
- **It must cover every glyph URL the subsequent acquisition will request**, including any Rentile
  adds internally — fallback stacks, notdef handling, a range fetched for measurement rather than
  drawing. If a URL can be requested without appearing in the closure, this API does not solve the
  problem it exists for.

## Verify before designing

- Whether `text-font` expressions are resolved before or after the closure is computed. Across the 34
  verified styles, 54 of 1235 text layers use a data-driven `text-font`, and every one observed is a
  `match` over `literal` branches — so the stacks are statically enumerable, but only if the closure
  reflects the *resolved* stacks rather than the expression.
- Whether the closure is stable across `ResourceAccessMode`. RenG passes its own three modes through.
- Whether two tiles sharing a range yield one entry. The KDoc at `Api.kt:467` says ranges dedupe across
  a ten-to-thirty tile call, which suggests yes — worth confirming it holds at the closure boundary too.

## Out of scope

Not asking Rentile to draw labels — RenG must, because text baked into a ground texture is
perspective-distorted under a pitched camera. Not asking for a public sprite atlas; RenG resolves
`LabelIconRef.imageName` itself, since it already validates the sprite pair. Not asking for any change
to glyph URL composition, atlas packing, or `LabelCandidate` itself — all of which are fine as shipped.

## Definition of done

RenG can, for a given prepared style and tile batch, obtain a list that provably contains every glyph
URL the following `acquireLabelCandidates` call will request — without that list costing a fetch, and
without RenG relaxing the firewall's exact-match rule for any resource class.
