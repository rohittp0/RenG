# RenG Cycle B handoff — 2026-08-17

This is the durable recovery point for the next agent. Cycle A is publicly complete. Cycle B's owner-approved public-API/pure-core implementation is partially complete on branch
`docs/cycle-b-resource-contract` and has been pushed to `origin`.

The implementation checkpoint immediately before this handoff commit is
`2f5dd211d3eac3e5c4774e07c341943e569b382d`. The handoff commit itself should be the branch's current HEAD.

## Read first

1. `CLAUDE.md` — repository constraints, purity contract, six targets, commands, and publication rules.
2. `CONTEXT.md` — canonical vocabulary.
3. `docs/adr/0001`–`0018` — newer ADRs override older prose.
4. `docs/superpowers/specs/2026-08-17-cycle-b-public-api-pure-core-design.md` — owner-approved Cycle B authority, approved at `11d7a03`.
5. `docs/superpowers/plans/2026-08-17-cycle-b-public-api-pure-core.md` — independently reviewed implementation plan, committed with its canonical fixture at `4ff05ee`.
6. This handoff, then the relevant implementation and tests.

The specification and plan are already approved. Do not re-run grilling or write another plan unless repository-owner review explicitly reopens a contract.

## Binding scope

Cycle B is pure core only. It includes public immutable values/protocols/failures, canonical identities and SHA-256, spatial/diff planning, and pure lifecycle/resource/preparation reducers driven by supplied values.

Do not add a renderer factory, consumer adapter call, Rentile acquisition, decoder/parser, production cache, platform-context or GL call, shader compilation, pixels, retry, repair, fallback, or repeated consumer exchange. Never forward injected adapter messages or causes. Preserve selected cancellation as an opaque, unwrapped cancellation ID/cause value.

The branch must retain exactly six published targets: Android, iOS Arm64, iOS Simulator Arm64, macOS Arm64, Linux x64, and Linux Arm64. No JVM publication, `macosX64`, or `iosX64`. Never commit `mavenLocal()`, a `-SNAPSHOT`, or `local.properties`.

## Completed and independently approved tasks

The following plan tasks were implemented, task-reviewed, fixed where necessary, integrated into this branch, controller-tested, and had their auxiliary worktrees removed:

- Task 0 — approved plan and canonical fixture
- Tasks 1–3 — public values, adapters, and frame model
- Tasks 4A–4C — renderer ownership/API, diagnostics/failures, configuration/protocol
- Task 5 — ABI, policy, consumer smoke, and build firewall
- Tasks 6–7 — canonical binary/SHA-256, identities, encoding, and structural diff
- Tasks 8A–8C — Mercator projection, camera/rays, and closed ground footprint
- Tasks 9A–9C — LOD/tile selection, placement/geometry, and shader-profile scanning
- Task 11 — renderer lifecycle reducer
- Tasks 12A–12C — preregistration, frontier scheduling, ordered retirement, and terminal arbitration

Important completed hardening includes:

- dependency-free common Kotlin SHA-256 without a full-message copy;
- exact retained camera geographic anchor rather than inverse-projection reconstruction;
- exact epsilon tile admission and bounded row/edge counting for full-support LOD 22;
- scanner rejection of unterminated block comments anywhere in shader source;
- linear 4,096-child frontier scheduling;
- route/external cancellation-channel invariants;
- repeat private-key collision attribution without duplicate terminal buffering;
- complete assigned-ordinal and ordered-buffer invariants;
- unresolved discovery cannot be bypassed by `RouteCompleted(Success)`.

## Implemented but review-pending

The repository owner explicitly instructed the prior agent to stop starting reviews and hand these off. Do not treat either task as review-approved yet.

### Task 9D — integrated Mercator spatial planning

Controller commits:

- `de7f714` — `feat: integrate Mercator spatial planning`
- `fdd9c59` — `fix: enforce Mercator spatial plan invariants`

The initial independent review found the generated planner path correct but reported that direct `MercatorSpatialPlan` construction admitted contradictory state. The fix now enforces:

- footprint and tile selection are jointly absent or jointly present;
- geometry/profile list cardinality matches;
- each geometry's exact vertex/fragment source matches its same-index profile pair;
- map entries use `MAP_OCCLUDED` and screen entries use `SCREEN_COMPOSITED`.

