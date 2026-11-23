package com.rohittp.reng.internal.image

/**
 * Whether [decodeStoredRows] alone decodes [bytes], for tests that must know the fast path -- not the
 * general one it falls back to -- is what produced an answer.
 */
internal fun storedFastPathDecodes(bytes: ByteArray): Boolean {
    val scan = scanPng(bytes) as? PngScan.Admitted ?: return false
    val channels = channelsFor(scan.header.colourType)
    val stride = scan.header.width * channels
    return decodeStoredRows(
        bytes = bytes,
        scan = scan,
        bpp = channels,
        channels = channels,
        currentRow = ByteArray(stride + 1),
        previousRow = ByteArray(stride + 1),
        rgba = ByteArray(scan.header.width * scan.header.height * 4),
    )
}
