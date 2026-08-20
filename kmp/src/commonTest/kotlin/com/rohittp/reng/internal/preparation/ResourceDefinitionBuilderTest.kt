package com.rohittp.reng.internal.preparation

import com.rohittp.reng.ResourceAccessMode
import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceLimits
import com.rohittp.reng.ResourceLocator
import com.rohittp.reng.ShaderPair
import com.rohittp.reng.internal.firewall.ProductionRentilePrivateKeyResolver
import com.rohittp.reng.internal.identity.PureKotlinSha256
import com.rohittp.reng.internal.identity.ResourceKeyDeriver
import com.rohittp.reng.internal.maximumBytesFor
import com.rohittp.reng.internal.planning.StaticResourceReference
import com.rohittp.reng.internal.resource.ResourceCommitBinding
import com.rohittp.reng.internal.resource.ResourceOccurrence
import com.rohittp.reng.internal.resource.ResourceOccurrenceId
import com.rohittp.reng.internal.resource.ResourceOwnerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The occurrence shape both preparation paths derive from, and above all its owner assignment — which
 * is the entire content of ADR 0016's style-owner barrier rather than a bookkeeping detail.
 */
class ResourceDefinitionBuilderTest {

    /**
     * The property the barrier actually depends on, stated as a property rather than as the
     * implementation: **the style's owner must also own the frame's other work.**
     *
     * `ResourceOperationStateMachine.styleOwnerBarrierComplete` asks whether every owner referencing the
     * style has any non-style occurrence still without installed visibility. Assign the style an owner
     * of its own and the answer is trivially "no work outstanding", the barrier is vacuously true, and
     * ADR 0016's "write only after successful compilation and completion of all other work for the
     * referencing preparation items" is silently not enforced by anything at all.
     */
    @Test
    fun givesTheStyleAnOwnerThatAlsoOwnsTheFramesOtherWork() {
        val definition = buildResourceOperationDefinition(
            traversalsByItem = listOf(
                listOf(
                    external(ResourceClass.BASEMAP_STYLE, "https://styles.example/basic.json"),
                    external(ResourceClass.STICKER_IMAGE, "https://images.example/a.png"),
                    external(ResourceClass.MODEL_TEXTURE, "https://images.example/b.png"),
                ),
            ),
            accessMode = ResourceAccessMode.NORMAL,
            maximumConcurrentRoutes = 4,
        )

        val style = definition.staticOccurrences.single {
            it.registration.route.resourceClass == ResourceClass.BASEMAP_STYLE
        }
        val ownerNonStyleWork = definition.staticOccurrences.filter {
            it.ownerId == style.ownerId && it.commitBinding !is ResourceCommitBinding.BasemapStyle
        }

        assertEquals(
            2,
            ownerNonStyleWork.size,
            "the style's owner must own the rest of its item's work, or its barrier waits for nothing",
        )
        assertEquals(
            listOf(ResourceOwnerId(1L)),
            definition.staticOccurrences.map(ResourceOccurrence::ownerId).distinct(),
            "one preparation item is one owner",
        )
    }

    @Test
    fun givesEachPreparationItemItsOwnOwnerAndOneInvocationWideOccurrenceIdSpace() {
        val definition = buildResourceOperationDefinition(
            traversalsByItem = listOf(
                listOf(
                    external(ResourceClass.BASEMAP_STYLE, "https://styles.example/basic.json"),
                    external(ResourceClass.STICKER_IMAGE, "https://images.example/a.png"),
                ),
                listOf(
                    external(ResourceClass.BASEMAP_STYLE, "https://styles.example/basic.json"),
                    external(ResourceClass.STICKER_IMAGE, "https://images.example/b.png"),
                ),
            ),
            accessMode = ResourceAccessMode.NORMAL,
            maximumConcurrentRoutes = 4,
        )

        assertEquals(
            listOf(ResourceOwnerId(1L), ResourceOwnerId(1L), ResourceOwnerId(2L), ResourceOwnerId(2L)),
            definition.staticOccurrences.map(ResourceOccurrence::ownerId),
        )
        assertEquals(
            listOf(1L, 2L, 3L, 4L),
            definition.staticOccurrences.map { it.id.value },
            "occurrence identity is invocation-wide and 1-based, not per item",
        )
        assertEquals(
            2,
            definition.staticOccurrences.count { it.commitBinding is ResourceCommitBinding.BasemapStyle },
            "two items referencing one style are two occurrences joining one route",
        )
        assertEquals(
            1,
            definition.staticOccurrences
                .mapNotNull { (it.commitBinding as? ResourceCommitBinding.BasemapStyle)?.groupId }
                .distinct()
                .size,
            "every style in one invocation commits under one group",
        )
    }

