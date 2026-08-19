package com.rohittp.reng.internal.gl

internal interface GlBinding {
    fun getError(): Int
    fun getString(name: Int): String?
    fun getStringi(name: Int, index: Int): String?
    fun getIntegerv(pname: Int, out: IntArray)
    fun getFloatv(pname: Int, out: FloatArray)
    fun getBooleanv(pname: Int, out: BooleanArray)
    fun isEnabled(cap: Int): Boolean

    fun genFramebuffers(count: Int, out: IntArray)
    fun deleteFramebuffers(count: Int, names: IntArray)
    fun bindFramebuffer(target: Int, framebuffer: Int)
    fun framebufferTexture2D(target: Int, attachment: Int, textureTarget: Int, texture: Int, level: Int)
    fun framebufferRenderbuffer(target: Int, attachment: Int, renderbufferTarget: Int, renderbuffer: Int)
    fun checkFramebufferStatus(target: Int): Int
    fun isFramebuffer(framebuffer: Int): Boolean
    fun genRenderbuffers(count: Int, out: IntArray)
    fun deleteRenderbuffers(count: Int, names: IntArray)
    fun bindRenderbuffer(target: Int, renderbuffer: Int)
    fun renderbufferStorage(target: Int, internalFormat: Int, width: Int, height: Int)
    fun blitFramebuffer(
        sourceX0: Int, sourceY0: Int, sourceX1: Int, sourceY1: Int,
        destinationX0: Int, destinationY0: Int, destinationX1: Int, destinationY1: Int,
        mask: Int, filter: Int,
    )
    fun drawBuffers(count: Int, buffers: IntArray)
    fun readBuffer(mode: Int)

    fun genTextures(count: Int, out: IntArray)
    fun deleteTextures(count: Int, names: IntArray)
    fun bindTexture(target: Int, texture: Int)
    fun activeTexture(unit: Int)
    fun texImage2D(
        target: Int, level: Int, internalFormat: Int, width: Int, height: Int,
        border: Int, format: Int, type: Int, pixels: ByteArray?,
    )
    fun texStorage2D(target: Int, levels: Int, internalFormat: Int, width: Int, height: Int)
    fun texParameteri(target: Int, pname: Int, value: Int)
    fun generateMipmap(target: Int)
    fun genSamplers(count: Int, out: IntArray)
    fun deleteSamplers(count: Int, names: IntArray)
    fun bindSampler(unit: Int, sampler: Int)
    fun samplerParameteri(sampler: Int, pname: Int, value: Int)
    fun pixelStorei(pname: Int, value: Int)
    fun readPixels(x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, out: ByteArray)

    fun genBuffers(count: Int, out: IntArray)
    fun deleteBuffers(count: Int, names: IntArray)
    fun bindBuffer(target: Int, buffer: Int)
    fun bufferData(target: Int, size: Int, data: ByteArray?, usage: Int)
    fun bufferSubData(target: Int, offset: Int, size: Int, data: ByteArray)
    fun genVertexArrays(count: Int, out: IntArray)
    fun deleteVertexArrays(count: Int, names: IntArray)
    fun bindVertexArray(array: Int)
    fun enableVertexAttribArray(index: Int)
    fun disableVertexAttribArray(index: Int)
    fun vertexAttribPointer(
        index: Int, size: Int, type: Int, normalized: Boolean, stride: Int, offset: Int,
    )

    fun createShader(type: Int): Int
    fun deleteShader(shader: Int)
    fun shaderSource(shader: Int, source: String)
    fun compileShader(shader: Int)
    fun getShaderiv(shader: Int, pname: Int, out: IntArray)
    fun getShaderInfoLog(shader: Int): String
    fun createProgram(): Int
    fun deleteProgram(program: Int)
    fun attachShader(program: Int, shader: Int)
    fun linkProgram(program: Int)
    fun getProgramiv(program: Int, pname: Int, out: IntArray)
    fun getProgramInfoLog(program: Int): String
    fun useProgram(program: Int)
    fun getAttribLocation(program: Int, name: String): Int
    fun getUniformLocation(program: Int, name: String): Int
    fun uniform1i(location: Int, value: Int)
    fun uniform1f(location: Int, value: Float)
    fun uniform2f(location: Int, x: Float, y: Float)
    fun uniform3f(location: Int, x: Float, y: Float, z: Float)
    fun uniform4f(location: Int, x: Float, y: Float, z: Float, w: Float)

    /**
     * Sets a `uint` uniform. [value] is a bit pattern, not a signed magnitude: the caller owns
     * the narrowing from whatever wider type it started as (see `FramePlan.frameIndex`, a `Long`
     * narrowed to fit `uFrameIndex`). Kotlin's `UInt` does not bridge cleanly through every
     * platform binding, so the seam stays in `Int` and each platform performs its own conversion.
     */
    fun uniform1ui(location: Int, value: Int)
    fun uniformMatrix4fv(location: Int, count: Int, transpose: Boolean, value: FloatArray)

    fun enable(cap: Int)
    fun disable(cap: Int)
    fun blendFuncSeparate(sourceRgb: Int, destinationRgb: Int, sourceAlpha: Int, destinationAlpha: Int)
    fun blendEquationSeparate(modeRgb: Int, modeAlpha: Int)
    fun blendColor(red: Float, green: Float, blue: Float, alpha: Float)
    fun depthFunc(function: Int)
    fun depthMask(enabled: Boolean)
    fun depthRangef(near: Float, far: Float)
    fun cullFace(mode: Int)
    fun frontFace(mode: Int)
    fun viewport(x: Int, y: Int, width: Int, height: Int)
    fun scissor(x: Int, y: Int, width: Int, height: Int)
    fun colorMask(red: Boolean, green: Boolean, blue: Boolean, alpha: Boolean)
    fun clearColor(red: Float, green: Float, blue: Float, alpha: Float)
    fun clearDepthf(depth: Float)
    fun clear(mask: Int)
    fun drawArrays(mode: Int, first: Int, count: Int)
    fun drawElements(mode: Int, count: Int, type: Int, offset: Int)
    fun finish()
}
