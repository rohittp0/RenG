package com.rohittp.reng.internal.resource

import com.rohittp.reng.internal.bench.RengBench
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * B4: the resource scheduler driven to completion over many distinct routes, through the same
 * [ResourceOperationScaleDriver] [ResourceOperationScaleTest] guards with. One operation is one
 * iteration.
 *
 * - `session.routes2048.concurrent8`: the production shape -- one owner, eight routes at a time, the
 *   class-gate pump run after every event;
 * - `styled_session.routes2048`: the same with a basemap style every owner joins, so the style-commit
 *   pump runs too;
 * - `session_waves.routes1024.concurrent1024`: every route eligible at once and the pump held back until
 *   the action queue drains, so about a thousand routes are pending when the pump runs -- the shape that
 *   makes each pump call scan every pending route;
 * - `pure.routes64`: the pure reducer path (`start` / `transition`), whose per-event rebuild is quadratic.
 */
class ResourceOperationScaleBenchmarkTest {
    private val driver = ResourceOperationScaleDriver()

    @Test
    fun drivesManyDistinctRoutesToCompletion() {
        measure("b4.resource_scheduler.session.routes2048.concurrent8") {
            driver.driveDistinctRoutesThroughSession(routeCount = 2048)
        }
        measure("b4.resource_scheduler.styled_session.routes2048") {
            driver.driveStyledRoutesThroughSession(routeCount = 2048)
        }
        measure("b4.resource_scheduler.session_waves.routes1024.concurrent1024") {
            driver.driveDistinctRoutesThroughSessionInWaves(routeCount = 1024, maximumConcurrentRoutes = 1024)
        }
        measure("b4.resource_scheduler.pure.routes64", minimumIterations = 3) {
            driver.driveDistinctRoutesToCompletion(routeCount = 64)
        }
    }

    private fun measure(name: String, minimumIterations: Int = 5, operation: () -> Long) {
        RengBench.measure(name, warmupIterations = 2, minimumIterations = minimumIterations, budget = 3.seconds) {
            // Timed here in nanoseconds; the driver's own whole-millisecond answer only feeds the checksum.
            fold(timed { operation() })
        }
    }
}