The implementation worker ran focused, retained, full Android/macOS, native compilation, and AAR gates after the fix. **First next action:** perform a scoped independent re-review of `de7f714..fdd9c59` against Task 9D and the approved spec. Verify the direct-constructor RED controls, source pairing, nullability, draw regimes, copies/equality, and no regression to basemap suppression or failure order.

The initial reviewer also made a Minor observation that the suppression test cannot detect deliberately computing then discarding footprint/tile work. The prior ruling parked it: production branches before that work, the otherwise-over-budget suppressed fixture catches behavioral accidental execution, and an injected instrumentation seam would exceed the exact approved Task 9D interface. Reopen only with a concrete failure.

### Task 13 — resource lookup and response rules

Controller commit:

- `2f5dd21` — `feat: add resource lookup decisions`

This is a large unreviewed task: five files and roughly 2,390 added lines. It implements pure lookup actions/events/cursors, strict freshness, resident/Store/Transport decisions, closed transport latches, stored-record integrity, response validation, 200 formation, 304 merge, provenance, and transition correlation. It stops successful content at `PendingClassGates`; Task 14A still exclusively owns class gates, writes, and visibility.

The worker reports controls for:

- strict freshness `freshUntil > sample`;
- NORMAL/CACHE_ONLY/RELOAD tables;
- invalid nonnull Store records failing terminally with Store provenance;
- no stale fallback, retry, repair, remove, or repeated exchange;
- exact ETag/last-modified/unconditional request rules;
- metadata-before-status/body response precedence;
- empty/oversized 200 and nonempty 304 handling;
- 304 requiring conditional NORMAL plus a stale valid validator-bearing baseline;
- defensive body/latch/list copies;
- opaque ADAPTER-only supplied cancellation;
- positive monotonic action IDs and exact cursor/event correlation;
- one transport call for 4,096 joined occurrences;
- no lookup start after terminal selection.

**Second next action:** independently review only `fdd9c59..2f5dd21` against Task 13 and the approved spec before starting Task 14A. This needs a high-judgment resource-state-machine review, especially malformed state admission, freshness/provenance, response precedence, cancellation, latch identity/replay, action correlation, one-exchange guarantees, linear complexity, and redaction. Do not infer approval from green tests.

## Remaining implementation order

After both pending reviews are approved and any fixes are integrated:

1. Task 10 — integrated pure frame planning (blocked only by Task 9D approval).
2. Task 14A — ordinary class gates, write outcomes, and visibility (blocked by Task 13 approval).
3. Task 14B — sprite pair commit and parked scheduling.
4. Task 14C — style staging/compile barriers and visibility.
5. Task 15 — ordered preparation reducer (requires Task 10 and Task 14C).
6. Task 16 — cross-engine tests.
7. Task 17 — final documentation/status and complete gates.

Task 10 and Task 14A can run in parallel after their separate review dependencies close. Tasks 14A → 14B → 14C remain ordered.

Use fresh isolated workers only for genuinely parallel file-mutating tasks. Integrate each approved commit into `docs/cycle-b-resource-contract`, archive needed results in the controller, and remove its auxiliary worktree/branch. Keep at most ten workers, per repository-owner instruction.

## Fresh verification at the handoff checkpoint

After integrating Tasks 9D and 13, the controller ran:

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
  -s tools/tests -p 'test_*.py' -v
PYTHONDONTWRITEBYTECODE=1 python3 tools/check_repository_policy.py --root .
./gradlew --no-configuration-cache \
  :kmp:checkKotlinAbi \
  :kmp:testAndroidHostTest \
  :kmp:macosArm64Test \
  :kmp:compileKotlinIosArm64 \
  :kmp:compileKotlinIosSimulatorArm64 \
  :kmp:compileKotlinMacosArm64 \
  :kmp:compileKotlinLinuxX64 \
  :kmp:compileKotlinLinuxArm64 \
  :kmp:bundleAndroidMainAar
git diff --check d86a4b9e716cb6d40e6a3522cdc43a2dbf500682..HEAD
```

Observed results:

- 73 Python tests passed.
- `Cycle B repository policy passed`.
- ABI check passed.
- Full Android host and macOS Arm64 suites passed.
- Both iOS, macOS, and both Linux compilation gates passed.
- Android AAR gate passed.
- Diff check passed.
- No Linux runtime test was claimed on macOS.

These commands verify integration/build state, not the two pending code-review gates.

## Fresh-device checkout and environment setup

The next agent is expected to run on a different device. None of the absolute paths, ignored SDD ledger/reports, Gradle caches, or worktrees from the source machine will exist there. All required source, tests, specification, plan, decisions, and recovery instructions are committed on the remote branch.

Start from a fresh clone:

```bash
git clone --branch docs/cycle-b-resource-contract --single-branch \
  https://github.com/rohittp0/RenG.git
