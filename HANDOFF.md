# Handoff

For whoever picks up RenG next. Cycle 0 — the graphics contract — is complete. Cycle A's
implementation and local gates are complete; the first public release is pending. Nothing renders and
there is no public runtime API yet. Cycle B becomes the next implementation cycle only after both CI jobs
pass the exact merged Cycle A commit and that commit's public workflow anonymously verifies its immutable
completion record.

## Read these first, in this order

1. `CLAUDE.md` — current project structure, contracts, tools, and local commands. Where it disagrees with
   an ADR, the ADR wins.
2. `CONTEXT.md` — vocabulary. Read it before naming anything.
3. `docs/adr/0001`–`0012` — the graphics contract. **Do not re-litigate these.** ADR 0013 separately
   governs the fail-closed release policy.
4. `docs/decomposition.md` — cycles A–J, their gates, and their order.

## What exists and what does not

Android Studio's placeholder `:app` is gone. Root settings include only `:kmp`, the single published
module for `com.rohittp.reng:kmp`. It declares exactly `android`, `iosArm64`, `iosSimulatorArm64`,
`macosArm64`, `linuxX64`, and `linuxArm64`; enables `explicitApi()` and KLIB ABI validation; and keeps
Rentile 0.1.5 as an `implementation` dependency. Cycle A's only Kotlin production code is an internal
Rentile linkage anchor exercised by a common test, so no Rentile type or RenG runtime symbol is public.

The standalone `consumer-smoke` build declares the same six targets and resolves RenG through an
`exclusiveContent` repository. It defaults to `build/local-maven`, reads the sole checked-in version from
root `gradle.properties`, and accepts explicit `rengRepositoryUrl`/`rengVersion` overrides for copied
public smoke. `VERSION_NAME=0.1.0`, `kmp/api/kmp.klib.api`, the dependency-free static `docs/` site,
Apache-2.0 legal files, and the publication/POM configuration all exist.

The standard-library Python tooling is implemented and unit-tested:

- `tools/check_repository_policy.py --root .` enforces the Cycle A repository contract.
- `tools/resolve_release_version.py --properties-file gradle.properties --repository-url <url>` selects
  one public candidate or fails closed.
- `tools/verify_publication.py` exposes:
  - `local --repository <path> --version <version> --manifest <path> [--require-signed-poms]`
  - `r2-preflight --endpoint <url> --bucket <bucket> --version <version> --manifest <path>`
  - `public --repository-url <url> --version <version> --manifest <path> [--attempts <n>] [--retry-delay <seconds>]`
  - `completion-create --version <version> --manifest <path> --source-commit <sha> --output <path>`
  - `completion-public --repository-url <url> --version <version> --manifest <path> --source-commit <sha> [--attempts <n>] [--retry-delay <seconds>]`
  These cover the seven-publication local manifest, authoritative exact-key collisions, anonymous public
  artifacts and metadata, canonical manifest-bound record creation, and anonymous record verification.

`.github/workflows/ci.yml` now gates Ubuntu and macOS work rather than referencing missing projects.
`.github/workflows/publish.yml` adds version resolution, a Linux release gate, signed local publication,
seven POM checks, fresh-home six-target local smoke, exact-key R2 preflight, upload, anonymous artifact
and retry-budgeted metadata verification, and a copied fresh-home credential-free public smoke. Only after
those public gates does it derive
`com/rohittp/reng/kmp/<version>/reng-release-completion-v1.json`, conditionally create it with
`If-None-Match: *`, and verify it anonymously with retries. The record's exact schema-version-1 fields are
`schemaVersion`, `mavenVersion`, `sourceCommitSha`, and `manifestSha256`; the hash covers the exact
serialized local manifest. Of those three completion-record stages, only the conditional write receives R2
credentials; local record derivation and final anonymous verification do not. The aggregate
KotlinMultiplatform R2 task depends on all six target R2 tasks as defense in depth, but neither aggregate
POM nor Maven metadata availability proves completion.

## Verified environment facts

Re-verify rather than trust if months have passed. Each fact below was established by running something,
not by recall.

