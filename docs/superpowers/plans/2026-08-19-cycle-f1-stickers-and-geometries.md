# Cycle F-1 — Stickers, Geometries, and the Renderer Factory Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make RenG operable — add the renderer factory and draw stickers and geometries — so downstream consumers can integrate against a working library.

**Architecture:** Cycle D left a `GlFrameContent` fun interface with the comment "Cycle D draws no frame content of its own; Cycle E replaces this with the real scene draw." This cycle supplies that content: a scene draw that runs the map regime depth-tested, then composites the screen regime on top. The renderer factory wires Cycle D's GL seam, Cycle D's lifecycle driver, and Cycle C's resource driver into the one public entry point that has never existed.

**Tech Stack:** Kotlin Multiplatform, six targets, GLSL ES 3.00 with desktop `330 core` substitution, standard library plus `kotlinx-coroutines` only.

**Spec:** `docs/superpowers/specs/2026-08-19-cycle-f1-stickers-and-geometries-design.md`

## Global Constraints

- Keep exactly six targets: `android`, `iosArm64`, `iosSimulatorArm64`, `macosArm64`, `linuxX64`, `linuxArm64`. Add no JVM, `macosX64`, or `iosX64` target.
- Keep `explicitApi()` enabled. **Unlike Cycles C and D, this cycle GROWS the public ABI.** Every addition is a reviewed `checkKotlinAbi` diff and must appear in the spec's Public API section — anything else is out of scope.
- Add **no new Gradle subproject**. Everything lands inside the single published `:kmp` module.
- Add **no cinterop definition on any target**. A declaration carrying `__attribute__((unavailable))` vanishes from a produced klib with no error and no warning (ADR 0009, re-measured).
- Standard library plus `kotlinx-coroutines` only. `kotlinx.serialization`, `okio`, and any other third-party dependency are FORBIDDEN.
- Every Gradle invocation passes `--no-configuration-cache`.
- RenG creates no Render Context and references no CGL, EAGL, EGL, `NSOpenGLContext`, or `ANativeWindow` in production source (ADR 0001). Context creation lives only in conformance fixtures.
- Typed failures only: stable error code, pipeline stage, and REDACTED diagnostics. No driver info log, entry-point name, shader source, library path, or adapter text reaches a `Diagnostic`, a `RenGException`, or any `toString()` — including via a `data class`'s generated `toString()`. `FailureDescriptor` and `Diagnostic` carry NO free-text field and `Diagnostic.fieldName` is allowlisted; do not introduce one.
- Keep cancellation as an unwrapped `CancellationException`, checked and rethrown before any generic catch.
- No retries, repairs, or fallbacks. The caller owns recovery.
- Never commit a `mavenLocal()` entry or a `-SNAPSHOT` dependency. `VERSION_NAME` stays `0.1.0` until the owner decides the release version.
- Platform-library resolution is enforced **per leaf compilation**, so a misplaced file fails at exactly one target and a partial compile can look green. Compile every target before trusting a layout.
- Against `RecordingGlBinding`, an assertion of the shape `assertEquals(before, captureGlState(...))` CANNOT FAIL — the fake's query maps are never mutated by its write methods (documented at `GlStateSnapshotTest.kt:77-83`). Assert on `binding.log` instead, with values chosen to DIFFER so calls are textually distinguishable, and assert ordering. This defect cost Cycle D three fix rounds.
- **All pixel verification is deferred to Cycle J** by owner decision. This cycle verifies the draw path by call-log assertion — draw calls issued, uniforms and textures bound, blend state set, regimes ordered — never by reading pixels back.

---

## File Structure

| Path | Responsibility |
|---|---|
| `kmp/src/commonMain/kotlin/com/rohittp/reng/DrawnThings.kt` (modify) | `Geometry` gains `uniforms` and `textures`; new public `ShaderValue` |
| `kmp/src/commonMain/kotlin/com/rohittp/reng/RendererFactory.kt` (create) | The public `createRenderer` entry point |
| `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/GlBinding.kt` (modify) | Three new uniform setters |
| `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/GlTextureUpload.kt` (create) | Premultiplied image upload and non-premultiplied data upload |
| `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/StickerPipeline.kt` (create) | The sticker quad, its program, and both regimes |
| `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/GeometryPipeline.kt` (create) | Geometry quad, consumer program, documented-name binding |
| `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/SceneContent.kt` (create) | The `GlFrameContent` implementation that orders the two regimes |
| `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/driver/ResourceActionExecutor.kt` (modify) | The MVP-blocking `CancelRoute` fix |
| `docs/adr/0024-order-the-two-draw-regimes.md` (create) | The between-regime decision and the silent-rename hazard |

---

## Task 1: Close the `CancelRoute` crash carried in from Cycle C

This lands first because **Task 9's factory is what makes the crash reachable**, and because every later
task's tests run through the driver.

A reviewer established the precise status: `Renderer` is a bare sealed interface with no concrete
implementation anywhere in the tree, and Cycle C's spec explicitly forbade building one, so
`cancelPreparations()` is public ABI text that no consumer can currently obtain an instance to call. The
gap is unreachable today — and becomes reachable the moment this cycle hands out a `Renderer`. Fixing it
after Task 9 would ship a window in which it is live.

The crash itself: `retireBufferedPrefix()` calls `cancelActiveRoutes(...)` whenever a route retires via
`ResourceRouteOutcome.Cancelled` — which requires `cause == ADAPTER`, exactly what Cycle C Task 15's new
code produces — while siblings with higher ordinals are still active. `ResourceActionExecutor.execute`
then hits an unhandled `else -> error(...)` for `CancelRoute`. Cycle C Task 15 kept its adapter-cancellation
tests single-route specifically to avoid tripping it.

