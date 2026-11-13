@file:OptIn(
    kotlin.js.ExperimentalWasmJsInterop::class,
    kotlin.wasm.unsafe.UnsafeWasmMemoryApi::class,
)

package com.rohittp.reng.demo

import com.rohittp.reng.Backdrop
import com.rohittp.reng.BlendMode
import com.rohittp.reng.Bloom
import com.rohittp.reng.Camera
import com.rohittp.reng.Diagnostic
import com.rohittp.reng.DiagnosticSink
import com.rohittp.reng.FramePlan
import com.rohittp.reng.FramebufferName
import com.rohittp.reng.Geometry
import com.rohittp.reng.OutputPixelSize
import com.rohittp.reng.RawResourceKey
import com.rohittp.reng.RenGMetricName
import com.rohittp.reng.Renderer
import com.rohittp.reng.RendererConfiguration
import com.rohittp.reng.ResourceLocator
import com.rohittp.reng.ShaderPair
import com.rohittp.reng.Store
import com.rohittp.reng.StoredRawResource
import com.rohittp.reng.Transport
import com.rohittp.reng.TransportRequest
import com.rohittp.reng.TransportResponse
import com.rohittp.reng.TransportResponseMetadata
import com.rohittp.reng.Vector3
import com.rohittp.reng.createRenderer
import com.rohittp.reng.installBasemapTileRenderer
import com.rohittp.reng.makeWebGl2ContextCurrent
import com.rohittp.rentile.BasemapRasterizer
import com.rohittp.rentile.CredentialProvider
import com.rohittp.rentile.ExecutionPolicy
import com.rohittp.rentile.MapSessionProvider
import com.rohittp.rentile.PreparedStyle
import com.rohittp.rentile.RasterAcquisitionDensity
import com.rohittp.rentile.RawResourceKey as TileRawResourceKey
import com.rohittp.rentile.RawResourceStore as TileRawResourceStore
import com.rohittp.rentile.RenderOptions
import com.rohittp.rentile.RenderPriority
import com.rohittp.rentile.Rentile
import com.rohittp.rentile.RentileClock
import com.rohittp.rentile.RentileConfiguration
import com.rohittp.rentile.ResourceAccessMode
import com.rohittp.rentile.ResourceTransport as TileResourceTransport
import com.rohittp.rentile.StoredRawResource as TileStoredRawResource
import com.rohittp.rentile.StyleInput
import com.rohittp.rentile.TileId
import com.rohittp.rentile.TileSubstitutionPolicy
import com.rohittp.rentile.TransportRequest as TileTransportRequest
import com.rohittp.rentile.TransportResponse as TileTransportResponse
import com.rohittp.rentile.TransportResponseMetadata as TileTransportResponseMetadata
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.js.JsAny
import kotlin.js.JsString
import kotlin.js.Promise
import kotlin.js.toJsString
import kotlin.math.PI
import kotlin.math.cos
import kotlin.wasm.unsafe.withScopedMemoryAllocator

// Served from the demo's own origin: the catalogue that hosts the app's styles sends no CORS header.
// `?style=<absolute url>` overrides it.
private val BASEMAP_STYLE_URL: String get() = basemapStyleUrl().toString()
private const val LOOP_MILLIS = 24_000.0

fun main() {
    if (isTileWorkerScope()) {
        startTileWorker()
        return
    }
    if (isWorkerScope()) {
        waitForWorkerSetup { setup ->
            try {
                startDemo(prepareWorker(setup))
            } catch (failure: Throwable) {
                showFailure(failure)
            }
        }
        return
    }

    try {
        val browser = prepareBrowser()
        if (browser.workerStarted) return
        startDemo(browser)
    } catch (failure: Throwable) {
        showFailure(failure)
    }
}

private fun startDemo(browser: BrowserSetup) {
    try {
        installTileWorkerPoolIfRequested()
        val context = browser.context ?: error("canvas.getContext(\"webgl2\") returned null")
        makeWebGl2ContextCurrent(context)

        Demo(
            width = browser.width,
            height = browser.height,
            rendererName = browser.renderer.toString(),
            basemapEnabled = !browser.basemapOff,
        ).start()
    } catch (failure: Throwable) {
        showFailure(failure)
    }
}

