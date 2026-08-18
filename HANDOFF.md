# RenG Cycle B handoff — 2026-08-17

This is the durable recovery point for the next agent. Cycle A is publicly complete. **Cycle B's
owner-approved public-API and pure-core implementation is complete on branch
`docs/cycle-b-resource-contract`** and pushed to `origin`. It is not merged, not released, and awaits
integration review.

Every plan task is implemented, and every task was independently reviewed with its findings fixed. RenG
still renders nothing and exposes no public runtime API.

## Read first

1. `CLAUDE.md` — repository constraints, purity contract, six targets, commands, and publication rules.
2. `CONTEXT.md` — canonical vocabulary.
3. `docs/adr/0001`–`0018` — newer ADRs override older prose.
4. `docs/superpowers/specs/2026-08-17-cycle-b-public-api-pure-core-design.md` — owner-approved Cycle B
   authority, approved at `11d7a03`.
5. `docs/superpowers/plans/2026-08-17-cycle-b-public-api-pure-core.md` — independently reviewed
   implementation plan, committed with its canonical fixture at `4ff05ee`.
6. This handoff, then the relevant implementation and tests.

The specification and plan are approved. Do not re-run grilling or write another plan unless
repository-owner review explicitly reopens a contract.

## Binding scope

Cycle B is pure core only: public immutable values, protocols and sanitized failures, canonical identities
and SHA-256, spatial and diff planning, and pure lifecycle, resource, and preparation reducers driven by
supplied values.

Do not add a renderer factory, consumer adapter call, Rentile acquisition, decoder or parser, production
cache, platform-context or GL call, shader compilation, pixels, retry, repair, fallback, or repeated
consumer exchange. Never forward injected adapter messages or causes. Preserve selected cancellation as an
opaque, unwrapped cancellation identifier and cause.

The branch retains exactly six published targets: Android, iOS Arm64, iOS Simulator Arm64, macOS Arm64,
Linux x64, and Linux Arm64. No JVM publication, `macosX64`, or `iosX64`. Never commit `mavenLocal()`, a
`-SNAPSHOT`, or `local.properties`.

## Implementation status

All plan tasks are complete: Task 0; Tasks 1–3; Tasks 4A–4C; Task 5; Tasks 6–7; Tasks 8A–8C; Tasks 9A–9D;
Task 10; Task 11; Tasks 12A–12C; Task 13; Tasks 14A–14C; Task 15; Task 16; and this documentation task.

The commits added after the previous handoff (`e4f2ace`) are:

| Commit | Content |
|---|---|
| `143ab67` | Task 9D review fix — screen compositing placement invariants |
| `afed63d` | Task 13 review fix — route cursor clearing and response controls |
| `c134e90` | Task 10 — integrated pure frame planning |
| `db74e8a` | Task 14A — ordinary resource class gates, writes, visibility |
| `d0bca59` | Task 14B — atomic sprite pair commit and parked scheduler |
| `c42caa3` | Task 14C — basemap style staging behind its owner barrier |
| `5949575` | Task 15 — ordered preparation reducer |
| `30b92b3` | Task 10 review fixes |
| `9bdbeeb` | Discovery-parent install and sprite closure fixes |
| `6eeb752` | Remaining review fixes — contradictory commit states rejected |
| `b91dbbb` | Task 16 — cross-engine pure-core contract proof |

## What independent review found, and why it mattered

Every implemented task was reviewed by an independent reviewer that read the code rather than trusting the
suite. Six of eight reviews returned changes required, totalling seven major and thirteen minor findings,
all now fixed. Two were serious enough to record permanently, because both lived in code whose own full
suite passed:

**Discovery parents never installed, so operations hung silently.** Route success requires every occurrence
installed and the style barrier requires every non-style occurrence installed, but discovery readiness
resolved a route without recording installed visibility. Any plan carrying a `BASEMAP_TILE_JSON` discovery
source produced no outcome at all, and a style waiting on that owner parked forever. Install and discovery
readiness had been mutually exclusive terminals. A discovery parent now installs its own content, stays
running under a child-discovery cursor, and retires only at readiness. Two reviewers found this
independently from different symptoms.

**Arbitration crashed a sprite group at concurrency two.** A buffered failure above a parked sprite image
member closed that member while its group owner still had validation, write, or install work in flight; the
group then wrote and installed into a resolved route and the reducer threw. The pre-existing trace missed it
only because it placed the unrelated route at a lower ordinal. Arbitration now skips a parked member whose
group has work outstanding, and the owner is not aborted, because its in-flight work may still report a
lower-ordinal failure that must win arbitration.

The rest were state-admission gaps: values whose constructors accepted self-contradictory combinations that
the reducer never produced. The pattern recurred often enough to be worth stating as guidance — **when a
reducer's own state type is the boundary that makes an illegal state impossible, write the invariant into
the type, not only into the paths that build it.**

