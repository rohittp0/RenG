# RenG handoff — 2026-08-18

The recovery point for whoever picks RenG up next. Cycle A is publicly released. **Cycle B is complete and
merged to `main` locally**, with its five open decisions resolved and every local gate green — but `main` is
unpushed, so its exact merged-commit CI and publication have not been observed. **Cycles C and D have
owner-approved design specifications and written implementation plans**; neither has been implemented.

RenG still renders nothing and exposes no public runtime API.

**Pushing `main` releases Cycle B.** `publish.yml` runs on every non-documentation push to `main`, and since
`0.1.0` already has a valid public completion record, the resolver advances to `0.1.1` and publishes it to R2
immutably. Nothing about that has happened.

## Read first

1. `CLAUDE.md` — repository constraints, purity contract, six targets, commands, publication rules.
2. `CONTEXT.md` — canonical vocabulary. Read it before naming anything.
3. `docs/adr/0001`–`0018` — newer ADRs override older prose.
4. `docs/decomposition.md` — the cycle sequence and each cycle's gates.
5. `docs/superpowers/specs/2026-08-18-cycle-c-resource-layer-design.md` and
   `docs/superpowers/specs/2026-08-18-cycle-d-gl-foundation-design.md` — the approved specifications for the
   next two cycles, each with a written plan alongside in `docs/superpowers/plans/`. The Cycle A and Cycle B
   pairs in the same directories are historical decision records.
6. `docs/research/` — the four Cycle C and D findings documents. Read the relevant one before writing
   either cycle's specification; each ends with a checklist of what its spec must decide.

Approved specifications and plans are not reopened without repository-owner review.

## Cycle B, as implemented

Pure core only: public immutable values, protocols and sanitized failures, canonical identities and
SHA-256, spatial and diff planning, and pure lifecycle, resource and preparation reducers driven entirely
by supplied values. Every plan task is complete, including the cross-engine contract proof, and every task
was independently reviewed with its findings fixed.

There is no renderer factory, consumer adapter call, Rentile acquisition, decoder, parser, production
cache, GL call, shader compilation or pixel. The public ABI dump contains no Rentile type, platform binding
or renderer factory, and was frozen by Task 5 — nothing since has changed it.

Verified on Linux with forced task re-execution: `checkKotlinAbi` clean; `testAndroidHostTest` 432 tests
and `linuxX64Test` 428 tests, no failures; Linux Arm64 compilation and the Android archive gate pass; all
seven publications resolve at `0.1.0` into a local repository, and a fresh-Gradle-home consumer resolves
the three Linux-executable targets with no credentials; 73 Python tests and `Cycle B repository policy
passed`. The Apple gates cannot run on Linux and were observed in continuous integration instead: runs
`32055118061` and `32058579004` each passed both the Ubuntu job and the macOS job, the latter compiling
both iOS targets, running `macosArm64Test`, publishing all seven publications and resolving a clean
six-target consumer.

Not observed, and not to be claimed: exact merged-commit CI, publication, `linuxX64Test` on macOS, or
`macosArm64Test` on Linux. `VERSION_NAME` remains `0.1.0` and the public `0.1.0` record remains Cycle A's.
One test-only commit landed after the last CI run, so its 432nd test has not run on Apple hardware.

### What review caught, and the lesson worth keeping

Six of eight independent reviews returned changes required — seven major and thirteen minor findings, all
fixed. Two were serious, and both lived in code whose own full suite passed.

A discovery parent never recorded installed visibility, so any plan carrying a discovery source produced no
outcome at all and a style waiting on that owner parked forever. Install and discovery readiness had been
mutually exclusive terminals, yet such a parent needs both. Two reviewers found it independently from
different symptoms. And arbitration closed a parked sprite member while its group owner still had work in
flight, after which the group wrote into a resolved route and the reducer threw; the existing trace missed
it only because it placed the unrelated route at a lower ordinal.

Most of the rest were the same shape: values whose constructors accepted self-contradictory combinations the
reducer never produced. **When a reducer's own state type is the boundary that makes an illegal state
impossible, write the invariant into the type, not only into the paths that build it.**

### The five open decisions, resolved

All five were worked on 2026-08-18. Four are closed in code or documentation; one is deliberately deferred.

