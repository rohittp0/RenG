# The Khronos sample corpus, run against the supported GLB feature subset

ADR 0021 closes with an admission: the accepted/rejected subset for `VALIDATE_GLB_FEATURES` was reasoned
from the glTF 2.0 specification and RenG's own vocabulary, not measured against a corpus, and calls
running the Khronos sample models through it "the check most likely to move a row from reject to accept
… owed before the first release that draws a model." This document records that check having been run,
and its result: no row moved.

## What was run

`KhronosGroup/glTF-Sample-Assets` was sparse-checked-out to `Models/*/glTF-Binary` — 118 `.glb` files,
about 413 MB — into scratch space (not committed). Each file was piped through RenG's own implemented
pipeline, unmodified: `scanGlb` (container) → `parseGltf` (`PARSE_GLB`) → `validateGltfFeatures`
(`VALIDATE_GLB_FEATURES`), via a temporary, uncommitted JVM test that was deleted after the run. No
fixture, gate, or reject code was changed to make the corpus pass; this measured the subset as
implemented, not a tuned version of it.

## Result

```
total files: 118
SUPPORTED                              52
GltfUnsupported.IMAGE_MEDIA_TYPE       35
GltfUnsupported.EXTENSION_REQUIRED     19
GltfUnsupported.ATTRIBUTE_SEMANTIC      9
GltfUnsupported.ANIMATION_TARGET_PATH   1  (AnimatedColorsCube -- KHR_animation_pointer "pointer" path)
GltfUnsupported.MORPH_TARGET            1  (AnimatedMorphCube)
GltfUnsupported.INTERPOLATION           1  (InterpolationTest -- CUBICSPLINE)
```

`MULTIPLE_BUFFERS`, `EXTERNAL_URI`, `SPARSE_ACCESSOR`, `ACCESSOR_WITHOUT_BUFFER_VIEW`, `PRIMITIVE_MODE`,
`SKIN`, `SCENE_AMBIGUOUS`, and `NORMALIZED_NOT_PERMITTED` occurred zero times: GLB packaging naturally
avoids external- and multi-buffer shapes and multi-scene documents, and none of the sample assets in this
corpus use sparse accessors, alternate primitive topologies, or a bare skin index without the
corresponding joint/weight attributes.

## Every rejection traces to an already-documented decision

- **`IMAGE_MEDIA_TYPE` (35, the largest bucket)** — JPEG base-colour or PBR textures. ADR 0021 fixes PNG
  as the one supported embedded image media type; many of the corpus's PBR-feature comparison and demo
  models ship JPEG.
- **`EXTENSION_REQUIRED` (19)** — Draco, sheen, transmission, `KHR_materials_variants`, and other
  required compression/material extensions. ADR 0021's blanket `extensionsRequired` rule is exactly
  built to cover this without staying current with each individual extension.
- **`ATTRIBUTE_SEMANTIC` (9)** — skinned characters (`BrainStem`, `Fox`, `RiggedFigure`, and others)
  whose primitives carry `JOINTS_0`/`WEIGHTS_0`, which ADR 0021 excludes from
  `SUPPORTED_ATTRIBUTE_SEMANTICS` because they are only meaningful with skins, and skins are rejected.
- **`ANIMATION_TARGET_PATH` (1, `AnimatedColorsCube`)** — a `KHR_animation_pointer` `"pointer"` target
  path, outside the `{translation, rotation, scale}` set ADR 0021 admits.
- **`MORPH_TARGET` (1, `AnimatedMorphCube`)** — a non-empty primitive `targets` array, which ADR 0021
  rejects because `AnimationTrack` has no weight vocabulary.
- **`INTERPOLATION` (1, `InterpolationTest`)** — `CUBICSPLINE`, rejected per ADR 0021 rather than
  approximated with `LINEAR`, since that substitution would silently change motion.

No accepted document in this run exercises a feature outside what ADR 0021 already lists as accepted,
and no rejection exposed a feature RenG could plausibly support that the subset currently excludes.

## Conclusion

The corpus check found no reason to revise the supported GLB feature subset. ADR 0021 is left
unchanged: every one of its listed rejections is a decision already made and already reasoned about,
and this run supplies confirming evidence rather than a new decision, so it does not belong in the ADR
itself.

One incidental note carried forward from the run that produced these counts: the skinned-character
`ATTRIBUTE_SEMANTIC` rejections above were observed under the *original* check ordering
(`validateMeshes()` before `validateNodes()`), under which a skinned mesh's flagged `JOINTS_0`/
`WEIGHTS_0` attributes were reported before its `node.skin` reference. That ordering was corrected
separately (`SKIN` now precedes `ATTRIBUTE_SEMANTIC`) so a skinned model is diagnosed by the feature a
consumer must actually remove; re-running this same corpus after that fix would move these 9 files from
`ATTRIBUTE_SEMANTIC` to `SKIN`, not from reject to accept, so it does not change the conclusion above.
