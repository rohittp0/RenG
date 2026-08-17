# Cycle B Public API and Pure Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish the approved Cycle B API and implement its dependency-free, network-free, and GL-free planning and decision core for all six RenG targets.

**Architecture:** Public immutable values and sealed ownership protocols live directly in `com.rohittp.reng`; focused internal packages implement canonical identity, structural diff, Double-only spatial planning, and three event-driven pure state machines. The state machines consume immutable supplied observations/outcomes and emit immutable actions, leaving real adapters, Rentile acquisition, decoding/parsing, caches, context discovery, GL, shader compilation, and pixels to later cycles.

**Tech Stack:** Kotlin Multiplatform 2.3.21, Gradle 9.5.0, AGP 9.3.1, `kotlin.test`, Kotlin ABI validation, dependency-free common Kotlin.

**Spec:** `docs/superpowers/specs/2026-08-17-cycle-b-public-api-pure-core-design.md`

## Global Constraints

- The approved specification at commit `11d7a03` is authoritative; newer ADRs win over older ADR wording.
- Keep exactly one published module, `:kmp`, and package public declarations under `com.rohittp.reng`.
- Keep exactly six targets: `android`, `iosArm64`, `iosSimulatorArm64`, `macosArm64`, `linuxX64`, and `linuxArm64`.
- Add no JVM, `macosX64`, or `iosX64` target and no new Gradle subproject.
- Keep `com.rohittp.rentile:kmp:0.1.5` as an `implementation` dependency; expose no Rentile or platform type in public ABI.
- Add no runtime dependency, serialization library, crypto library, Ktor, Skiko, Wire, or coroutine library.
- Keep `explicitApi()` and Kotlin ABI validation enabled.
- Public plan/resource/adapter DTO/selector/diagnostic/report values have structural equality; `RendererConfiguration`, exceptions, adapters/sinks, and ownership protocols retain identity semantics exactly as specified.
- Canonicalize every public floating negative zero before assignment; reject non-finite or out-of-range values rather than clamping, wrapping, or repairing.
- Snapshot every `List`/`ByteArray` constructor input. Every public getter returns a fresh copy that cannot mutate backing state, including empty and singleton lists on Android/JVM.
- Preserve exact Unicode scalar content with no normalization and reject isolated UTF-16 surrogates.
- Never expose locator, shader, validator, metadata, adapter message/cause, or bytes in diagnostics, exceptions, or textual representations.
- Keep `RenGException.message` exactly `RenG failure: <CODE> at <STAGE>` and its cause null.
- Propagate selected `CancellationException` outcomes as cancellation rather than converting them to `RenGException`; pure reducers carry only an opaque `CancellationId` plus closed cause category, while the future integration layer retains the ID-to-original-exception mapping and rethrows the deterministically selected exception.
- Expose no renderer factory, public implementation, top-level `createRenderer`, `RenG` construction object, context parameter, resource acquisition, decoder/parser, production cache, GL call, shader compilation, or pixel behavior.
- Implement pure state engines as reducers over immutable observations/outcomes. No state-engine protocol may accept `Transport`, `Store`, Rentile, decoder/parser, clock implementation, context API, framebuffer API, or GL implementation.
- Use only Double for planning. Float conversion is a representability check and never feeds projection, clipping, history, culling, footprint, or tile selection.
- Keep canonical field tags, wire widths, enum values, root kinds, exact Resource Locator payload encoding, and hash prefixes exactly as specified; never use Kotlin enum ordinals.
- Preserve the operation-scoped Route Key firewall, static identity-only preregistration, depth-first discovery-frontier release, eligibility-time ordinals, ordered failure arbitration, one freshness sample, and exact lookup/write provenance.
- Keep `VERSION_NAME` in root `gradle.properties` as the sole checked-in version input; never add `mavenLocal()` or a snapshot dependency.
- Every Gradle command uses `--no-configuration-cache`; fresh consumer smoke also uses a new Gradle home and `--refresh-dependencies`.
- Do not edit historical Cycle A design or plan documents.
- Keep `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/RentileLinkage.kt` and its test until a later production path actually replaces their dependency-linkage purpose.

---

## File Structure and Ownership

### Public root package

| File | Responsibility |
|---|---|
| `kmp/src/commonMain/kotlin/com/rohittp/reng/SpatialValues.kt` | `OutputPixelSize`, `AnchoringMode`, `Vector3`, `Camera`, `Placement` |
| `kmp/src/commonMain/kotlin/com/rohittp/reng/Resources.kt` | locator, resource enums, access mode, limits |
| `kmp/src/commonMain/kotlin/com/rohittp/reng/ResourceAdapters.kt` | transport/store DTOs and consumer protocols |
| `kmp/src/commonMain/kotlin/com/rohittp/reng/DrawnThings.kt` | stickers, models, animation, shader pair, geometry |
| `kmp/src/commonMain/kotlin/com/rohittp/reng/FramePlan.kt` | projection mode and frame plan |
| `kmp/src/commonMain/kotlin/com/rohittp/reng/Diagnostics.kt` | stages, diagnostics, sink |
| `kmp/src/commonMain/kotlin/com/rohittp/reng/Exceptions.kt` | error codes and typed exception |
| `kmp/src/commonMain/kotlin/com/rohittp/reng/ResourceReports.kt` | resource keys, selectors, reports, free result |
| `kmp/src/commonMain/kotlin/com/rohittp/reng/Renderer.kt` | configuration and sealed renderer/frame/target protocols |
| `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/ValueSupport.kt` | scalar/string validation and always-fresh copies |
| `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/DiagnosticFactories.kt` | allowlisted diagnostic/failure creation |

### Internal pure core

| Package | Files |
|---|---|
| `internal.identity` | `CanonicalBytes.kt`, `CanonicalBinary.kt`, `Sha256.kt`, `IdentityRegistry.kt`, `FramePlanCanonicalEncoding.kt`, `ResourceKeyDerivation.kt` |
| `internal.diff` | `FrameStructuralDiff.kt`, `ResourceTraversalDiff.kt` |
| `internal.math` | `DoubleLinearAlgebra.kt` |
| `internal.projection` | `MercatorProjection.kt`, `CameraMatrices.kt`, `MercatorGroundFootprint.kt` |
| `internal.planning` | `SpatialPlanningResult.kt`, `MercatorLod.kt`, `BasemapTileSelector.kt`, `GpuRepresentability.kt`, `PlacementResolver.kt`, `GeometryResolver.kt`, `MercatorSpatialPlanner.kt`, `FramePlanningCore.kt` |
| `internal.shader` | `ShaderProfilePlanner.kt` |
| `internal.failure` | `FailureDescriptor.kt` |
| `internal.lifecycle` | `RendererLifecycleProtocol.kt`, `RendererLifecycleStateMachine.kt` |
| `internal.resource` | `RentilePrivateKey.kt`, `ResourceOperationProtocol.kt`, `ResourceResponseRules.kt`, `ResourceOperationStateMachine.kt` |
| `internal.preparation` | `OrderedPreparationProtocol.kt`, `OrderedPreparationStateMachine.kt` |

Every production file has a mirrored focused `commonTest` file. Android/JVM interop mutation tests live in `kmp/src/androidHostTest/kotlin/com/rohittp/reng/JvmDefensiveCopyTest.kt`.

## Parallel Execution Map

Use isolated worktrees only for these parallel waves. Each worker owns only its listed production/test files, commits them, and returns the commit SHA. The controller reviews and cherry-picks each commit before the next wave.

```text
Task 0 freeze exact worker base
  └─ Task 1 shared public foundation
       └─ Tasks 2 and 3 in parallel
            └─ Tasks 4A → 4B → 4C (serial public surface)
                 ├─ Task 5 ABI/policy/smoke
                 ├─ Task 6 identity primitives
                 ├─ Tasks 8A → 8B → 8C (serial projection lane)
                 └─ Task 11 lifecycle reducer

After Task 6:
  ├─ Task 7 frame/resource identities and diff
  └─ Tasks 12A → 12B → 12C → 13 → 14A → 14B → 14C

After Task 8C:
  ├─ Task 9A LOD/tiles
  ├─ Task 9B placement/geometry (also needs 8B)
  └─ Task 9C shader planning
       └─ Task 9D after 9A, 9B, and 9C
            └─ Task 10 after 7 and 9D

Task 15 after Task 10 and Task 14C
Task 16 after Task 11 and Task 15
Task 17 after Task 5 and Task 16
```

At most three independent implementation workers run at once. A practical schedule is: `(6,8A,11)`, then `(7,8B,12A)`, then `(8C,12B,5)`, then `(9A,9B,9C)`, then `(9D,12C)`, then `(10,13)`, followed by the serial `14A→14B→14C→15→16→17` joins. Each dependent dispatch uses a new exact controller SHA after its prerequisites are reviewed and incorporated.

No two workers edit the same public root-package file, ABI dump, policy tool, consumer smoke, build script, documentation status file, or shared fixture file.

---

### Task 0: Freeze the Execution Base and Clean Prior Auxiliary Worktrees

**Files:**
- Verify tracked: `docs/superpowers/plans/2026-08-17-cycle-b-public-api-pure-core.md`
- Verify tracked: `docs/superpowers/specs/2026-08-17-cycle-b-canonical-frame-v1-test-vector.txt`
- Modify: none.

**Interfaces:**
- Consumes: the committed, independently reviewed plan and approved specification commit `11d7a03`.
- Produces: one exact clean controller SHA used as the base of every worker in the next wave.

- [ ] **Step 1: Require the reviewed artifacts to be committed**

```bash
git ls-files --error-unmatch \
  docs/superpowers/plans/2026-08-17-cycle-b-public-api-pure-core.md \
  docs/superpowers/specs/2026-08-17-cycle-b-canonical-frame-v1-test-vector.txt
git merge-base --is-ancestor 11d7a03 HEAD
test -z "$(git status --porcelain)"
```

If any command fails, stop before implementation and commit/review the missing documentation rather than giving workers an untracked side channel.

- [ ] **Step 2: Remove only completed auxiliary agent worktrees**

```bash
git worktree list --porcelain
```

Remove completed `.claude/worktrees/agent-*` entries only after confirming each is clean and its result is already incorporated. Keep this host-pinned controller worktree. Do not remove the primary checkout or any worktree with uncommitted/unique work.

- [ ] **Step 3: Capture and enforce each wave base**

```bash
WAVE_BASE="$(git rev-parse HEAD)"
printf '%s\n' "$WAVE_BASE"
```

Every isolated worker starts by running, in its clean temporary branch:

```bash
git merge --ff-only "$WAVE_BASE"
test "$(git rev-parse HEAD)" = "$WAVE_BASE"
test -z "$(git status --porcelain)"
```

The controller includes the literal `WAVE_BASE` SHA in each dispatch. After reviewing and cherry-picking a worker commit, capture a new base for dependent waves. Never let more than three implementation workers mutate files concurrently; when a wave lists four independent tasks, start the fourth only after one slot finishes, from the same declared wave base if it has no dependency on the finished lane.

---

### Task 1: Shared Validation, Spatial Values, and Resource Vocabulary

**Files:**
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/ValueSupport.kt`
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/resource/RentilePrivateKey.kt`
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/SpatialValues.kt`
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/Resources.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/resource/RentilePrivateKeyTest.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/SpatialValuesTest.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/ResourcesTest.kt`

**Interfaces:**
- Consumes: only Kotlin standard library.
- Produces: public `AnchoringMode`, `ResourceLocator`, `OutputPixelSize`, `Vector3`, `Camera`, `Placement`, `ResourceClass`, `ResourceKind`, `ResourceAccessMode`, `ResourceLimits`; internal scalar/string/copy helpers, class mappings, and the opaque shape-redacted `RentilePrivateKey`/`RentilePrivateKeyResolver` seam shared by pure planning and resource admission.

- [ ] **Step 1: Write scalar and Unicode tests**

```kotlin
package com.rohittp.reng

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SpatialValuesTest {
    @Test fun canonicalizesNegativeZeroBeforeAssignment() {
        val vector = Vector3(-0.0, -0.0, -0.0)
        assertEquals(0L, vector.x.toBits())
        assertEquals(0L, vector.y.toBits())
        assertEquals(0L, vector.z.toBits())
    }

    @Test fun cameraBoundariesAreClosedOrOpenExactlyAsSpecified() {
        Camera(-90.0, -0.0, 0.0, 0.0, 0.0)
        Camera(90.0, 1.0, 22.0, 359.999, 89.999)
        assertFailsWith<IllegalArgumentException> { Camera(90.0001, 0.0, 0.0, 0.0, 0.0) }
        assertFailsWith<IllegalArgumentException> { Camera(0.0, 0.0, 22.0001, 0.0, 0.0) }
        assertFailsWith<IllegalArgumentException> { Camera(0.0, 0.0, 0.0, 360.0, 0.0) }
        assertFailsWith<IllegalArgumentException> { Camera(0.0, 0.0, 0.0, 0.0, 90.0) }
    }

    @Test fun outputProductUsesExactLongArithmetic() {
        OutputPixelSize(1, Int.MAX_VALUE)
        assertFailsWith<IllegalArgumentException> { OutputPixelSize(46_341, 46_341) }
    }
}
```

Add table-driven cases for NaN/infinities, rotation `[-180, 180)`, scale `[0,+∞)`, all anchoring combinations, exact structural equality/hash, and non-sensitive constructor messages. Add `ResourcesTest` cases for blank/isolated-surrogate locators, exact locator equality, exact `ResourceLocator(<redacted>)`, all enum members, and every limit at `1`, `Int.MAX_VALUE`, `0`, and `Int.MAX_VALUE + 1L`. Add `RentilePrivateKeyTest` cases proving exact structural token equality, blank/isolated-surrogate rejection, a shape-only `toString()` that never contains the token, and a fake resolver that can deliberately map two distinct Route inputs to one equal private key without invoking Rentile.

- [ ] **Step 2: Run the focused tests and verify red**

Run:

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.SpatialValuesTest" \
  --tests "com.rohittp.reng.ResourcesTest" \
  --tests "com.rohittp.reng.internal.resource.RentilePrivateKeyTest"
```

Expected: compilation fails because the public values do not exist.

- [ ] **Step 3: Implement shared helpers and exact public values**

```kotlin
internal fun canonicalDouble(value: Double, field: String): Double
internal fun requireFinite(value: Double, field: String): Double
internal fun requireUnicodeScalars(value: String, field: String, nonBlank: Boolean): String
internal fun <T> freshListCopy(values: List<T>): List<T> = ArrayList(values)
internal fun ByteArray.freshCopy(): ByteArray = copyOf()

internal fun ResourceLimits.maximumBytesFor(resourceClass: ResourceClass): Long
internal val ResourceClass.acceptValue: String
internal val ResourceClass.reportOrder: Int
internal val ResourceKind.reportOrder: Int

internal class RentilePrivateKey(token: String) {
    private val token: String = requireUnicodeScalars(token, "privateRentileKey", nonBlank = true)
    override fun equals(other: Any?): Boolean =
        other is RentilePrivateKey && token == other.token
    override fun hashCode(): Int = token.hashCode()
    override fun toString(): String = "RentilePrivateKey(<redacted>)"
}
internal fun interface RentilePrivateKeyResolver {
    fun resolve(
        locator: ResourceLocator,
        resourceClass: ResourceClass,
    ): RentilePrivateKey
}
```

Implement the exact constructors/properties/defaults from the specification. Regular floating classes validate every argument into locals before assigning properties and implement equality/hash over canonical stored values. `ResourceLocator.toString()` is exactly redacted. `RentilePrivateKey` is an internal opaque equality token for the credential-sanitized private callback key, not a consumer `RawResourceKey`; its resolver is injected pure protocol and Cycle B supplies only fakes—no implementation calls Rentile. Do not perform Mercator-only checks in `Camera`.

- [ ] **Step 4: Run Android and macOS focused tests**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.SpatialValuesTest" \
  --tests "com.rohittp.reng.ResourcesTest" \
  --tests "com.rohittp.reng.internal.resource.RentilePrivateKeyTest"
./gradlew --no-configuration-cache :kmp:macosArm64Test \
  --tests "com.rohittp.reng.SpatialValuesTest" \
  --tests "com.rohittp.reng.ResourcesTest" \
  --tests "com.rohittp.reng.internal.resource.RentilePrivateKeyTest"
```

Expected: both classes pass on both runtimes.

- [ ] **Step 5: Commit**

```bash
git add kmp/src/commonMain/kotlin/com/rohittp/reng/internal/ValueSupport.kt \
  kmp/src/commonMain/kotlin/com/rohittp/reng/internal/resource/RentilePrivateKey.kt \
  kmp/src/commonMain/kotlin/com/rohittp/reng/SpatialValues.kt \
  kmp/src/commonMain/kotlin/com/rohittp/reng/Resources.kt \
  kmp/src/commonTest/kotlin/com/rohittp/reng/internal/resource/RentilePrivateKeyTest.kt \
  kmp/src/commonTest/kotlin/com/rohittp/reng/SpatialValuesTest.kt \
  kmp/src/commonTest/kotlin/com/rohittp/reng/ResourcesTest.kt
git commit -m "feat: add Cycle B value foundation"
```

---

### Task 2: Resource Adapter DTOs and Consumer Protocols

**Files:**
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/ResourceAdapters.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/ResourceAdaptersTest.kt`

**Interfaces:**
- Consumes: Task 1 resource vocabulary and copy/string helpers.
- Produces: exact transport/store DTOs, `Transport`, `Store`, `RawResourceKey`, and internal request construction seams.

- [ ] **Step 1: Write malformed-admission, copying, equality, and redaction tests**

```kotlin
@Test fun responseAdmitsMalformedConsumerValuesButCopiesBytes() {
    val source = byteArrayOf(1, 2, 3)
    val response = TransportResponse(
        statusCode = -7,
        body = source,
        metadata = TransportResponseMetadata(contentType = "\rsecret", freshUntilEpochMillis = -1),
    )
    source[0] = 9
    val returned = response.body
    returned[1] = 9
    assertContentEquals(byteArrayOf(1, 2, 3), response.body)
    assertFalse(response.toString().contains("secret"))
}

@Test fun storedResourceUsesCopiedBytesInEqualityAndHashing() {
    val metadata = StoredRawResourceMetadata(storedAtEpochMillis = -1)
    val first = StoredRawResource(byteArrayOf(1), "bad", metadata)
    val second = StoredRawResource(byteArrayOf(1), "bad", metadata)
    assertEquals(first, second)
    assertEquals(first.hashCode(), second.hashCode())
}
```

Also assert request text reveals only metadata presence, class, and limits; locator/validator/content type/epochs/body never appear. Assert consumer DTO constructors do not reject malformed status, digest, metadata, or epochs.

- [ ] **Step 2: Run and verify red**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.ResourceAdaptersTest"
```

- [ ] **Step 3: Implement the exact adapter surface**

Implement:

```kotlin
public class TransportRequestMetadata internal constructor(
    public val ifNoneMatch: String? = null,
    public val ifModifiedSince: String? = null,
    public val accept: String? = null,
)

public class TransportRequest internal constructor(
    public val locator: ResourceLocator,
    public val resourceClass: ResourceClass,
    public val maximumResponseBytes: Long,
    public val metadata: TransportRequestMetadata = TransportRequestMetadata(),
)

public class TransportResponseMetadata(
    public val contentType: String? = null,
    public val etag: String? = null,
    public val lastModified: String? = null,
    public val freshUntilEpochMillis: Long? = null,
)

public class TransportResponse(
    public val statusCode: Int,
    body: ByteArray,
    public val metadata: TransportResponseMetadata = TransportResponseMetadata(),
) {
    public val body: ByteArray
}

public fun interface Transport {
    public suspend fun execute(request: TransportRequest): TransportResponse
}

@ConsistentCopyVisibility
public data class RawResourceKey internal constructor(
    public val stableId: String,
    public val resourceClass: ResourceClass,
)

public class StoredRawResourceMetadata(
    public val contentType: String? = null,
    public val etag: String? = null,
    public val lastModified: String? = null,
    public val freshUntilEpochMillis: Long? = null,
    public val storedAtEpochMillis: Long,
)

public class StoredRawResource(
    bytes: ByteArray,
    public val contentDigest: String,
    public val metadata: StoredRawResourceMetadata,
) {
    public val bytes: ByteArray
}

public interface Store {
    public suspend fun read(key: RawResourceKey): StoredRawResource?
    public suspend fun write(key: RawResourceKey, resource: StoredRawResource): Unit
}
```

Manual structural equality/hash includes copied arrays. DTO `toString()` implementations disclose only approved shape/presence facts. There is no extra request builder: the two internal constructors are the library-only construction seam.