1. **Transport response copy amplification — partly fixed.** The original note undercounted its own
   arithmetic: a successful 200 made **seven** copies, not four to five (1.75 GB ÷ 256 MiB is exactly 7),
   and two paths went unmentioned — the `304` merge and the Store read, the latter running on every warm
   frame. `TransportResponse` and `StoredRawResource` now expose internal non-copying snapshot accessors
   used where the array is only measured or fed straight into a copying constructor, and the redundant
   latch and `copyStored` re-copies are gone. Both classes are `final` over private snapshots with
   copying public getters, so no aliasing escapes and the public copy-on-read contract is untouched.
   **Still open, and deliberately:** letting RenG retain the consumer's own `TransportResponse` instead of
   its own copy would remove two further copies, but ADR 0016 says the outcome is latched as a
   *defensively copied* response, and retaining the consumer's object makes identity observable through
   `===`. That is an ADR 0016 amendment, not a cleanup.
2. **`ShaderProfilePlan` validates nothing — fixed.** The type now carries five `init` invariants relating
   its declared span to the actual source: ascending in-range span, span starts and ends a physical line,
   and the trimmed span is exactly the accepted directive. Bounds are checked before the trim comparison,
   which indexes the span. Seven contradictory constructions are now rejected with
   `IllegalArgumentException`, including the reported `#version 330 core#version 300 es` case and a
   descending span that previously duplicated text silently. Messages carry no source text.
3. **Scheduling cost — measured, not changed, by decision.** The original note named the wrong cause.
   `startNotYetStartedRoutes` sorting every route is real but nearly irrelevant. The actual shape is a
   **Θ(routes + occurrences) floor paid by every event** — the reducer rebuilds all derived indexes,
   re-copies every route record and re-validates the whole state per transition — times roughly nine
   events per route, giving Θ(events × (routes + occurrences)). Two further costs compound it: the
   style-owner barrier is O(owners × occurrences), which bites because a 256-frame batch binds all 256
   owners to one `StyleGroupId`; and `OwnerResourceSet` calls `toSet()` on every transition while
   `StoredRawResource.hashCode()` does a full `contentHashCode()` byte scan, so **every event re-hashes the
   complete payload of every already-installed resource** — on the order of 100 GB per frame at 512 tiles
   of 50 KB.

   **The per-event rebuild floor above is now measured directly; the style-owner barrier and full-payload
   re-hashing costs above remain extrapolations.** `ResourceOperationScaleBenchmarkTest` drives many
   *distinct* sticker routes (one occurrence each, no joining) through `ResourceOperationStateMachine.start`
   / `beginLookup` / `transition` to a real `ResourceOperationOutcome.Success`, on the always-succeeds path,
   and timed it on Apple Silicon macOS via
   `./gradlew --no-configuration-cache --rerun-tasks :kmp:macosArm64Test --tests
   "com.rohittp.reng.internal.resource.ResourceOperationScaleBenchmarkTest"`. Three independent runs:

   | routes | run 1 | run 2 | run 3 |
   |---|---|---|---|
   | 64  | 503 ms   | 498 ms   | 501 ms   |
   | 128 | 1838 ms  | 1724 ms  | 1705 ms  |
   | 256 | 7274 ms  | 6975 ms  | 6824 ms  |
   | 512 | 33622 ms | 29052 ms | 29610 ms |

   Successive-doubling ratios sit at roughly 3.5–3.7, 4.0–4.1, and 4.2–4.6 — at or above the quadratic
   signature (ratio 4), not the linear one (ratio 2, which the existing 4096-occurrence-on-one-route test
   already shows for the joined case).

   **This measures the base rebuild floor alone, not the other two costs named above.** Every route in this
   benchmark takes a unique `ResourceOwnerId`, so `OwnerResourceSet` never holds more than one element and
   the O(owners × occurrences) style-owner barrier above is never exercised; every occurrence uses
   `ResourceCommitBinding.Single`, so the shared style/sprite group path is never taken either; and the
   transport response body is four bytes, not the ~50 KB-per-tile payload the re-hashing cost above
   assumes. So the table measures only the reducer's per-event O(routes + occurrences) rebuild, at roughly
   nine events per route, compounding into effectively **O(routes²) or worse** for this scenario — not the
   owner barrier, not the re-hashing cost, and not their combination. A real mixed frame paying those two
   costs as well would cost more than the table shows, not less.

   **This is not acceptable at the shipped default 512-tile budget, and if anything the verdict is
   conservative** given it excludes two costs that only add to it: 29–34 seconds of scheduler CPU before a
   single byte is decoded, per frame, is not a viable production number on the rebuild floor alone, and the
   4096 maximum configuration is minutes. The benchmark's guard asserts the 512-route case stays under 50
   real seconds — anchored to the worst of the three observed runs with ~49% headroom for slower CI
   hardware, not for a further regression: extrapolating the 256-route worst run (7274ms) at a cubic rather
   than the observed near-quadratic doubling ratio (8x instead of ~4.2-4.6x) predicts ~58192ms for 512
   routes, which this ceiling still catches. A future change that makes this meaningfully worse fails a
   real test rather than shipping silently. None of this was observable in Cycle B, which has no factory
   and no public runtime API. **Cycle C's resource driver must fix this scheduling cost before or alongside
   connecting real adapters** — the benchmark above is the measurement any such fix must move, and the
   guard is what proves it worked. The cheapest real win for the re-hashing cost is caching
   `StoredRawResource`'s hash at construction; the honest fix for the floor measured here is the per-event
   rebuild itself — Cycle C needs to address both, since they are separate costs.

   A 2026-08-19 harness review found the benchmark's own driver had contributed a second, avoidable
   O(routes²) cost stacked on top of the reducer's: `advanceAllPendingClassGates` re-scanned the full route
   list after every action rather than tracking which route needed advancing next. That scan is gone — the
   driver now names the ordinal directly off the `CallTransport` action that parks it, in O(1), which this
   benchmark's fixed always-succeeds event mapping makes exact rather than approximate. Re-measuring after
   the fix reproduced doubling ratios in the same at-or-above-quadratic range reported above, confirming
   the reducer's own per-event rebuild — not the benchmark's driving loop — produced the numbers in the
   table.
