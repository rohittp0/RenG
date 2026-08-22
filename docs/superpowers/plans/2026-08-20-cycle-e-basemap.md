# Cycle E (basemap half) — Drawing the Rentile Ground Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Draw the map — decode Rentile's rendered tiles, upload them, and draw them as the ground beneath everything Cycle F-1 draws.

**Architecture:** Five Cycle C tasks that turned out to be basemap work are inherited rather than rewritten. On top of them, this cycle adds tile decode and upload, byte-budgeted GPU residency on `GlObjectRegistry`, and the ground draw inside ADR 0024's map regime.

**Tech Stack:** Kotlin Multiplatform, six targets, `com.rohittp.rentile:kmp` as the basemap engine behind an operation-scoped firewall.

**Spec:** `docs/superpowers/specs/2026-08-20-cycle-e-basemap-design.md`

## Global Constraints

- Keep exactly six targets. Keep `explicitApi()`. This cycle grows the public ABI by **one** `ResourceLimits` field — the resident GPU texture byte budget — and nothing else.
- Add no new Gradle subproject and no cinterop definition on any target.
- Standard library plus `kotlinx-coroutines` only, alongside the existing `com.rohittp.rentile:kmp` dependency. `kotlinx.serialization`, `okio`, and any other third-party dependency are FORBIDDEN.
- **RenG is pure.** It performs no network I/O and owns no persistent cache. The consumer injects transport and store adapters, which RenG proxies down to Rentile through the firewall.
- **Never forward messages or causes from injected adapters** — they can carry signed URLs. `SuppliedCallOutcome.Failed` is a zero-field `data object` and `Diagnostic` has no free-text field; keep both properties.
- Keep cancellation as an unwrapped `CancellationException`, checked and rethrown before any generic catch.
- No retries, repairs, or fallbacks. Rentile's private retry calls replay a latched outcome; the caller owns recovery.
- Every Gradle invocation passes `--no-configuration-cache`.
- `RecordingGlBinding.getUniformLocation` returns `-1` for undeclared names, so a test that forgets to declare a name sees zero calls and **can pass while asserting nothing.** Two gathers in Cycle F-1 hit exactly that. Declare every name you expect bound, and never assert `assertEquals(before, captureGlState(...))` — against that fake it cannot fail.
- `ResidentCache` uses a **non-reentrant** spinlock: never call a `locked{}` method from inside the lock, or it hangs forever rather than failing.

---

## Task 0: Preflight-scan the five inherited Cycle C task plans

The five inherited tasks were written against Cycle C's context and have never been executed. Cycle C's plan
carried real defects that execution caught — a self-contradiction between two of its own sections, and a
latch-replay test whose fixture structurally could not exercise replay. Scanning before executing is cheaper
than discovering mid-task.

**Files:** none modified. Output is a written scan.

- [ ] **Step 1: Extract the five task texts**

Tasks 14, 16, 17, 18 and 19 from `docs/superpowers/plans/2026-08-18-cycle-c-resource-layer.md`.

- [ ] **Step 2: Build the conflict table**

One row per pair of tasks sharing a file or an interface: the two tasks, what one produces against what the
other consumes, and what you found. One row per task: whether its own text agrees with itself — the tests it
specifies against the code it specifies, the files it creates against the files it later touches.

**"The scan is clean" without those rows is not a scan.**

- [ ] **Step 3: Check each against the tree as it now stands**

These plans assume Cycle C's tree. Since then Cycle D, Cycle F-1 and two integrations have landed. For each
task, verify every type, function and file it names still exists with the signature it assumes. Record every
mismatch — a plan referencing a signature that has since changed will fail at implementation time, and
finding it now costs minutes instead of a task.

Pay particular attention to `ResidentCache`, whose canonical implementation replaced a stand-in during
integration, and to `ResourceActionExecutor`, which gained a `classGateRunner` parameter and a `CancelRoute`
branch after those plans were written.

- [ ] **Step 4: Rule on every finding and record it**

The spec is the binding authority and the plan argues from it. Record each ruling beside its row. Do not
begin any inherited task before its row is ruled.

---

## Task 1: The resident GPU texture byte budget