- [ ] **Step 4: Run cross-runtime tests and commit**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.ResourceAdaptersTest"
./gradlew --no-configuration-cache :kmp:macosArm64Test \
  --tests "com.rohittp.reng.ResourceAdaptersTest"
git add kmp/src/commonMain/kotlin/com/rohittp/reng/ResourceAdapters.kt \
  kmp/src/commonTest/kotlin/com/rohittp/reng/ResourceAdaptersTest.kt
git commit -m "feat: add resource adapter contracts"
```

---

### Task 3: Drawn Things and Immutable Frame Plans

**Files:**
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/DrawnThings.kt`
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/FramePlan.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/DrawnThingsTest.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/FramePlanTest.kt`

**Interfaces:**
- Consumes: Task 1 values and `ResourceLocator`.
- Produces: public `ProjectionMode` and the exact frame/drawn-thing vocabulary plus internal snapshot accessors used by identity/planning.

- [ ] **Step 1: Write constructor and always-fresh list tests**

```kotlin
@Test fun framePlanCopiesInputAndEveryGetterResult() {
    val sticker = Sticker(screenPlacement(), ResourceLocator("sticker"))
    val input = mutableListOf(sticker, sticker)
    val plan = FramePlan(1, camera(), stickers = input)
    input.clear()
    val returned = plan.stickers as MutableList<Sticker>
    returned.clear()
    assertEquals(listOf(sticker, sticker), plan.stickers)
    assertEquals(listOf(sticker, sticker), plan.stickersForCore())
}

@Test fun animationSelectorAndTimeValidationIsExact() {
    AnimationSelector.Index(0)
    AnimationSelector.Name("é")
    assertFailsWith<IllegalArgumentException> { AnimationSelector.Index(-1) }
    assertFailsWith<IllegalArgumentException> { AnimationSelector.Name(" ") }
    assertFailsWith<IllegalArgumentException> {
        AnimationTrack(AnimationSelector.Index(0), Double.NaN)
    }
}
```

Add geometry north/west/span controls, shader nonblank/scalar validation without profile scanning, frame defaults, frame-index non-negativity, duplicates/order preservation, model list copies, and mutation-stable equality/hash. Assert `ShaderPair.toString()` reveals neither source and `Geometry.toString()` is redacted transitively rather than using the generated data-class representation.

- [ ] **Step 2: Run and verify red**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.DrawnThingsTest" \
  --tests "com.rohittp.reng.FramePlanTest"
```

- [ ] **Step 3: Implement exact declarations and core snapshot accessors**

```kotlin
internal fun Model.animationTracksForCore(): List<AnimationTrack>
internal fun FramePlan.stickersForCore(): List<Sticker>
internal fun FramePlan.modelsForCore(): List<Model>
internal fun FramePlan.geometriesForCore(): List<Geometry>
```

Back each list with a private always-fresh `ArrayList` snapshot. Public getters and internal accessors return fresh copies; manual equality/hash reads private snapshots. Keep `Sticker`, selector variants, `ShaderPair`, and `Geometry` structural, but override `ShaderPair.toString()` and `Geometry.toString()` with redacted shape-only text. Keep canonicalizing floating classes regular.

- [ ] **Step 4: Run cross-runtime tests and commit**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.DrawnThingsTest" \
  --tests "com.rohittp.reng.FramePlanTest"
./gradlew --no-configuration-cache :kmp:macosArm64Test \
  --tests "com.rohittp.reng.DrawnThingsTest" \
  --tests "com.rohittp.reng.FramePlanTest"
git add kmp/src/commonMain/kotlin/com/rohittp/reng/DrawnThings.kt \
  kmp/src/commonMain/kotlin/com/rohittp/reng/FramePlan.kt \
  kmp/src/commonTest/kotlin/com/rohittp/reng/DrawnThingsTest.kt \
  kmp/src/commonTest/kotlin/com/rohittp/reng/FramePlanTest.kt
git commit -m "feat: add immutable frame vocabulary"
```

---

### Task 4A: Resource Keys, Selectors, Reports, and Free Results

**Files:**
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/ResourceReports.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/ResourceReportsTest.kt`

**Interfaces:**
- Consumes: Task 1 resource vocabulary.
- Produces: `ResourceKey`, `ResourceSelector`, `ResourceUsage`, `ResourceReportEntry`, `ResourceReport`, and `ResourceFreeResult` for diagnostics and renderer protocols.

- [ ] **Step 1: Write exact construction, copying, sorting, and invariant tests**

Test external/nonexternal resource-key class invariants, exact lowercase 64-hex stable ids, all selector variants, every byte/count boundary, known/unknown GPU-byte combinations, free-category sum, deterministic `(kind wire, nullable class wire, stableId)` report sorting, fresh list getters, equality/hash, and redacted text.

- [ ] **Step 2: Run and verify red**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.ResourceReportsTest"
```

Expected: compilation fails because report values do not exist.

- [ ] **Step 3: Implement exact report declarations**

```kotlin
@ConsistentCopyVisibility
public data class ResourceKey internal constructor(
    public val kind: ResourceKind,
    public val stableId: String,
    public val resourceClass: ResourceClass?,
)

public sealed interface ResourceSelector {
    public data object All : ResourceSelector
    public data class ByKind(public val kind: ResourceKind) : ResourceSelector
    public data class ByClass(public val resourceClass: ResourceClass) : ResourceSelector
    public data class ByKey(public val key: ResourceKey) : ResourceSelector
}

@ConsistentCopyVisibility
public data class ResourceUsage internal constructor(
    public val rawBytes: Long,
    public val decodedCpuBytes: Long,
    public val knownGpuBytes: Long?,
    public val hasUnknownGpuBytes: Boolean,
)

@ConsistentCopyVisibility
public data class ResourceReportEntry internal constructor(
    public val key: ResourceKey,
    public val residentGenerationCount: Int,
    public val retiredGenerationCount: Int,
    public val leaseCount: Int,
    public val reloadRequired: Boolean,
    public val usage: ResourceUsage,
)

public class ResourceReport internal constructor(
    entries: List<ResourceReportEntry>,
    public val totals: ResourceUsage,
) {
    public val entries: List<ResourceReportEntry>
}

@ConsistentCopyVisibility
public data class ResourceFreeResult internal constructor(
    public val matchedKeys: Int,
    public val fullyFreedKeys: Int,
    public val deferredKeys: Int,
    public val alreadyFreeKeys: Int,
)
```

`ResourceReport` sorts and snapshots its constructor input, returns a fresh mutable copy on every getter, and implements structural equality/hash manually. Apply `@ConsistentCopyVisibility` unconditionally as shown and never use `@ExposedCopyVisibility`.

- [ ] **Step 4: Run cross-runtime tests and commit**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.ResourceReportsTest"
./gradlew --no-configuration-cache :kmp:macosArm64Test \
  --tests "com.rohittp.reng.ResourceReportsTest"
git add kmp/src/commonMain/kotlin/com/rohittp/reng/ResourceReports.kt \
  kmp/src/commonTest/kotlin/com/rohittp/reng/ResourceReportsTest.kt
git commit -m "feat: add resource report contracts"
```

---

### Task 4B: Diagnostics and Sanitized Failures

**Files:**
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/Diagnostics.kt`
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/Exceptions.kt`
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/DiagnosticFactories.kt`
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/failure/FailureDescriptor.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/DiagnosticsAndFailuresTest.kt`

**Interfaces:**
- Consumes: Task 1 resource vocabulary and Task 4A `ResourceKey`.
- Produces: complete public diagnostic/exception surface and internal sanitized failure descriptors used by every pure engine.

- [ ] **Step 1: Write exact enum, factory-matrix, redaction, and identity tests**

```kotlin
@Test fun failureMessageCauseAndDiagnosticAreFixed() {
    val diagnostic = failureContextDiagnostic(
        stage = PipelineStage.FRAME_PLANNING,
        fieldName = DiagnosticField.FRAME_INDEX,
    )
    val failure = renGFailure(
        RenGErrorCode.PREPARATION_ORDER_VIOLATION,
        PipelineStage.FRAME_PLANNING,
        diagnostic,
    )
    assertEquals("RenG failure: PREPARATION_ORDER_VIOLATION at FRAME_PLANNING", failure.message)
    assertNull(failure.cause)
    assertEquals(listOf(diagnostic), failure.diagnostics)
}
```

Table-test every `PipelineStage`, `RenGErrorCode`, `DiagnosticSeverity`, and `DiagnosticCode`; the exact 21 field names below; zero-or-one failure diagnostic; stage matching; stable reload warning; fresh diagnostic lists; sink `None`; exception identity semantics; and absence of locator/shader/validator/metadata/adapter text or cause. Reject nonallowlisted factory combinations, unmatched limit/actual, non-Transport status, and resource class/key before identity establishment.

- [ ] **Step 2: Run and verify red**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.DiagnosticsAndFailuresTest"
```

Expected: compilation fails because diagnostic/failure values do not exist.

- [ ] **Step 3: Implement the exact contracts and factory seam**

```kotlin
public enum class PipelineStage {
    CONFIGURATION, FRAME_PLANNING, FRAME_PREPARATION, RESOURCE_LOOKUP,
    STORE_READ, STORE_VALIDATION, TRANSPORT, TRANSPORT_VALIDATION,
    STORE_WRITE, RESOURCE_DECODING, RESOURCE_PARSING, SHADER_COMPILATION,
    GPU_RESOURCE, RENDER_TARGET, DRAW, RESOURCE_FREE, RENDERER_CLOSE,
    CONTEXT_ADOPTION,
}

public enum class RenGErrorCode {
    INVALID_VALUE, RESOURCE_LIMIT_EXCEEDED, UNSUPPORTED_PROJECTION_MODE,
    PREPARATION_ORDER_VIOLATION, PREPARATION_IN_PROGRESS, RENDERER_CLOSED,
    RENDER_CONTEXT_ADOPTION_REQUIRED, NO_CURRENT_RENDER_CONTEXT,
    DIFFERENT_CURRENT_RENDER_CONTEXT, UNSUPPORTED_RENDER_CONTEXT,
    FOREIGN_PREPARED_FRAME, PREPARED_FRAME_CLOSED, FOREIGN_RENDER_TARGET,
    STALE_RENDER_TARGET, INVALID_RENDER_TARGET, AMBIGUOUS_RESOURCE_ROUTE,
    RESOURCE_UNAVAILABLE, TRANSPORT_EXECUTION_FAILED,
    INVALID_TRANSPORT_RESPONSE, STORE_READ_FAILED, STORE_WRITE_FAILED,
    STORE_INTEGRITY_FAILED, RESOURCE_DECODE_FAILED, RESOURCE_PARSE_FAILED,
    UNSUPPORTED_RESOURCE_FEATURE, SHADER_COMPILE_FAILED, SHADER_LINK_FAILED,
    GPU_OPERATION_FAILED, IDENTITY_COLLISION,
}

public enum class DiagnosticSeverity { INFO, WARNING, ERROR }
public enum class DiagnosticCode { RESOURCE_RELOADED_AFTER_FREE, FAILURE_CONTEXT }

@ConsistentCopyVisibility
public data class Diagnostic internal constructor(
    public val code: DiagnosticCode,
    public val severity: DiagnosticSeverity,
    public val stage: PipelineStage,
    public val fieldName: String? = null,
    public val resourceClass: ResourceClass? = null,
    public val resourceKey: ResourceKey? = null,
    public val statusCode: Int? = null,
    public val limit: Long? = null,
    public val actual: Long? = null,
)

public fun interface DiagnosticSink {
    public fun emit(diagnostic: Diagnostic)
    public companion object {
        public val None: DiagnosticSink = DiagnosticSink { }
    }
}

public class RenGException internal constructor(
    public val code: RenGErrorCode,
    public val stage: PipelineStage,
    diagnostics: List<Diagnostic> = emptyList(),
) : RuntimeException("RenG failure: $code at $stage") {
    public val diagnostics: List<Diagnostic>
}

internal enum class DiagnosticField(internal val wireName: String) {
    PLANS("plans"), FRAME_INDEX("frameIndex"),
    PROJECTION_MODE("projectionMode"),
    CAMERA_LATITUDE("camera.latitude"),
    CAMERA_UNWRAPPED_LONGITUDE("camera.unwrappedLongitude"),
    MAP_POSITION_LATITUDE("mapPosition.latitude"),
    MAP_POSITION_UNWRAPPED_LONGITUDE("mapPosition.unwrappedLongitude"),
    MAP_POSITION_ALTITUDE("mapPosition.altitude"),
    SCREEN_POSITION_X("screenPosition.x"), SCREEN_POSITION_Y("screenPosition.y"),
    PLACEMENT_SCALE("placement.scale"), GEOMETRY_LATITUDE("geometry.latitude"),
    GEOMETRY_UNWRAPPED_LONGITUDE("geometry.unwrappedLongitude"),
    GEOMETRY_ALTITUDE("geometry.altitude"),
    BASEMAP_TILE_INSTANCES("basemapTileInstances"),
    RESPONSE_BODY_BYTES("responseBodyBytes"), RESOURCE("resource"),
    FRAME_IDENTITY("frameIdentity"), ANIMATION_SELECTOR("animationSelector"),
    SHADER_PAIR("shaderPair"), RENDER_TARGET("renderTarget"),
}

internal data class FailureDescriptor(
    val code: RenGErrorCode,
    val stage: PipelineStage,
    val diagnostic: Diagnostic? = null,
)

internal fun failureContextDiagnostic(
    stage: PipelineStage,
    fieldName: DiagnosticField? = null,
    resourceClass: ResourceClass? = null,
    resourceKey: ResourceKey? = null,
    statusCode: Int? = null,
    limit: Long? = null,
    actual: Long? = null,
): Diagnostic
internal fun resourceReloadedAfterFreeDiagnostic(key: ResourceKey): Diagnostic
internal fun renGFailure(
    code: RenGErrorCode,
    stage: PipelineStage,
    failureContext: Diagnostic? = null,
): RenGException
```

`RenGException` fixes its message to `RenG failure: <CODE> at <STAGE>`, passes no cause, snapshots diagnostics, and returns a fresh list. `Diagnostic` uses `@ConsistentCopyVisibility` unconditionally.

- [ ] **Step 4: Run cross-runtime tests and commit**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.DiagnosticsAndFailuresTest"
./gradlew --no-configuration-cache :kmp:macosArm64Test \
  --tests "com.rohittp.reng.DiagnosticsAndFailuresTest"
git add kmp/src/commonMain/kotlin/com/rohittp/reng/{Diagnostics.kt,Exceptions.kt} \
  kmp/src/commonMain/kotlin/com/rohittp/reng/internal/DiagnosticFactories.kt \
  kmp/src/commonMain/kotlin/com/rohittp/reng/internal/failure/FailureDescriptor.kt \
  kmp/src/commonTest/kotlin/com/rohittp/reng/DiagnosticsAndFailuresTest.kt
git commit -m "feat: add sanitized failure contracts"
```

---

### Task 4C: Renderer Configuration and Ownership Protocols

**Files:**
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/Renderer.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/RendererProtocolTest.kt`

**Interfaces:**
- Consumes: Tasks 1–3, Task 4A selectors/reports, and Task 4B sink/failures.
- Produces: exact public configuration, framebuffer value, and sealed renderer/frame/target protocols; no implementation or factory.

- [ ] **Step 1: Write exact defaults, bounds, identity, and method-surface tests**

Test output/resources/adapters/sink retention; tile/batch bounds `1..4096`; concurrency `1..64`; exact defaults `512/256/8`; nullable style; `FramebufferName(0u)`; configuration/adapters/sink and ownership-protocol identity semantics; every method and default argument through compile-time references; and absence of a factory or constructible sealed implementation.

- [ ] **Step 2: Run and verify red**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.RendererProtocolTest"
```

Expected: compilation fails because renderer protocols do not exist.

- [ ] **Step 3: Implement the exact protocol**

```kotlin
@JvmInline
public value class FramebufferName(public val value: UInt)

public sealed interface PreparedFrame : AutoCloseable {
    public val frameIndex: Long
    override fun close(): Unit
}

public sealed interface RenderTarget {
    public val framebufferName: FramebufferName
}

public class RendererConfiguration(
    public val outputPixelSize: OutputPixelSize,
    public val transport: Transport,
    public val store: Store,
    public val basemapStyle: ResourceLocator? = null,
    public val resourceLimits: ResourceLimits = ResourceLimits(),
    public val maximumBasemapTileInstances: Int = 512,
    public val maximumPreparationBatchSize: Int = 256,
    public val maximumConcurrentResourceOperations: Int = 8,
    public val diagnosticSink: DiagnosticSink = DiagnosticSink.None,
)

public sealed interface Renderer : AutoCloseable {
    public suspend fun prepare(
        plan: FramePlan,
        accessMode: ResourceAccessMode = ResourceAccessMode.NORMAL,
    ): PreparedFrame
    public suspend fun prepareBatch(
        plans: List<FramePlan>,
        accessMode: ResourceAccessMode = ResourceAccessMode.NORMAL,
    ): List<PreparedFrame>
    public suspend fun cancelPreparations(): Unit
    public fun clearFrameHistory(): Unit
    public fun queryResources(selector: ResourceSelector = ResourceSelector.All): ResourceReport
    public fun freeResources(selector: ResourceSelector = ResourceSelector.All): ResourceFreeResult
    public fun notifyGpuObjectsGone(): Unit
    public fun adoptCurrentRenderContext(): Unit
    public fun mintRenderTarget(framebufferName: FramebufferName): RenderTarget
    public fun draw(preparedFrame: PreparedFrame, renderTarget: RenderTarget): Unit
    override fun close(): Unit
}
```

Keep `RendererConfiguration` a regular identity-semantics class. Add no implementation, constructor seam, companion factory, top-level `createRenderer`, or construction object.

- [ ] **Step 4: Run cross-runtime tests and commit**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.RendererProtocolTest"
./gradlew --no-configuration-cache :kmp:macosArm64Test \
  --tests "com.rohittp.reng.RendererProtocolTest"
git add kmp/src/commonMain/kotlin/com/rohittp/reng/Renderer.kt \
  kmp/src/commonTest/kotlin/com/rohittp/reng/RendererProtocolTest.kt
git commit -m "feat: add renderer ownership protocols"
```

---

### Task 5: Public ABI, Policy, JVM Copy Proof, and Consumer Smoke

**Files:**
- Create: `kmp/src/androidHostTest/kotlin/com/rohittp/reng/JvmDefensiveCopyTest.kt`
- Modify: `consumer-smoke/src/commonMain/kotlin/com/rohittp/reng/smoke/ConsumerProof.kt`
- Modify: `tools/check_repository_policy.py`
- Modify: `tools/tests/test_check_repository_policy.py`
- Regenerate: `kmp/api/kmp.klib.api`

**Interfaces:**
- Consumes: Tasks 4A–4C complete public surface.
- Produces: reviewed public ABI, external compilation proof, Cycle B policy enforcement.

- [ ] **Step 1: Add JVM mutation tests for empty, singleton, and multi-element copies**

For every public list getter (`Model.animationTracks`, all three `FramePlan` lists, `RenGException.diagnostics`, and `ResourceReport.entries`), construct sizes 0, 1, and 2, cast each returned value to `MutableList`, mutate it successfully, and assert a new getter call still returns the original contents and an independently mutable object. Assert equality/hash remain stable. For `TransportResponse.body` and `StoredRawResource.bytes`, cover zero-length and nonempty arrays, use referential-identity assertions to prove even zero-length getter results are fresh, mutate nonempty results, and assert backing content/equality/hash remain unchanged.

```kotlin
@Test fun emptySingletonAndMultiElementListsAreFreshJvmCopies() {
    listOf(frameWithStickerCount(0), frameWithStickerCount(1), frameWithStickerCount(2))
        .forEach { plan ->
            val first = plan.stickers as MutableList<Sticker>
            val second = plan.stickers as MutableList<Sticker>
            assertNotSame(first, second)
            first.clear()
            assertEquals(second, plan.stickers)
        }
}

@Test fun zeroLengthByteArraysAreIndependentGetterResults() {
    val response = TransportResponse(200, byteArrayOf(), TransportResponseMetadata())
    assertNotSame(response.body, response.body)
    assertContentEquals(byteArrayOf(), response.body)
}
```

- [ ] **Step 2: Replace the smoke placeholder with exhaustive public consumer code**

```kotlin
private val transport = Transport { request ->
    listOf(
        request.locator.value,
        request.resourceClass.name,
        request.maximumResponseBytes.toString(),
        request.metadata.ifNoneMatch,
        request.metadata.ifModifiedSince,
        request.metadata.accept,
    )
    TransportResponse(200, byteArrayOf(1), TransportResponseMetadata())
}