    /**
     * `discoveryRequired` no longer announces children — a validated style announces a route manifest —
     * but it is still what opens the depth-first frontier that holds a frame's other resources back
     * until the style document has been read, so it stays exactly on the style and nowhere else.
     */
    @Test
    fun marksOnlyTheStyleForDiscoveryAndBindsOnlyTheStyleToAStyleCommit() {
        val definition = buildResourceOperationDefinition(
            traversalsByItem = listOf(
                listOf(
                    external(ResourceClass.BASEMAP_STYLE, "https://styles.example/basic.json"),
                    external(ResourceClass.STICKER_IMAGE, "https://images.example/a.png"),
                    external(ResourceClass.MODEL_GLB, "https://models.example/a.glb"),
                ),
            ),
            accessMode = ResourceAccessMode.RELOAD,
            maximumConcurrentRoutes = 2,
        )

        definition.staticOccurrences.forEach { occurrence ->
            val style = occurrence.registration.route.resourceClass == ResourceClass.BASEMAP_STYLE
            assertEquals(style, occurrence.discoveryRequired, occurrence.registration.route.toString())
            assertEquals(
                style,
                occurrence.commitBinding is ResourceCommitBinding.BasemapStyle,
                occurrence.registration.route.toString(),
            )
            assertEquals(ResourceAccessMode.RELOAD, occurrence.registration.route.accessMode)
        }
        assertEquals(ResourceOccurrenceId(1L), definition.staticOccurrences.first().id)
    }

    @Test
    fun recordsAGeometryProgramsIdentityWithoutMakingItAFetchedOccurrence() {
        val shaderPair = ShaderPair(
            vertexSource = "#version 300 es\nvoid main(){}",
            fragmentSource = "#version 300 es\nvoid main(){}",
        )
        val program = ResourceKeyDeriver(PureKotlinSha256).geometryProgram(shaderPair)
        val definition = buildResourceOperationDefinition(
            traversalsByItem = listOf(
                listOf(
                    StaticResourceReference.GeometryProgram(
                        shaderPair = shaderPair,
                        resourceKey = program.key,
                        canonicalIdentity = program.identity,
                    ),
                    external(ResourceClass.STICKER_IMAGE, "https://images.example/a.png"),
                ),
            ),
            accessMode = ResourceAccessMode.NORMAL,
            maximumConcurrentRoutes = 1,
        )

        assertEquals(1, definition.staticOccurrences.size, "a shader program is compiled, never fetched")
        assertEquals(2, definition.resourceIdentities.size, "but its canonical identity still takes part")
        assertTrue(definition.resourceIdentities.any { it.resourceKey == program.key })
        assertFalse(definition.staticOccurrences.any { it.registration.resourceKey == program.key })
    }
}

private fun external(resourceClass: ResourceClass, url: String): StaticResourceReference.External {
    val locator = ResourceLocator(url)
    val derived = ResourceKeyDeriver(PureKotlinSha256).external(resourceClass, locator)
    return StaticResourceReference.External(
        resourceClass = resourceClass,
        locator = locator,
        maximumResponseBytes = ResourceLimits().maximumBytesFor(resourceClass),
        resourceKey = derived.key,
        rawKey = requireNotNull(derived.rawKey),
        privateRentileKey = ProductionRentilePrivateKeyResolver(PureKotlinSha256).resolve(locator, resourceClass),
        canonicalIdentity = derived.identity,
    )
}