| Fact | How it was verified |
|---|---|
| Rentile 0.1.5 publishes all six targets RenG needs | `curl` on `maven.rohittp.com` for `kmp-{android,iosarm64,iossimulatorarm64,macosarm64,linuxx64,linuxarm64}-0.1.5.pom` → all 200 |
| Depend on `com.rohittp.rentile:kmp:0.1.5`, no `mavenLocal()` | same |
| Kotlin/Native ships Apple GL bindings | `klib dump-metadata` on `~/.konan/.../klib/platform/*` |
| `macosArm64` → `platform.OpenGL3` (509 fns) + `platform.OpenGLCommon` (52 CGL fns) | same |
| iOS targets → **`platform.gles3`** (296 fns) — *not* `platform.OpenGLES3` | same; the module is named `OpenGLES3` but its package is `platform.gles3` |
| Linux has no GL platform klib (only `builtin`, `iconv`, `linux`, `posix`, `zlib`) | `ls ~/.konan/.../klib/platform/linux_x64` |
| Linux sysroots ship `dlfcn.h`, no `GL/` or `EGL/` headers | `find ~/.konan -name 'GL' -o -name 'EGL'` → empty |
| Android `GLES20`+`GLES30` cover every entry point needed | `javap` over `android.jar` (android-37.0) |
| Toolchain: Kotlin 2.3.21, AGP 9.3.1, Gradle 9.5.0 wrapper | `gradle/libs.versions.toml` here and in rentile |

Shader dialect, from a headless CGL context on an M3 Max reporting `4.1 Metal - 90.5`:

| Source | Core 4.1 | Legacy 2.1 |
|---|---|---|
| `#version 300 es` | **FAIL** — "version '300' is not supported" | FAIL |
| `#version 410 core` + ES-style body | OK | FAIL |
| `#version 330 core` + ES-style body | **OK, and links** | FAIL |
| `#version 120` / no directive | FAIL | OK |

"ES-style body" means `precision mediump float;`, `in`/`out`, `texture()`, `textureSize()`,
`layout(location = …)`, and integer uniforms. This is why ADR 0008 substitutes `330 core`.

## Traps that cost time — do not rediscover them

- **Hand-rolled cinterop against Apple GL headers silently produces an empty binding.** Clang
  availability attributes (`API_DEPRECATED(macos(10.5, 10.14))`) make cinterop *drop* declarations with
  no error and no warning: a def naming `OpenGL/gl3.h` yielded 19 GLU functions and zero GL ones.
  `GL_SILENCE_DEPRECATION` in `compilerOpts` did not fix it under Clang modules. Use the shipped
  platform klibs (ADR 0009).
- **A cinterop package name comes from the `.def` filename, not the `cinterops.create(...)` name.**
  `gl_macos.def` produces package `gl_macos`.
- **Inline C in a def file's `---` section *is* imported** even when the header declarations are dropped.
  Useful as an escape hatch; not needed for RenG.
- **Platform GL klibs are invisible from shared source sets.** `platform.gles3` resolves in
  `iosArm64Main` and fails in `iosMain`. `kotlin.mpp.enableCInteropCommonization=true` did not change
  this. GL code lives in leaf source sets.
- **Cinterops declared on the `main` compilation are not visible to the test compilation.** Put GL calls
  in main source and have tests call them — which is what RenG does anyway.
- **`klib dump-metadata` is the tool** for answering "what is actually in this klib": `~/.konan/
  kotlin-native-prebuilt-macos-aarch64-<version>/bin/klib dump-metadata <path>`. `klib contents` does
  not exist.

## Cycle A local completion and pending public outcome

The approved design and its fail-closed release policy are in
`docs/superpowers/specs/2026-08-14-cycle-a-build-publication-design.md` and ADR 0013. The implementation
and local gates are complete. No push, merge, workflow dispatch, AWS operation, R2 upload, or public
publication has been performed from this worktree. **Public release pending** is the authoritative
outcome until both CI jobs pass the exact merged commit and that commit's public workflow anonymously
verifies its exact completion record.

After that observed outcome, request approval for a separate documentation-only follow-up. Remove or revise
pending claims in `CLAUDE.md`, `README.md`, `docs/index.html`, `docs/kmp.html`, and `docs/llms.txt`; adjust
the `docs/versions.js` `pending` fallback if applicable; and record the exact merged CI/public outcome here
and in `docs/decomposition.md`. ADR 0013 and the Cycle A design spec and implementation plan remain
historical decision records unless the release exposes a contract error. Keep the public version display
metadata-driven and do not check a RenG semantic version literal into README or served docs. Do not
pre-apply that follow-up or infer success from artifacts, POM, metadata, local publication, or a branch CI
run.

