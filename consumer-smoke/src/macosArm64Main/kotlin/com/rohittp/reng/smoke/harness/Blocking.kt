package com.rohittp.reng.smoke.harness

import kotlin.concurrent.Volatile
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import platform.posix.usleep

/**
 * Runs [block] to completion and returns its result, blocking the calling thread.
 *
 * Hand-rolled rather than `kotlinx.coroutines.runBlocking` on purpose. RenG takes coroutines as an
 * `implementation` dependency (ADR 0019), so the library is on the consumer's runtime classpath but
 * not its compile classpath -- which is the correct shape: `prepare` is `suspend`, and nothing in
 * RenG's public API mentions a coroutines type. A consumer that already uses coroutines simply
 * calls `prepare` from its own scope; this harness deliberately adds no dependency at all, and the
 * price is these twenty lines.
 *
 * `prepare` hops to `Dispatchers.Default` internally and resumes on whichever worker finished, so
 * the completion below may run on another thread; the poll loop is the wait. `draw` is not
 * suspending and stays on the thread holding the Render Context, which is what RenG requires.
 */
internal fun <T> runBlockingHarness(block: suspend () -> T): T {
    val completion = PollableCompletion<T>()
    block.startCoroutine(completion)
    while (!completion.done) {
        usleep(200u)
    }
    completion.failure?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return completion.value as T
}

private class PollableCompletion<T> : Continuation<T> {
    @Volatile
    var value: Any? = null

    @Volatile
    var failure: Throwable? = null

    @Volatile
    var done: Boolean = false

    override val context = EmptyCoroutineContext

    override fun resumeWith(result: Result<T>) {
        result.fold(
            onSuccess = { value = it },
            onFailure = { failure = it },
        )
        done = true
    }
}
