package com.rohittp.reng.internal.bench

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The opt-in benchmark harness every `*BenchmarkTest` reports through.
 *
 * `kmp/build.gradle.kts` keeps those classes out of every test task unless Gradle runs with
 * `-Preng.benchmarks=true`, and then runs them alone. Each measurement prints exactly one line,
 *
 *     RENG-BENCH <name> median_ns=<n> p90_ns=<n> iterations=<n>
 *
 * over the measured iterations of the operation, after warm-up. Only the work inside
 * [BenchIteration.timed] is counted, so an iteration may rebuild its fixture untimed. Whatever an
 * iteration [BenchIteration.fold]s goes into a checksum printed on a separate `RENG-BENCH-INFO` line,
 * which is what stops an optimiser dropping the measured work.
 *
 * Names are stable: a before/after comparison joins on them, so rename one only on purpose.
 */
internal object RengBench {
    /** Every benchmark's checksum, folded together; read once per report so it is never dead. */
    private var sink: Long = 0L

    internal class Result(val name: String, val medianNanos: Long, val p90Nanos: Long, val iterations: Int)

    /**
     * Runs [body] [warmupIterations] times untimed, then measured until [maximumIterations] have run, or
     * [minimumIterations] have run and [budget] of wall time (fixture rebuilds included) has passed.
     */
    internal fun measure(
        name: String,
        warmupIterations: Int = 3,
        minimumIterations: Int = 5,
        maximumIterations: Int = 400,
        budget: Duration = 2.seconds,
        body: BenchIteration.(iteration: Int) -> Unit,
    ): Result {
        var iteration = 0
        var checksum = 0L
        repeat(warmupIterations) {
            val warmup = BenchIteration()
            warmup.body(iteration++)
            checksum = checksum * 31 + warmup.checksum
        }
        val samples = ArrayList<Long>()
        val started = TimeSource.Monotonic.markNow()
        while (samples.size < maximumIterations &&
            (samples.size < minimumIterations || started.elapsedNow() < budget)
        ) {
            val measured = BenchIteration()
            measured.body(iteration++)
            samples += measured.elapsedNanos
            checksum = checksum * 31 + measured.checksum
        }
        return report(name, samples, checksum)
    }

    /** Prints the contract line for samples a benchmark timed itself (one per measured iteration). */
    internal fun report(name: String, samples: List<Long>, checksum: Long = 0L): Result {
        require(samples.isNotEmpty()) { "$name measured nothing" }
        require(name.none { it.isWhitespace() }) { "a benchmark name is one token: '$name'" }
        val sorted = samples.sorted()
        val median = if (sorted.size % 2 == 1) {
            sorted[sorted.size / 2]
        } else {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
        }
        // Nearest rank: the smallest sample at or above 90% of them.
        val p90 = sorted[((sorted.size * 9 + 9) / 10 - 1).coerceIn(0, sorted.lastIndex)]
        sink = sink * 31 + checksum
        println("RENG-BENCH $name median_ns=$median p90_ns=$p90 iterations=${samples.size}")
        println("RENG-BENCH-INFO $name checksum=$checksum sink=$sink")
        return Result(name, median, p90, samples.size)
    }

    /** A free-form note beside a benchmark (fixture sizes, counts), never parsed as a measurement. */
    internal fun info(name: String, message: String) {
        println("RENG-BENCH-INFO $name $message")
    }
}

/** One iteration of a [RengBench.measure] body: what it timed, and what it folded. */
internal class BenchIteration {
    internal var elapsedNanos: Long = 0L
    internal var checksum: Long = 0L

    /** Times [block] and adds it to this iteration; an iteration may time several disjoint blocks. */
    internal inline fun <T> timed(block: () -> T): T {
        val mark = TimeSource.Monotonic.markNow()
        val result = block()
        elapsedNanos += mark.elapsedNow().inWholeNanoseconds
        return result
    }

    internal fun fold(value: Long) {
        checksum = checksum * 31 + value
    }

    internal fun fold(value: Int) = fold(value.toLong())

    internal fun fold(value: Boolean) = fold(if (value) 1L else 0L)
}