**Files:**
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/driver/ResourceActionExecutor.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/driver/DriverCancellationTest.kt`

**Interfaces:**
- Consumes: `ResourceOperationAction.CancelRoute`, `ResourceOperationEvent`.
- Produces: executor handling for `CancelRoute` that emits the route-cancelled event rather than falling through to `error(...)`.

- [ ] **Step 1: Write the failing multi-route test**

Add to `DriverCancellationTest.kt`. The existing tests are deliberately single-route; this one is not.

```kotlin
@Test
fun aMultiRouteOperationSurvivesOneRouteObservingAdapterCancellation() = runTest {
    // Two distinct locators so preRegister cannot merge them into one route.
    val cancellingLocator = ResourceLocator("https://example.invalid/first.png")
    val hangingLocator = ResourceLocator("https://example.invalid/second.png")
    val transport = Transport { request ->
        if (request.locator == cancellingLocator) throw CancellationException("adapter cancelled itself")
        else TransportResponse(status = 200, body = onePixelPngBytes())
    }
    val driver = driverWith(transport = transport)

    val outcome = driver.run(operationOver(cancellingLocator, hangingLocator))

    // The crash was an error(...) fallthrough in the executor, not an assertion failure,
    // so the meaningful claim is that we reach a typed outcome at all.
    assertIs<ResourceOperationOutcome.Cancelled>(outcome)
}
```

- [ ] **Step 2: Run it and confirm it fails for the stated reason**

Run: `./gradlew --no-configuration-cache :kmp:testAndroidHostTest --tests "com.rohittp.reng.internal.driver.DriverCancellationTest"`

Expected: failure originating in `ResourceActionExecutor`'s `else -> error(...)` branch on an unhandled
`CancelRoute`, NOT an assertion failure. If it fails as an assertion instead, the gap is already closed
and you should stop and report that rather than writing a fix for a bug that is not there.

- [ ] **Step 3: Handle `CancelRoute` in the executor**

Add the branch beside the existing action handling in `ResourceActionExecutor`, following the shape the
neighbouring branches already use. It performs no consumer exchange — cancelling a route is internal
bookkeeping, so it must not call transport or store, and it must not be wrapped in `suppliedCall`.

- [ ] **Step 4: Confirm it passes, and confirm the single-route tests still do**

Run: `./gradlew --no-configuration-cache :kmp:testAndroidHostTest :kmp:macosArm64Test`
Expected: PASS, with the pre-existing `DriverCancellationTest` cases unchanged.

- [ ] **Step 5: Mutation-check the new branch**

Replace your `CancelRoute` handling with `error("unreachable")` and confirm the new test fails; restore it
and confirm it passes. Report both observations. A branch whose removal changes nothing is not load-bearing.

- [ ] **Step 6: Commit**

```bash
git add kmp/src/commonMain/kotlin/com/rohittp/reng/internal/driver/ResourceActionExecutor.kt \
        kmp/src/commonTest/kotlin/com/rohittp/reng/internal/driver/DriverCancellationTest.kt
git commit -m "fix(kmp): handle CancelRoute so multi-route cancellation cannot crash"
```

---

## Task 2: Add the three missing uniform setters to the GL seam

The documented shader interface needs `uniform2f` (for `uResolution`), `uniform1ui` (for `uFrameIndex`),
and `uniform3f` (for `ShaderValue.Vec3`). `GlBinding` currently declares only `uniform1i`, `uniform1f`,
`uniform4f` and `uniformMatrix4fv`. Adding entry points touches every platform binding, the recording fake,
and Cycle D's fixed entry-point roster.

**Files:**
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/GlBinding.kt`
- Modify: `kmp/src/androidMain/.../AndroidGlBinding.kt`, `iosMain/.../IosGlBinding.kt`, `macosMain/.../MacosGlBinding.kt`, `linuxMain/.../LinuxGlBinding.kt`
- Modify: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/gl/RecordingGlBinding.kt`
- Modify: the entry-point roster and its test from Cycle D Task 1

**Interfaces:**
- Produces: `fun uniform2f(location: Int, x: Float, y: Float)`, `fun uniform3f(location: Int, x: Float, y: Float, z: Float)`, `fun uniform1ui(location: Int, value: Int)`.

`uniform1ui` takes a Kotlin `Int` because Kotlin's `UInt` does not bridge cleanly through every platform
binding; the value is a bit pattern and the caller is responsible for the narrowing documented in the spec.

- [ ] **Step 1: Write the failing roster and fake tests**

The roster test is the one that matters — Cycle D fixed the entry-point count deliberately, so growing it
must be a conscious, reviewed act rather than a silent drift.

```kotlin
@Test
fun theRosterContainsTheThreeUniformSettersTheShaderInterfaceNeeds() {
    // NOTE: assert on cName, not name. The enum's identifiers are UPPER_SNAKE_CASE
    // (UNIFORM_2F), so comparing them against a lowercase GL spelling can never match
    // and the test would fail for a reason unrelated to what it checks.
    val names = GlEntryPoint.entries.map { it.cName }
    assertTrue("glUniform2f" in names)
    assertTrue("glUniform3f" in names)
    assertTrue("glUniform1ui" in names)
}

@Test
fun theRecordingFakeLogsEachNewUniformSetterDistinguishably() {
    val binding = RecordingGlBinding()
    binding.uniform2f(location = 3, x = 1f, y = 2f)
    binding.uniform3f(location = 4, x = 3f, y = 4f, z = 5f)
    binding.uniform1ui(location = 5, value = 7)
    assertEquals(listOf("uniform2f(3,1.0,2.0)", "uniform3f(4,3.0,4.0,5.0)", "uniform1ui(5,7)"), binding.log)
}
```

- [ ] **Step 2: Run and confirm both fail on unresolved references**

Run: `./gradlew --no-configuration-cache :kmp:testAndroidHostTest`
Expected: compile failure on `uniform2f`, `uniform3f`, `uniform1ui`.

- [ ] **Step 3: Declare them on the seam and implement on all four platforms**

Add to `GlBinding`, then implement in each platform binding against that platform's real GL entry point.
Follow each file's existing style exactly — the iOS and Linux bindings differ in how they reach the driver,
and matching the neighbours matters more than internal consistency between them.

**Bounds discipline:** several macOS sites were previously fixed for pinning caller arrays and passing GL
`count` slots without `require(out.size >= count)`. These three setters take scalars rather than arrays, so
that hazard does not apply — but do not copy an array-taking neighbour's body without removing its guard.

- [ ] **Step 4: Confirm every target compiles, not just the host**

Run:
```
./gradlew --no-configuration-cache :kmp:testAndroidHostTest :kmp:macosArm64Test :kmp:checkKotlinAbi
./gradlew --no-configuration-cache :kmp:compileKotlinLinuxX64 :kmp:compileKotlinLinuxArm64 :kmp:compileKotlinIosArm64 :kmp:compileKotlinIosSimulatorArm64
```
Expected: all PASS. Platform-library resolution is per leaf compilation, so a binding that compiles on one
target proves nothing about the others.

`checkKotlinAbi` must still report NO public change — `GlBinding` is `internal`.

- [ ] **Step 5: Commit**

```bash
git add kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/GlBinding.kt \
        kmp/src/androidMain kmp/src/iosMain kmp/src/macosMain kmp/src/linuxMain \
        kmp/src/commonTest/kotlin/com/rohittp/reng/internal/gl/
git commit -m "feat(kmp): add the uniform setters the shader interface requires"
```

---

## Task 3: `ShaderValue`, the `Geometry` growth, and the frame-identity consequence

This is the cycle's first public ABI change and its most cross-cutting task. Read the whole task before
starting — the encoding consequence in Step 3 is not optional, and skipping it produces a correctness bug
that no test in the current suite would catch.

**Files:**
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/DrawnThings.kt`
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/identity/FramePlanCanonicalEncoding.kt`
- Modify: `kmp/api/kmp.klib.api` (regenerated, reviewed as a diff)
- Modify: `docs/superpowers/specs/2026-08-17-cycle-b-canonical-frame-v1-test-vector.txt` (appended erratum)
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/ShaderValueTest.kt`
- Test: existing canonical-encoding tests

**Interfaces:**
- Produces:
  ```kotlin
  public sealed interface ShaderValue {
      public data class Scalar(public val value: Float) : ShaderValue
      public data class Vec2(public val x: Float, public val y: Float) : ShaderValue
      public data class Vec3(public val x: Float, public val y: Float, public val z: Float) : ShaderValue
      public data class Vec4(public val x: Float, public val y: Float, public val z: Float, public val w: Float) : ShaderValue
      public data class Integer(public val value: Int) : ShaderValue
      public class Mat4(elements: FloatArray) : ShaderValue
  }
  ```
