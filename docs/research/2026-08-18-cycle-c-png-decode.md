# PNG decode across the six targets — Cycle C spike, 2026-08-18

Cycle C cannot be specified without settling how RenG turns PNG bytes into pixels. The decomposition
records the decision as open, with the framing "Skiko is proven on these targets but heavy, and a pure
Kotlin decoder needs an inflate implementation". Both halves of that framing turned out to be wrong in
ways that change the answer: Skiko is not a new cost at all, because Rentile already pins it strictly on
every target; and inflate is not something RenG has to implement, because every target already ships one.

Everything below separates what was executed from what was reasoned. Measurements were taken on this
Linux x86-64 development host with the Kotlin/Native 2.3.21 prebuilt, `android-37.0`'s `android.jar`,
JDK 21.0.10, and artifacts fetched over HTTPS from Maven Central, `https://maven.rohittp.com`, and the
JetBrains Compose dev repository. All spike code lives in the session scratchpad under
`spike/` and is deliberately not committed, per this repository's convention for throwaway spikes.

## Verified environment facts

| Fact | Command that produced it |
|---|---|
| A `zlib` platform klib exists for `ios_arm64`, `ios_simulator_arm64`, `macos_arm64`, `linux_x64`, and `linux_arm64` | `ls ~/.konan/kotlin-native-prebuilt-linux-x86_64-2.3.21/klib/platform/<target>/` |
| Each of those klibs exports `inflateInit_`, `inflateInit2_`, `inflate`, `inflateEnd`, `inflateReset`, `inflateReset2`, `uncompress`, `uncompress2`, `crc32`, and `zlibVersion`, plus `Z_OK`, `Z_STREAM_END`, `Z_NO_FLUSH`, `Z_BUF_ERROR`, `Z_DATA_ERROR`, and `ZLIB_VERSION` | `klib dump-metadata .../klib/platform/<target>/org.jetbrains.kotlin.native.platform.zlib` |
| Total external function count is 72 on the three Apple targets, 71 on `linux_x64`, 70 on `linux_arm64`; the only difference relevant to inflate is that `MAX_WBITS` is absent on both Linux targets | `grep -cE '^ *public final external fun ' zlib_<target>.txt` |
| The klib manifest carries `linkerOpts=-lz` and `depends=stdlib …posix` on every target | `cat .../org.jetbrains.kotlin.native.platform.zlib/*/manifest` |
| Kotlin/Native bundles `libz.a` and `libz.so.1.2.11` in both its `x86_64-unknown-linux-gnu` and `aarch64-unknown-linux-gnu` sysroots, so `-lz` needs nothing from the host | `find ~/.konan/dependencies -name 'libz*'` |
| The `ZLIB_VERSION` header constant differs by target — `1.2.11` on Linux, `1.2.12` on `macos_arm64` — while the linked runtime on this host reports `1.3`, and `inflateInit_` accepted that mismatch | `grep ZLIB_VERSION zlib_*.txt`; spike run output |
| One Kotlin source using `platform.zlib` compiles to a klib for `linux_arm64`, `macos_arm64`, `ios_arm64`, and `ios_simulator_arm64` from this Linux host | `kotlinc-native -target <t> -produce library -o lib_<t> spike.kt` |
| The same source builds and runs as a native executable on `linux_x64`, and as an `aarch64` executable under the bundled qemu against the bundled sysroot | `kotlinc-native -target linux_x64 -o spike_linux_x64 spike.kt && ./spike_linux_x64.kexe`; `qemu-aarch64 -L <aarch64 sysroot> ./spike_linux_arm64.kexe` |
| `java.util.zip.Inflater` in `android-37.0` exposes `Inflater(boolean)`, `setInput(byte[],int,int)`, `inflate(byte[],int,int)`, `needsInput`, `needsDictionary`, `finished`, `getRemaining`, `reset`, `end`; `java.util.zip.CRC32` exposes `update(byte[],int,int)` and `getValue` | `javap -classpath /home/user/android-sdk/platforms/android-37.0/android.jar java.util.zip.Inflater java.util.zip.CRC32` |
| `org.jetbrains.skiko:skiko` newest on Central is `0.150.1`, last updated `20260714110512` | `curl https://repo1.maven.org/maven2/org/jetbrains/skiko/skiko/maven-metadata.xml` |
| `com.rohittp.rentile:kmp:0.1.5` declares `org.jetbrains.skiko:skiko` with `{"strictly":"0.148.2","requires":"0.148.2"}` on **all six** of RenG's targets — in `ApiElements` for the five native targets and in `RuntimeElements` only for `android` | `curl https://maven.rohittp.com/com/rohittp/rentile/kmp-<target>/0.1.5/kmp-<target>-0.1.5.module` then reading each variant's `dependencies` |
| The same appears in the Maven POMs as `skiko-linuxx64` at `compile` scope and `skiko-android` at `runtime` scope | `curl .../kmp-linuxx64-0.1.5.pom`; `curl .../kmp-android-0.1.5.pom` |
| `skiko-android` does not exist on Maven Central at any version (`maven-metadata.xml` is HTTP 404) nor on Google's Maven; it resolves only from `https://maven.pkg.jetbrains.space/public/p/compose/dev` | `curl -I` against all three repositories for `skiko-android/0.148.2` and `0.150.1` |
| RenG's own `settings.gradle.kts` and `consumer-smoke/settings.gradle.kts` already declare that Compose dev repository, scoped with `includeGroup("org.jetbrains.skiko")` | `cat settings.gradle.kts consumer-smoke/settings.gradle.kts` |
| Skiko `0.148.2` native klib sizes: `macosarm64` 34,007,359 B; `linuxx64` 47,073,694 B; `linuxarm64` 46,879,030 B; `iosarm64` 57,634,037 B; `iossimulatorarm64` 57,762,562 B | `curl -sSLI https://repo1.maven.org/maven2/org/jetbrains/skiko/skiko-<t>/0.148.2/…klib` |
| Rentile's Android AAR is 21,956,575 B and bundles `jni/arm64-v8a/libskiko-android-arm64.so` (30,391,808 B) and `jni/x86_64/libskiko-android-x64.so` (29,940,144 B) — those two ABIs only | Python `zipfile` listing of the cached `kmp.aar` |
| `skiko-android`'s own AAR contains `classes.jar` and no `.so`; `org.jetbrains.skia.Codec` is present in it | `zipfile` listing; `javap -classpath classes.jar org.jetbrains.skia.Codec` |
| Rentile's only rendered-tile output is `RenderedTile.pngBytes: ByteArray`; `ValidatedDemTile` carries raw `bytes` plus `TerrainDemEncoding`, whose only entries are `MAPBOX` and `TERRARIUM` | `klib dump-metadata` on the cached `kmp-linuxX64Main-0.1.5.klib` |
| Cycle B already routes `BASEMAP_RASTER_TILE`, `BASEMAP_DEM_TILE`, `STICKER_IMAGE`, and `MODEL_TEXTURE` through `ResourceClassGate.DECODE_PNG`, with `BASEMAP_SPRITE_IMAGE` decoding inside the sprite-pair path | `kmp/src/commonMain/kotlin/com/rohittp/reng/internal/resource/ResourceOperationProtocol.kt` lines 604–634 |