cd RenG
git status --short
git rev-parse HEAD
git log -5 --oneline
```

`git status --short` must be empty. If the repository already exists on the new device, stop if it has local changes, then fetch and switch without resetting or discarding anything:

```bash
git fetch origin
git switch docs/cycle-b-resource-contract 2>/dev/null || \
  git switch --create docs/cycle-b-resource-contract \
    --track origin/docs/cycle-b-resource-contract
git pull --ff-only
git status --short
```

Required local tools:

- Git.
- A 64-bit JDK 21. Android host compilation targets JVM 21.
- Python 3; repository tools use only the standard library.
- Android SDK Platform 37. Set its path in untracked `local.properties`, for example:

  ```properties
  sdk.dir=/absolute/path/to/Android/sdk
  ```

- The checked-in Gradle 9.5.0 wrapper; do not substitute a system Gradle.
- Network access on the first build for the Gradle distribution, Kotlin/Native toolchain, Google Maven, Maven Central, and `https://maven.rohittp.com`.
- For complete local Apple gates: Apple Silicon macOS with Xcode and Command Line Tools installed. The project deliberately has no Intel macOS/iOS simulator targets.
- On Ubuntu x64, use the Linux CI command below; Apple targets cannot be built there. Linux Arm64 is a compile-only cross-target gate from x64.

No Docker, R2/AWS credentials, signing key, Firebase setup, publication secret, or `mavenLocal()` repository is needed for the pending Cycle B reviews and pure-core tasks. Do not create or commit `local.properties` beyond the local machine.

Sanity-check the fresh environment before reviewing code:

```bash
java -version
python3 --version
./gradlew --version
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
  -s tools/tests -p 'test_*.py' -v
PYTHONDONTWRITEBYTECODE=1 python3 tools/check_repository_policy.py --root .
```

On Apple Silicon macOS, run the locally complete implementation gate:

```bash
./gradlew --no-configuration-cache \
  :kmp:checkKotlinAbi \
  :kmp:testAndroidHostTest \
  :kmp:macosArm64Test \
  :kmp:compileKotlinIosArm64 \
  :kmp:compileKotlinIosSimulatorArm64 \
  :kmp:compileKotlinMacosArm64 \
  :kmp:compileKotlinLinuxX64 \
  :kmp:compileKotlinLinuxArm64 \
  :kmp:bundleAndroidMainAar
```

On Ubuntu x64, use the host-executable CI subset instead:

```bash
./gradlew --no-configuration-cache \
  :kmp:checkKotlinAbi \
  :kmp:testAndroidHostTest \
  :kmp:linuxX64Test \
  :kmp:compileKotlinLinuxArm64 \
  :kmp:bundleAndroidMainAar
```

Do not claim `linuxX64Test` from macOS or `macosArm64Test` from Linux. Kotlin/Native's first invocation may download toolchains and take substantially longer than later runs.

The ignored `.superpowers/sdd/...` ledger, extracted task briefs, and `/tmp` worker reports are intentionally not transportable. The tracked plan contains every task requirement. If the next agent uses the Superpowers SDD workflow, initialize a new local ledger from this handoff and regenerate Task 9D/13 briefs from the tracked plan rather than looking for source-machine scratch files. The exact pending review ranges are recorded above.

## Git and source-machine worktree state

All completed Task 9D and Task 13 worker commits were cherry-picked into `docs/cycle-b-resource-contract` before their auxiliary worktrees and branches were deleted. At handoff creation, no `agent-*` worktree remained.

The source machine's live Claude session was pinned to:

`/Users/rohittp/Data/Other/RenG/.claude/worktrees/cycle-a-implementation`

That absolute path and worktree are irrelevant on the new device and do not need to be recreated. Its stale directory name contained the Cycle B branch, not unintegrated Cycle A work. The remote branch is the authority for cross-device continuation.

The source machine's primary checkout remained on its pre-existing branch and was not merged or modified by this handoff. No PR, merge, workflow dispatch, R2 upload, publication, or release was performed.

## Publication boundary

Pushing this development branch is authorized as a recovery checkpoint. It is not permission to merge, dispatch publication, upload to R2, or claim a public release. Cycle A's immutable public `0.1.0` record remains historical; Cycle B is not complete or released.
