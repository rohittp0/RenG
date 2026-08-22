@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.rohittp.reng.smoke.harness

import com.rohittp.reng.ResourceClass
import com.rohittp.reng.Transport
import com.rohittp.reng.TransportRequest
import com.rohittp.reng.TransportResponse
import com.rohittp.reng.TransportResponseMetadata
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicReference
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import platform.Foundation.NSData
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLSession
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.setValue
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.DISPATCH_TIME_FOREVER

/**
 * Replaces the value of any `key=` query parameter with `REDACTED`.
 *
 * The owner's styles carry api keys in their style, glyph, sprite and tile urls. The harness prints
 * failures so a broken run is diagnosable, and a printed url is a leaked key unless this runs first.
 */
internal fun redactKeys(url: String): String =
    Regex("([?&]key=)[^&]*").replace(url) { it.groupValues[1] + "REDACTED" }

/**
 * The consumer's transport: `NSURLSession` for everything on the network, plus a tiny embedded table
 * for the harness's own sticker images.
 *
 * `NSURLSession` needs no cinterop -- Foundation ships with Kotlin/Native for `macosArm64`. The
 * synchronous shape (semaphore around an async task) is a harness convenience; RenG calls this from
 * a coroutine worker, never from the thread holding the Render Context.
 *
 * Nothing here retries, repairs, or falls back. A non-200 is returned as it stands, because RenG's
 * failure classification is exactly what a harness should be able to see.
 */
internal class NsUrlTransport(
    private val embedded: Map<String, Pair<ByteArray, String>>,
    private val verbose: Boolean = false,
) : Transport {
    private val requests = AtomicInt(0)
    private val failures = AtomicReference<List<String>>(emptyList())
    private val seen = AtomicReference<Set<String>>(emptySet())

    override suspend fun execute(request: TransportRequest): TransportResponse {
        requests.addAndGet(1)
        val url = request.locator.value
        embedded[url]?.let { (bytes, contentType) ->
            return TransportResponse(200, bytes, TransportResponseMetadata(contentType = contentType))
        }
        val response = fetch(url, request.resourceClass, request.metadata.accept)
        if (verbose) {
            val line = "${request.resourceClass} -> ${response.statusCode} " +
                "${response.body.size}B ${redactKeys(url)}"
            while (true) {
                val current = seen.value
                if (line in current) break
                if (seen.compareAndSet(current, current + line)) {
                    println("  fetch $line")
                    break
                }
            }
        }
        return response
    }

    fun summary(): String = "transport: ${requests.value} requests"

    fun failureSummary(): List<String> = failures.value

    private fun fetch(url: String, resourceClass: ResourceClass, accept: String?): TransportResponse {
        val target = NSURL.URLWithString(url)
            ?: return recordFailure(url, resourceClass, 0, "not a url")
        val nsRequest = NSMutableURLRequest.requestWithURL(target)
        nsRequest.setCachePolicy(NSURLRequestReloadIgnoringLocalCacheData)
        nsRequest.setTimeoutInterval(30.0)
        if (accept != null) nsRequest.setValue(accept, forHTTPHeaderField = "Accept")

        val outcome = AtomicReference<Triple<Int, ByteArray, String?>?>(null)
        val problem = AtomicReference<String?>(null)
        val semaphore = dispatch_semaphore_create(0)
        NSURLSession.sharedSession.dataTaskWithRequest(nsRequest) { data, response, error ->
            when {
                error != null -> problem.value = "urlsession error " + error.code
                response !is NSHTTPURLResponse -> problem.value = "no http response"
                else -> outcome.value = Triple(
                    response.statusCode.toInt(),
                    data?.toByteArray() ?: ByteArray(0),
                    response.valueForHTTPHeaderField("Content-Type"),
                )
            }
            dispatch_semaphore_signal(semaphore)
        }.resume()
        dispatch_semaphore_wait(semaphore, DISPATCH_TIME_FOREVER)

        problem.value?.let { return recordFailure(url, resourceClass, 0, it) }
        val (status, body, contentType) = outcome.value
            ?: return recordFailure(url, resourceClass, 0, "no outcome")
        if (status != 200) recordFailure(url, resourceClass, status, "http $status")
        return TransportResponse(status, body, TransportResponseMetadata(contentType = contentType))
    }

    private fun recordFailure(
        url: String,
        resourceClass: ResourceClass,
        status: Int,
        reason: String,
    ): TransportResponse {
        val line = "$resourceClass $reason <" + redactKeys(url) + ">"
        while (true) {
            val current = failures.value
            if (current.size >= 20 || line in current) break
            if (failures.compareAndSet(current, current + line)) break
        }
        return TransportResponse(if (status == 0) 599 else status, ByteArray(0))
    }
}

internal fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    return bytes!!.reinterpret<kotlinx.cinterop.ByteVar>().readBytes(size)
}