## Inflate is already solved on all six targets

The `zlib` platform klib is present for every native target RenG publishes, and it is a full streaming
interface rather than the one-shot `uncompress` helper. That matters because PNG splits its zlib stream
across an arbitrary number of `IDAT` chunks, so a one-shot call would require concatenating them first.

The spike (`spike/spike.kt`, 185 lines including its test vectors) walks a PNG's chunks, validates every chunk CRC with zlib's own
`crc32`, feeds each `IDAT` payload into a single `inflateInit_`/`inflate`/`inflateEnd` stream, and reverses
the PNG scanline filters. Against five hand-built PNGs generated by `spike/make_png.py` it produced
byte-exact expected output:

```
zlib runtime version: 1.3  (header ZLIB_VERSION=1.2.11)
rgb8_2x2 (single IDAT, filter 0)                    PASS
rgb8_filtered_split (3 IDATs, Sub then Up)          PASS
dem_rgb8_ancillary (pHYs before, tEXt after IDAT)   PASS
gray16_2x1 (16-bit grayscale)                       PASS
rgb8_stored (level 0: stored deflate blocks)        PASS
corrupted IDAT payload    -> chunk CRC false, inflate = -3 (Z_DATA_ERROR)
ALL POSITIVE CASES PASS
```

The `rgb8_filtered_split` case is the one that matters most: the zlib stream is cut into three `IDAT`
chunks at arbitrary byte offsets and the streaming calls stitch it back together without the decoder
buffering the whole compressed payload. The corrupted case shows zlib reporting a data error rather than
producing plausible-looking pixels, which is exactly the behaviour RenG's no-repair contract needs.

