package com.rohittp.reng.internal.identity

import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceLocator
import com.rohittp.reng.internal.cache.BoundedMemo
import com.rohittp.reng.internal.thread.PlatformLock

/**
 * A value derived from one `(ResourceClass, ResourceLocator)` route by a pure function, remembered so a
 * route is not derived again on every `prepare`: an external reference's [DerivedResourceKey], or its
 * Rentile private key.
 *
 * **Why.** Every `prepare` derived every external reference's key from scratch -- encode the root,
 * SHA-256 it, spell out the hex -- and resolved its Rentile private key, which for RenG-keyed classes
 * derives the same key a second time; a model texture was hashed about four times per `prepare`. On an
 * OPPO CPH2629 (2026-10-09), exporting a project whose motion blur prepares every frame up to six
 * times, resource keys were 13% of RenG's time.
 *
 * **It cannot be stale.** Both derivations read nothing but the class and the locator's exact string
 * ([ResourceLocator] equality is that string), so a hit is the value a fresh derivation would give,
 * byte for byte.
 *
 * **It is bounded, by second chance ([com.rohittp.reng.internal.cache.BoundedMemo]).** A frame names
 * its stable resources -- stickers, model GLBs and their textures -- every time, but also routes that
 * are new every frame (a label's per-frame glyph data). Evicting oldest-inserted first would let those
 * push the stable ones out every few frames; a hit marks its entry so it survives the next pass, so only
 * routes no frame has named recently leave.
 *
 * **It is locked**, because drawing and `readAhead` may derive keys while a preparation does. The
 * derivation runs outside the lock; two threads that both miss derive equal values and the insert keeps
 * whichever arrived first.
 */
internal class RouteMemo<V : Any>(private val capacity: Int = MAXIMUM_REMEMBERED_ROUTES) {
    init {
        require(capacity >= 1) { "capacity must be at least 1" }
    }

    private data class Route(val resourceClass: ResourceClass, val locator: String)

    private val values: BoundedMemo<Route, V> = BoundedMemo(capacity)
    private val lock = PlatformLock()

    internal inline fun valueOf(resourceClass: ResourceClass, locator: ResourceLocator, derive: () -> V): V {
        lookup(resourceClass, locator)?.let { return it }
        return remember(resourceClass, locator, derive())
    }

    internal fun lookup(resourceClass: ResourceClass, locator: ResourceLocator): V? =
        lock.withLock { values.get(Route(resourceClass, locator.value)) }

    internal fun remember(resourceClass: ResourceClass, locator: ResourceLocator, value: V): V =
        lock.withLock { values.putIfAbsent(Route(resourceClass, locator.value), value) }

    internal val size: Int get() = lock.withLock { values.size }
}

/**
 * Far more routes than a frame names: a busy project's stickers, models and textures are tens, and a
 * label's glyph data adds a few per frame. An entry is a short canonical root and a digest.
 */
internal const val MAXIMUM_REMEMBERED_ROUTES: Int = 4096