private object SmokeStore : Store {
    override suspend fun read(key: RawResourceKey): StoredRawResource? {
        key.stableId
        key.resourceClass
        return null
    }
    override suspend fun write(key: RawResourceKey, resource: StoredRawResource) {
        key.stableId
        resource.bytes
        resource.contentDigest
        resource.metadata.storedAtEpochMillis
    }
}

internal fun consumerCompilationProof(): FramePlan = FramePlan(
    frameIndex = 0,
    camera = Camera(0.0, 0.0, 0.0, 0.0, 0.0),
    stickers = listOf(
        Sticker(
            Placement(
                AnchoringMode.SCREEN,
                Vector3(0.5, 0.5, 0.0),
                AnchoringMode.SCREEN,
                Vector3(0.0, 0.0, 0.0),
                AnchoringMode.SCREEN,
                1.0,
            ),
            ResourceLocator("smoke:sticker"),
        ),
    ),
)

@Suppress("unused")
private val configuration = RendererConfiguration(
    outputPixelSize = OutputPixelSize(1, 1),
    transport = transport,
    store = SmokeStore,
    diagnosticSink = DiagnosticSink { diagnostic ->
        diagnostic.code
        diagnostic.severity
        diagnostic.stage
        diagnostic.fieldName
        diagnostic.resourceClass
        diagnostic.resourceKey
        diagnostic.statusCode
        diagnostic.limit
        diagnostic.actual
    },
)

@Suppress("unused")
private suspend fun protocolReference(
    renderer: Renderer,
    plan: FramePlan,
    frame: PreparedFrame,
    target: RenderTarget,
) {
    renderer.prepare(plan)
    renderer.prepare(plan, ResourceAccessMode.RELOAD)
    renderer.prepareBatch(listOf(plan))
    renderer.prepareBatch(listOf(plan), ResourceAccessMode.CACHE_ONLY)
    renderer.cancelPreparations()
    renderer.clearFrameHistory()
    val report = renderer.queryResources()
    renderer.queryResources(ResourceSelector.ByKind(ResourceKind.EXTERNAL))
    report.entries.forEach { entry ->
        entry.key.kind
        entry.key.stableId
        entry.key.resourceClass
        entry.residentGenerationCount
        entry.retiredGenerationCount
        entry.leaseCount
        entry.reloadRequired
        entry.usage.rawBytes
        entry.usage.decodedCpuBytes
        entry.usage.knownGpuBytes
        entry.usage.hasUnknownGpuBytes
        renderer.queryResources(ResourceSelector.ByKey(entry.key))
        entry.key.resourceClass?.let { renderer.queryResources(ResourceSelector.ByClass(it)) }
    }
    report.totals
    val freed = renderer.freeResources()
    renderer.freeResources(ResourceSelector.All)
    freed.matchedKeys
    freed.fullyFreedKeys
    freed.deferredKeys
    freed.alreadyFreeKeys
    renderer.notifyGpuObjectsGone()
    renderer.adoptCurrentRenderContext()
    renderer.mintRenderTarget(FramebufferName(0u))
    frame.frameIndex
    target.framebufferName
    renderer.draw(frame, target)
    frame.close()
    renderer.close()
}
```

In the same file, add compile-only construction/property helpers that explicitly name every public enum entry and selector variant; every constructor/default/property of `OutputPixelSize`, `Vector3`, `Camera`, `Placement`, `ResourceLocator`, `ResourceLimits`, adapter DTOs, `Sticker`, `AnimationSelector.Name`, `AnimationSelector.Index`, `AnimationTrack`, `Model`, `ShaderPair`, `Geometry`, and `FramePlan`; `DiagnosticSink.None`; `RenGException` properties supplied through a typed parameter; and all `RendererConfiguration` properties/defaults. Library-owned internal-constructor values and sealed protocols are referenced only through callback/method parameters or renderer-returned values, never constructed by the consumer.

- [ ] **Step 3: Make Cycle B policy mutations fail before changing the checker**

Update the clean fixture ABI to contain a representative `com.rohittp.reng` declaration. Add mutation tests for empty/comment-only ABI, Rentile and platform ABI leaks, `createRenderer`, `RendererFactory`, a public `RenG` construction object, any `@ExposedCopyVisibility` token, a `jvm*` ABI file, and every existing forbidden dependency. Add allowlist mutations for a coroutine dependency, crypto/hash library, serialization library, Ktor, Skiko, Wire, and an arbitrary otherwise-unknown runtime coordinate. Change expected dependency violation wording/code to `FORBIDDEN_CYCLE_B_DEPENDENCY` and successful output to exactly `Cycle B repository policy passed`.

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest \
  tools.tests.test_check_repository_policy -v
```

Expected: FAIL because the Cycle A checker still requires an empty ABI and does not reject the new Cycle B mutations.

- [ ] **Step 4: Implement the Cycle B policy and make its tests green**

Replace `CYCLE_A_PUBLIC_ABI` with `CYCLE_B_PUBLIC_ABI` checks that:

- ABI exists and contains at least one noncomment `com.rohittp.reng` declaration;
- ABI contains no `com.rohittp.rentile` or `platform.` type;
- no `jvm*` ABI file exists;
- ABI contains no `createRenderer`, `RendererFactory`, or public RenG construction object;
- ABI contains no `@ExposedCopyVisibility`; library-owned internal-constructor data classes use `@ConsistentCopyVisibility` instead;
- dependency restriction code/messages use `FORBIDDEN_CYCLE_B_DEPENDENCY` and enforce an exact `:kmp` source-set dependency allowlist: `commonMain` contains only `implementation(libs.rentile.kmp)` and `commonTest` contains only `implementation(kotlin("test"))`; any additional runtime, coroutine, crypto, serialization, Ktor, Skiko, Wire, or unknown coordinate fails closed;
- successful CLI output is exactly `Cycle B repository policy passed`.

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest \
  tools.tests.test_check_repository_policy -v
```

Expected: PASS.

- [ ] **Step 5: Prove the checked-in ABI is red, then regenerate and inspect it**

```bash
./gradlew --no-configuration-cache :kmp:checkKotlinAbi
```

Expected: FAIL because the checked-in Cycle A ABI lacks the new public declarations.

```bash
./gradlew --no-configuration-cache :kmp:updateKotlinAbi
./gradlew --no-configuration-cache :kmp:checkKotlinAbi
! grep -nE 'com\.rohittp\.rentile|platform\.|createRenderer|RendererFactory|@ExposedCopyVisibility' \
  kmp/api/kmp.klib.api
```

Manually verify exact approved signatures/defaults, no internal helper, no public constructor/copy escape for library-created values, unconditional `@ConsistentCopyVisibility` on `RawResourceKey`, `Diagnostic`, `ResourceKey`, `ResourceUsage`, `ResourceReportEntry`, and `ResourceFreeResult`, and no factory.

- [ ] **Step 6: Run JVM tests and compile the standalone smoke**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.JvmDefensiveCopyTest"
./gradlew --no-configuration-cache :kmp:publishAllPublicationsToLocalTestRepository
smoke_home="$(mktemp -d)"
./gradlew --no-configuration-cache --gradle-user-home "$smoke_home" \
  --refresh-dependencies -p consumer-smoke \
  compileAndroidMain compileKotlinIosArm64 compileKotlinIosSimulatorArm64 \
  compileKotlinMacosArm64 compileKotlinLinuxX64 compileKotlinLinuxArm64
```

- [ ] **Step 7: Commit**

```bash
git add kmp/src/androidHostTest/kotlin/com/rohittp/reng/JvmDefensiveCopyTest.kt \
  consumer-smoke/src/commonMain/kotlin/com/rohittp/reng/smoke/ConsumerProof.kt \
  tools/check_repository_policy.py tools/tests/test_check_repository_policy.py \
  kmp/api/kmp.klib.api
git commit -m "test: freeze the Cycle B public surface"
```

---

### Task 6: Canonical Binary Primitives, SHA-256, and Collision Registry

**Files:**
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/identity/CanonicalBytes.kt`
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/identity/CanonicalBinary.kt`
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/identity/Sha256.kt`
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/identity/IdentityRegistry.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/identity/CanonicalBinaryTest.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/identity/Sha256Test.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/identity/IdentityRegistryTest.kt`

**Interfaces:**
- Consumes: Task 1 Unicode/negative-zero semantics.
- Produces: defensive canonical bytes, exact field writer, pure SHA-256, collision registry.

- [ ] **Step 1: Write exact primitive and rejection tests**

Assert these hex values:

```text
u16(0x0102) = 0102
u64(0x0102030405060708) = 0102030405060708
binary64(1.0) = 3ff0000000000000
binary64(-0.0) = 0000000000000000
exactUtf8("é") = c3a9
exactUtf8("😀") = f09f9880
optional("é") = 01c3a9
list([aa,bbcc]) = 0000000200000001aa00000002bbcc
```

Reject nonfinite doubles, isolated surrogates, zero/duplicate/decreasing field tags, and checked-size overflow.

- [ ] **Step 2: Write SHA and collision tests**

Use SHA-256 known answers for empty, `abc`, the NIST 56-byte vector, one million `a`, and 55/56/64-byte padding boundaries. Inject a fake repeated-`5a` digest to prove different bytes collide without replacing the first registry entry.

- [ ] **Step 3: Run and verify red**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.internal.identity.CanonicalBinaryTest" \
  --tests "com.rohittp.reng.internal.identity.Sha256Test" \
  --tests "com.rohittp.reng.internal.identity.IdentityRegistryTest"
```

- [ ] **Step 4: Implement the exact internal primitives**

```kotlin
internal enum class CanonicalRootKind(internal val wireByte: Int) {
    FRAME(1), EXTERNAL_RESOURCE(2), GEOMETRY_PROGRAM(3),
    INTERNAL_PIPELINE(4), OFFSCREEN_SURFACE(5),
}
internal class CanonicalBytes(bytes: ByteArray) {
    internal val bytes: ByteArray
}
internal class Sha256Digest internal constructor(bytes: ByteArray) {
    internal val bytes: ByteArray
    internal val lowercaseHex: String
}
internal fun interface Sha256Function {
    fun digest(bytes: CanonicalBytes): Sha256Digest
}
internal data class HashedCanonicalBytes(
    val digest: Sha256Digest,
    val canonicalBytes: CanonicalBytes,
)
internal class CanonicalFieldWriter internal constructor() {
    internal fun field(tag: Int, payload: CanonicalBytes)
}
internal object CanonicalBinary {
    internal fun root(
        kind: CanonicalRootKind,
        block: CanonicalFieldWriter.() -> Unit,
    ): CanonicalBytes
    internal fun fields(block: CanonicalFieldWriter.() -> Unit): CanonicalBytes
    internal fun u16(value: Int): CanonicalBytes
    internal fun u64(value: Long): CanonicalBytes
    internal fun boolean(value: Boolean): CanonicalBytes
    internal fun binary64(value: Double): CanonicalBytes
    internal fun exactUtf8(value: String): CanonicalBytes
    internal fun optional(value: CanonicalBytes?): CanonicalBytes
    internal fun list(elements: List<CanonicalBytes>): CanonicalBytes
}

internal object PureKotlinSha256 : Sha256Function
internal sealed interface IdentityRegistration {
    data object Registered : IdentityRegistration
    data object AlreadyRegistered : IdentityRegistration
    data class Collision(
        val established: HashedCanonicalBytes,
        val attempted: HashedCanonicalBytes,
    ) : IdentityRegistration
}
internal class CanonicalIdentityRegistry {
    internal fun register(identity: HashedCanonicalBytes): IdentityRegistration
}
```

`CanonicalBytes`, `Sha256Digest`, and the registry copy every input/output and implement content equality/hash; textual forms redact bytes. `Sha256Digest` accepts exactly 32 bytes. `CanonicalFieldWriter` rejects zero/duplicate/decreasing tags and serializes `tag:u16be | length:u32be | payload`; `root` prefixes ASCII `RNGC`, schema byte `1`, and the explicit root byte. Process complete 64-byte SHA blocks directly and only allocate the final 64/128-byte tail. Registry collision preserves the established entry.

- [ ] **Step 5: Run cross-runtime tests and commit**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.internal.identity.*"
./gradlew --no-configuration-cache :kmp:macosArm64Test \
  --tests "com.rohittp.reng.internal.identity.*"
git add kmp/src/commonMain/kotlin/com/rohittp/reng/internal/identity \
  kmp/src/commonTest/kotlin/com/rohittp/reng/internal/identity
git commit -m "feat: add canonical bytes and SHA-256"
```

---

### Task 7: Frame and Resource Identities plus Deterministic Diff

**Files:**
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/identity/FramePlanCanonicalEncoding.kt`
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/identity/ResourceKeyDerivation.kt`
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/diff/FrameStructuralDiff.kt`
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/diff/ResourceTraversalDiff.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/identity/FramePlanCanonicalEncodingTest.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/identity/ResourceKeyDerivationTest.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/identity/CanonicalTestFixtures.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/diff/FrameStructuralDiffTest.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/diff/ResourceTraversalDiffTest.kt`

**Interfaces:**
- Consumes: Tasks 3, 4A, and 6.
- Produces: encoded frame segments/identity, external/geometry resource keys, structural/resource diff.

- [ ] **Step 1: Write exact minimal and representative frame goldens**

Minimal frame canonical bytes are exactly 141 bytes with identity:

```text
reng-frame-v1:a143c83e1d2d0d0c2852e0cc58451491985688105e8b8f73e8ff38a8aab30d85
```

Use the complete tracked constructor and exact bytes from:

```text
docs/superpowers/specs/2026-08-17-cycle-b-canonical-frame-v1-test-vector.txt
```

Copy that constructor into `CanonicalTestFixtures.kt` without changing values. Assert exactly 1,431 bytes and:

```text
reng-frame-v1:447341d0410d7aea75e07153528b87609e7d408f1b7657e231e0381fb0a40599
```

Add field-significance, list-order/duplicate, selector-kind, optional locator (`01` then direct UTF-8 with no inner length), NFC/NFD, and getter-mutation controls. Assert derived external keys have a matching nonnull `RawResourceKey`, geometry-program keys have null `rawKey` because their sources are plan-local and never enter Store/Transport, and neither case registers an identity in Task 7.

- [ ] **Step 2: Write exact resource-key and diff tests**

External `STICKER_IMAGE` with locator `é`:

```text
root = 524e4743010200010000000200010002000000020009000300000002c3a9
stableId = 086f1d5f61081736cd1bb0145d5b9070cb9903796396f3b73c65cb6413b3db61
```

Geometry profile 1, vertex `v`, fragment `f`:

```text
root = 524e47430103000100000002000200020000000200010003000000017600040000000166
stableId = 8b639140035249737f3f95cee835e93fe8fd2124b91cde5acaf6ee537a573df2
```

For previous traversal `[A,B,A,C]` and current `[C,D,C,B]`, assert `retain=[C,B]`, `acquire=[D]`, `release=[A]`. Null frame baseline changes all seven segments in tag order; diff compares bytes, never hashes.

- [ ] **Step 3: Run identity and diff tests and verify red**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.internal.identity.FramePlanCanonicalEncodingTest" \
  --tests "com.rohittp.reng.internal.identity.ResourceKeyDerivationTest" \
  --tests "com.rohittp.reng.internal.diff.*"
```

Expected: compilation fails because the frame/resource encoders and differs do not exist.

- [ ] **Step 4: Implement encoders and differs**

```kotlin
internal class FramePlanCanonicalEncoder(
    private val sha256: Sha256Function = PureKotlinSha256,
) {
    internal fun encode(plan: FramePlan): EncodedFramePlan
}

internal class EncodedFramePlan(
    internal val identity: HashedCanonicalBytes,
    segmentPayloads: List<CanonicalBytes>,
) {
    internal val segmentPayloads: List<CanonicalBytes>
}

internal class ResourceKeyDeriver(
    private val sha256: Sha256Function = PureKotlinSha256,
) {
    internal fun external(
        resourceClass: ResourceClass,
        locator: ResourceLocator,
    ): DerivedResourceKey
    internal fun geometryProgram(shaderPair: ShaderPair): DerivedResourceKey
}

internal data class DerivedResourceKey(
    val key: ResourceKey,
    val rawKey: RawResourceKey?,
    val identity: HashedCanonicalBytes,
)

internal enum class FramePlanSegment(internal val tag: Int) {
    FRAME_INDEX(1), CAMERA(2), PROJECTION_MODE(3), DRAW_BASEMAP(4),
    STICKERS(5), MODELS(6), GEOMETRIES(7),
}

internal class FrameStructuralDiff(
    changedSegments: List<FramePlanSegment>,
) {
    val changedSegments: List<FramePlanSegment>
}

internal object FrameStructuralDiffer {
    internal fun diff(
        previous: EncodedFramePlan?,
        current: EncodedFramePlan,
    ): FrameStructuralDiff
}

internal class ResourceTraversalDiff(
    retain: List<ResourceKey>,
    acquire: List<ResourceKey>,
    release: List<ResourceKey>,
) {
    val retain: List<ResourceKey>
    val acquire: List<ResourceKey>
    val release: List<ResourceKey>
}

internal object ResourceTraversalDiffer {
    internal fun diff(
        previous: List<ResourceKey>,
        current: List<ResourceKey>,
    ): ResourceTraversalDiff
}
```

Use explicit enum wire tables. Define root kinds 4/5 but derive no pipeline/offscreen key. Snapshot each public list once through internal core accessors; frame segments retain payload bytes only. Every list-bearing result above snapshots/fresh-copies and implements structural equality/hash. `ResourceTraversalDiffer` deduplicates each input at first occurrence before producing current-order retain/acquire and previous-order release.

- [ ] **Step 5: Run cross-runtime tests and commit**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.internal.identity.FramePlanCanonicalEncodingTest" \
  --tests "com.rohittp.reng.internal.identity.ResourceKeyDerivationTest" \
  --tests "com.rohittp.reng.internal.diff.*"
./gradlew --no-configuration-cache :kmp:macosArm64Test \
  --tests "com.rohittp.reng.internal.identity.FramePlanCanonicalEncodingTest" \
  --tests "com.rohittp.reng.internal.identity.ResourceKeyDerivationTest" \
  --tests "com.rohittp.reng.internal.diff.*"
git add kmp/src/commonMain/kotlin/com/rohittp/reng/internal/{identity,diff} \
  kmp/src/commonTest/kotlin/com/rohittp/reng/internal/{identity,diff}
git commit -m "feat: add canonical identities and diffs"
```

---

### Task 8A: Double Algebra and Mercator/WGS84 Projection

**Files:**
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/math/DoubleLinearAlgebra.kt`
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/planning/SpatialPlanningResult.kt`
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/projection/MercatorProjection.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/math/DoubleLinearAlgebraTest.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/planning/SpatialPlanningResultTest.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/projection/MercatorProjectionTest.kt`

**Interfaces:**
- Consumes: Task 1 values and Task 4B `FailureDescriptor`.
- Produces: immutable Double vectors/matrices, `SpatialOutcome`, exact Mercator positions, and WGS84 local frames for camera and placement tasks.

- [ ] **Step 1: Write algebra, projection, and rejection controls**

Test identity/transpose/composition/cross product; right-handed X/Y/Z rotations; a discriminating `(90,90,0)` proving `Rz*Ry*Rx`; exact Mercator endpoints; nonperiodic `x(λ+360n)`; copy `[-16384,16384]` and support `[-16384,16385]`; ECEF/ENU at 0/90 degrees, poles, `(45,45,1000)`; copy-equivalent bases; point-latitude altitude scaling; and sanitized failures.

- [ ] **Step 2: Run and verify red**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.internal.math.*" \
  --tests "com.rohittp.reng.internal.projection.MercatorProjectionTest" \
  --tests "com.rohittp.reng.internal.planning.SpatialPlanningResultTest"
```

- [ ] **Step 3: Implement the exact Double-only seam**

```kotlin
internal data class DoubleVector3(val x: Double, val y: Double, val z: Double)
internal class DoubleMatrix3 internal constructor(valuesInColumnMajorOrder: List<Double>)
internal class DoubleMatrix4 internal constructor(valuesInColumnMajorOrder: List<Double>)

internal sealed interface SpatialOutcome<out T> {
    data class Success<T>(val value: T) : SpatialOutcome<T>
    data class Failure(val failure: FailureDescriptor) : SpatialOutcome<Nothing>
}

internal data class GeographicPosition(
    val latitude: Double,
    val unwrappedLongitude: Double,
    val altitudeMetres: Double,
)
internal data class MercatorPosition(val x: Double, val y: Double, val z: Double)
internal data class Wgs84LocalFrame(
    val ecefPosition: DoubleVector3,
    val basisEastNorthUp: DoubleMatrix3,
)

internal fun projectMercator(position: GeographicPosition): MercatorPosition
internal fun wgs84LocalFrame(position: GeographicPosition): Wgs84LocalFrame
```

