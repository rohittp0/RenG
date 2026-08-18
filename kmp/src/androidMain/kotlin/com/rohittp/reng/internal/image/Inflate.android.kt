package com.rohittp.reng.internal.image

import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Streams zlib inflate over `java.util.zip.Inflater`: PNG's IDAT chunking means the compressed payload
 * must never be buffered whole.
 */
internal actual class InflateStream actual constructor() {
    private val inflater = Inflater()
    private var closed = false

    actual fun inflate(input: ByteArray, output: ByteArray, outputOffset: Int): InflateStep {
        require(!closed) { "inflate stream is closed" }
        require(outputOffset in 0..output.size) { "output offset out of range" }
        // Inflater.setInput() replaces the buffer it reads from, so only call it once the previous
        // buffer is fully drained (needsInput() true) — otherwise it would discard unconsumed bytes
        // from the prior call. When we skip it, none of *this* call's `input` bytes were touched, so
        // `consumed` for this step must be exactly 0, independent of `input`'s size.
        val suppliedNewInput = input.isNotEmpty() && inflater.needsInput()
        if (suppliedNewInput) {
            inflater.setInput(input)
        }
        val availOut = output.size - outputOffset
        // Call inflate() even with availOut == 0: a stream whose remaining bytes decode to zero output
        // (the empty-payload vector) can only be detected as finished by letting the inflater consume
        // the trailing bytes, which needs no output space at all. Inflater.inflate(buf, off, 0) is a
        // legal, well-defined call that still advances internal state.
        val produced = try {
            inflater.inflate(output, outputOffset, availOut)
        } catch (failure: DataFormatException) {
            throw InflateException("inflate failed: ${failure.message ?: failure::class.simpleName}")
        }
        val consumed = if (suppliedNewInput) input.size - inflater.remaining else 0
        return InflateStep(
            consumed = consumed,
            produced = produced,
            finished = inflater.finished(),
        )
    }

    actual fun close() {
        if (closed) return
        closed = true
        inflater.end()
    }
}

// A hand-rolled CRC-32 (the reflected IEEE 802.3 / zlib / PNG variant) rather than a wrapper over
// java.util.zip.CRC32: that class has no way to inject a running value as a seed, only to accumulate
// through repeated update() calls on one stateful instance, and the required signature here is a pure
// function that takes its running value as an explicit seed parameter — the exact shape a PNG chunk
// walk needs to chain the CRC across a chunk's type field and its (possibly multi-piece) payload. This
// table and loop are the same algorithm `platform.zlib.crc32` runs on the native targets, so both
// actuals produce identical bytes for every seed, not just seed 0.
private const val CRC32_POLYNOMIAL = 0xEDB88320u

private val crc32Table: UIntArray = UIntArray(256) { index ->
    var value = index.toUInt()
    repeat(8) {
        value = if (value and 1u != 0u) (value shr 1) xor CRC32_POLYNOMIAL else value shr 1
    }
    value
}

internal actual fun crc32(seed: UInt, bytes: ByteArray, offset: Int, length: Int): UInt {
    require(offset >= 0 && length >= 0 && offset + length <= bytes.size) { "crc range out of bounds" }
    if (length == 0) return seed
    var crc = seed xor 0xFFFFFFFFu
    for (i in offset until offset + length) {
        val byteValue = (bytes[i].toInt() and 0xFF).toUInt()
        val tableIndex = (crc xor byteValue) and 0xFFu
        crc = crc32Table[tableIndex.toInt()] xor (crc shr 8)
    }
    return crc xor 0xFFFFFFFFu
}
