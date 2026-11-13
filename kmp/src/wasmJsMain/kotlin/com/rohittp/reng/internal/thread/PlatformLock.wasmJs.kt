package com.rohittp.reng.internal.thread

internal actual class PlatformLock {
    private var depth: Int = 0

    internal actual fun <T> withLock(block: () -> T): T {
        lock()
        try {
            return block()
        } finally {
            unlock()
        }
    }

    internal actual fun lock() {
        depth += 1
    }

    internal actual fun unlock() {
        check(depth > 0) { "unlock without a matching lock" }
        depth -= 1
    }
}
