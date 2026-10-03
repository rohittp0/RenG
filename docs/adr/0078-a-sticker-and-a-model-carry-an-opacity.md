# A Sticker and a Model carry an opacity

A consumer may fade a Sticker or a Model without rewriting its image or material. Both public types
therefore carry an opacity in the closed range zero through one, defaulting to one. Non-finite values
and values outside that range are rejected at construction. Sticker also rejects negative zero so
its data-class equality cannot distinguish values that canonical identity encoding treats alike;
Model canonicalizes negative zero to zero. Sticker texels are already premultiplied, so its shader
multiplies the complete sampled `vec4` by opacity. A Model first applies the glTF
opaque-or-material-alpha rule, multiplies that alpha by opacity, and multiplies its lit RGB by the
same final alpha. The value written by either path remains premultiplied.

A Model below full opacity is routed with alpha-blended primitives: it draws back-to-front, after
opaque primitives, with depth writes disabled. This is necessary even when its glTF material says
`OPAQUE`, because model opacity has made the resulting primitive translucent.

The appended constructor parameters are source-compatible but binary-breaking on Kotlin/Native;
consumers must recompile against `0.6.0-local8`. Serialization surrogates use the same defaults, so
older documents decode unchanged. Canonical encoding omits Sticker tag 3 and Model tag 5 while
opacity is one (and Sticker blend is `OVER`), so a plan using only the new defaults keeps the exact
identity it had before these fields.
