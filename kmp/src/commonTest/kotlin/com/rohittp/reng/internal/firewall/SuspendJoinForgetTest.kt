package com.rohittp.reng.internal.firewall

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test

/**
 * A cancelled owner forgets its entry by queueing for the lock, not by spinning on `tryLock`.
 *
 * kotlinx `Mutex.unlock` hands the lock directly to a queued suspended waiter. When that waiter and
 * the cancelled owner share one thread, a spinning owner occupies the thread the waiter needs to run
 * and release the lock, and neither ever finishes. Against the spinning `forget` this times out.
 */
class SuspendJoinForgetTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aCancelledOwnerDoesNotStarveAWaiterQueuedOnTheSameThread() = runTest {
        withContext(Dispatchers.Default) {
            val mutex = Mutex()
            val join = SuspendJoin<String, Int>(mutex)
            val oneThread = Dispatchers.Default.limitedParallelism(1)
            // Not children of this test: against a spinning `forget` they never finish, and a parent
            // would wait for them forever instead of letting the timeout below fail the test.
            val scope = CoroutineScope(oneThread)

            val ownerEntered = CompletableDeferred<Unit>()
            val owner = scope.launch {
                join.run("owned") {
                    ownerEntered.complete(Unit)
                    awaitCancellation()
                }
            }
            ownerEntered.await()

            // Held from another thread, so the waiter below must queue behind it.
            mutex.lock()
            val waiter = scope.launch { join.invalidate("other") }
            // The single thread runs the waiter up to its suspension inside `lock` before this.
            scope.launch {}.join()

            owner.cancel()
            // Let the owner reach `forget` and contend for the held lock.
            delay(100)
            mutex.unlock()

            withTimeout(5_000) {
                waiter.join()
                owner.join()
            }
        }
    }
}
