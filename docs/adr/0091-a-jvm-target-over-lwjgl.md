# A JVM target over LWJGL

RenG published six targets: Android, `iosArm64`, `iosSimulatorArm64`, `macosArm64`, `linuxX64` and
`linuxArm64`. Its main consumer, Geoanimator, ran every GPU readback test it has through a Kotlin/Native
macOS harness, and an edit-then-test loop there cost about five minutes, most of it in Kotlin/Native
compiling and linking a 63 MB test binary. A spike on 2026-11-12 put RenG on the JVM over LWJGL with a
headless CGL context. Every frame it read back was byte-identical to the Kotlin/Native frame: the same
`commonMain`, the same Apple driver and the same context attributes, with only the marshalling of each GL
call differing.

## Decision

`:kmp` gains a seventh target, `jvm()` (JVM 21), published as `kmp-jvm` alongside the other six.

- **The GL binding is `JvmGlBinding`**: all of `GlBinding` over LWJGL's `GL11C` to `GL42C`, modelled on
  `AndroidGlBinding`. ADR 0001 is unchanged: the consumer creates the context, makes it current and calls
  `GL.createCapabilities()`; RenG never does. LWJGL takes no array offset and needs off-heap byte payloads,
  so a short `count` and every `ByteArray` go through a copy. That is irrelevant to tests and would matter
  only if a consumer exported video through this binding.
- **LWJGL is an `implementation` dependency** (`org.lwjgl:lwjgl` and `org.lwjgl:lwjgl-opengl`, 3.4.2). No
  LWJGL type reaches the public ABI, and RenG ships no natives: the consumer, who owns the context, brings
  the natives for the platform it runs on.
- **The `java.*` actuals live once**, in a `jvmAndAndroidMain` source set that both `androidMain` and
  `jvmMain` depend on: hashing, inflate, the calling thread and the lock.
- **The Apple core-profile readback gates run on both runtimes.** `MacosGlConformanceTest` moves to a
  `cglTest` source set that both `macosTest` and `jvmTest` depend on, with `openCglCoreProfileContext` as its
  one `expect`. So `:kmp:jvmTest` runs the same 23 readback gates as `:kmp:macosArm64Test`, against the same
  CGL pixel formats. It fails, rather than skips, when no default context or native library is available;
  the software-renderer cases keep their existing out-loud skip when that renderer is absent.
  `jvmTest` puts LWJGL's `natives-macos-arm64` jars, and the Skia natives Rentile's JVM variant
  rasterises basemap tiles with (`skiko-awt-runtime-macos-arm64`, at the skiko Rentile pins), on its
  runtime classpath only.
- **What stays Kotlin/Native-only:** `nativeTest`. `runLabelIntegrationReadbackSuite` lives there, so
  `MacosLabelIntegrationConformanceTest` keeps that one gate in `macosTest`. Most `nativeTest` classes are
  there only because Skia used to be linked by the native targets alone; three use Kotlin/Native workers
  directly. Moving the rest to a source set the JVM shares is possible and is not done here.
- `tools/check_repository_policy.py` admits the target, the two catalog coordinates, the `jvmMain`
  dependency block, the test-runtime natives block and the JVM ABI dump (`kmp/api/jvm/kmp.api`, which
  must not leak Rentile or LWJGL types), each pinned exactly; `verify_publication.py` expects
  `kmp-jvm`; and `consumer-smoke` declares `jvm()`, so the resolution proof covers seven targets.

## Consequences

- A release publishes eight publications (the root and seven targets), and `kmp-jvm` must resolve like the
  rest.
- The JVM GL gate needs macOS on Apple Silicon. CI's Ubuntu job does not run `jvmTest`; the macOS job is
  where it belongs. A Linux JVM would need LWJGL's EGL path and a different rasteriser (llvmpipe), and is
  not attempted.
- Two runtimes now run the same Apple readback gates. A disagreement between them is a marshalling defect
  in one of the two bindings, never a driver difference, and is worth treating as such.
