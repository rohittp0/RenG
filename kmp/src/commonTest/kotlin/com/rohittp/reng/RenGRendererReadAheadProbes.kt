package com.rohittp.reng

/** Whether this renderer ever started a read-ahead worker. */
internal val RenGRenderer.readAheadWorkerStarted: Boolean
    get() = readAheadCoordinator.readAheadWorkerStarted

/** Tiles the read-ahead rendered-tile cache holds, or zero when it is disabled (ADR 0077). */
internal val RenGRenderer.renderedTileCacheTiles: Int
    get() = readAheadCoordinator.renderedTileCache?.size ?: 0

/** Bytes the read-ahead rendered-tile cache holds, or zero when it is disabled (ADR 0077). */
internal val RenGRenderer.renderedTileCacheBytes: Long
    get() = readAheadCoordinator.renderedTileCache?.bytes ?: 0L
