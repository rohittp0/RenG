# Place GL implementations by measured source-set visibility

ADR 0009 states that "platform GL bindings are invisible from shared source sets — a file in `iosMain`
cannot resolve `platform.gles3` while the identical file in `iosArm64Main` can — so GL code lives in leaf
source sets behind `expect`/`actual`". On the Kotlin version this repository now builds with, that is
false, and it is the single fact that decides Cycle D's source-set layout. This ADR supersedes that
sentence. Every other claim in ADR 0009 stands: the binding choice per target, the reason hand-written
cinterop against the Apple SDK is dangerous, the reason Linux gets runtime resolution, and the principle
that the seam speaks in types both sides can implement.

The correction was measured, not reasoned. A file placed in `kmp/src/iosMain/kotlin` importing
`platform.gles3.glGetError` compiled cleanly under `:kmp:compileKotlinIosArm64`. Both iOS targets ship
the klib, so the source-set intersection contains it, and the shared set resolves it.

The layout that follows has four implementations rather than five: one in `iosMain` serving both iOS
targets, one in `macosArm64Main`, one shared by both Linux targets for the `dlopen`/`eglGetProcAddress`
path, and one in `androidMain` over `GLES30`. macOS and iOS can never share regardless of visibility,
because `platform.OpenGL3` and `platform.gles3` are different packages and Kotlin has no conditional
import — so the two remaining near-duplicates are irreducible. At that scale, a build-time generator for
the entry-point table costs more than the duplication it removes, and a per-leaf `typealias`
indirection would save one file while betting that all eighty-four signatures are byte-identical when
twelve were checked.

The seam is typed at Android's width because Android is the narrower side wherever the platforms
disagree: `Int` for object names and enums, `Boolean` and `BooleanArray` for GL booleans, `IntArray` for
integer outputs, `Int` for buffer sizes, `ByteArray?` for pixel and vertex data, and a single `String` for
shader source. That last one is not a preference — Android exposes exactly `glShaderSource(int, String)`
with no count and no length array, so the seam cannot offer the `char**` form even though both
Kotlin/Native klibs do. Typing the seam in `UInt` instead would place a conversion on the one
implementation with no zero-cost way to perform it and would leak a type the JVM side cannot honour,
whereas `Int` to `UInt` on the native side is free. All marshalling asymmetry — `memScoped` arenas,
`usePinned`, copy-back of output arrays — stays inside the implementations, and every representative call
in that mapping was executed against a real llvmpipe context with `glGetError` returning zero afterwards.

One caveat is deliberately left open rather than closed by this record. The probe resolved a single
zero-argument entry point. That a shared source set also resolves `memScoped`, `CValuesRef` marshalling
and the pointer-output calls is likely but unproven, so Cycle D re-verifies with a real translation object
compiled for both iOS targets before the layout is committed. If that fails, the fallback is ADR 0009's
original leaf-source-set arrangement, at the cost of one duplicated file kept in sync by review.