private class Demo(
    width: Int,
    height: Int,
    private val rendererName: String,
    private val basemapEnabled: Boolean,
) {
    private val scope = MainScope()
    private val outputSize = "${width}x$height"
    private val labelsEnabled = basemapEnabled && !labelsOff()
    private val diagnostics = ArrayList<String>()
    private val renderer: Renderer = createRenderer(
        configuration = RendererConfiguration(
            outputPixelSize = OutputPixelSize(width = width, height = height),
            transport = BrowserTransport(),
            store = MemoryStore(),
            basemapStyle = ResourceLocator(value = BASEMAP_STYLE_URL),
            diagnosticSink = DiagnosticSink(::recordDiagnostic),
            maximumReadAheadRasterisedTileBytes = if (readAheadOn()) 256L * 1024 * 1024 else 0L,
        ),
    )
    private val renderTarget = renderer.mintRenderTarget(
        framebufferName = FramebufferName(value = 0u),
    )

    private var preparing = false
    private var failed = false
    private var nextFrameIndex = 0L
    private var drawnFrames = 0L
    private var prepareMillis = 0.0
    private var drawMillis = 0.0
    private var framesPerSecond = 0.0
    private var fpsWindowStart = performanceNow()
    private var fpsWindowFrames = 0
    private val readAheadEnabled = readAheadOn()

    fun start() {
        setStatus(statusText(), isError = false)
        val benchFrames = benchFrameCount()
        if (benchFrames > 0) {
            scope.launch {
                try {
                    runBenchmark(frames = benchFrames)
                } catch (failure: Throwable) {
                    showFailure(failure)
                }
            }
        } else {
            traceEndMillis = traceSeconds().let { if (it > 0) performanceNow() + it * 1000.0 else -1.0 }
            if (traceEndMillis > 0) startLongTaskObserver()
            requestFrame(::onAnimationFrame)
        }
    }

    // `?trace=SECONDS`: record every frame of the live animation, then report.
    private var traceEndMillis = -1.0
    private var traceTicks = 0
    private var traceSkippedTicks = 0
    private val traceFrames = ArrayList<String>()

    private fun counter(name: RenGMetricName): Long = renderer.queryMetrics().counters[name] ?: 0L

    private fun finishTrace() {
        val report = "{\"size\":\"$outputSize\",\"style\":\"$BASEMAP_STYLE_URL\",\"labels\":$labelsEnabled," +
            "\"ticks\":$traceTicks,\"skippedTicks\":$traceSkippedTicks," +
            "\"longTasks\":${longTasksJson()},\"frames\":[${traceFrames.joinToString(",")}]}"
        consoleLog("TRACE done".toJsString())
        setBenchResult(report)
    }

    /**
     * `?bench=N`: the same camera flight as the animation, sampled at N fixed points instead of by
     * wall-clock time, run twice. The first pass is cold (style, tiles and glyphs come over the
     * network); the second runs with every raw resource already in the in-memory store, so it
     * measures RenG's own work. Each frame is presented (one animation frame) before the next.
     */
    private suspend fun runBenchmark(frames: Int) {
        val passes = ArrayList<String>()
        var frameIndex = 0L
        for (pass in listOf("cold", "warm")) {
            val prepare = DoubleArray(frames)
            val draw = DoubleArray(frames)
            val tiles = LongArray(frames)
            val before = renderer.queryMetrics().counters
            val passStart = performanceNow()
            for (sample in 0 until frames) {
                val counters = renderer.queryMetrics().counters
                val currentFrameIndex = frameIndex++
                val plan = framePlanAt(frameIndex = currentFrameIndex, phase = sample.toDouble() / frames)
                val prepareStart = performanceNow()
                val prepared = renderer.prepare(plan = plan)
                prepare[sample] = performanceNow() - prepareStart
                readAheadBenchmark(frameIndex = currentFrameIndex, sample = sample, frames = frames)
                try {
                    val drawStart = performanceNow()
                    renderer.draw(preparedFrame = prepared, renderTarget = renderTarget)
                    draw[sample] = performanceNow() - drawStart
                } finally {
                    prepared.close()
                }
                tiles[sample] = (renderer.queryMetrics().counters[RenGMetricName.ENGINE_TILES_RENDERED] ?: 0L) -
                    (counters[RenGMetricName.ENGINE_TILES_RENDERED] ?: 0L)
                drawnFrames++
                setStatus("bench $pass ${sample + 1}/$frames", isError = false)
                awaitAnimationFrame()
            }
            val wall = performanceNow() - passStart
            val after = renderer.queryMetrics().counters
            fun delta(name: RenGMetricName): Long = (after[name] ?: 0L) - (before[name] ?: 0L)
            val sorted = prepare.sortedArray()
            passes += buildString {
                append("{\"pass\":\"$pass\",\"frames\":$frames")
                append(",\"wallMs\":${wall.roundTo1()},\"fps\":${(frames * 1000.0 / wall).roundTo1()}")
                append(",\"prepareAvgMs\":${prepare.average().roundTo1()}")
                append(",\"prepareP50Ms\":${sorted[frames / 2].roundTo1()}")
                append(",\"prepareP95Ms\":${sorted[(frames * 95) / 100].roundTo1()}")
                append(",\"prepareMaxMs\":${sorted.last().roundTo1()}")
                append(",\"drawAvgMs\":${draw.average().roundTo1()}")
                append(",\"tilesRendered\":${delta(RenGMetricName.ENGINE_TILES_RENDERED)}")
                append(",\"tileDrawMs\":${(delta(RenGMetricName.ENGINE_TILE_DRAW_NANOS) / 1e6).roundTo1()}")
                append(",\"tilePngEncodeMs\":${(delta(RenGMetricName.ENGINE_TILE_PNG_ENCODE_NANOS) / 1e6).roundTo1()}")
                append(",\"tilePngBytes\":${delta(RenGMetricName.ENGINE_TILE_PNG_BYTES)}")
                append(",\"renderedTileCacheHits\":${delta(RenGMetricName.RENDERED_TILE_CACHE_HITS)}")
                append(",\"resourceRequests\":${delta(RenGMetricName.ENGINE_RESOURCE_REQUESTS)}")
                append(",\"wireBytes\":${delta(RenGMetricName.ENGINE_RESOURCE_WIRE_BYTES)}")
                append(",\"perFramePrepareMs\":[${prepare.joinToString(",") { it.roundTo1().toString() }}]")
                append(",\"perFrameTiles\":[${tiles.joinToString(",")}]")
                append("}")
            }
        }
        val report = "{\"size\":\"${outputSize}\",\"basemap\":$basemapEnabled,\"labels\":$labelsEnabled," +
            "\"style\":\"$BASEMAP_STYLE_URL\",\"passes\":[${passes.joinToString(",")}]}"
        consoleLog("BENCH $report".toJsString())
        setBenchResult(report)
        setStatus("bench done", isError = false)
    }

    private fun onAnimationFrame(timestampMillis: Double) {
        if (traceEndMillis > 0 && performanceNow() >= traceEndMillis) {
            if (!preparing) {
                traceEndMillis = -1.0
                finishTrace()
                return
            }
        }
        requestFrame(::onAnimationFrame)
        if (traceEndMillis > 0) traceTicks++
        if (preparing || failed) {
            if (traceEndMillis > 0) traceSkippedTicks++
            return
        }

        preparing = true
        val frameIndex = nextFrameIndex++
        scope.launch {
            try {
                val tracing = traceEndMillis > 0
                val tiles0 = if (tracing) counter(RenGMetricName.ENGINE_TILES_RENDERED) else 0L
                val requests0 = if (tracing) counter(RenGMetricName.ENGINE_RESOURCE_REQUESTS) else 0L
                val bytes0 = if (tracing) counter(RenGMetricName.ENGINE_RESOURCE_WIRE_BYTES) else 0L
                val tileNanos0 = if (tracing) counter(RenGMetricName.ENGINE_TILE_DRAW_NANOS) else 0L
                val plan = framePlan(frameIndex = frameIndex, timestampMillis = timestampMillis)
                val prepareStart = performanceNow()
                val prepared = renderer.prepare(plan = plan)
                val prepareEnd = performanceNow()
                prepareMillis += prepareEnd - prepareStart
                readAheadLive(frameIndex = frameIndex, timestampMillis = timestampMillis)
                var drawEnd: Double
                try {
                    val drawStart = performanceNow()
                    renderer.draw(preparedFrame = prepared, renderTarget = renderTarget)
                    drawEnd = performanceNow()
                    drawMillis += drawEnd - drawStart
                } finally {
                    prepared.close()
                }
                if (tracing) {
                    traceFrames += "[${timestampMillis.roundTo1()},${prepareStart.roundTo1()},${prepareEnd.roundTo1()}," +
                        "${drawEnd.roundTo1()},${counter(RenGMetricName.ENGINE_TILES_RENDERED) - tiles0}," +
                        "${counter(RenGMetricName.ENGINE_RESOURCE_REQUESTS) - requests0}," +
                        "${counter(RenGMetricName.ENGINE_RESOURCE_WIRE_BYTES) - bytes0}," +
                        "${((counter(RenGMetricName.ENGINE_TILE_DRAW_NANOS) - tileNanos0) / 1e6).roundTo1()}]"
                }

                drawnFrames++
                updateFramesPerSecond()
                setStatus(statusText(), isError = false)
            } catch (failure: Throwable) {
                failed = true
                showFailure(failure)
            } finally {
                preparing = false
            }
        }
    }

    private fun framePlan(frameIndex: Long, timestampMillis: Double): FramePlan =
        framePlanAt(frameIndex = frameIndex, phase = (timestampMillis % LOOP_MILLIS) / LOOP_MILLIS)

    private fun readAheadLive(frameIndex: Long, timestampMillis: Double) {
        if (!readAheadEnabled) return
        renderer.readAhead(
            plans = (1L..8L).map { step ->
                framePlan(frameIndex = frameIndex + step, timestampMillis = timestampMillis + step * 250.0)
            },
        )
    }

    private fun readAheadBenchmark(frameIndex: Long, sample: Int, frames: Int) {
        if (!readAheadEnabled) return
        val count = minOf(8, frames - sample - 1)
        if (count == 0) return
        renderer.readAhead(
            plans = (1..count).map { step ->
                framePlanAt(
                    frameIndex = frameIndex + step,
                    phase = (sample + step).toDouble() / frames,
                )
            },
        )
    }

    private fun framePlanAt(frameIndex: Long, phase: Double): FramePlan {
        val travel = 0.5 - 0.5 * cos(phase * 2.0 * PI)
        return FramePlan(
            frameIndex = frameIndex,
            camera = Camera(
                latitude = 50.4 + (48.8566 - 50.4) * travel,
                unwrappedLongitude = 1.1 + (2.3522 - 1.1) * travel,
                zoom = 3.2 + (10.8 - 3.2) * travel,
                bearing = phase * 360.0,
                pitch = 5.0 + 40.0 * travel,
            ),
            drawBasemap = basemapEnabled,
            drawLabels = labelsEnabled,
            geometries = listOf(ROUTE_GEOMETRY),
            backdrop = if (basemapEnabled) null else SOLID_BACKDROP,
            bloom = ROUTE_BLOOM,
        )
    }

    private fun recordDiagnostic(diagnostic: Diagnostic) {
        diagnostics += diagnostic.toString()
        if (diagnostics.size > 3) diagnostics.removeAt(0)
    }

    private fun updateFramesPerSecond() {
        fpsWindowFrames++
        val now = performanceNow()
        val elapsed = now - fpsWindowStart
        if (elapsed >= 1_000.0) {
            framesPerSecond = fpsWindowFrames * 1_000.0 / elapsed
            fpsWindowFrames = 0
            fpsWindowStart = now
        }
    }

    private fun statusText(): String = buildString {
        appendLine("renderer: $rendererName")
        appendLine("frame: $drawnFrames")
        appendLine("fps: ${formatNumber(framesPerSecond)}")
        appendLine("prepare avg: ${average(prepareMillis)} ms")
        appendLine("draw avg: ${average(drawMillis)} ms")
        append("basemap: ${if (basemapEnabled) "on" else "off"}")
        if (diagnostics.isNotEmpty()) {
            appendLine()
            append("diagnostics: ")
            append(diagnostics.joinToString(separator = "\n  "))
        }
    }

    private fun average(totalMillis: Double): String =
        if (drawnFrames == 0L) "0.0" else formatNumber(totalMillis / drawnFrames)
}

