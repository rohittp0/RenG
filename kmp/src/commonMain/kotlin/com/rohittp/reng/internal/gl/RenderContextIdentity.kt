package com.rohittp.reng.internal.gl

import com.rohittp.reng.internal.lifecycle.ExactContextFact
import kotlin.jvm.JvmInline

/**
 * An opaque identity for the caller's already-current Render Context.
 *
 * RenG never creates, makes current, or destroys a context, and never references CGL, EAGL, EGL,
 * `NSOpenGLContext`, or `ANativeWindow` (ADR 0001). The value is whatever the supplier considers
 * the context's identity — a pointer on the platforms measured — and RenG only compares it.
 */
@JvmInline
internal value class RenderContextIdentity(val value: Long)

internal fun interface RenderContextProbe {
    fun currentContextIdentity(): RenderContextIdentity?
}

internal fun exactContextFact(
    adopted: RenderContextIdentity,
    probe: RenderContextProbe,
): ExactContextFact = when (probe.currentContextIdentity()) {
    null -> ExactContextFact.NONE
    adopted -> ExactContextFact.EXACT
    else -> ExactContextFact.DIFFERENT
}
