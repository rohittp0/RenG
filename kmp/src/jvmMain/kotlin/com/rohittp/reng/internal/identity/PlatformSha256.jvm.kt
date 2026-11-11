package com.rohittp.reng.internal.identity

import java.security.MessageDigest

/**
 * SHA-256 through `MessageDigest`, which uses the CPU's SHA-2 instructions where the JVM supports
 * them.
 *
 * One instance per thread rather than one per call. `MessageDigest` is stateful and RenG hashes from
 * whatever thread a preparation resumed on, which is why it was fresh per call -- but a thread-local
 * instance is never shared between threads, and `digest(bytes)` resets it before returning, so each
 * call starts from the same state a fresh instance would. `getInstance` walks the security providers
 * every time, and a `prepare` hashes tens of times.
 */
internal actual fun platformSha256(bytes: ByteArray): ByteArray? = threadDigest.get().digest(bytes)

private val threadDigest: ThreadLocal<MessageDigest> = ThreadLocal.withInitial { MessageDigest.getInstance("SHA-256") }