Both matrix classes snapshot input, expose no mutable storage, implement structural equality/hash, and provide focused multiplication/transpose/vector/rotation factory operations in the same file. Use literals `a=6378137.0`, `f=1.0/298.257223563`, `C=40075016.68557849`, and `φmax=85.0511287798066`; use exact endpoint branches and no horizontal ECEF subtraction.

- [ ] **Step 4: Run cross-runtime tests and commit**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.internal.math.*" \
  --tests "com.rohittp.reng.internal.projection.MercatorProjectionTest" \
  --tests "com.rohittp.reng.internal.planning.SpatialPlanningResultTest"
./gradlew --no-configuration-cache :kmp:macosArm64Test \
  --tests "com.rohittp.reng.internal.math.*" \
  --tests "com.rohittp.reng.internal.projection.MercatorProjectionTest" \
  --tests "com.rohittp.reng.internal.planning.SpatialPlanningResultTest"
git add kmp/src/commonMain/kotlin/com/rohittp/reng/internal/{math,projection/MercatorProjection.kt,planning/SpatialPlanningResult.kt} \
  kmp/src/commonTest/kotlin/com/rohittp/reng/internal/{math,projection/MercatorProjectionTest.kt,planning/SpatialPlanningResultTest.kt}
git commit -m "feat: add Double Mercator projection core"
```

---

### Task 8B: Camera Matrices and Physical-Pixel Ground Rays

**Files:**
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/projection/CameraMatrices.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/projection/CameraMatricesTest.kt`

**Interfaces:**
- Consumes: Task 8A algebra/projection.
- Produces: resolved camera basis/matrices and classified pixel-centre rays for footprint and placement tasks.

- [ ] **Step 1: Write camera, reverse-Z, and ray controls**

Test zero/90-degree bearing, zero/near-horizon pitch, `D=H(1+sqrt(2))/2`, non-square aspect, basis handedness, reverse-Z depths (`1→1`, `2→0.5`, `10→0.1`), `q=0`, `t=1`, physical `(i+0.5,j+0.5)` centres, horizon/sky, near clip, and finite hit coordinates.

- [ ] **Step 2: Run and verify red**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.internal.projection.CameraMatricesTest"
```

- [ ] **Step 3: Implement exact camera/ray variants**

```kotlin
internal data class MercatorGroundPoint(val x: Double, val y: Double)

internal data class ResolvedMercatorCamera(
    val outputPixelSize: OutputPixelSize,
    val mercatorAnchor: MercatorPosition,
    val worldSizeLogicalPixels: Double,
    val right: DoubleVector3,
    val cameraUp: DoubleVector3,
    val cameraBack: DoubleVector3,
    val cameraDistanceLogicalPixels: Double,
    val viewMatrix: DoubleMatrix4,
    val projectionMatrix: DoubleMatrix4,
)

internal sealed interface GroundRayResult {
    data object HorizonOrSky : GroundRayResult
    data object NearClipped : GroundRayResult
    data class Hit(
        val point: MercatorGroundPoint,
        val q: Double,
        val t: Double,
    ) : GroundRayResult
}

internal fun resolveMercatorCamera(
    camera: Camera,
    outputPixelSize: OutputPixelSize,
): SpatialOutcome<ResolvedMercatorCamera>

internal fun physicalPixelGroundRay(
    camera: ResolvedMercatorCamera,
    pixelX: Int,
    pixelY: Int,
): GroundRayResult
```

Use 45° vertical FOV, near `1.0`, infinite-far reverse-Z, and no Float conversion. Pixel indices are checked against the retained output size.

- [ ] **Step 4: Run cross-runtime tests and commit**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.internal.projection.CameraMatricesTest"
./gradlew --no-configuration-cache :kmp:macosArm64Test \
  --tests "com.rohittp.reng.internal.projection.CameraMatricesTest"
git add kmp/src/commonMain/kotlin/com/rohittp/reng/internal/projection/CameraMatrices.kt \
  kmp/src/commonTest/kotlin/com/rohittp/reng/internal/projection/CameraMatricesTest.kt
git commit -m "feat: add Mercator camera ray core"
```

---

### Task 8C: Closed Mercator Ground Footprint

**Files:**
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/projection/MercatorGroundFootprint.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/projection/MercatorGroundFootprintTest.kt`

**Interfaces:**
- Consumes: Task 8B camera/ray seam.
- Produces: immutable support-clipped empty/point/segment/polygon footprint for tile selection.

- [ ] **Step 1: Write closed-footprint controls**

Test 1×1 point, 1×N/N×1 segments, convex polygons, tangent clipping, all four support planes, above-horizon rows, near-clipped rows, and empty out-of-support views. Assert vertices are deterministic and no duplicate closing vertex is retained.

- [ ] **Step 2: Run and verify red**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.internal.projection.MercatorGroundFootprintTest"
```

- [ ] **Step 3: Implement the exact closed variants**

```kotlin
internal sealed interface ClosedMercatorFootprint {
    data object Empty : ClosedMercatorFootprint
    data class Point(val point: MercatorGroundPoint) : ClosedMercatorFootprint
    data class Segment(
        val start: MercatorGroundPoint,
        val end: MercatorGroundPoint,
    ) : ClosedMercatorFootprint
    class Polygon(vertices: List<MercatorGroundPoint>) : ClosedMercatorFootprint {
        val vertices: List<MercatorGroundPoint>
    }
}

internal fun clippedPhysicalPixelFootprint(
    camera: ResolvedMercatorCamera,
): ClosedMercatorFootprint
```

Build the closed pixel-centre rectangle from the contiguous admissible row set, then clip against `x∈[-16384,16385]`, `y∈[0,1]`. `Polygon` snapshots and fresh-copies vertices and implements structural equality/hash.

- [ ] **Step 4: Run cross-runtime tests and commit**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.internal.projection.MercatorGroundFootprintTest"
./gradlew --no-configuration-cache :kmp:macosArm64Test \
  --tests "com.rohittp.reng.internal.projection.MercatorGroundFootprintTest"
git add kmp/src/commonMain/kotlin/com/rohittp/reng/internal/projection/MercatorGroundFootprint.kt \
  kmp/src/commonTest/kotlin/com/rohittp/reng/internal/projection/MercatorGroundFootprintTest.kt
git commit -m "feat: add clipped Mercator footprint"
```

---

### Task 9A: Mercator LOD and Closed-Cell Tile Selection

**Files:**
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/planning/MercatorLod.kt`
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/planning/BasemapTileSelector.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/planning/MercatorLodTest.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/planning/BasemapTileSelectorTest.kt`

**Interfaces:**
- Consumes: Task 8C closed footprint.
- Produces: provisional LOD and deterministic unwrapped/canonical tile selection for Task 9D.

- [ ] **Step 1: Write exact LOD and tile controls**

Cover nearest/ties-down no-history selection, exact hysteresis equalities, multi-level jumps, bounds, suppressed-basemap LOD, four cells at a shared vertex, two cells at a shared edge, epsilon boundaries, support clamps, negative floor division, deterministic ordering/deduplication, world-copy budget before deduplication, and exact over-budget actual count without a partial selection.

- [ ] **Step 2: Run and verify red**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.internal.planning.MercatorLodTest" \
  --tests "com.rohittp.reng.internal.planning.BasemapTileSelectorTest"
```

- [ ] **Step 3: Implement exact tile result types and functions**

```kotlin
internal data class LodObservation(val selectedLod: Int)
internal data class BasemapTileInstance(
    val lod: Int,
    val tileY: Int,
    val unwrappedX: Long,
    val instanceCopy: Int,
    val canonicalX: Int,
)
internal data class CanonicalBasemapTile(
    val lod: Int,
    val tileY: Int,
    val canonicalX: Int,
)
internal sealed interface TileSelectionOutcome {
    class Success(
        instances: List<BasemapTileInstance>,
        canonicalResources: List<CanonicalBasemapTile>,
    ) : TileSelectionOutcome {
        val instances: List<BasemapTileInstance>
        val canonicalResources: List<CanonicalBasemapTile>
    }
    data class OverBudget(val limit: Int, val actual: Long) : TileSelectionOutcome
}

internal fun observeMercatorLod(
    zoom: Double,
    previousSelectedLod: Int?,
): LodObservation
internal fun selectBasemapTiles(
    footprint: ClosedMercatorFootprint,
    lod: Int,
    maximumInstances: Int,
): TileSelectionOutcome
```

Count intersections with checked `Long` candidate bounds/iteration before materializing an over-budget list; LOD 22 unwrapped x reaches approximately ±68.7 billion and must never narrow to `Int`. Success instances sort `(tileY,unwrappedX)` and canonical resources deduplicate/sort `(lod,tileY,canonicalX)`.

- [ ] **Step 4: Run cross-runtime tests and commit**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.internal.planning.MercatorLodTest" \
  --tests "com.rohittp.reng.internal.planning.BasemapTileSelectorTest"
./gradlew --no-configuration-cache :kmp:macosArm64Test \
  --tests "com.rohittp.reng.internal.planning.MercatorLodTest" \
  --tests "com.rohittp.reng.internal.planning.BasemapTileSelectorTest"
git add kmp/src/commonMain/kotlin/com/rohittp/reng/internal/planning/{MercatorLod.kt,BasemapTileSelector.kt} \
  kmp/src/commonTest/kotlin/com/rohittp/reng/internal/planning/{MercatorLodTest.kt,BasemapTileSelectorTest.kt}
git commit -m "feat: add deterministic basemap tile selection"
```

---

### Task 9B: GPU Representability, Placement, and Geometry Resolution

**Files:**
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/planning/GpuRepresentability.kt`
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/planning/PlacementResolver.kt`
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/planning/GeometryResolver.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/planning/GpuRepresentabilityTest.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/planning/PlacementResolverTest.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/planning/GeometryResolverTest.kt`

**Interfaces:**
- Consumes: Tasks 3, 4B, 8A, and 8B.
- Produces: sanitized placement/geometry outcomes and CPU-side screen z for Task 9D.

- [ ] **Step 1: Write placement, geometry, and representability controls**

Cover all eight anchoring combinations, position-only draw regime, nonperiodic map displacement, `V*B(c)^T*B(a)*Q`, copy-equivalent/different anchors, screen-position MAP fallback to camera, MAP/SCREEN scale, zero scale, CPU-only screen z, `Float.MAX_VALUE`, and failures for screen x/y, map/geometry altitude, and converted scale with exact diagnostic fields. Resolve Geometry to the exact clockwise input-corner sequence `topLeft`, `Vector3(topLeft.x, bottomRight.y, topLeft.z)`, `bottomRight`, `Vector3(bottomRight.x, topLeft.y, bottomRight.z)` and map-resolve each in that order. This uses `topLeft.z` on the north edge and `bottomRight.z` on the south edge, preserves unwrapped longitude, and leaves no renderer-selected winding.

- [ ] **Step 2: Run and verify red**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.internal.planning.GpuRepresentabilityTest" \
  --tests "com.rohittp.reng.internal.planning.PlacementResolverTest" \
  --tests "com.rohittp.reng.internal.planning.GeometryResolverTest"
```

- [ ] **Step 3: Implement exact resolved values and functions**

```kotlin
internal enum class DrawRegime { MAP_OCCLUDED, SCREEN_COMPOSITED }
internal data class ResolvedPlacement(
    val drawRegime: DrawRegime,
    val logicalPosition: DoubleVector3,
    val directionTransform: DoubleMatrix3,
    val logicalScale: Double,
    val screenCompositeZ: Double?,
)
internal class ResolvedGeometry(
    cornersClockwiseFromTopLeft: List<DoubleVector3>,
    val shaderPair: ShaderPair,
) {
    val cornersClockwiseFromTopLeft: List<DoubleVector3>
}

internal fun resolvePlacement(
    placement: Placement,
    camera: ResolvedMercatorCamera,
): SpatialOutcome<ResolvedPlacement>
internal fun resolveGeometry(
    geometry: Geometry,
    camera: ResolvedMercatorCamera,
): SpatialOutcome<ResolvedGeometry>
```

`ResolvedGeometry` requires exactly four entries in the frozen `topLeft → topRight → bottomRight → bottomLeft` clockwise sequence, snapshots them, returns a fresh list, and uses structural equality/hash. `screenCompositeZ` is nonnull exactly for `SCREEN_COMPOSITED`; it remains Double and is never part of GPU conversion. All Float conversions are terminal representability checks over camera-relative values and never feed planning.

- [ ] **Step 4: Run cross-runtime tests and commit**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.internal.planning.GpuRepresentabilityTest" \
  --tests "com.rohittp.reng.internal.planning.PlacementResolverTest" \
  --tests "com.rohittp.reng.internal.planning.GeometryResolverTest"
./gradlew --no-configuration-cache :kmp:macosArm64Test \
  --tests "com.rohittp.reng.internal.planning.GpuRepresentabilityTest" \
  --tests "com.rohittp.reng.internal.planning.PlacementResolverTest" \
  --tests "com.rohittp.reng.internal.planning.GeometryResolverTest"
git add kmp/src/commonMain/kotlin/com/rohittp/reng/internal/planning/{GpuRepresentability.kt,PlacementResolver.kt,GeometryResolver.kt} \
  kmp/src/commonTest/kotlin/com/rohittp/reng/internal/planning/{GpuRepresentabilityTest.kt,PlacementResolverTest.kt,GeometryResolverTest.kt}
git commit -m "feat: add placement and geometry resolution"
```

---

### Task 9C: Deterministic Shader Profile Planning

**Files:**
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/shader/ShaderProfilePlanner.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/shader/ShaderProfilePlannerTest.kt`

**Interfaces:**
- Consumes: Task 3 exact shader source strings and Task 4B failure descriptors.
- Produces: original GLES source plus exact UTF-16 desktop replacement span; compiles nothing.

- [ ] **Step 1: Write scanner and substitution controls**

Accept start/EOF directive, ASCII trim, LF/CRLF/CR, blank/comment prefix lines, multiline non-nesting block comments, and exact desktop span replacement. Reject BOM, non-ASCII whitespace, missing/alternate version, prefix token, unterminated block comment, comment sharing directive line, trailing comment/token, and comment-only EOF. Assert GLES source unchanged and desktop preserves every nonspan code unit.

- [ ] **Step 2: Run and verify red**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.internal.shader.ShaderProfilePlannerTest"
```

- [ ] **Step 3: Implement exact profile values and functions**

```kotlin
internal class ShaderProfilePlan(
    val originalSource: String,
    val directiveStartUtf16: Int,
    val directiveEndExclusiveUtf16: Int,
) {
    internal fun gles300Source(): String
    internal fun desktop330Source(): String
}
internal fun scanShaderProfile(source: String): ShaderProfilePlan?
```

`gles300Source()` returns the original string object/content unchanged. `desktop330Source()` replaces only the half-open directive span with `#version 330 core`. The planner stores no compiled shader and `toString()` is redacted.

- [ ] **Step 4: Run cross-runtime tests and commit**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.internal.shader.ShaderProfilePlannerTest"
./gradlew --no-configuration-cache :kmp:macosArm64Test \
  --tests "com.rohittp.reng.internal.shader.ShaderProfilePlannerTest"
git add kmp/src/commonMain/kotlin/com/rohittp/reng/internal/shader/ShaderProfilePlanner.kt \
  kmp/src/commonTest/kotlin/com/rohittp/reng/internal/shader/ShaderProfilePlannerTest.kt
git commit -m "feat: add deterministic shader profile planning"
```

---

### Task 9D: Integrated Mercator Spatial Plan and Screen Ordering

**Files:**
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/planning/MercatorSpatialPlanner.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/planning/MercatorSpatialPlannerTest.kt`

**Interfaces:**
- Consumes: Tasks 3, 8B–8C, and 9A–9C.
- Produces: one immutable `MercatorSpatialPlan` for frame planning.

- [ ] **Step 1: Write integrated active/suppressed basemap and draw-order tests**

Assert active basemap is exactly `plan.drawBasemap && basemapStyleConfigured`; inactive basemap still advances provisional LOD but produces no footprint, budget, instances, or resources. Assert geometry/shader failures occur in plan order. Expose separate map-regime entries and screen-compositing entries. Sort screen entries by ascending z so greater z composites later; for equal z preserve sticker order, then model order. Test mixed sticker/model equal-z ties, unequal z, duplicate entries, and map entries remaining in plan order.

- [ ] **Step 2: Run and verify red**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.internal.planning.MercatorSpatialPlannerTest"
```

- [ ] **Step 3: Implement the exact integration seam**

```kotlin
internal sealed interface DrawnThingReference {
    data class StickerAt(val index: Int) : DrawnThingReference
    data class ModelAt(val index: Int) : DrawnThingReference
}
internal data class ResolvedDrawnThing(
    val reference: DrawnThingReference,
    val placement: ResolvedPlacement,
)
internal class MercatorSpatialPlan(
    val camera: ResolvedMercatorCamera,
    val lodObservation: LodObservation,
    val footprint: ClosedMercatorFootprint?,
    val tileSelection: TileSelectionOutcome.Success?,
    mapEntries: List<ResolvedDrawnThing>,
    screenEntries: List<ResolvedDrawnThing>,
    geometries: List<ResolvedGeometry>,
    shaderProfiles: List<Pair<ShaderProfilePlan, ShaderProfilePlan>>,
) {
    val mapEntries: List<ResolvedDrawnThing>
    val screenEntries: List<ResolvedDrawnThing>
    val geometries: List<ResolvedGeometry>
    val shaderProfiles: List<Pair<ShaderProfilePlan, ShaderProfilePlan>>
}

internal fun planMercatorSpatial(
    plan: FramePlan,
    outputPixelSize: OutputPixelSize,
    previousSelectedLod: Int?,
    maximumBasemapTileInstances: Int,
    basemapStyleConfigured: Boolean,
): SpatialOutcome<MercatorSpatialPlan>
```

Snapshot every list and return fresh copies. Convert `TileSelectionOutcome.OverBudget` to the exact sanitized frame-planning limit failure; no partial selection escapes.

- [ ] **Step 4: Run cross-runtime tests and commit**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.internal.planning.MercatorSpatialPlannerTest"
./gradlew --no-configuration-cache :kmp:macosArm64Test \
  --tests "com.rohittp.reng.internal.planning.MercatorSpatialPlannerTest"
git add kmp/src/commonMain/kotlin/com/rohittp/reng/internal/planning/MercatorSpatialPlanner.kt \
  kmp/src/commonTest/kotlin/com/rohittp/reng/internal/planning/MercatorSpatialPlannerTest.kt
git commit -m "feat: integrate Mercator spatial planning"
```

---

### Task 10: Integrated Pure Frame Planning

**Files:**
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/planning/FramePlanningCore.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/planning/FramePlanningCoreTest.kt`

**Interfaces:**
- Consumes: Tasks 1, 7, and 9D.
- Produces: immutable `FramePlanningRequest`, `PlannedFrameCore`, `FramePlanningOutcome` used by ordered preparation, with every static external reference carrying its separately supplied opaque private Rentile key.

- [ ] **Step 1: Write barrier-order and integration tests**

```kotlin
internal data class FramePlanningRequest(
    val plan: FramePlan,
    val outputPixelSize: OutputPixelSize,
    val basemapStyle: ResourceLocator?,
    val resourceLimits: ResourceLimits,
    val maximumBasemapTileInstances: Int,
    val previousPlan: EncodedFramePlan?,
    val previousSelectedLod: Int?,
)

internal sealed interface StaticResourceReference {
    val resourceKey: ResourceKey
    val rawKey: RawResourceKey?
    val canonicalIdentity: HashedCanonicalBytes

    data class External(
        val resourceClass: ResourceClass,
        val locator: ResourceLocator,
        val maximumResponseBytes: Long,
        override val resourceKey: ResourceKey,
        override val rawKey: RawResourceKey,
        val privateRentileKey: RentilePrivateKey,
        override val canonicalIdentity: HashedCanonicalBytes,
    ) : StaticResourceReference

    data class GeometryProgram(
        val shaderPair: ShaderPair,
        override val resourceKey: ResourceKey,
        override val canonicalIdentity: HashedCanonicalBytes,
    ) : StaticResourceReference {
        override val rawKey: RawResourceKey? = null
    }
}

internal class PlannedFrameCore(
    val encodedPlan: EncodedFramePlan,
    val structuralDiff: FrameStructuralDiff,
    val spatialPlan: MercatorSpatialPlan,
    staticResourceTraversal: List<StaticResourceReference>,
) {
    val staticResourceTraversal: List<StaticResourceReference>
}

