# Decomposition

RenG is too large for one specification. It is built as a sequence of cycles, each with its own spec,
its own implementation plan, and its own gates. A cycle is finished when its outcome gates pass, not when
its code exists. Cycle A additionally requires both CI jobs on the exact merged commit and anonymous
verification of that commit's first public completion record.

Cycle 0 is complete: the graphics contract is decided and recorded in ADRs 0001–0012 and `CONTEXT.md`.
Everything below inherits those decisions rather than revisiting them.

## Order

```
A skeleton ──► B core ──┬──► C resources ──┐
                        │                  ├──► E basemap ──► F drawn things ──┬──► G globe
                        └──► D gl foundation┘                                  ├──► H platforms
                                                                               └──► I harness ──► J corpus
```

C and D are genuinely independent — one is I/O and CPU, the other is GPU — and are the natural place to
work in parallel. Everything else is a chain.

| Cycle | Delivers | Gates |
|---|---|---|
| A | Publishable `:kmp`, six targets, `:app` gone | Both CI jobs on exact merged commit; public six-target smoke; immutable completion record verifies anonymously |
| B | Public API surface and the pure core behind it | `checkKotlinAbi`, host + `linuxX64` + `macosArm64` tests |
| C | Resource acquisition, decode, parse, caching | Host tests against fake transport/store |
| D | The GL seam and its three implementations | Real-context conformance on macOS and llvmpipe |
| E | Basemap drawn from Rentile tiles | First frame with pixels; golden baseline per platform |
| F | Stickers, models, geometries, both draw regimes | Per-platform golden baselines |
| G | Globe projection | Golden baselines at both projection modes |
| H | Android and iOS bring-up | Device/simulator runs, manual for Android GL |
| I | macOS harness: plans in, MP4 out | A rendered sequence encodes and plays |
| J | Golden-image corpus gate | Corpus job wired into `ci.yml` and `publish.yml` |

## A — Build and publication skeleton

The `:kmp` module with `android`, `iosArm64`, `iosSimulatorArm64`, `macosArm64`, `linuxX64`, and
`linuxArm64`; `explicitApi()` and Kotlin ABI validation; `com.rohittp.rentile:kmp:0.1.5` resolving from
`https://maven.rohittp.com` with no `mavenLocal()`; the standalone `consumer-smoke` build; the
dependency-free `docs/` site; `VERSION_NAME` as the sole checked-in version input. `:app` is deleted —
it is Android Studio's skeleton and nothing in it is RenG.

Nothing here renders. The point is that `ci.yml` and `publish.yml`, which already reference `:kmp` and
`consumer-smoke`, stop failing, so every later cycle lands against a working gate. Publication fails
closed on remote uncertainty or an occupied artifact. Cycle A ends only when both CI jobs pass the exact
merged commit and its public workflow anonymously verifies every manifest artifact, valid aggregate
metadata, credential-free resolution for all six targets, and the final immutable record at
`com/rohittp/reng/kmp/<version>/reng-release-completion-v1.json`; POM and metadata availability alone are
not completion proof. See ADR 0013.

Cycle B preparation starts only after that outcome. First read `CONTEXT.md`, ADRs 0001–0013, this
decomposition, and `HANDOFF.md`; then run the required feasibility spikes; invoke `/grill-with-docs` with
the governing documents and spike findings; and only then write an implementation plan.

The implementation and local gates exist, but the public outcome is pending. After it is observed, an
authorized documentation-only follow-up revises pending claims in `README.md`, `docs/index.html`,
`docs/kmp.html`, and `docs/llms.txt`; adjusts the `docs/versions.js` fallback if applicable; and records
the result in `HANDOFF.md` and this file without hardcoding a release version into README or served docs.

## B — Public API surface and pure core

Every type a consumer touches, embodying ADRs 0001–0012: the frame vocabulary (`FramePlan`,
`Placement`, `Sticker`, `Model`, `Geometry`, `AnimationTrack`, camera), the renderer boundary
(`prepare`, `draw`, cancellation, resource query and free, the GPU-objects-are-gone operation, `close`),
RenG's own transport and store interfaces with RenG's resource classes, and typed exceptions carrying
stable codes, pipeline stage, and redacted diagnostics.

