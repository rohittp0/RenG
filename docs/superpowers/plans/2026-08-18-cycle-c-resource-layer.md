# Cycle C Resource Layer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Execute Cycle B's pure resource decisions against real adapters — acquiring through the consumer's `Transport` and `Store`, driving the basemap engine behind the ADR 0016 firewall, decoding PNG, parsing GLB, and holding results in a refcounted resident cache.

**Architecture:** A policy-free driver turns each `ResourceOperationAction` into exactly one real operation and feeds back exactly one event; every decision was already made by `ResourceOperationStateMachine` and is replayed, never reinterpreted. Decode and parse are pure common Kotlin over one two-actual inflate seam. The firewall multiplexes one long-lived basemap rasterizer through the active preparation invocation, absorbing an engine `remove` and answering repeated engine reads from the joined route sample.

**Tech Stack:** Kotlin Multiplatform 2.3.21, Gradle 9.5.0, AGP 9.3.1, `kotlin.test`, Kotlin ABI validation, `kotlinx-coroutines-core`, `com.rohittp.rentile:kmp:0.1.5`, `platform.zlib` and `java.util.zip`.

**Spec:** `docs/superpowers/specs/2026-08-18-cycle-c-resource-layer-design.md`

## Global Constraints

- The approved specification above is authoritative; newer ADRs win over older ADR wording. ADRs 0016, 0018, 0019, 0020, and 0021 govern this cycle directly.
- Keep exactly one published module, `:kmp`, and package public declarations under `com.rohittp.reng`.
- Keep exactly six targets: `android`, `iosArm64`, `iosSimulatorArm64`, `macosArm64`, `linuxX64`, and `linuxArm64`. Add no JVM, `macosX64`, or `iosX64` target and no new Gradle subproject.
- Keep `com.rohittp.rentile:kmp:0.1.5` as an `implementation` dependency; expose no Rentile or platform type in public ABI.
- `org.jetbrains.kotlinx:kotlinx-coroutines-core` is the **only** new production dependency permitted, per ADR 0019, and its version lives in RenG's own version catalogue. `org.jetbrains.kotlinx:kotlinx-coroutines-test`, sharing that same catalogue version, is the one test-scope artifact permitted alongside it — restricted to `:kmp`'s `commonTest`, because `runTest`, `TestScope`, and `advanceTimeBy` live only there, never in `kotlinx-coroutines-core`. Add no serialization library, crypto library, Ktor, Skiko, Wire, or protobuf runtime.
- Keep `explicitApi()` and Kotlin ABI validation enabled. The public ABI grows by **exactly five declarations** across this whole cycle: `ResourceLimits.maximumDecodedImageBytes`, `ResourceLimits.maximumModelJsonChunkBytes`, `RenGErrorCode.BASEMAP_RENDER_FAILED`, `PipelineStage.BASEMAP_RENDER`, and `ResourceKind.BASEMAP_TILE`. Nothing else public changes.
- Expose no renderer factory, public implementation, top-level `createRenderer`, `RenG` construction object, GL call, shader compilation, context, framebuffer, or pixel behavior.
- Snapshot every `List`/`ByteArray` constructor input. Every public getter returns a fresh copy that cannot mutate backing state.
- Never expose locator, adapter message/cause, validator, bytes, or engine text in diagnostics, exceptions, or textual representations. An engine `RawResourceKey` must never reach a RenG diagnostic — its `toString()` prints its `stableId`.
- `RenGException.message` stays exactly `RenG failure: <CODE> at <STAGE>` and its cause stays null. Never wrap an engine exception; classify it.
- Propagate `CancellationException` unwrapped. Never convert cancellation into a `RenGException`. Closing the rasterizer surfaces to in-flight work as a plain `CancellationException`, and only post-close calls throw a typed closed failure — do not classify the first as a RenG failure.
- RenG performs no retry, repair, redirect, status fallback, or byte range. The state machine emits no action for any of them, so the driver must have no code path for them.
- The renderer mutex is never held across an adapter call, a decode, or a parse.
- Keep `VERSION_NAME` in root `gradle.properties` as the sole checked-in version input; never add `mavenLocal()` or a snapshot dependency.
- Every Gradle command uses `--no-configuration-cache`; fresh consumer smoke also uses a new Gradle home and `--refresh-dependencies`.
- Do not edit historical Cycle A or Cycle B design or plan documents.
- Gradle reports `UP-TO-DATE` for unchanged test tasks. Pass `--rerun-tasks` whenever a run is meant to prove something.

---

### Task 1: Take the coroutines dependency and amend repository policy

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `kmp/build.gradle.kts`
- Modify: `tools/check_repository_policy.py`
- Test: `tools/tests/test_check_repository_policy.py`

**Interfaces:**
- Consumes: nothing.
- Produces: `kotlinx.coroutines` available to `commonMain` on all six targets, and `kotlinx.coroutines.test` available to `commonTest`; the policy checker permits exactly `libs.kotlinx.coroutines.core` in `:kmp`'s `commonMain` and exactly `libs.kotlinx.coroutines.test` in `:kmp`'s `commonTest`, and continues to reject every other forbidden coordinate.

- [ ] **Step 1: Write the failing policy test**

Add to `tools/tests/test_check_repository_policy.py`:

```python
def test_allows_only_the_named_coroutines_coordinates(self):
    root = self._repository()
    self._write(root / "kmp/build.gradle.kts", _KMP_BUILD_WITH_COROUTINES)
    self.assertEqual(check_dependencies(root), [])

def test_rejects_a_second_new_main_dependency(self):
    root = self._repository()
    self._write(
        root / "kmp/build.gradle.kts",
        _KMP_BUILD_WITH_COROUTINES.replace(
            "implementation(libs.kotlinx.coroutines.core)",
            "implementation(libs.kotlinx.coroutines.core)\n"
            "            implementation(libs.kotlinx.serialization.json)",
        ),
    )
    violations = check_dependencies(root)
    self.assertEqual([violation.code for violation in violations], ["FORBIDDEN_CYCLE_B_DEPENDENCY"])

def test_rejects_a_second_new_test_dependency(self):
    root = self._repository()
    self._write(
        root / "kmp/build.gradle.kts",
        _KMP_BUILD_WITH_COROUTINES.replace(
            "implementation(libs.kotlinx.coroutines.test)",
            "implementation(libs.kotlinx.coroutines.test)\n"
            "            implementation(libs.kotlinx.serialization.json)",
        ),
    )
    violations = check_dependencies(root)
    self.assertEqual([violation.code for violation in violations], ["FORBIDDEN_CYCLE_B_DEPENDENCY"])

def test_still_rejects_a_bare_coroutines_coordinate(self):
    root = self._repository()
    self._write(
        root / "kmp/build.gradle.kts",
        _KMP_BUILD_WITH_COROUTINES.replace(
            "implementation(libs.kotlinx.coroutines.core)",
            'implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")',
        ),
    )
    self.assertNotEqual(check_dependencies(root), [])

def test_still_rejects_a_bare_coroutines_test_coordinate(self):
    root = self._repository()
    self._write(
        root / "kmp/build.gradle.kts",
        _KMP_BUILD_WITH_COROUTINES.replace(
            "implementation(libs.kotlinx.coroutines.test)",
            'implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")',
        ),
    )
    self.assertNotEqual(check_dependencies(root), [])
```

Define the fixture next to the existing build fixtures in that file, with the one test-scope coordinate this task also takes:

```python
_KMP_BUILD_WITH_COROUTINES = """
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.rentile.kmp)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
"""
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `PYTHONDONTWRITEBYTECODE=1 python3 -m unittest tools.tests.test_check_repository_policy -v`
Expected: FAIL — `check_dependencies` currently pins `:kmp` to exactly two dependency calls and its `_FORBIDDEN_DEPENDENCY` pattern matches the literal `coroutines`.

- [ ] **Step 3: Amend the policy checker**

In `tools/check_repository_policy.py`, add the two permitted coordinates — one production, one test-scope — and exempt both from the forbidden pattern. Keep the pattern itself intact so every other library, and every other coroutines artifact, stays rejected:

```python
# ADR 0019 takes exactly one coroutines coordinate as a first-party production dependency,
# plus the one test-scope artifact `runTest`/`TestScope`/`advanceTimeBy` live in. The forbidden
# pattern below still rejects every other library, including any other coroutines artifact, so
# neither exemption can widen by accident.
_PERMITTED_NEW_DEPENDENCIES = frozenset({"libs.kotlinx.coroutines.core"})
_PERMITTED_NEW_TEST_DEPENDENCIES = frozenset({"libs.kotlinx.coroutines.test"})
```

Update `check_dependencies` so `:kmp`'s `commonMain` admits `libs.rentile.kmp` plus any member of `_PERMITTED_NEW_DEPENDENCIES`, and `:kmp`'s `commonTest` admits `kotlin("test")` plus any member of `_PERMITTED_NEW_TEST_DEPENDENCIES`. `main_calls`/`test_calls` each become a fixed two-call set instead of one call, so the total the `allowed` check counts rises from `len(dependency_calls) == 2` to `len(dependency_calls) == 4` — two `implementation` calls in `commonMain`, two in `commonTest` — and `allowed_call_indices` gains the coroutines-test call alongside `kotlin("test")`.

Rewrite the forbidden-token scan itself rather than trying to skip one token: `_kotlin_tokens` splits a qualified reference into one token per identifier and per `.` (`libs`, `.`, `kotlinx`, `.`, `coroutines`, `.`, `core` — seven tokens, not one), so no single token can ever equal a full coordinate string, and "skip a token that is exactly a permitted coordinate" cannot be implemented as such. Instead, tokenize each permitted coordinate once (with `_kotlin_tokens`, exactly as `expected_main`/`expected_test` already are) and change `_contains_forbidden` to walk `arguments` by index: at each position, check whether the run starting there matches a permitted coordinate's tokens using `_token_sequence_at` — the same run-matching helper the file already uses for `_EXPECTED_PLUGIN_BLOCKS` — and if it does, skip past the whole run without applying `_FORBIDDEN_DEPENDENCY` to any token inside it; otherwise test that one token exactly as today.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tools/tests -p 'test_*.py'`
Expected: PASS, with the suite count risen by five.

- [ ] **Step 5: Declare the dependencies**

In `gradle/libs.versions.toml`, under `[versions]` add `kotlinxCoroutines = "1.11.0"`, and under `[libraries]`:

```toml
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "kotlinxCoroutines" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "kotlinxCoroutines" }
```

In `kmp/build.gradle.kts`:

```kotlin
commonMain.dependencies {
    implementation(libs.rentile.kmp)
    implementation(libs.kotlinx.coroutines.core)
}
commonTest.dependencies {
    implementation(kotlin("test"))
    implementation(libs.kotlinx.coroutines.test)
}
```

- [ ] **Step 6: Prove it resolves on every target and changes no ABI**

Run:
```bash
PYTHONDONTWRITEBYTECODE=1 python3 tools/check_repository_policy.py --root .
./gradlew --no-configuration-cache :kmp:checkKotlinAbi \
  :kmp:compileKotlinIosArm64 :kmp:compileKotlinIosSimulatorArm64 \
  :kmp:compileKotlinMacosArm64 :kmp:compileKotlinLinuxX64 \
  :kmp:compileKotlinLinuxArm64 :kmp:compileAndroidMain
```
Expected: policy passes; all six compile; `checkKotlinAbi` reports no change, because a dependency is not public surface.

- [ ] **Step 7: Commit**

```bash
git add gradle/libs.versions.toml kmp/build.gradle.kts tools/check_repository_policy.py tools/tests/test_check_repository_policy.py
git commit -m "build: take kotlinx-coroutines as the one permitted new dependency"
```

---

### Task 2: Grow the public surface by exactly five declarations

**Files:**
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/Resources.kt`
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/Exceptions.kt`
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/Diagnostics.kt`
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/ValueSupport.kt`
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/identity/ResourceKeyDerivation.kt`
- Modify: `kmp/api/kmp.klib.api`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/ResourcesTest.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/DiagnosticsAndFailuresTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `ResourceLimits.maximumDecodedImageBytes: Long` and `.maximumModelJsonChunkBytes: Long`; `RenGErrorCode.BASEMAP_RENDER_FAILED`; `PipelineStage.BASEMAP_RENDER`; `ResourceKind.BASEMAP_TILE`.

- [ ] **Step 1: Write the failing tests**

Add to `ResourcesTest.kt`:

```kotlin
@Test
fun decodedAndJsonChunkCeilingsHaveDocumentedDefaultsAndRanges() {
    val limits = ResourceLimits()
    assertEquals(64L * 1024L * 1024L, limits.maximumDecodedImageBytes)
    assertEquals(16L * 1024L * 1024L, limits.maximumModelJsonChunkBytes)

    assertFailsWith<IllegalArgumentException> { ResourceLimits(maximumDecodedImageBytes = 0L) }
    assertFailsWith<IllegalArgumentException> {
        ResourceLimits(maximumDecodedImageBytes = Int.MAX_VALUE.toLong() + 1L)
    }
    assertFailsWith<IllegalArgumentException> { ResourceLimits(maximumModelJsonChunkBytes = 0L) }
    assertFailsWith<IllegalArgumentException> {
        ResourceLimits(maximumModelJsonChunkBytes = Int.MAX_VALUE.toLong() + 1L)
    }
}

