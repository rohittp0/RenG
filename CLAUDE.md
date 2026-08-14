# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository state

**Cycle A's implementation and local gate surface are complete in this worktree; the public release is
pending.** The root build includes only the single `:kmp` library module; Android Studio's placeholder
`:app` has been deleted. `:kmp` is a publishable six-target skeleton with no public runtime API and no
rendering behavior yet. `consumer-smoke`, the dependency-free static site, publication tooling,
repository-policy tooling, and `VERSION_NAME=0.1.0` now exist.

Cycle A is not complete as a released cycle until the exact merged commit passes both CI jobs and the
first public publication anonymously verifies the immutable release-completion record. Do not start Cycle B
implementation before that outcome. A passing branch or local publication proves implementation/merge
readiness only; it does not prove the public outcome. After completion, Cycle B preparation must first read
`CONTEXT.md`, the governing ADRs, `docs/decomposition.md`, and `HANDOFF.md`, then run the required
feasibility spikes, invoke `/grill-with-docs` with those findings, and only then write an implementation plan.

Design decisions live in `CONTEXT.md` (vocabulary) and `docs/adr/` (ADRs 0001–0012 cover the graphics
contract; ADR 0013 governs fail-closed publication). Read both before proposing anything that touches
the public API — where this file and an ADR disagree, the ADR is newer and wins.

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

## Implemented structure (mirroring rentile)

Rentile remains the structural template. The implemented Cycle A surface is:

| Path | Purpose |
|---|---|
| `kmp/` | The one published module. Cycle A contains only an internal Rentile linkage anchor and its common test; later API, scene graph, resource layer, and platform renderers remain deep package boundaries inside it — **not** separate Gradle subprojects (rentile ADR 0002: KMP publication does not fold unpublished project dependencies into the aggregate artifact, so extra modules would break the single-coordinate guarantee). |
| `docs/adr/` | One short ADR per decision, `NNNN-imperative-title.md`, a few paragraphs of prose — no template headings. |
| `docs/` | Dependency-free static site published to GitHub Pages at `https://rohittp.com/reng/`. |
| `CONTEXT.md` | Domain vocabulary: each term with its definition and an explicit `_Avoid_:` list of rejected synonyms. Read it before naming anything. |
| `consumer-smoke/` | **Standalone** Gradle build (own `settings.gradle.kts`) that resolves the published coordinate from an isolated repository with `exclusiveContent`, proving a release resolves without credentials and without Central masking it. Reads `VERSION_NAME` out of `../gradle.properties` rather than pinning a literal. |
| `.github/workflows/` | `ci.yml` gates the branch on Ubuntu and macOS; `publish.yml` resolves one release candidate and verifies signed local, R2, public HTTP, and clean-consumer publication stages. See "CI/CD" below. |
| `tools/` | Standard-library Python release resolver, publication verifier, repository-policy checker, and their unit tests. |

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
This target decision is recorded in ADR 0010.

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

`.github/workflows/ci.yml` and `publish.yml` consume the implemented `:kmp`, `consumer-smoke`,
Python tools, and policy checks.

`ci.yml` has two jobs on push to `main` and every PR. `android-linux` runs the complete Python suite,
repository policy, ABI validation, Android host tests, `linuxX64Test`, Linux ARM64 compilation, and the
Android AAR gate on Ubuntu. `apple-publication` compiles both iOS targets, runs `macosArm64Test`,
publishes all seven publications to `build/local-maven`, then compiles the standalone consumer's six
targets with a fresh Gradle home and `--refresh-dependencies`.

`publish.yml` runs for every non-documentation push to `main` and for an explicit dispatch from `main`.
It implements a **one-candidate rule**. If checked-in stable `VERSION_NAME` is newer than every public
stable version, that explicit declaration is the candidate and may recover from a partial release. Otherwise,
automatic next-patch advancement requires HTTP 200 plus a strict matching completion record for the newest
metadata-listed version at
`com/rohittp/reng/kmp/<version>/reng-release-completion-v1.json`. Schema version 1 has exactly integer
`schemaVersion` equal to 1, canonical stable `mavenVersion`, lowercase 40-character `sourceCommitSha`, and
lowercase 64-character `manifestSha256` over the exact serialized local manifest. Missing, malformed,
mismatched, redirected, or uncertain records; malformed or empty metadata; transport errors; unexpected
statuses; snapshots; and occupied candidates stop resolution. Explicit upward recovery bypasses the prior
record. The selected candidate still receives exactly one aggregate-POM availability probe, and the resolver
never skips it. Partial-release recovery is always an explicit upward `VERSION_NAME` change, never
overwrite, delete, reuse, or automatic skip.

The release gate chain is: Python tests and repository policy → Ubuntu ABI/Android/Linux gates → signed
local publication of `kmp` plus its six target artifacts → all seven POM checks → manifest-derived signed
POM and artifact validation → fresh-home six-target local smoke → authoritative exact-key R2 collision
checks → upload → anonymous HTTP verification of every manifest entry and aggregate metadata, with stale or
malformed HTTP 200 metadata retried within the configured budget → a copied standalone smoke project
resolving all six targets from the public repository with no credentials, a fresh Gradle home, and
`--refresh-dependencies` → canonical completion-record derivation → authoritative conditional R2 creation
with `If-None-Match: *` → credential-free anonymous record verification with retries. Of those three
completion-record stages, only the conditional write receives R2 credentials. The aggregate publication
still runs after all six target publications as defense in depth, but neither aggregate-POM nor metadata
availability proves completion. `publish-main` concurrency is serialized with `cancel-in-progress: false`.