- Modifies: `Geometry` gains `uniforms: Map<String, ShaderValue> = emptyMap()` and `textures: Map<String, ResourceLocator> = emptyMap()`.

- [ ] **Step 1: Write the failing value-semantics tests**

`ShaderValue` must match the canonicalization discipline `Vector3` already sets — `CONTEXT.md` says
construction "canonicalizes negative zero to positive zero and rejects every non-finite component". A value
type that skips this breaks content-keyed identity, because `-0.0` and `0.0` would produce different digests
for plans that are semantically identical.

```kotlin
class ShaderValueTest {
    @Test
    fun negativeZeroIsCanonicalizedLikeEveryOtherRenGValue() {
        assertEquals(ShaderValue.Vec2(0.0f, 0.0f), ShaderValue.Vec2(-0.0f, -0.0f))
    }

    @Test
    fun everyNonFiniteComponentIsRejected() {
        assertFailsWith<IllegalArgumentException> { ShaderValue.Scalar(Float.NaN) }
        assertFailsWith<IllegalArgumentException> { ShaderValue.Vec3(1f, Float.POSITIVE_INFINITY, 3f) }
        assertFailsWith<IllegalArgumentException> { ShaderValue.Mat4(FloatArray(16) { Float.NaN }) }
    }

    @Test
    fun aMat4RequiresExactlySixteenElementsAndComparesByValue() {
        assertFailsWith<IllegalArgumentException> { ShaderValue.Mat4(FloatArray(15)) }
        assertEquals(ShaderValue.Mat4(FloatArray(16) { it.toFloat() }), ShaderValue.Mat4(FloatArray(16) { it.toFloat() }))
    }
}
```

**A contradiction in this task, discovered during implementation and left here deliberately.** The
`Produces` block above mandates a literal `data class Scalar(public val value: Float)` shape, while this
step asks for Vector3-style construction canonicalization. Kotlin makes those mutually exclusive: a data
class's primary-constructor parameter *is* the stored property, so nothing can normalize it before storage.
The resolution taken was to keep the literal shape and hand-write `equals`/`hashCode` that compare and hash
`-0.0f` and `0.0f` identically. That leaves the accessor returning the raw value, which is a real
inconsistency with `Vector3` — but identity safety is preserved regardless, because `CanonicalBinary.binary64`
calls `canonicalDouble` before encoding, so the encoder re-canonicalizes independently of storage.

`Mat4` is a plain `class`, not a `data class`: a `data class` holding a `FloatArray` generates
reference-based `equals`/`hashCode`, so two identical matrices would compare unequal and produce different
frame identities. Write `equals`, `hashCode` and a redacting `toString` by hand, and copy the incoming array
defensively so a caller mutating theirs cannot change a value RenG has already hashed.

- [ ] **Step 2: Run and confirm failure, then implement `ShaderValue` and grow `Geometry`**

Run: `./gradlew --no-configuration-cache :kmp:testAndroidHostTest --tests "com.rohittp.reng.ShaderValueTest"`
Expected: unresolved reference, then PASS after implementing.

Both new `Geometry` parameters take defaults so every existing construction site keeps compiling.

- [ ] **Step 3: Extend the canonical frame encoding — THE LOAD-BEARING STEP**

`FramePlanCanonicalEncoding.encodeGeometry` currently emits fields 1 (topLeft), 2 (bottomRight) and
3 (shaderPair). Add field 4 for `uniforms` and field 5 for `textures`, appended so existing tags keep their
meaning.

**Why this is mandatory rather than tidy:** RenG's frame-to-frame reuse is keyed on plan content. If
uniforms are not encoded, two plans differing only in a uniform value produce the *same* frame identity, and
a consumer animating through a uniform is served a stale frame. That is precisely the scenario `uFrameIndex`
exists to support, so leaving it unencoded would break the cycle's own headline feature — silently, since
nothing currently asserts that differing uniforms yield differing identities.

Maps have no inherent order, and the encoding must be deterministic. Sort entries by key using a fixed
code-unit ordering rather than a locale-sensitive comparison, and document that choice at the call site.

Add the test that pins it:

```kotlin
@Test
fun geometriesDifferingOnlyByAUniformValueGetDifferentFrameIdentities() {
    val base = geometryWith(uniforms = mapOf("uTint" to ShaderValue.Scalar(0.25f)))
    val other = geometryWith(uniforms = mapOf("uTint" to ShaderValue.Scalar(0.75f)))
    assertNotEquals(frameIdentityOf(planWith(base)), frameIdentityOf(planWith(other)))
}

@Test
fun uniformMapIterationOrderDoesNotChangeTheFrameIdentity() {
    val forward = geometryWith(uniforms = linkedMapOf("uA" to ShaderValue.Integer(1), "uB" to ShaderValue.Integer(2)))
    val reversed = geometryWith(uniforms = linkedMapOf("uB" to ShaderValue.Integer(2), "uA" to ShaderValue.Integer(1)))
    assertEquals(frameIdentityOf(planWith(forward)), frameIdentityOf(planWith(reversed)))
}
```

- [ ] **Step 4: Regenerate the frozen test vector, and append an erratum rather than rewriting its header**

`docs/superpowers/specs/2026-08-17-cycle-b-canonical-frame-v1-test-vector.txt` declares itself normative and
says its "values, exact bytes, length, and identity remain unchanged". Extending the geometry encoding
changes the digest, so that statement stops being true and the vector must be regenerated.

Do NOT rewrite the header. Append an erratum recording what changed, when, and why — the same practice
`docs/research/2026-08-18-cycle-c-glb-parse.md` already follows. Rewriting it in place would erase the fact
that the vector was normative under a narrower encoding.

Keep the schema byte and the `reng-frame-v1` prefix as they are, and record the reasoning: frame identity is
**internal** — it appears nowhere in `kmp/api/kmp.klib.api` — and no published artifact ever exposed a way to
compute one, since `0.1.0` shipped with no public runtime API. There is therefore no external consumer whose
stored digest could disagree. If a later cycle makes frame identity observable, that is the moment a version
bump earns its cost.

- [ ] **Step 5: Regenerate and review the ABI dump**

Run: `./gradlew --no-configuration-cache :kmp:apiDump` then inspect `git diff kmp/api/kmp.klib.api`.
Expected additions: `ShaderValue` and its six members, and `Geometry`'s two new constructor parameters and
accessors. **Anything else in that diff is out of scope and must be explained or removed** — this is the
cycle's first ABI growth and the diff is the reviewed artifact.

- [ ] **Step 6: Commit**

```bash
git add kmp/src/commonMain/kotlin/com/rohittp/reng/DrawnThings.kt \
        kmp/src/commonMain/kotlin/com/rohittp/reng/internal/identity/FramePlanCanonicalEncoding.kt \
        kmp/api/kmp.klib.api kmp/src/commonTest docs/superpowers/specs/
git commit -m "feat(kmp): let a Geometry carry consumer uniforms and textures"
```

---

