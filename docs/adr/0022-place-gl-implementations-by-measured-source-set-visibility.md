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

The layout is confirmed by a full translation object, not by the original one-line probe. A twelve-method
seam in `iosMain` compiled for both iOS targets, and its class appears in both produced klibs from that one
shared file. It exercised `memScoped`; `allocArray` over `IntVar`, `UByteVar`, `ByteVar` and
`CPointerVar<ByteVar>`; `usePinned` with `addressOf` on `IntArray` and `ByteArray`; `.cstr` with `ptr`
against the enclosing scope; `toKString` on both an allocated buffer and a reinterpreted `glGetString`
return; `reinterpret` in both directions the seam needs; `Int`-to-`UInt` and `UByte`-to-`Boolean`
conversion; and null pass-through for an optional pointer. `compileIosMainKotlinMetadata` passed too, which
matters because the shared-source-set metadata compilation resolves against commonized cinterop libraries
and is the compilation most likely to reject platform GL. The same body compiles from `linuxMain` over
`platform.posix`, and `macosMain` resolves `platform.OpenGL3`, so each implementation sits in the shared set
above its targets rather than in a leaf.

`appleMain` was measured in both directions and can host neither: a `platform.gles3` file there compiles for
both iOS targets and fails only at `compileKotlinMacosArm64`, and a `platform.OpenGL3` file fails at
`compileKotlinIosArm64`. The klibs confirm it — `ios_arm64` ships the `OpenGLES*` family and no `OpenGL3`,
`macos_arm64` ships `OpenGL3` and no GLES. The iOS and macOS near-duplication is therefore irreducible.

That measurement also exposes a trap worth carrying forward: platform-library resolution for a shared source
set is enforced per leaf compilation rather than by pre-computing an intersection, so a misplaced GL file
fails at one specific target's compile task and a partial compile can look green. Every target must be
compiled to trust the layout.
