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
