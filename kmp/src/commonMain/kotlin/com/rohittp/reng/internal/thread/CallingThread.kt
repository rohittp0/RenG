package com.rohittp.reng.internal.thread

/**
 * The thread a renderer was created on or last adopted a context on (ADR 0056).
 *
 * Opaque on purpose: a thread identifier is not a resource, means nothing outside this process, and
 * nothing in RenG may put one in a diagnostic. The only question anyone asks of it is
 * [isCurrentThread].
 */
internal expect class CallingThread

/** The thread this call is on. */
internal expect fun currentCallingThread(): CallingThread

/**
 * Whether this call is on the thread [CallingThread] names.
 *
 * A predicate rather than an equality, because the native targets answer it by the identity of a
 * per-thread `@ThreadLocal` token, which has no value to compare.
 */
internal expect fun CallingThread.isCurrentThread(): Boolean
