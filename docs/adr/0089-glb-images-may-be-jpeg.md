# GLB images may be JPEG

ADR 0021 refused `image/jpeg` as `IMAGE_MEDIA_TYPE` because one model of 41 carried a JPEG texture and
RenG had no JPEG decoder. The consumer's catalogue has since grown JPEG-textured models it cannot
re-export (a dashboard-supplied `Military_Man1.glb`, two baseline 4:2:0 JPEGs of 1024 and 480 pixels).
A refused model does not degrade: `VALIDATE_GLB_FEATURES` fails the whole frame's preparation with
`UNSUPPORTED_RESOURCE_FEATURE`, so a project containing one stops playing at the first frame that
reaches the model. JPEG is one of the two image types core glTF 2.0 defines.

## Decision

`validateGltfFeatures` admits an embedded image whose `mimeType` is `image/png` or `image/jpeg`.
Anything else -- no `mimeType`, `image/webp`, `image/ktx2` -- stays `IMAGE_MEDIA_TYPE`, and a `uri`,
`data:` included, stays `EXTERNAL_URI` exactly as ADR 0021 decided.

`ModelDecode` decodes each image by its declared type: PNG through the existing decoder, JPEG through a
new pure-Kotlin `decodeJpeg` in `commonMain`. Bytes that are not the declared format are `Malformed`, as a
mislabelled PNG already was. Both produce the same canonical `DecodedImage` (unpremultiplied RGBA8, top
row first); a JPEG's alpha is 255. Everything downstream -- the eager model-image decode, CPU admission and
reservation from a header-only projection before allocation, `TextureContent.IMAGE` upload with
premultiplication, sampler and `alphaMode` handling -- is unchanged and format-blind.

The decoder admits baseline and extended-sequential Huffman (SOF0/SOF1) and progressive (SOF2) files with
8-bit samples, one (greyscale) or three components (YCbCr, or RGB under an Adobe transform of 0 or
`R`/`G`/`B` component ids), sampling factors 1-4 that divide the frame maximum, and restart intervals. It
reports lossless, hierarchical and arithmetic-coded files, 12-bit samples, CMYK/YCCK, and DNL-deferred
heights as `Unsupported`. The IDCT is libjpeg's integer islow algorithm, colour conversion libjpeg's
fixed-point BT.601 full-range transform, and 2x chroma uses libjpeg's triangle upsampling; against
libjpeg-turbo 3.2.0 the output is within 2 levels on the test fixtures and 3 on the 1024-pixel model
texture. EXIF orientation is ignored, because glTF addresses texels by stored rows.

The decoder treats its input as hostile, and is total: every fault is a typed result.

- Huffman length tables are checked against the Kraft inequality before any table is built.
- Progressive scans must advance ITU T.81 G.1.1.1's successive approximation per component and
  coefficient (a first scan only of uncoded coefficients, a refinement only at the bit the last scan
  left, AC only after DC, a sequential component once), and an image carries at most 64 scans, so
  decode work is a bounded multiple of the admitted raster.
- A scan may consume at most 16 bits past its real data; restart markers must run `RST0`..`RST7` in
  order exactly where an interval ends; bytes between segments must be markers or fill; the file must
  end in EOI; and every component's DC must have been coded. A truncated or damaged texture is refused
  as `Malformed` rather than drawn part grey.
- Three components are ordered by id (JFIF 1/2/3, 0/1/2, `R`/`G`/`B`), and by frame order otherwise.
- The header projection runs the decoder's own marker parser, so it can never read a frame header the
  decoder does not; when it yields nothing, `ModelDecode` refuses the image without decoding it.
- An exception the validation did not anticipate is caught at the boundary as `Malformed`.

One implementation in `commonMain` rather than a platform codec per target: Linux has no system decoder,
and one integer decoder gives every platform the same texels, which is what lets frame comparison stay
exact across targets.

## Consequences

- The consumer's JPEG-textured models prepare and draw on every target RenG builds.
- A 1024x1024 JPEG decodes in about 45 ms on a desktop JVM, comparable to a PNG of the same size; it is
  paid once per model decode, as a PNG is.
- Decode working memory is bounded before allocation: the raster against `maximumDecodedImageBytes`, and
  the raster plus two bytes per coefficient and one per sample of every component plane against
  `maximumImageDecodeWorkingBytes`.
- `MODEL_TEXTURE` overrides, stickers, backdrops and geometry textures still accept PNG (and ETC2 for
  stickers) only; this ADR covers images embedded in a GLB.
