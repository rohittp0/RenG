# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository state

**RenG is not implemented yet.** The tree is an unmodified Android Studio application skeleton:
`:app` (`com.rohittp.reng`, AGP 9.3.1, Gradle 9.5.0) with generated `ExampleUnitTest`/
`ExampleInstrumentedTest` and stock resources. Nothing here is RenG.

The `:app` module is a placeholder. RenG's real structure is a single `:kmp` library module modeled
on `/Users/rohittp/Data/Other/rentile` (see "Structure to build" below). Do not grow features inside
`:app`; when the KMP module lands, `:app` is either deleted or demoted to an Android demo consumer.

Design decisions live in `CONTEXT.md` (vocabulary) and `docs/adr/` (ADRs 0001–0012 cover the graphics
contract). Read both before proposing anything that touches the public API — where this file and an ADR
disagree, the ADR is newer and wins.

## What RenG is

A Kotlin Multiplatform dependency that renders 3D worlds on top of basemap tiles from
[Rentile](https://rohittp.com/rentile/) (`com.rohittp.rentile:kmp`, source at
`/Users/rohittp/Data/Other/rentile`). RenG draws a frame onto a caller-supplied surface. It does not
own a window, a render loop, a capture path, or an encoder.

Two contracts drive every design decision:

**RenG is pure.** It makes no changes to the host system. It performs no network I/O and owns no
persistent cache of its own — the consumer injects transport and store adapters, which RenG proxies
down to Rentile (Rentile's `ResourceTransport` / `RawResourceStore` interfaces) and uses for its own
assets (sticker images, GLBs, textures). Persistent caches are the consumer's. RenG only follows the
supplied `FramePlan`.

**A `FramePlan` is a complete definition of on-screen state.** Callers do not issue incremental
mutations. Frame-to-frame reuse — of decoded images, uploaded textures, parsed GLBs, compiled
shaders, tiles — is entirely RenG's internal concern, invisible in the API. This means diffing
consecutive `FramePlan`s and keying cached GPU/CPU resources by plan content is core renderer work,
not an optimization to bolt on later.

### Lifecycle contract

- Setup takes the caller's already-current GL context and native resources (transport, store, basemap
  style, output pixel size); the render loop takes a **prepared** `FramePlan`. Acquisition and drawing
  are separate operations — see ADRs 0001, 0002, 0004, 0012.
- RenG exposes API to query and free the resources it holds; the consumer calls it when it needs to.
- `close()` frees everything. `close()` and `free()` are idempotent, and both require the GL context to
  be current on the calling thread.
- Accessing a freed resource **reloads it and emits a warning** — freeing is never an error for the
  caller to recover from, so the resource layer needs a reload path on every access, not an assert.
- Losing the GL context is **not** freeing: a separate operation makes RenG forget its GL handles
  without deleting them, keeping every CPU-side resource intact (ADR 0007).

## Domain model

```
FramePlan(projectionMode = MERCATOR|GLOBE, stickers, models, geometries,
          camera = (lat, lon, zoom, bearing, pitch))

Placement(positionMode: AnchoringMode, position: Vector3,
          rotationMode: AnchoringMode, rotation: Vector3,
          pitchMode:    AnchoringMode, pitch: Double,
          scaleMode:    AnchoringMode, scale: Double /* [0, inf) */)

AnchoringMode = SCREEN | MAP

Sticker(placement, image: String /* url or local file path */)

Model(placement, glb: String, texture: String /* png */,
      animationStates: Array<AnimationTrack>)

AnimationTrack(name: String, frame: Int)

Geometry(topLeft: Vector3(lat, lon, altitude), bottomRight: Vector3(lat, lon, altitude),
         fragmentShader: Program, vertexShader: Program)
```

Non-obvious semantics:

- **Anchoring is per-property, not per-object.** One `Placement` can mix modes — e.g. `MAP` position
  with `SCREEN` rotation (a billboard pinned to a coordinate). The transform pipeline must resolve
  each of position/rotation/pitch/scale independently.
- **`SCREEN` anchoring turns `position.z` into a z-index** — ordered compositing, no depth test.
  **`MAP` anchoring requires full occlusion testing** against the 3D scene. These are two distinct
  draw regimes in one frame; ordering between them is a design decision worth an ADR.
