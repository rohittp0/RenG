@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

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
import com.rohittp.reng.makeWebGl2ContextCurrent
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

// Served from the demo's own origin: the catalogue that hosts the app's styles sends no CORS header.
// `?style=<absolute url>` overrides it.
private val BASEMAP_STYLE_URL: String get() = basemapStyleUrl().toString()
private const val LOOP_MILLIS = 24_000.0

fun main() {
    try {
        val browser = prepareBrowser()
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
            requestFrame(::onAnimationFrame)
        }
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
                val plan = framePlanAt(frameIndex = frameIndex++, phase = sample.toDouble() / frames)
                val prepareStart = performanceNow()
                val prepared = renderer.prepare(plan = plan)
                prepare[sample] = performanceNow() - prepareStart
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
        requestFrame(::onAnimationFrame)
        if (preparing || failed) return

        preparing = true
        val frameIndex = nextFrameIndex++
        scope.launch {
            try {
                val plan = framePlan(frameIndex = frameIndex, timestampMillis = timestampMillis)
                val prepareStart = performanceNow()
                val prepared = renderer.prepare(plan = plan)
                prepareMillis += performanceNow() - prepareStart
                try {
                    val drawStart = performanceNow()
                    renderer.draw(preparedFrame = prepared, renderTarget = renderTarget)
                    drawMillis += performanceNow() - drawStart
                } finally {
                    prepared.close()
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
        val body = ByteArray(payload.body.length)
        for (index in body.indices) {
            body[index] = payload.body[index].toByte()
        }

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
    operator fun get(index: Int): Int
}

@JsFun(
    """() => {
        const canvas = document.getElementById('map');
        const dpr = window.devicePixelRatio || 1;
        const scale = Math.max(0.01, Math.min(dpr, 2048 / window.innerWidth, 2048 / window.innerHeight));
        const width = Math.max(1, Math.floor(window.innerWidth * scale));
        const height = Math.max(1, Math.floor(window.innerHeight * scale));
        canvas.width = width;
        canvas.height = height;
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
            basemapOff: new URLSearchParams(window.location.search).get('basemap') === 'off'
        };
    }""",
)
private external fun prepareBrowser(): BrowserSetup

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

@JsFun("callback => window.requestAnimationFrame(callback)")
private external fun requestFrame(callback: (Double) -> Unit): Int

@JsFun("() => new URLSearchParams(window.location.search).get('style') || new URL('style.json', window.location.href).href")
private external fun basemapStyleUrl(): JsString

@JsFun("() => Number(new URLSearchParams(window.location.search).get('bench') || 0)")
private external fun benchFrameCount(): Int

@JsFun("() => new URLSearchParams(window.location.search).get('labels') === 'off'")
private external fun labelsOff(): Boolean

@JsFun("message => console.log(message)")
private external fun consoleLog(message: JsString)

@JsFun("text => { let pre = document.getElementById('bench'); if (!pre) { pre = document.createElement('pre'); pre.id = 'bench'; pre.style.display = 'none'; document.body.appendChild(pre); } pre.textContent = text; document.title = 'BENCH DONE'; }")
private external fun setBenchResult(text: String)

@JsFun("() => performance.now()")
private external fun performanceNow(): Double

@JsFun(
    """(text, isError) => {
        const status = document.getElementById('status');
        status.textContent = text;
        status.classList.toggle('error', isError);
    }""",
)
private external fun setStatus(text: String, isError: Boolean)

@JsFun("message => console.error(message)")
private external fun consoleError(message: JsString)
