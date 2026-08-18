# Fix the supported GLB subset

RenG parses glTF 2.0 binary with its own container reader and its own strict JSON reader, and admits a
subset written down here rather than discovered during implementation. Every accepted feature is a
compatibility commitment and every rejection is a file some consumer cannot load, which is the reasoning
ADR 0010 applies to published targets: widening later is a compatible change, narrowing is not.

The split between the two ordered class gates is about which true statement the consumer is told, not
about difficulty. `PARSE_GLB` owns every check whose failure means the bytes are not a well-formed glTF
2.0 binary asset — the container, the JSON document, `asset.version`, index references, all accessor and
buffer-view arithmetic, the node hierarchy being disjoint strict trees, and RenG's own rule that
animation names be unique when non-blank. `VALIDATE_GLB_FEATURES` owns every check whose failure means
the document is valid and outside what RenG draws. The consequence that matters is that `PARSE_GLB` must
tolerate anything the specification permits even when RenG will refuse it: an accessor with no
`bufferView` is legal, means all zeros, and is the signature of a Draco-compressed asset, so treating it
as malformed would report corruption for a file whose real problem is an unsupported extension.

The subset is driven by what a `Model` is — one placement, one GLB, an optional base-colour texture
override, and animation tracks sampled at a time. Accepted: component types 5120–5123, 5125 and 5126;
triangles only; `POSITION` required, `NORMAL`, `TEXCOORD_0`, `TANGENT` and `COLOR_0` optional, with
`TANGENT` and `COLOR_0` parsed and otherwise unused because exporters emit them by default and ignoring
an optional attribute changes no pixel; indexed and non-indexed primitives; node `matrix` or TRS but
never both; arbitrary breadth and bounded depth; `scene` present, or absent with exactly one scene;
multiple mesh-bearing nodes and multiple primitives per mesh; the full `pbrMetallicRoughness` block plus
the four secondary texture slots and the alpha and cull state, parsed and retained because a texture
override is specified to preserve every other material property; embedded PNG images; `byteStride` and
interleaved attributes; translation, rotation and scale animation channels with `LINEAR` and `STEP`
interpolation.

Rejected, each with the gate that owns it: sparse accessors, strips and fans and point and line modes,
`TEXCOORD_n` and `COLOR_n` above zero, joints and weights, skins, morph targets, `weights` animation
targets, JPEG images, any non-empty `extensionsRequired`, and any buffer or image carrying a `uri`. The
`uri` case is the purity case and is stronger than convenience: a resource named inside a GLB has no
**Resource Locator**, no **Resource Class**, no selected limit, and no place in the operation's route
set, and RenG cannot resolve a relative reference because a locator is opaque text RenG never parses as a
URL. There is no correct behaviour available and drawing an untextured model would be a silent fallback.
`data:` URIs are rejected too, on the narrower ground that they duplicate the embedded-buffer path for
something any producer can convert; that one is a scope judgement rather than a purity requirement.
`CUBICSPLINE` is rejected in this cycle rather than approximated, because substituting `LINEAR` would
silently change motion, and accepting it later is purely additive behind the same gate and code.

Three container rules are stricter than the specification's wording and are adopted deliberately. The
declared total length must equal the actual byte count rather than merely not exceed it, which collapses
truncation, an inflated length, a misaligned length, a file ending inside a chunk header, and appended
garbage into one comparison. An unknown chunk in the second position is rejected rather than scanned
past, because the specification permits extension chunks only after the first two. And every byte after
the JSON document's closing token must be exactly `0x20`, since chunk length includes padding and nothing
else would catch a producer that pads with tabs — tab being JSON whitespace, an ordinary reader accepts
it silently.

RenG writes its own JSON reader because it cannot borrow one. Rentile exposes no parser, and
`kotlinx-serialization-json`, though already resolved transitively, is compile-visible only on the native
targets. The reader classifies number tokens by spelling so that every field the specification types as
an integer is read only from an integer form — `9007199254740993` parsed as a `Double` is silently off by
one — rejects duplicate member names because first-wins and last-wins readers would disagree about what a
document means, rejects lone surrogates at both the escape and UTF-8 layers because `AnimationSelector`
could never address such a name, and never substitutes a replacement character, since that is repair.

Bounded input does not imply a bounded parse, so the JSON chunk carries its own configurable ceiling
separate from the whole-GLB ceiling: a boxed value tree was measured at roughly 27× its text, so a
256 MiB chunk would demand six to seven gigabytes on every target. Accessor arithmetic is checked in
`Long` before any array exists, JSON nesting and node-hierarchy depth are bounded by fixed non-configurable
constants, and no time budget is imposed because the parse is linear in an already-bounded input and a
wall-clock limit would make failure depend on the host.

The subset is reasoned from the specification and RenG's own vocabulary rather than measured against a
corpus. Running the container and JSON layers over the Khronos sample models, and counting what the
subset would reject and why, is the check most likely to move a row from reject to accept, and it is
owed before the first release that draws a model.