@Test
fun basemapTileIsANonExternalResourceKind() {
    assertTrue(ResourceKind.BASEMAP_TILE in ResourceKind.entries)
    // Only EXTERNAL keys carry a resource class; this invariant must survive the new entry.
    assertFailsWith<IllegalArgumentException> {
        ResourceKey(ResourceKind.BASEMAP_TILE, "0".repeat(64), ResourceClass.BASEMAP_RASTER_TILE)
    }
}
```

Add to `DiagnosticsAndFailuresTest.kt`:

```kotlin
@Test
fun basemapRenderFailureCarriesItsOwnStage() {
    val failure = RenGException(RenGErrorCode.BASEMAP_RENDER_FAILED, PipelineStage.BASEMAP_RENDER)
    assertEquals("RenG failure: BASEMAP_RENDER_FAILED at BASEMAP_RENDER", failure.message)
    assertNull(failure.cause)
    assertEquals(emptyList(), failure.diagnostics)
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew --no-configuration-cache :kmp:testAndroidHostTest --tests "com.rohittp.reng.ResourcesTest" --tests "com.rohittp.reng.DiagnosticsAndFailuresTest"`
Expected: FAIL — unresolved references.

- [ ] **Step 3: Add the declarations**

In `Resources.kt`, extend `ResourceLimits` with the two fields and their range checks, matching the existing eight exactly:

```kotlin
public data class ResourceLimits(
    // ... the existing eight parameters, unchanged ...
    public val maximumDecodedImageBytes: Long = 64L * 1024L * 1024L,
    public val maximumModelJsonChunkBytes: Long = 16L * 1024L * 1024L,
) {
    init {
        // ... the existing eight requires, unchanged ...
        require(maximumDecodedImageBytes in minimum..maximum) {
            "maximumDecodedImageBytes must be within the supported range"
        }
        require(maximumModelJsonChunkBytes in minimum..maximum) {
            "maximumModelJsonChunkBytes must be within the supported range"
        }
    }
}
```

Add `BASEMAP_TILE` to `ResourceKind`, `BASEMAP_RENDER_FAILED` to `RenGErrorCode`, and `BASEMAP_RENDER` to `PipelineStage`. Extend `reportOrder` in `internal/ValueSupport.kt` so `BASEMAP_TILE` sorts after the existing kinds, keeping report ordering total.

`ResourceKind` has a second exhaustive `when` with no `else`: `private val ResourceKind.wireValue: Int` in `internal/identity/ResourceKeyDerivation.kt` matches `EXTERNAL`, `GEOMETRY_PROGRAM`, `INTERNAL_PIPELINE`, and `OFFSCREEN_SURFACE` only, so adding `BASEMAP_TILE` without a branch there fails the build the same way an unextended `reportOrder` would. Add a fifth branch:

```kotlin
private val ResourceKind.wireValue: Int
    get() = when (this) {
        ResourceKind.EXTERNAL -> 1
        ResourceKind.GEOMETRY_PROGRAM -> 2
        ResourceKind.INTERNAL_PIPELINE -> 3
        ResourceKind.OFFSCREEN_SURFACE -> 4
        ResourceKind.BASEMAP_TILE -> 5
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew --no-configuration-cache :kmp:testAndroidHostTest :kmp:macosArm64Test`
Expected: PASS.

- [ ] **Step 5: Regenerate and review the ABI dump**

Run: `./gradlew --no-configuration-cache :kmp:updateKotlinAbi && git diff kmp/api/kmp.klib.api`
Expected: exactly five added lines — two `ResourceLimits` properties with their `copy`/`componentN` consequences, and three enum entries. **Read the whole diff.** If anything else appears, stop and find out why.

- [ ] **Step 6: Commit**

```bash
git add kmp/src/commonMain/kotlin/com/rohittp/reng/ kmp/src/commonTest/kotlin/com/rohittp/reng/ kmp/api/kmp.klib.api
git commit -m "feat(kmp): add the five public declarations Cycle C requires"
```

---

### Task 3: The inflate and CRC-32 seam, with one shared vector suite

**Files:**
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/image/Inflate.kt`
- Create: `kmp/src/nativeMain/kotlin/com/rohittp/reng/internal/image/Inflate.native.kt`
- Create: `kmp/src/androidMain/kotlin/com/rohittp/reng/internal/image/Inflate.android.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/image/InflateTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  ```kotlin
  internal expect class InflateStream() {
      fun inflate(input: ByteArray, output: ByteArray, outputOffset: Int): InflateStep
      fun close()
  }
  internal data class InflateStep(val consumed: Int, val produced: Int, val finished: Boolean)
  internal expect fun crc32(seed: UInt, bytes: ByteArray, offset: Int, length: Int): UInt
  internal class InflateException(message: String) : Exception(message)
  ```

The stream is incremental in both directions because PNG splits one zlib stream across arbitrarily many `IDAT` chunks and the decoder must never buffer the whole compressed payload.

- [ ] **Step 1: Write the failing vector suite**

`InflateTest.kt` holds every vector as a byte literal so all three test tasks assert identically:

```kotlin
package com.rohittp.reng.internal.image

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InflateTest {
    @Test
    fun inflatesStoredFixedAndDynamicBlocks() {
        for (vector in inflateVectors) {
            val output = ByteArray(vector.expected.size)
            val stream = InflateStream()
            var consumed = 0
            var produced = 0
            var finished = false
            while (!finished) {
                val step = stream.inflate(vector.deflated.copyOfRange(consumed, vector.deflated.size), output, produced)
                consumed += step.consumed
                produced += step.produced
                finished = step.finished
                if (step.consumed == 0 && step.produced == 0 && !step.finished) break
            }
            stream.close()
            assertTrue(finished, "${vector.name}: stream did not finish")
            assertEquals(vector.expected.size, produced, "${vector.name}: wrong output length")
            assertContentEquals(vector.expected, output, "${vector.name}: wrong bytes")
        }
    }

    @Test
    fun splitInputAcrossChunkBoundariesProducesIdenticalOutput() {
        val vector = inflateVectors.first { it.name == "dynamic_text" }
        val output = ByteArray(vector.expected.size)
        val stream = InflateStream()
        var produced = 0
        var finished = false
        // Feed one byte at a time: the PNG case, where a zlib stream is cut at
        // arbitrary offsets by IDAT chunk boundaries.
        var index = 0
        while (!finished && index <= vector.deflated.size) {
            val slice = vector.deflated.copyOfRange(index, minOf(index + 1, vector.deflated.size))
            val step = stream.inflate(slice, output, produced)
            index += step.consumed
            produced += step.produced
            finished = step.finished
            if (step.consumed == 0 && step.produced == 0 && !step.finished) index += 1
        }
        stream.close()
        assertTrue(finished)
        assertContentEquals(vector.expected, output)
    }

    @Test
    fun corruptPayloadFailsRatherThanProducingPlausibleBytes() {
        val vector = inflateVectors.first { it.name == "dynamic_text" }
        val corrupted = vector.deflated.copyOf()
        corrupted[corrupted.size / 2] = (corrupted[corrupted.size / 2].toInt() xor 0x5A).toByte()
        val stream = InflateStream()
        assertFailsWith<InflateException> {
            var produced = 0
            var finished = false
            var index = 0
            while (!finished) {
                val step = stream.inflate(corrupted.copyOfRange(index, corrupted.size), ByteArray(4096), produced)
                index += step.consumed
                produced += step.produced
                finished = step.finished
                if (step.consumed == 0 && !step.finished) break
            }
            if (!finished) throw InflateException("stalled")
        }
        stream.close()
    }

    @Test
    fun crc32MatchesKnownAnswers() {
        assertEquals(0u, crc32(0u, ByteArray(0), 0, 0))
        val check = "123456789".encodeToByteArray()
        assertEquals(0xCBF43926u, crc32(0u, check, 0, check.size))
        val ihdr = byteArrayOf(0x49, 0x48, 0x44, 0x52)
        assertEquals(crc32(0u, ihdr, 0, 4), crc32(crc32(0u, ihdr, 0, 2), ihdr, 2, 2))
    }
}
```

Generate `inflateVectors` with Python and paste the literals in. The suite must cover: empty input, a stored (level 0) block, fixed Huffman, dynamic Huffman, a 64 KiB output exercising long matches, and a 32 768-byte maximum back-reference distance.

```bash
python3 - <<'PY'
import zlib
cases = {
    "empty": (b"", 6),
    "stored_level0": (b"stored block payload", 0),
    "fixed_huffman_short": (b"aaaaaaaaaabbbbbbbbbb", 1),
    "dynamic_text": ((b"the quick brown fox jumps over the lazy dog " * 40), 9),
    "long_match_64k": (bytes(range(256)) * 256, 6),
    "max_distance": (b"A" + bytes(32767) + b"A" * 8, 9),
}
for name, (raw, level) in cases.items():
    print(name, list(zlib.compress(raw, level)), len(raw))
PY
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew --no-configuration-cache :kmp:testAndroidHostTest --tests "com.rohittp.reng.internal.image.InflateTest"`
Expected: FAIL — `InflateStream` unresolved.

- [ ] **Step 3: Declare the common seam**

`Inflate.kt`:

```kotlin
package com.rohittp.reng.internal.image

/** One incremental inflate step: input consumed, output produced, and whether the stream ended. */
internal data class InflateStep(
    val consumed: Int,
    val produced: Int,
    val finished: Boolean,
)

internal class InflateException(message: String) : Exception(message)

/**
 * A streaming zlib inflater. PNG splits one zlib stream across arbitrarily many IDAT chunks, so this
 * must accept input incrementally and must never require the whole compressed payload at once.
 */
internal expect class InflateStream() {
    fun inflate(input: ByteArray, output: ByteArray, outputOffset: Int): InflateStep
    fun close()
}

internal expect fun crc32(seed: UInt, bytes: ByteArray, offset: Int, length: Int): UInt
```

- [ ] **Step 4: Implement the native actual over `platform.zlib`**

`Inflate.native.kt`:

```kotlin
package com.rohittp.reng.internal.image

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import platform.zlib.Z_BUF_ERROR
import platform.zlib.Z_DATA_ERROR
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.ZLIB_VERSION
import platform.zlib.crc32 as zlibCrc32
import platform.zlib.inflate as zlibInflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit_
import platform.zlib.z_stream

@OptIn(ExperimentalForeignApi::class)
internal actual class InflateStream actual constructor() {
    private val stream = nativeHeap.alloc<z_stream>()
    private var closed = false

    init {
        val status = inflateInit_(stream.ptr, ZLIB_VERSION, sizeOf<z_stream>().toInt())
        if (status != Z_OK) {
            nativeHeap.free(stream.ptr)
            throw InflateException("inflateInit failed with $status")
        }
    }

    actual fun inflate(input: ByteArray, output: ByteArray, outputOffset: Int): InflateStep {
        require(!closed) { "inflate stream is closed" }
        require(outputOffset in 0..output.size) { "output offset out of range" }
        // addressOf(0) throws on an empty array, so guard both sides explicitly.
        if (input.isEmpty() && outputOffset == output.size) return InflateStep(0, 0, false)
        return input.usePinned { pinnedInput ->
            output.usePinned { pinnedOutput ->
                stream.next_in = if (input.isEmpty()) null else pinnedInput.addressOf(0).reinterpret()
                stream.avail_in = input.size.toUInt()
                stream.next_out = pinnedOutput.addressOf(outputOffset).reinterpret()
                stream.avail_out = (output.size - outputOffset).toUInt()
                val status = zlibInflate(stream.ptr, Z_NO_FLUSH)
                if (status != Z_OK && status != Z_STREAM_END && status != Z_BUF_ERROR) {
                    throw InflateException("inflate failed with $status")
                }
                InflateStep(
                    consumed = input.size - stream.avail_in.toInt(),
                    produced = (output.size - outputOffset) - stream.avail_out.toInt(),
                    finished = status == Z_STREAM_END,
                )
            }
        }
    }

    actual fun close() {
        if (closed) return
        closed = true
        inflateEnd(stream.ptr)
        nativeHeap.free(stream.ptr)
    }
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun crc32(seed: UInt, bytes: ByteArray, offset: Int, length: Int): UInt {
    require(offset >= 0 && length >= 0 && offset + length <= bytes.size) { "crc range out of bounds" }
    if (length == 0) return seed
    return bytes.usePinned { pinned ->
        zlibCrc32(seed.convert(), pinned.addressOf(offset).reinterpret(), length.toUInt()).toUInt()
    }
}
```

Add the imports `kotlinx.cinterop.convert`, `kotlinx.cinterop.reinterpret`, `kotlinx.cinterop.sizeOf`, and `kotlinx.cinterop.free` as the compiler requires. If `nativeMain` does not yet exist, create it — `platform.zlib` is present for all five native targets, so the shared set resolves it.

- [ ] **Step 5: Implement the Android actual over `java.util.zip`**

`Inflate.android.kt`:

```kotlin
package com.rohittp.reng.internal.image

import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater

internal actual class InflateStream actual constructor() {
    private val inflater = Inflater()
    private var closed = false

    actual fun inflate(input: ByteArray, output: ByteArray, outputOffset: Int): InflateStep {
        require(!closed) { "inflate stream is closed" }
        require(outputOffset in 0..output.size) { "output offset out of range" }
        var consumed = 0
        if (input.isNotEmpty() && inflater.needsInput()) {
            inflater.setInput(input)
            consumed = input.size
        }
        val produced = try {
            inflater.inflate(output, outputOffset, output.size - outputOffset)
        } catch (failure: DataFormatException) {
            throw InflateException("inflate failed: ${failure::class.simpleName}")
        }
        val remaining = inflater.remaining
        return InflateStep(
            consumed = consumed - remaining,
            produced = produced,
            finished = inflater.finished(),
        )
    }

    actual fun close() {
        if (closed) return
        closed = true
        inflater.end()
    }
}

internal actual fun crc32(seed: UInt, bytes: ByteArray, offset: Int, length: Int): UInt {
    require(offset >= 0 && length >= 0 && offset + length <= bytes.size) { "crc range out of bounds" }
    val digest = CRC32()
    if (seed != 0u) {
        // CRC32 has no seed setter; the PNG walk only ever seeds from zero or chains through
        // this function's own return value, so a non-zero seed means a chained call.
        throw IllegalArgumentException("chained crc32 seeds are supplied by the caller's running value")
    }
    digest.update(bytes, offset, length)
    return digest.value.toUInt()
}
```

The seed restriction is deliberate: chain CRC by accumulating over one `CRC32` per chunk in the caller rather than by seeding. Adjust `crc32`'s common signature to a small `Crc32` class if the caller needs chaining across calls — decide this in Task 4 when the chunk walk is written, and keep both actuals identical.

- [ ] **Step 6: Run the suite on all three test tasks**

Run:
```bash
./gradlew --no-configuration-cache --rerun-tasks \
  :kmp:testAndroidHostTest :kmp:macosArm64Test \
  --tests "com.rohittp.reng.internal.image.InflateTest"
./gradlew --no-configuration-cache :kmp:compileKotlinLinuxX64 :kmp:compileKotlinLinuxArm64 \
  :kmp:compileKotlinIosArm64 :kmp:compileKotlinIosSimulatorArm64
```
Expected: PASS on both test tasks with byte-identical results; all four remaining targets compile. `linuxX64Test` runs this same suite in CI.

- [ ] **Step 7: Commit**

```bash
git add kmp/src/commonMain/kotlin/com/rohittp/reng/internal/image/ kmp/src/nativeMain kmp/src/androidMain kmp/src/commonTest/kotlin/com/rohittp/reng/internal/image/
git commit -m "feat(kmp): add the streaming inflate and CRC-32 seam with a shared vector suite"
```

---

### Task 4: PNG container walk, chunk CRC, and IHDR admission

**Files:**
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/image/PngContainer.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/image/PngContainerTest.kt`

**Interfaces:**
- Consumes: `crc32` from Task 3.
- Produces:
  ```kotlin
  internal data class PngHeader(
      val width: Int, val height: Int, val bitDepth: Int, val colourType: Int,
      val interlaceMethod: Int,
  )
  internal sealed interface PngScan {
      data class Admitted(
          val header: PngHeader,
          val palette: ByteArray?, val transparency: ByteArray?,
          val imageDataRanges: List<IntRange>,
      ) : PngScan
      data class Malformed(val reason: PngReject) : PngScan
      data class Unsupported(val reason: PngReject) : PngScan
  }
  internal enum class PngReject {
      SIGNATURE, IHDR_NOT_FIRST, IHDR_LENGTH, IEND_NOT_LAST, TRAILING_BYTES,
      CHUNK_LENGTH, CHUNK_CRC, UNKNOWN_CRITICAL_CHUNK, COMPRESSION_METHOD,
      FILTER_METHOD, ZERO_DIMENSION, PALETTE_MISSING, PALETTE_FORBIDDEN,
      BIT_DEPTH, COLOUR_TYPE, INTERLACE,
  }
  internal fun scanPng(bytes: ByteArray): PngScan
  ```
  `Malformed` maps to `RESOURCE_DECODE_FAILED`; `Unsupported` maps to `UNSUPPORTED_RESOURCE_FEATURE`. `BIT_DEPTH`, `COLOUR_TYPE`, and `INTERLACE` are the only three that are `Unsupported`; every other reason is `Malformed`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.rohittp.reng.internal.image

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PngContainerTest {
    @Test
    fun admitsAMinimalEightBitTruecolourImage() {
        val scan = assertIs<PngScan.Admitted>(scanPng(rgb8TwoByTwo))
        assertEquals(PngHeader(2, 2, 8, 2, 0), scan.header)
        assertEquals(1, scan.imageDataRanges.size)
    }

    @Test
    fun admitsImageDataSplitAcrossSeveralChunks() {
        val scan = assertIs<PngScan.Admitted>(scanPng(rgb8SplitImageData))
        assertEquals(3, scan.imageDataRanges.size)
    }

    @Test
    fun skipsAncillaryChunksButStillValidatesTheirCrc() {
        assertIs<PngScan.Admitted>(scanPng(rgb8WithAncillaryChunks))
        assertEquals(PngReject.CHUNK_CRC, rejectionOf(rgb8WithBadAncillaryCrc))
    }

    @Test
    fun rejectsEveryMalformedShape() {
        assertEquals(PngReject.SIGNATURE, rejectionOf(wrongSignature))
        assertEquals(PngReject.IHDR_NOT_FIRST, rejectionOf(ihdrNotFirst))
        assertEquals(PngReject.IHDR_LENGTH, rejectionOf(ihdrWrongLength))
        assertEquals(PngReject.IEND_NOT_LAST, rejectionOf(iendNotLast))
        assertEquals(PngReject.TRAILING_BYTES, rejectionOf(trailingAfterIend))
        assertEquals(PngReject.CHUNK_LENGTH, rejectionOf(chunkLengthPastEnd))
        assertEquals(PngReject.CHUNK_CRC, rejectionOf(badCriticalCrc))
        assertEquals(PngReject.UNKNOWN_CRITICAL_CHUNK, rejectionOf(unknownCriticalChunk))
        assertEquals(PngReject.COMPRESSION_METHOD, rejectionOf(compressionMethodOne))
        assertEquals(PngReject.FILTER_METHOD, rejectionOf(filterMethodOne))
        assertEquals(PngReject.ZERO_DIMENSION, rejectionOf(zeroWidth))
        assertEquals(PngReject.PALETTE_MISSING, rejectionOf(colourTypeThreeWithoutPlte))
        assertEquals(PngReject.PALETTE_FORBIDDEN, rejectionOf(greyscaleWithPlte))
    }

    @Test
    fun reportsOutOfSubsetFeaturesAsUnsupportedRatherThanMalformed() {
        assertIs<PngScan.Unsupported>(scanPng(sixteenBitGreyscale))
        assertIs<PngScan.Unsupported>(scanPng(paletteAtBitDepthFour))
        assertIs<PngScan.Unsupported>(scanPng(adam7Interlaced))
        // An APNG carries acTL/fcTL/fdAT, which are ancillary, so it decodes as its base frame.
        assertIs<PngScan.Admitted>(scanPng(apngBaseFrame))
    }

    private fun rejectionOf(bytes: ByteArray): PngReject = when (val scan = scanPng(bytes)) {
        is PngScan.Malformed -> scan.reason
        is PngScan.Unsupported -> scan.reason
        is PngScan.Admitted -> error("unexpectedly admitted")
    }
}
```

Generate every fixture with Python and paste them as `private val name: ByteArray = byteArrayOf(...)`. A minimal generator:

```bash
python3 - <<'PY'
import zlib, struct
def chunk(kind, payload):
    return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload) & 0xffffffff)
def png(w, h, depth, colour, idat, interlace=0, extra=b""):
    ihdr = struct.pack(">IIBBBBB", w, h, depth, colour, 0, 0, interlace)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + extra + chunk(b"IDAT", idat) + chunk(b"IEND", b"")
raw = b"".join(b"\x00" + bytes(row) for row in ([255,0,0, 0,255,0], [0,0,255, 255,255,255]))
print("rgb8TwoByTwo", list(png(2, 2, 8, 2, zlib.compress(raw))))
PY
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew --no-configuration-cache :kmp:testAndroidHostTest --tests "com.rohittp.reng.internal.image.PngContainerTest"`
Expected: FAIL — `scanPng` unresolved.

- [ ] **Step 3: Implement the walk**

`scanPng` reads the eight-byte signature, then walks chunks: four big-endian length bytes, a four-byte type, the payload, and four CRC bytes over type plus payload. Every chunk's CRC is validated including ancillary ones. `IHDR` must be first and exactly 13 bytes; `IEND` must be last with nothing after it; a length that would run past the end is `CHUNK_LENGTH`.

A chunk is critical when the fifth bit of its first byte is clear (`type[0].code and 0x20 == 0`). Unknown critical chunks are rejected; unknown ancillary chunks are skipped. `PLTE` and `tRNS` payloads are captured; `IDAT` payload ranges are collected in order so Task 5 can stream them without concatenating.

Admission: bit depth must be 8; colour type must be 0, 2, 3, 4, or 6; interlace method must be 0. Those three failures are `Unsupported`. Compression method and filter method must both be 0, width and height must both be non-zero, `PLTE` is required for colour type 3 and forbidden for 0 and 4 — those are `Malformed`.

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew --no-configuration-cache :kmp:testAndroidHostTest :kmp:macosArm64Test --tests "com.rohittp.reng.internal.image.PngContainerTest"`
Expected: PASS on both.

- [ ] **Step 5: Commit**

```bash
git add kmp/src/commonMain/kotlin/com/rohittp/reng/internal/image/PngContainer.kt kmp/src/commonTest/kotlin/com/rohittp/reng/internal/image/PngContainerTest.kt
git commit -m "feat(kmp): walk and admit the accepted PNG container subset"
```

---

### Task 5: PNG unfiltering into one canonical RGBA8 form

**Files:**
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/image/PngDecoder.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/image/PngDecoderTest.kt`

**Interfaces:**
- Consumes: `scanPng`, `PngScan`, `InflateStream` from Tasks 3 and 4.
- Produces:
  ```kotlin
  internal class DecodedImage(val width: Int, val height: Int, rgba: ByteArray) {
      val byteCount: Int
      fun rgbaSnapshot(): ByteArray
  }
  internal sealed interface PngDecodeResult {
      data class Success(val image: DecodedImage) : PngDecodeResult
      data class Malformed(val reason: PngReject) : PngDecodeResult
      data class Unsupported(val reason: PngReject) : PngDecodeResult
      data object TooLarge : PngDecodeResult
  }
  internal fun decodePng(bytes: ByteArray, maximumDecodedBytes: Long): PngDecodeResult
  ```
  The output is always tightly packed RGBA8, unpremultiplied, with no row padding. Greyscale and palette inputs are widened losslessly; `tRNS` supplies alpha for colour types 0, 2, and 3.

- [ ] **Step 1: Write the failing tests**

```kotlin
class PngDecoderTest {
    @Test
    fun decodesEveryFilterTypeExactly() {
        // One fixture per filter byte 0..4, each 4x4 RGB, all encoding the same pixels.
        for ((name, bytes) in filterFixtures) {
            val result = assertIs<PngDecodeResult.Success>(decodePng(bytes, 1L shl 20), name)
            assertContentEquals(expectedFilterPixels, result.image.rgbaSnapshot(), name)
        }
    }

    @Test
    fun widensGreyscaleAndPaletteLosslessly() {
        val grey = assertIs<PngDecodeResult.Success>(decodePng(grey8TwoPixels, 1L shl 20))
        assertContentEquals(
            byteArrayOf(0x40, 0x40, 0x40, -1, -0x80, -0x80, -0x80, -1),
            grey.image.rgbaSnapshot(),
        )
        val palette = assertIs<PngDecodeResult.Success>(decodePng(paletteWithTrns, 1L shl 20))
        assertContentEquals(
            byteArrayOf(-1, 0, 0, -1, 0, 0, -1, -0x80),
            palette.image.rgbaSnapshot(),
        )
    }

    @Test
    fun rejectsAFilterByteAboveFour() {
        assertIs<PngDecodeResult.Malformed>(decodePng(filterByteFive, 1L shl 20))
    }

    @Test
    fun rejectsAStreamThatEndsEarlyOrRunsLong() {
        assertIs<PngDecodeResult.Malformed>(decodePng(deflateShorterThanRaster, 1L shl 20))
        assertIs<PngDecodeResult.Malformed>(decodePng(deflateLongerThanRaster, 1L shl 20))
    }

    @Test
    fun decidesTheCeilingFromHeaderDimensionsBeforeAllocating() {
        // 4096x4096 RGBA is 67_108_864 bytes; the declared ceiling is one byte short.
        assertIs<PngDecodeResult.TooLarge>(decodePng(declared4096Square, 67_108_863L))
        // The same header passes when the ceiling admits it, proving the check is on dimensions.
        assertIs<PngDecodeResult.Success>(decodePng(declared4096Square, 67_108_864L))
    }

    @Test
    fun appliesNoColourTransformForAnyAncillaryColourChunk() {
        val plain = assertIs<PngDecodeResult.Success>(decodePng(rgb8Reference, 1L shl 20))
        for (fixture in listOf(rgb8WithGama, rgb8WithSrgb, rgb8WithIccp, rgb8WithChrm)) {
            val decoded = assertIs<PngDecodeResult.Success>(decodePng(fixture, 1L shl 20))
            assertContentEquals(plain.image.rgbaSnapshot(), decoded.image.rgbaSnapshot())
        }
    }

    @Test
    fun decodedImageCopiesOnEveryRead() {
        val image = assertIs<PngDecodeResult.Success>(decodePng(rgb8Reference, 1L shl 20)).image
        val first = image.rgbaSnapshot()
        first[0] = 0
        assertNotEquals(first[0], image.rgbaSnapshot()[0])
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew --no-configuration-cache :kmp:testAndroidHostTest --tests "com.rohittp.reng.internal.image.PngDecoderTest"`
Expected: FAIL.

- [ ] **Step 3: Implement the decoder**

Compute bytes-per-pixel from colour type, then `stride = width * bytesPerPixel` and `rawSize = height * (stride + 1)` in `Long`. Decide the ceiling first: `width.toLong() * height.toLong() * 4L > maximumDecodedBytes` returns `TooLarge` **before any array is allocated**. Then allocate the raster, feed each `IDAT` range into one `InflateStream` in order, and unfilter per scanline.

The five filters are the specification's own, operating on bytes not pixels, where `a` is the byte `bytesPerPixel` to the left, `b` the byte above, and `c` the byte above-left, each zero outside the image:

```kotlin
when (filter) {
    0 -> x
    1 -> x + a
    2 -> x + b
    3 -> x + ((a + b) / 2)          // integer division, before adding
    4 -> x + paeth(a, b, c)
    else -> return PngDecodeResult.Malformed(PngReject.FILTER_METHOD)
}
```

```kotlin
private fun paeth(a: Int, b: Int, c: Int): Int {
    val p = a + b - c
    val pa = kotlin.math.abs(p - a)
    val pb = kotlin.math.abs(p - b)
    val pc = kotlin.math.abs(p - c)
    return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
}
```

All filter arithmetic is modulo 256 on unsigned bytes. After unfiltering, widen into RGBA8: colour type 0 replicates grey into RGB with alpha 255 or the `tRNS` grey; type 2 copies RGB with alpha 255 or the `tRNS` triple; type 3 indexes `PLTE` with alpha from `tRNS` or 255; types 4 and 6 copy through. `tRNS` is rejected for colour types 4 and 6 as `Malformed`.

Finally require the inflate stream to have finished exactly at `rawSize`: a stream that ends earlier or yields more is `Malformed`.

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew --no-configuration-cache --rerun-tasks :kmp:testAndroidHostTest :kmp:macosArm64Test --tests "com.rohittp.reng.internal.image.PngDecoderTest"`
Expected: PASS on both, byte-identical.

- [ ] **Step 5: Commit**

```bash
git add kmp/src/commonMain/kotlin/com/rohittp/reng/internal/image/PngDecoder.kt kmp/src/commonTest/kotlin/com/rohittp/reng/internal/image/PngDecoderTest.kt
git commit -m "feat(kmp): decode PNG into one canonical unpremultiplied RGBA8 form"
```

---

### Task 6: Strict UTF-8 and the JSON reader

**Files:**
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/json/Utf8.kt`
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/json/JsonReader.kt`
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/ValueSupport.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/json/JsonReaderTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  ```kotlin
  internal sealed interface JsonValue {
      data object Null : JsonValue
      data class Bool(val value: Boolean) : JsonValue
      data class Integer(val value: Long) : JsonValue
      data class Real(val value: Double) : JsonValue
      data class Text(val value: String) : JsonValue
      class Arr(elements: List<JsonValue>) : JsonValue
      class Obj(members: Map<String, JsonValue>) : JsonValue
  }
  internal sealed interface JsonParse {
      data class Parsed(val value: JsonValue, val endOffset: Int) : JsonParse
      data class Failed(val reason: JsonReject) : JsonParse
  }
  internal fun parseJson(bytes: ByteArray, offset: Int, endExclusive: Int, maximumDepth: Int): JsonParse
  internal fun containsOnlyUnicodeScalars(value: String): Boolean
  ```
  `Integer` and `Real` are distinct by **spelling**: a token with no fraction and no exponent that fits in `Long` is `Integer`; anything else is `Real`. Every glTF field the specification types as an integer is read only from `Integer`, so `1e2` is a number but is not an index.

- [ ] **Step 1: Write the failing tests**

```kotlin
class JsonReaderTest {
    @Test
    fun classifiesNumbersBySpellingRatherThanValue() {
        assertEquals(JsonValue.Integer(1L), memberOf("""{"a":1}"""))
        // 2^53 + 1 is exactly the value a Double loses.
        assertEquals(JsonValue.Integer(9007199254740993L), memberOf("""{"a":9007199254740993}"""))
        assertEquals(JsonValue.Real(100.0), memberOf("""{"a":1e2}"""))
        assertEquals(JsonValue.Integer(0L), memberOf("""{"a":-0}"""))
        assertEquals(JsonReject.NON_FINITE_NUMBER, rejectionOf("""{"a":1E+400}"""))
    }

    @Test
    fun rejectsEveryGrammarViolationTheSubsetNames() {
        assertEquals(JsonReject.DUPLICATE_MEMBER_NAME, rejectionOf("""{"a":1,"a":2}"""))
        assertEquals(JsonReject.LEADING_ZERO, rejectionOf("""{"a":01}"""))
        assertEquals(JsonReject.BAD_FRACTION, rejectionOf("""{"a":5.}"""))
        assertEquals(JsonReject.EXPECTED_MEMBER_NAME, rejectionOf("""{"a":1,}"""))
        assertEquals(JsonReject.TRAILING_CONTENT, rejectionOf("{}{}"))
        assertEquals(JsonReject.UNESCAPED_CONTROL_CHARACTER, rejectionOf("{\"a\":\"tab\there\"}"))
        assertEquals(JsonReject.LONE_HIGH_SURROGATE_ESCAPE, rejectionOf("""{"a":"\uD800"}"""))
        assertEquals(JsonReject.BAD_ESCAPE, rejectionOf("""{"a":"\x"}"""))
    }

    @Test
    fun rejectsAByteOrderMarkAndBoundsDepth() {
        assertEquals(JsonReject.UNEXPECTED_CHARACTER, rejectionOf("﻿{}"))
        assertIs<JsonParse.Parsed>(parseJson(nested(64), 0, nested(64).size, 64))
        assertEquals(JsonReject.DEPTH_EXCEEDED, rejectionOf(nested(65), maximumDepth = 64))
    }

    @Test
    fun rejectsMalformedUtf8WithoutSubstitutingAReplacementCharacter() {
        assertEquals(JsonReject.UTF8_OVERLONG, rejectionOfBytes(byteArrayOf(0x22, 0xE0.toByte(), 0x80.toByte(), 0x80.toByte(), 0x22)))
        assertEquals(JsonReject.UTF8_ENCODED_SURROGATE, rejectionOfBytes(byteArrayOf(0x22, 0xED.toByte(), 0xA0.toByte(), 0x80.toByte(), 0x22)))
        assertEquals(JsonReject.UTF8_TRUNCATED_SEQUENCE, rejectionOfBytes(byteArrayOf(0x22, 0xE2.toByte(), 0x82.toByte(), 0x22)))
    }

    @Test
    fun sharedScalarPredicateAgreesWithTheThrowingValidator() {
        assertTrue(containsOnlyUnicodeScalars("astral 😀"))
        assertFalse(containsOnlyUnicodeScalars("lone \uD800"))
        assertFailsWith<IllegalArgumentException> { requireUnicodeScalars("lone \uD800", "field", nonBlank = true) }
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew --no-configuration-cache :kmp:testAndroidHostTest --tests "com.rohittp.reng.internal.json.JsonReaderTest"`
Expected: FAIL.

- [ ] **Step 3: Extract the shared scalar predicate**

In `internal/ValueSupport.kt`, add a non-throwing predicate and refactor the existing validator to call it. This is a planned, reviewed touch of a Cycle B file: the scanning logic is shared, but the failure mode is not — `IllegalArgumentException` stays reserved for public value-constructor violations, while a malformed document is a typed `RenGException`.

```kotlin
internal fun containsOnlyUnicodeScalars(value: String): Boolean {
    var index = 0
    while (index < value.length) {
        val unit = value[index]
        if (unit.isHighSurrogate()) {
            if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) return false
            index += 2
            continue
        }
        if (unit.isLowSurrogate()) return false
        index += 1
    }
    return true
}
```

Then rewrite `requireUnicodeScalars`'s surrogate scan as `require(containsOnlyUnicodeScalars(value)) { ... }`, leaving its message and blank-checking behaviour byte-identical.

- [ ] **Step 4: Implement the UTF-8 decoder and the reader**

The decoder accepts only shortest-form sequences: reject an overlong encoding, a lead byte above `F4`, any encoded surrogate (`ED A0 80`, the CESU-8 form), a truncated sequence, and an invalid continuation. It never substitutes `U+FFFD`, because that is repair.

The reader implements RFC 8259 with the strictness above, tracks nesting against `maximumDepth`, and rejects a duplicate member name because first-wins and last-wins readers would otherwise disagree about what a document means. `JsonParse.Parsed` returns `endOffset` so the caller can enforce what follows — Task 7 uses it for the GLB padding rule.

- [ ] **Step 5: Run to verify it passes**

Run: `./gradlew --no-configuration-cache --rerun-tasks :kmp:testAndroidHostTest :kmp:macosArm64Test`
Expected: PASS, and every existing `ValueSupport` test still green.

- [ ] **Step 6: Commit**

```bash
git add kmp/src/commonMain/kotlin/com/rohittp/reng/internal/json/ kmp/src/commonMain/kotlin/com/rohittp/reng/internal/ValueSupport.kt kmp/src/commonTest/kotlin/com/rohittp/reng/internal/json/
git commit -m "feat(kmp): add a strict UTF-8 and JSON reader with a shared scalar predicate"
```

---

### Task 7: GLB container and the strict-space padding rule

**Files:**
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/glb/GlbContainer.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/glb/GlbContainerTest.kt`

**Interfaces:**
- Consumes: `parseJson` from Task 6.
- Produces:
  ```kotlin
  internal sealed interface GlbScan {
      data class Admitted(val json: JsonValue.Obj, val binChunk: IntRange?) : GlbScan
      data class Malformed(val reason: GlbReject) : GlbScan
  }
  internal enum class GlbReject {
      HEADER_TOO_SHORT, BAD_MAGIC, UNSUPPORTED_CONTAINER_VERSION,
      DECLARED_LENGTH_MISMATCH, CHUNK_LENGTH_MISALIGNED, TRUNCATED_CHUNK_HEADER,
      TRUNCATED_CHUNK_DATA, JSON_CHUNK_NOT_FIRST, EMPTY_JSON_CHUNK,
      BIN_CHUNK_NOT_SECOND, UNKNOWN_CHUNK_IN_BIN_POSITION,
      JSON_TRAILING_CONTENT, JSON_PADDING_NOT_SPACE, JSON_CHUNK_TOO_LARGE,
  }
  internal fun scanGlb(bytes: ByteArray, maximumJsonChunkBytes: Long): GlbScan
  ```

- [ ] **Step 1: Write the failing tests**

Port the research document's forty-one fixtures. The classifications are already known and are the assertions:

```kotlin
class GlbContainerTest {
    @Test
    fun classifiesEveryContainerFixtureAsIntended() {
        assertIs<GlbScan.Admitted>(scan("01-valid-json-and-bin"))
        assertIs<GlbScan.Admitted>(scan("02-valid-json-only"))
        assertEquals(GlbReject.BAD_MAGIC, reject("03-bad-magic"))
        assertEquals(GlbReject.UNSUPPORTED_CONTAINER_VERSION, reject("04-version-1"))
        assertEquals(GlbReject.UNSUPPORTED_CONTAINER_VERSION, reject("05-version-3"))
        // Five different authoring accidents collapse into one equality comparison.
        for (name in listOf(
            "06-truncated-chunk-data", "07-declared-length-too-large",
            "08-declared-length-not-multiple-of-4", "13-truncated-chunk-header",
            "31-trailing-garbage-length-unchanged",
        )) {
            assertEquals(GlbReject.DECLARED_LENGTH_MISMATCH, reject(name), name)
        }
        assertEquals(GlbReject.BIN_CHUNK_NOT_SECOND, reject("10-json-chunk-second"))
        assertEquals(GlbReject.JSON_CHUNK_NOT_FIRST, reject("15-two-json-chunks"))
        assertEquals(GlbReject.UNKNOWN_CHUNK_IN_BIN_POSITION, reject("18-bin-after-unknown-chunk"))
        assertEquals(GlbReject.EMPTY_JSON_CHUNK, reject("14-empty-json-chunk"))
        assertIs<GlbScan.Admitted>(scan("17-unknown-chunk-third"))
        assertEquals(GlbReject.HEADER_TOO_SHORT, reject("30-empty-file"))
    }

    @Test
    fun adoptsTheStrictSpacePaddingRule() {
        assertIs<GlbScan.Admitted>(scan("19-json-padded-with-spaces"))
        assertEquals(GlbReject.JSON_TRAILING_CONTENT, reject("20-json-padded-with-nulls"))
        // Tab is JSON whitespace, so only the strict rule catches it.
        assertEquals(GlbReject.JSON_PADDING_NOT_SPACE, reject("21-json-padded-with-tabs"))
    }

    @Test
    fun boundsTheBinChunkByTheDeclaredBufferLength() {
        assertIs<GlbScan.Admitted>(scan("27-buffer-3-shorter-than-bin-chunk"))
        assertIs<GlbScan.Admitted>(scan("22-bin-padded-with-zeros"))
        // Padding bytes are unverifiable: chunkLength includes them and nothing records the
        // unpadded length, so 22 and 23 differ only in pad bytes and both are admitted.
        assertIs<GlbScan.Admitted>(scan("23-bin-padded-with-spaces"))
    }

    @Test
    fun boundsTheJsonChunkIndependentlyOfTheWholeGlbCeiling() {
        assertEquals(GlbReject.JSON_CHUNK_TOO_LARGE, rejectWithCeiling("02-valid-json-only", 8L))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew --no-configuration-cache :kmp:testAndroidHostTest --tests "com.rohittp.reng.internal.glb.GlbContainerTest"`
Expected: FAIL.

- [ ] **Step 3: Implement the walk**

Twelve-byte header: magic exactly `0x46546C67`, version exactly `2`, and a declared length that **equals** the actual byte count and is a multiple of four. The equality reading is stricter than the wording and is deliberate — it collapses truncation, an inflated length, a misaligned length, a file ending inside a chunk header, and appended garbage into one comparison.

Then walk chunks: each needs eight readable header bytes, a `chunkLength` that is a multiple of four and does not run past the end. Chunk one must be JSON (`0x4E4F534A`) and non-empty; a BIN chunk (`0x004E4942`) is permitted only as chunk two; an unknown chunk in position two is rejected rather than scanned past, because the specification permits extension chunks only after the first two. Chunks from position three on with other types are ignored.

Bound the JSON chunk against `maximumJsonChunkBytes` **before** parsing it. Parse it, then require every byte from `endOffset` to the chunk end to be exactly `0x20`.

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew --no-configuration-cache --rerun-tasks :kmp:testAndroidHostTest :kmp:macosArm64Test --tests "com.rohittp.reng.internal.glb.GlbContainerTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add kmp/src/commonMain/kotlin/com/rohittp/reng/internal/glb/GlbContainer.kt kmp/src/commonTest/kotlin/com/rohittp/reng/internal/glb/GlbContainerTest.kt
git commit -m "feat(kmp): scan the GLB container with the strict-space padding rule"
```

---

### Task 8: The glTF document and the PARSE_GLB gate

**Files:**
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/glb/GltfDocument.kt`
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/glb/GltfParse.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/glb/GltfParseTest.kt`

**Interfaces:**
- Consumes: `JsonValue` from Task 6; `scanGlb` from Task 7.
- Produces:
  ```kotlin
  internal data class GltfAccessor(
      val bufferView: Int?, val byteOffset: Long, val componentType: Int,
      val count: Long, val type: String, val normalized: Boolean, val sparse: Boolean,
  )
  internal data class GltfDocument(
      val accessors: List<GltfAccessor>, val bufferViews: List<GltfBufferView>,
      val meshes: List<GltfMesh>, val nodes: List<GltfNode>, val scenes: List<GltfScene>,
      val defaultScene: Int?, val animations: List<GltfAnimation>,
      val materials: List<GltfMaterial>, val images: List<GltfImage>,
      val textures: List<GltfTexture>, val samplers: List<GltfSampler>,
      val extensionsRequired: List<String>, val buffers: List<GltfBuffer>,
  )
  internal enum class GltfReject {
      ACCESSOR_SPAN_EXCEEDS_BUFFER_VIEW, BUFFER_VIEW_EXCEEDS_BUFFER,
      NODE_MATRIX_AND_TRS, NODE_GRAPH_NOT_DISJOINT_TREES, NODE_DEPTH_EXCEEDED,
      INDEX_OUT_OF_RANGE, INDEX_VALUE_OUT_OF_RANGE, DUPLICATE_ANIMATION_NAME,
      NON_INTEGER_INDEX, COMPONENT_TYPE,
  }
  internal sealed interface GltfParseResult {
      data class Parsed(val document: GltfDocument) : GltfParseResult
      data class Malformed(val reason: GltfReject) : GltfParseResult
  }
  internal fun parseGltf(json: JsonValue.Obj, binChunkLength: Long, maximumNodeDepth: Int): GltfParseResult
  ```

**`PARSE_GLB` must be permissive about anything the specification permits, even when `VALIDATE_GLB_FEATURES` will refuse it.** An accessor with no `bufferView` is legal, means all zeros, and is the Draco signature; treating it as malformed here reports corruption for a file whose real problem is an unsupported extension.

- [ ] **Step 1: Write the failing tests**

```kotlin
class GltfParseTest {
    @Test
    fun toleratesAnAccessorWithNoBufferView() {
        // Fixture 41's shape: extensionsRequired names a compression extension and the
        // accessor has no bufferView. PARSE_GLB must accept; the feature gate rejects it.
        val parsed = assertIs<GltfParseResult.Parsed>(parse(dracoShapedDocument))
        assertNull(parsed.document.accessors[0].bufferView)
        assertEquals(listOf("KHR_draco_mesh_compression"), parsed.document.extensionsRequired)
    }

    @Test
    fun checksAccessorArithmeticInLongBeforeAllocating() {
        assertEquals(GltfReject.ACCESSOR_SPAN_EXCEEDS_BUFFER_VIEW, reject(accessorCountTwoPowForty))
        assertEquals(GltfReject.ACCESSOR_SPAN_EXCEEDS_BUFFER_VIEW, reject(accessorOffsetPastView))
        assertEquals(GltfReject.BUFFER_VIEW_EXCEEDS_BUFFER, reject(bufferViewPastBuffer))
        assertIs<GltfParseResult.Parsed>(parse(accessorFitsExactly))
        assertIs<GltfParseResult.Parsed>(parse(accessorInterleavedStride))
    }

    @Test
    fun rejectsAContradictoryOrCyclicNodeGraph() {
        assertEquals(GltfReject.NODE_MATRIX_AND_TRS, reject(nodeWithMatrixAndTrs))
        assertEquals(GltfReject.NODE_GRAPH_NOT_DISJOINT_TREES, reject(nodeCycle))
        assertEquals(GltfReject.NODE_GRAPH_NOT_DISJOINT_TREES, reject(nodeWithTwoParents))
        assertEquals(GltfReject.NODE_DEPTH_EXCEEDED, reject(nodeChainOfLength(200), maximumNodeDepth = 128))
    }

    @Test
    fun rejectsIndexReferencesOutOfRangeAndReservedIndexValues() {
        assertEquals(GltfReject.INDEX_OUT_OF_RANGE, reject(meshNamingMissingAccessor))
        assertEquals(GltfReject.INDEX_VALUE_OUT_OF_RANGE, reject(indexValueAboveVertexCount))
    }

    @Test
    fun rejectsDuplicateNonBlankAnimationNames() {
        assertEquals(GltfReject.DUPLICATE_ANIMATION_NAME, reject(twoAnimationsNamedWalk))
        // Absent and blank names are legal and addressable only by index.
        assertIs<GltfParseResult.Parsed>(parse(animationsWithBlankAndAbsentNames))
    }

    @Test
    fun readsIntegerFieldsOnlyFromIntegerSpelling() {
        // 1e2 is a JSON number but is not an index.
        assertEquals(GltfReject.NON_INTEGER_INDEX, reject(bufferViewIndexWrittenAsExponent))
    }

    @Test
    fun rejectsAnUnknownComponentTypeAsMalformedNotUnsupported() {
        // An unknown componentType has no known size, so accessor arithmetic is undecidable.
        assertEquals(GltfReject.COMPONENT_TYPE, reject(componentType9999))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew --no-configuration-cache :kmp:testAndroidHostTest --tests "com.rohittp.reng.internal.glb.GltfParseTest"`
Expected: FAIL.

- [ ] **Step 3: Implement the parse gate**

Read `asset.version` (major 2, `minVersion` at most 2.0). Read every array, taking integer-typed fields only from `JsonValue.Integer`. Validate component types against exactly `5120, 5121, 5122, 5123, 5125, 5126`; anything else is malformed because its size is unknown.

Validate the complete accessor arithmetic in `Long` before any array exists:

```
byteOffset + (count - 1) * effectiveStride + elementSize <= bufferView.byteLength
bufferView.byteOffset + bufferView.byteLength <= buffer.byteLength
buffers[0].byteLength <= binChunkLength
```

Reject `byteStride` below the element size or not a multiple of the component size. Walk the node hierarchy **iteratively** against `maximumNodeDepth`, rejecting a cycle or a node with two parents — the specification requires disjoint strict trees, and the iterative walk is what makes a cyclic graph terminate. Reject a node carrying both `matrix` and TRS. Reject an index value equal to the reserved maximum for its component type or at or above the attribute count.

Reject two or more animations sharing the same non-blank exact name, per `CONTEXT.md`; an absent or blank name is legal and simply unaddressable by `AnimationSelector.Name`.

- [ ] **Step 4: Run to verify it passes, then commit**

Run: `./gradlew --no-configuration-cache --rerun-tasks :kmp:testAndroidHostTest :kmp:macosArm64Test --tests "com.rohittp.reng.internal.glb.GltfParseTest"`

```bash
git add kmp/src/commonMain/kotlin/com/rohittp/reng/internal/glb/ kmp/src/commonTest/kotlin/com/rohittp/reng/internal/glb/
git commit -m "feat(kmp): parse the glTF document and enforce the PARSE_GLB gate"
```

---

### Task 9: The VALIDATE_GLB_FEATURES gate

**Files:**
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/glb/GltfFeatures.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/glb/GltfFeaturesTest.kt`

**Interfaces:**
- Consumes: `GltfDocument` from Task 8.
- Produces:
  ```kotlin
  internal enum class GltfUnsupported {
      EXTENSION_REQUIRED, ACCESSOR_WITHOUT_BUFFER_VIEW, SPARSE_ACCESSOR, PRIMITIVE_MODE,
      ATTRIBUTE_SEMANTIC, SKIN, MORPH_TARGET, ANIMATION_TARGET_PATH, INTERPOLATION,
      IMAGE_MEDIA_TYPE, EXTERNAL_URI, SCENE_AMBIGUOUS, NORMALIZED_NOT_PERMITTED,
  }
  internal sealed interface GltfFeatureResult {
      data object Supported : GltfFeatureResult
      data class Unsupported(val reason: GltfUnsupported) : GltfFeatureResult
  }
  internal fun validateGltfFeatures(document: GltfDocument): GltfFeatureResult
  ```

- [ ] **Step 1: Write the failing tests**

One assertion per row of ADR 0021's table, so no row can regress silently:

```kotlin
class GltfFeaturesTest {
    @Test
    fun rejectsEveryUnsupportedFeatureWithItsOwnReason() {
        assertEquals(GltfUnsupported.EXTENSION_REQUIRED, unsupported(dracoShapedDocument))
        assertEquals(GltfUnsupported.ACCESSOR_WITHOUT_BUFFER_VIEW, unsupported(accessorWithoutBufferViewNoExtension))
        assertEquals(GltfUnsupported.SPARSE_ACCESSOR, unsupported(sparseAccessor))
        assertEquals(GltfUnsupported.PRIMITIVE_MODE, unsupported(triangleStrip))
        assertEquals(GltfUnsupported.PRIMITIVE_MODE, unsupported(pointsMode))
        assertEquals(GltfUnsupported.ATTRIBUTE_SEMANTIC, unsupported(texcoordOne))
        assertEquals(GltfUnsupported.ATTRIBUTE_SEMANTIC, unsupported(colourOne))
        assertEquals(GltfUnsupported.ATTRIBUTE_SEMANTIC, unsupported(customAttribute))
        assertEquals(GltfUnsupported.SKIN, unsupported(documentWithSkin))
        assertEquals(GltfUnsupported.MORPH_TARGET, unsupported(documentWithMorphTargets))
        assertEquals(GltfUnsupported.ANIMATION_TARGET_PATH, unsupported(weightsChannel))
        assertEquals(GltfUnsupported.INTERPOLATION, unsupported(cubicSplineSampler))
        assertEquals(GltfUnsupported.IMAGE_MEDIA_TYPE, unsupported(jpegImage))
        assertEquals(GltfUnsupported.EXTERNAL_URI, unsupported(imageWithUri))
        assertEquals(GltfUnsupported.EXTERNAL_URI, unsupported(bufferWithDataUri))
        assertEquals(GltfUnsupported.SCENE_AMBIGUOUS, unsupported(twoScenesNoDefault))
        assertEquals(GltfUnsupported.NORMALIZED_NOT_PERMITTED, unsupported(normalizedFloatAccessor))
    }

    @Test
    fun acceptsEverythingTheSubsetAdmits() {
        for (fixture in listOf(
            triangleIndexed, triangleNonIndexed, nodeMatrixOnly, nodeTrsOnly,
            tangentAndColourZero, sceneAbsentWithExactlyOneScene, interleavedStride,
            linearAndStepAnimation, embeddedPngImage, fullMaterialBlock,
            cameraAndLightIgnored, extensionsUsedWithoutRequired,
        )) {
            assertEquals(GltfFeatureResult.Supported, validateGltfFeatures(fixture.document))
        }
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew --no-configuration-cache :kmp:testAndroidHostTest --tests "com.rohittp.reng.internal.glb.GltfFeaturesTest"`
Expected: FAIL.

- [ ] **Step 3: Implement the gate**

Reject a non-empty `extensionsRequired` with one blanket rule — that covers Draco, meshopt, Basis and every future compression extension without going stale. Reject sparse accessors, any primitive mode other than 4, `TEXCOORD_n`/`COLOR_n` above zero, joints and weights, unrecognised attribute semantics including `_CUSTOM`, skins, morph targets, `weights` animation channels, `CUBICSPLINE`, JPEG images, any `uri` on a buffer or image including a `data:` URI, and `scene` absent with zero or two-or-more scenes.

Accept and ignore `TANGENT`, `COLOR_0`, `extensionsUsed` without `extensionsRequired`, unknown `extensions`/`extras`, cameras and lights, and a channel with no `target.node`. Parse and retain the whole `pbrMetallicRoughness` block, the four secondary texture slots, and the alpha and cull state, because a base-colour override is specified to preserve every other material property.

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew --no-configuration-cache --rerun-tasks :kmp:testAndroidHostTest :kmp:macosArm64Test --tests "com.rohittp.reng.internal.glb.GltfFeaturesTest"`
Expected: PASS on both.

**Deferred, not a checkbox step — the Khronos sample-model corpus count.** ADR 0021 names running the container and feature layers over the Khronos glTF-Sample-Models corpus as the check most likely to move a row from reject to accept, and states it is owed before the first release that draws a model. Unlike every checkbox step above, this plan cannot yet give it a pinned download location, a fixed asset subset, or a command that produces a recorded pass/fail result, so it is left as a standing obligation rather than a step nobody can actually check off. Whichever later task first draws an authored model must add the concrete download step, the command, and the recorded per-asset reject/accept counts, and must write those counts into ADR 0021 as an addendum if any row should change. Do not silently widen the subset in the meantime.

- [ ] **Step 5: Commit**

```bash
git add kmp/src/commonMain/kotlin/com/rohittp/reng/internal/glb/GltfFeatures.kt kmp/src/commonTest/kotlin/com/rohittp/reng/internal/glb/GltfFeaturesTest.kt
git commit -m "feat(kmp): enforce the supported GLB feature subset"
```

---

### Task 10: The resident cache — generations, leases, and reload markers

**Files:**
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/cache/ResidentCache.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/cache/ResidentCacheTest.kt`

**Interfaces:**
- Consumes: `ResourceKey`, `ResourceReport`, `ResourceFreeResult`, `StoredRawResource`.
- Produces:
  ```kotlin
  internal class ResidentCache {
      fun current(key: ResourceKey): ResidentGeneration?
      fun install(key: ResourceKey, stored: StoredRawResource, decoded: DecodedImage?): ResidentGeneration
      fun takeLease(generation: ResidentGeneration): Lease
      fun releaseLease(lease: Lease)
      fun free(selector: ResourceSelector): ResourceFreeResult
      fun report(selector: ResourceSelector): ResourceReport
      fun wasFreed(key: ResourceKey): Boolean
      fun closeAll()
  }
  ```

- [ ] **Step 1: Write the failing tests**

```kotlin
class ResidentCacheTest {
    @Test
    fun onlyTheCurrentGenerationSatisfiesALookup() {
        val cache = ResidentCache()
        val first = cache.install(key, storedA, null)
        val lease = cache.takeLease(first)
        val second = cache.install(key, storedB, null)
        assertEquals(second, cache.current(key))
        // The superseded generation stays usable while leased.
        assertEquals(2, cache.report(ResourceSelector.ByKey(key)).entries.single().residentGenerationCount)
        cache.releaseLease(lease)
        assertEquals(1, cache.report(ResourceSelector.ByKey(key)).entries.single().residentGenerationCount)
    }

    @Test
    fun freeRetiresEveryGenerationAndDefersThoseStillLeased() {
        val cache = ResidentCache()
        val generation = cache.install(key, storedA, null)
        val lease = cache.takeLease(generation)
        val result = cache.free(ResourceSelector.ByKey(key))
        assertEquals(ResourceFreeResult(matchedKeys = 1, fullyFreedKeys = 0, deferredKeys = 1, alreadyFreeKeys = 0), result)
        assertNull(cache.current(key))
        cache.releaseLease(lease)
        assertEquals(0, cache.report(ResourceSelector.ByKey(key)).entries.single().retiredGenerationCount)
    }

    @Test
    fun freeWithNoLeaseReportsFullyFreedAndASecondFreeReportsAlreadyFree() {
        val cache = ResidentCache()
        cache.install(key, storedA, null)
        assertEquals(1, cache.free(ResourceSelector.ByKey(key)).fullyFreedKeys)
        assertEquals(1, cache.free(ResourceSelector.ByKey(key)).alreadyFreeKeys)
    }

    @Test
    fun aFreedKeyIsDistinguishableFromOneNeverLoaded() {
        val cache = ResidentCache()
        assertFalse(cache.wasFreed(key))
        cache.install(key, storedA, null)
        cache.free(ResourceSelector.ByKey(key))
        assertTrue(cache.wasFreed(key))
        assertTrue(cache.report(ResourceSelector.ByKey(key)).entries.single().reloadRequired)
    }

    @Test
    fun aRetiredGenerationIsNeverResurrectedByIdenticalBytes() {
        val cache = ResidentCache()
        val first = cache.install(key, storedA, null)
        val lease = cache.takeLease(first)
        cache.free(ResourceSelector.ByKey(key))
        val reloaded = cache.install(key, storedA, null)
        assertNotSame(first, reloaded)
        assertEquals(1, cache.report(ResourceSelector.ByKey(key)).entries.single().retiredGenerationCount)
        cache.releaseLease(lease)
    }

    @Test
    fun manyLeasesShareOneGeneration() {
        val cache = ResidentCache()
        val generation = cache.install(key, storedA, null)
        val leases = List(8) { cache.takeLease(generation) }
        assertEquals(8, cache.report(ResourceSelector.ByKey(key)).entries.single().leaseCount)
        leases.forEach(cache::releaseLease)
        assertEquals(0, cache.report(ResourceSelector.ByKey(key)).entries.single().leaseCount)
    }

    @Test
    fun reportAccountsRawAndDecodedBytesWithNoGpuAllocation() {
        val cache = ResidentCache()
        cache.install(key, storedA, decodedOf(64))
        val entry = cache.report(ResourceSelector.ByKey(key)).entries.single()
        assertEquals(storedA.bytes.size.toLong(), entry.usage.rawBytes)
        assertEquals(64L, entry.usage.decodedCpuBytes)
        assertEquals(0L, entry.usage.knownGpuBytes)
        assertFalse(entry.usage.hasUnknownGpuBytes)
    }

    @Test
    fun concurrentLeaseAndFreeLinearizeAtTheStateBoundary() = runTest {
        val cache = ResidentCache()
        val generation = cache.install(key, storedA, null)
        // Free racing the last lease release must report one or the other, never both and never
        // neither: deferred if free wins, fully freed if the release wins.
        val lease = cache.takeLease(generation)
        val results = listOf(
            async { cache.free(ResourceSelector.ByKey(key)) },
            async { cache.releaseLease(lease); null },
        ).awaitAll()
        val free = results.filterIsInstance<ResourceFreeResult>().single()
        assertEquals(1, free.deferredKeys + free.fullyFreedKeys)
        assertEquals(0, cache.report(ResourceSelector.ByKey(key)).entries.single().leaseCount)
    }

    @Test
    fun selectorsMatchAllByKindByClassAndByKey() {
        val cache = ResidentCache()
        cache.install(externalStickerKey, storedA, null)
        cache.install(externalModelKey, storedB, null)
        assertEquals(2, cache.report(ResourceSelector.All).entries.size)
        assertEquals(2, cache.report(ResourceSelector.ByKind(ResourceKind.EXTERNAL)).entries.size)
        assertEquals(1, cache.report(ResourceSelector.ByClass(ResourceClass.STICKER_IMAGE)).entries.size)
        assertEquals(1, cache.report(ResourceSelector.ByKey(externalStickerKey)).entries.size)
    }
}
```

- [ ] **Step 2: Run to verify it fails, then implement**

Run: `./gradlew --no-configuration-cache :kmp:testAndroidHostTest --tests "com.rohittp.reng.internal.cache.ResidentCacheTest"`

The cache carries the renderer mutex, because it is the state that mutex exists to guard. Hold it for
state transitions only and **never** across an adapter call, a decode, or a parse — an adapter is consumer
code of unbounded duration. `CONTEXT.md` fixes the linearization points: drawing may overlap GL-free
preparation, and resource query, free, and Prepared Frame close linearize at renderer state boundaries.

One entry per `ResourceKey` holding a list of generations and a `freed` flag. Exactly one generation is `current`. Raw bytes are **retained**, never dropped after decode, because Cycle B's `NORMAL` rules use a stale resident as a `304` baseline and `ObserveResident` is typed to answer with a `StoredRawResource`. A superseded generation with no lease is dropped immediately. Free retires all generations, sets `freed`, deletes the unleased ones, and reports the rest deferred; a key with no live generation and no retired generation counts as already free. Free and report share one snapshot boundary, which is what makes the documented race — deferred if free wins, fully freed if the last release wins — well defined. There is no automatic eviction.

- [ ] **Step 3: Run to verify it passes, then commit**

```bash
git add kmp/src/commonMain/kotlin/com/rohittp/reng/internal/cache/ kmp/src/commonTest/kotlin/com/rohittp/reng/internal/cache/
git commit -m "feat(kmp): add the resident cache with generations, leases, and reload markers"
```

---

### Task 11: The scale benchmark, before any driver optimisation

**Files:**
- Create: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/resource/ResourceOperationScaleBenchmarkTest.kt`
- Modify: `HANDOFF.md`

**Interfaces:**
- Consumes: `ResourceOperationStateMachine` and its protocol, unchanged.
- Produces: a reproducible measurement of scheduler cost against route and occurrence count, recorded in the repository.

**This task must land before any other task touches the driver, and before any scheduler optimisation.** `HANDOFF.md` records a measured Θ(events × (routes + occurrences)) cost with roughly nine events per route, an O(owners × occurrences) style-owner barrier, and per-event full-payload hashing that extrapolates to seconds of pure CPU at the shipped default tile budget. Fixing that without a benchmark that fails first is how a scheduling regression ships. The cost lives entirely in the pure reducer, so the benchmark needs no adapter and no network.

- [ ] **Step 1: Write the benchmark as a test**

```kotlin
class ResourceOperationScaleBenchmarkTest {
    @Test
    fun drivesManyDistinctRoutesToCompletionAndReportsCost() {
        // Existing scheduling tests register many routes but never drive one past StartRoute,
        // and the lookup test drives 4096 occurrences joined onto ONE route. Neither pays the
        // per-route event multiplier, which is why the cost is unexercised rather than absent.
        for (routeCount in listOf(64, 128, 256, 512)) {
            val elapsed = driveDistinctRoutesToCompletion(routeCount)
            println("routes=$routeCount elapsedMillis=$elapsed")
        }
        // Guard, not a target: HANDOFF.md's own extrapolation already puts the current, unfixed
        // cost at roughly five seconds of pure CPU for this exact 512-tile scenario, so a ceiling
        // has to sit at that figure rather than well above it — a looser ceiling (e.g. 20 seconds)
        // would not fail even for a substantially regressed implementation, only a catastrophic one.
        assertTrue(driveDistinctRoutesToCompletion(512) < 5_000L)
    }

    private fun driveDistinctRoutesToCompletion(routeCount: Int): Long {
        val definition = definitionOfDistinctStickerRoutes(routeCount)
        val started = TimeSource.Monotonic.markNow()
        var transition = ResourceOperationStateMachine.start(definition)
        val pending = ArrayDeque<ResourceOperationAction>()
        pending.addAll(transition.actions)
        while (transition.outcome == null) {
            val action = pending.removeFirstOrNull() ?: break
            // transition() is a two-argument function on the ResourceOperationStateMachine object,
            // not a method reachable through the previous transition's result.
            val runningState = requireNotNull(transition.state) { "no outcome yet but state is null" }
            transition = ResourceOperationStateMachine.transition(runningState, eventFor(action))
            pending.addAll(transition.actions)
        }
        return started.elapsedNow().inWholeMilliseconds
    }

    /** One supplied outcome per action, always the success path, so the measurement is of
     *  scheduling cost alone rather than of failure arbitration. */
    private fun eventFor(action: ResourceOperationAction): ResourceOperationEvent = when (action) {
        is SampleClock -> ClockSampled(action.actionId, sampleEpochMillis = 1_700_000_000_000L)
        is ObserveResident -> ResidentObserved(action.actionId, resource = null)
        is ReadStore -> StoreReadCompleted(action.actionId, SuppliedCallOutcome.Success(null))
        is CallTransport -> TransportCompleted(action.actionId, SuppliedCallOutcome.Success(okResponse()))
        is ValidateResourceClass -> ResourceClassValidationCompleted(action.actionId, SuppliedValidationOutcome.Valid)
        is WriteStore -> StoreWriteCompleted(action.actionId, SuppliedCallOutcome.Success(Unit))
        // SuppliedInstallOutcome's success case is `Succeeded`, a data object with no payload.
        is InstallVisibility -> VisibilityInstallCompleted(action.actionId, SuppliedInstallOutcome.Succeeded)
        else -> error("the sticker-only definition emits no other action: $action")
    }
}
```

- [ ] **Step 2: Run it and record the numbers**

Run: `./gradlew --no-configuration-cache --rerun-tasks :kmp:macosArm64Test --tests "com.rohittp.reng.internal.resource.ResourceOperationScaleBenchmarkTest"`

Record the four measurements. Compare the growth between 256 and 512 routes against linear: a ratio near 2 is linear, near 4 is quadratic.

- [ ] **Step 3: Replace HANDOFF.md's item 3 with the measurement**

Rewrite the deferred-item paragraph with the observed numbers rather than the extrapolation, and state plainly whether the cost is acceptable at the default 512-tile budget. If it is not, the optimisation now has a failing measurement to justify it and to prove it worked — and only then is it in scope.

- [ ] **Step 4: Commit**

```bash
git add kmp/src/commonTest/kotlin/com/rohittp/reng/internal/resource/ResourceOperationScaleBenchmarkTest.kt HANDOFF.md
git commit -m "test(kmp): measure resource scheduler cost against distinct route count"
```

---

### Task 12: The driver — clock, resident, Store read, Transport, and latch replay

**Files:**
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/driver/ResourceActionExecutor.kt`
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/driver/PreparationDriver.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/driver/PreparationDriverTest.kt`

**Interfaces:**
- Consumes: `ResourceOperationStateMachine`, `ResidentCache`, `Transport`, `Store`.
- Produces:
  ```kotlin
  internal class PreparationDriver(
      private val transport: Transport,
      private val store: Store,
      private val cache: ResidentCache,
      private val maximumConcurrentOperations: Int,
      private val clock: () -> Long,
  ) {
      suspend fun run(definition: ResourceOperationDefinition): ResourceOperationOutcome
  }
  ```

`ResourceOperationAction` is a sealed interface with 18 subtypes, and `ResourceActionExecutor.kt`'s dispatch is one exhaustive `when (action)` over all of them. This task's commit handles `SampleClock`, `ObserveResident`, `ReadStore`, `CallTransport`, `ReplayLatchedTransport`, and `StartRoute` — the last is how a route begins at all, so every test below already depends on it working. Every action no task has reached yet falls through `else -> error("ResourceActionExecutor does not yet handle $action")`, so this task's own commit compiles and its own tests pass standing alone. Tasks 13 and 14 each replace one or more `else` branches with the class-gate, write, visibility, sprite, and style actions; Task 14 also replaces the `DiscoverChildren` branch; Task 15 replaces the `CancelRoute` branch. No `else` branch survives once every action is covered.

- [ ] **Step 1: Write the failing tests**

```kotlin
class PreparationDriverTest {
    @Test
    fun performsExactlyOneConsumerExchangePerStructuralIdentity() = runTest {
        val transport = CountingTransport(); val store = CountingStore()
        driver(transport, store).run(twoOccurrencesOfOneRoute())
        assertEquals(1, transport.executeCalls)
        assertEquals(1, store.readCalls)
        assertEquals(1, store.writeCalls)
    }

    @Test
    fun neverExceedsTheConfiguredConcurrency() = runTest {
        val transport = ConcurrencyRecordingTransport()
        driver(transport, CountingStore(), maximumConcurrentOperations = 4).run(sixteenDistinctRoutes())
        assertTrue(transport.maximumObservedConcurrency <= 4)
    }

    @Test
    fun replaysALatchedOutcomeWithoutASecondExchange() = runTest {
        val transport = CountingTransport()
        driver(transport, CountingStore()).run(routeJoinedByTwoOwners())
        assertEquals(1, transport.executeCalls)
    }

    @Test
    fun sanitizesAnAdapterThrowableIntoATypedFailure() = runTest {
        val transport = ThrowingTransport(IllegalStateException("signed-url-SECRET"))
        val outcome = driver(transport, CountingStore()).run(oneStickerRoute())
        val failure = assertIs<ResourceOperationOutcome.Failure>(outcome)
        assertEquals(RenGErrorCode.TRANSPORT_EXECUTION_FAILED, failure.failure.code)
        assertEquals(PipelineStage.TRANSPORT, failure.failure.stage)
        assertFalse(failure.toString().contains("SECRET"))
    }

    @Test
    fun performsNoRetryRepairOrFallbackOnAnyStatus() = runTest {
        for (status in listOf(301, 404, 429, 500, 503)) {
            val transport = CountingTransport(status = status)
            val outcome = driver(transport, CountingStore()).run(oneStickerRoute())
            assertIs<ResourceOperationOutcome.Failure>(outcome)
            assertEquals(1, transport.executeCalls, "status $status must not be retried")
        }
    }

    @Test
    fun samplesTheClockExactlyOncePerOperation() = runTest {
        val clock = CountingClock()
        driver(CountingTransport(), CountingStore(), clock = clock).run(twoOccurrencesOfOneRoute())
        assertEquals(1, clock.samples)
    }
}
```

- [ ] **Step 2: Run to verify it fails, then implement**

Run: `./gradlew --no-configuration-cache :kmp:testAndroidHostTest --tests "com.rohittp.reng.internal.driver.PreparationDriverTest"`

`run` opens one `coroutineScope`, so structured concurrency binds every child to the caller's job and no child outlives the invocation. A `Semaphore(maximumConcurrentOperations)` bounds concurrent actions. The loop: ask the state machine for actions, launch each inside a permit, await outcomes, feed each back as exactly one event, repeat until the machine yields an outcome.

`StartRoute(ordinal, registration)` is the one action in this task that performs no adapter call and feeds back no event through `transition`: the driver executes it by calling `ResourceOperationStateMachine.beginLookup(state, action.ordinal)` directly — `beginLookup`'s own `(state, ordinal)` shape matches `StartRoute`'s `ordinal` field exactly — and folds the resulting transition's actions and outcome into the same loop as every other action.

Select no dispatcher — work runs on the caller's context, because RenG owns no thread pool. Map a non-cancellation `Throwable` from `Store.read` to `STORE_READ_FAILED / STORE_READ`, from `Store.write` to `STORE_WRITE_FAILED / STORE_WRITE`, and from `Transport.execute` to `TRANSPORT_EXECUTION_FAILED / TRANSPORT`, discarding message and cause. Write **no code path** for a retry, repair, redirect, status fallback, or byte range: the state machine emits no action for any of them, so a code path for one is unreachable by construction and must not exist.

- [ ] **Step 3: Run to verify it passes, then commit**

```bash
git add kmp/src/commonMain/kotlin/com/rohittp/reng/internal/driver/ kmp/src/commonTest/kotlin/com/rohittp/reng/internal/driver/
git commit -m "feat(kmp): drive lookup actions against real transport and store adapters"
```

---

### Task 13: The driver — class gates, Store writes, and visibility installs

**Files:**
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/driver/ResourceActionExecutor.kt`
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/driver/ClassGateRunner.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/driver/ClassGateRunnerTest.kt`

**Interfaces:**
- Consumes: `decodePng`, `scanGlb`, `parseGltf`, `validateGltfFeatures`, `ResidentCache`.
- Produces, all in `ClassGateRunner.kt`, which is their sole definer — Task 20 consumes them rather than redefining them:
  ```kotlin
  internal enum class TerrainEncoding { MAPBOX, TERRARIUM }
  internal fun encodingOf(samples: DecodedImage): TerrainEncoding?
  internal fun validateTerrain(samples: DecodedImage): SuppliedValidationOutcome
  internal fun interface ClassGateRunner {
      suspend fun run(gate: ResourceClassGate, content: ResolvedResourceContent): SuppliedValidationOutcome
  }
  internal class RenGClassGateRunner(private val limits: ResourceLimits) : ClassGateRunner
  ```

- [ ] **Step 1: Write the failing tests**

```kotlin
class ClassGateRunnerTest {
    @Test
    fun runsRenGsOwnGatesForItsOwnThreeClasses() = runTest {
        assertEquals(SuppliedValidationOutcome.Valid, run(ResourceClassGate.DECODE_PNG, stickerContent(validPng)))
        assertEquals(SuppliedValidationOutcome.Valid, run(ResourceClassGate.PARSE_GLB, modelContent(validGlb)))
        assertEquals(SuppliedValidationOutcome.Valid, run(ResourceClassGate.VALIDATE_GLB_FEATURES, modelContent(validGlb)))
    }

    @Test
    fun separatesMalformedFromUnsupportedForFreshContent() = runTest {
        assertIs<SuppliedValidationOutcome.Failed>(run(ResourceClassGate.DECODE_PNG, stickerContent(corruptPng)))
        assertIs<SuppliedValidationOutcome.Failed>(run(ResourceClassGate.DECODE_PNG, stickerContent(interlacedPng)))
        // The distinction is in the code the failure carries, which Cycle B maps:
        // decode gates to RESOURCE_DECODE_FAILED, feature gates to UNSUPPORTED_RESOURCE_FEATURE.
    }

    @Test
    fun enforcesTheDecodedCeilingFromHeaderDimensions() = runTest {
        val runner = RenGClassGateRunner(ResourceLimits(maximumDecodedImageBytes = 1024L))
        assertIs<SuppliedValidationOutcome.Failed>(runner.run(ResourceClassGate.DECODE_PNG, stickerContent(large4096Png)))
    }

    @Test
    fun validatesDemTerrainEncodingOnDecodedSamples() = runTest {
        // Rentile validates DEM only as a generic image, so terrain encoding is RenG's gate.
        assertEquals(SuppliedValidationOutcome.Valid, run(ResourceClassGate.VALIDATE_DEM_TERRAIN_ENCODING, demContent(mapboxDem)))
        assertEquals(SuppliedValidationOutcome.Valid, run(ResourceClassGate.VALIDATE_DEM_TERRAIN_ENCODING, demContent(terrariumDem)))
        assertIs<SuppliedValidationOutcome.Failed>(run(ResourceClassGate.VALIDATE_DEM_TERRAIN_ENCODING, demContent(fourChannelDem)))
    }

    @Test
    fun aFailedGateOnStoredContentReportsStoreIntegrityWhicheverGateFailed() = runTest {
        // Cycle B's rule: STORE provenance collapses every gate failure into one code, because
        // both a corrupt and an unsupported stored record mean the record cannot be trusted.
        val outcome = run(ResourceClassGate.DECODE_PNG, storedProvenanceContent(corruptPng))
        assertIs<SuppliedValidationOutcome.Failed>(outcome)
    }
}
```

- [ ] **Step 2: Run to verify it fails, then implement**

Run: `./gradlew --no-configuration-cache :kmp:testAndroidHostTest --tests "com.rohittp.reng.internal.driver.ClassGateRunnerTest"`

`DECODE_PNG` calls `decodePng` with the class's decoded ceiling. `PARSE_GLB` calls `scanGlb` then `parseGltf`; `VALIDATE_GLB_FEATURES` calls `validateGltfFeatures`. `VALIDATE_DEM_TERRAIN_ENCODING` runs on decoded samples and admits exactly the two eight-bit RGB terrain encodings, rejecting anything else — no RenG source names them today, so this task defines `TerrainEncoding`, `encodingOf`, and `validateTerrain` in `ClassGateRunner.kt` as this task's own internal enum and functions. Task 13 is their sole definer; Task 20's terrain acquisition consumes them rather than declaring its own copy.

For the six engine-validated classes the runner does **not** decode or parse. It reports the outcome the firewall observed, supplied by Task 18. Wire that as a constructor parameter so the runner stays testable in isolation.

Then extend the executor to perform `WriteStore` through `Store.write` and `InstallVisibility` by installing the generation into the cache and taking the owner's lease.

- [ ] **Step 3: Run, then commit**

```bash
git add kmp/src/commonMain/kotlin/com/rohittp/reng/internal/driver/ kmp/src/commonTest/kotlin/com/rohittp/reng/internal/driver/
git commit -m "feat(kmp): run class gates and perform writes and visibility installs"
```

---

### Task 14: The driver — sprite pair and basemap style commits

**Files:**
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/driver/ResourceActionExecutor.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/driver/CommitActionsTest.kt`

**Interfaces:**
- Consumes: the sprite and style actions from `ResourceOperationProtocol`.
- Produces: execution for `ValidateSpritePair`, `WriteSpriteMember`, `InstallSpriteVisibility`, `ValidateBasemapStyle`, `CompileBasemapStyle`, `WriteBasemapStyle`, `InstallBasemapStyleVisibility`, and `DiscoverChildren` — this task replaces Task 12's `else -> error(...)` branch for it.

- [ ] **Step 1: Write the failing tests**

```kotlin
class CommitActionsTest {
    @Test
    fun validatesTheSpritePairJointlyBeforeEitherMemberIsWritten() = runTest {
        val store = CountingStore()
        val outcome = driver(store = store).run(spriteGroupWhereImageIsCorrupt())
        assertIs<ResourceOperationOutcome.Failure>(outcome)
        assertEquals(0, store.writeCalls, "no member may be written when joint validation fails")
    }

    @Test
    fun writesSpriteMembersJsonBeforeImageRegardlessOfCompletionOrder() = runTest {
        val store = OrderRecordingStore()
        driver(store = store).run(spriteGroupWhereImageCompletesFirst())
        assertEquals(
            listOf(ResourceClass.BASEMAP_SPRITE_JSON, ResourceClass.BASEMAP_SPRITE_IMAGE),
            store.writtenClasses,
        )
    }

    @Test
    fun stagesStyleBytesPrivatelyAndWritesOnlyAfterCompilationAndAllOwnerWork() = runTest {
        val store = CountingStore()
        val outcome = driver(store = store).run(styleWhoseChildFails())
        assertIs<ResourceOperationOutcome.Failure>(outcome)
        assertEquals(0, store.writeCalls, "staged style bytes must not reach the consumer store")
    }

    @Test
    fun neverRewritesResidentOrStoreSourcedContent() = runTest {
        val store = CountingStore()
        driver(store = store).run(routeSatisfiedFromStore())
        assertEquals(0, store.writeCalls)
    }

    @Test
    fun discoversAStylesChildrenAfterItsOwnRouteCompletes() = runTest {
        // DiscoverChildren is how a completed style or sprite route's structural children
        // (e.g. the style's referenced sources and sprite) are announced back to the machine.
        val outcome = driver().run(styleWithTwoDiscoverableChildren())
        assertIs<ResourceOperationOutcome.Success>(outcome)
    }
}
```

- [ ] **Step 2: Run to verify it fails, then implement**

Run: `./gradlew --no-configuration-cache :kmp:testAndroidHostTest --tests "com.rohittp.reng.internal.driver.CommitActionsTest"`

`ValidateSpritePair` decodes the image with the PNG decoder and parses the JSON with the JSON reader, jointly, before either member is written. `WriteSpriteMember` writes in the machine's supplied member order. `ValidateBasemapStyle` parses the style document and reports its discovered children. `CompileBasemapStyle` hands the staged bytes to the engine. `WriteBasemapStyle` performs the one consumer write, and only when the machine asks for it — never for resident or Store-sourced content.

`DiscoverChildren(ordinal, parentOccurrenceId)` performs no adapter call: it walks the already-resolved parent content structurally (the parsed style document's referenced sources, or a sprite group's members) and feeds back `ChildrenDiscovered(action.parentOccurrenceId, children)`. This task replaces Task 12's `else -> error(...)` branch for it, alongside the sprite and style branches above.

- [ ] **Step 3: Run, then commit**

```bash
git add kmp/src/commonMain/kotlin/com/rohittp/reng/internal/driver/ kmp/src/commonTest/kotlin/com/rohittp/reng/internal/driver/CommitActionsTest.kt
git commit -m "feat(kmp): execute sprite pair and basemap style commit actions"
```

---

### Task 15: Cancellation through the driver

**Files:**
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/driver/PreparationDriver.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/driver/DriverCancellationTest.kt`

**Interfaces:**
- Consumes: `CancellationSelection`, `ResourceOperationOutcome.Cancelled`, `ResidentCache` — the fourth test below instantiates and queries it directly.
- Produces: `PreparationDriver.cancel()`, unwrapped cancellation propagation, and execution of `CancelRoute` — this task replaces Task 12's `else -> error(...)` branch for it.

- [ ] **Step 1: Write the failing tests**

```kotlin
class DriverCancellationTest {
    @Test
    fun callerCancellationPropagatesUnwrappedAndStopsFurtherAdapterCalls() = runTest {
        val transport = CountingTransport(delayMillis = 1_000)
        val job = launch { driver(transport).run(manyRoutes()) }
        advanceTimeBy(10); job.cancel(); job.join()
        val before = transport.executeCalls
        advanceTimeBy(5_000)
        assertEquals(before, transport.executeCalls, "no adapter call may start after cancellation")
    }

    @Test
    fun anAdapterCancellationIsNeverTranslatedIntoARenGFailure() = runTest {
        val transport = ThrowingTransport(CancellationException("adapter cancelled"))
        val outcome = driver(transport).run(oneStickerRoute())
        assertIs<ResourceOperationOutcome.Cancelled>(outcome)
    }

    @Test
    fun aClosedRasterizerCancellationIsCancellationNotAFailure() = runTest {
        // The engine surfaces close() to in-flight work as a plain CancellationException and only
        // to later calls as a typed closed failure. The first must not become a RenG failure.
        val outcome = driver(engine = ClosingEngine()).run(oneBasemapRoute())
        assertIs<ResourceOperationOutcome.Cancelled>(outcome)
    }

    @Test
    fun contentAcquiredBeforeCancellationMayRemainResident() = runTest {
        val cache = ResidentCache()
        val job = launch { driver(cache = cache).run(twoRoutesWhereFirstCompletes()) }
        advanceTimeBy(50); job.cancel(); job.join()
        // CONTEXT.md permits this explicitly: cancellation exposes no partial history, but valid
        // acquired content may remain cached.
        assertNotNull(cache.current(firstRouteKey))
    }
}
```

- [ ] **Step 2: Run to verify it fails, then implement**

Run: `./gradlew --no-configuration-cache :kmp:testAndroidHostTest --tests "com.rohittp.reng.internal.driver.DriverCancellationTest"`

Because `run` uses `coroutineScope`, caller cancellation already reaches every child. Feed an adapter's `CancellationException` back as `SuppliedCallOutcome.Cancelled` with its opaque selection identifier; never construct a `RenGException` from it. Cycle B's arbitration decides precedence — supply the observation, do not re-decide it.

`CancelRoute(ordinal)` performs no adapter call either: it cancels that specific route's own in-flight child coroutine, then feeds back `CleanupCancellationObserved(action.ordinal)` once that cancellation is observed. This task replaces Task 12's `else -> error(...)` branch for it.

- [ ] **Step 3: Run, then commit**

```bash
git add kmp/src/commonMain/kotlin/com/rohittp/reng/internal/driver/PreparationDriver.kt kmp/src/commonTest/kotlin/com/rohittp/reng/internal/driver/DriverCancellationTest.kt
git commit -m "feat(kmp): propagate cancellation unwrapped through the resource driver"
```

---

### Task 16: The production private-key resolver

**Files:**
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/firewall/RentileKeyDerivation.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/firewall/RentileKeyDerivationTest.kt`

**Interfaces:**
- Consumes: `Sha256Function`, `RentilePrivateKey`, `RentilePrivateKeyResolver`.
- Produces: `internal class ProductionRentilePrivateKeyResolver(sha256: Sha256Function) : RentilePrivateKeyResolver`, and `internal fun redactAuthenticationQuery(url: String): String` — the test below calls it directly.

**This derivation's failure mode is silent.** If RenG computes a digest the engine does not ask for, the result is a permanent cache miss rather than an error.

- [ ] **Step 1: Write the failing tests**

```kotlin
class RentileKeyDerivationTest {
    @Test
    fun reproducesTheEngineDerivationForTheSevenClassesItKeys() {
        // Measured against the published artifact: sha256Hex(withRedactedAuthenticationQuery(url)),
        // no class prefix, no coordinate suffix, over the final requested URL.
        val locator = ResourceLocator("https://tiles.example/0/0/0.pbf?access_token=SECRET&x=1")
        val token = resolver.resolve(locator, ResourceClass.BASEMAP_VECTOR_TILE)
        assertEquals(expectedTokenFor("https://tiles.example/0/0/0.pbf?access_token=<redacted>&x=1", "VECTOR_TILE"), token)
    }

    @Test
    fun redactsOnlyTheEightAuthenticationParameterValues() {
        for (name in listOf("access_token", "apikey", "api_key", "key", "mtsid", "session", "session_id", "token")) {
            val redacted = redactAuthenticationQuery("https://h/p?$name=S&keep=1")
            assertEquals("https://h/p?$name=<redacted>&keep=1", redacted)
        }
        assertEquals("https://h/p?other=S", redactAuthenticationQuery("https://h/p?other=S"))
        assertEquals("https://h/p", redactAuthenticationQuery("https://h/p"))
        assertEquals("https://h/p?TOKEN=<redacted>", redactAuthenticationQuery("https://h/p?TOKEN=S"))
        assertEquals("https://h/p?a=1#frag", redactAuthenticationQuery("https://h/p?a=1#frag"))
    }

    @Test
    fun usesRenGsOwnIdentityForTheFourClassesTheEngineNeverKeys() {
        // Two stickers differing only in an auth token are distinct RenG resources and must not
        // collapse to one private key, or the whole preparation fails AMBIGUOUS_RESOURCE_ROUTE.
        val first = resolver.resolve(ResourceLocator("https://cdn/a.png?token=T1"), ResourceClass.STICKER_IMAGE)
        val second = resolver.resolve(ResourceLocator("https://cdn/a.png?token=T2"), ResourceClass.STICKER_IMAGE)
        assertNotEquals(first, second)
        for (klass in listOf(ResourceClass.BASEMAP_STYLE, ResourceClass.MODEL_GLB, ResourceClass.MODEL_TEXTURE)) {
            val a = resolver.resolve(ResourceLocator("https://cdn/x?key=A"), klass)
            val b = resolver.resolve(ResourceLocator("https://cdn/x?key=B"), klass)
            assertNotEquals(a, b, "$klass must not collapse on an authentication value")
        }
    }

    @Test
    fun sameLocatorAndClassAlwaysYieldsTheSameToken() {
        val locator = ResourceLocator("https://tiles.example/1/2/3.png")
        assertEquals(
            resolver.resolve(locator, ResourceClass.BASEMAP_RASTER_TILE),
            resolver.resolve(locator, ResourceClass.BASEMAP_RASTER_TILE),
        )
    }

    @Test
    fun theTokenIsRedactedInItsTextualRepresentation() {
        val token = resolver.resolve(ResourceLocator("https://cdn/a.png?token=SECRET"), ResourceClass.STICKER_IMAGE)
        assertFalse(token.toString().contains("SECRET"))
        assertFalse(token.toString().contains("cdn"))
    }
}
```

- [ ] **Step 2: Run to verify it fails, then implement**

Run: `./gradlew --no-configuration-cache :kmp:testAndroidHostTest --tests "com.rohittp.reng.internal.firewall.RentileKeyDerivationTest"`

The redaction rewrites any query parameter whose **lowercased** name is one of `access_token`, `apikey`, `api_key`, `key`, `mtsid`, `session`, `session_id`, `token` to `<name>=<redacted>` and rejoins with `&`, preserving the fragment. For the seven engine-keyed classes the token is the mapped engine class plus `sha256Hex(redactedUrl)`. For `BASEMAP_STYLE`, `STICKER_IMAGE`, `MODEL_GLB`, and `MODEL_TEXTURE` the token derives from RenG's own canonical resource identity, which is injective in locator and class.

- [ ] **Step 3: Run, then commit**

```bash
git add kmp/src/commonMain/kotlin/com/rohittp/reng/internal/firewall/ kmp/src/commonTest/kotlin/com/rohittp/reng/internal/firewall/
git commit -m "feat(kmp): derive private engine keys by whether the engine keys the class"
```

---

### Task 17: The firewall adapters

**Files:**
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/firewall/FirewallTransport.kt`
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/firewall/FirewallStore.kt`
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/firewall/OperationRegistry.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/firewall/FirewallTest.kt`

**Interfaces:**
- Consumes: `ProductionRentilePrivateKeyResolver`, the driver's latch state.
- Produces: implementations of the engine's `ResourceTransport` and `RawResourceStore` that multiplex through the one active preparation invocation.

- [ ] **Step 1: Write the failing tests**

```kotlin
class FirewallTest {
    @Test
    fun answersARepeatedEngineReadFromTheJoinedRouteSample() = runTest {
        val store = CountingStore()
        val firewall = firewall(store)
        repeat(64) { firewall.store.read(engineKeyFor(rasterRoute)) }
        assertEquals(1, store.readCalls, "engine reads must not become consumer reads")
    }

    @Test
    fun replaysALatchedOutcomeForTheEnginesSecondAttempt() = runTest {
        val transport = CountingTransport()
        val firewall = firewall(transport = transport)
        firewall.transport.execute(engineRequestFor(rasterRoute))
        firewall.transport.execute(engineRequestFor(rasterRoute))
        assertEquals(1, transport.executeCalls, "the engine's extra attempt is not a consumer retry")
    }

    @Test
    fun absorbsRemoveWithoutConsumerMutationOrFollowOnWork() = runTest {
        val store = CountingStore()
        firewall(store).store.remove(engineKeyFor(rasterRoute))
        assertEquals(0, store.readCalls + store.writeCalls)
        // RenG's Store has no remove at all; the call is private and terminal.
    }

    @Test
    fun acceptsANonNullAcceptOnASpriteRouteWithoutTreatingItAsAMismatch() = runTest {
        // Measured: the engine sends application/json on sprite JSON and image/png on sprite image,
        // and null on the other six classes.
        for ((klass, accept) in listOf(
            ResourceClass.BASEMAP_SPRITE_JSON to "application/json",
            ResourceClass.BASEMAP_SPRITE_IMAGE to "image/png",
        )) {
            val response = firewall().transport.execute(engineRequestFor(spriteRoute(klass), accept = accept))
            assertEquals(200, response.statusCode)
        }
    }

    @Test
    fun refusesToForwardAnUnrecognisedUrl() = runTest {
        val transport = CountingTransport()
        assertFailsWith<Exception> { firewall(transport = transport).transport.execute(unplannedRequest()) }
        assertEquals(0, transport.executeCalls, "an unplanned exchange must never reach the consumer")
    }

    @Test
    fun trustsRenGsRouteLimitRatherThanTheEnginesNumber() = runTest {
        val response = firewall().transport.execute(engineRequestFor(rasterRoute, maxResponseBytes = Long.MAX_VALUE))
        assertEquals(200, response.statusCode)
        // The route's own ceiling comes from ResourceLimits and is part of the route key.
    }

    @Test
    fun passesTheDocumentedNullsIncludingRetryAfterDeliberately() = runTest {
        val response = firewall().transport.execute(engineRequestFor(rasterRoute))
        assertNull(response.metadata.retryAfterMillis)
        assertNull(response.metadata.cacheControl)
        assertNull(response.metadata.redirectLocation)
        assertNull(response.metadata.wireByteCount)
        assertEquals(emptyList(), response.metadata.vary)
    }

    @Test
    fun fullyValidatesASpriteRecordBeforeAnsweringAnEngineRead() = runTest {
        // The engine's sprite acquirer validates only size and digest on a store hit and never
        // parses, so a record it accepts but cannot use is permanently unrecoverable inside it.
        val poisoned = storedRecordWithConsistentDigestButInvalidPng()
        assertNull(firewall(storeReturning(poisoned)).store.read(engineKeyFor(spriteImageRoute)))
    }

    @Test
    fun neverLetsAnEngineKeyReachADiagnostic() = runTest {
        val sink = RecordingDiagnosticSink()
        firewall(sink = sink).store.read(engineKeyFor(rasterRoute))
        assertTrue(sink.diagnostics.none { it.toString().contains(engineKeyFor(rasterRoute).stableId) })
    }
}
```

- [ ] **Step 2: Run to verify it fails, then implement**

Run: `./gradlew --no-configuration-cache :kmp:testAndroidHostTest --tests "com.rohittp.reng.internal.firewall.FirewallTest"`

The registry maps an engine request URL and an engine `RawResourceKey` onto a preregistered route via the private-key token. An unrecognised URL is not forwarded. The route's byte ceiling comes from `ResourceLimits`, never from the engine's number. Response metadata passes `contentType`, `etag`, `lastModified`, and `freshUntilEpochMillis` through as `expiresAtEpochMillis`, and passes `cacheControl`, `redirectLocation`, `wireByteCount`, and `retryAfterMillis` as `null` with empty `vary`. `retryAfterMillis` is null **deliberately** — it is the only input to the engine's retry delay, and the firewall replays a latched outcome instead.

`remove` performs no consumer removal, no repair, and no follow-on exchange. Sprite records are **fully validated** — JSON parsed, PNG decoded — before being handed to the engine, because a poisoned sprite record is terminal inside it.

- [ ] **Step 3: Run, then commit**

```bash
git add kmp/src/commonMain/kotlin/com/rohittp/reng/internal/firewall/ kmp/src/commonTest/kotlin/com/rohittp/reng/internal/firewall/FirewallTest.kt
git commit -m "feat(kmp): contain the basemap engine behind operation-scoped adapters"
```

---

### Task 18: Engine failure classification and diagnostics

**Files:**
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/firewall/EngineFailureClassification.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/firewall/EngineFailureClassificationTest.kt`

**Interfaces:**
- Produces: `internal fun classifyEngineFailure(failure: Throwable): FailureDescriptor`.

- [ ] **Step 1: Write the failing tests**

```kotlin
class EngineFailureClassificationTest {
    @Test
    fun mapsEveryEngineCodeOntoRenGsClosedVocabulary() {
        assertEquals(RenGErrorCode.RESOURCE_PARSE_FAILED, classify(stylePreparationFailure(parse = true)).code)
        assertEquals(RenGErrorCode.UNSUPPORTED_RESOURCE_FEATURE, classify(stylePreparationFailure(parse = false)).code)
        assertEquals(RenGErrorCode.RESOURCE_DECODE_FAILED, classify(resourceDecodeFailure()).code)
        assertEquals(RenGErrorCode.RESOURCE_LIMIT_EXCEEDED, classify(safetyLimitFailure()).code)
        assertEquals(RenGErrorCode.STORE_WRITE_FAILED, classify(storeFailure(writing = true)).code)
        assertEquals(RenGErrorCode.BASEMAP_RENDER_FAILED, classify(rasterizationFailure()).code)
        assertEquals(PipelineStage.BASEMAP_RENDER, classify(rasterizationFailure()).stage)
        assertEquals(RenGErrorCode.BASEMAP_RENDER_FAILED, classify(pngEncodingFailure()).code)
        // A wrapped batch failure is unwrapped to its primary and then classified.
        assertEquals(RenGErrorCode.RESOURCE_DECODE_FAILED, classify(batchRenderFailure(primary = resourceDecodeFailure())).code)
        // These can only fire if RenG mismanaged a handle it owns; still fail closed.
        for (failure in listOf(rasterizerClosedFailure(), foreignPreparedStyleFailure(), invalidTileIdFailure())) {
            assertEquals(RenGErrorCode.BASEMAP_RENDER_FAILED, classify(failure).code)
        }
    }

    @Test
    fun neverForwardsEngineTextOrCause() {
        val descriptor = classify(rasterizationFailureWithMessage("tile https://host/a?token=SECRET failed"))
        assertFalse(descriptor.toString().contains("SECRET"))
        assertFalse(descriptor.toString().contains("host"))
        // FailureDescriptor.diagnostic is a singular nullable Diagnostic?, not a list.
        val diagnostic = descriptor.diagnostic
        assertTrue(diagnostic == null || diagnostic.resourceKey == null || diagnostic.resourceKey!!.stableId.length == 64)
    }

    @Test
    fun rethrowsCancellationRatherThanClassifyingIt() {
        assertFailsWith<CancellationException> { classify(CancellationException("engine closed")) }
    }
}
```

- [ ] **Step 2: Run to verify it fails, then implement**

Run: `./gradlew --no-configuration-cache :kmp:testAndroidHostTest --tests "com.rohittp.reng.internal.firewall.EngineFailureClassificationTest"`

Classify, never wrap: `RenGException` has no `cause` parameter, so forwarding is structurally impossible. Drop the engine's free-form `message` and `details` entirely. Rethrow `CancellationException` before any classification.

- [ ] **Step 3: Run, then commit**

```bash
git add kmp/src/commonMain/kotlin/com/rohittp/reng/internal/firewall/EngineFailureClassification.kt kmp/src/commonTest/kotlin/com/rohittp/reng/internal/firewall/EngineFailureClassificationTest.kt
git commit -m "feat(kmp): classify engine failures into RenG's closed vocabulary"
```

---

### Task 19: Rasterizer lifetime, style compilation, and rendered-tile identity

**Files:**
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/firewall/BasemapEngineHost.kt`
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/identity/CanonicalBinary.kt`
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/identity/ResourceKeyDerivation.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/firewall/BasemapEngineHostTest.kt`

**Interfaces:**
- Consumes: `ProductionRentilePrivateKeyResolver` from Task 16; `FirewallTransport`, `FirewallStore` from Task 17; `classifyEngineFailure` from Task 18; `ResourceKind.BASEMAP_TILE` from Task 2.
- Produces: `internal class BasemapEngineHost` owning one rasterizer per renderer, plus
  `internal fun basemapTileKey(styleDigest: String, tile: TileCoordinate, outputSize: OutputPixelSize): ResourceKey`, plus a `CanonicalRootKind.BASEMAP_TILE` and its dedicated derivation function.

- [ ] **Step 1: Write the failing tests**

```kotlin
class BasemapEngineHostTest {
    @Test
    fun createsOneRasterizerAtSetupWithoutSuspendingOrPerformingIo() {
        val transport = CountingTransport(); val store = CountingStore()
        BasemapEngineHost(configuration(transport, store))
        assertEquals(0, transport.executeCalls + store.readCalls, "setup performs no I/O")
    }

    @Test
    fun compilesThePreparedStyleLazilyAndReusesItWhileResident() = runTest {
        val host = BasemapEngineHost(configuration())
        val first = host.preparedStyle(styleContent)
        val second = host.preparedStyle(styleContent)
        assertSame(first, second)
    }

    @Test
    fun recompilesAfterTheStyleGenerationIsFreed() = runTest {
        val host = BasemapEngineHost(configuration())
        val first = host.preparedStyle(styleContent)
        host.invalidateStyle()
        assertNotSame(first, host.preparedStyle(styleContent))
    }

    @Test
    fun derivesTileIdentityFromRenGsOwnCanonicalRootNotTheEnginesKey() {
        val a = basemapTileKey("digest-a", TileCoordinate(2, 1, 1), OutputPixelSize(512, 512))
        val b = basemapTileKey("digest-b", TileCoordinate(2, 1, 1), OutputPixelSize(512, 512))
        val c = basemapTileKey("digest-a", TileCoordinate(2, 1, 2), OutputPixelSize(512, 512))
        val d = basemapTileKey("digest-a", TileCoordinate(2, 1, 1), OutputPixelSize(256, 256))
        assertEquals(4, setOf(a, b, c, d).size, "style digest, tile, and output size all key a tile")
        assertEquals(ResourceKind.BASEMAP_TILE, a.kind)
        assertNull(a.resourceClass)
    }

    @Test
    fun closeClosesTheRasterizerWithoutRequiringAGlContext() = runTest {
        val host = BasemapEngineHost(configuration())
        host.close()
        // The engine's close is not GL-scoped, so it is independent of the exact-context rule.
        assertTrue(host.isClosed)
    }

    @Test
    fun renderOverAPreparedBatchPerformsNoAdapterCall() = runTest {
        val transport = CountingTransport(); val store = CountingStore()
        val host = BasemapEngineHost(configuration(transport, store))
        val batch = host.prepareBatch(tiles)
        val before = transport.executeCalls + store.readCalls
        host.render(batch)
        assertEquals(before, transport.executeCalls + store.readCalls)
    }
}
```

- [ ] **Step 2: Add the BASEMAP_TILE canonical root and its derivation function**

`CanonicalRootKind` (`internal/identity/CanonicalBinary.kt`) has exactly five entries today — `FRAME`, `EXTERNAL_RESOURCE`, `GEOMETRY_PROGRAM`, `INTERNAL_PIPELINE`, `OFFSCREEN_SURFACE` — one per existing identity, each with its own dedicated derivation function on `ResourceKeyDeriver` in `internal/identity/ResourceKeyDerivation.kt` (`external(...)`, `geometryProgram(...)`). A rendered basemap tile needs a sixth root following that same one-root-per-identity pattern exactly:

```kotlin
internal enum class CanonicalRootKind(internal val wireByte: Int) {
    FRAME(1),
    EXTERNAL_RESOURCE(2),
    GEOMETRY_PROGRAM(3),
    INTERNAL_PIPELINE(4),
    OFFSCREEN_SURFACE(5),
    BASEMAP_TILE(6),
}
```

Add this as a member function of `ResourceKeyDeriver` itself, alongside `external` and `geometryProgram` — `derive` is a `private` member of that class, so a same-file extension cannot reach it, and `ResourceKind.wireValue` (already a `private` top-level property in the same file, extended for `BASEMAP_TILE` by Task 2) is likewise only reachable from inside the file:

```kotlin
internal fun basemapTile(
    styleDigest: String,
    tile: TileCoordinate,
    outputSize: OutputPixelSize,
): DerivedResourceKey {
    val identity = derive(
        CanonicalBinary.root(CanonicalRootKind.BASEMAP_TILE) {
            field(1, CanonicalBinary.u16(ResourceKind.BASEMAP_TILE.wireValue))
            field(2, CanonicalBinary.exactUtf8(styleDigest))
            field(3, CanonicalBinary.u16(tile.zoom))
            field(4, CanonicalBinary.u64(tile.x.toLong()))
            field(5, CanonicalBinary.u64(tile.y.toLong()))
            field(6, CanonicalBinary.u16(outputSize.width))
            field(7, CanonicalBinary.u16(outputSize.height))
        },
    )
    return DerivedResourceKey(
        key = ResourceKey(kind = ResourceKind.BASEMAP_TILE, stableId = identity.digest.lowercaseHex, resourceClass = null),
        rawKey = null,
        identity = identity,
    )
}
```

`basemapTileKey`, this task's own free function in `BasemapEngineHost.kt`, is then `ResourceKeyDeriver().basemapTile(styleDigest, tile, outputSize).key` — never the engine's own `outputRequestKey`.

- [ ] **Step 3: Run to verify it fails, then implement**

Run: `./gradlew --no-configuration-cache :kmp:testAndroidHostTest --tests "com.rohittp.reng.internal.firewall.BasemapEngineHostTest"`

Build one engine configuration with the firewall's fixed adapters, `CredentialProvider.None`, `MapSessionProvider.None`, default execution policy and limits, no metrics sink, and the system clock. Compile the prepared style lazily on first use and bind it to the style's current resident generation.

Derive the rendered-tile key from the ADR 0018 canonical root added in Step 2, containing the prepared style's digest, the tile coordinate, and the output size — never from the engine's own request key, so an engine release that changes its derivation cannot silently invalidate RenG's cache.

- [ ] **Step 4: Run, then commit**

```bash
git add kmp/src/commonMain/kotlin/com/rohittp/reng/internal/firewall/BasemapEngineHost.kt kmp/src/commonMain/kotlin/com/rohittp/reng/internal/identity/CanonicalBinary.kt kmp/src/commonMain/kotlin/com/rohittp/reng/internal/identity/ResourceKeyDerivation.kt kmp/src/commonTest/kotlin/com/rohittp/reng/internal/firewall/BasemapEngineHostTest.kt
git commit -m "feat(kmp): own one basemap engine per renderer with RenG-derived tile identity"
```

---

### Task 20: Terrain acquisition and encoding validation

**Files:**
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/firewall/TerrainAcquisition.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/firewall/TerrainAcquisitionTest.kt`

**Interfaces:**
- Consumes: `decodePng` from Task 5; `TerrainEncoding`, `encodingOf`, `validateTerrain` from Task 13; `BasemapEngineHost` from Task 19.
- Produces: `internal class TerrainAcquisition` exposing the style's terrain descriptor, ground radiance, and decoded DEM samples.

Nothing consumes elevation in this cycle; Cycle E displaces the ground with it. Acquire, decode, and validate only. `TerrainEncoding`, `encodingOf`, and `validateTerrain`, used directly in this task's own test below, are Task 13's — defined once in `ClassGateRunner.kt` and consumed here, never redefined.

- [ ] **Step 1: Write the failing tests**

```kotlin
class TerrainAcquisitionTest {
    @Test
    fun exposesTheTerrainDescriptorAndGroundRadianceWhenTheStyleHasThem() = runTest {
        val acquisition = terrainAcquisition(styleWithTerrain)
        assertNotNull(acquisition.descriptor())
        assertNotNull(acquisition.groundRadiance())
    }

    @Test
    fun reportsNoTerrainForAStyleWithout() = runTest {
        assertNull(terrainAcquisition(styleWithoutTerrain).descriptor())
    }

    @Test
    fun decodesDemSamplesBitExactlyWithNoColourTransform() = runTest {
        val decoded = terrainAcquisition(styleWithTerrain).samples(tile)
        // Any premultiplication, scaling, or colour transform silently changes elevations.
        assertContentEquals(expectedDemChannelBytes, decoded.rgbaSnapshot())
    }

    @Test
    fun admitsExactlyTheTwoSupportedEncodings() = runTest {
        assertEquals(TerrainEncoding.MAPBOX, encodingOf(mapboxDemTile))
        assertEquals(TerrainEncoding.TERRARIUM, encodingOf(terrariumDemTile))
        assertIs<SuppliedValidationOutcome.Failed>(validateTerrain(unsupportedEncodingTile))
    }
}
```

- [ ] **Step 2: Run to verify it fails, then implement, then commit**

Run: `./gradlew --no-configuration-cache :kmp:testAndroidHostTest --tests "com.rohittp.reng.internal.firewall.TerrainAcquisitionTest"`

```bash
git add kmp/src/commonMain/kotlin/com/rohittp/reng/internal/firewall/TerrainAcquisition.kt kmp/src/commonTest/kotlin/com/rohittp/reng/internal/firewall/TerrainAcquisitionTest.kt
git commit -m "feat(kmp): acquire, decode, and validate terrain tiles"
```

---

### Task 21: Cycle gates and documentation

**Files:**
- Modify: `CLAUDE.md`
- Modify: `docs/decomposition.md`
- Modify: `HANDOFF.md`

**Interfaces:**
- Consumes: every prior task.
- Produces: a repository whose documented state matches what was built.

- [ ] **Step 1: Run the complete local gate chain with forced re-execution**

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tools/tests -p 'test_*.py' -v
PYTHONDONTWRITEBYTECODE=1 python3 tools/check_repository_policy.py --root .
./gradlew --no-configuration-cache --rerun-tasks \
  :kmp:checkKotlinAbi :kmp:testAndroidHostTest :kmp:macosArm64Test \
  :kmp:compileKotlinIosArm64 :kmp:compileKotlinIosSimulatorArm64 \
  :kmp:compileKotlinLinuxX64 :kmp:compileKotlinLinuxArm64 \
  :kmp:bundleAndroidMainAar :kmp:publishAllPublicationsToLocalTestRepository
```

- [ ] **Step 2: Run the fresh-home six-target consumer smoke**

```bash
final_smoke_home="$(mktemp -d)"
./gradlew --no-configuration-cache --gradle-user-home "$final_smoke_home" \
  --refresh-dependencies -p consumer-smoke \
  compileAndroidMain compileKotlinIosArm64 compileKotlinIosSimulatorArm64 \
  compileKotlinMacosArm64 compileKotlinLinuxX64 compileKotlinLinuxArm64
```

- [ ] **Step 3: Review the ABI diff one final time**

Run: `git diff main -- kmp/api/kmp.klib.api`
Expected: exactly the five declarations from Task 2 and nothing else. If a Rentile or platform type appears anywhere in the dump, stop.

- [ ] **Step 4: Update the repository documents**

`CLAUDE.md`'s repository-state section, `docs/decomposition.md`'s Cycle C paragraph, and `HANDOFF.md` must describe what now exists: acquisition, decode, parse, cache, and firewall implemented; no factory, no GL, no pixels. State plainly that RenG draws no map text and why, and carry forward the scheduler measurement from Task 11. Claim no gate that was not observed — `linuxX64Test` is Linux CI coverage and must not be claimed from a macOS run.

- [ ] **Step 5: Commit**

```bash
git add CLAUDE.md docs/decomposition.md HANDOFF.md
git commit -m "docs: record Cycle C as implemented"
```
