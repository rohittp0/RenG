# Stickers accept an ETC2 container

Travel Animator plays an animated GIF as a sticker by handing RenG one `STICKER_IMAGE` locator per GIF
frame. As PNGs, every frame is inflated twice on the CPU (once by the class gate, once by acquisition),
premultiplied on the CPU, and uploaded as RGBA8, and then has no mip chain, so a GIF drawn small
aliases. Already-compressed frames avoid all of that: on a Mali-G615, uploading a 480x270 ETC2 frame
took 0.08 ms against 0.25 ms for the same frame as RGBA8, and the blocks are a quarter of the bytes on
the GPU and in the resident cache. A sticker image may therefore be an ETC2 container as well as a PNG.
Nothing else may: a backdrop and a model texture stay PNG-only and refuse a container with
`RESOURCE_DECODE_FAILED`, exactly as they refuse any other non-PNG.

## The container

Content type `image/x-reng-etc2`. A 16-byte little-endian header: ASCII `RET2`; `u8` version 1;
`u8` format (1 ETC2 RGB8, 2 ETC2 RGB8 punch-through alpha, 3 ETC2 RGBA8 EAC); `u8` level count in
`1..floor(log2(max(w, h))) + 1`; `u8` zero; `u32` width and height, both at least 1. The levels follow
back to back with nothing after them; level `i` is `max(1, w >> i)` by `max(1, h >> i)` texels in whole
4x4 blocks of 8 (formats 1, 2) or 16 (format 3) bytes. The texels are sRGB-encoded and already
premultiplied by the producer -- format 1 is opaque and format 2's transparent texels decode to
`(0, 0, 0, 0)` by the specification -- so, unlike a PNG, nothing in RenG multiplies them. We wrote our
own header rather than adopting KTX because the producer is ours and this is all a sticker needs: no
key-value data, cube faces, array layers, or alignment padding to validate. An unknown version or format
is unsupported, any framing fault (including a level count the bytes do not match exactly) is
malformed, and both fail as `RESOURCE_DECODE_FAILED`, as a bad PNG does.

## Told by magic, admitted as a view

`STICKER_IMAGE` gets a class gate of its own, `DECODE_STICKER_IMAGE`, which sniffs the first four bytes:
`RET2` is validated as a container, anything else is decoded as a PNG. The verdict depends on the bytes
alone, never on the response's content type, because ADR 0060 remembers passes by content digest. A
container's validation is its framing and the decoded-image ceiling and nothing else: every bit pattern
of an ETC2 or EAC block decodes to some texels, so there is nothing further to fail. The ceiling is
measured as every level's RGBA8 size, which is what the CPU path below allocates at most.

Acquisition admits a container as a `CompressedImage`: a view of the levels inside the stored raw
resource's immutable snapshot. Nothing is copied and nothing is attached to the resident generation;
re-reading a 16-byte header each frame is cheaper than a memo, and the bytes are already charged to
the prepared frame through its lease. `decodedCpuBytes` reports zero for it, which is true. A
sticker's quad is sized by level zero's declared pixels, never its block-padded size.

## The upload, and the context that cannot sample ETC2

Where the context samples ETC2 -- every ES 3.0 context, and a desktop one advertising
`GL_ARB_ES3_compatibility` -- each level goes to `glCompressedTexImage2D` straight from the snapshot
(a new entry point, the roster's ninety-third, which takes an offset into the array for this). Apple's
desktop GL 4.1 cannot. There RenG decodes each level on the CPU, a bounded run of whole block rows at a
time (at most 1 MiB, or one block row), into immutable RGBA8 storage. The decoder implements every mode
of the ES 3.0 specification's appendix C.1 -- individual, differential, T, H, planar, punch-through and
EAC alpha -- and the GL conformance suite checks it texel for texel against every real driver that can
sample ETC2.

The CPU path is not one of the fallbacks CLAUDE.md forbids. It is chosen from the adopted context's
profile before anything is uploaded, it gives the same pixels the GPU would, and nothing failed to make
RenG take it; a context that samples ETC2 never reaches it, and one that cannot never tries blocks first.
Which path ran is part of the texture's upload identity (`TextureStorage`), so a texture uploaded for one
can never be reused as the other, and its GPU bytes are what is resident: the blocks, or `w * h * 4`
per level.

Either way the chain is the container's. `GL_TEXTURE_MAX_LEVEL` is set to its last level, which keeps a
partial chain complete; minification is `GL_LINEAR_MIPMAP_LINEAR` when there is more than one level and
`GL_LINEAR` otherwise; and `glGenerateMipmap` is never called. A producer that wants a sticker to
minify cleanly ships the levels.

## Measured

`Etc2StickerBenchmarkTest` prepares and draws 300 frames of a 480x272 sticker whose locator changes
every frame -- the cost an animated sticker pays -- on macOS, which takes the CPU path:

Apple M2 Pro, 720x1280 output, 290 frames after a 10-frame warm-up, means in ms (p95 in brackets):

| frames | bytes a frame | prepare | draw (incl. upload, `glFinish`) | CPU decode alone |
|---|---|---|---|---|
| PNG, deflated | 113,065 | 7.75 (8.09) | 3.67 (4.08) | -- |
| ETC2 RGB8, 1 level | 65,296 | 0.46 (0.57) | 10.13 (10.61) | 8.96 |
| ETC2 RGB8, 9 levels | 87,192 | 0.49 (0.58) | 13.14 (13.50) | 11.91 |

Prepare falls 17x: a PNG frame is inflated by the gate and again by acquisition, a container's header is
read twice. On macOS the decode moves to the draw and costs more than the inflate it replaced; these are
Kotlin/Native debug test binaries, so absolute CPU figures overstate a release build. Where the context
samples ETC2 -- every phone -- there is no decode and no premultiply at all, and the draw pays only the
block upload.

## Consequences

- No public API changed. `ResourceClass.STICKER_IMAGE`'s accept value is now
  `image/x-reng-etc2, image/png`; a consumer that ignored accept keeps working.
- `ResourceClassGate` has a fourth member, and the GL entry-point roster a ninety-third.
- An ETC2 sticker costs no CPU premultiply and no CPU decode on any GPU that samples it; on macOS it
  costs a decode at upload, once per generation.
- The CPU path is RenG's only ETC2 decoder. The conformance check is what keeps it honest: it is the
  one place a FAIL means the decoder and a driver disagree.