private class MemoryStore : Store {
    private val resources = HashMap<RawResourceKey, StoredRawResource>()

    override suspend fun read(key: RawResourceKey): StoredRawResource? = resources[key]

    override suspend fun write(key: RawResourceKey, resource: StoredRawResource) {
        resources[key] = resource
    }
}

private class BrowserTransport : Transport {
    override suspend fun execute(request: TransportRequest): TransportResponse {
        val payload = fetchResource(
            url = request.locator.value.toJsString(),
            method = "GET".toJsString(),
            headers = requestHeaders(
                ifNoneMatch = request.metadata.ifNoneMatch?.toJsString(),
                ifModifiedSince = request.metadata.ifModifiedSince?.toJsString(),
                accept = request.metadata.accept?.toJsString(),
            ),
        ).await()

        require(payload.body.length.toLong() <= request.maximumResponseBytes) {
            "response exceeded the ${request.maximumResponseBytes}-byte limit"
        }
        val body = payload.body.copyToByteArray()

        return TransportResponse(
            statusCode = payload.statusCode,
            body = body,
            metadata = TransportResponseMetadata(
                contentType = payload.contentType?.toString(),
                etag = payload.etag?.toString(),
                lastModified = payload.lastModified?.toString(),
                freshUntilEpochMillis = payload.freshUntilEpochMillis
                    .takeIf { it >= 0.0 }
                    ?.toLong(),
            ),
        )
    }
}

private fun JsUint8Array.copyToByteArray(): ByteArray {
    if (length == 0) return ByteArray(0)
    return withScopedMemoryAllocator { allocator ->
        val pointer = allocator.allocate(length)
        copyJsBytesToLinearMemory(this, pointer.address.toInt(), length)
        ByteArray(length) { index -> (pointer + index).loadByte() }
    }
}

private var tileWorkerPool: TileWorkerPool? = null

private fun installTileWorkerPoolIfRequested() {
    val workerCount = tileWorkerCount().coerceAtLeast(0)
    if (workerCount == 0) return
    val pool = createTileWorkerPool(
        workerCount = workerCount,
        skipSkia = skipSkia(),
        compareCount = if (skipSkia()) compareTileCount().coerceAtLeast(0) else 0,
    )
    tileWorkerPool = pool
    installBasemapTileRenderer { styleLocator, zoom, column, row, outputSizePx ->
        requestTileFromPool(pool, styleLocator.toJsString(), zoom, column, row, outputSizePx)
            .await()
            .copyToByteArray()
    }
}