The release resolver does not search for alternatives. If checked-in `VERSION_NAME` is newer than every
public stable version, that explicit declaration is the candidate and is allowed to recover from a partial
release. Otherwise, automatic next-patch advancement GETs the completion record for the newest
metadata-listed version and requires HTTP 200 plus strict schema-version-1 JSON with exactly the four fields
above, matching Maven version, lowercase 40-character source SHA, and lowercase 64-character manifest
SHA-256. Missing, malformed, mismatched, redirected, transport-failed, uncertain, or otherwise unsuccessful
record reads stop with an explicit upward-recovery instruction. Explicit upward recovery bypasses the prior
record. The selected candidate receives exactly one aggregate-POM availability probe; an occupied candidate
or remote uncertainty stops without skipping. Partial-release recovery requires an explicit upward
`VERSION_NAME`, full gates, and fresh approval. Never overwrite, delete, reuse, or automatically skip the
partial version. Public metadata verification retries stale or malformed HTTP 200 responses within its fixed
budget and fails closed after exhaustion.

Run the local Python and policy gates exactly as follows:

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
  -s tools/tests -p 'test_*.py' -v
PYTHONDONTWRITEBYTECODE=1 python3 tools/check_repository_policy.py --root .
```

On macOS, run the locally compilable Ubuntu-equivalent tasks; `linuxX64Test` cannot execute there:

```bash
./gradlew --no-configuration-cache \
  :kmp:checkKotlinAbi \
  :kmp:testAndroidHostTest \
  :kmp:compileKotlinLinuxX64 \
  :kmp:compileKotlinLinuxArm64 \
  :kmp:bundleAndroidMainAar
```

Then run Apple/target compilation, local publication, and clean smoke:

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

Ubuntu CI owns the host-executable Linux test and runs:

```bash
./gradlew --no-configuration-cache \
  :kmp:checkKotlinAbi \
  :kmp:testAndroidHostTest \
  :kmp:linuxX64Test \
  :kmp:compileKotlinLinuxArm64 \
  :kmp:bundleAndroidMainAar
```

Every Gradle invocation in CI/publication passes `--no-configuration-cache`. The smoke commands use a
fresh Gradle home and `--refresh-dependencies`, so cached RenG artifacts cannot mask an incomplete local
or public repository.

## Next cycle after release success: B — public API and pure core

Do not begin Cycle B merely because the feature branch, local publication, or PR is green. First require
both CI jobs on the exact merged commit and its public workflow to prove signed local publication, no
authoritative R2 collision, anonymous retrieval of every manifest artifact, valid aggregate metadata
containing the resolved version, fresh credential-free six-target resolution, conditional completion-record
creation, and credential-free anonymous verification of the exact record.

Only after that observed success may Cycle B preparation begin, in this order:

1. Read `CONTEXT.md`, ADRs 0001–0013, `docs/decomposition.md`, and this handoff.
2. Run the required Cycle B feasibility spikes, including coordinate-precision and transform-boundary work.
3. Invoke `/grill-with-docs` with the governing documents and spike findings.
4. Write an implementation plan only after that design review resolves its questions.

Cycle B implementation does not begin during Cycle A's release or post-release documentation follow-up.

## Decisions still open, each needing a spike before its cycle's spec

- **Coordinate precision (cycle B).** Latitude/longitude need doubles at high zoom; the GPU has floats.
  Camera-relative rebasing is required and its boundary belongs in the transform code.
- **PNG decode and GLB parse (cycle C).** No free answer for PNG across six targets — Skiko is proven
  but heavy, a pure-Kotlin decoder needs inflate. GLB is tractable in pure Kotlin but its supported
  feature subset must be written down, not discovered.
- **Draw-regime ordering (cycle F).** Screen-anchored things composite by z-index with no depth test;
  map-anchored things are occlusion-tested. How the two interleave in one frame is ADR-worthy.
- **Golden images (cycle E onward).** llvmpipe and Apple's GL will never be pixel-identical. Baselines
  are per-platform with a tolerance, never cross-platform equality.
- **Basemap suppression per frame.** Left undecided in ADR 0004 until the basemap cycle needs an answer.

## Process expectations

- The repository owner's standing instruction: **run `/grill-with-docs` before writing any plan**, and
  use parallel subagents for genuinely independent implementation tasks.
- ADRs are a few paragraphs of prose, no template headings, `NNNN-imperative-title.md`. Next number is
  0014.
- Update `CONTEXT.md` as terms resolve rather than batching it.
- Never commit `mavenLocal()`, a `-SNAPSHOT` dependency, or an independently hardcoded RenG version —
  `VERSION_NAME` in the root `gradle.properties` is the sole checked-in version input. ADR 0013 defines
  how publication may derive a later patch from the public version line.

## Throwaway spike code

Cycle 0's probes live in this session's scratchpad, not the repo, and are deliberately disposable:
`gl_dialect_probe.c` and `gl_330_probe.c` (headless CGL, C), and `glspike/` (a five-target Kotlin/Native
build whose `macosArm64Test` drives a real context). If the scratchpad is gone, the tables above are the
findings; reproducing them takes about an hour.