## Task 4: Texture upload — premultiplied for images, bit-exact for data

**Files:**
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/GlTextureUpload.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/gl/GlTextureUploadTest.kt`

**Interfaces:**
- Consumes: `DecodedImage` (from Cycle C — tightly packed, unpremultiplied RGBA8, `rgbaSnapshot()`), `GlBinding`, `GlObjectRegistry`.
- Produces:
  ```kotlin
  internal enum class TextureContent { IMAGE, DATA }
  internal fun uploadTexture(binding: GlBinding, image: DecodedImage, content: TextureContent): Int
  ```

The enum is the point of the task. The premultiply decision is **by purpose, not by file format** — both
paths decode identically through Cycle C and differ only at upload — and expressing it in the type means a
caller cannot get it wrong by omission.

- [ ] **Step 1: Write the failing tests**

```kotlin
class GlTextureUploadTest {
    private fun halfAlphaRed() = DecodedImage(1, 1, byteArrayOf(-1, 0, 0, -128))  // 255,0,0,128 unpremultiplied

    @Test
    fun anImageTextureIsPremultipliedBeforeUpload() {
        val binding = RecordingGlBinding()
        uploadTexture(binding, halfAlphaRed(), TextureContent.IMAGE)
        // 255 * 128/255 = 128 exactly; alpha is untouched.
        assertEquals(listOf<Byte>(-128, 0, 0, -128), binding.lastTexImageBytes())
    }

    @Test
    fun aDataTextureIsUploadedBitExact() {
        val binding = RecordingGlBinding()
        uploadTexture(binding, halfAlphaRed(), TextureContent.DATA)
        assertEquals(listOf<Byte>(-1, 0, 0, -128), binding.lastTexImageBytes())
    }

    @Test
    fun theDecodedImageIsNeverMutatedByEitherPath() {
        val image = halfAlphaRed()
        val before = image.rgbaSnapshot().toList()
        uploadTexture(RecordingGlBinding(), image, TextureContent.IMAGE)
        assertEquals(before, image.rgbaSnapshot().toList())
    }
}
```

The third test is the one that protects Cycle C's contract: the canonical decoded form stays unpremultiplied,
so premultiplication must happen on a copy. `DecodedImage.rgbaSnapshot()` already returns a fresh copy on
every read, so the natural implementation is correct — the test exists so a later "optimization" that
premultiplies in place fails loudly.

- [ ] **Step 2: Run, confirm failure, implement**

`RecordingGlBinding` will need a way to expose the bytes passed to `texImage2D`; add it in the same style as
its existing recording fields, and keep it an instance member — the fake has no static state and must not
gain any.

- [ ] **Step 3: Confirm the premultiply arithmetic on a rounding case**

Add a case where the multiply does not divide evenly, and assert the exact expected byte rather than a
range. Rounding choices differ between `(c * a) / 255` and `(c * a + 127) / 255`; pick one, state it in a
comment, and pin it. An unpinned rounding rule is the kind of thing that silently differs between platforms.

- [ ] **Step 4: Run every gate and commit**

```
./gradlew --no-configuration-cache :kmp:testAndroidHostTest :kmp:macosArm64Test :kmp:checkKotlinAbi
```
`checkKotlinAbi` must report NO change — everything here is `internal`.

```bash
git commit -m "feat(kmp): upload image textures premultiplied and data textures bit-exact"
```

---

## Task 5: The sticker pipeline and both draw regimes

**Files:**
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/StickerPipeline.kt`
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/CompositePipeline.kt` (one enum value)
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/gl/StickerPipelineTest.kt`

**Interfaces:**
- Consumes: `uploadTexture` (Task 4), `compileShaderProgram` and `GlProgramCache` (Cycle D Task 11), `ResourceKeyDeriver.internalPipeline` (Cycle D Task 10), `ShaderProfilePlan.sourceFor`.
- Produces: `internal class StickerPipeline`, `internal fun createStickerPipeline(...)`, `internal fun deleteStickerPipeline(...)`, and `InternalPipelineRole.STICKER`.

- [ ] **Step 1: Add the pipeline role, without renumbering the existing one**

`InternalPipelineRole.COMPOSITE` has wire value 1 and is pinned by literal hex byte vectors in
`InternalResourceKeyTest`. Add `STICKER` with wire value **2**. Renumbering `COMPOSITE` would silently
invalidate every previously derived internal pipeline identity, and the pinned vectors are what would catch
it — if those tests fail, stop and report rather than updating the expected strings.

- [ ] **Step 2: Write the failing regime-ordering test**

This is the assertion that proves ADR 0024, and it is expressible without a basemap because the map regime
still contains map-anchored stickers depth-testing one another.

```kotlin
@Test
fun theMapRegimeDrawsDepthTestedBeforeTheScreenRegimeComposites() {
    val binding = RecordingGlBinding()
    val world = stickerWorld(
        mapAnchored = listOf(stickerAt(AnchoringMode.MAP, z = 0.0)),
        screenAnchored = listOf(stickerAt(AnchoringMode.SCREEN, z = 5.0)),
    )
    drawStickers(binding, world)

    val depthEnabled = binding.log.indexOfFirst { it == "enable(0x0B71)" }        // GL_DEPTH_TEST
    val depthDisabled = binding.log.indexOfFirst { it == "disable(0x0B71)" }
    val mapDraw = binding.log.indexOfFirst { it.startsWith("drawArrays") }
    val screenDraw = binding.log.indexOfLast { it.startsWith("drawArrays") }

    assertTrue(depthEnabled in 0 until mapDraw, "the map regime must be depth-tested")
    assertTrue(mapDraw < depthDisabled, "depth must stay on until the map regime is finished")
    assertTrue(depthDisabled < screenDraw, "the screen regime must composite with depth off")
}
```

Note the shape: this asserts on `binding.log` positions with distinguishable entries, never on a
capture/restore round trip, which cannot fail against this fake.

- [ ] **Step 3: Write the failing screen-ordering test**

**A defect in the test below, found by running the mutation this task asks for.** Asserting stable plan
order for EQUAL z values cannot catch a reversed sort, because a stable sort preserves tied elements'
order regardless of direction. The tie test is still worth having — `CONTEXT.md` fixes that rule — but it
must be paired with a test using DIFFERING z values, which is what actually pins the comparator.

`CONTEXT.md` fixes this precisely: greater `position.z` composites on top, and equal values use stable plan
order — stickers in list order, then models in list order, later entries on top. Models do not exist this
cycle, so only the sticker half is testable; pin it anyway, because the rule is already documented and a
later cycle inserting models must not perturb it.

```kotlin
@Test
fun equalZIndexCompositesInStablePlanOrder() {
    val binding = RecordingGlBinding()
    drawStickers(binding, stickerWorld(screenAnchored = listOf(
        stickerAt(AnchoringMode.SCREEN, z = 1.0, texture = firstTexture),
        stickerAt(AnchoringMode.SCREEN, z = 1.0, texture = secondTexture),
    )))
    val firstBind = binding.log.indexOfFirst { it == "bindTexture(0x0DE1,$firstTexture)" }
    val secondBind = binding.log.indexOfFirst { it == "bindTexture(0x0DE1,$secondTexture)" }
    assertTrue(firstBind < secondBind, "later plan entries composite on top, so they draw later")
}
```