private fun startTileWorker() {
    val scope = MainScope()
    val renderer = TileWorkerRenderer(skipSkia = tileWorkerSkipsSkia())
    installTileWorkerMessageHandler { request ->
        scope.launch {
            try {
                val rgba = renderer.render(
                    styleLocator = request.style.toString(),
                    tile = TileId(request.zoom, request.column, request.row),
                    outputSizePx = request.outputSizePx,
                    compare = request.compare,
                )
                postTileWorkerSuccess(request.id, rgba)
            } catch (failure: Throwable) {
                val type = failure::class.simpleName ?: "Throwable"
                postTileWorkerFailure(request.id, "$type: ${failure.message ?: "no message"}".toJsString())
            }
        }
    }
}

private class TileWorkerRenderer(private val skipSkia: Boolean) {
    private val engine: BasemapRasterizer = Rentile.create(
        RentileConfiguration(
            transport = TileWorkerTransport(),
            rawResourceStore = TileWorkerStore(),
            sessionProvider = MapSessionProvider.None,
            credentialProvider = CredentialProvider.None,
            clock = RentileClock.System,
            executionPolicy = ExecutionPolicy(maxConcurrentMetatileWorkers = 1),
        ),
    )
    private val styles = HashMap<String, PreparedStyle>()
    private val reportedBrowserFallbacks = HashSet<String>()

    suspend fun render(
        styleLocator: String,
        tile: TileId,
        outputSizePx: Int,
        compare: Boolean,
    ): ByteArray {
        if (skipSkia) {
            val browser = renderBrowserRaster(
                styleLocator.toJsString(),
                tile.z,
                tile.x,
                tile.y,
                outputSizePx,
            ).await()
            val browserBytes = browser.rgba?.copyToByteArray()
            if (browser.supported && browserBytes != null) {
                if (compare) comparePixels(tile, browserBytes, renderWithRentile(styleLocator, tile, outputSizePx))
                return browserBytes
            }
            if (reportedBrowserFallbacks.add(styleLocator)) {
                postTileWorkerNotice(
                    ("Skia skip unavailable for $styleLocator; using Rentile: " +
                        (browser.reason?.toString() ?: "style is not a single default raster layer")).toJsString(),
                )
            }
        }
        return renderWithRentile(styleLocator, tile, outputSizePx)
    }

    private suspend fun renderWithRentile(styleLocator: String, tile: TileId, outputSizePx: Int): ByteArray {
        val style = styles[styleLocator] ?: engine.prepare(StyleInput.Remote(styleLocator)).also {
            styles[styleLocator] = it
        }
        val batch = engine.prepareBatch(
            style = style,
            tiles = listOf(tile),
            options = RenderOptions(
                outputSizePx = outputSizePx,
                rasterAcquisitionDensity = RasterAcquisitionDensity.OUTPUT_ZOOM,
            ),
            resourceAccess = ResourceAccessMode.NORMAL,
            substitutionPolicy = TileSubstitutionPolicy.Disabled,
        )
        return try {
            engine.renderRaw(batch, tiles = listOf(tile), priority = RenderPriority.NORMAL)
                .tiles
                .single()
                .rgbaBytes
        } finally {
            batch.close()
        }
    }

    private fun comparePixels(tile: TileId, browser: ByteArray, skia: ByteArray) {
        require(browser.size == skia.size) {
            "comparison sizes differ: browser=${browser.size}, Skia=${skia.size}"
        }
        var absoluteTotal = 0L
        var maximum = 0
        var pixelsOverEight = 0
        for (pixelStart in browser.indices step 4) {
            var pixelOverEight = false
            for (channel in 0..3) {
                val difference = kotlin.math.abs(
                    (browser[pixelStart + channel].toInt() and 0xff) -
                        (skia[pixelStart + channel].toInt() and 0xff),
                )
                absoluteTotal += difference
                maximum = maxOf(maximum, difference)
                if (difference > 8) pixelOverEight = true
            }
            if (pixelOverEight) pixelsOverEight++
        }
        val mean = absoluteTotal.toDouble() / browser.size
        val share = pixelsOverEight.toDouble() / (browser.size / 4)
        consoleLog(
            ("RASTER COMPARE ${tile.z}/${tile.x}/${tile.y}: " +
                "meanAbs=${mean.roundTo1()}, maxAbs=$maximum, " +
                "pixelsOver8=${(share * 100.0).roundTo1()}%").toJsString(),
        )
    }
}

private class TileWorkerStore : TileRawResourceStore {
    private val resources = HashMap<TileRawResourceKey, TileStoredRawResource>()

    override suspend fun read(key: TileRawResourceKey): TileStoredRawResource? = resources[key]

    override suspend fun write(key: TileRawResourceKey, resource: TileStoredRawResource) {
        resources[key] = resource
    }

    override suspend fun remove(key: TileRawResourceKey) {
        resources.remove(key)
    }
}

private class TileWorkerTransport : TileResourceTransport {
    override suspend fun execute(request: TileTransportRequest): TileTransportResponse {
        val payload = fetchResource(
            url = request.url.toJsString(),
            method = "GET".toJsString(),
            headers = requestHeaders(null, null, null),
        ).await()
        require(payload.body.length.toLong() <= request.maxResponseBytes) {
            "response exceeded the ${request.maxResponseBytes}-byte limit"
        }
        return TileTransportResponse(
            statusCode = payload.statusCode,
            body = payload.body.copyToByteArray(),
            metadata = TileTransportResponseMetadata(
                contentType = payload.contentType?.toString(),
                etag = payload.etag?.toString(),
                lastModified = payload.lastModified?.toString(),
                expiresAtEpochMillis = payload.freshUntilEpochMillis.takeIf { it >= 0.0 }?.toLong(),
                wireByteCount = payload.body.length.toLong(),
            ),
        )
    }
}

private fun showFailure(failure: Throwable) {
    val type = failure::class.simpleName ?: "Throwable"
    val text = "$type: ${failure.message ?: "no message"}"
    setStatus(text, isError = true)
    consoleError(text.toJsString())
}

private fun Double.roundTo1(): Double = kotlin.math.round(this * 10.0) / 10.0

private suspend fun awaitAnimationFrame() {
    suspendCancellableCoroutine { continuation ->
        requestFrame { continuation.resume(Unit) }
    }
}

private fun formatNumber(value: Double): String = ((value * 10.0).toInt() / 10.0).toString()

