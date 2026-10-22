package com.rohittp.reng.internal.driver

import com.rohittp.reng.ResourceLimits
import com.rohittp.reng.internal.glb.GlbScan
import com.rohittp.reng.internal.glb.GltfFeatureResult
import com.rohittp.reng.internal.glb.GltfParseResult
import com.rohittp.reng.internal.glb.MAXIMUM_GLB_NODE_DEPTH
import com.rohittp.reng.internal.glb.parseGltf
import com.rohittp.reng.internal.glb.scanGlb
import com.rohittp.reng.internal.glb.validateGltfFeatures
import com.rohittp.reng.internal.image.DecodedImage
import com.rohittp.reng.internal.image.Etc2ContainerResult
import com.rohittp.reng.internal.image.PngDecodeResult
import com.rohittp.reng.internal.image.decodePng
import com.rohittp.reng.internal.image.isEtc2Container
import com.rohittp.reng.internal.image.parseEtc2Container
import com.rohittp.reng.internal.resource.ResolvedResourceContent
import com.rohittp.reng.internal.resource.ResourceClassGate
import com.rohittp.reng.internal.resource.SuppliedValidationOutcome
import com.rohittp.reng.internal.thread.PlatformLock

/**
 * Runs exactly one [ResourceClassGate] over already-resolved content and reports whether it is
 * [SuppliedValidationOutcome.Valid] or [SuppliedValidationOutcome.Failed]. The gate's identity alone
 * decides which check runs; [ResourceOperationStateMachine][com.rohittp.reng.internal.resource.ResourceOperationStateMachine]
 * decides the [com.rohittp.reng.RenGErrorCode] a `Failed` outcome maps to (decode gates report
 * `RESOURCE_DECODE_FAILED`, feature gates report `UNSUPPORTED_RESOURCE_FEATURE`, and STORE-provenance
 * content collapses either into `STORE_INTEGRITY_FAILED`) — this interface never carries a reason code,
 * only the two outcomes that decision needs.
 */
internal fun interface ClassGateRunner {
    suspend fun run(gate: ResourceClassGate, content: ResolvedResourceContent): SuppliedValidationOutcome

    /**
     * Whether [gate] is already known to pass for the bytes with [contentDigest], without running it:
     * what `PreparationDriver.leaseSteadyStateResident` asks before letting a resident reference skip the
     * operation. False unless the runner remembers passes (ADR 0060), so a runner that does not is never
     * bypassed.
     */
    fun hasPassed(gate: ResourceClassGate, contentDigest: String): Boolean = false
}

/**
 * RenG's own class gates, and only those. `DECODE_PNG` genuinely decodes a backdrop or model-texture PNG
 * through [decodePng], gated by [limits]'s decoded-byte ceiling; `DECODE_STICKER_IMAGE` does the same
 * for a sticker's PNG and validates a sticker's ETC2 container instead (ADR 0084);
 * `PARSE_GLB`/`VALIDATE_GLB_FEATURES` genuinely scan and parse a GLB container's JSON chunk into a
 * [com.rohittp.reng.internal.glb.GltfDocument] via the canonical
 * [scanGlb]/[parseGltf]/[validateGltfFeatures] (ADR 0021's complete supported subset).
 *
 * The gate's identity alone selects the check, with no second discrimination on the content's resource
 * class, because [com.rohittp.reng.internal.resource.ordinaryResourceClassGates] is what pairs the two:
 * it names `DECODE_PNG` for exactly `BACKDROP_IMAGE` and `MODEL_TEXTURE`, `DECODE_STICKER_IMAGE` for
 * `STICKER_IMAGE`, and
 * [com.rohittp.reng.internal.resource.AwaitingClassGate]'s own `init` refuses any cursor whose gate is
 * not that class's gate at that index. Nothing engine-keyed reaches here: the Rentile engine acquires and
 * validates its own seven classes through RenG's firewall, so RenG's driver never routes one.
 *
 * **GLB wiring:** `PARSE_GLB` and `VALIDATE_GLB_FEATURES` each independently re-derive the parsed
 * [com.rohittp.reng.internal.glb.GltfDocument] from [ResolvedResourceContent.stored]'s raw bytes via
 * [parseGlbDocument] — the same redundancy [runDecodePng] already accepts for PNG, since this interface
 * answers one gate at a time, and re-derives it for bytes it has not seen before. A [GlbScan]
 * container-framing rejection
 * or a [GltfParseResult.Malformed] structural rejection both fail `PARSE_GLB`; `VALIDATE_GLB_FEATURES`
 * additionally fails on either of those (a document `PARSE_GLB` would already have refused can never be
 * "supported"), or on a genuine [GltfFeatureResult.Unsupported] feature outside ADR 0021's subset.
 */
internal class RenGClassGateRunner(private val limits: ResourceLimits) : ClassGateRunner {

    /**
     * Which `gate:contentDigest` pairs have already passed, so unchanged bytes are validated once
     * (ADR 0060).
     *
     * Every gate re-derives its answer from the whole resource and the driver asks on every
     * preparation, so an unchanged resident PNG was copied out of the store and decoded again purely
     * to re-answer "is this a valid PNG". Measured on a OnePlus 12R by the fork this is ported from:
     * a frame carrying a 2048x1365 area fill, 334 ms to 119 ms.
     *
     * Sound rather than merely likely: validity is a pure function of the bytes and `contentDigest`
     * is a SHA-256 of exactly those bytes. Keyed by gate *and* digest because the three ask different
     * questions of one resource -- a GLB that parses may still use a feature outside ADR 0021's subset.
     */
    private val passedGates: MutableSet<String> = LinkedHashSet()

