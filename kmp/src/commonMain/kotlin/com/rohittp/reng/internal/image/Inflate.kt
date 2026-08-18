package com.rohittp.reng.internal.image

/** One incremental inflate step: input consumed, output produced, and whether the stream ended. */
internal data class InflateStep(
    val consumed: Int,
    val produced: Int,
    val finished: Boolean,
)

internal class InflateException(message: String) : Exception(message)

/**
 * A streaming zlib inflater. PNG splits one zlib stream across arbitrarily many IDAT chunks, so this
 * must accept input incrementally and must never require the whole compressed payload at once. It must
 * also tolerate an output buffer smaller than the remaining decompressed data, producing what fits and
 * reporting how much of each side it actually touched so the caller can resume with a fresh slice and/or
 * a fresh output window.
 */
internal expect class InflateStream() {
    fun inflate(input: ByteArray, output: ByteArray, outputOffset: Int): InflateStep
    fun close()
}

/**
 * A streaming, chainable CRC-32 (the zlib/PNG variant): `crc32(seed, bytes, offset, length)` continues a
 * running checksum from `seed`. `crc32(0u, a, ...)` followed by `crc32(<that result>, b, ...)` is
 * identical to a single call over the concatenation of `a` and `b` — the shape a PNG chunk walk needs to
 * accumulate the CRC over a chunk's type field and its payload, which may itself arrive in more than one
 * piece.
 */
internal expect fun crc32(seed: UInt, bytes: ByteArray, offset: Int, length: Int): UInt