- [ ] **Step 4: Implement**

The sticker program is RenG's own, so it is written as a GLES 3.00 source and goes through
`ShaderProfilePlan.sourceFor(dialect)` exactly as a consumer's would — RenG's own shaders are not exempt
from the substitution rule, and running them through it is what keeps the rule exercised on every platform.

Follow `createCompositePipeline` in `CompositePipeline.kt` as the reference call site for compiling and
caching a program; match its argument order rather than reconstructing the signature from memory.

One unit quad is created once and reused for every sticker, transformed per-sticker by the matrix uniform.
Do not allocate a VBO per sticker.

Blend state is `GL_ONE, GL_ONE_MINUS_SRC_ALPHA` per Task 4's premultiplied upload. Set it explicitly rather
than inheriting whatever the caller left bound — RenG restores GL state around a frame, so it must also
establish what it depends on.

- [ ] **Step 5: Confirm the tests fail without each guard**

Mutation-check three things and report each observation: remove the depth-enable and confirm the ordering
test fails; reverse the screen sort and confirm the stable-order test fails; and set blend to
`GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA` and confirm a test catches it. If the blend change is caught by
nothing, add the assertion — a blend mode nothing pins will drift.

- [ ] **Step 6: Run every gate and commit**

```
./gradlew --no-configuration-cache :kmp:testAndroidHostTest :kmp:macosArm64Test :kmp:checkKotlinAbi
./gradlew --no-configuration-cache :kmp:compileKotlinLinuxX64 :kmp:compileKotlinLinuxArm64 :kmp:compileKotlinIosArm64 :kmp:compileKotlinIosSimulatorArm64
```

```bash
git commit -m "feat(kmp): draw stickers in both anchoring regimes"
```

---

## Task 6: The geometry pipeline and the documented shader interface

**Files:**
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/GeometryPipeline.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/gl/GeometryPipelineTest.kt`

**Interfaces:**
- Consumes: `compileShaderProgram`, `GlProgramCache`, `ResourceKeyDeriver.geometryProgram`, `ShaderProfilePlan`.
- Produces: `internal class GeometryPipeline`, `internal fun drawGeometry(...)`, and the documented name constants.

- [ ] **Step 1: Fix the documented names as constants**

```kotlin
internal const val ATTRIBUTE_POSITION = "aPosition"
internal const val ATTRIBUTE_TEXTURE_COORDINATE = "aTexCoord"
internal const val UNIFORM_MODEL_VIEW_PROJECTION = "uModelViewProjection"
internal const val UNIFORM_RESOLUTION = "uResolution"
internal const val UNIFORM_GEOMETRY_BOUNDS = "uGeometryBounds"
internal const val UNIFORM_FRAME_INDEX = "uFrameIndex"

/** Every documented name, for reserved-name rejection in Task 7. */
internal val RESERVED_SHADER_NAMES: Set<String> = setOf(
    ATTRIBUTE_POSITION, ATTRIBUTE_TEXTURE_COORDINATE,
    UNIFORM_MODEL_VIEW_PROJECTION, UNIFORM_RESOLUTION,
    UNIFORM_GEOMETRY_BOUNDS, UNIFORM_FRAME_INDEX,
)
```

- [ ] **Step 2: Write the failing bind-only-when-declared tests**

This is ADR 0008's actual mechanism and the thing most likely to be implemented wrongly. `getUniformLocation`
returns a negative value when the compiled program does not declare that name, and setting a uniform at a
negative location is a silent no-op in GL — so an implementation that skips the check appears to work while
issuing meaningless calls.

```kotlin
@Test
fun aShaderDeclaringNoDocumentedNameStillCompilesAndDraws() {
    val binding = RecordingGlBinding().withNoDeclaredNames()
    drawGeometry(binding, geometryWith(minimalShaderPair()), frameIndex = 7L)
    assertTrue(binding.log.any { it.startsWith("drawArrays") }, "it must still draw")
    assertTrue(binding.log.none { it.startsWith("uniform") }, "and set nothing it cannot set")
}

@Test
fun aShaderDeclaringOnlyFrameIndexGetsOnlyThatOneSet() {
    val binding = RecordingGlBinding().withDeclaredNames(UNIFORM_FRAME_INDEX to 4)
    drawGeometry(binding, geometryWith(minimalShaderPair()), frameIndex = 7L)
    assertEquals(listOf("uniform1ui(4,7)"), binding.log.filter { it.startsWith("uniform") })
}
```

- [ ] **Step 3: Write the failing frame-index narrowing test**

`FramePlan.frameIndex` is a `Long`; the uniform is a `uint`. Pin the narrowing explicitly so the documented
wrap is a decision rather than an accident.

```kotlin
@Test
fun aFrameIndexBeyondThirtyTwoBitsWrapsAsDocumented() {
    val binding = RecordingGlBinding().withDeclaredNames(UNIFORM_FRAME_INDEX to 4)
    drawGeometry(binding, geometryWith(minimalShaderPair()), frameIndex = 0x1_0000_0007L)
    assertEquals(listOf("uniform1ui(4,7)"), binding.log.filter { it.startsWith("uniform") })
}
```

- [ ] **Step 4: Implement, and keep `uGeometryBounds` informational**

Build the quad from `topLeft` and `bottomRight`, with altitude interpolating north-to-south exactly as
`CONTEXT.md` specifies. Vertex positions go through the camera-relative path — do **not** compute vertex
positions from raw degrees, which would discard the sub-0.001px accuracy Cycle B measured.

`uGeometryBounds` carries west, south, east, north in degrees as a `vec4`. Document at the binding site that
it is informational and unsuitable for placement arithmetic, so nobody later "improves" the vertex path to
use it.

- [ ] **Step 5: Run every gate and commit**

```bash
git commit -m "feat(kmp): paint geometries with the documented shader interface"
```

---

## Task 7: Consumer uniforms, consumer textures, and reserved-name rejection

**Files:**
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/GeometryPipeline.kt`
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/reng/DrawnThings.kt` (the `init` validation)
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/gl/GeometryPipelineTest.kt`, `.../GeometryValidationTest.kt`

- [ ] **Step 1: Reject reserved names at `Geometry` construction**

Task 3 added the fields; this step adds their validation, now that Task 6 has defined the reserved set. The
rejection belongs at construction — loudly, at the point of the mistake — rather than letting RenG's own
binding silently win at draw time.

```kotlin
@Test
fun aConsumerUniformCollidingWithADocumentedNameIsRejectedAtConstruction() {
    assertFailsWith<IllegalArgumentException> {
        geometryWith(uniforms = mapOf(UNIFORM_MODEL_VIEW_PROJECTION to ShaderValue.Scalar(1f)))
    }
}

@Test
fun aConsumerTextureCollidingWithADocumentedNameIsRejectedAtConstruction() {
    assertFailsWith<IllegalArgumentException> {
        geometryWith(textures = mapOf(UNIFORM_RESOLUTION to ResourceLocator("https://example.invalid/a.png")))
    }
}
```