The standard-library Python tools are:

- `tools/check_repository_policy.py --root .` — enforces the Cycle A target, dependency, ABI, version,
  docs, repository, and license constraints.
- `tools/resolve_release_version.py --properties-file gradle.properties --repository-url <url>` — prints
  the sole selected candidate or fails closed.
- `tools/verify_publication.py` has five exact CLI surfaces:
  - `local --repository <path> --version <version> --manifest <path> [--require-signed-poms]`
  - `r2-preflight --endpoint <url> --bucket <bucket> --version <version> --manifest <path>`
  - `public --repository-url <url> --version <version> --manifest <path> [--attempts <n>] [--retry-delay <seconds>]`
  - `completion-create --version <version> --manifest <path> --source-commit <sha> --output <path>`
  - `completion-public --repository-url <url> --version <version> --manifest <path> --source-commit <sha> [--attempts <n>] [--retry-delay <seconds>]`
  They derive and validate the immutable local manifest, reject exact R2 key collisions, verify anonymous
  public artifacts and metadata, derive the manifest-bound completion record, and verify that record
  anonymously.

Publishing needs repository **vars** `R2_ENDPOINT`, `R2_BUCKET`, `R2_PUBLIC_URL` and **secrets**
`R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`, `SIGNING_KEY`, `SIGNING_KEY_ID`, `SIGNING_KEY_PASSWORD`.
A dedicated step fails fast if any is missing. Do not run AWS, upload, push, dispatch, or otherwise infer
that these outward gates passed without explicit approval and an observed workflow result. The first
public release is pending. After the exact merged CI jobs and first public completion record succeed, an
authorized documentation-only follow-up must remove or revise pending claims in `README.md`,
`docs/index.html`, `docs/kmp.html`, and `docs/llms.txt`; adjust the `docs/versions.js` `pending` fallback if
applicable; and update `HANDOFF.md` plus `docs/decomposition.md`. Keep public version display
metadata-driven and do not check a RenG semantic version literal into README or served docs.

Two rentile gates were **not** ported because RenG has no analogue: the credential-free coverage
manifest check (`tools/check_coverage_manifest.py` over `compatibility/`) and the rolling
`map-catalog-corpus.yml` workflow, both of which exist to prove rentile renders every style in a
live public map catalog. RenG's equivalent — golden-image rendering over a corpus of `FramePlan`
documents — is undesigned; if it lands, it slots into the same two places (a CI job plus a gate step
in `publish.yml` before upload).

`org.gradle.configuration-cache=true` is set in `gradle.properties`, but every workflow and release-gate
Gradle invocation passes `--no-configuration-cache` because remote Maven publishing is not CC-compatible.

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

Run Python and policy gates first:

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
  -s tools/tests -p 'test_*.py' -v
PYTHONDONTWRITEBYTECODE=1 python3 tools/check_repository_policy.py --root .
```

On macOS, run every locally compilable Ubuntu-equivalent gate (do **not** claim `linuxX64Test` ran):

```bash
./gradlew --no-configuration-cache \
  :kmp:checkKotlinAbi \
  :kmp:testAndroidHostTest \
  :kmp:compileKotlinLinuxX64 \
  :kmp:compileKotlinLinuxArm64 \
  :kmp:bundleAndroidMainAar
```

Run the Apple, cross-target compilation, local publication, and fresh dependency-cache smoke gates:

```bash
./gradlew --no-configuration-cache \
  :kmp:compileKotlinIosArm64 \
  :kmp:compileKotlinIosSimulatorArm64 \
  :kmp:macosArm64Test \
  :kmp:compileKotlinLinuxX64 \
  :kmp:compileKotlinLinuxArm64 \
  :kmp:publishAllPublicationsToLocalTestRepository

final_smoke_home="$(mktemp -d)"
./gradlew --no-configuration-cache \
  --gradle-user-home "$final_smoke_home" \
  --refresh-dependencies \
  -p consumer-smoke \
  compileAndroidMain \
  compileKotlinIosArm64 \
  compileKotlinIosSimulatorArm64 \
  compileKotlinMacosArm64 \
  compileKotlinLinuxX64 \
  compileKotlinLinuxArm64
```

On Ubuntu CI, the host-executable command is:

```bash
./gradlew --no-configuration-cache \
  :kmp:checkKotlinAbi \
  :kmp:testAndroidHostTest \
  :kmp:linuxX64Test \
  :kmp:compileKotlinLinuxArm64 \
  :kmp:bundleAndroidMainAar
```

`linuxX64Test` is Linux CI coverage, not a macOS-local gate. `macosArm64Test` is the one Apple target
with a test task rather than a compile-only gate. On the current macOS system Ruby 2.6 toolchain, parse
both workflow files with Psych's aliases-enabled positional API:

```bash
ruby -e 'require "yaml"; YAML.safe_load(File.read(".github/workflows/ci.yml"), [], [], true); YAML.safe_load(File.read(".github/workflows/publish.yml"), [], [], true)'
```

Single test in any Gradle test source set: `--tests "com.rohittp.reng.SomeTest"` (works on Kotlin/Native
test tasks too). Every CI and publication Gradle invocation passes `--no-configuration-cache`.

`local.properties` is untracked and machine-specific; do not commit it.