- **Geometry shaders are GLSL ES 3.00 sources, self-contained but for their version directive.** No
  RenG-injected includes or uniform preamble; RenG substitutes `#version 330 core` for
  `#version 300 es` on desktop GL contexts and changes nothing else, and binds documented uniform and
  attribute names only when the shader declares them (ADR 0008). A `Geometry` is a
  lat/lon/altitude-bounded quad the shader pair paints.

## Structure to build (mirroring rentile)

Rentile is the structural template. Match it unless there is a documented reason not to.

| Path | Purpose |
|---|---|
| `kmp/` | The one published module. API, scene graph, resource layer, and platform renderers are deep package boundaries inside it — **not** separate Gradle subprojects (rentile ADR 0002: KMP publication does not fold unpublished project dependencies into the aggregate artifact, so extra modules would break the single-coordinate guarantee). |
| `docs/adr/` | One short ADR per decision, `NNNN-imperative-title.md`, a few paragraphs of prose — no template headings. |
| `docs/` | Dependency-free static site published to GitHub Pages at `https://rohittp.com/reng/`. |
| `CONTEXT.md` | Domain vocabulary: each term with its definition and an explicit `_Avoid_:` list of rejected synonyms. Read it before naming anything. |
| `consumer-smoke/` | **Standalone** Gradle build (own `settings.gradle.kts`) that resolves the published coordinate from an isolated repository with `exclusiveContent`, proving a release resolves without credentials and without Central masking it. Reads `VERSION_NAME` out of `../gradle.properties` rather than pinning a literal. |
| `.github/workflows/` | Already ported — `ci.yml` and `publish.yml`. See "CI/CD" below. |

Conventions carried over:

- `explicitApi()` plus Kotlin ABI validation (`checkKotlinAbi`) — public API changes are a reviewed diff.
- `VERSION_NAME` in the root `gradle.properties` is the **sole checked-in version input**. Never
  hardcode a RenG version in docs HTML, the smoke consumer, or the README. The release workflow may
  derive a later patch from the public version line under ADR 0013.
- `org.gradle.configuration-cache=true` is on, but publish/CI invocations pass
  `--no-configuration-cache` because remote Maven publishing is not CC-compatible.
- Typed exceptions with stable error codes, pipeline stage, and **redacted** diagnostics. Never
  forward messages or causes from injected transport/store adapters — they can carry signed URLs.
  Propagate `CancellationException` unchanged. No internal retries or fallbacks; the caller owns recovery.
- Never commit a `mavenLocal()` entry or a `-SNAPSHOT` dependency. Local cross-repo development uses
  `./gradlew publishToMavenLocal` in rentile plus a temporary repository entry, reverted before committing.

## Platform targets

RenG publishes exactly six targets:

```
android  iosArm64  iosSimulatorArm64  macosArm64  linuxX64  linuxArm64
```

**Apple Silicon only** — no `macosX64`, no `iosX64`. This matches rentile's release surface and its
reasoning (rentile ADR 0022): every published target is a permanent commitment, because removing one
later breaks resolution for anyone who adopted it. An Intel Mac gets a hard resolution failure, not a
degraded render. Adding `macosX64` later is a compatible change; adding it speculatively is not free.
Write this decision up as RenG's own ADR rather than leaving it implicit in the target list.

**No `jvm` target.** Rentile publishes one; RenG's spec enumerates Android, iOS, macOS, and Linux, so
the JVM is deliberately out of the published surface and absent from the ported workflows. Android
host tests still run on the JVM — that is a test source set, not a published target. If the macOS
harness or a future consumer needs it, adding `jvm` means touching both workflows and `consumer-smoke`.

**Rentile publishes every target RenG needs, as of `0.1.5`.** `kmp-android`, `kmp-iosarm64`,
`kmp-iossimulatorarm64`, `kmp-macosarm64`, `kmp-linuxx64`, and `kmp-linuxarm64` all resolve from
`https://maven.rohittp.com`, so RenG depends on `com.rohittp.rentile:kmp:0.1.5` with no `mavenLocal()`
and no temporary repository entry. (Earlier guidance here described `macosArm64` as unpublished at
`0.1.4`; that was true then and is not now.)

## CI/CD

`.github/workflows/ci.yml` and `publish.yml` are ported from rentile and adapted to
`com.rohittp.reng:kmp`. **Both reference `:kmp` and `consumer-smoke`, neither of which exists yet, so
they fail until those land** — and nothing runs at all until this directory becomes a git repository
with a GitHub remote.

