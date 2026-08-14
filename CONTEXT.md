# RenG

RenG provides the vocabulary for a Kotlin Multiplatform 3D renderer that draws worlds on top of
[Rentile](https://rohittp.com/rentile/) basemap tiles into a surface the consumer owns. RenG draws
frames; it owns no window, no render loop, no capture path, and no encoder.

## Language

### The frame

**Frame Plan**:
A complete, self-contained definition of one frame's content, drawn by whichever renderer prepared it.
_Avoid_: Scene graph, render command, frame delta, mutation batch

**Basemap Style**:
The style document a renderer draws its ground from, fixed for that renderer's lifetime and never named
by a **Frame Plan**.
_Avoid_: Map style, theme, basemap, style profile

**Prepared Frame**:
A network-free, GL-free rendering input holding every resource one **Frame Plan** needs, produced
before any drawing occurs.
_Avoid_: Frame buffer, render pass, draw queue, prepared batch

**Render Target**:
The caller-owned framebuffer identity RenG composites one finished frame into; its dimensions are the
renderer's configured output size, not a property of the target.
_Avoid_: Surface, canvas, viewport, swapchain

**Render Context**:
The caller's OpenGL context, already current on the calling thread, that RenG issues every GL call
against.
_Avoid_: Surface, device, GL session, EGL context

### Placement

**Placement**:
The resolved position, rotation, pitch, and scale of one drawn thing, each property carrying its own
**Anchoring Mode**.
_Avoid_: Transform, matrix, pose

**Anchoring Mode**:
Whether one placement property is resolved against the map (`MAP`) or against the screen (`SCREEN`).
_Avoid_: Coordinate space, reference frame, projection mode

**Screen Anchoring**:
Resolution against screen space, in which `position.z` is a compositing z-index rather than a depth
value.
_Avoid_: Overlay mode, 2D mode, billboard

**Map Anchoring**:
Resolution against map space, in which the drawn thing is depth-tested and occluded by the 3D scene.
_Avoid_: World mode, 3D mode, geo mode

**Draw Regime**:
One of the two distinct ordering-and-occlusion rules a frame draws under — screen-composited or
map-occluded.
_Avoid_: Layer, pass, stage

### Drawn things

**Sticker**:
A **Placement** paired with one image, drawn as a flat quad.
_Avoid_: Marker, pin, decal, sprite

**Model**:
A **Placement** paired with one GLB mesh, one texture, and its **Animation Track** states.
_Avoid_: Mesh, asset, actor, entity

**Animation Track**:
A named animation within a **Model** together with the single frame index selected for this
**Frame Plan**.
_Avoid_: Clip, timeline, animation state, keyframe

**Geometry**:
A lat/lon/altitude-bounded quad painted by a consumer-supplied **Shader Pair**.
_Avoid_: Layer, overlay, custom layer, primitive

**Shader Pair**:
The vertex and fragment shader sources a **Geometry** is painted with.
_Avoid_: Program, material, effect

**Shader Profile**:
The single accepted shader source dialect — a GLSL ES 3.00 body — that RenG adapts to a desktop
**Render Context** by substituting `#version 330 core` for `#version 300 es` and changing nothing else.
_Avoid_: Shader language, GLSL version, compatibility profile

**Basemap Tile**:
One PNG tile acquired from Rentile and drawn as the ground beneath a frame.
_Avoid_: Output tile, source tile, map tile, raster

### Resources

**Raw Resource**:
The exact encoded bytes of one thing RenG needs — a basemap resource proxied to Rentile, a sticker
image, a GLB mesh, or a model texture — before any decode or parse.
_Avoid_: Asset, blob, cache entry, decoded resource

**Resource Class**:
Which kind of thing a **Raw Resource** is, named in RenG's vocabulary rather than Rentile's.
_Avoid_: Resource type, MIME type, content type

**Transport**:
The consumer-supplied adapter RenG performs every network exchange through, for its own resources and
for the ones it proxies to Rentile.
_Avoid_: HTTP client, fetcher, network layer

**Store**:
The consumer-supplied persistent adapter RenG reads and writes **Raw Resource** bytes through; RenG
owns no persistent cache of its own.
_Avoid_: Cache, disk cache, repository

## Relationships

- One renderer draws exactly one **Basemap Style**, or none at all, at exactly one output pixel size
- One **Frame Plan** prepares into exactly one **Prepared Frame**
- Many **Prepared Frame**s may exist at once and be drawn in any order
- One **Prepared Frame** draws into any number of **Render Target**s, and drawing does not consume it
- One **Frame Plan** holds any number of **Sticker**s, **Model**s, and **Geometry**s
- Each of **Sticker**, **Model**, and **Geometry** carries exactly one **Placement**
- One **Placement** resolves four properties independently, each under its own **Anchoring Mode**
- **Anchoring Mode** selects which **Draw Regime** a property is drawn under, so one **Placement**
  can straddle both regimes
- One **Model** carries zero or more **Animation Track**s
- Every **Geometry** carries exactly one **Shader Pair**, written in the **Shader Profile**

## Example dialogue

> **Dev:** "If I prepare frames 1 through 30 up front, does preparing frame 30 evict what frame 1
> needs?"
> **Domain expert:** "No. A **Prepared Frame** holds every resource its **Frame Plan** needs for as
> long as it is alive, and identical resources are shared between them rather than duplicated."
>
> **Dev:** "So can I draw frame 12 before frame 5?"
> **Domain expert:** "Yes. A **Prepared Frame** is independent — nothing about it depends on the
> order frames were prepared or drawn in."
>
> **Dev:** "This **Sticker** has a `MAP` position and a `SCREEN` rotation. Which **Draw Regime**
> does it belong to?"
> **Domain expert:** "Both, per property. Its position resolves in map space and is depth-tested;
> its rotation resolves in screen space. **Anchoring Mode** is per property, never per object."

## Flagged ambiguities

- "surface" was used for three distinct things — the caller's framebuffer (**Render Target**), the
  caller's GL context (**Render Context**), and the platform window handle (which RenG never
  touches). Resolved: RenG's API names the first two and has no term for the third.
- "frame" was used for both a rendered image and an animation frame index. Resolved: a rendered
  image is a **Frame Plan**/**Prepared Frame**; an animation frame index belongs to an
  **Animation Track**.
- CLAUDE.md describes the render loop as taking "only a `FramePlan`". Resolved: the render loop
  takes a **Prepared Frame**; see ADR 0002.
- CLAUDE.md describes geometry shaders as "plain, fully self-contained OpenGL shaders" with no
  RenG-injected preamble. Resolved: RenG substitutes the version directive and nothing else; see
  ADR 0008.
- "free" was used for both "release these resources, reload them on next access" and "these GPU
  objects no longer exist". Resolved: these are separate operations, because the first deletes GL
  objects and the second must not; see ADR 0007.