internal sealed interface FramePlanningOutcome {
    data class Success(val planned: PlannedFrameCore) : FramePlanningOutcome
    data class Failure(val failure: FailureDescriptor) : FramePlanningOutcome
}
```

Test `GLOBE` precedence, camera latitude before copy, shader validation at planning barrier, every successful Mercator LOD observation, suppressed basemap no footprint/budget/routes, active basemap exact tile budget, canonical segments/diff, static direct resource order (style; stickers; each model GLB/texture; geometry programs), input duplicate/order preservation, frame canonical collision mapping to `IDENTITY_COLLISION / FRAME_PLANNING` with only `frameIdentity`, and no resource-engine side effect. Inject a fake `RentilePrivateKeyResolver`, assert it is called exactly once for each static external `(locator,class)` and never for geometry programs, and preserve its exact opaque key separately from `RawResourceKey`; include two distinct locators deliberately resolved to one equal private key so Task 12A can detect the batch collision without any Rentile call.

- [ ] **Step 2: Run and verify red**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.internal.planning.FramePlanningCoreTest"
```

- [ ] **Step 3: Implement one injected deterministic planner entry point**

```kotlin
internal class FramePlanningCore(
    private val frameEncoder: FramePlanCanonicalEncoder,
    private val frameIdentityRegistry: CanonicalIdentityRegistry,
    private val resourceKeyDeriver: ResourceKeyDeriver,
    private val rentilePrivateKeyResolver: RentilePrivateKeyResolver,
) {
    internal fun plan(request: FramePlanningRequest): FramePlanningOutcome
}
```

Construct the core explicitly at test/composition sites with `FramePlanCanonicalEncoder(PureKotlinSha256)`, a renderer-owned frame `CanonicalIdentityRegistry`, `ResourceKeyDeriver(PureKotlinSha256)`, and a supplied `RentilePrivateKeyResolver`. Cycle B test sites use deterministic fakes; later integration may wrap Rentile's credential-sanitized private-key derivation, but this core never calls or owns Rentile. The private key is stored only on `StaticResourceReference.External` and remains distinct from the consumer Store's `RawResourceKey`. The resource deriver is stateless beyond its hash function and never receives a registry: Task 12A's invocation-owned `CanonicalIdentityRecord` list is the sole external/geometry resource-collision seam and its fake-digest tests cover same-digest/different-bytes registrations. Frame tests inject an encoder with a constant digest and submit two byte-distinct plans to make the frame collision seam reachable. On the second frame registration, preserve the first renderer-owned frame entry and return `FailureDescriptor(RenGErrorCode.IDENTITY_COLLISION, PipelineStage.FRAME_PLANNING, diagnostic with only frameIdentity)`. Validation order follows the specification and returns only sanitized `FailureDescriptor`. Dynamic basemap children remain discovery-frontier work; their supplied `DiscoveredResourceChild` occurrence carries the opaque private key before registration—do not fabricate locators or private keys unavailable during pure planning.

- [ ] **Step 4: Run cross-runtime tests and commit**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.internal.planning.FramePlanningCoreTest"
./gradlew --no-configuration-cache :kmp:macosArm64Test \
  --tests "com.rohittp.reng.internal.planning.FramePlanningCoreTest"
git add kmp/src/commonMain/kotlin/com/rohittp/reng/internal/planning/FramePlanningCore.kt \
  kmp/src/commonTest/kotlin/com/rohittp/reng/internal/planning/FramePlanningCoreTest.kt
git commit -m "feat: integrate pure frame planning"
```

---

### Task 11: Renderer Lifecycle State Machine

**Files:**
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/lifecycle/RendererLifecycleProtocol.kt`
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/lifecycle/RendererLifecycleStateMachine.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/lifecycle/RendererLifecycleMatrixTest.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/lifecycle/RendererLifecyclePrecedenceTest.kt`

**Interfaces:**
- Consumes: Task 4B `FailureDescriptor` and Task 4C public operation types.
- Produces: pure begin/resume lifecycle reducer and immutable actions.

- [ ] **Step 1: Write the total state matrix tests**

Table every public operation across `LIVE`, `AWAITING_CONTEXT_ADOPTION`, and `CLOSED`. Assert post-close no-ops/empty results; exact failure stages; live adoption failure without context observation; awaiting/closed behavior; Prepared Frame close idempotence.

- [ ] **Step 2: Write combined-conflict precedence traces**

Assert active-preparation interference, closed, awaiting, frame owner/closed, target owner/generation, exact context, deferred deletion, framebuffer, operation work in that order. Assert context failure leaves the entire deletion queue. Model each deferred deletion as request then acknowledgement: after acknowledged deletions `0` and `1`, a failure deleting `2` must retain `0`/`1` as deleted, leave only `2` plus later handles pending in their original order, and prevent framebuffer/work. Retrying must not re-delete acknowledged handles. Invalid framebuffer has only `renderTarget` field.

- [ ] **Step 3: Run and verify red**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.internal.lifecycle.*"
```

- [ ] **Step 4: Implement the exact lazy-observation protocol and reducer**

```kotlin
internal enum class RendererOwnerState { LIVE, AWAITING_CONTEXT_ADOPTION, CLOSED }
@JvmInline internal value class DeletionId(val value: Long)
internal data class DeferredDeletion(
    val id: DeletionId,
    val resourceKey: ResourceKey?,
)
internal class GpuLedger(
    val hasLiveGpuObjects: Boolean,
    deferredDeletions: List<DeferredDeletion>,
) {
    val deferredDeletions: List<DeferredDeletion>
}
internal data class RendererLifecycleSnapshot(
    val ownerState: RendererOwnerState,
    val contextGeneration: Long,
    val preparationActive: Boolean,
    val gpuLedger: GpuLedger,
)

internal sealed interface PreparedFrameFact {
    data object OwnedOpen : PreparedFrameFact
    data object OwnedClosed : PreparedFrameFact
    data object Foreign : PreparedFrameFact
}
internal sealed interface RenderTargetFact {
    data class OwnedCurrent(val framebufferName: FramebufferName) : RenderTargetFact
    data object Foreign : RenderTargetFact
    data object Stale : RenderTargetFact
}
internal enum class ExactContextFact { EXACT, NONE, DIFFERENT }
internal enum class AdoptionContextFact { SUPPORTED, NONE, UNSUPPORTED }
internal enum class FramebufferFact { COMPLETE, MISSING_OR_INCOMPLETE }

internal sealed interface RendererLifecycleOperation {
    data object BeginPreparation : RendererLifecycleOperation
    data object CancelPreparations : RendererLifecycleOperation
    data object ClearFrameHistory : RendererLifecycleOperation
    data class QueryResources(val selector: ResourceSelector) : RendererLifecycleOperation
    data class FreeResources(val selector: ResourceSelector) : RendererLifecycleOperation
    data object NotifyGpuObjectsGone : RendererLifecycleOperation
    data object AdoptCurrentRenderContext : RendererLifecycleOperation
    data class MintRenderTarget(val framebufferName: FramebufferName) : RendererLifecycleOperation
    data class Draw(
        val frame: PreparedFrameFact,
        val target: RenderTargetFact,
    ) : RendererLifecycleOperation
    data class ClosePreparedFrame(val frame: PreparedFrameFact) : RendererLifecycleOperation
    data object CloseRenderer : RendererLifecycleOperation
}

internal sealed interface RendererLifecycleAction {
    data object AwaitRenderCallQuiescence : RendererLifecycleAction
    data object ObserveExactCurrentContext : RendererLifecycleAction
    data object ObserveAdoptableCurrentContext : RendererLifecycleAction
    data class DeleteDeferred(val deletion: DeferredDeletion) : RendererLifecycleAction
    data class ValidateFramebuffer(val framebufferName: FramebufferName) : RendererLifecycleAction
    data object RequestPreparationCancellation : RendererLifecycleAction
    data class ExecutePermittedOperation(
        val operation: RendererLifecycleOperation,
    ) : RendererLifecycleAction
}

internal sealed interface RendererLifecycleCursor {
    val snapshot: RendererLifecycleSnapshot
    val operation: RendererLifecycleOperation

    data class AwaitingRenderCallQuiescence(
        override val snapshot: RendererLifecycleSnapshot,
        override val operation: RendererLifecycleOperation,
    ) : RendererLifecycleCursor
    data class AwaitingExactContext(
        override val snapshot: RendererLifecycleSnapshot,
        override val operation: RendererLifecycleOperation,
    ) : RendererLifecycleCursor
    data class AwaitingAdoptionContext(
        override val snapshot: RendererLifecycleSnapshot,
        override val operation: RendererLifecycleOperation.AdoptCurrentRenderContext,
    ) : RendererLifecycleCursor
    data class AwaitingDeferredDeletion(
        override val snapshot: RendererLifecycleSnapshot,
        override val operation: RendererLifecycleOperation,
        val deletionId: DeletionId,
    ) : RendererLifecycleCursor
    data class AwaitingFramebuffer(
        override val snapshot: RendererLifecycleSnapshot,
        override val operation: RendererLifecycleOperation,
        val framebufferName: FramebufferName,
    ) : RendererLifecycleCursor
    data class AwaitingPreparationTermination(
        override val snapshot: RendererLifecycleSnapshot,
        override val operation: RendererLifecycleOperation,
    ) : RendererLifecycleCursor
    data class AwaitingPermittedOperation(
        override val snapshot: RendererLifecycleSnapshot,
        override val operation: RendererLifecycleOperation,
    ) : RendererLifecycleCursor
}

internal sealed interface RendererLifecycleObservation {
    data object RenderCallsQuiesced : RendererLifecycleObservation
    data class ExactContextObserved(val fact: ExactContextFact) : RendererLifecycleObservation
    data class AdoptionContextObserved(val fact: AdoptionContextFact) : RendererLifecycleObservation
    data class DeferredDeletionAcknowledged(val deletionId: DeletionId) : RendererLifecycleObservation
    data class DeferredDeletionFailed(
        val deletionId: DeletionId,
        val failure: FailureDescriptor,
    ) : RendererLifecycleObservation
    data class FramebufferObserved(val fact: FramebufferFact) : RendererLifecycleObservation
    data object PreparationTerminated : RendererLifecycleObservation
    data object PermittedOperationSucceeded : RendererLifecycleObservation
    data class PermittedOperationFailed(
        val failure: FailureDescriptor,
    ) : RendererLifecycleObservation
}

internal sealed interface RendererLifecycleOutcome {
    data object Succeeded : RendererLifecycleOutcome
    data object NoOp : RendererLifecycleOutcome
    data object EmptyResourceResult : RendererLifecycleOutcome
    data class Failed(val failure: FailureDescriptor) : RendererLifecycleOutcome
}
internal class RendererLifecycleTransition(
    val snapshot: RendererLifecycleSnapshot,
    actions: List<RendererLifecycleAction>,
    val cursor: RendererLifecycleCursor?,
    val outcome: RendererLifecycleOutcome?,
) {
    val actions: List<RendererLifecycleAction>
}

internal object RendererLifecycleStateMachine {
    internal fun begin(
        snapshot: RendererLifecycleSnapshot,
        operation: RendererLifecycleOperation,
    ): RendererLifecycleTransition
    internal fun resume(
        cursor: RendererLifecycleCursor,
        observation: RendererLifecycleObservation,
    ): RendererLifecycleTransition
}
```

Every transition has exactly one of cursor/outcome nonnull; every cursor accepts only its matching observation. `GpuLedger` and transition actions snapshot/fresh-copy lists. Implement object-loss generation increment/forget, live-only adoption, owner-wide terminal close, exact-context/deletion phases, and sanitized failure precedence. A deletion acknowledgement removes exactly the current queue entry from the cursor snapshot before the next deletion action; a failure retains that entry and suffix while acknowledged prefixes remain removed. No action calls a context/GL API.

- [ ] **Step 5: Run cross-runtime tests and commit**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.internal.lifecycle.*"
./gradlew --no-configuration-cache :kmp:macosArm64Test \
  --tests "com.rohittp.reng.internal.lifecycle.*"
git add kmp/src/commonMain/kotlin/com/rohittp/reng/internal/lifecycle \
  kmp/src/commonTest/kotlin/com/rohittp/reng/internal/lifecycle
git commit -m "feat: add renderer lifecycle decisions"
```

---

### Task 12A: Resource Protocol, Static Registration, and Collision Admission

**Files:**
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/resource/ResourceOperationProtocol.kt`
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/resource/ResourceOperationStateMachine.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/resource/ResourceOperationProtocolTest.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/resource/ResourceOperationRegistrationTest.kt`

**Interfaces:**
- Consumes: Tasks 1, 2, 4A–4B, and 6. Registrations receive already-derived keys/canonical bytes and derive no hash.
- Produces: immutable redacted route/occurrence/state/action/event vocabulary plus static identity-only preregistration.

- [ ] **Step 1: Write protocol and static-registration tests**

Assert route/latch/registration equality while every textual form redacts locator, validators, metadata, canonical bytes, opaque private Rentile tokens, and keys beyond credential-free `ResourceKey`. Assert every list is snapshotted/fresh-copied. Call the final `preRegister(definition)` helper with repeated equal routes and prove they join one record but receive no ordinal/sample/work from preregistration. Construct two distinct Route Keys with different consumer `RawResourceKey`s but one equal `RentilePrivateKey` and assert `AMBIGUOUS_RESOURCE_ROUTE / RESOURCE_LOOKUP` with only field `resource` and zero actions; conversely, equal consumer raw keys alone never define this collision. Same stable digest with different canonical bytes—both for external occurrence identities and geometry-program identity-only records—fails `IDENTITY_COLLISION / RESOURCE_LOOKUP`, retains the first identity record, includes established class/key when external and the established key with null class for geometry, and emits zero work. No protocol type accepts an adapter, cache, parser, clock, context, GL object, throwable, or coroutine type.

- [ ] **Step 2: Run and verify red**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.internal.resource.ResourceOperationProtocolTest" \
  --tests "com.rohittp.reng.internal.resource.ResourceOperationRegistrationTest"
```

- [ ] **Step 3: Implement the exact common resource protocol and final preregistration helper**

```kotlin
internal enum class CancellationCause { CALLER, CANCEL_PREPARATIONS, ADAPTER }
@JvmInline internal value class CancellationId(val value: Long)
internal data class CancellationSelection(
    val cause: CancellationCause,
    val id: CancellationId,
)
@JvmInline internal value class ResourceOwnerId(val value: Long)
@JvmInline internal value class ResourceOccurrenceId(val value: Long)
@JvmInline internal value class SpriteGroupId(val value: Long)
@JvmInline internal value class StyleGroupId(val value: Long)
@JvmInline internal value class ResourceActionId(val value: Long)

internal class ResourceRouteKey(
    val accessMode: ResourceAccessMode,
    val locator: ResourceLocator,
    val resourceClass: ResourceClass,
    val maximumResponseBytes: Long,
)
internal class TransportLatchKey(
    val route: ResourceRouteKey,
    val ifNoneMatch: String?,
    val ifModifiedSince: String?,
    val accept: String?,
)
internal data class ResourceRouteRegistration(
    val route: ResourceRouteKey,
    val resourceKey: ResourceKey,
    val rawKey: RawResourceKey,
    val privateRentileKey: RentilePrivateKey,
    val canonicalBytes: CanonicalBytes,
)
internal enum class SpriteMember { JSON, IMAGE }
internal sealed interface ResourceCommitBinding {
    data object Single : ResourceCommitBinding
    data class Sprite(
        val groupId: SpriteGroupId,
        val member: SpriteMember,
    ) : ResourceCommitBinding
    data class BasemapStyle(val groupId: StyleGroupId) : ResourceCommitBinding
}
internal data class ResourceOccurrence(
    val id: ResourceOccurrenceId,
    val ownerId: ResourceOwnerId,
    val registration: ResourceRouteRegistration,
    val discoveryRequired: Boolean,
    val commitBinding: ResourceCommitBinding,
)
internal class ResourceOperationDefinition(
    val maximumConcurrentRoutes: Int,
    staticOccurrences: List<ResourceOccurrence>,
    resourceIdentities: List<CanonicalIdentityRecord>,
) {
    val staticOccurrences: List<ResourceOccurrence>
    val resourceIdentities: List<CanonicalIdentityRecord>
}

internal enum class ResourceRouteStatus {
    PREREGISTERED, ELIGIBLE, RUNNING, RESOLVED, BLOCKED_BY_COLLISION,
}
internal sealed interface ResourceRouteCursor
internal class RouteRecord(
    val registration: ResourceRouteRegistration,
    joinedOccurrenceIds: List<ResourceOccurrenceId>,
    val ordinal: Long?,
    val cursor: ResourceRouteCursor?,
    val status: ResourceRouteStatus,
) {
    val joinedOccurrenceIds: List<ResourceOccurrenceId>
}
internal data class PrivateRentileKeyClaim(
    val privateKey: RentilePrivateKey,
    val firstRoute: ResourceRouteKey,
    val usable: Boolean,
)
internal data class CanonicalIdentityRecord(
    val resourceKey: ResourceKey,
    val canonicalBytes: CanonicalBytes,
)
internal sealed interface ResourceOperationEvent
internal sealed interface ResourceOperationAction
internal sealed interface ResourceOperationOutcome {
    data class Failure(val failure: FailureDescriptor) : ResourceOperationOutcome
    data class Cancelled(
        val cancellation: CancellationSelection,
    ) : ResourceOperationOutcome
}
internal sealed interface ResourceOperationState {
    class Running(
        val definition: ResourceOperationDefinition,
        occurrences: List<ResourceOccurrence>,
        routeRecords: List<RouteRecord>,
        privateRentileKeyClaims: List<PrivateRentileKeyClaim>,
        identityRecords: List<CanonicalIdentityRecord>,
    ) : ResourceOperationState {
        val occurrences: List<ResourceOccurrence>
        val routeRecords: List<RouteRecord>
        val privateRentileKeyClaims: List<PrivateRentileKeyClaim>
        val identityRecords: List<CanonicalIdentityRecord>
    }
}
internal class ResourceOperationTransition(
    val state: ResourceOperationState.Running?,
    actions: List<ResourceOperationAction>,
    val outcome: ResourceOperationOutcome?,
) {
    val actions: List<ResourceOperationAction>
}

internal object ResourceOperationStateMachine {
    internal fun preRegister(
        definition: ResourceOperationDefinition,
    ): ResourceOperationTransition
}
```

`preRegister` is the final collision-admission helper: it only snapshots/preregisters, detects static collisions, and returns either the admitted `Running` state with zero actions or a terminal failure. Private-route admission indexes only `ResourceRouteRegistration.privateRentileKey`; `rawKey` remains exclusively the consumer Store key and never participates in `AMBIGUOUS_RESOURCE_ROUTE`. On collision the `PrivateRentileKeyClaim` for that opaque token becomes unusable while its token remains text-redacted. Task 12A deliberately does not declare or test `ResourceOperationStateMachine.start`; Task 12B adds that final scheduler entry point and calls `preRegister` before assigning the first eligibility ordinal. `Running.occurrences` is a complete copy-backed registry initialized from every static occurrence; Task 12B appends each admitted dynamic occurrence before exposing its ID to traversal. Task 12B adds its final traversal/eligibility fields to `Running`, and Task 12C later adds its final retirement/terminal fields—Task 12A declares no temporary placeholder types. Validate positive IDs/concurrency without revealing sensitive values. Final successful visibility aggregation does not exist yet: Task 14A adds `VisibleResource`, `OwnerResourceSet`, and `ResourceOperationOutcome.Success` only after resolved content and class gates exist.

- [ ] **Step 4: Run cross-runtime tests and commit**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.internal.resource.ResourceOperationProtocolTest" \
  --tests "com.rohittp.reng.internal.resource.ResourceOperationRegistrationTest"
./gradlew --no-configuration-cache :kmp:macosArm64Test \
  --tests "com.rohittp.reng.internal.resource.ResourceOperationProtocolTest" \
  --tests "com.rohittp.reng.internal.resource.ResourceOperationRegistrationTest"
git add kmp/src/commonMain/kotlin/com/rohittp/reng/internal/resource \
  kmp/src/commonTest/kotlin/com/rohittp/reng/internal/resource/{ResourceOperationProtocolTest.kt,ResourceOperationRegistrationTest.kt}