That binary ran natively on `linux_x64` and, unmodified, as an `aarch64` executable under the bundled
qemu with identical output. The same source compiles to a klib for `macos_arm64`, `ios_arm64`, and
`ios_simulator_arm64` from this host, which proves the Kotlin-visible API surface is identical across
them. It does **not** prove Apple linking or execution — see the unverified list.

On Android the equivalent is `java.util.zip.Inflater`, whose full streaming surface is present in
`android-37.0`. `spike/JvmInflate.java` runs the identical five vectors plus the corruption case through
it on JDK 21.0.10 and reports `ALL POSITIVE CASES PASS`, with the corrupted stream raising
`java.util.zip.DataFormatException: incorrect data check`. That was executed on the JVM, which is what
`testAndroidHostTest` also uses; that ART's `Inflater` behaves the same is an inference from it being the
same libcore-over-zlib implementation, not a measurement, and belongs in the Cycle H device pass.

So the platform split is one `expect`/`actual` pair with exactly two implementations — `platform.zlib`
for `nativeMain` and `java.util.zip` for `androidMain` — not six.

## Skiko is not a new dependency, and that is not the reason to reject it

The framing that has governed this decision treated Skiko as a heavy thing RenG might take on. It is
already taken on. `com.rohittp.rentile:kmp:0.1.5` declares `org.jetbrains.skiko:skiko` as a **strict**
`0.148.2` on all six targets, and this repository's build already carries the extra repository that makes
it resolvable. Skiko's klibs are in this machine's Gradle cache from the Cycle B builds of 2026-08-17,
and the 60 MB of Skia `.so` in an Android consumer's APK arrives inside Rentile's own AAR.

Two asymmetries in that constraint matter for Cycle C:

- On the five native targets Skiko is in `ApiElements`, so it is already on RenG's compile classpath
  transitively. On `android` it is in `RuntimeElements` only, so RenG's Android source set **cannot**
  compile against `org.jetbrains.skia` today without declaring the dependency itself.
- `skiko-android` is absent from Maven Central and from Google's Maven. It resolves only from the
  JetBrains Compose dev repository, which is why RenG's `settings.gradle.kts` and `consumer-smoke`
  already whitelist that repository for `org.jetbrains.skiko`. Under ADR 0010's reasoning a missing
  target is a hard resolution failure; here the failure is avoided only by a non-Central repository that
  every consumer must add. That burden exists today because of Rentile, but a RenG decode path built on
  Skiko would make RenG a first-party reason for it rather than a bystander.

Consequently, if Cycle C used Skiko it would have to **declare `org.jetbrains.skiko:skiko` directly** in
`kmp/build.gradle.kts` rather than leaning on Rentile's transitive constraint. Relying on another
library's `strictly` for a compile-time dependency of RenG's own is not acceptable: a Rentile patch
release may move that pin, and `strictly` means RenG could not override it without a resolution conflict.
Recording the version in RenG's own version catalogue is the only honest form of that dependency.

## What Skia measurably does and does not do

Because the `skiko-linuxx64` klib is already cached, the spike linked it directly with the Kotlin/Native
CLI (`kotlinc-native -l skiko-linuxX64Main-0.148.2.klib`) and ran real Skia decodes on this host. That is
the strongest evidence in this note, because it replaces speculation about Skia's behaviour with output.

| Input | Skia `Codec` result (measured) |
|---|---|
| 2×2 8-bit truecolour | `BGRA_8888/OPAQUE` source; `RGBA_8888/UNPREMUL` readback `ff0000ff 00ff00ff 0000ffff ffffffff` — exact |
| 3×1 8-bit truecolour with `pHYs` before and `tEXt` after `IDAT` | `010203ff fafbfcff 000000ff` — exact, ancillary chunks ignored |
| 2×1 palette with `tRNS` | `ff0000ff 0000ff80` — exact, alpha unpremultiplied as requested |
| 4×4 Adam7 interlaced truecolour | all sixteen pixels correct |
| 2×1 **16-bit grayscale** | source reported as `GRAY_8`; samples `0x1234` and `0xFFFE` came back as `0x12` and `0xFF` — **silently truncated to 8 bits** |
| same, requesting `R16G16B16A16_UNORM` | throws `Invalid conversion: The generator cannot convert to match the request` |
| chunk with a deliberately wrong CRC, valid payload | throws `IllegalArgumentException: Error in input` |
| corrupted `IDAT` payload | throws `IllegalArgumentException: Error in input` |
| stream truncated mid-`IDAT` with no `IEND` | **decoded successfully** and returned pixels |

