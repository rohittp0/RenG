# Stickers accept a raw RGBA container

Geoanimator exports user video clips by decoding each video frame and handing RenG the full-resolution
frame -- 1080x1920, say -- as a sticker image, once per output frame. A PNG costs a deflate per frame at
the producer and an inflate per frame in RenG, twice (the class gate and acquisition), which is far too
slow at 30 frames a second; an ETC2 container (ADR 0084) is fast to upload but costs quality, and
encoding it is no cheaper. The producer already holds the pixels as RGBA. A sticker image may therefore
also be a raw RGBA container. As with ETC2, nothing else may: a backdrop and a model texture stay PNG-only
and refuse it with `RESOURCE_DECODE_FAILED`.

## The container

Content type `image/x-reng-rgba`. A 16-byte little-endian header: ASCII `RRGB`; `u8` version 1; `u8`
flags, where bit 0 says the texels are already premultiplied and every other bit must be zero; two zero
bytes; `u32` width and height, both at least 1. Then exactly `width * height * 4` bytes of sRGB-encoded
RGBA8, top row first, with nothing after them. An unknown version or flag bit is unsupported, any framing
fault -- a wrong length, a zero extent, a nonzero reserved byte -- is malformed, and both fail as
`RESOURCE_DECODE_FAILED`, as a bad PNG does. The length check bounds both extents by the pixels present
before multiplying, so no header can overflow it.

A premultiplied flag rather than a fixed convention because a video decoder hands over straight alpha (and
usually opaque frames, where the two are the same bytes), while a producer compositing its own frames may
already hold premultiplied ones. Either way RenG uploads premultiplied texels, as ADR 0084 and the PNG
path do.

## Told by magic, admitted as a view

`DECODE_STICKER_IMAGE` (ADR 0084) sniffs `RRGB` before `RET2`; anything else is still decoded as a PNG.
The verdict depends on the bytes alone, never the response's content type, because ADR 0060 remembers
passes by digest. Validation is the framing and the decoded-image ceiling, measured as `width * height * 4`;
there is nothing else to fail. `maximumStickerImageBytes` (32 MiB) still bounds the response, which a
1080x1920 frame (about 8 MiB) is well inside.

Acquisition admits the container as a `RawRgbaImage`: a view of the texels inside the stored raw resource's
immutable snapshot. Nothing is copied and nothing is attached to the resident generation (ADR 0059 does
not apply), so `decodedCpuBytes` reports zero for it, and the bytes are charged once, through the frame's
lease, as the raw resource.

## The upload

One `GL_RGBA8` level, allocated with `glTexImage2D` and filled with `glTexSubImage2D` from the snapshot at
offset 16, through the same bounded 1 MiB workspace the PNG path uses: `GlBinding` takes no array offset,
and the workspace is what avoids a full-raster copy. A premultiplied container is copied into the workspace
as it is; a straight one goes through the PNG path's own `(c * a + 127) / 255`, so a raw image uploads
bit-for-bit what a PNG of the same pixels uploads. The sampler is the sticker one, linear and clamped, and
no mip level is generated. The texture's upload identity is a PNG sticker's; the two never meet, because a
generation is one form or the other.

## Consequences

- No public API changed. `ResourceClass.STICKER_IMAGE`'s accept value is now
  `image/x-reng-etc2, image/x-reng-rgba, image/png`.
- A raw sticker costs no inflate and no decode anywhere: the gate and acquisition read a header, and the
  draw pays one premultiply (or copy) and the upload, once per generation.
- It costs its full RGBA size as raw bytes in the resident cache and on the wire from the consumer's
  `Transport`. A video frame used once should also be a transient sticker (ADR 0093), or it will crowd
  out everything else the cache holds.
