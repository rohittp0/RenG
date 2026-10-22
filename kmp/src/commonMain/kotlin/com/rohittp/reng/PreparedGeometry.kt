package com.rohittp.reng

/**
 * One [Geometry] with both of Task 9b item 3's snapshots already taken at `prepare()` time:
 * [uniformsSnapshot] is a `.toMap()` copy of [Geometry.uniforms] and [consumerTextures] replaces
 * [Geometry.textures]' locators with their already-fetched, already-decoded content — never the
 * caller's own live `Map` references. [geometry] itself is retained only for its immutable
 * `topLeft`/`bottomRight`/`shaderPair` fields, which carry no such live-reference hazard.
 */
internal class PreparedGeometry(
    internal val geometry: Geometry,
    internal val uniformsSnapshot: Map<String, ShaderValue>,
    internal val consumerTextures: Map<String, PreparedGeometryTexture>,
)