Three of those rows are decisive against Skia as RenG's decoder:

1. **Silent 16-bit truncation.** A 16-bit sample becomes 8 bits with no error and no diagnostic. For
   image content that is a quality question; for elevation it is silent data corruption.
2. **Silent tolerance of a truncated stream.** RenG's contract says malformed input fails and RenG never
   repairs. Skia repaired. Even if Skia produced the pixels, RenG would still have to walk and validate
   the container itself to keep that promise, which means writing the chunk layer anyway.
3. **Undifferentiated failures.** Both a CRC mismatch and a corrupt payload surface as
   `IllegalArgumentException: Error in input`. RenG's failure vocabulary distinguishes
   `RESOURCE_DECODE_FAILED` from `UNSUPPORTED_RESOURCE_FEATURE`, and there is no way to derive that
   distinction from a single opaque message without pre-parsing the file.

Skia's colour management is a further hazard rather than a measurement: `ImageInfo` carries a
`ColorSpace?`, and the decodes above passed `null`, which is why the bytes came back untransformed. What
Skia would do with a PNG carrying `iCCP` or `gAMA` was not tested and would need pinning per platform.

Performance does not favour Skia either. On the same 512×512 RGBA tile (455,899 B encoded, 1,048,576 B of
pixels):

| Path | Measured |
|---|---|
| Skia `Codec` decode plus copy-out | 5.38 ms/iter |
| zlib `inflate` alone | 2.58 ms/iter |
| Kotlin scanline unfilter alone | 2.57 ms/iter |
| zlib + Kotlin unfilter, total | 5.15 ms/iter |

Skia buys nothing measurable here, while costing exactness, error fidelity, and a dependency RenG would
have to own.

## The pure-Kotlin inflate is real but unnecessary

To size the "no platform dependency" option honestly, the spike includes a complete RFC 1950/1951
inflate in pure Kotlin — stored blocks, fixed Huffman, dynamic Huffman with the code-length alphabet and
its 16/17/18 repeat codes, the full length and distance base/extra tables, the 32 KB back-reference
window, the zlib header check, and the Adler-32 trailer. It is **173 lines** including the unboxed
variant, and it passes ten vectors generated by Python's `zlib` at levels 0, 1, 6, and 9 with
`Z_FIXED`, `Z_RLE`, and `Z_HUFFMAN_ONLY` strategies, including a 65,120-byte output and a 32,768-byte
maximum-distance case:

```
empty PASS  stored_level0 PASS  fixed_huffman_short PASS  fixed_huffman_rle PASS
dynamic_text PASS  dynamic_random_4k PASS  long_match_64k PASS  rle_strategy PASS
huffman_only PASS  max_distance PASS
PURE-KOTLIN INFLATE: ALL 10 VECTORS PASS
```

So "a pure Kotlin decoder needs an inflate implementation" costs roughly 200–400 production lines once
bounded output, a streaming entry point, and typed errors are added. The reason not to do it is speed and
risk, not size: on the tile payload it ran 13.87 ms/iter with an unboxed `ByteArray` sink and 64.48
ms/iter with the boxed `MutableList<Byte>` sink, against zlib's 2.58 ms — 4.8× and 22× respectively. It
also introduces a novel correctness surface on a format where a subtle Huffman bug produces wrong pixels
rather than an error, in exchange for avoiding a library that is present on every target already.

It is worth keeping as a documented reserve. If a future target ever lacks a system zlib, this is the
fallback, and the vector-generation approach above is how it would be gated.

## What RenG actually has to decode

Rentile's public surface settles part of the subset question. `RenderedTile.pngBytes` is Rentile's only
rendered-tile output, so **RenG must decode PNG even for a purely vector basemap** — Rentile rasterises
with Skia and hands back an encoded PNG. Those tiles are Skia's own encoder output, which narrows their
feature set to something predictable, but RenG cannot assume it, because the same decoder also has to
accept raster tiles from arbitrary servers and sticker and texture PNGs from arbitrary consumers.

