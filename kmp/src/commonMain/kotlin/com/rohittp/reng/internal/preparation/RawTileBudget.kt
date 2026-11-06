package com.rohittp.reng.internal.preparation

import com.rohittp.reng.internal.thread.PlatformLock
import kotlin.concurrent.Volatile

/**
 * Atomic accounting for raw basemap pixels retained by open prepared frames (ADR 0044), charged once
 * per distinct pixel array however many open frames hold it (ADR 0052's note of 2026-11-06).
 *
 * **Held pixels are keyed by the array itself.** A batch shares one `RenderedBasemapTile`, and so one
 * `rgba` array, between every frame that shows it (ADR 0052), and that array is exactly what this budget
 * bounds. Kotlin compares arrays by reference on every target, so a map keyed on the array is an
 * identity map: two holds are one charge only when they name one allocation. The tile's `ResourceKey`
 * would be wrong in the direction that matters: it names a picture rather than an allocation, so two
 * frames outside a batch that each rasterise one tile -- or a read-ahead tile and the fresh render that
 * replaced it -- hold two arrays under one key, and keying on it would charge them once. A blended second
 * style (ADR 0086) needs no key of its own for the same reason: its tiles are its own arrays, whatever
 * their coordinates.
 *
 * A reservation charges its arrays the way `ResidentCache` and `GlObjectRegistry` count leases: an array
 * no reservation holds costs its size, one another open reservation already holds costs nothing, and its
 * bytes return when the last holder releases it. Pixels a preparation has yet to rasterise have no array
 * to key on, so they are reserved as fresh bytes and become held arrays at [Reservation.bindFresh], which
 * moves no bytes.
 */
internal class RawTileBudget(private val maximumBytes: Long) {
    private val lock = PlatformLock()
    private var outstandingBytes: Long = 0L

    /** How many holds each distinct raw pixel array has across every unreleased reservation. */
    private val holdCounts: MutableMap<ByteArray, Int> = HashMap()

    init {
        require(maximumBytes >= 0L) { "maximumBytes must not be negative" }
    }

