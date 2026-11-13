# External frame stickers are latched in RenG's render context

This decision adds a draw-time source to `Sticker` and changes no sticker that does not name one.

Video decoders ordinarily render into a `Surface` backed by a `SurfaceTexture`, then expose the result as
`GL_TEXTURE_EXTERNAL_OES`. That keeps the decoded frame on the GPU and lets the driver perform YUV-to-RGB
conversion. Passing each frame through `Transport` as PNG, ETC2 or raw RGBA would instead copy and encode the
pixels, and a texture or `SurfaceTexture` created in another context cannot simply be sampled by RenG. The
external source therefore enters RenG at draw time, on RenG's GL thread with RenG's context current.

## Public contract

`ExternalFrame(sourceId, frame)` identifies one source sample. `Sticker.external`, appended after `transient`,
optionally carries that identity. `Sticker.image` remains required: preparation acquires and decodes it normally,
it determines the sticker's dimensions, and it is the poster drawn whenever the external sample cannot be used.

A consumer registers an `ExternalFrameSource` by id on `Renderer`. Registration and unregistration are callable
from any thread; their GL work is reconciled at the next draw. Before a registered source's first latch, RenG
creates a texture name and calls `attach(textureName)`. `latch(frame)` then makes that sample current and returns
an `ExternalFrameLatch` containing its `ExternalTextureTarget`, texture name and column-major 4x4 texture-coordinate
transform. A null latch means “not ready”, not a failed frame. Unregistering or replacing an attached source calls
`detach()` and deletes RenG's texture name on the GL thread. Closing a renderer does the same for every attached
source before deleting the renderer's other GL objects.

Declared GPU object loss is different: ADR 0015 says `notifyGpuObjectsGone()` is context-free and means the GL
objects already do not exist. RenG forgets attached texture names without trying to call GL or consumer GL code;
the retained registrations attach afresh after context adoption and their next use.

## Preparation and draw

Preparation carries the external identity alongside the ordinary prepared sticker and does not contact the
source. The fallback image follows the same acquisition, validation, accounting and transient rules as before.

At draw, a sticker without a consumer fragment program looks up its source, attaches it once if necessary and
latches its frame. One draw keeps a map keyed by the whole `ExternalFrame`, including null results, so every
sticker naming the same `(sourceId, frame)` shares one latch call. This also makes motion-blur sub-samples that
name one video frame share that frame. An unknown id, a null latch, an unsupported target, or an unavailable
external-OES program draws the prepared image instead and does not fail the frame.

ADR 0087 consumer fragment programs remain image programs. When a sticker has both `shader` and `external`, RenG
does not attach or latch the external source for that sticker; it draws `image` through the consumer program exactly
as before. Supporting external textures in consumer programs would require a separate shader interface and is not
part of this decision.

## Programs and GL state

The built-in sticker vertex shader now applies `rengStickerTextureTransform` to `(u, v, 0, 1)`. Ordinary stickers
and consumer sticker programs receive the identity transform, preserving their texture coordinates. A transformed
`TEXTURE_2D` latch uses the ordinary built-in fragment program.

`EXTERNAL_OES` uses a second built-in fragment program with
`GL_OES_EGL_image_external_essl3 : require` immediately after the version directive and a
`samplerExternalOES rengStickerTexture`. This program is compiled only on its first use. If the context does not
advertise `GL_OES_EGL_image_external_essl3`, or compilation or linking fails, the sticker uses its image and this
renderer does not try the OES program again for that context. The directive belongs to RenG's built-in program;
ADR 0087's refusal of extension directives in consumer sticker programs is unchanged.

Both variants apply sticker opacity after sampling and use the same premultiplied blend modes as the original
program. Driver-converted video is opaque RGB, so multiplying the sampled value by opacity produces the same
premultiplied output rule.

RenG samples every external texture on unit zero. The external-OES path binds `GL_TEXTURE_EXTERNAL_OES`, draws,
unbinds that target and restores the ordinary sticker program. The draw's lazy texture-unit capture independently
saves and restores both `GL_TEXTURE_2D` and external-OES bindings on any unit RenG touches, so a source callback or
external draw cannot leak either binding into the consumer's state.

## Consequences

- Decoded video can stay in the decoder/driver path without travelling through RenG's byte transport.
- The consumer owns decoder timing and the source's non-GL objects; RenG owns the attached texture name and the
  exact GL thread and context on which every source callback runs.
- A fallback is always prepared, even when the external source is ready on every draw. This costs the ordinary
  poster resource but keeps readiness and capability failures deterministic.
- The public data-class ABI stays append-only: `transient` is component 6 and `external` is component 7.
- `TEXTURE_2D` makes the platform-neutral contract testable on desktop GL. The OES program is compiled in an
  Android device test and is skipped there with an explicit reason when the extension is absent.