- [ ] **Step 2: Write the failing texture-unit assignment test**

Consumer textures occupy units deterministically, and the sampler uniform receives the unit index. Sorting
by name keeps unit assignment stable across runs — the same determinism requirement the canonical encoding
has.

```kotlin
@Test
fun consumerTexturesTakeStableUnitsAndTheirSamplersReceiveTheUnitIndex() {
    val binding = RecordingGlBinding().withDeclaredNames("uMaskB" to 9, "uMaskA" to 8)
    drawGeometry(binding, geometryWith(textures = linkedMapOf(
        "uMaskB" to ResourceLocator("https://example.invalid/b.png"),
        "uMaskA" to ResourceLocator("https://example.invalid/a.png"),
    )), frameIndex = 0L)
    // Sorted by name: uMaskA takes unit 0, uMaskB takes unit 1, regardless of map order.
    assertEquals(listOf("uniform1i(8,0)", "uniform1i(9,1)"), binding.log.filter { it.startsWith("uniform1i") })
}
```

- [ ] **Step 3: Write the failing texture-unit cap test**

GLES 3.0 guarantees only 16 fragment texture units, and RenG's own draw consumes some. Exceeding the budget
must be a typed rejection rather than a silently ignored bind.

```kotlin
@Test
fun exceedingTheConsumerTextureBudgetIsATypedRejectionNotASilentDrop() {
    val tooMany = (0 until MAXIMUM_CONSUMER_TEXTURES + 1).associate {
        "uMask$it" to ResourceLocator("https://example.invalid/$it.png")
    }
    assertFailsWith<IllegalArgumentException> { geometryWith(textures = tooMany) }
}
```

- [ ] **Step 4: Close the live-map hazard before reading uniforms at draw time**

`Geometry.uniforms` and `.textures` store the caller's **live `Map` reference** with no defensive copy — a
forced consequence of keeping `Geometry` a `data class`. A reviewer traced the exposure: today the only
reader is `FramePlanningCore.plan()`, which reads synchronously once and serializes into immutable bytes, so
nothing can diverge. **This step is the first code to read those maps at DRAW time**, which is a second,
later read of the same object.

If a `PreparedFrame` retains the original `Geometry` reference, a consumer mutating their map between
`prepare()` and `draw()` renders values that differ from what the frame's recorded identity hashed — a silent
divergence between what was drawn and what was proven. Snapshot the maps at prepare time rather than reading
the live reference at draw time, and if that is not possible here, document the no-mutation-after-construction
contract on `Geometry` and hand the snapshot requirement to Task 9.

- [ ] **Step 5: Implement**

Consumer uniforms dispatch over `ShaderValue`'s sealed hierarchy with an exhaustive `when` and **no `else`
branch**, so a future `ShaderValue` variant is a compile error rather than a silently unset uniform. That is
the same discipline Cycle D applied to `applyTerminal`, and it exists for the same reason.

Consumer textures upload through `uploadTexture(..., TextureContent.DATA)` — never `IMAGE`. Premultiplying a
mask or an SDF destroys it with no error.

**Set sampler state, and understand that it is mandatory rather than tidy.** GL's default minification
filter is `GL_NEAREST_MIPMAP_LINEAR`, which expects a mipmap chain, so a texture uploaded with neither
mipmaps nor an explicit `GL_TEXTURE_MIN_FILTER` is incomplete and samples **black** on real drivers.

Data textures take `GL_NEAREST` for both min and mag filter, by owner decision: nearest never invents a
value, and interpolating between index 3 and index 7 yields index 5. The accepted cost is that a
signed-distance field wants linear and loses its antialiasing under nearest — document at the call site
that a per-texture filter choice is the intended additive fix when a consumer needs it, so the next reader
knows this was decided rather than missed.

Both filters take `GL_CLAMP_TO_EDGE` wrap. The GL default is `GL_REPEAT`, which samples the opposite edge
at the boundary.

- [ ] **Step 5: Mutation-check the exhaustiveness and the DATA choice**

Confirm that changing `TextureContent.DATA` to `IMAGE` in the consumer-texture path fails a test. If nothing
catches it, add the assertion — this is the single most damaging silent error available in this cycle, and
it must not depend on review attention.

- [ ] **Step 6: Run every gate and commit**

```bash
git commit -m "feat(kmp): bind consumer uniforms and data textures, rejecting reserved names"
```

---

## Task 8: `SceneContent` — the frame content that orders the two regimes

Cycle D left `GlFrameContent` with the comment "Cycle D draws no frame content of its own; Cycle E replaces
this with the real scene draw." This task supplies that replacement.