**Files:**
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/Resources.kt` (the `ResourceLimits` field)
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/GlObjectRegistry.kt`
- Modify: `kmp/api/kmp.klib.api` (regenerated, reviewed as a diff)
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/gl/GlObjectRegistryTest.kt`

**Interfaces:**
- Produces: a `ResourceLimits` byte-budget field, and registry retention of unleased GL textures up to that budget with least-recently-used eviction beyond it.

- [ ] **Step 1: Write the failing residency tests**

The property that matters is that a tile survives losing its lease, because that is what makes panning cheap.

```kotlin
@Test
fun anUnleasedTextureStaysResidentWhileTheBudgetAllows() {
    val registry = registryWithBudget(bytes = 4 * ONE_TILE_BYTES)
    val handle = registry.register(tileKey(0), textureOf(ONE_TILE_BYTES))
    registry.releaseLease(handle)
    assertNotNull(registry.resident(tileKey(0)), "a tile must survive losing its lease, or panning re-uploads")
}

@Test
fun theLeastRecentlyUsedUnleasedTextureIsEvictedFirstWhenTheBudgetIsExceeded() {
    val registry = registryWithBudget(bytes = 2 * ONE_TILE_BYTES)
    listOf(0, 1).forEach { registry.releaseLease(registry.register(tileKey(it), textureOf(ONE_TILE_BYTES))) }
    registry.touch(tileKey(0))                                    // 1 becomes least-recently-used
    registry.releaseLease(registry.register(tileKey(2), textureOf(ONE_TILE_BYTES)))
    assertNotNull(registry.resident(tileKey(0)))
    assertNull(registry.resident(tileKey(1)), "the least recently used unleased tile is evicted first")
    assertNotNull(registry.resident(tileKey(2)))
}