4. **Advancement events — fixed in the plan.** `AdvancePendingClassGates` appeared three times in the plan
   and `AdvancePendingSpriteCommit` and `AdvancePendingStyleCommit` zero times, though the reducer
   implements all three with identical preconditions. Tasks 14B and 14C now specify theirs, including the
   start-ceiling behaviour that makes a ceiling-prohibited sprite member stage no candidate.
5. **Two unkillable guards — kept and now documented in code.** The buffered-outcome guard in
   `successOutcome` carries a comment explaining that outcomes buffer only at or above
   `nextRetirementOrdinal`, so the preceding retirement check already implies it. Neither guard had any
   comment before, so a future reader would reasonably have deleted them as dead code.

## Cycle C — resource layer

Acquisition through the consumer's adapters, proxying basemap resources to Rentile and fetching RenG's own;
PNG decode; GLB parse; the content-keyed cache with refcounted lifetime across live prepared frames; the
reload-on-access path; cancellation of everything in flight. Cycle B already decided every pure action this
cycle executes, so Cycle C connects real observations rather than inventing policy.

Its two open questions are now settled, and a third emerged.

**PNG decode — `docs/research/2026-08-18-cycle-c-png-decode.md`.** Own the container in common Kotlin and
delegate only decompression and checksum to the platform: the bundled `zlib` binding on the five native
targets, `java.util.zip` on Android. A spike walking chunks, validating checksums, streaming across split
image data and unfiltering produced byte-exact output on a Linux host and as an emulated aarch64 binary, and
the same source compiles for the Apple targets. Skia is rejected on measured behaviour, not weight: it
silently truncates sixteen-bit grayscale to eight bits, silently accepts a stream truncated mid-image-data
with no end marker, collapses a checksum mismatch and a corrupt payload into one opaque message, and is not
faster. Repairing malformed input contradicts RenG's contract outright. Note also that Rentile returns
rendered tiles as encoded bytes, so RenG decodes PNG even for vector basemaps, and both of its terrain
encodings are eight-bit, so sixteen-bit support is a choice rather than a requirement.

**GLB parse — `docs/research/2026-08-18-cycle-c-glb-parse.md`.** The accept and reject table is written
down rather than left to discovery, and a pure-Kotlin container reader classified forty-one deliberately
malformed fixtures exactly as intended. Two consequences bind the design: padding is not verifiable at the
container layer, because a chunk's declared length includes its padding, so a padding policy is an explicit
choice; and parsing must tolerate an accessor without a buffer view so that the feature gate reports an
unsupported feature rather than the parse gate reporting malformed input — a compressed model is
unsupported, not corrupt, and Cycle B's two gates already model that distinction.

