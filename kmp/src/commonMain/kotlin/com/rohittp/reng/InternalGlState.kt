package com.rohittp.reng

import com.rohittp.reng.internal.gl.BackdropPipeline
import com.rohittp.reng.internal.gl.CompositePipeline
import com.rohittp.reng.internal.gl.GroundPipeline
import com.rohittp.reng.internal.gl.IconPipeline
import com.rohittp.reng.internal.gl.LabelPipeline
import com.rohittp.reng.internal.gl.OffscreenSurface
import com.rohittp.reng.internal.gl.StickerPipeline

/** Every GL object [createInternalGlState] allocates, bundled so the factory and re-adoption share one shape. */
internal class InternalGlState(
    val offscreenSurface: OffscreenSurface,
    val compositePipeline: CompositePipeline,
    val stickerPipeline: StickerPipeline,
    val backdropPipeline: BackdropPipeline,
    val groundPipeline: GroundPipeline,
    val labelPipeline: LabelPipeline,
    val iconPipeline: IconPipeline,
)
