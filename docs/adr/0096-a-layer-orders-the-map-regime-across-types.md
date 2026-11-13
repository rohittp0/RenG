# A layer orders the map regime across types

## Context

ADR 0025 fixed the map-regime order as ground, Geometry, then Sticker; ADR 0030 inserted Model before Sticker. That phase order preserves a sensible default, but it prevents a consumer from putting one type below or above another: a Sticker cannot sit below a Geometry and a Geometry cannot sit above a Sticker.

## Decision

Sticker, Geometry and Model gain an integer `layer`, defaulting to zero. Ground still draws first. All map-anchored caller content then forms one stable order by ascending `(layer, type rank, source index)`, where the type rank is Geometry, Model, Sticker and the source index is the entry's index in its own `FramePlan` list. Consecutive entries of one type form a draw run and retain that type's existing GL state and draw implementation.

This amends ADR 0025's map-regime order and ADR 0030's models-before-stickers order: the old phase order is now the tie-break inside one layer. With every layer at zero, the resulting draw sequence and batching are the sequence that shipped before this ADR. A screen-anchored Sticker ignores `layer`; its screen stack remains ordered by `position.z` and stable plan order.

Geometry remains absent from spatial `mapEntries` because it has no Placement. The renderer merges `GeometryAt` references with the resolved map-placement references after spatial planning. Bloom composites immediately after the last emissive Geometry in that merged order, and texture-unit history includes intervening Sticker and Model runs.

## Consequences

A consumer can interleave map-anchored Geometry, Model and Sticker content without splitting or reordering its source lists. Older serialized plans omit `layer` and decode it as zero.

Two limits are accepted. A Model writes depth so that its mesh can occlude itself; consequently a later layer does not paint over a nearer Model. Also, `ModelPipeline.kt` sorts blended primitives inside each `drawModels` call. Models separated into different runs by other types therefore no longer have their blended primitives sorted together.