git commit -m "feat: add resource operation protocol"
```

---

### Task 12B: Depth-First Frontiers and Eligibility Ordinals

**Files:**
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/resource/ResourceOperationProtocol.kt`
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/resource/ResourceOperationStateMachine.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/resource/ResourceOperationSchedulingTest.kt`

**Interfaces:**
- Consumes: Task 12A preregistered running state.
- Produces: canonical child ordering, withheld depth-first continuation, eligibility-time ordinals, joined occurrences, and bounded FIFO start/discovery actions.

- [ ] **Step 1: Write the exact frontier trace and ordering tables**

For static roots `[A(frontier), B, X(later plan)]`, preregister `X`, discover `A→[X,C(frontier)]`, then `C→[D]`, and assert ordinals `A=0, X=1, C=2, D=3, B=4`; later static `X` joins ordinal 1. Assert preregistration assigns no ordinal/sample/work. Test sprite JSON before image; sources by unsigned lexicographic exact UTF-8; within source metadata before tiles and tiles by `(lod,tileY,canonicalX)`; arrays by index; object members by unsigned UTF-8 key. Reverse every supplied list and assert canonical output. Reject indistinguishable duplicate descriptors.

- [ ] **Step 2: Run and verify red**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.internal.resource.ResourceOperationSchedulingTest"
```

- [ ] **Step 3: Implement exact traversal, events, and actions**

```kotlin
internal sealed interface ResourceChildTraversal {
    data class BasemapSprite(val member: SpriteMember) : ResourceChildTraversal
    class BasemapSource(
        val sourceId: String,
        val member: BasemapSourceMember,
    ) : ResourceChildTraversal
    data class DeclaredArray(val index: Int) : ResourceChildTraversal
    class ObjectMember(
        val exactKey: String,
    ) : ResourceChildTraversal
}
internal sealed interface BasemapSourceMember {
    data object Metadata : BasemapSourceMember
    data class Tile(
        val lod: Int,
        val tileY: Int,
        val canonicalX: Int,
    ) : BasemapSourceMember
}
internal data class DiscoveredResourceChild(
    val traversal: ResourceChildTraversal,
    val occurrence: ResourceOccurrence,
)
internal class DiscoveryFrontier(
    val parentOccurrenceId: ResourceOccurrenceId,
    childOccurrenceIds: List<ResourceOccurrenceId>,
    withheldContinuation: List<ResourceOccurrenceId>,
) {
    val childOccurrenceIds: List<ResourceOccurrenceId>
    val withheldContinuation: List<ResourceOccurrenceId>
}
internal class TraversalState(
    eligibleFifo: List<ResourceOccurrenceId>,
    staticContinuation: List<ResourceOccurrenceId>,
    frontierStack: List<DiscoveryFrontier>,
) {
    val eligibleFifo: List<ResourceOccurrenceId>
    val staticContinuation: List<ResourceOccurrenceId>
    val frontierStack: List<DiscoveryFrontier>
}

internal class ChildrenDiscovered(
    val parentOccurrenceId: ResourceOccurrenceId,
    children: List<DiscoveredResourceChild>,
) : ResourceOperationEvent {
    val children: List<DiscoveredResourceChild>
}
internal data class RouteReadyForDiscovery(
    val ordinal: Long,
    val parentOccurrenceId: ResourceOccurrenceId,
) : ResourceOperationEvent
internal data class StartRoute(
    val ordinal: Long,
    val registration: ResourceRouteRegistration,
) : ResourceOperationAction
internal data class DiscoverChildren(
    val ordinal: Long,
    val parentOccurrenceId: ResourceOccurrenceId,
) : ResourceOperationAction

internal object ResourceOperationStateMachine {
    internal fun preRegister(
        definition: ResourceOperationDefinition,
    ): ResourceOperationTransition
    internal fun start(
        definition: ResourceOperationDefinition,
    ): ResourceOperationTransition
    internal fun transition(
        state: ResourceOperationState.Running,
        event: ResourceOperationEvent,
    ): ResourceOperationTransition
}
```

Implement the final `ResourceOperationStateMachine.start` in this commit. It first calls the retained Task 12A `preRegister` helper; a collision terminal is returned unchanged, while an admitted state is extended with traversal fields, releases only the depth-first first eligible occurrence, assigns its ordinal, and emits the first `StartRoute` (or `DiscoverChildren` only after that route resolves). No synthetic event is needed to begin scheduling. Modify `ResourceOperationState.Running` to add `traversal: TraversalState`, `nextRouteOrdinal: Long`, and fresh-copied `activeRouteOrdinals: List<Long>`—these are the final fields, not placeholders. `BasemapSource` and `ObjectMember` implement structural equality/hash manually but use shape-only `toString()` values that redact `sourceId`/`exactKey`; enclosing discovered-child/event text therefore cannot expose parsed metadata. `ChildrenDiscovered` validates/canonical-sorts its private child snapshot, returns fresh copies, then appends every admitted `child.occurrence` to the copy-backed `Running.occurrences` registry before placing its ID in a frontier/FIFO; all later owner/binding/discovery/registration lookups resolve through that registry. A dynamic identity/private-key collision also retains its full blocked occurrence, registers an ordinal failure, and emits no `StartRoute`; only equal `RentilePrivateKey` values collide, never equal consumer `RawResourceKey`s, and the private-key collision marks the existing and new claims unusable. Concurrency counts distinct started routes, not occurrences. Every joined occurrence with `discoveryRequired` still receives its own `DiscoverChildren` action before its frontier closes.

- [ ] **Step 4: Run cross-runtime tests and commit**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.internal.resource.ResourceOperationProtocolTest" \
  --tests "com.rohittp.reng.internal.resource.ResourceOperationRegistrationTest" \
  --tests "com.rohittp.reng.internal.resource.ResourceOperationSchedulingTest"
./gradlew --no-configuration-cache :kmp:macosArm64Test \
  --tests "com.rohittp.reng.internal.resource.ResourceOperationProtocolTest" \
  --tests "com.rohittp.reng.internal.resource.ResourceOperationRegistrationTest" \
  --tests "com.rohittp.reng.internal.resource.ResourceOperationSchedulingTest"
git add kmp/src/commonMain/kotlin/com/rohittp/reng/internal/resource \
  kmp/src/commonTest/kotlin/com/rohittp/reng/internal/resource/ResourceOperationSchedulingTest.kt
git commit -m "feat: add resource discovery frontiers"
```

---

### Task 12C: Ordered Route Retirement and Terminal Arbitration

**Files:**
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/resource/ResourceOperationProtocol.kt`
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/resource/ResourceOperationStateMachine.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/resource/ResourceOperationArbitrationTest.kt`

**Interfaces:**
- Consumes: Task 12B ordinal scheduler.
- Produces: buffered in-order retirement, terminal-slot arbitration, and cancellation cleanup semantics used by lookup/commit tasks.

- [ ] **Step 1: Write deterministic arbitration traces**

Complete three routes in reverse order; earliest ordinal failure wins. Adapter cancellation participates at its ordinal. Caller/`cancelPreparations` external cancellation wins only by claiming the terminal slot first. Cleanup cancellation cannot replace a selected terminal. A buffered failure sets a start ceiling and prevents new higher work while lower ordinals finish; selected terminal emits cancellation only for active higher routes and reports only after cleanup observations.

- [ ] **Step 2: Run and verify red**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.internal.resource.ResourceOperationArbitrationTest"
```

- [ ] **Step 3: Implement exact route/terminal values and events**

```kotlin
internal sealed interface ResourceRouteOutcome {
    data object Success : ResourceRouteOutcome
    data class Failure(val failure: FailureDescriptor) : ResourceRouteOutcome
    data class Cancelled(
        val cancellation: CancellationSelection,
    ) : ResourceRouteOutcome
}
internal data class BufferedRouteOutcome(
    val ordinal: Long,
    val outcome: ResourceRouteOutcome,
)
internal sealed interface ResourceTerminalSelection {
    data class Route(
        val ordinal: Long,
        val outcome: ResourceRouteOutcome,
    ) : ResourceTerminalSelection
    data class External(
        val cancellation: CancellationSelection,
    ) : ResourceTerminalSelection
}
internal data class RouteCompleted(
    val ordinal: Long,
    val outcome: ResourceRouteOutcome,
) : ResourceOperationEvent
internal data class ExternalCancellationRequested(
    val cancellation: CancellationSelection,
) : ResourceOperationEvent
internal data class CleanupCancellationObserved(
    val ordinal: Long,
) : ResourceOperationEvent
internal data class CancelRoute(
    val ordinal: Long,
) : ResourceOperationAction
```

Modify `ResourceOperationState.Running` in this commit to add the final fields `nextRetirementOrdinal: Long`, fresh-copied `bufferedRouteOutcomes: List<BufferedRouteOutcome>`, `startCeilingOrdinal: Long?`, and `terminalSelection: ResourceTerminalSelection?`. Retire only `nextRetirementOrdinal`; buffer later completions. Every `Cancelled` value preserves the opaque `CancellationSelection` ID that the integration layer maps back to the selected original `CancellationException`; the core never receives a throwable. Task 12C arbitrates route success/failure/cancellation only; it does not construct an operation-level successful visible set before Task 14A has content and install acknowledgements. Selected cleanup results never replace the route/external terminal.

- [ ] **Step 4: Run cross-runtime tests and commit**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.internal.resource.ResourceOperationArbitrationTest"
./gradlew --no-configuration-cache :kmp:macosArm64Test \
  --tests "com.rohittp.reng.internal.resource.ResourceOperationArbitrationTest"
git add kmp/src/commonMain/kotlin/com/rohittp/reng/internal/resource \
  kmp/src/commonTest/kotlin/com/rohittp/reng/internal/resource/ResourceOperationArbitrationTest.kt
git commit -m "feat: add ordered resource arbitration"
```

---

### Task 13: Resource Lookup, Freshness, Response Validation, and 304 Merge

**Files:**
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/resource/ResourceResponseRules.kt`
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/resource/ResourceOperationProtocol.kt`
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/resource/ResourceOperationStateMachine.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/resource/ResourceOperationLookupTest.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/resource/ResourceOperationResponseTest.kt`

**Interfaces:**
- Consumes: Task 6 pure SHA/digest values and Task 12C reducer.
- Produces: exact mode decisions, stored-record integrity checks, metadata/latch actions, generic response validation/merge and provenance.

- [ ] **Step 1: Write lookup decision table**

Test one nonnegative sample, strict freshness `freshUntil > sample`, fresh/equal/stale resident, invalid Store envelope/integrity beating stale resident, valid Store superseding resident, Store miss retaining stale resident, ETag preference, last-modified fallback, no-validator unconditional 200-only request, CACHE_ONLY suppression, RELOAD suppression, and no stale fallback. Validate stored bytes as nonempty/within-limit, exact lowercase 64-hex SHA-256 matching copied bytes, and valid metadata/epochs. Successful resident/Store/Transport selection ends in `PendingClassGates` without claiming class validity or route success; Task 14A exclusively owns parse/decode/feature gates and source-specific failure mapping. Map supplied Store-read and Transport non-cancellation failures to their exact sanitized codes/stages with discarded messages/causes; preserve the opaque selected cancellation ID. Assert at most one Store read and one consumer Transport action per joined route and no retry/repair/remove/fallback action exists. Store-write outcome mapping belongs only to Task 14A after a write action exists.

- [ ] **Step 2: Write ordered response validation and merge tests**

Metadata faults beat status/body. Empty 200 is invalid; oversized nonempty 200 is limit exceeded. A valid 200 copies its body, computes lowercase SHA-256 over those copied bytes, retains the validated response metadata, sets `storedAtEpochMillis` from the route's single freshness sample, and produces `TRANSPORT_200` provenance requiring class validation and a later write. Nonempty 304 is invalid without limit comparison. 304 requires conditional NORMAL plus valid baseline. Merge preserves bytes/digest, overrides each nonnull metadata field, retains null fields, sets the same sampled stored-at, and produces `TRANSPORT_304_MERGED` provenance requiring validation/write.

- [ ] **Step 3: Run and verify red**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.internal.resource.ResourceOperationLookupTest" \
  --tests "com.rohittp.reng.internal.resource.ResourceOperationResponseTest"
```

- [ ] **Step 4: Implement the exact pure lookup/response protocol**

```kotlin
internal enum class ContentProvenance {
    RESIDENT, STORE, TRANSPORT_200, TRANSPORT_304_MERGED,
}
internal data class ResolvedResourceContent(
    val route: ResourceRouteKey,
    val resourceKey: ResourceKey,
    val stored: StoredRawResource,
    val provenance: ContentProvenance,
)
internal data class LookupProgress(
    val sampleEpochMillis: Long?,
    val resident: StoredRawResource?,
    val staleBaseline: StoredRawResource?,
    val storeReadStarted: Boolean,
    val transportLatch: TransportLatchKey?,
    val selectedContent: ResolvedResourceContent?,
)

internal sealed interface SuppliedCallOutcome<out T> {
    data class Success<T>(val value: T) : SuppliedCallOutcome<T>
    data object Failed : SuppliedCallOutcome<Nothing>
    data class Cancelled(
        val cancellation: CancellationSelection,
    ) : SuppliedCallOutcome<Nothing>
}
internal sealed interface LatchedTransportOutcome {
    data class Response(val response: TransportResponse) : LatchedTransportOutcome
    data object Failed : LatchedTransportOutcome
    data class Cancelled(
        val cancellation: CancellationSelection,
    ) : LatchedTransportOutcome
}
internal data class TransportLatchRecord(
    val key: TransportLatchKey,
    val outcome: LatchedTransportOutcome,
)

internal data class AwaitingClockSample(
    val actionId: ResourceActionId,
    val ordinal: Long,
) : ResourceRouteCursor
internal data class AwaitingResident(
    val actionId: ResourceActionId,
    val ordinal: Long,
) : ResourceRouteCursor
internal data class AwaitingStoreRead(
    val actionId: ResourceActionId,
    val ordinal: Long,
) : ResourceRouteCursor
internal data class AwaitingTransport(
    val actionId: ResourceActionId,
    val ordinal: Long,
    val latchKey: TransportLatchKey,
) : ResourceRouteCursor
internal data class AwaitingLatchedTransportReplay(
    val actionId: ResourceActionId,
    val ordinal: Long,
    val latchKey: TransportLatchKey,
) : ResourceRouteCursor
internal data class PendingClassGates(
    val ordinal: Long,
    val content: ResolvedResourceContent,
) : ResourceRouteCursor

internal data class SampleClock(
    val actionId: ResourceActionId,
    val ordinal: Long,
) : ResourceOperationAction
internal data class ObserveResident(
    val actionId: ResourceActionId,
    val ordinal: Long,
    val resourceKey: ResourceKey,
) : ResourceOperationAction
internal data class ReadStore(
    val actionId: ResourceActionId,
    val ordinal: Long,
    val rawKey: RawResourceKey,
) : ResourceOperationAction
internal data class CallTransport(
    val actionId: ResourceActionId,
    val ordinal: Long,
    val request: TransportRequest,
    val latchKey: TransportLatchKey,
) : ResourceOperationAction
internal data class ReplayLatchedTransport(
    val actionId: ResourceActionId,
    val ordinal: Long,
    val latch: TransportLatchRecord,
) : ResourceOperationAction

internal data class ClockSampled(
    val actionId: ResourceActionId,
    val sampleEpochMillis: Long,
) : ResourceOperationEvent
internal data class ResidentObserved(
    val actionId: ResourceActionId,
    val resource: StoredRawResource?,
) : ResourceOperationEvent
internal data class StoreReadCompleted(
    val actionId: ResourceActionId,
    val outcome: SuppliedCallOutcome<StoredRawResource?>,
) : ResourceOperationEvent
internal data class TransportCompleted(
    val actionId: ResourceActionId,
    val outcome: SuppliedCallOutcome<TransportResponse>,
) : ResourceOperationEvent
internal data class LatchedTransportReplayCompleted(
    val actionId: ResourceActionId,
) : ResourceOperationEvent

internal sealed interface ResponseRuleOutcome {
    data class Selected(val content: ResolvedResourceContent) : ResponseRuleOutcome
    data class Failure(val failure: FailureDescriptor) : ResponseRuleOutcome
}
internal fun resolveTransportResponse(
    route: ResourceRouteKey,
    resourceKey: ResourceKey,
    sampleEpochMillis: Long,
    staleBaseline: StoredRawResource?,
    conditionalRequest: Boolean,
    response: TransportResponse,
    sha256: Sha256Function,
): ResponseRuleOutcome
```

Modify `RouteRecord` to add `lookup: LookupProgress?`, and `ResourceOperationState.Running` to add fresh-copied `transportLatches: List<TransportLatchRecord>` plus `nextActionId: Long`. Actions never call their named facility; events carry only copied values or closed success/failure/cancellation outcomes. A `Failed` Store read maps to `STORE_READ_FAILED / STORE_READ`; a failed Transport maps to `TRANSPORT_EXECUTION_FAILED / TRANSPORT`; messages/causes never enter the event. Final latch identity is Route Key plus exact `ifNoneMatch`, `ifModifiedSince`, and `accept`. `resolveTransportResponse` performs only envelope/integrity work in the exact metadata/status/body order, including 200 digest/stored-at formation and 304 merge. Every successful lookup stops in `PendingClassGates` with zero route-success/visibility action; Task 14A is the sole owner of class-format actions and tests.

- [ ] **Step 5: Run cross-runtime tests and commit**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.internal.resource.ResourceOperationLookupTest" \
  --tests "com.rohittp.reng.internal.resource.ResourceOperationResponseTest"
./gradlew --no-configuration-cache :kmp:macosArm64Test \
  --tests "com.rohittp.reng.internal.resource.ResourceOperationLookupTest" \
  --tests "com.rohittp.reng.internal.resource.ResourceOperationResponseTest"
git add kmp/src/commonMain/kotlin/com/rohittp/reng/internal/resource \
  kmp/src/commonTest/kotlin/com/rohittp/reng/internal/resource
git commit -m "feat: add resource lookup decisions"
```

---

### Task 14A: Ordinary Resource-Class Gates, Writes, and Visibility

**Files:**
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/resource/ResourceOperationProtocol.kt`
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/resource/ResourceOperationStateMachine.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/resource/ResourceOperationOrdinaryCommitTest.kt`

**Interfaces:**
- Consumes: Task 13 selected content/provenance.
- Produces: supplied class-gate, Store-write, and visibility acknowledgements for every non-style/non-sprite resource.

- [ ] **Step 1: Write ordinary class/provenance action tables**

Table exact gates: TileJSON parse; vector decode; raster/sticker/texture PNG decode; DEM generic image decode then terrain encoding validation; GeoJSON parse; GLB parse then supported-feature validation. Preserve Task 13's successful lookup boundary: first dispatch `AdvancePendingClassGates(ordinal)` against the matching `PendingClassGates`, assert one first `ValidateResourceClass` action plus `AwaitingClassGate`, and reject a mismatched ordinal without changing state. Resident/Store content never writes. Transport 200/merged 304 writes once only after every class gate. Visibility installs only after required write acknowledgement. A failed class gate for `ContentProvenance.STORE` always selects `STORE_INTEGRITY_FAILED / STORE_VALIDATION` with field `resource`, class, and key, regardless of parse/decode/feature gate; it emits no Transport/write/remove/fallback. The parse/decode/unsupported mapping applies only to Transport-produced content (resident generations are valid by construction and tests never supply a failed gate for `RESIDENT`). Invalid/cancelled non-Store gates select their exact terminal. Store-write failure maps to sanitized `STORE_WRITE_FAILED / STORE_WRITE`; Store-write cancellation remains cancellation. No retry/repair/remove/fallback action exists. Build final owner sets only from acknowledged visibility installs, deduplicate at first traversal occurrence, and assert `VisibleResource`, `OwnerResourceSet`, and `ResourceOperationOutcome.Success` structural equality plus snapshot/fresh-copy behavior for empty, singleton, and multi-element lists.

- [ ] **Step 2: Run and verify red**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.internal.resource.ResourceOperationOrdinaryCommitTest"
```

- [ ] **Step 3: Implement exact ordinary gate/write/install protocol**

```kotlin
internal data class VisibleResource(
    val resourceKey: ResourceKey,
    val content: ResolvedResourceContent,
)
internal class OwnerResourceSet(
    val ownerId: ResourceOwnerId,
    resources: List<VisibleResource>,
) {
    val resources: List<VisibleResource>
}
internal sealed interface ResourceOperationOutcome {
    class Success(
        resourceSets: List<OwnerResourceSet>,
    ) : ResourceOperationOutcome {
        val resourceSets: List<OwnerResourceSet>
    }
    data class Failure(val failure: FailureDescriptor) : ResourceOperationOutcome
    data class Cancelled(
        val cancellation: CancellationSelection,
    ) : ResourceOperationOutcome
}