private val ROUTE_GEOMETRY = Geometry(
    // A small glowing marker over central Paris, where the camera ends its flight.
    topLeft = Vector3(x = 48.872, y = 2.322, z = 300.0),
    bottomRight = Vector3(x = 48.846, y = 2.372, z = 300.0),
    shaderPair = ShaderPair(
        vertexSource = GEOMETRY_VERTEX_SHADER,
        fragmentSource = ROUTE_FRAGMENT_SHADER,
    ),
    emissive = true,
    blend = BlendMode.OVER,
    bloomReach = 0.7f,
)

private val ROUTE_BLOOM = Bloom(
    intensity = 1.4f,
    insideSuppression = 0.15f,
    whiteness = 0.1f,
    reachGain = 1.2f,
)

private val SOLID_BACKDROP = Backdrop.Shader(
    shaderPair = ShaderPair(
        vertexSource = BACKDROP_VERTEX_SHADER,
        fragmentSource = BACKDROP_FRAGMENT_SHADER,
    ),
)

private const val GEOMETRY_VERTEX_SHADER = """#version 300 es
in vec3 aPosition;
uniform mat4 uModelViewProjection;
void main() {
    gl_Position = uModelViewProjection * vec4(aPosition, 1.0);
}
"""

private const val ROUTE_FRAGMENT_SHADER = """#version 300 es
precision highp float;
out vec4 rengGeometryColour;
void main() {
    rengGeometryColour = vec4(0.10, 0.88, 1.0, 0.88);
}
"""

private const val BACKDROP_VERTEX_SHADER = """#version 300 es
layout(location = 0) in vec2 aPosition;
void main() {
    gl_Position = vec4(aPosition, 0.0, 1.0);
}
"""

private const val BACKDROP_FRAGMENT_SHADER = """#version 300 es
precision highp float;
out vec4 rengBackdropColour;
void main() {
    rengBackdropColour = vec4(0.025, 0.055, 0.095, 1.0);
}
"""

private external interface BrowserSetup : JsAny {
    val context: JsAny?
    val width: Int
    val height: Int
    val renderer: JsString
    val basemapOff: Boolean
    val workerStarted: Boolean
}

private external interface WorkerSetup : JsAny {
    val canvas: JsAny
    val width: Int
    val height: Int
    val query: JsString
}

private external interface FetchPayload : JsAny {
    val statusCode: Int
    val body: JsUint8Array
    val contentType: JsString?
    val etag: JsString?
    val lastModified: JsString?
    val freshUntilEpochMillis: Double
}

private external interface JsUint8Array : JsAny {
    val length: Int
}

private external interface TileWorkerPool : JsAny

private external interface TileWorkerRequest : JsAny {
    val id: Int
    val style: JsString
    val zoom: Int
    val column: Int
    val row: Int
    val outputSizePx: Int
    val compare: Boolean
}

private external interface BrowserRasterResult : JsAny {
    val supported: Boolean
    val reason: JsString?
    val rgba: JsUint8Array?
}

@JsFun(
    """(workerCount, skipSkia, compareCount) => {
        const queue = [];
        const workers = [];
        const notices = new Set();
        let nextId = 1;
        let comparisonsLeft = compareCount;

        const rejectIfPoolFailed = () => {
            if (!workers.every(state => state.broken)) return;
            while (queue.length) queue.shift().reject(new Error('every tile worker failed'));
        };
        const pump = () => {
            for (const state of workers) {
                if (state.broken || state.current || queue.length === 0) continue;
                const job = queue.shift();
                state.current = job;
                state.worker.postMessage({
                    kind: 'tileRequest',
                    id: job.id,
                    style: job.style,
                    zoom: job.zoom,
                    column: job.column,
                    row: job.row,
                    outputSizePx: job.outputSizePx,
                    compare: job.compare
                });
            }
        };

        for (let index = 0; index < workerCount; index++) {
            const url = new URL('worker-bootstrap.js', globalThis.location.href);
            url.search = '';
            url.searchParams.set('role', 'tile');
            if (skipSkia) url.searchParams.set('skia', 'skip');
            const worker = new Worker(url);
            const state = { worker, current: null, broken: false };
            worker.onmessage = event => {
                const message = event.data || {};
                if (message.kind === 'tileNotice') {
                    if (!notices.has(message.message)) {
                        notices.add(message.message);
                        console.warn(message.message);
                    }
                    return;
                }
                const job = state.current;
                if (!job || message.id !== job.id) return;
                state.current = null;
                if (message.kind === 'tileResult') {
                    job.resolve(new Uint8Array(message.rgba));
                } else {
                    job.reject(new Error(message.message || 'tile worker failed'));
                }
                pump();
            };
            worker.onerror = event => {
                state.broken = true;
                if (state.current) {
                    state.current.reject(new Error(event.message || 'tile worker failed to load'));
                    state.current = null;
                }
                rejectIfPoolFailed();
                pump();
            };
            workers.push(state);
        }

        return {
            render(style, zoom, column, row, outputSizePx) {
                return new Promise((resolve, reject) => {
                    const compare = comparisonsLeft > 0;
                    if (compare) comparisonsLeft--;
                    queue.push({
                        id: nextId++, style, zoom, column, row, outputSizePx, compare, resolve, reject
                    });
                    pump();
                });
            }
        };
    }""",
)
private external fun createTileWorkerPool(
    workerCount: Int,
    skipSkia: Boolean,
    compareCount: Int,
): TileWorkerPool

@JsFun("(pool, style, zoom, column, row, outputSizePx) => pool.render(style, zoom, column, row, outputSizePx)")
private external fun requestTileFromPool(
    pool: TileWorkerPool,
    style: JsString,
    zoom: Int,
    column: Int,
    row: Int,
    outputSizePx: Int,
): Promise<JsUint8Array>

@JsFun(
    """callback => {
        if (self.__rengTileHold) self.removeEventListener('message', self.__rengTileHold);
        const accept = message => { if (message?.kind === 'tileRequest') callback(message); };
        self.onmessage = event => accept(event.data);
        for (const message of self.__rengTileMessages || []) accept(message);
        self.__rengTileMessages = [];
    }""",
)
private external fun installTileWorkerMessageHandler(callback: (TileWorkerRequest) -> Unit)

private fun postTileWorkerSuccess(id: Int, rgba: ByteArray) {
    withScopedMemoryAllocator { allocator ->
        val pointer = allocator.allocate(rgba.size)
        for (index in rgba.indices) (pointer + index).storeByte(rgba[index])
        postTileWorkerSuccessFromMemory(id, pointer.address.toInt(), rgba.size)
    }
}