    internal class Reservation internal constructor(
        private val owner: RawTileBudget,
    ) {
        /**
         * What this reservation holds, per occurrence: the size of every array it holds plus the fresh
         * bytes it has not yet bound. This, not the budget's distinct total, is what a frame compares with
         * its own raw pixels (ADR 0044), because a frame's pixels are its own list whichever other frames
         * share them. Changed only under the budget's lock; the frame that owns the reservation reads it
         * after its preparation has finished with it.
         */
        @Volatile
        var bytes: Long = 0L
            internal set

        /** The part of [bytes] held as arrays rather than as fresh bytes still to be bound. */
        @Volatile
        internal var heldBytes: Long = 0L

        /** Every array this reservation holds, once per hold. Guarded by the budget's lock. */
        internal val heldPixels: MutableList<ByteArray> = ArrayList()

        private var released: Boolean = false

        /**
         * Holds [pixels] and reserves [freshBytes] for pixels still to be rasterised, all or nothing:
         * answers `false`, having changed nothing, when what this adds to the budget does not fit. What
         * it adds is only the arrays no reservation holds yet, so a tile an earlier frame of the batch
         * holds is free -- and this reservation still takes its own hold, so the pixels stay counted if
         * that frame closes first.
         */
        fun tryHold(pixels: List<ByteArray>, freshBytes: Long): Boolean = owner.tryHold(this, pixels, freshBytes)

        /**
         * Holds those of [pixels] some open reservation already holds, which costs nothing and so always
         * succeeds, and answers the rest, which it leaves alone. For a preparation whose remembered tiles
         * do not all fit: what is already held stays shared rather than being rasterised a second time.
         */
        fun holdAlreadyHeld(pixels: List<ByteArray>): List<ByteArray> = owner.holdAlreadyHeld(this, pixels)

        /**
         * Turns the fresh bytes this reservation took into holds on the arrays the engine rendered for
         * them, so a later frame of the batch that remembers one finds it held and is not charged again.
         * Moves no bytes.
         */
        fun bindFresh(pixels: List<ByteArray>) {
            owner.bindFresh(this, pixels)
        }

        fun release() {
            owner.release(this)
        }

        /**
         * Gives back everything above [retainedBytes] at once, keeping this same reservation (ADR 0044,
         * ADR 0057). For a preparation that reserved for tiles it then did not rasterise -- a failed
         * colour tile drawn from its ancestor -- so the frame's reservation still covers exactly the raw
         * pixels it carries. Only fresh bytes can go: [retainedBytes] never falls below [heldBytes]. In
         * place rather than release-and-reserve-again, because a second reserve can lose the room to a
         * concurrent frame in between, and because the frame has already been handed this reservation
         * and owns exactly one.
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

    /** A reservation that holds nothing yet, for [Reservation.tryHold] and its siblings to fill. */
    fun reserve(): Reservation = Reservation(owner = this)

    /** A reservation of [bytes] fresh bytes, or `null` when they do not fit. */
    fun tryReserve(bytes: Long): Reservation? {
        val reservation = Reservation(owner = this)
        return if (tryHold(reservation, emptyList(), bytes)) reservation else null
    }

    internal fun outstandingBytes(): Long = lock.withLock { outstandingBytes }

    private fun tryHold(reservation: Reservation, pixels: List<ByteArray>, freshBytes: Long): Boolean {
        require(freshBytes >= 0L) { "reservation bytes must not be negative" }
        return lock.withLock {
            check(!reservation.isReleased) { "a released raw tile reservation cannot hold more" }
            var charged = freshBytes
            var held = 0L
            // An array named twice in one call is one allocation, so it is charged once here too.
            val newlyCharged = HashSet<ByteArray>()
            for (array in pixels) {
                val size = array.size.toLong()
                if (size > Long.MAX_VALUE - held) return@withLock false
                held += size
                if (array !in holdCounts && newlyCharged.add(array)) {
                    if (size > Long.MAX_VALUE - charged) return@withLock false
                    charged += size
                }
            }
            if (charged > maximumBytes - outstandingBytes) return@withLock false
            if (held > Long.MAX_VALUE - freshBytes || held + freshBytes > Long.MAX_VALUE - reservation.bytes) {
                return@withLock false
            }
            pixels.forEach { array -> holdLocked(reservation, array) }
            outstandingBytes += charged
            reservation.bytes += held + freshBytes
            true
        }
    }

    private fun holdAlreadyHeld(reservation: Reservation, pixels: List<ByteArray>): List<ByteArray> =
        lock.withLock {
            check(!reservation.isReleased) { "a released raw tile reservation cannot hold more" }
            val unheld = ArrayList<ByteArray>()
            for (array in pixels) {
                if (array in holdCounts) {
                    holdLocked(reservation, array)
                    reservation.bytes += array.size.toLong()
                } else {
                    unheld += array
                }
            }
            unheld
        }

    private fun bindFresh(reservation: Reservation, pixels: List<ByteArray>) {
        lock.withLock {
            check(!reservation.isReleased) { "a released raw tile reservation cannot bind pixels" }
            // A render whose pixels are not exactly what was reserved is refused by the frame's own
            // `init` (ADR 0044) with the failure it has always had; binding part of it here would only
            // change which check says so.
            if (pixels.sumOf { it.size.toLong() } != reservation.bytes - reservation.heldBytes) return@withLock
            pixels.forEach { array ->
                // Its fresh bytes were charged; if another reservation already held this very array they
                // would count it twice, so they go back. An array the engine has just allocated never is.
                if (array in holdCounts) outstandingBytes -= array.size.toLong()
                holdLocked(reservation, array)
            }
        }
    }

    private fun holdLocked(reservation: Reservation, array: ByteArray) {
        holdCounts[array] = (holdCounts[array] ?: 0) + 1
        reservation.heldPixels += array
        reservation.heldBytes += array.size.toLong()
    }

    private fun shrink(reservation: Reservation, retainedBytes: Long) {
        lock.withLock {
            check(!reservation.isReleased) { "a released raw tile reservation cannot shrink" }
            require(retainedBytes in reservation.heldBytes..reservation.bytes) {
                "a raw tile reservation can only shrink, and only its fresh bytes"
            }
            outstandingBytes -= reservation.bytes - retainedBytes
            reservation.bytes = retainedBytes
        }
    }

    private fun release(reservation: Reservation) {
        lock.withLock {
            if (!reservation.markReleased()) return@withLock
            var releasedBytes = reservation.bytes - reservation.heldBytes
            for (array in reservation.heldPixels) {
                val count = checkNotNull(holdCounts[array]) { "a held raw tile array must remain counted" }
                if (count == 1) {
                    holdCounts.remove(array)
                    releasedBytes += array.size.toLong()
                } else {
                    holdCounts[array] = count - 1
                }
            }
            reservation.heldPixels.clear()
            check(releasedBytes <= outstandingBytes) { "raw tile budget cannot become negative" }
            outstandingBytes -= releasedBytes
        }
    }
}