internal enum class ResourceClassGate {
    PARSE_TILEJSON,
    DECODE_VECTOR_TILE,
    DECODE_PNG,
    VALIDATE_DEM_TERRAIN_ENCODING,
    PARSE_GEOJSON,
    PARSE_GLB,
    VALIDATE_GLB_FEATURES,
}
internal sealed interface SuppliedValidationOutcome {
    data object Valid : SuppliedValidationOutcome
    data object Failed : SuppliedValidationOutcome
    data class Cancelled(
        val cancellation: CancellationSelection,
    ) : SuppliedValidationOutcome
}
internal sealed interface SuppliedInstallOutcome {
    data object Succeeded : SuppliedInstallOutcome
    data class Failed(val failure: FailureDescriptor) : SuppliedInstallOutcome
    data class Cancelled(
        val cancellation: CancellationSelection,
    ) : SuppliedInstallOutcome
}
internal data class AwaitingClassGate(
    val actionId: ResourceActionId,
    val ordinal: Long,
    val content: ResolvedResourceContent,
    val gate: ResourceClassGate,
) : ResourceRouteCursor
internal data class AwaitingStoreWrite(
    val actionId: ResourceActionId,
    val ordinal: Long,
    val content: ResolvedResourceContent,
) : ResourceRouteCursor
internal data class AwaitingVisibilityInstall(
    val actionId: ResourceActionId,
    val ordinal: Long,
    val content: ResolvedResourceContent,
) : ResourceRouteCursor

internal data class ValidateResourceClass(
    val actionId: ResourceActionId,
    val ordinal: Long,
    val content: ResolvedResourceContent,
    val gate: ResourceClassGate,
) : ResourceOperationAction
internal data class WriteStore(
    val actionId: ResourceActionId,
    val ordinal: Long,
    val rawKey: RawResourceKey,
    val resource: StoredRawResource,
) : ResourceOperationAction
internal data class InstallVisibility(
    val actionId: ResourceActionId,
    val ordinal: Long,
    val content: ResolvedResourceContent,
) : ResourceOperationAction

internal data class AdvancePendingClassGates(
    val ordinal: Long,
) : ResourceOperationEvent
internal data class ResourceClassValidationCompleted(
    val actionId: ResourceActionId,
    val outcome: SuppliedValidationOutcome,
) : ResourceOperationEvent
internal data class StoreWriteCompleted(
    val actionId: ResourceActionId,
    val outcome: SuppliedCallOutcome<Unit>,
) : ResourceOperationEvent
internal data class VisibilityInstallCompleted(
    val actionId: ResourceActionId,
    val outcome: SuppliedInstallOutcome,
) : ResourceOperationEvent
```

Task 14A replaces Task 12A's failure/cancellation-only `ResourceOperationOutcome` declaration with the final additive `Success` variant and introduces `VisibleResource`/`OwnerResourceSet` for the first time; no earlier test constructs a content-free visibility value. The reducer accepts `AdvancePendingClassGates` only when the selected route cursor is `PendingClassGates` at the same ordinal, allocates the next action ID, and emits the first exact `ValidateResourceClass` action with `AwaitingClassGate`; this explicit closed scheduler event preserves Task 13's zero-action lookup boundary without requiring Task 14A to rewrite retained lookup tests. The action executor later supplied by Cycle C performs the named gate/write/install; this reducer invokes nothing. Every class maps to an explicit ordered gate list, with no generic “validated” shortcut for DEM or GLB. `SuppliedValidationOutcome.Failed` first branches on the cursor content's provenance: `STORE` always maps to `STORE_INTEGRITY_FAILED / STORE_VALIDATION`; otherwise decode gates map to `RESOURCE_DECODE_FAILED / RESOURCE_DECODING`, parse gates to `RESOURCE_PARSE_FAILED / RESOURCE_PARSING`, and feature/terrain gates to `UNSUPPORTED_RESOURCE_FEATURE / RESOURCE_PARSING`. Class/key are taken only from the established cursor content, and the Store-integrity diagnostic additionally has only `fieldName=resource`. Cancellation and install outcomes preserve their opaque selection/failure without arbitrary adapter text. `ResourceOperationOutcome.Success` snapshots/fresh-copies structurally equal owner sets, and each owner set deduplicates acknowledged visible resources at first traversal occurrence.

- [ ] **Step 4: Run cross-runtime tests and commit**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.internal.resource.ResourceOperationLookupTest" \
  --tests "com.rohittp.reng.internal.resource.ResourceOperationResponseTest" \
  --tests "com.rohittp.reng.internal.resource.ResourceOperationOrdinaryCommitTest"
./gradlew --no-configuration-cache :kmp:macosArm64Test \
  --tests "com.rohittp.reng.internal.resource.ResourceOperationLookupTest" \
  --tests "com.rohittp.reng.internal.resource.ResourceOperationResponseTest" \
  --tests "com.rohittp.reng.internal.resource.ResourceOperationOrdinaryCommitTest"
git add kmp/src/commonMain/kotlin/com/rohittp/reng/internal/resource \
  kmp/src/commonTest/kotlin/com/rohittp/reng/internal/resource/ResourceOperationOrdinaryCommitTest.kt
git commit -m "feat: add ordinary resource commit decisions"
```

---

### Task 14B: Atomic Sprite Pair Validation and Visibility

**Files:**
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/resource/ResourceOperationProtocol.kt`
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/resource/ResourceOperationStateMachine.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/resource/ResourceOperationSpriteCommitTest.kt`

**Interfaces:**
- Consumes: Task 14A write/install outcomes and Task 12A sprite bindings.
- Produces: joint JSON+PNG validation, sequential transported-member writes, all-or-nothing atlas visibility, and the parked-but-unretired bounded-capacity scheduler reused by style barriers.

- [ ] **Step 1: Write complete sprite traces**

Exercise all resident/Store/transport provenance combinations. Require both candidates before joint validation. Distinguish JSON parse, image decode, and unsupported-feature failures at the reported member's route ordinal; if that member's content provenance is `STORE`, every kind maps to `STORE_INTEGRITY_FAILED / STORE_VALIDATION` with field `resource`, class, and key. Otherwise map them respectively to `RESOURCE_PARSE_FAILED / RESOURCE_PARSING`, `RESOURCE_DECODE_FAILED / RESOURCE_DECODING`, and `UNSUPPORTED_RESOURCE_FEATURE / RESOURCE_PARSING`. Attribute cross-member consistency/atlas-bounds failures to JSON, the first traversal member, then apply that JSON member's provenance rule. Write only transported members, JSON before image regardless of completion/input order. Each write cursor/action/event carries group, member, ordinal, and content. If image write fails after JSON acknowledgement, preserve the content-addressed JSON orphan but install no atlas. Sprite visibility is one binding-specific action carrying both validated members and can be acknowledged only after every required member write. Never expose one member or write before joint validation. Add an exact `maximumConcurrentRoutes=1` trace: JSON reaches its validated candidate barrier, becomes parked-but-unretired and releases ordinal 0 from active capacity; image ordinal 1 then starts, reaches its candidate barrier, and releases capacity; the now-ready pair resumes ordinal 0 before any new route, performs joint validation/writes/install under one reacquired slot, and retires both route outcomes in ordinal order. Reverse completion and add unrelated higher work to prove ready parked ordinals resume lowest-first without changing failure arbitration. Buffer a failure at ordinal `N` while a lower sprite member is parked waiting on a not-yet-started dependency above the resulting start ceiling: the parked lower ordinal must close as route-arbitration `Success` without write/install/visibility so `N` can retire, while parked higher ordinals require no cancellation action because they have no in-flight work.

- [ ] **Step 2: Run and verify red**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.internal.resource.ResourceOperationSpriteCommitTest"
```

- [ ] **Step 3: Implement exact sprite group values and actions**

```kotlin
internal enum class SpriteJointValidationStatus { WAITING, REQUESTED, VALID, FAILED }
internal enum class SpritePairFailureKind { JSON_PARSE, IMAGE_DECODE, UNSUPPORTED_FEATURE }
internal sealed interface ParkedRouteBarrier {
    data class SpritePair(val groupId: SpriteGroupId) : ParkedRouteBarrier
}
internal data class ParkedRoute(
    val ordinal: Long,
    val barrier: ParkedRouteBarrier,
)
internal class SpriteCommitState(
    val groupId: SpriteGroupId,
    val jsonOrdinal: Long,
    val imageOrdinal: Long,
    val jsonCandidate: ResolvedResourceContent?,
    val imageCandidate: ResolvedResourceContent?,
    val jointValidationStatus: SpriteJointValidationStatus,
    acknowledgedWrites: List<SpriteMember>,
    val visible: Boolean,
) {
    val acknowledgedWrites: List<SpriteMember>
}
internal data class AwaitingSpritePairValidation(
    val actionId: ResourceActionId,
    val groupId: SpriteGroupId,
    val jsonOrdinal: Long,
    val imageOrdinal: Long,
    val json: ResolvedResourceContent,
    val image: ResolvedResourceContent,
) : ResourceRouteCursor
internal data class AwaitingSpriteMemberWrite(
    val actionId: ResourceActionId,
    val groupId: SpriteGroupId,
    val member: SpriteMember,
    val ordinal: Long,
    val content: ResolvedResourceContent,
) : ResourceRouteCursor
internal data class AwaitingSpriteVisibilityInstall(
    val actionId: ResourceActionId,
    val groupId: SpriteGroupId,
    val json: ResolvedResourceContent,
    val image: ResolvedResourceContent,
) : ResourceRouteCursor

internal data class ValidateSpritePair(
    val actionId: ResourceActionId,
    val groupId: SpriteGroupId,
    val json: ResolvedResourceContent,
    val image: ResolvedResourceContent,
) : ResourceOperationAction
internal data class WriteSpriteMember(
    val actionId: ResourceActionId,
    val groupId: SpriteGroupId,
    val member: SpriteMember,
    val ordinal: Long,
    val rawKey: RawResourceKey,
    val resource: StoredRawResource,
) : ResourceOperationAction
internal data class InstallSpriteVisibility(
    val actionId: ResourceActionId,
    val groupId: SpriteGroupId,
    val json: ResolvedResourceContent,
    val image: ResolvedResourceContent,
) : ResourceOperationAction

internal sealed interface SpritePairValidationOutcome {
    data object Valid : SpritePairValidationOutcome
    data class Failed(
        val member: SpriteMember,
        val kind: SpritePairFailureKind,
    ) : SpritePairValidationOutcome
    data class Cancelled(
        val cancellation: CancellationSelection,
    ) : SpritePairValidationOutcome
}
internal data class SpritePairValidationCompleted(
    val actionId: ResourceActionId,
    val outcome: SpritePairValidationOutcome,
) : ResourceOperationEvent
internal data class SpriteMemberWriteCompleted(
    val actionId: ResourceActionId,
    val groupId: SpriteGroupId,
    val member: SpriteMember,
    val outcome: SuppliedCallOutcome<Unit>,
) : ResourceOperationEvent
internal data class SpriteVisibilityInstallCompleted(
    val actionId: ResourceActionId,
    val groupId: SpriteGroupId,
    val outcome: SuppliedInstallOutcome,
) : ResourceOperationEvent
```

Add fresh-copied `spriteCommitStates: List<SpriteCommitState>` and `parkedRoutes: List<ParkedRoute>` to `ResourceOperationState.Running`. A route occupies bounded capacity only while it has emitted work awaiting an external outcome. When either sprite member becomes a validated candidate but its pair cannot yet progress, move its ordinal from `activeRouteOrdinals` to `parkedRoutes` without producing `RouteCompleted`; it stays assigned/unretired and retains its cursor/content for terminal arbitration. Every transition that parks/completes work reruns the Task 12B scheduler: if a parked barrier is now ready, reacquire one available slot for the lowest parked ordinal before starting any not-yet-started route; if none is ready, start the next eligible route, which allows a missing pair member or discovered style child to run. For a ready sprite pair, activate `jsonOrdinal` as the one group-work owner while `imageOrdinal` remains parked; joint validation, sequential writes, and install occupy that single slot until another barrier or terminal. Successful install records both route successes; a pair/write failure records success for every earlier non-failing member ordinal and the exact failure at the reported member ordinal, after which Task 12C retirement/cleanup rules apply. Thus parked ordinals release worker capacity but never retire early or escape the start ceiling/terminal cleanup rules. If a non-success is buffered at ordinal `N`, every already parked lower ordinal records route-arbitration `Success` immediately with no remaining write/install/visibility, and an active lower route does the same if its successful supplied outcome would next park; active lower actions may still return their own lower-ordinal non-success and win normally. This lets retirement reach `N` without starting ceiling-prohibited dependencies or changing the selected non-success. Once a terminal is selected, higher parked ordinals are discarded as cleanup-complete without `CancelRoute` because no external action is in flight, while active higher ordinals still require the Task 12C cancellation observation. Each acknowledgement must match the complete binding-specific cursor before state advances. A failed pair maps `member` back to the cursor's JSON/image ordinal and content, applies `STORE_INTEGRITY_FAILED / STORE_VALIDATION` first for Store provenance, and only then uses the closed kind mapping for non-Store content; joint consistency failures are emitted as `Failed(JSON, JSON_PARSE)`. `SpriteMember` ordering is explicitly `JSON` then `IMAGE`, never enum ordinal. The ordinary `WriteStore`/`InstallVisibility` actions are not reused for a sprite pair.

- [ ] **Step 4: Run cross-runtime tests and commit**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.internal.resource.ResourceOperationSchedulingTest" \
  --tests "com.rohittp.reng.internal.resource.ResourceOperationArbitrationTest" \
  --tests "com.rohittp.reng.internal.resource.ResourceOperationSpriteCommitTest"
./gradlew --no-configuration-cache :kmp:macosArm64Test \
  --tests "com.rohittp.reng.internal.resource.ResourceOperationSchedulingTest" \
  --tests "com.rohittp.reng.internal.resource.ResourceOperationArbitrationTest" \
  --tests "com.rohittp.reng.internal.resource.ResourceOperationSpriteCommitTest"
git add kmp/src/commonMain/kotlin/com/rohittp/reng/internal/resource \
  kmp/src/commonTest/kotlin/com/rohittp/reng/internal/resource/ResourceOperationSpriteCommitTest.kt
git commit -m "feat: add atomic sprite commit decisions"
```

---

### Task 14C: Basemap Style Staging, Compilation, Barrier, and Visibility

**Files:**
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/resource/ResourceOperationProtocol.kt`
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/resource/ResourceOperationStateMachine.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/resource/ResourceOperationStyleCommitTest.kt`

**Interfaces:**
- Consumes: Task 14A write/install actions, Task 14B parked scheduler, Task 12B discovered children, and style bindings.
- Produces: privately staged style validation/discovery/compilation plus whole-reference barrier, provenance-correct visibility, and concurrency-one liveness.

- [ ] **Step 1: Write style source/provenance/barrier traces**

Style validation rejects duplicate JSON members and returns deterministic discovered children before compilation. Validation distinguishes parse from unsupported-feature failure and maps exact code/stage, except that either failure for `ContentProvenance.STORE` is always `STORE_INTEGRITY_FAILED / STORE_VALIDATION` with field `resource`, class, and key; compilation likewise returns a closed sanitized kind, with a Store-sourced compilation failure using the same Store-integrity mapping rather than a resource parse/feature code. Resident style uses an already compiled generation and never writes. Store style compiles privately, never rewrites, and installs only after all referencing owners' non-style work succeeds. Transport 200/merged 304 compiles privately, waits for the same barrier, writes exactly once, then installs. Derive each owner's non-style completion from the reducer's occurrence/route/visibility state after matched events; expose no public `OwnerNonStyleWorkCompleted` event that could arrive early. Binding-specific write/install actions and cursors retain group/content/owners. Compilation, other-work, or write failure installs nothing. Completion order cannot change child order or visibility. Add an exact concurrency-one liveness trace: style ordinal 0 validates/discovers children, parks at `StyleChildren` and releases its slot; each child and referencing owner's non-style route can then start/finish; style reacquires the slot for compilation, parks again at `StyleOwners` if the owner barrier is incomplete, then reacquires lowest-first for provenance-correct write/install. Assert style remains assigned/unretired while parked, later failures buffer behind it, and no action exceeds one active slot.

- [ ] **Step 2: Run and verify red**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.internal.resource.ResourceOperationStyleCommitTest"
```

- [ ] **Step 3: Implement exact style state, actions, and events**

```kotlin
internal sealed interface ParkedRouteBarrier {
    data class SpritePair(val groupId: SpriteGroupId) : ParkedRouteBarrier
    data class StyleChildren(val groupId: StyleGroupId) : ParkedRouteBarrier
    data class StyleOwners(val groupId: StyleGroupId) : ParkedRouteBarrier
}
internal enum class StyleCompilationStatus { NOT_REQUIRED, WAITING, REQUESTED, SUCCEEDED, FAILED }
internal enum class StyleFailureKind { PARSE, UNSUPPORTED_FEATURE }
internal class StyleCommitState(
    val groupId: StyleGroupId,
    val ordinal: Long,
    val stagedContent: ResolvedResourceContent,
    val compilationStatus: StyleCompilationStatus,
    referencingOwnerIds: List<ResourceOwnerId>,
    ownersWithCompletedNonStyleWork: List<ResourceOwnerId>,
    val writeAcknowledged: Boolean,
    val visible: Boolean,
) {
    val referencingOwnerIds: List<ResourceOwnerId>
    val ownersWithCompletedNonStyleWork: List<ResourceOwnerId>
}
internal data class AwaitingStyleValidation(
    val actionId: ResourceActionId,
    val ordinal: Long,
    val groupId: StyleGroupId,
    val content: ResolvedResourceContent,
) : ResourceRouteCursor
internal data class AwaitingStyleCompilation(
    val actionId: ResourceActionId,
    val ordinal: Long,
    val groupId: StyleGroupId,
    val content: ResolvedResourceContent,
) : ResourceRouteCursor
internal data class AwaitingStyleWrite(
    val actionId: ResourceActionId,
    val ordinal: Long,
    val groupId: StyleGroupId,
    val content: ResolvedResourceContent,
) : ResourceRouteCursor
internal class AwaitingStyleVisibilityInstall(
    val actionId: ResourceActionId,
    val ordinal: Long,
    val groupId: StyleGroupId,
    val content: ResolvedResourceContent,
    referencingOwnerIds: List<ResourceOwnerId>,
) : ResourceRouteCursor {
    val referencingOwnerIds: List<ResourceOwnerId>
}

internal data class ValidateBasemapStyle(
    val actionId: ResourceActionId,
    val ordinal: Long,
    val groupId: StyleGroupId,
    val content: ResolvedResourceContent,
) : ResourceOperationAction
internal data class CompileBasemapStyle(
    val actionId: ResourceActionId,
    val ordinal: Long,
    val groupId: StyleGroupId,
    val content: ResolvedResourceContent,
) : ResourceOperationAction
internal data class WriteBasemapStyle(
    val actionId: ResourceActionId,
    val ordinal: Long,
    val groupId: StyleGroupId,
    val rawKey: RawResourceKey,
    val resource: StoredRawResource,
) : ResourceOperationAction
internal class InstallBasemapStyleVisibility(
    val actionId: ResourceActionId,
    val ordinal: Long,
    val groupId: StyleGroupId,
    val content: ResolvedResourceContent,
    referencingOwnerIds: List<ResourceOwnerId>,
) : ResourceOperationAction {
    val referencingOwnerIds: List<ResourceOwnerId>
}