**Files:**
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/SceneContent.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/internal/gl/SceneContentTest.kt`

**Interfaces:**
- Consumes: `GlFrameContent` and `drawFrame` (Cycle D Task 13), `StickerPipeline` (Task 5), `GeometryPipeline` (Task 6).
- Produces: `internal class SceneContent(...) : GlFrameContent`.

- [ ] **Step 1: Write the failing whole-frame ordering test**

Tasks 5 and 6 each proved their own regime in isolation. This proves they compose in one frame, which is
what ADR 0024 actually claims.

```kotlin
@Test
fun oneFrameDrawsMapAnchoredThingsAndGeometriesBeforeScreenCompositing() {
    val binding = RecordingGlBinding()
    SceneContent(sceneWith(
        mapStickers = listOf(stickerAt(AnchoringMode.MAP, z = 0.0)),
        geometries = listOf(geometryWith(minimalShaderPair())),
        screenStickers = listOf(stickerAt(AnchoringMode.SCREEN, z = 3.0)),
    )).draw(binding)

    val lastDepthTested = binding.log.indexOfLast { it == "disable(0x0B71)" }
    val screenDraw = binding.log.indexOfLast { it.startsWith("drawArrays") }
    assertTrue(lastDepthTested in 0 until screenDraw,
        "every depth-tested thing draws before the screen regime composites")
}
```

- [ ] **Step 2: Write the failing empty-scene test**

A plan with nothing in it must draw nothing and fail nothing. This is the case a consumer hits first while
wiring their integration, so it must be quiet rather than merely non-crashing.

```kotlin
@Test
fun anEmptySceneIssuesNoDrawCallAndNoFailure() {
    val binding = RecordingGlBinding()
    SceneContent(sceneWith()).draw(binding)
    assertTrue(binding.log.none { it.startsWith("drawArrays") || it.startsWith("drawElements") })
}
```

- [ ] **Step 3: Own the geometry resolution Task 6 deliberately did not**

**Both Tasks 5 and 6 pushed resolution up to this task**, independently and for the same reason, so
`SceneContent` owns materially more than this plan originally assigned it. `drawStickers` consumes
pre-resolved `ResolvedSticker`/`StickerWorld` values — matrix, texture, z — rather than raw
`Sticker`/`Placement`/`Camera`, and composing a real model-view-projection matrix from `PlacementResolver`
and `CameraMatrices` output does not exist anywhere in the tree yet. That composition is this task's, and
it is the largest single piece of unwritten work left in the cycle.

Task 6 made the same call for geometries: `drawGeometry` takes **already-resolved camera-relative
primitives** — a Float corner array, the MVP matrix, resolution and bounds — rather than a raw `Geometry`
plus camera. Its reasoning was that resolving a geometry is a `FRAME_PLANNING`-stage operation and does not
belong inside a GL draw call, and it proved the precision path holds with an integration test calling the
real `resolveMercatorCamera`/`resolveGeometry` and asserting the uploaded vertex bytes match bit-exactly.

So `SceneContent` converts `Geometry` plus the resolved camera into those primitives per frame. Keep the
conversion camera-relative — do not compute vertex positions from raw degrees, which would discard the
sub-0.001px accuracy Cycle B measured and Task 6 preserved.

- [ ] **Step 4: Implement and wire into `drawFrame`**

`SceneContent` implements `GlFrameContent` and is passed as `drawFrame`'s `content` argument in place of
`EmptyGlFrameContent`. Do not change `drawFrame`'s signature — Cycle D designed the seam for exactly this.

Geometries are map-anchored by definition: `CONTEXT.md` says "A Geometry carries no Placement", so they draw
in the map regime and never the screen one.

- [ ] **Step 4: Confirm Cycle D's state restoration still holds**

`drawFrame` restores captured GL state around the frame via `withCapturedGlState`. Content that leaves depth
or blend state changed must not defeat that. Run Cycle D's existing `GlFrameDrawerTest` unchanged and confirm
it still passes — if it does not, the content is escaping the guard rather than the guard being wrong.

- [ ] **Step 5: Run every gate and commit**

```bash
git commit -m "feat(kmp): draw the scene's two regimes in one frame"
```

---

## Task 9: The renderer factory

**This task is split into 9a and 9b.** It accumulated four distinct concerns during the cycle — the factory
itself, texture lifetime, the `PreparedFrame` snapshot point, and sticker quad sizing — and a reviewer could
meaningfully reject one while approving the other. They run in sequence, not parallel, because 9b needs the
`PreparedFrame` implementation 9a creates.

- **9a — the factory and `RenGRenderer`.** `createRenderer`, the concrete renderer, lifecycle delegation,
  `drawBasemap` warn-and-degrade, wiring `SceneContent` into `drawFrame`, and leak discipline on partial
  construction. Grows the public ABI by exactly `createRenderer`.
- **9b — resource lifetime and correctness.** Texture caching and deletion through `GlObjectRegistry`,
  `PreparedFrame` snapshotting each `Geometry`'s maps, sticker quad sizing from `DecodedImage` dimensions,
  populating `SceneGeometry.consumerTextures`, and the untyped-`error(...)` decision. Adds no public ABI.

The largest task in the cycle, and the one that makes RenG operable.

**PRECONDITION — do not start this task until it is met.** The factory needs Cycle C's resource driver and
Cycle D's GL seam in ONE worktree. Those cycles were built on separate branches, and that split has already
produced a duplicate `ResidentCache` and a narrowed set of GLB class gates when a dependency crossed it.
Confirm both are integrated before writing a line, and if they are not, stop and report — building the
factory against half the tree would repeat a mistake this project has already paid for twice.

**Files:**
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/RendererFactory.kt`
- Create: `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/RenGRenderer.kt`
- Modify: `kmp/api/kmp.klib.api`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/reng/RendererFactoryTest.kt`

**Interfaces:**
- Produces: `public fun createRenderer(configuration: RendererConfiguration): Renderer`, and an `internal class RenGRenderer : Renderer`.

- [ ] **Step 1: Write the failing setup-failure tests**

Setup throws `RenGException` rather than returning a result, for consistency with `prepare`, which returns a
`PreparedFrame` directly and therefore must throw.

```kotlin
@Test
fun setupWithoutACurrentContextIsATypedFailure() {
    val failure = assertFailsWith<RenGException> { createRenderer(configurationWithNoCurrentContext()) }
    assertEquals(RenGErrorCode.NO_CURRENT_RENDER_CONTEXT, failure.code)
}

