# Consumer model corpus check — what RenG's GLB subset accepts today

Measured, not estimated, against the 41 GLB models served from the consumer's own catalogue
(`travel_animator/v0/android/models/`, plus `family_car_0.glb`). Every figure below is derived by
parsing each file's glTF JSON chunk and evaluating RenG's own rejection rules from
`internal/glb/GltfFeatures.kt` — `SUPPORTED_ATTRIBUTE_SEMANTICS`, the `node.skin` check, morph
targets, primitive mode, animation target path, interpolation, sparse accessors, image media type,
multiple buffers, and required extensions.

## Headline

**RenG accepts 23 of 41 models today.**

| rejection reason | models |
|---|---|
| `ATTR:TEXCOORD_1` | 14 |
| `SKIN` | 7 |
| `ATTR:COLOR_1` | 1 |
| `IMG:image/jpeg` | 1 |

- Rejected **only** for a second UV or colour set: **10**
- Rejected for true vertex skinning: **7**
- Accepted if extra UV/colour sets were ignored: **33**
- Accepted if skinning were also supported: **40**

## What this settles

**Morph targets are not needed.** Zero of 41 models carry a single morph target, so deferring them
is confirmed by measurement rather than assumed.

**The interpolation subset is already exactly right.** Across every animation sampler in the corpus:
1443 `STEP` and 490 `LINEAR`, and no `CUBICSPLINE` at all. ADR 0021 admits precisely those two.

**A second UV set is a false rejection.** RenG samples one texture, so `TEXCOORD_1` carries data it
never reads — yet 14 models are rejected for it, 10 of them for nothing else. Widening a subset is
a compatible change by ADR 0021's own reasoning, so this can land at any time.

**Skinning needs a uniform buffer, not a uniform array.** Joint counts in the corpus:
112, 44, 38, 13, 4, 3, 2. GLSL ES 3.00 guarantees only 256 vec4 vertex uniforms — 64 `mat4`
before anything else RenG binds — so the 112-joint models exceed a naive uniform array. A uniform
buffer's guaranteed 16 KB minimum holds 256 `mat4` and covers the whole corpus with headroom.

Also observed, all benign: every model is single-buffer; no primitive declares a `mode`, so all are
`TRIANGLES`; component types are `FLOAT`, `UNSIGNED_SHORT` and `UNSIGNED_BYTE` only; and the two
extensions present (`KHR_materials_specular`, `KHR_materials_ior`) appear in `extensionsUsed` and
never in `extensionsRequired`, so RenG's required-extension check correctly ignores them.

## Vestigial rigs — a check that is already right

18 models carry a `skins` array that **no node references**: exporter leftovers. RenG rejects on
`node.skin != null` rather than on the presence of `skins`, so it already accepts them. Had the check
been written against the array, 18 drawable models would have been rejected for dead data.

## Per-model detail

| model | MB | skinned | max joints | rejected for |
|---|---|---|---|---|
| `Alexander_Dennis_Enviro500.glb` | 3.5 | no | 4 | — accepted — |
| `BMW_Couple3.glb` | 4.3 | no | 3 | — accepted — |
| `Dacia_Logan.glb` | 2.7 | no | 3 | — accepted — |
| `Proton_Saga.glb` | 2.2 | no | 3 | — accepted — |
| `Tuk_Tuk2.glb` | 3.8 | no | 3 | — accepted — |
| `Volkswagen_Passat.glb` | 3.3 | no | 3 | — accepted — |
| `Volvo_XC60.glb` | 3.4 | no | 3 | — accepted — |
| `Blimp_White_Aircraft.glb` | 3.1 | no | 2 | — accepted — |
| `Commuter_Rail0.glb` | 1.4 | no |  | — accepted — |
| `Commuter_Rail1.glb` | 1.4 | no |  | — accepted — |
| `Commuter_Rail2.glb` | 1.4 | no |  | — accepted — |
| `Desiro_ML0.glb` | 1.2 | no |  | — accepted — |
| `Desiro_ML1.glb` | 1.2 | no |  | — accepted — |
| `Desiro_ML2.glb` | 1.2 | no |  | — accepted — |
| `Eurostar_Train0.glb` | 1.5 | no |  | — accepted — |
| `Eurostar_Train1.glb` | 1.4 | no |  | — accepted — |
| `Eurostar_Train2.glb` | 1.5 | no |  | — accepted — |
| `KTX_I_Train0.glb` | 0.7 | no |  | — accepted — |
| `KTX_I_Train1.glb` | 1.1 | no |  | — accepted — |
| `KTX_I_Train2.glb` | 1.1 | no |  | — accepted — |
| `Skoda_Artic0.glb` | 1.5 | no |  | — accepted — |
| `Skoda_Artic1.glb` | 2.4 | no |  | — accepted — |
| `Skoda_Artic2.glb` | 2.5 | no |  | — accepted — |
| `Elephant_Man.glb` | 4.1 | yes | 112 | `ATTR:TEXCOORD_1`, `SKIN` |
| `Elephant_Woman.glb` | 4.3 | yes | 112 | `ATTR:TEXCOORD_1`, `SKIN` |
| `Ducati_Monster.glb` | 4.6 | yes | 44 | `SKIN` |
| `Suzuki_Raider_150.glb` | 4.5 | yes | 44 | `ATTR:TEXCOORD_1`, `SKIN` |
| `Yamaha_NMAX_125.glb` | 3.7 | yes | 44 | `ATTR:TEXCOORD_1`, `SKIN` |
| `Dinosaur_Man.glb` | 1.1 | yes | 38 | `SKIN` |
| `Dinosaur_Woman2.glb` | 1.4 | yes | 38 | `SKIN` |
| `family_car_0.glb` | 0.4 | no | 13 | `IMG:image/jpeg` |
| `Dacia_Duster.glb` | 3.0 | no | 3 | `ATTR:TEXCOORD_1` |
| `Fiat_Panda.glb` | 3.3 | no | 3 | `ATTR:TEXCOORD_1` |
| `Hyundai_Avante_Elantra.glb` | 2.8 | no | 3 | `ATTR:TEXCOORD_1` |
| `Hyundai_i20.glb` | 2.6 | no | 3 | `ATTR:TEXCOORD_1` |
| `Kia_K5_Optima.glb` | 2.6 | no | 3 | `ATTR:TEXCOORD_1` |
| `Nissan_Frontier.glb` | 3.0 | no | 3 | `ATTR:TEXCOORD_1` |
| `Perodua_Myvi.glb` | 3.0 | no | 3 | `ATTR:TEXCOORD_1` |
| `Toyota_Fortuner_.glb` | 2.8 | no | 3 | `ATTR:TEXCOORD_1` |
| `Volvo_V60.glb` | 1.8 | no | 3 | `ATTR:COLOR_1`, `ATTR:TEXCOORD_1` |
| `Bulk_Carrier_Ship.glb` | 1.3 | no |  | `ATTR:TEXCOORD_1` |
