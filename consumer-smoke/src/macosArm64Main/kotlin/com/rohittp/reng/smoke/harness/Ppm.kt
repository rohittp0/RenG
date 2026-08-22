@file:OptIn(ExperimentalForeignApi::class)

package com.rohittp.reng.smoke.harness

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite

/**
 * Writes one frame as a binary PPM (`P6`): an ASCII header, then raw RGB triples, nothing else.
 *
 * PPM rather than PNG because RenG owns a PNG *decoder* and no encoder (ADR 0020), and writing an
 * encoder in order to look at a picture is the wrong trade. `ffmpeg` reads `P6` natively.
 *
 * [pixels] arrives as RGBA8 straight from `glReadPixels`, whose first row is the **bottom** of the
 * image; PPM's first row is the top. This function flips. If a video ever comes out upside down,
 * this is where to look first.
 */
internal fun writePpm(path: String, width: Int, height: Int, pixels: ByteArray) {
    require(pixels.size == width * height * 4) { "expected an RGBA8 frame of $width x $height" }
    val header = "P6\n$width $height\n255\n".encodeToByteArray()
    val body = ByteArray(width * height * 3)
    for (row in 0 until height) {
        val sourceRow = height - 1 - row
        var source = sourceRow * width * 4
        var destination = row * width * 3
        repeat(width) {
            body[destination] = pixels[source]
            body[destination + 1] = pixels[source + 1]
            body[destination + 2] = pixels[source + 2]
            source += 4
            destination += 3
        }
    }

    val file = fopen(path, "wb") ?: error("cannot open $path for writing")
    try {
        header.usePinned { fwrite(it.addressOf(0), 1u, header.size.toULong(), file) }
        body.usePinned { fwrite(it.addressOf(0), 1u, body.size.toULong(), file) }
    } finally {
        fclose(file)
    }
}