## Verification record

Verified on Linux with forced task re-execution at `b91dbbb`:

- `:kmp:checkKotlinAbi` — clean. Cycle B's public surface was frozen earlier by Task 5 at `817f917`, and
  `kmp/api/kmp.klib.api` is byte-identical from the previous handoff `e4f2ace` through `b91dbbb` — every
  addition in this session is `internal`. The dump contains no Rentile type, platform binding, or renderer
  factory.
- `:kmp:testAndroidHostTest` — 431 tests, 0 failures.
- `:kmp:linuxX64Test` — 427 tests, 0 failures.
- `:kmp:compileKotlinLinuxX64`, `:kmp:compileKotlinLinuxArm64`, `:kmp:bundleAndroidMainAar` — pass.
- `:kmp:publishAllPublicationsToLocalTestRepository` — all seven publications at `0.1.0` under
  `build/local-maven/com/rohittp/reng/`: the aggregate `kmp` plus `kmp-android`, `kmp-iosarm64`,
  `kmp-iossimulatorarm64`, `kmp-macosarm64`, `kmp-linuxx64`, and `kmp-linuxarm64`, each with its POM,
  Gradle module metadata, sources, javadoc, `maven-metadata.xml`, and md5/sha1/sha256/sha512 checksums.
- `consumer-smoke` with a fresh Gradle home and `--refresh-dependencies` — resolves
  `com.rohittp.reng:kmp-android`, `kmp-linuxx64`, and `kmp-linuxarm64` at `0.1.0` plus
  `com.rohittp.rentile:kmp:0.1.5` with no credentials. Its three Apple targets were not attempted here.
- 73 Python tests pass; `tools/check_repository_policy.py --root .` prints `Cycle B repository policy passed`.
- `git diff --check 11d7a03..HEAD` is clean, and `kmp/api/kmp.klib.api` (772 lines) contains no match for
  `com.rohittp.rentile`, `platform.`, `createRenderer`, or `RendererFactory`.

Note when re-running the workflow parse: `CLAUDE.md` gives the macOS Ruby 2.6 positional form
`YAML.safe_load(text, [], [], true)`, which modern Psych rejects with `wrong number of arguments`. On Ruby 3
use the keyword form instead, which parses both workflow files:

```bash
ruby -e 'require "yaml"; ["ci","publish"].each { |w| YAML.safe_load(File.read(".github/workflows/#{w}.yml"), aliases: true) }'
```

The Apple gates cannot execute on Linux, so they were verified in GitHub Actions rather than claimed. Two CI
runs passed both jobs — `Android and Linux` on `ubuntu-latest` and `Apple and publication metadata` on
`macos-latest`:

| Run | Source commit | Result |
|---|---|---|
| `32055118061` | `9bdbeeb` | both jobs success |
| `32058579004` | `b91dbbb` | both jobs success |

The `macos-latest` job compiled both iOS targets, ran `macosArm64Test`, published all seven publications
locally, and resolved the aggregate coordinate from a clean six-target consumer with a fresh Gradle home. So
every gate in this task's list has now been observed on the final code commit, on the platform that owns it.

Those runs came from a temporary branch `ci/cycle-b-apple-verification` and draft pull request #2, which
existed only to borrow macOS hardware. The pull request is closed without merging. **The temporary branch
still exists on `origin` and should be deleted** — the development environment's git proxy refused both
delete refspecs, so it could not be removed from here. It is pinned at `b91dbbb` and carries no unique
work.

**Not observed, and not to be claimed:** exact merged-commit CI, publication, `linuxX64Test` on macOS, or
`macosArm64Test` on Linux. Cycle B is unreleased; `VERSION_NAME` remains `0.1.0` and the public `0.1.0`
record remains Cycle A's.

## Open decisions for the repository owner

None of these block integration review. All were found by independent review and deliberately left
unfixed, because each changes approved code or approved contracts.

1. **Transport response copy amplification.** A successful 200 duplicates its body four to five times and
   retains three simultaneously. With `maximumModelGlbBytes` at 256 MiB the peak live set approaches 1.75 GB
   for one GLB, before any concurrency multiplier. Latch retention is required by ADR 0016; the
   copy-then-latch-copy and the digest copy are not. `TransportResponse` already snapshots on construction
   and copies on every read, so the extra copies are removable without weakening purity.
2. **`ShaderProfilePlan` validates nothing.** A directly constructed profile can claim a source it does not
   describe, and desktop substitution then emits `#version 330 core#version 300 es`, which will not compile.
   That breaks ADR 0008's substitution contract. The fix belongs in Task 9C's construction surface, which is
   approved, so it was not changed.