@Test
fun aTypedSetupFailureCarriesNoDriverTextAtAll() {
    val failure = assertFailsWith<RenGException> { createRenderer(configurationWithNoCurrentContext()) }
    val rendered = failure.toString() + failure.message.orEmpty()
    assertFalse(rendered.contains("Mesa", ignoreCase = true))
    assertFalse(rendered.contains("Apple", ignoreCase = true))
    assertFalse(rendered.contains("GL_", ignoreCase = false))
}
```

The second test matters more than it looks. A factory is where a developer most wants to echo the driver
string back for debugging, and the redaction rule has no exception for setup.

- [ ] **Step 2: Write the failing basemap warn-and-degrade test**

```kotlin
@Test
fun requestingABasemapWithNoConfiguredStyleWarnsOnceAndKeepsDrawing() {
    val sink = RecordingDiagnosticSink()
    val renderer = createRenderer(configurationWith(basemapStyle = null, diagnosticSink = sink))
    repeat(3) { renderer.draw(preparedFrameWith(drawBasemap = true), target) }
    assertEquals(1, sink.diagnostics.count { it.code == DiagnosticCode.BASEMAP_NOT_CONFIGURED },
        "warn once per renderer, never once per frame")
}
```

Once per renderer, never per frame — a per-frame warning floods a render loop and trains consumers to ignore
the sink.

- [ ] **Step 3: Write the failing record-do-not-fetch test**

```kotlin
@Test
fun setupPerformsNoConsumerExchangeAtAll() {
    val transport = CountingTransport()
    createRenderer(configurationWith(basemapStyle = someLocator, transport = transport))
    assertEquals(0, transport.callCount, "the style locator is recorded at setup and acquired at first prepare")
}
```

This is what makes the synchronous signature honest. A setup that fetched would have to suspend, and a
library whose defining claim is purity must not perform network I/O in its constructor.

- [ ] **Step 4: Implement**

`createRenderer` captures the already-current context's identity via `adoptRenderContext`, queries the
profile, creates the offscreen surface, compiles the composite, sticker and geometry pipelines, and
constructs `RenGRenderer` holding the resource driver, the resident cache, the program cache and the object
registry.

**A sticker's quad is currently the wrong size, and this task owns the fix.** `CONTEXT.md` specifies a
Sticker as "a centred local XY quad whose width and height are the image's pixel dimensions", with
screen-anchored scale meaning output pixels per local unit and sticker local dimensions being
encoded-image pixels. Task 5's `STICKER_QUAD` is a fixed **unit square**, and nothing threads per-image
dimensions into the draw — so at scale 1.0 a sticker renders one pixel across instead of its image's size.
Task 8 flagged this rather than patching it, because the fix needs `DecodedImage`'s width and height
threaded from wherever the texture is uploaded, which is the wiring this task builds. Bake the image's
pixel dimensions into the resolved sticker so the quad matches the specification. No test in this cycle
can catch this — pixel verification is deferred to Cycle J — so it is invisible until a consumer sees a
one-pixel sticker.

**Decide on Task 8's draw-time re-resolution — but for the right reason.** `SceneContent` calls
`resolvePlacement` and `resolveGeometry` at DRAW time because no pre-resolved spatial plan is plumbed
through yet. A reviewer established that **divergence is not the risk**: only per-object resolution is
re-derived (the camera arrives already resolved), and both resolvers are pure and deterministic, so
recomputing on identical inputs cannot disagree with `FRAME_PLANNING`'s result.

The real exposure is narrower and is the live-`Map` hazard above: re-resolution only diverges if a
`Geometry` or `Placement` can change between `prepare()` and `draw()`. Close that by snapshotting, and
re-resolution becomes merely redundant work rather than a correctness risk.

One thing does need your decision. The draw-time resolution failure path is a bare `error(...)` — an
untyped `IllegalStateException` — rather than a `RenGException`. `drawFrame`'s `try`/`finally` means it
cannot defeat GL state restoration, so the worst case is an untyped exception surfacing past `drawFrame`
into consumer code. That contradicts RenG's typed-failure contract at exactly the boundary a consumer
touches. Decide whether to convert it, and say which and why.

**Texture lifetime is this task's, and without it the MVP leaks GPU memory every frame.** Neither the
sticker nor the geometry pipeline caches or deletes its uploaded textures — every draw call re-uploads
through `uploadTexture`, which calls `genTextures` each time. Nothing deletes them. Task 7 could not fix
this because no resource-driver wiring exists anywhere yet, and the components that should own it are
exactly the ones this task constructs: `GlObjectRegistry` for the GL handles and `ResidentCache` keyed by
`ResourceKey` for content. Wire uploaded textures through the registry so a repeated draw reuses its
texture and renderer close deletes it. A per-frame `genTextures` with no matching delete exhausts GPU
memory in seconds of real rendering and is invisible to every test in this cycle.

`PreparedFrame` must snapshot each `Geometry`'s `uniforms` and `textures` rather than retaining the live
`Map` references. Task 7 could not do this either — `PreparedFrame` is still a bare interface with zero
implementations, so there was no snapshot point to write into. It took a defensive `.toMap()` on entry to
`drawGeometry`, which guards only against mutation *during* one call, and documented a
no-mutation-after-construction contract on `Geometry`. The real fix is yours. Those maps are the caller's own objects and are not
defensively copied; a mutation between `prepare()` and `draw()` would otherwise render content that differs
from what the frame's identity hashed.

Leak discipline: if any step after the first allocation fails, delete everything already created before
throwing. Cycle D's `createOffscreenSurface` handles this by deferring every error check to a single point
after all allocations, so no intermediate early return exists — follow that shape rather than checking after
each step.

`RenGRenderer` implements the `Renderer` sealed interface. Its `close()`, `freeResources()` and
`notifyGpuObjectsGone()` delegate to Cycle D's `GlLifecycleDriver`, whose `forgetWithoutDeleting()` already
calls both `registry.forgetEverything()` and `programs.forgetAll()` — the cross-dialect invalidation that
`GlProgramCache`'s dialect-omitting key depends on. Do not reimplement that logic here.

- [ ] **Step 5: Regenerate and review the ABI dump**

Expected additions: `createRenderer` and nothing else — `RenGRenderer` is `internal` and must not appear.
`Renderer` is already public and sealed, so its members do not change. Any other line in the diff is out of
scope.

- [ ] **Step 6: Run every gate and commit**

```bash
git commit -m "feat(kmp): add the renderer factory"
```

---

## Task 10: ADR 0024, the cycle gates, and the documentation

**Files:**
- Create: `docs/adr/0024-order-the-two-draw-regimes.md`
- Modify: `CLAUDE.md`, `CONTEXT.md`, `HANDOFF.md`, `docs/decomposition.md`

- [ ] **Step 1: Write ADR 0024**

Record the decision — map regime depth-tested first, screen regime composited on top as one stack — and the
alternative that was rejected and why: splitting the screen regime by sign of z-index would overload the
sign with regime meaning while the magnitude kept ordering meaning, so `z = -5` and `z = -3` would order
correctly relative to each other while both silently jumping behind the map.

**Record the silent-rename hazard in the same ADR**, because it belongs with the shader interface it governs:
ADR 0008 binds a documented name only when the compiled program declares it, so renaming one later leaves
consumer shaders compiling and drawing without that value. It is the one class of breaking change that does
not announce itself.

- [ ] **Step 2: Add the shader interface to `CONTEXT.md`**

`CONTEXT.md` currently has no shader-interface vocabulary. Add the six documented names with their types and
meanings, with `uGeometryBounds` explicitly marked informational and unsuitable for placement arithmetic.
Follow the file's existing format, including the `_Avoid_:` lines.

- [ ] **Step 3: Record the reordering in `HANDOFF.md` and `docs/decomposition.md`**

Existing cycle letters stay bound to their existing content so no prior reference breaks. Record that Cycle F
splits into F-1 and F-2, that Cycle E splits across the basemap and terrain halves, and state the execution
order the spec fixes.

- [ ] **Step 4: Run every gate this repository can run on this host**

Use `CLAUDE.md`'s exact commands. Report actual output, and separate the three tiers honestly:
execution-verified here, compile-only, and CI-only. **Do not claim any Linux gate passed** — `linuxX64Test`
cannot run on a macOS host.

- [ ] **Step 5: Commit**

```bash
git commit -m "docs: record the draw-regime decision and the cycle reordering"
```

---

## Self-Review

**Spec coverage.** Every decision in the spec maps to a task: draw-regime ordering to Tasks 5, 8 and 10;
premultiplied alpha and its data-texture exception to Tasks 4 and 7; the factory to Task 9; `drawBasemap`
warn-and-degrade to Task 9 Step 2; scope to the absence of any model task; the shader interface to Tasks 6
and 10; consumer data to Tasks 3 and 7; the `CancelRoute` carry-in to Task 1.

**Two spec items are deliberately not tasks.** Pixel verification is deferred to Cycle J by owner decision,
so no task captures or compares an image. Uniform arrays are excluded, so no task implements them.

**One consequence the spec did not anticipate**, discovered while writing Task 3: growing `Geometry` forces
the canonical frame encoding to change, because unencoded uniforms would let two semantically different
plans share a frame identity and serve a stale frame to an animating consumer. That is now Task 3 Step 3,
and it invalidates the frozen Cycle B test vector, handled by appended erratum in Step 4.

**A second consequence**, discovered while scanning the GL seam: `GlBinding` lacks `uniform2f`, `uniform3f`
and `uniform1ui`, which the documented interface requires. That is Task 2, and it touches all four platform
bindings plus Cycle D's fixed entry-point roster.

**Type consistency.** `ShaderValue`'s six variants are named identically in Tasks 3 and 7.
`TextureContent.IMAGE`/`DATA` is used consistently in Tasks 4, 5 and 7. `RESERVED_SHADER_NAMES` is defined in
Task 6 and consumed in Task 7. `InternalPipelineRole.STICKER` is added in Task 5 and used nowhere else.

**Ordering dependency worth restating.** Task 7 validates reserved names against a set Task 6 defines, so
between Tasks 3 and 7 a colliding `Geometry` is constructible. That is acceptable within a cycle because
nothing ships mid-cycle, and the alternative — defining the names in Task 3, before the pipeline that gives
them meaning — would scatter one decision across two files.