@Test
fun aLeasedTextureIsNeverEvictedEvenWhenThatExceedsTheBudget() {
    val registry = registryWithBudget(bytes = ONE_TILE_BYTES)
    val held = registry.register(tileKey(0), textureOf(ONE_TILE_BYTES))   // leased, never released
    registry.releaseLease(registry.register(tileKey(1), textureOf(ONE_TILE_BYTES)))
    assertNotNull(registry.resident(tileKey(0)), "a live PreparedFrame's tile must outrank the budget")
}
```

The third test is the load-bearing one. The budget governs what *may* stay, never what *must*: a tile leased
by a live `PreparedFrame` cannot be evicted, and exceeding the budget is the correct outcome rather than
breaking a frame that is still drawable.

- [ ] **Step 2: Run and confirm each fails for the stated reason, then implement**

Eviction deletes the GL texture and removes the entry. Deletion requires the renderer's exact GL context to
be current per ADR 0015 — evicting from a path where it is not would violate that, so evict only from
operations already known to hold the context.

- [ ] **Step 3: Prove context loss costs a re-upload, not a re-fetch**

This is the whole reason residency lives here rather than on `ResidentCache`.

```kotlin
@Test
fun contextLossForgetsTexturesWhileTheDecodedImageStaysLeased() {
    val world = residencyWorld()
    world.registry.forgetEverything()
    assertNull(world.registry.resident(tileKey(0)), "the GL name is meaningless after context loss")
    assertNotNull(world.residentCache.current(tileKey(0)), "the decoded pixels are still valid and leased")
}
```

- [ ] **Step 4: Mutation-check the budget**

Remove the eviction call entirely and confirm the LRU test fails; remove the leased-entry guard and confirm
the third test fails. Report both observations. A budget nothing enforces is worse than none, because it
reads as bounded.

- [ ] **Step 5: Regenerate and review the ABI**

Expected: exactly one new `ResourceLimits` field with its accessor and the `copy`/`componentN` follow-on.
Anything else in that diff is out of scope.

- [ ] **Step 6: Commit**

```bash
git commit -m "feat(kmp): bound resident GPU texture memory by bytes"
```

---

## Task 2: Rulings from the preflight scan — read before any inherited task

Task 0's scan returned **3 blocking, 4 major, 4 minor and 2 informational** findings against the five
inherited Cycle C tasks. The full table is at
`.superpowers/sdd/2026-08-20-cycle-e-basemap/preflight-scan.md`. These rulings resolve the blocking ones and
bind every inherited task. **No inherited task may be dispatched before its ruling is applied.**

### The stated execution order is wrong and is hereby replaced

The scan found that Task 14 (sprite pair and basemap style commits) is numbered first but **cannot complete
until Task 19 exists**: its own prose says `CompileBasemapStyle` "hands the staged bytes to the engine",
while `ResourceActionExecutor`'s constructor takes `transport, store, cache, classGateRunner, clock` and no
engine host — and neither task adds one. Task 14's declared `Consumes` list does not even name the engine,
the firewall adapters, or the failure classifier its own implementation text requires.

**Execute in this order instead: 16 → 17 → 18 → 19 → 14.** Key resolver, then firewall adapters, then
failure classification, then the engine host, and only then the commits that hand bytes to it.

### No task wires the engine host into the driver — Task 19 now owns that

Task 19 creates `BasemapEngineHost` and unit-tests it in isolation, and nothing threads it into
`ResourceActionExecutor`. That gap is why the order above still would not have been sufficient on its own.

Task 19 additionally threads the engine host into `ResourceActionExecutor`'s constructor and wires the six
engine-validated gate/class combinations that `RenGClassGateRunner` currently reaches with `error(...)`.
Those `error(...)` calls were deliberate — an exception on an unreached path cannot be mistaken for
enforcement the way an always-succeeding placeholder could — and replacing them is exactly what makes the
firewall real. **Removing an `error(...)` without wiring a genuine outcome behind it would be the worst
possible resolution**, so each removal needs a test that fails if the gate stops enforcing.

### Task 19's tile identity must be rebuilt against types that exist

`TileCoordinate(zoom, x, y)` appears nowhere in the tree. Cycle D landed the real tile-identity types
instead: `BasemapTileInstance` and `CanonicalBasemapTile(lod, tileY, canonicalX)`, already echoed by
`BasemapSourceMember.Tile` in the protocol.

Rebuild `basemapTileKey`'s signature and its canonical-encoding sample against `CanonicalBasemapTile`, and
**decide explicitly whether identity is pre- or post-world-copy-dedup.** `CONTEXT.md` says a canonical tile
"may back multiple unwrapped world-copy draw instances" and that instances are deduplicated only after
unwrapped draw instances and the Tile Budget are determined — so keying on the canonical tile rather than the
instance is almost certainly right, but it must be a decision with a recorded reason, not an accident of
whichever type was nearest.

### Task 19 must use the atomic lease methods, not the two-step sequence it describes

Its "bind to the style's current resident generation" prose describes exactly the `current()`-then-
`takeLease()` race that `ResidentCache.installAndTakeLease`/`observeAndTakeLease` were added to close: a
generation with no lease can be dropped by a racing `free()` in the gap between the two calls. Cycle C Task
13's already-landed `installVisibility` uses the atomic path. Task 19 must too.

### Two inherited tests claim more than they exercise

- Task 16's `reproducesTheEngineDerivationForTheSevenClassesItKeys` exercises **one** class, not seven.
  Either exercise all seven or rename it to what it does — a name promising sevenfold coverage over a single
  case is exactly the pattern this project has corrected nineteen times.
- Task 14's `discoversAStylesChildrenAfterItsOwnRouteCompletes` asserts only overall success and never checks
  the discovered children. Assert the children, or the test cannot fail for the reason its name gives.

### A second deletion path that must learn the same discipline before tiles are freed

Task 1's eviction is provably leak-proof against leased entries — eviction only ever iterates the unleased
candidate set, so a leased texture is structurally unreachable. But a reviewer found that `defer(key, id)`
is a **second deletion path in the same class** and does *not* check `textureLeaseCounts` before removing the
key from `live` and queuing its handles for deletion.

Nothing calls `registerTexture` and `defer` together today, so there is no live bug. It becomes one the
moment consumer-triggered `freeResources()` is wired onto budget-tracked tiles — which is this cycle's tile
work. `ResidentCache.free()` already establishes the correct precedent for exactly this situation: it
*retires* a still-leased generation rather than deleting it.

Whichever task wires consumer-facing free onto tiles must either release the lease first or teach `defer()`
the same leased-survives discipline, and must prove it with a test that fails if a leased tile is deleted.

### One finding that is good news, recorded so nobody re-checks it

Rentile's `Api.kt` and `Resources.kt` are **byte-identical** between the 0.1.x commit these tasks were
measured against and the `0.2.0` that RenG's build now pins — verified by direct `git diff` in the local
Rentile checkout. The measurements underlying ADR 0016's firewall design therefore still hold, and the
counting-stub respike against 0.3.0 confirmed the behavioural claims independently.

---
