package com.rohittp.reng.internal.preparation

import com.rohittp.reng.internal.thread.PlatformLock
import kotlin.concurrent.Volatile

/** Atomic accounting for raw basemap pixels retained by open prepared frames. */
internal class RawTileBudget(private val maximumBytes: Long) {
    private val lock = PlatformLock()
    private var outstandingBytes: Long = 0L

    init {
        require(maximumBytes >= 0L) { "maximumBytes must not be negative" }
    }

    internal class Reservation internal constructor(
        private val owner: RawTileBudget,
        bytes: Long,
    ) {
        /**
         * What this reservation holds against the budget. Only ever lowered, by [shrinkTo], and only
         * under the budget's lock; the frame that owns the reservation reads it after its preparation
         * has finished shrinking it.
         */
        @Volatile
        var bytes: Long = bytes
            internal set

        private var released: Boolean = false

        fun release() {
            owner.release(this)
        }

        /**
         * Gives back everything above [retainedBytes] at once, keeping this same reservation (ADR 0044,
         * ADR 0057). For a preparation that reserved for tiles it then did not rasterise -- a failed
         * colour tile drawn from its ancestor -- so the frame's reservation still covers exactly the raw
         * pixels it carries. In place rather than release-and-reserve-again, because a second reserve can
         * lose the room to a concurrent frame in between, and because the frame has already been handed
         * this reservation and owns exactly one.
         */
        fun shrinkTo(retainedBytes: Long) {
            owner.shrink(this, retainedBytes)
        }

        internal val isReleased: Boolean get() = released

        internal fun markReleased(): Boolean {
            if (released) return false
            released = true
            return true
        }
    }

    fun tryReserve(bytes: Long): Reservation? {
        require(bytes >= 0L) { "reservation bytes must not be negative" }
        return lock.withLock {
            if (bytes > maximumBytes - outstandingBytes) return@withLock null
            outstandingBytes += bytes
            Reservation(this, bytes)
        }
    }

    internal fun outstandingBytes(): Long = lock.withLock { outstandingBytes }

    private fun shrink(reservation: Reservation, retainedBytes: Long) {
        lock.withLock {
            check(!reservation.isReleased) { "a released raw tile reservation cannot shrink" }
            require(retainedBytes in 0L..reservation.bytes) { "a raw tile reservation can only shrink" }
            outstandingBytes -= reservation.bytes - retainedBytes
            reservation.bytes = retainedBytes
        }
    }

    private fun release(reservation: Reservation) {
        lock.withLock {
            if (!reservation.markReleased()) return@withLock
            check(reservation.bytes <= outstandingBytes) { "raw tile budget cannot become negative" }
            outstandingBytes -= reservation.bytes
        }
    }

}