Behind it, the parts that need no GPU and no network: resolving a camera to matrices, resolving each
placement property independently under its own anchoring mode, selecting basemap tiles for a camera at
the configured output size, diffing consecutive plans, and deriving content-keyed identity for every
cacheable resource. All of it is host-testable, which is why it comes before both C and D.

This cycle must decide how coordinates keep their precision: latitude and longitude need doubles at high
zoom and the GPU only has floats, so some form of camera-relative rebasing is required and its boundary
belongs in the transform code, not scattered through the passes.

## C — Resource layer

Acquisition through the consumer's transport and store, proxying basemap resources down to Rentile and
fetching RenG's own; PNG decode; GLB parse; the content-keyed cache with refcounted lifetime across
concurrently live prepared frames; the reload-on-access path that makes freeing safe; cancellation of
everything in flight.

Two open technical decisions belong here and both deserve a spike before the spec is written. PNG
decoding across six targets has no free answer — Skiko is proven on these targets but heavy, and a pure
Kotlin decoder needs an inflate implementation. GLB parsing is glTF 2.0 binary: a JSON chunk plus a
binary chunk, tractable in pure Kotlin, but the supported feature subset must be written down rather
than discovered.

## D — GL foundation

The internal GL seam and its three implementations — `platform.OpenGL3`/`platform.OpenGLCommon`,
`platform.gles3`, `dlsym`, and Android's `GLES30` — with signatures both pointer-based and
JVM-array-based sides can implement. Context and dialect detection at setup; the offscreen colour+depth
surface and the composite pass; the documented save-and-restore state set; shader compilation with
version-directive substitution and program caching.

The conformance suite lands here and is the reason ADR 0006 and ADR 0008 are claims rather than hopes:
state identical before and after a draw, and a GLSL ES 3.00 source compiling under both a substituted
and an unsubstituted directive. It runs against real contexts on `macosArm64` and llvmpipe.

## E — Basemap

Rentile PNG tiles decoded, uploaded, and drawn as the mercator ground under a camera, with texture
residency and eviction driven by the prepared frames that are alive. This is the first cycle that
produces pixels, so it is also where per-platform golden baselines start — never cross-platform pixel
equality, since llvmpipe and Apple's GL will not agree.

## F — Drawn things

Stickers, models with their textures and animation-track frame selection, and geometries painted by
consumer shader pairs. This cycle owns the decision CLAUDE.md flags as ADR-worthy: how the two draw
regimes order against each other within one frame, given screen-anchored things composite by z-index
with no depth test while map-anchored things are occlusion-tested against the scene. It also fixes the
documented uniform and attribute names a shader pair may declare.

## G — Globe projection

The second projection mode, re-projecting mercator basemap tiles and every placement onto a globe.
Deliberately after F so it re-projects a complete scene rather than being designed around a partial one.

## H — Android and iOS bring-up

The two targets CI cannot exercise against a real context. Android's `GLES30` path and iOS's
`platform.gles3` path get run on real devices, and whatever differs from the macOS and Linux behaviour
gets fixed or documented.

## I — macOS harness

A consumer that happens to live in this repo, under its own build like `consumer-smoke`, resolving the
published coordinate rather than a project dependency. It owns everything RenG refuses to: creating the
headless CGL context, driving a capture framebuffer, reading back frames, and encoding MP4. It consumes
a sequence of `FramePlan` JSON documents, which means plan serialization is settled by then.

## J — Golden-image corpus

The gate that proves RenG still draws what it drew: a corpus of frame plans rendered per platform and
compared against baselines with a tolerance. It slots into the same two places Rentile's corpus does — a
job in `ci.yml` and a step in `publish.yml` before upload. Rentile's two credential-bearing corpus gates
have no RenG analogue and were deliberately not ported.