@JsFun(
    """(id, address, length) => {
        const rgba = new Uint8Array(length);
        rgba.set(new Uint8Array(wasmExports.memory.buffer, address >>> 0, length));
        self.postMessage({ kind: 'tileResult', id, rgba: rgba.buffer }, [rgba.buffer]);
    }""",
)
private external fun postTileWorkerSuccessFromMemory(id: Int, address: Int, length: Int)

@JsFun("(id, message) => self.postMessage({ kind: 'tileError', id, message: String(message) })")
private external fun postTileWorkerFailure(id: Int, message: JsString)

@JsFun("message => self.postMessage({ kind: 'tileNotice', message: String(message) })")
private external fun postTileWorkerNotice(message: JsString)

@JsFun(
    """(styleLocator, zoom, column, row, outputSizePx) => {
        const plans = globalThis.__rengBrowserRasterPlans ||= new Map();
        const canvases = globalThis.__rengBrowserRasterCanvases ||= new Map();
        const ineligible = reason => ({ eligible: false, reason });
        const loadJson = async url => {
            const response = await fetch(url);
            if (!response.ok) throw new Error('HTTP ' + response.status + ' for ' + url);
            return response.json();
        };
        const defaultPaintValues = {
            'raster-opacity': 1,
            'raster-hue-rotate': 0,
            'raster-brightness-min': 0,
            'raster-brightness-max': 1,
            'raster-saturation': 0,
            'raster-contrast': 0,
            'raster-resampling': 'linear',
            'raster-fade-duration': 300
        };
        const defaultPaint = paint => paint == null || (
            typeof paint === 'object' &&
            !Array.isArray(paint) &&
            Object.entries(paint).every(([name, value]) =>
                Object.hasOwn(defaultPaintValues, name) && defaultPaintValues[name] === value
            )
        );
        const defaultLayout = layout => layout == null || (
            typeof layout === 'object' &&
            !Array.isArray(layout) &&
            Object.entries(layout).every(([name, value]) => name === 'visibility' && value === 'visible')
        );

        const inspect = async () => {
            try {
                const styleUrl = new URL(styleLocator, self.location.href).href;
                const style = await loadJson(styleUrl);
                const layers = Array.isArray(style.layers) ? style.layers : [];
                const sources = style.sources && typeof style.sources === 'object' ? style.sources : {};
                if (layers.length !== 1 || Object.keys(sources).length !== 1) {
                    return ineligible('style must contain exactly one layer and one source');
                }
                const layer = layers[0];
                const source = sources[layer.source];
                if (layer.type !== 'raster' || !source || source.type !== 'raster') {
                    return ineligible('the only layer and source must both be raster');
                }
                if (!defaultPaint(layer.paint) || !defaultLayout(layer.layout)) {
                    return ineligible('the raster layer has non-default paint or layout');
                }
                if (source.bounds != null) return ineligible('bounded raster sources are not mirrored');

                let definition = source;
                let tileBase = styleUrl;
                if (source.url != null) {
                    const tileJsonUrl = new URL(String(source.url), styleUrl).href;
                    const tileJson = await loadJson(tileJsonUrl);
                    definition = { ...tileJson, ...source };
                    tileBase = tileJsonUrl;
                }
                if (!Array.isArray(definition.tiles) || definition.tiles.length !== 1) {
                    return ineligible('exactly one tile URL template is required');
                }
                const templateText = String(definition.tiles[0]);
                const tokens = templateText.match(/\{[^}]+\}/g) || [];
                if (tokens.some(token => !['{z}', '{x}', '{y}', '{-y}', '{quadkey}'].includes(token))) {
                    return ineligible('tile URL template uses an unsupported token');
                }
                // URL percent-encodes braces. Protect the already-validated placeholders while
                // resolving a relative template, then put them back for substitution per request.
                const template = new URL(
                    templateText.replaceAll('{', '__reng_open__').replaceAll('}', '__reng_close__'),
                    tileBase
                ).href.replaceAll('__reng_open__', '{').replaceAll('__reng_close__', '}');
                const scheme = definition.scheme == null ? 'xyz' : String(definition.scheme);
                if (scheme !== 'xyz' && scheme !== 'tms') return ineligible('unsupported tile scheme ' + scheme);
                const minimumZoom = Number.isFinite(Number(definition.minzoom)) ? Number(definition.minzoom) : 0;
                const maximumZoom = Number.isFinite(Number(definition.maxzoom)) ? Number(definition.maxzoom) : 22;
                const tileSize = Number.isFinite(Number(definition.tileSize)) ? Number(definition.tileSize) : 512;
                if (minimumZoom < 0 || maximumZoom < minimumZoom || tileSize <= 0) {
                    return ineligible('invalid raster source zoom or tile size');
                }
                return {
                    eligible: true,
                    template,
                    scheme,
                    minimumZoom,
                    maximumZoom,
                    tileSize,
                    layerMinimumZoom: Number.isFinite(Number(layer.minzoom)) ? Number(layer.minzoom) : 0,
                    layerMaximumZoom: Number.isFinite(Number(layer.maxzoom)) ? Number(layer.maxzoom) : 24
                };
            } catch (error) {
                return ineligible('style inspection failed: ' + (error?.message || error));
            }
        };

        let planPromise = plans.get(styleLocator);
        if (!planPromise) {
            planPromise = inspect();
            plans.set(styleLocator, planPromise);
        }
        return planPromise.then(async plan => {
            if (!plan.eligible) return { supported: false, reason: plan.reason, rgba: null };
            const transparent = () => ({
                supported: true,
                reason: null,
                rgba: new Uint8Array(outputSizePx * outputSizePx * 4)
            });
            if (zoom < plan.layerMinimumZoom || zoom >= plan.layerMaximumZoom || zoom < plan.minimumZoom) {
                return transparent();
            }

            const outputDimension = 2 ** zoom;
            if (row < 0 || row >= outputDimension) return transparent();
            const canonicalColumn = ((column % outputDimension) + outputDimension) % outputDimension;
            const sourceZoom = Math.min(zoom, Math.floor(plan.maximumZoom));
            const childScale = 2 ** (zoom - sourceZoom);
            const sourceColumn = Math.floor(canonicalColumn / childScale);
            const sourceRow = Math.floor(row / childScale);
            const childColumn = canonicalColumn - sourceColumn * childScale;
            const childRow = row - sourceRow * childScale;
            const sourceDimension = 2 ** sourceZoom;
            const urlRow = plan.scheme === 'tms' ? sourceDimension - sourceRow - 1 : sourceRow;
            let quadkey = '';
            for (let bit = sourceZoom; bit > 0; bit--) {
                const mask = 2 ** (bit - 1);
                let digit = 0;
                if ((sourceColumn & mask) !== 0) digit++;
                if ((sourceRow & mask) !== 0) digit += 2;
                quadkey += digit;
            }
            const tileUrl = plan.template
                .replaceAll('{z}', String(sourceZoom))
                .replaceAll('{x}', String(sourceColumn))
                .replaceAll('{y}', String(urlRow))
                .replaceAll('{-y}', String(sourceDimension - sourceRow - 1))
                .replaceAll('{quadkey}', quadkey);
            const response = await fetch(tileUrl);
            if (!response.ok) throw new Error('raster tile HTTP ' + response.status);
            const bitmap = await createImageBitmap(await response.blob(), {
                premultiplyAlpha: 'none',
                colorSpaceConversion: 'default'
            });
            try {
                let entry = canvases.get(outputSizePx);
                if (!entry) {
                    const canvas = new OffscreenCanvas(outputSizePx, outputSizePx);
                    const context = canvas.getContext('2d', { alpha: true, willReadFrequently: true });
                    if (!context) throw new Error('OffscreenCanvas 2D context is unavailable');
                    entry = { canvas, context };
                    canvases.set(outputSizePx, entry);
                }
                const context = entry.context;
                context.clearRect(0, 0, outputSizePx, outputSizePx);
                context.imageSmoothingEnabled = true;
                context.imageSmoothingQuality = 'low';
                const sourceLeft = bitmap.width * childColumn / childScale;
                const sourceTop = bitmap.height * childRow / childScale;
                const sourceWidth = bitmap.width / childScale;
                const sourceHeight = bitmap.height / childScale;
                context.drawImage(
                    bitmap,
                    sourceLeft, sourceTop, sourceWidth, sourceHeight,
                    0, 0, outputSizePx, outputSizePx
                );
                const data = context.getImageData(0, 0, outputSizePx, outputSizePx).data;
                for (let index = 0; index < data.length; index += 4) {
                    const alpha = data[index + 3];
                    if (alpha !== 255) {
                        data[index] = Math.round(data[index] * alpha / 255);
                        data[index + 1] = Math.round(data[index + 1] * alpha / 255);
                        data[index + 2] = Math.round(data[index + 2] * alpha / 255);
                    }
                }
                return { supported: true, reason: null, rgba: new Uint8Array(data.buffer) };
            } finally {
                bitmap.close();
            }
        });
    }""",
)
private external fun renderBrowserRaster(
    styleLocator: JsString,
    zoom: Int,
    column: Int,
    row: Int,
    outputSizePx: Int,
): Promise<BrowserRasterResult>