internal sealed interface BasemapStyleValidationOutcome {
    class Valid(
        children: List<DiscoveredResourceChild>,
    ) : BasemapStyleValidationOutcome {
        val children: List<DiscoveredResourceChild>
    }
    data class Failed(
        val kind: StyleFailureKind,
    ) : BasemapStyleValidationOutcome
    data class Cancelled(
        val cancellation: CancellationSelection,
    ) : BasemapStyleValidationOutcome
}
internal sealed interface BasemapStyleCompilationOutcome {
    data object Succeeded : BasemapStyleCompilationOutcome
    data class Failed(
        val kind: StyleFailureKind,
    ) : BasemapStyleCompilationOutcome
    data class Cancelled(
        val cancellation: CancellationSelection,
    ) : BasemapStyleCompilationOutcome
}
internal data class BasemapStyleValidationCompleted(
    val actionId: ResourceActionId,
    val outcome: BasemapStyleValidationOutcome,
) : ResourceOperationEvent
internal data class BasemapStyleCompilationCompleted(
    val actionId: ResourceActionId,
    val outcome: BasemapStyleCompilationOutcome,
) : ResourceOperationEvent
internal data class BasemapStyleWriteCompleted(
    val actionId: ResourceActionId,
    val groupId: StyleGroupId,
    val outcome: SuppliedCallOutcome<Unit>,
) : ResourceOperationEvent
internal data class BasemapStyleVisibilityInstallCompleted(
    val actionId: ResourceActionId,
    val groupId: StyleGroupId,
    val outcome: SuppliedInstallOutcome,
) : ResourceOperationEvent
```

Extend Task 14B's parked scheduler rather than holding a route slot across a dependency barrier. After successful style validation registers its canonical children, park the style ordinal under `StyleChildren`; only after that frontier and all required child routes complete may the lowest-ready style reacquire a slot and emit `CompileBasemapStyle`. After compilation, park under `StyleOwners` whenever any referencing owner still has unfinished non-style occurrences; write/install can resume only after the derived barrier becomes true. Parking removes the ordinal from `activeRouteOrdinals` but not from assigned routes or retirement, and every resumed compilation/write/install action consumes one slot until its supplied outcome or next park. At concurrency one, this guarantees children and owner work can run while preserving Task 12C ordinal terminal selection.

Add fresh-copied `styleCommitStates: List<StyleCommitState>` and `visibleResourcesByOwner: List<OwnerResourceSet>` to `Running`. `BasemapStyleValidationOutcome.Valid` snapshots/fresh-copies children and all style textual forms are shape-only. `BasemapStyleValidationOutcome.Failed` and `BasemapStyleCompilationOutcome.Failed` resolve the cursor's staged content before mapping: Store provenance is terminal `STORE_INTEGRITY_FAILED / STORE_VALIDATION`, while non-Store parse/feature kinds use their resource failure mapping. Validation children enter the Task 12B frontier through the same canonical sorter before any later route. After every matched route/write/install event, recompute `ownersWithCompletedNonStyleWork` solely from the complete occurrence registry and current route/visibility records; an owner is complete only when every non-style occurrence is successfully visible. Store/resident styles skip `WriteBasemapStyle`; transported styles emit it only after compilation plus every referencing owner completion. `InstallBasemapStyleVisibility` is the final acknowledged action and carries the complete owner set.

- [ ] **Step 4: Run cross-runtime tests and commit**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.internal.resource.ResourceOperationSchedulingTest" \
  --tests "com.rohittp.reng.internal.resource.ResourceOperationArbitrationTest" \
  --tests "com.rohittp.reng.internal.resource.ResourceOperationSpriteCommitTest" \
  --tests "com.rohittp.reng.internal.resource.ResourceOperationStyleCommitTest"
./gradlew --no-configuration-cache :kmp:macosArm64Test \
  --tests "com.rohittp.reng.internal.resource.ResourceOperationSchedulingTest" \
  --tests "com.rohittp.reng.internal.resource.ResourceOperationArbitrationTest" \
  --tests "com.rohittp.reng.internal.resource.ResourceOperationSpriteCommitTest" \
  --tests "com.rohittp.reng.internal.resource.ResourceOperationStyleCommitTest"
git add kmp/src/commonMain/kotlin/com/rohittp/reng/internal/resource \
  kmp/src/commonTest/kotlin/com/rohittp/reng/internal/resource/ResourceOperationStyleCommitTest.kt
git commit -m "feat: add basemap style commit decisions"
```

---

### Task 15: Ordered Preparation State Machine

**Files:**
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/preparation/OrderedPreparationProtocol.kt`
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/preparation/OrderedPreparationStateMachine.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/preparation/OrderedPreparationStateMachineTest.kt`

**Interfaces:**
- Consumes: Tasks 4B, 10, and 14C terminal values; calls none of them.
- Produces: pure ordered-batch/history/lease reducer and same-order fresh result snapshots.

- [ ] **Step 1: Write planning-barrier tests**

Cover empty/oversized/non-increasing/not-above-history begin, defensive input snapshots, second begin, and the singleton `prepare` form as exactly one nonempty atomic batch item. Assert strictly sequential `RunPurePlanning` actions with committed then immediate-predecessor plan/LOD baselines and zero resource action before every plan succeeds.

- [ ] **Step 2: Write commit/rollback/cancellation tests**

Reverse resource completion order but return frame seeds in input order. Install leases in item/traversal order. Model installation as request followed by explicit acknowledgement: if lease `2` fails before acknowledgement, only `0` and `1` were installed, so assert releases in reverse acknowledgement order `1,0` and never release `2`. Preserve original history on failure/cancellation. Commit only final item on total success. Prove returned list is fresh/unretained. Test clear-history active/idle and resource-cancellation barrier.

- [ ] **Step 3: Run and verify red**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.internal.preparation.OrderedPreparationStateMachineTest"
```

- [ ] **Step 4: Implement the exact immutable preparation protocol and reducer**

```kotlin
@JvmInline internal value class PreparationInvocationId(val value: Long)
@JvmInline internal value class PreparationItemId(val value: Long)
@JvmInline internal value class LeaseId(val value: Long)

internal data class CommittedFrameHistory(
    val frameIndex: Long,
    val encodedPlan: EncodedFramePlan,
    val selectedLod: Int,
)
internal data class PreparationEnvironment(
    val outputPixelSize: OutputPixelSize,
    val basemapStyle: ResourceLocator?,
    val resourceLimits: ResourceLimits,
    val maximumBasemapTileInstances: Int,
    val maximumPreparationBatchSize: Int,
    val maximumConcurrentResourceOperations: Int,
)
internal class PreparationInvocation(
    val id: PreparationInvocationId,
    val accessMode: ResourceAccessMode,
    plans: List<FramePlan>,
    val environment: PreparationEnvironment,
    val initialHistory: CommittedFrameHistory?,
) {
    val plans: List<FramePlan>
}
internal data class PlannedPreparationItem(
    val itemId: PreparationItemId,
    val plannedFrame: PlannedFrameCore,
)
internal sealed interface LeaseResource {
    data class External(val visible: VisibleResource) : LeaseResource
    data class PlannedLogical(val key: ResourceKey) : LeaseResource
}
internal data class LeaseInstallRequest(
    val itemId: PreparationItemId,
    val traversalIndex: Int,
    val resource: LeaseResource,
)
internal data class AcknowledgedLease(
    val leaseId: LeaseId,
    val request: LeaseInstallRequest,
)
internal class PreparedFrameSeed(
    val itemId: PreparationItemId,
    val frameIndex: Long,
    val plannedFrame: PlannedFrameCore,
    leases: List<AcknowledgedLease>,
) {
    val leases: List<AcknowledgedLease>
}

internal sealed interface OrderedPreparationTerminal {
    data class Failure(val failure: FailureDescriptor) : OrderedPreparationTerminal
    data class Cancelled(
        val cancellation: CancellationSelection,
    ) : OrderedPreparationTerminal
}
internal sealed interface OrderedPreparationState {
    data class Idle(
        val history: CommittedFrameHistory?,
    ) : OrderedPreparationState
    class Planning(
        val invocation: PreparationInvocation,
        val nextItemIndex: Int,
        val provisionalHistory: CommittedFrameHistory?,
        plannedItems: List<PlannedPreparationItem>,
    ) : OrderedPreparationState {
        val plannedItems: List<PlannedPreparationItem>
    }
    class ResolvingResources(
        val invocation: PreparationInvocation,
        plannedItems: List<PlannedPreparationItem>,
        val pendingCancellation: CancellationSelection? = null,
    ) : OrderedPreparationState {
        val plannedItems: List<PlannedPreparationItem>
    }
    class InstallingLeases(
        val invocation: PreparationInvocation,
        plannedItems: List<PlannedPreparationItem>,
        pendingInstalls: List<LeaseInstallRequest>,
        acknowledgedLeases: List<AcknowledgedLease>,
    ) : OrderedPreparationState {
        val plannedItems: List<PlannedPreparationItem>
        val pendingInstalls: List<LeaseInstallRequest>
        val acknowledgedLeases: List<AcknowledgedLease>
    }
    class RollingBack(
        val invocation: PreparationInvocation,
        val originalOutcome: OrderedPreparationTerminal,
        pendingReleases: List<AcknowledgedLease>,
        releasedLeases: List<LeaseId>,
        failedReleaseLeases: List<LeaseId>,
    ) : OrderedPreparationState {
        val pendingReleases: List<AcknowledgedLease>
        val releasedLeases: List<LeaseId>
        val failedReleaseLeases: List<LeaseId>
    }
}

internal sealed interface OrderedPreparationAction {
    data class RunPurePlanning(
        val itemId: PreparationItemId,
        val request: FramePlanningRequest,
    ) : OrderedPreparationAction
    data class RunResourceOperation(
        val definition: ResourceOperationDefinition,
    ) : OrderedPreparationAction
    data class RequestResourceCancellation(
        val invocationId: PreparationInvocationId,
        val cancellation: CancellationSelection,
    ) : OrderedPreparationAction
    data class InstallLease(
        val request: LeaseInstallRequest,
    ) : OrderedPreparationAction
    data class ReleaseLease(
        val lease: AcknowledgedLease,
    ) : OrderedPreparationAction
}
internal sealed interface OrderedPreparationEvent {
    data class BeginSingleton(
        val invocationId: PreparationInvocationId,
        val plan: FramePlan,
        val accessMode: ResourceAccessMode,
        val environment: PreparationEnvironment,
    ) : OrderedPreparationEvent
    class BeginBatch(
        val invocationId: PreparationInvocationId,
        plans: List<FramePlan>,
        val accessMode: ResourceAccessMode,
        val environment: PreparationEnvironment,
    ) : OrderedPreparationEvent {
        val plans: List<FramePlan>
    }
    data class PlanningCompleted(
        val itemId: PreparationItemId,
        val outcome: FramePlanningOutcome,
    ) : OrderedPreparationEvent
    data class ResourcesCompleted(
        val outcome: ResourceOperationOutcome,
    ) : OrderedPreparationEvent
    data class CancellationRequested(
        val cancellation: CancellationSelection,
    ) : OrderedPreparationEvent
    data class LeaseInstallAcknowledged(
        val request: LeaseInstallRequest,
        val leaseId: LeaseId,
    ) : OrderedPreparationEvent
    data class LeaseInstallFailed(
        val request: LeaseInstallRequest,
        val failure: FailureDescriptor,
    ) : OrderedPreparationEvent
    data class LeaseInstallCancelled(
        val request: LeaseInstallRequest,
        val cancellation: CancellationSelection,
    ) : OrderedPreparationEvent
    data class LeaseReleaseAcknowledged(
        val leaseId: LeaseId,
    ) : OrderedPreparationEvent
    data class LeaseReleaseFailed(
        val leaseId: LeaseId,
    ) : OrderedPreparationEvent
    data object ClearHistoryRequested : OrderedPreparationEvent
}

internal sealed interface OrderedPreparationCursor {
    data class AwaitingPlanning(val itemId: PreparationItemId) : OrderedPreparationCursor
    data object AwaitingResources : OrderedPreparationCursor
    data class AwaitingResourceCancellation(
        val cancellation: CancellationSelection,
    ) : OrderedPreparationCursor
    data class AwaitingLeaseInstall(
        val request: LeaseInstallRequest,
    ) : OrderedPreparationCursor
    data class AwaitingLeaseRelease(
        val lease: AcknowledgedLease,
    ) : OrderedPreparationCursor
}
internal sealed interface OrderedPreparationOutcome {
    class Success(
        frameSeeds: List<PreparedFrameSeed>,
    ) : OrderedPreparationOutcome {
        val frameSeeds: List<PreparedFrameSeed>
    }
    data class Failure(val failure: FailureDescriptor) : OrderedPreparationOutcome
    data class Cancelled(
        val cancellation: CancellationSelection,
    ) : OrderedPreparationOutcome
    data object HistoryCleared : OrderedPreparationOutcome
}
internal class OrderedPreparationTransition(
    val state: OrderedPreparationState,
    actions: List<OrderedPreparationAction>,
    val cursor: OrderedPreparationCursor?,
    val outcome: OrderedPreparationOutcome?,
) {
    val actions: List<OrderedPreparationAction>
}

internal object OrderedPreparationStateMachine {
    internal fun transition(
        state: OrderedPreparationState,
        event: OrderedPreparationEvent,
    ): OrderedPreparationTransition
}
```

Every list above is privately snapshotted/fresh-copied and every copy-backed class implements structural equality/hash plus shape-only text; each transition emits actions in exact order and has at most one waiting cursor. Every cancellation event/outcome retains the selected `CancellationSelection` unchanged through rollback and completion, allowing the integration layer to rethrow exactly the deterministically selected exception by opaque ID without exposing a throwable to the reducer. `BeginSingleton` normalizes to a one-element atomic invocation and exercises the same validation as `BeginBatch`. `RunResourceOperation.definition.staticOccurrences` contains external occurrences only and copies each planned external reference's distinct consumer `rawKey` plus opaque `privateRentileKey` into `ResourceRouteRegistration`; `definition.resourceIdentities` contains every external and geometry-program canonical identity from all planned items so invocation-scoped collisions are checked before lookup. Geometry-program keys remain `PlannedLogical` lease resources and perform no Store/Transport work. The reducer emits resource work only after all sequential planning succeeds, installs in item/traversal order, appends a lease only after acknowledgement, and rolls back that acknowledged list in reverse. Release failure records the lease but never replaces the original terminal. History changes only on total install success to the final item's plan/LOD. No public `PreparedFrame` implementation or renderer factory is exposed.

- [ ] **Step 5: Run cross-runtime tests and commit**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.internal.preparation.OrderedPreparationStateMachineTest"
./gradlew --no-configuration-cache :kmp:macosArm64Test \
  --tests "com.rohittp.reng.internal.preparation.OrderedPreparationStateMachineTest"
git add kmp/src/commonMain/kotlin/com/rohittp/reng/internal/preparation \
  kmp/src/commonTest/kotlin/com/rohittp/reng/internal/preparation
git commit -m "feat: add ordered preparation decisions"
```

---

### Task 16: Cross-Engine Pure-Core Contract Tests

**Files:**
- Create: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/CycleBPureCoreContractTest.kt`

**Interfaces:**
- Consumes: all internal pure components.
- Produces: end-to-end trace proof only; no production orchestrator/factory.

- [ ] **Step 1: Write complete success trace**

Create two strictly increasing plans; drive ordered preparation actions into `FramePlanningCore` with a fake `RentilePrivateKeyResolver`; feed planned static occurrences—including separate consumer raw and opaque private keys—into `ResourceOperationStateMachine`; supply fake resident/Store/Transport/validation/write outcomes; return complete resource results; install fake leases; assert same-order frame seeds, final history, canonical identity, LOD, and no leaked mutable list.

- [ ] **Step 2: Write barrier, failure, collision, and cancellation traces**

Assert invalid second plan causes zero resource actions; two distinct routes with different `RawResourceKey`s but one supplied `RentilePrivateKey` fail ambiguous before work, while equal raw keys alone do not; dynamic child matching later preregistered route gets earlier ordinal; earliest ordinal failure beats reverse completion; a Store-sourced class/pair/style gate failure is always `STORE_INTEGRITY_FAILED / STORE_VALIDATION` with no Transport/write/remove; two adapter cancellations completed in reverse still propagate the lower retired route's opaque `CancellationId` through resource and preparation outcomes; external cancellation claims terminal before an unretired route failure and retains its own ID; at concurrency one, sprite and style dependency barriers park without retiring, release capacity for pair/child/owner routes, and resume lowest-ready first; transported style cannot write/install before compilation and every referencing owner's derived non-style completion; a buffered failure closes lower parked commit-only work as route success without visibility so ordinal retirement cannot deadlock; a failed third lease install releases only acknowledged leases `1,0`; lifecycle deletion acknowledgement removes the successful prefix before a later deletion failure; lifecycle close/adoption decisions remain independent of preparation history.

- [ ] **Step 3: Run on Android host and macOS**

```bash
./gradlew --no-configuration-cache :kmp:testAndroidHostTest \
  --tests "com.rohittp.reng.internal.CycleBPureCoreContractTest"
./gradlew --no-configuration-cache :kmp:macosArm64Test \
  --tests "com.rohittp.reng.internal.CycleBPureCoreContractTest"
```

Expected: all traces pass without any adapter, Rentile call, decoder/parser, cache, context, or GL implementation.

- [ ] **Step 4: Commit**

```bash
git add kmp/src/commonTest/kotlin/com/rohittp/reng/internal/CycleBPureCoreContractTest.kt
git commit -m "test: prove the Cycle B pure core"
```

---

### Task 17: Documentation Status and Full Release-Equivalent Local Gates

**Files:**
- Modify: `CLAUDE.md`
- Modify: `HANDOFF.md`
- Modify: `docs/decomposition.md`
- Verify: all production/tests, policy, ABI, publication, smoke.

**Interfaces:**
- Consumes: Tasks 1–16.
- Produces: a locally verified Cycle B implementation branch ready for integration review; no push/publication.

- [ ] **Step 1: Update repository status factually**

Record that the Cycle B specification is approved and implemented on this branch, list the plan path and pure-engine boundaries, and state that exact merged-commit CI/publication has not been observed. Do not claim Linux executable coverage from macOS and do not claim public release.

- [ ] **Step 2: Run Python and repository policy gates**

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover \
  -s tools/tests -p 'test_*.py' -v
PYTHONDONTWRITEBYTECODE=1 python3 tools/check_repository_policy.py --root .
```

Expected: all Python tests pass and output ends with `Cycle B repository policy passed`.

- [ ] **Step 3: Run complete macOS-local Gradle gates**

```bash
./gradlew --no-configuration-cache --rerun-tasks \
  :kmp:checkKotlinAbi \
  :kmp:testAndroidHostTest \
  :kmp:compileKotlinLinuxX64 \
  :kmp:compileKotlinLinuxArm64 \
  :kmp:bundleAndroidMainAar \
  :kmp:compileKotlinIosArm64 \
  :kmp:compileKotlinIosSimulatorArm64 \
  :kmp:macosArm64Test \
  :kmp:publishAllPublicationsToLocalTestRepository
```

Do not claim `linuxX64Test` ran locally on macOS.

- [ ] **Step 4: Run fresh six-target consumer smoke**

```bash
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

- [ ] **Step 5: Parse workflows and inspect repository scope**

```bash
ruby -e 'require "yaml"; YAML.safe_load(File.read(".github/workflows/ci.yml"), [], [], true); YAML.safe_load(File.read(".github/workflows/publish.yml"), [], [], true)'
git diff --check
! grep -nE 'com\.rohittp\.rentile|platform\.|createRenderer|RendererFactory' kmp/api/kmp.klib.api
git status --short
```

- [ ] **Step 6: Commit status documentation**

```bash
git add CLAUDE.md HANDOFF.md docs/decomposition.md
git commit -m "docs: record Cycle B implementation state"
```

- [ ] **Step 7: Preserve Ubuntu-only gate for CI**

The exact Ubuntu command is:

```bash
./gradlew --no-configuration-cache \
  :kmp:checkKotlinAbi \
  :kmp:testAndroidHostTest \
  :kmp:linuxX64Test \
  :kmp:compileKotlinLinuxArm64 \
  :kmp:bundleAndroidMainAar
```

- [ ] **Step 8: Verify the committed range and final cleanliness**

```bash
git diff --check 11d7a03..HEAD
test -z "$(git status --porcelain)"
```

Expected: both commands exit zero after the documentation commit. If implementation commits deliberately changed the approved spec or historical Cycle A records, stop and remove that scope drift before review.

Execution stops before any push, merge, workflow dispatch, R2 upload, or publication side effect. Those remain explicit outward actions.

---

## Plan Self-Review Checklist

- Every public declaration in the approved specification maps to Tasks 1–5, with Tasks 4A–4C separating reports, failures, and ownership protocols.
- Canonical roots/tags/widths/UTF-8/identity/collision/diff map to Tasks 6–7.
- Mercator/WGS84/camera/reverse-Z/footprint/LOD/tile/placement/shader-profile rules map to Tasks 8A–10.
- Lifecycle total matrix and precedence map to Task 11.
- Route preregistration/frontiers/ordinals/arbitration/lookup/304/write visibility map to Tasks 12A–14C.
- Full planning barrier/history/rollback/cancellation maps to Task 15.
- Cross-component supplied-outcome traces map to Task 16.
- ABI, policy, all target gates, local publication, and fresh smoke map to Tasks 5 and 17.
- No task adds Cycle C/D acquisition, decoders/parsers, cache, factory, GL, context, shader compilation, or pixels.

## Recorded Execution Decision

The repository owner approved the specification and instructed execution once this plan is prepared. After built-in and Codex plan review pass, execute with parallel subagents according to the waves above, without another implementation checkpoint. Before implementation, remove completed auxiliary planning/review worktrees; retain this host-pinned worktree and create new worktrees only for the named parallel waves.
