# Geometry textures decode on a GPU miss

A phone workload with 64 text-label `Geometry`s and a roughly 47 MiB route PNG held about 95 MiB
against a 96 MiB resident CPU budget: each generation charged its encoded PNG plus decoded RGBA.
Frame close then evicted whole generations, so the next frame repeated Store/Transport, SHA-256,
generation installation, GPU upload and PNG decode. Measured preparation rose from 13 ms to 55 ms,
GPU work doubled, and garbage collection consumed 22% of CPU time.

## Decision

A `Geometry.textures` PNG remains encoded through preparation. Its prepared texture holds the raw
bytes from the frame-leased resident generation plus width and height read from IHDR alone by `pngHeaderDimensions` (not `prescanPng`, which CRC-checks every chunk and would cost a full pass over every geometry texture per frame).
Preparation neither decodes it nor calls `ResidentCache.attachDecoded`. The frame's lease continues
to pin the generation, and therefore those bytes, until the prepared frame closes.

`ContentTextureCache` first leases the allocation identified by owner key, `ResidentGenerationId`,
subresource and upload variant. Only a miss runs the upload lambda; that lambda decodes the leased
PNG with the ordinary image decode limits and uploads it. A cache hit does not decode. Forgetting GPU
objects, including context loss, makes the next draw decode and upload again from the still-leased
bytes. A rejected decode becomes the same sanitized `RESOURCE_DECODE_FAILED` /
`RESOURCE_DECODING` failure used by the former preparation path.

The policy is occurrence-specific, not a new `MODEL_TEXTURE`-wide rule. Stickers, ground images,
shader-backdrop textures and model textures keep their existing eager decode and resident attachment.
If a resident key is needed by both a Geometry and an eager consumer in one frame, the eager consumer
still causes the generation to retain decoded pixels. GPU identity remains generation-based rather
than digest-based, so a retired generation can never regain GPU objects.

## Consequences

- A Geometry texture pays one PNG decode per GPU residency, on the GL thread at the first draw or
  after GPU eviction/context loss, instead of during preparation and while already GPU-resident.
- Resident and prepared-frame CPU accounting charge encoded bytes only for Geometry-only textures.
  `decodedCpuBytes` is therefore zero for them, matching `ResourceLimits`' encoded-resource wording;
  every other resource class keeps its existing accounting.
- GPU allocation and texture-budget sizing still use the IHDR width and height without inflating the
  PNG.
- Resident eviction stays LRU, Store/Transport flow is unchanged, and generation installation still
  mints a new identity whenever content is reinstalled.