**Cycle C needs its own JSON reader, and this is the finding that was not anticipated.** The serialization
library is already resolved transitively but is compile-visible only on the native targets, and the
repository policy forbids declaring it directly. A reader is therefore required rather than chosen. The
spike's version is the feasibility proof, and measured allocation behaviour shows the JSON chunk needs its
own ceiling separate from the model byte limit, since a chunk at that limit would demand several gigabytes.

**Rentile's real surface — `docs/research/2026-08-18-cycle-c-rentile-surface.md`.** Read this before
writing the adapter; it was taken from the published artifact because the source tree is not on this
machine. The adapter must absorb a `remove` that RenG's own store does not have, because Rentile removes a
stored record on digest mismatch or parse failure. Rentile's raw key is a hash of the URL with
authentication query parameters redacted, and that derivation is what the real private-key resolver must
reproduce — Cycle B wired a deterministic fake. Rentile always sends null conditional headers, so every
conditional and accept decision is RenG's. Its response metadata carries nine fields against RenG's four.
Its retry is bounded to one extra call on specific statuses with a clamped delay, and it rethrows
cancellation unchanged. Credential and session providers are never invoked in `0.1.5`. And
`RawResourceKey.toString()` prints its identifier in the clear, so a Rentile key must never reach a RenG
diagnostic.

What the artifact cannot show is behaviour: threading, actual exchange counts, per-class store ordering,
revalidation, cancellation depth. The document prescribes a counting-stub spike against a real Rentile
call to close those before the specification is final.

## Cycle D — GL foundation

The internal GL seam and its three implementations, context and dialect detection at setup, the offscreen
surface and composite pass, the documented save-and-restore set, and shader compilation with version
substitution and program caching. The conformance suite lands here and is what makes ADRs 0006 and 0008
claims rather than hopes. Read `docs/research/2026-08-18-cycle-d-gl-foundation.md` first; it corrects
three things.

**The substitution trigger must be the context's queried shading language, never the target platform.** On
a real llvmpipe ES context the unsubstituted source compiles and the substituted one fails; on a desktop
context both compile; on Apple only the substituted one does. Because the consumer creates the context, one
Linux target serves either kind, so platform-keying would inject a desktop directive into an ES context,
which is fatal — and that is the more likely Linux case. Substituting on every desktop context is confirmed
safe rather than merely assumed.

**The documented restore set was incomplete**, missing the colour write mask, pixel store alignment and row
length, clear values, the array buffer binding and pack alignment; the pixel store alignment default is
four, not one. A full save, perturb and restore round trip is now byte-exact on both a real ES and a real
desktop context. The error queue is the one piece of state that cannot be preserved, because reading it
clears it — a real exception to the no-modification guarantee, and one to state rather than discover.

**The binding inventory is measured, not remembered**, and the earlier counts were right. An eighty-four
name checklist of the entry points a renderer needs has no gap on any of the four implementations. Linux
ships no GL or EGL headers at all, and the `dlsym` seam now compiles for both Linux targets from one source
and runs against a surfaceless llvmpipe context created with no display server, including the array-based
uploads the Android side must mirror.

**The conformance suite can run on a hosted macOS runner, provided it never requests acceleration.** A
context is created only when the accelerated pixel format requirement is dropped, and it then reports
`Apple Software Renderer` rather than Metal. A suite that asks for acceleration fails to obtain a context
at all, with an error that names an invalid pixel format rather than the absence of a GPU.

The seam's central design problem remains, and no research settles it: one interface must be implementable
by pointer-based Kotlin/Native sides and by Android's JVM-array-based `GLES30`. The research document
sketches signatures for representative calls; the specification has to choose.

## Cycles E through J

**E — basemap and terrain.** Rentile tiles decoded, uploaded and drawn as the mercator ground, with texture
residency and eviction driven by the prepared frames that are alive, which connects directly to Cycle B's
lease machinery. It also displaces that ground with the terrain Cycle C acquires, since nothing before it
consumes elevation. First pixels, so golden baselines start here.

Baselines need a finer key than the platform. A hosted macOS runner renders through a software renderer
while a developer's machine renders through Metal, so the reported renderer string — not the target —
should key a baseline, or the first run somewhere new will fail on a difference that is not a regression.