3. **Scheduling cost across distinct routes.** Per-event work is proportional to total registry size, so a
   full run over R distinct routes is quadratic. This is pre-existing from Task 12B, and Cycle B multiplies
   the event count per route. The plan's stated case — 4,096 joined occurrences on one route — is linear and
   passes.
4. **The plan omits advancement events.** Tasks 14A, 14B, and 14C each required a driving event to preserve
   Task 13's zero-action lookup boundary, but only `AdvancePendingClassGates` is specified. The three
   implementations added `AdvancePendingClassGates`, `AdvancePendingSpriteCommit`, and
   `AdvancePendingStyleCommit` consistently. Worth folding into the plan or an ADR so the next cycle does not
   rediscover it.
5. **Two guards proved unkillable by test.** `successOutcome`'s buffered-outcome guard and the
   `gates.indexOf`-versus-`gateIndex` distinction survive mutation because no admissible state distinguishes
   them. They were kept and documented rather than removed or covered by a vacuous test.

## Fresh-device checkout and environment setup

Start from a fresh clone:

```bash
git clone --branch docs/cycle-b-resource-contract --single-branch \
  https://github.com/rohittp0/RenG.git
cd RenG
git status --short
git rev-parse HEAD
```

`git status --short` must be empty. If the repository already exists, stop if it has local changes, then:

```bash
git fetch origin
git switch docs/cycle-b-resource-contract 2>/dev/null || \
  git switch --create docs/cycle-b-resource-contract \
    --track origin/docs/cycle-b-resource-contract
git pull --ff-only
```

Required local tools:

- Git; a 64-bit JDK 21; Python 3 (standard library only); the checked-in Gradle 9.5.0 wrapper — do not
  substitute a system Gradle.
- **Android SDK Platform 37.0.** The package identifier is `platforms;android-37.0`, not
  `platforms;android-37`, and on older `cmdline-tools` it resolves only from the canary channel. Installing
  it on a bare Linux host looks like:

  ```bash
  sdkmanager --sdk_root="$ANDROID_HOME" --channel=3 \
    "platforms;android-37.0" "build-tools;37.0.0" "platform-tools"
  ```

  Then point untracked `local.properties` at it with `sdk.dir=/absolute/path/to/Android/sdk`. Without the
  SDK, AGP fails at configuration time and **every** Gradle task is blocked, including `linuxX64Test`.
- Network access on the first build for the Gradle distribution, the Kotlin/Native toolchain, Google Maven,
  Maven Central, and `https://maven.rohittp.com`.
- For the complete Apple gates: Apple Silicon macOS with Xcode and Command Line Tools. Alternatively, push a
  temporary branch and open a **draft** pull request; `ci.yml` runs on every pull request and its
  `apple-publication` job covers `macosArm64Test`, both iOS targets, local publication, and the clean
  six-target consumer. `publish.yml` triggers only on push to `main` or explicit dispatch, so a pull request
  cannot consume a version or reach R2.

No Docker, R2 or AWS credentials, signing key, publication secret, or `mavenLocal()` entry is needed for any
Cycle B work.

On Linux, the host-executable gate is:

```bash
./gradlew --no-configuration-cache \
  :kmp:checkKotlinAbi \
  :kmp:testAndroidHostTest \
  :kmp:linuxX64Test \
  :kmp:compileKotlinLinuxArm64 \
  :kmp:bundleAndroidMainAar
```

On Apple Silicon macOS, run the locally complete implementation gate instead:

```bash
./gradlew --no-configuration-cache \
  :kmp:checkKotlinAbi \
  :kmp:testAndroidHostTest \
  :kmp:macosArm64Test \
  :kmp:compileKotlinIosArm64 \
  :kmp:compileKotlinIosSimulatorArm64 \
  :kmp:compileKotlinLinuxX64 \
  :kmp:compileKotlinLinuxArm64 \
  :kmp:bundleAndroidMainAar
```

Do not claim `linuxX64Test` from macOS or `macosArm64Test` from Linux. Gradle reports `UP-TO-DATE` for test
tasks whose inputs have not changed, so pass `--rerun-tasks` when a run is meant to be evidence.

## Next work

Cycle B awaits integration review. After it is approved and merged, the decomposition's next cycles are C
(resource acquisition, decode, parse, caching) and D (the GL seam and its three implementations), which are
genuinely independent and the natural place to work in parallel. Both connect real observations and execute
the actions Cycle B's pure engines already decide.

## Publication boundary

Pushing this development branch is authorized as a recovery checkpoint. It is not permission to merge,
dispatch publication, upload to R2, or claim a public release. Cycle A's immutable public `0.1.0` record
remains historical; Cycle B is neither complete as a released cycle nor published.