@JsFun(
    """() => {
        const canvas = document.getElementById('map');
        const query = window.location.search;
        const params = new URLSearchParams(query);
        const workerOn = params.get('worker') === 'on';
        const dpr = window.devicePixelRatio || 1;
        const scale = Math.max(0.01, Math.min(dpr, 2048 / window.innerWidth, 2048 / window.innerHeight));
        const width = Math.max(1, Math.floor(window.innerWidth * scale));
        const height = Math.max(1, Math.floor(window.innerHeight * scale));
        canvas.width = width;
        canvas.height = height;

        const meter = { intervals: [], longTasks: [] };
        globalThis.__rengMainThread = meter;
        if (workerOn || Number(params.get('trace') || 0) > 0) {
            let previousFrame = null;
            let lastReadout = 0;
            const readout = document.getElementById('smoothness-readout');
            const tick = now => {
                if (previousFrame != null) {
                    const interval = Math.round((now - previousFrame) * 10) / 10;
                    meter.intervals.push(interval);
                    if (readout && now - lastReadout >= 250) {
                        readout.textContent = 'main rAF ' + interval.toFixed(1) + ' ms';
                        lastReadout = now;
                    }
                }
                previousFrame = now;
                window.requestAnimationFrame(tick);
            };
            window.requestAnimationFrame(tick);
            try {
                new PerformanceObserver(list => {
                    for (const entry of list.getEntries()) {
                        meter.longTasks.push([Math.round(entry.startTime), Math.round(entry.duration)]);
                    }
                }).observe({ type: 'longtask', buffered: true });
            } catch (ignored) {}
        }

        const applyMessage = message => {
            if (message.kind === 'status') {
                const status = document.getElementById('status');
                status.textContent = message.text;
                status.classList.toggle('error', Boolean(message.isError));
                return;
            }
            if (message.kind === 'result') {
                let text = message.text;
                try {
                    const report = JSON.parse(text);
                    if (Array.isArray(report.frames)) {
                        report.mainThread = {
                            intervals: meter.intervals.slice(),
                            longTasks: meter.longTasks.slice()
                        };
                        text = JSON.stringify(report);
                    }
                } catch (ignored) {}
                let pre = document.getElementById('bench');
                if (!pre) {
                    pre = document.createElement('pre');
                    pre.id = 'bench';
                    pre.style.display = 'none';
                    document.body.appendChild(pre);
                }
                pre.textContent = text;
                document.title = 'BENCH DONE';
            }
        };
        globalThis.__rengApplyDemoMessage = applyMessage;

        if (workerOn) {
            if (typeof canvas.transferControlToOffscreen !== 'function') {
                throw new Error('OffscreenCanvas transfer is unavailable');
            }
            document.body.classList.add('worker-mode');
            const offscreen = canvas.transferControlToOffscreen();
            const worker = new Worker(new URL('worker-bootstrap.js', window.location.href));
            worker.onmessage = event => applyMessage(event.data);
            worker.onerror = event => applyMessage({
                kind: 'status',
                text: 'Worker: ' + (event.message || 'failed to load'),
                isError: true
            });
            worker.postMessage({ canvas: offscreen, width, height, query }, [offscreen]);
            return {
                context: null,
                width,
                height,
                renderer: 'worker starting',
                basemapOff: params.get('basemap') === 'off',
                workerStarted: true
            };
        }

        const context = canvas.getContext('webgl2', {
            antialias: false,
            depth: true,
            stencil: false,
            alpha: false,
            premultipliedAlpha: false,
            preserveDrawingBuffer: false
        });
        const renderer = context ? context.getParameter(context.RENDERER) : 'WebGL2 unavailable';
        return {
            context,
            width,
            height,
            renderer: String(renderer),
            basemapOff: params.get('basemap') === 'off',
            workerStarted: false
        };
    }""",
)
private external fun prepareBrowser(): BrowserSetup

