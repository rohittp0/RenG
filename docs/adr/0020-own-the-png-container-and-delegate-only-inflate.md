# Own the PNG container and delegate only inflate

RenG decodes PNG with its own container reader in `commonMain` — signature and chunk walk, per-chunk
CRC validation, `IHDR` admission against an explicit accepted subset, palette and transparency handling,
and scanline filter reversal — over one internal `expect`/`actual` seam that supplies streaming inflate
and CRC-32 only. That seam has exactly two implementations: the bundled `platform.zlib` klib on the five
native targets, and `java.util.zip.Inflater` with `java.util.zip.CRC32` on Android. RenG does not use
Skia for decode, and does not write its own inflate.

Skia was rejected on measured behaviour, not on weight. It is already in the dependency graph — Rentile
pins `org.jetbrains.skiko:skiko` strictly on all six targets — so choosing it would have added no new
coordinate. Real decodes against `skiko-linuxx64` showed three disqualifying behaviours. A sixteen-bit
grayscale sample came back truncated to eight bits with no error and no diagnostic, which is a quality
question for imagery and silent data corruption for elevation. A stream truncated mid-image-data with no
end marker decoded successfully and returned pixels, which is repair, and RenG never repairs. And a
chunk CRC mismatch and a corrupt payload both surfaced as one opaque `Error in input`, leaving no way to
distinguish `RESOURCE_DECODE_FAILED` from `UNSUPPORTED_RESOURCE_FEATURE` without pre-parsing the file
anyway. It was also not faster: 5.38 ms per iteration against 5.15 ms for zlib plus a Kotlin unfilter on
the same 512×512 tile.

Inflate is not a thing RenG has to implement, which is what changed the answer. A `zlib` platform klib
exists for all five native targets and exports the full streaming interface, not just the one-shot
helper — which matters because a PNG splits its zlib stream across arbitrarily many image-data chunks.
A spike walked chunks, validated CRCs with zlib's own `crc32`, streamed across split image data and
unfiltered scanlines with byte-exact results on a Linux host and as an emulated aarch64 binary, and the
same source compiles for all three Apple targets. A complete pure-Kotlin RFC 1950/1951 inflate was also
written and passed ten vectors, so it is a proven reserve if a future target ever lacks zlib — but it ran
4.8× slower than zlib with an unboxed sink, and a subtle Huffman bug there produces wrong pixels rather
than an error.

The accepted subset is deliberately narrow: colour types 0, 2, 3, 4 and 6 at bit depth 8 only. Sixteen-bit
samples, sub-byte palette depths and Adam7 interlace are rejected with `UNSUPPORTED_RESOURCE_FEATURE`.
Everything decodes to one canonical form — tightly packed RGBA8, unpremultiplied, no row padding, with
palette and grayscale widened losslessly — so there is one upload path per platform, one form in the
resource report's decoded-byte accounting, and no endianness rule. Rentile's `MAPBOX` and `TERRARIUM`
DEM encodings are both eight-bit, so nothing RenG needs is excluded, and every one of these rejections
is purely additive to widen later.

Two consequences bind the rest of the design. RenG decodes PNG even for a purely vector basemap, because
Rentile rasterises with Skia and returns encoded PNG as its only rendered-tile output. And the
decoded-size ceiling is decided from the declared image dimensions before any pixel buffer is allocated,
never after decompression, because an encoded ceiling admits a compression ratio that turns into far
more decoded bytes.

The cost accepted here is the container code itself, including the filter reversal that is the easiest
place to introduce an off-by-one, plus two platform inflate seams that must stay behaviourally
identical. Both are gated by one shared vector suite of byte literals run in `commonTest` on every
target, which is cheap precisely because the vectors are literals.