    /**
     * Guards [passedGates]. Gates run in parallel coroutines on the preparing caller's dispatcher, and the
     * steady-state shortcut reads the set before the operation starts; a caller preparing on a
     * multi-threaded dispatcher would otherwise mutate one LinkedHashSet from several threads.
     */
    private val passedGatesLock = PlatformLock()

    override fun hasPassed(gate: ResourceClassGate, contentDigest: String): Boolean =
        passedGatesLock.withLock { "$gate:$contentDigest" in passedGates }

    override suspend fun run(
        gate: ResourceClassGate,
        content: ResolvedResourceContent,
    ): SuppliedValidationOutcome {
        val identity = "$gate:${content.stored.contentDigest}"
        if (passedGatesLock.withLock { identity in passedGates }) return SuppliedValidationOutcome.Valid
        val outcome = when (gate) {
            ResourceClassGate.DECODE_PNG -> runDecodePng(content)
            ResourceClassGate.DECODE_STICKER_IMAGE -> runDecodeStickerImage(content)
            ResourceClassGate.PARSE_GLB -> runParseGlb(content)
            ResourceClassGate.VALIDATE_GLB_FEATURES -> runValidateGlbFeatures(content)
        }
        // Only a pass (ADR 0060). [limits] is fixed for this runner's life -- it is the renderer's
        // configuration, which cannot change -- so a remembered pass never skips a stricter check. A
        // failure, though, can be about this run rather than these bytes: a native inflater that could
        // not be initialised reads as a malformed image, and remembering that would turn a transient
        // refusal into a permanent verdict the consumer cannot clear.
        if (outcome == SuppliedValidationOutcome.Valid) remember(identity)
        return outcome
    }

    /**
     * Records [identity], forgetting the oldest past [MAXIMUM_REMEMBERED_GATES].
     *
     * Bounded because a long editing session picks many images and nothing here would otherwise ever
     * forget one. Forgetting costs exactly one re-validation, which is what every release before this
     * did for every resource on every frame — the worst case of this cache is the whole behaviour of
     * its absence, which is what makes the eviction policy uninteresting.
     */
    private fun remember(identity: String) = passedGatesLock.withLock {
        passedGates += identity
        val oldest = passedGates.iterator()
        while (passedGates.size > MAXIMUM_REMEMBERED_GATES) {
            oldest.next()
            oldest.remove()
        }
    }

    private fun runDecodePng(content: ResolvedResourceContent): SuppliedValidationOutcome =
        when (decodePng(
                content.stored.bytes,
                limits.maximumDecodedImageBytes,
                limits.maximumImageDecodeWorkingBytes,
            )
        ) {
            is PngDecodeResult.Success -> SuppliedValidationOutcome.Valid
            is PngDecodeResult.Malformed,
            is PngDecodeResult.Unsupported,
            PngDecodeResult.TooLarge,
            -> SuppliedValidationOutcome.Failed
        }

    /**
     * A PNG decodes as [runDecodePng] does; an ETC2 container (ADR 0084) is validated in full, framing
     * and decoded-image ceiling, which is all there is to validate -- every block decodes.
     */
    private fun runDecodeStickerImage(content: ResolvedResourceContent): SuppliedValidationOutcome {
        if (!isEtc2Container(content.stored.byteSnapshot)) return runDecodePng(content)
        return when (parseEtc2Container(content.stored.byteSnapshot, limits.maximumDecodedImageBytes)) {
            is Etc2ContainerResult.Admitted -> SuppliedValidationOutcome.Valid
            Etc2ContainerResult.Malformed,
            Etc2ContainerResult.Unsupported,
            Etc2ContainerResult.TooLarge,
            -> SuppliedValidationOutcome.Failed
        }
    }

    private fun runParseGlb(content: ResolvedResourceContent): SuppliedValidationOutcome =
        when (parseGlbDocument(content.stored.bytes)) {
            is GltfParseResult.Parsed -> SuppliedValidationOutcome.Valid
            is GltfParseResult.Malformed, null -> SuppliedValidationOutcome.Failed
        }

    private fun runValidateGlbFeatures(content: ResolvedResourceContent): SuppliedValidationOutcome {
        val document = (parseGlbDocument(content.stored.bytes) as? GltfParseResult.Parsed)?.document
            ?: return SuppliedValidationOutcome.Failed
        return when (validateGltfFeatures(document)) {
            GltfFeatureResult.Supported -> SuppliedValidationOutcome.Valid
            is GltfFeatureResult.Unsupported -> SuppliedValidationOutcome.Failed
        }
    }

    /**
     * Runs the container scan and document parse `PARSE_GLB` performs — [scanGlb] bounded by
     * [ResourceLimits.maximumModelJsonChunkBytes], then [parseGltf] over the admitted JSON chunk and BIN
     * chunk length — sharing those exact steps with [runValidateGlbFeatures], which needs the same parsed
     * [com.rohittp.reng.internal.glb.GltfDocument] before it can inspect it. Returns `null` for a
     * container-framing fault ([scanGlb] rejected); a [GltfParseResult.Malformed] result still flows
     * through so callers see `PARSE_GLB`'s own malformation vocabulary rather than a second `null`.
     */
    private fun parseGlbDocument(bytes: ByteArray): GltfParseResult? {
        val admitted = scanGlb(bytes, limits.maximumModelJsonChunkBytes) as? GlbScan.Admitted ?: return null
        return parseGltf(admitted.json, admitted.binChunkLength, MAXIMUM_GLB_NODE_DEPTH)
    }
}

private const val OPAQUE_ALPHA: Byte = -1 // 0xFF unsigned

/** Comfortably more distinct resources than one editing session presents, at ~70 bytes an entry. */
private const val MAXIMUM_REMEMBERED_GATES: Int = 512