**F — drawn things.** Stickers, models with textures and animation-track time sampling, and geometries
painted by consumer shader pairs. Owns the decision `CLAUDE.md` flags as ADR-worthy and still unmade: how
the two draw regimes order within one frame, given screen-anchored things composite by z-index with no
depth test while map-anchored things are occlusion-tested. That would be **ADR 0023**. This cycle also
fixes the documented uniform and attribute names a shader pair may declare.

**G — globe projection.** The second projection mode, re-projecting mercator tiles and every placement.
Deliberately after F so it re-projects a complete scene.

**H — Android and iOS bring-up.** The one cycle no continuous integration can cover. A draft pull request
borrows macOS and Linux hardware, but not a device; Android GL remains manual.

**I — macOS harness.** A consumer living in this repository under its own build, resolving the published
coordinate. It owns everything RenG refuses: creating the headless context, driving a capture framebuffer,
reading back frames, encoding MP4.

**`FramePlan` serialization is an unowned prerequisite for this cycle.** The decomposition states that the
harness consumes `FramePlan` JSON documents, "which means plan serialization is settled by then" — but
nothing has settled it. RenG has no serialization surface in its public ABI and no serialization dependency
or plugin anywhere in the build. Two candidate owners: a public serialization API in RenG, which adds
public surface and probably a dependency the repository policy currently forbids; or harness-side parsing
that constructs plans through the existing public constructors, which keeps RenG dependency-free but
duplicates the schema. Decide before Cycle I, and note that Cycle F fixes shader uniform names that a
serialized plan would have to name.

**J — golden-image corpus.** The gate that proves RenG still draws what it drew, wired into the same two
places Rentile's is: a job in `ci.yml` and a step in `publish.yml` before upload. Rentile's two
credential-bearing corpus gates have no RenG analogue and were deliberately not ported.

## Environment notes

Established on a fresh Linux container today, and each cost time to discover.

- **Android SDK Platform 37.0** is the package `platforms;android-37.0`, not `platforms;android-37`, and on
  older `cmdline-tools` it resolves only from the canary channel:
  `sdkmanager --sdk_root="$ANDROID_HOME" --channel=3 "platforms;android-37.0" "build-tools;37.0.0"`. Point
  untracked `local.properties` at it with `sdk.dir=…`. Without the SDK, AGP fails at configuration time and
  **every** Gradle task is blocked, including `linuxX64Test`.
- **All Kotlin/Native platform klibs, including the Apple ones, are present on a Linux host** under
  `~/.konan/kotlin-native-prebuilt-linux-x86_64-<version>/klib/platform/`. Apple bindings can therefore be
  inspected without a Mac: `klib dump-metadata <path>` (`klib contents` does not exist).
- **A real GL context is available on Linux.** Install `libegl1 libegl-mesa0 libgles2` after
  `apt-get update`, then create a surfaceless context via `EGL_PLATFORM_SURFACELESS_MESA` — no display
  server needed. Both an ES 3.2 and a 4.5 core profile context are reachable on llvmpipe.
- **Gradle reports `UP-TO-DATE` for unchanged test tasks.** A green build off cached test tasks is not
  evidence; pass `--rerun-tasks` when a run is meant to prove something.
- **The workflow parse command in `CLAUDE.md` uses the macOS Ruby 2.6 positional form**, which modern
  Psych rejects with `wrong number of arguments`. On Ruby 3 use
  `YAML.safe_load(File.read(path), aliases: true)`.
- **Borrowing Apple hardware:** push a temporary branch and open a **draft** pull request. `ci.yml` runs on
  every pull request and its `apple-publication` job covers `macosArm64Test`, both iOS targets, local
  publication and the clean six-target consumer. `publish.yml` triggers only on push to `main` or explicit
  dispatch, so a pull request cannot consume a version or reach R2. Such branches are disposable; say so in
  the pull request body.

Spike code is deliberately throwaway and lives outside the repository. The findings documents in
`docs/research/` are the durable record; if a spike needs re-running, they say what it did.

## Publication boundary

Pushing a development branch is a recovery checkpoint, not permission to merge, dispatch publication,
upload to R2, or claim a public release. Cycle A's immutable public `0.1.0` record remains historical.
Cycle B is neither released nor complete as a released cycle.
