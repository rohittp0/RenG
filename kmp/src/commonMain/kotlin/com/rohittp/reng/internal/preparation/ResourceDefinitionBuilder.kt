package com.rohittp.reng.internal.preparation

import com.rohittp.reng.ResourceAccessMode
import com.rohittp.reng.ResourceClass
import com.rohittp.reng.internal.planning.StaticResourceReference
import com.rohittp.reng.internal.resource.CanonicalIdentityRecord
import com.rohittp.reng.internal.resource.ResourceCommitBinding
import com.rohittp.reng.internal.resource.ResourceOccurrence
import com.rohittp.reng.internal.resource.ResourceOccurrenceId
import com.rohittp.reng.internal.resource.ResourceOperationDefinition
import com.rohittp.reng.internal.resource.ResourceOwnerId
import com.rohittp.reng.internal.resource.ResourceRouteKey
import com.rohittp.reng.internal.resource.ResourceRouteRegistration
import com.rohittp.reng.internal.resource.StyleGroupId

/**
 * The one group every basemap style in one preparation invocation commits under. A style is a
 * per-renderer configuration value, not per-item content, so every preparation item that draws a
 * basemap references the same style through the same group.
 */
internal val BASEMAP_STYLE_GROUP: StyleGroupId = StyleGroupId(1L)

/**
 * Turns the already-planned static traversals of one preparation invocation — one list per preparation
 * item, in item order — into the [ResourceOperationDefinition] the resource driver runs.
 *
 * **One [ResourceOwnerId] per preparation item, never per reference.** This is what makes ADR 0016's
 * style-owner barrier — "write the style only after successful compilation *and* completion of all other
 * work for the referencing preparation items" — mean anything at all. `styleReferencingOwnerIds` derives
 * the barrier's owner set from the style occurrence's own owners, and `ownerNonStyleWorkComplete` asks
 * whether those owners have any non-style occurrence still without installed visibility. Give the style
 * an owner of its own and that owner has no other work by construction, so the barrier is *vacuously*
 * true and the ordering guarantee silently stops being enforced. Sharing one owner across everything a
 * single frame plan asked for is what puts the frame's sticker images and consumer textures inside the
 * barrier's reach.
 *
 * Occurrence identity is invocation-wide and 1-based ([ResourceOccurrenceId] and [ResourceOwnerId] both
 * reject zero), assigned in traversal order across items so the operation's traversal order is the
 * plan's own.
 *
 * The basemap style is the one class that declares `discoveryRequired`. It carries no children any more
 * — validation announces a *route manifest* instead — but the flag stays load-bearing: it is what opens
 * the depth-first frontier that holds a frame's other resources back until the style document has been
 * read, and so what keeps the style's route ahead of them in traversal order.
 *
 * Non-[StaticResourceReference.External] references (a geometry's shader program) contribute a canonical
 * identity record and no occurrence: they are compiled from the plan, never fetched.
 */
internal fun buildResourceOperationDefinition(
    traversalsByItem: List<List<StaticResourceReference>>,
    accessMode: ResourceAccessMode,
    maximumConcurrentRoutes: Int,
): ResourceOperationDefinition {
    val occurrences = mutableListOf<ResourceOccurrence>()
    val identities = mutableListOf<CanonicalIdentityRecord>()
    var nextOccurrenceId = 1L
    traversalsByItem.forEachIndexed { index, traversal ->
        val ownerId = ResourceOwnerId(index + 1L)
        traversal.forEach { reference ->
            identities += CanonicalIdentityRecord(
                resourceKey = reference.resourceKey,
                canonicalBytes = reference.canonicalIdentity.canonicalBytes,
            )
            if (reference !is StaticResourceReference.External) {
                return@forEach
            }
            val style = reference.resourceClass == ResourceClass.BASEMAP_STYLE
            occurrences += ResourceOccurrence(
                id = ResourceOccurrenceId(nextOccurrenceId++),
                ownerId = ownerId,
                registration = ResourceRouteRegistration(
                    route = ResourceRouteKey(
                        accessMode = accessMode,
                        locator = reference.locator,
                        resourceClass = reference.resourceClass,
                        maximumResponseBytes = reference.maximumResponseBytes,
                    ),
                    resourceKey = reference.resourceKey,
                    rawKey = reference.rawKey,
                    privateRentileKey = reference.privateRentileKey,
                    canonicalBytes = reference.canonicalIdentity.canonicalBytes,
                ),
                discoveryRequired = style,
                commitBinding = if (style) {
                    ResourceCommitBinding.BasemapStyle(BASEMAP_STYLE_GROUP)
                } else {
                    ResourceCommitBinding.Single
                },
            )
        }
    }
    return ResourceOperationDefinition(
        maximumConcurrentRoutes = maximumConcurrentRoutes,
        staticOccurrences = occurrences,
        resourceIdentities = identities,
    )
}