@JsFun(
    """setup => {
        globalThis.__rengQuery = String(setup.query || '');
        const canvas = setup.canvas;
        canvas.width = setup.width;
        canvas.height = setup.height;
        const context = canvas.getContext('webgl2', {
            antialias: false,
            depth: true,
            stencil: false,
            alpha: false,
            premultipliedAlpha: false,
            preserveDrawingBuffer: false
        });
        const renderer = context ? context.getParameter(context.RENDERER) : 'WebGL2 unavailable';
        return {
            context,
            width: setup.width,
            height: setup.height,
            renderer: String(renderer),
            basemapOff: new URLSearchParams(globalThis.__rengQuery).get('basemap') === 'off',
            workerStarted: false
        };
    }""",
)
private external fun prepareWorker(setup: WorkerSetup): BrowserSetup

@JsFun("() => typeof window === 'undefined'")
private external fun isWorkerScope(): Boolean

@JsFun("() => typeof window === 'undefined' && new URL(self.location.href).searchParams.get('role') === 'tile'")
private external fun isTileWorkerScope(): Boolean

@JsFun("callback => self.__rengSetup.then(callback)")
private external fun waitForWorkerSetup(callback: (WorkerSetup) -> Unit)

@JsFun(
    """(ifNoneMatch, ifModifiedSince, accept) => {
        const headers = new Headers();
        if (ifNoneMatch != null) headers.set('If-None-Match', ifNoneMatch);
        if (ifModifiedSince != null) headers.set('If-Modified-Since', ifModifiedSince);
        if (accept != null) headers.set('Accept', accept);
        return headers;
    }""",
)
private external fun requestHeaders(
    ifNoneMatch: JsString?,
    ifModifiedSince: JsString?,
    accept: JsString?,
): JsAny

@JsFun(
    """(url, method, headers) => fetch(url, { method, headers }).then(async (response) => {
        const cacheControl = response.headers.get('cache-control');
        const maxAge = cacheControl && cacheControl.match(/(?:^|,)\s*max-age=(\d+)/i);
        const expires = response.headers.get('expires');
        let freshUntilEpochMillis = -1;
        if (maxAge) {
            freshUntilEpochMillis = Date.now() + Number(maxAge[1]) * 1000;
        } else if (expires) {
            const parsed = Date.parse(expires);
            if (Number.isFinite(parsed)) freshUntilEpochMillis = parsed;
        }
        return {
            statusCode: response.status,
            body: new Uint8Array(await response.arrayBuffer()),
            contentType: response.headers.get('content-type'),
            etag: response.headers.get('etag'),
            lastModified: response.headers.get('last-modified'),
            freshUntilEpochMillis
        };
    })""",
)
private external fun fetchResource(
    url: JsString,
    method: JsString,
    headers: JsAny,
): Promise<FetchPayload>

@JsFun("callback => globalThis.requestAnimationFrame(callback)")
private external fun requestFrame(callback: (Double) -> Unit): Int

@JsFun("() => { const query = typeof window === 'undefined' ? globalThis.__rengQuery : window.location.search; const href = typeof window === 'undefined' ? self.location.href : window.location.href; return new URLSearchParams(query).get('style') || new URL('style.json', href).href; }")
private external fun basemapStyleUrl(): JsString

@JsFun("() => Number(new URLSearchParams(typeof window === 'undefined' ? globalThis.__rengQuery : window.location.search).get('bench') || 0)")
private external fun benchFrameCount(): Int

// Labels are off unless `?labels=on`: label layout was ~1.6 s a frame in the benchmark.
@JsFun("() => new URLSearchParams(typeof window === 'undefined' ? globalThis.__rengQuery : window.location.search).get('labels') !== 'on'")
private external fun labelsOff(): Boolean

@JsFun("() => Number(new URLSearchParams(typeof window === 'undefined' ? globalThis.__rengQuery : window.location.search).get('trace') || 0)")
private external fun traceSeconds(): Int

@JsFun("() => new URLSearchParams(typeof window === 'undefined' ? globalThis.__rengQuery : window.location.search).get('readahead') === 'on'")
private external fun readAheadOn(): Boolean

@JsFun("() => Math.floor(Number(new URLSearchParams(typeof window === 'undefined' ? globalThis.__rengQuery : window.location.search).get('tileworkers') || 0))")
private external fun tileWorkerCount(): Int

@JsFun("() => new URLSearchParams(typeof window === 'undefined' ? globalThis.__rengQuery : window.location.search).get('skia') === 'skip'")
private external fun skipSkia(): Boolean

@JsFun("() => Math.floor(Number(new URLSearchParams(typeof window === 'undefined' ? globalThis.__rengQuery : window.location.search).get('compare') || 0))")
private external fun compareTileCount(): Int

@JsFun("() => new URL(self.location.href).searchParams.get('skia') === 'skip'")
private external fun tileWorkerSkipsSkia(): Boolean

@JsFun("() => { globalThis.__rengRenderLongTasks = []; try { new PerformanceObserver((list) => { for (const e of list.getEntries()) globalThis.__rengRenderLongTasks.push([Math.round(e.startTime), Math.round(e.duration)]); }).observe({ type: 'longtask', buffered: true }); } catch (e) {} }")
private external fun startLongTaskObserver()

@JsFun("() => JSON.stringify(globalThis.__rengRenderLongTasks || [])")
private external fun longTasksJson(): String

@JsFun("message => console.log(message)")
private external fun consoleLog(message: JsString)

@JsFun("text => { const message = { kind: 'result', text }; if (typeof window === 'undefined') self.postMessage(message); else globalThis.__rengApplyDemoMessage(message); }")
private external fun setBenchResult(text: String)

@JsFun("() => performance.now()")
private external fun performanceNow(): Double

@JsFun(
    """(text, isError) => {
        const message = { kind: 'status', text, isError };
        if (typeof window === 'undefined') self.postMessage(message);
        else globalThis.__rengApplyDemoMessage(message);
    }""",
)
private external fun setStatus(text: String, isError: Boolean)

@JsFun("(source, address, length) => new Uint8Array(wasmExports.memory.buffer, address >>> 0, length).set(source)")
private external fun copyJsBytesToLinearMemory(source: JsUint8Array, address: Int, length: Int)

@JsFun("message => console.error(message)")
private external fun consoleError(message: JsString)