`ValidatedDemTile` carries raw encoded `bytes` plus a `TerrainDemEncoding` whose only entries are
`MAPBOX` and `TERRARIUM`. Both encode elevation in three 8-bit channels, so **Rentile's DEM path does not
require 16-bit support**, and the 16-bit question is about DEM sources RenG might accept beyond
Rentile's two encodings. What both encodings do require is bit-exact 8-bit channel values with no
premultiplication, no scaling, and no colour transform — an alpha premultiply or a gamma adjustment on a
Terrain-RGB tile silently changes elevations. Android's `BitmapFactory.Options` exposes `inPremultiplied`,
`inScaled`, `inDensity`, `inTargetDensity`, and `inPreferredColorSpace`, every one of which would have to
be defeated, and `Bitmap.Config` has no unsigned 16-bit-per-channel entry (`RGBA_F16` is half-float), so
the platform image decoders are the wrong tool for elevation regardless of which one is chosen.

## Recommendation

**Own the PNG container in common Kotlin; delegate only inflate and CRC-32 to the platform.**

- `commonMain` contains the whole decoder: signature and chunk walk, per-chunk CRC validation, `IHDR`
  admission against an explicit accepted subset, `PLTE`/`tRNS` handling, scanline filter reversal,
  16-bit sample handling, and typed failures mapped onto Cycle B's existing `RESOURCE_DECODE_FAILED` and
  `UNSUPPORTED_RESOURCE_FEATURE` codes. This is pure, testable in `commonTest`, and identical on every
  target — which also means golden pixels from a decode are cross-platform comparable in a way rendered
  pixels never will be.
- One internal `expect`/`actual` seam supplies streaming inflate and CRC-32: `platform.zlib`
  (`inflateInit_`, `inflate`, `inflateEnd`, `crc32`) for the five native targets, `java.util.zip.Inflater`
  and `java.util.zip.CRC32` for `android`. Two actuals, both proven here on real vectors.
- Do **not** use Skia for decode, even though it costs no new coordinate. It silently truncates 16-bit
  samples, silently accepts a truncated stream, cannot distinguish its failure causes, and is no faster.
- Do **not** write a pure-Kotlin inflate now. Record it as the fallback if a target ever lacks zlib.

The trade-offs of accepting this: RenG takes on the PNG container code it would otherwise borrow, roughly
400–600 lines with its tests, including the filter reversal that is the easiest place to introduce an
off-by-one. It also takes on two platform inflate seams that must be kept behaviourally identical, which
needs a shared vector suite run in `commonTest` on both — cheap, since the vectors are byte literals. In
exchange it gets bit-exactness, one failure vocabulary, and no first-party dependency on a coordinate
that is missing from Maven Central.

## What remains unverified

- **Apple linking and execution.** The Kotlin source compiles to klibs for `macos_arm64`, `ios_arm64`,
  and `ios_simulator_arm64` from this host, but final linking against Apple's `libz` needs Xcode on
  Apple Silicon, and no Apple binary was run. The `-lz` linker option and Apple SDKs' `libz.tbd` make
  this very likely to work; it is an inference. `macosArm64Test` in `apple-publication`, or a draft
  pull request, is the cheapest proof.
- **Android runtime, as opposed to the JVM.** `java.util.zip.Inflater` was exercised on JDK 21, and its
  presence in `android-37.0` was confirmed by `javap`. ART execution was not observed; that is Cycle H's
  device pass, or an `androidHostTest` for the JVM half.
- **`linux_arm64` on real hardware.** The aarch64 run was under qemu against Kotlin/Native's bundled
  glibc 2.25 sysroot with zlib 1.2.11, not on an ARM64 Linux machine with the distribution's zlib.
- **Behaviour against a real tile corpus.** No Rentile-produced tile, real raster tile, or real DEM tile
  was decoded here; every PNG in the spike was hand-built. What Skia's encoder actually emits — bit
  depth, colour type, whether it ever emits `iCCP` — was not measured and should be before the accepted
  subset is frozen.
- **zlib version skew.** The header constant differs by target (`1.2.11` versus `1.2.12`) and the linked
  runtime here reported `1.3`. `inflateInit_` accepted the mismatch on this host because zlib compares
  only the major version, which is documented behaviour rather than something this spike proved in
  general.
- **Skia's colour management.** All Skia decodes here passed a `null` colour space. A PNG carrying
  `iCCP`, `sRGB`, or `gAMA` was not tested.
- **Memory ceilings.** Nothing here measured peak allocation for a decode, which is what the resident
  and decoded byte ceilings in `ResourceLimits` will have to be reasoned against.