`ci.yml` — two jobs on push-to-main and every PR: `android-linux` on ubuntu (`checkKotlinAbi`,
`testAndroidHostTest`, `linuxX64Test`, arm64 compile, `bundleAndroidMainAar`) and `apple-publication`
on macOS (Apple compiles, `macosArm64Test`, local publication, then clean-consumer resolution).

`publish.yml` — **every non-doc push to `main` cuts a release.** A `resolve-version` job reads
`VERSION_NAME`; if it is strictly greater than everything published it governs the release, otherwise
the job selects exactly the next patch after the newest published version. If that candidate's aggregate
POM already exists, resolution stops rather than skipping it. Docs, `**/*.md`, and `LICENSE` are
`paths-ignore`d so documentation commits do not consume a version. Snapshots are rejected outright,
and a `publish-main` concurrency group with `cancel-in-progress: false` serialises runs so two cannot
race for one immutable coordinate. The gate chain is: signed local publication with per-target
`checkPomFileFor*Publication` → clean-consumer resolution from the local repo → manifest-derived
collision checks against R2 → upload → verify every uploaded artifact and aggregate metadata over HTTP
→ re-resolve from the public repo with a fresh Gradle user home and `--refresh-dependencies`.

Publishing needs repository **vars** `R2_ENDPOINT`, `R2_BUCKET`, `R2_PUBLIC_URL` and **secrets**
`R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`, `SIGNING_KEY`, `SIGNING_KEY_ID`, `SIGNING_KEY_PASSWORD`.
A dedicated step fails fast if any is missing.

Two rentile gates were **not** ported because RenG has no analogue: the credential-free coverage
manifest check (`tools/check_coverage_manifest.py` over `compatibility/`) and the rolling
`map-catalog-corpus.yml` workflow, both of which exist to prove rentile renders every style in a
live public map catalog. RenG's equivalent — golden-image rendering over a corpus of `FramePlan`
documents — is undesigned; if it lands, it slots into the same two places (a CI job plus a gate step
in `publish.yml` before upload).

`org.gradle.configuration-cache=true` is set in `gradle.properties`, but every workflow invocation
passes `--no-configuration-cache` because remote Maven publishing is not CC-compatible.

## The macOS test harness

A local development client that consumes a locally published RenG, feeds it a series of `FramePlan`
JSON documents, and encodes the output as an MP4. **Capture and MP4 encoding live in the harness,
not in RenG** — RenG only draws. Treat the harness as a consumer that happens to live in this repo,
under its own build like `consumer-smoke`, so it exercises the real published coordinate rather than
a project dependency. It targets `macosArm64`, and it is the component that owns context creation:
RenG requires an already-current GL context, so the harness creates a headless core-profile context
through CGL — proven to work with no window and no display server, reporting `4.1 Metal - 90.5` on
Apple Silicon — draws into a capture framebuffer, and encodes.

## Commands

Today, against the placeholder skeleton:

```bash
./gradlew :app:assembleDebug
./gradlew :app:test
./gradlew :app:test --tests "com.rohittp.reng.ExampleUnitTest.addition_isCorrect"
./gradlew :app:connectedAndroidTest        # requires a device/emulator
```

Once `:kmp` exists, the local gate list mirrors rentile's (run before proposing a release):

```bash
./gradlew :kmp:checkKotlinAbi
./gradlew :kmp:testAndroidHostTest
./gradlew :kmp:linuxX64Test
./gradlew :kmp:compileKotlinIosArm64 :kmp:compileKotlinIosSimulatorArm64
./gradlew :kmp:macosArm64Test                     # Apple leg; macOS host only
./gradlew :kmp:compileKotlinLinuxX64 :kmp:compileKotlinLinuxArm64
./gradlew :kmp:publishAllPublicationsToLocalTestRepository
./gradlew -p consumer-smoke compileAndroidMain compileKotlinIosArm64 \
    compileKotlinIosSimulatorArm64 compileKotlinMacosArm64 compileKotlinLinuxX64 compileKotlinLinuxArm64
```

This list is exactly what `ci.yml` runs, split across its ubuntu and macOS jobs.

`macosArm64` is the one Apple target with a *test* task rather than a compile-only gate — rentile
runs `macosArm64Test` on its macOS CI leg, which makes it the fastest real-device-free way to
exercise native rendering code paths.

Single test in any of those source sets: `--tests "com.rohittp.reng.SomeTest"` (works on
Kotlin/Native test tasks too). CI passes `--no-configuration-cache` on every Gradle invocation.

`local.properties` is untracked and machine-specific; do not commit it.
