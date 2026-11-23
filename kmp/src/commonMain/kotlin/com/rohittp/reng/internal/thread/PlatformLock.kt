package com.rohittp.reng.internal.thread

/**
 * A small blocking, reentrant lock for non-suspending renderer state.
 *
 * GL-bound calls are synchronous and can legitimately take hundreds of milliseconds, so their
 * serialization cannot be implemented by spinning on a coroutine mutex. Registry critical
 * sections use the same primitive and release it before invoking a GL binding.
 */
internal expect class PlatformLock() {
    internal fun <T> withLock(block: () -> T): T

    /** Acquires the lock; every call must be paired with exactly one [unlock] on the same thread. */
    internal fun lock()

    internal fun unlock()
}

/**
 * [PlatformLock.withLock] inlined into the caller, so a critical section on a hot path allocates no
 * closure for the variables it captures.
 */
internal inline fun <T> PlatformLock.locked(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}