## What Cycle C's spec must decide

1. Whether it accepts this recommendation: common-Kotlin container plus a two-actual inflate seam, with
   Skia rejected for decode and pure-Kotlin inflate held in reserve.
2. Whether `org.jetbrains.skiko:skiko` becomes a declared RenG dependency for any purpose, and if so,
   whether the version lives in RenG's version catalogue rather than being inherited from Rentile's
   `strictly` pin — and what happens if a Rentile release moves that pin.
3. The exact accepted `IHDR` matrix: colour types 0, 2, 3, 4, 6 at which bit depths. Recommended
   starting point — 8 and 16 for colour types 0, 2, 4, and 6; 8 only for palette, rejecting palette bit
   depths 1, 2, and 4 with `UNSUPPORTED_RESOURCE_FEATURE` until a real corpus demands the sub-byte
   unpacking path. Widening later is a compatible change; narrowing is not.
4. Whether 16-bit samples are accepted at all, and separately whether **DEM** may be 16-bit. Rentile's
   `MAPBOX` and `TERRARIUM` encodings are both 8-bit RGB, so 16-bit DEM is a choice, not a requirement.
   If it is accepted, the spec must fix the in-memory layout handed to the GL layer, since PNG samples
   are big-endian.
5. Adam7 interlace: accept or reject. Recommended reject with a typed
   `UNSUPPORTED_RESOURCE_FEATURE` failure, because no source RenG needs uses it, seven-pass reassembly
   is a distinct code path, and accepting it later is compatible. The spec must say so explicitly rather
   than leaving it to the implementation, since Skia accepts it and a consumer sticker could carry it.
6. `tRNS` semantics per colour type — permitted for 0, 2, and 3, rejected for 4 and 6 — and whether
   palette expansion happens in the decoder or later in the resource layer.
7. Ancillary chunk policy: skip all non-critical chunks, but still validate their CRC. Explicitly state
   that `gAMA`, `iCCP`, `sRGB`, and `cHRM` are ignored and no colour transform is ever applied, since
   that is precisely where RenG differs from a platform decoder.
8. Unknown **critical** chunks are rejected; unknown ancillary chunks are skipped. Say which, so an APNG
   `acTL`/`fcTL`/`fdAT` file decodes as its base frame rather than failing by accident.
9. The exhaustive rejection list, since RenG never repairs: wrong signature; `IHDR` not first or not 13
   bytes; `IEND` not last; any trailing byte after `IEND`; chunk length exceeding the remaining bytes;
   any chunk CRC mismatch; compression method other than 0; filter method other than 0; a scanline
   filter byte above 4; interlace method other than 0 (subject to item 5); missing `PLTE` for colour
   type 3; `PLTE` present for colour types 0 and 4; a zlib preset dictionary; Adler-32 mismatch; a
   deflate stream that ends before or continues past the exact expected raw size; zero width or height;
   and dimensions whose decoded size exceeds the configured ceiling.
10. Which `RenGErrorCode` each rejection maps to — malformed bytes to `RESOURCE_DECODE_FAILED`,
    well-formed but out-of-subset features to `UNSUPPORTED_RESOURCE_FEATURE`, oversize to
    `RESOURCE_LIMIT_EXCEEDED` — and that no decode diagnostic ever carries a locator or adapter text.
11. Where the decoded-size ceiling is enforced: from `IHDR` before any allocation, not after inflating.
    A 32 MiB encoded ceiling admits a decompression ratio that turns into far more decoded bytes.
12. The decoded pixel form the cache and GL layer consume — channel order, alpha state (unpremultiplied,
    given DEM), row padding — and whether palette and grayscale inputs are widened at decode time or
    kept in their source form.
13. The vector suite that gates both inflate actuals, and whether the decoder's golden outputs are
    checked in as byte literals in `commonTest` so `linuxX64Test`, `macosArm64Test`, and
    `testAndroidHostTest` all run the same assertions.
14. That the DEM terrain-encoding validation named by Cycle B's `VALIDATE_DEM_TERRAIN_ENCODING` gate runs
    on decoded samples and states which encodings it admits — no RenG source names `MAPBOX` or
    `TERRARIUM` yet.
15. Whether `crc32` comes from the platform (zlib and `java.util.zip.CRC32`, both confirmed present) or
    from a small common-Kotlin table, which would remove one function from the platform seam.
