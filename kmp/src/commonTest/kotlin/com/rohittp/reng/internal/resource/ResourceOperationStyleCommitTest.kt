package com.rohittp.reng.internal.resource

import com.rohittp.reng.PipelineStage
import com.rohittp.reng.RawResourceKey
import com.rohittp.reng.RenGErrorCode
import com.rohittp.reng.ResourceAccessMode
import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceKind
import com.rohittp.reng.ResourceLocator
import com.rohittp.reng.StoredRawResource
import com.rohittp.reng.StoredRawResourceMetadata
import com.rohittp.reng.TransportResponse
import com.rohittp.reng.TransportResponseMetadata
import com.rohittp.reng.internal.DiagnosticField
import com.rohittp.reng.internal.failure.FailureDescriptor
import com.rohittp.reng.internal.failureContextDiagnostic
import com.rohittp.reng.internal.identity.CanonicalBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResourceOperationStyleCommitTest {
    @Test
    fun styleValidationIsRequestedOnlyForAnActiveStyleRouteThroughItsOwnAdvancement() {
        val ordinaryDriver = StyleDriver(
            definitionOf(concurrency = 1, occurrences = ordinaryOccurrences(9L, OWNER_A, '9')),
        )
        ordinaryDriver.driveToPendingContent(0L, ContentProvenance.TRANSPORT_200)
        assertFailsWith<IllegalArgumentException> {
            ResourceOperationStateMachine.transition(ordinaryDriver.state, AdvancePendingStyleCommit(0L))
        }

        val driver = StyleDriver(styleDefinition(concurrency = 1))
        driver.driveToPendingContent(STYLE_ORDINAL, ContentProvenance.TRANSPORT_200)
        val content = driver.candidate(STYLE_ORDINAL)

        assertFailsWith<IllegalArgumentException> {
            ResourceOperationStateMachine.transition(driver.state, AdvancePendingClassGates(STYLE_ORDINAL))
        }
        assertFailsWith<IllegalArgumentException> {
            ResourceOperationStateMachine.transition(driver.state, AdvancePendingSpriteCommit(STYLE_ORDINAL))
        }
        assertFailsWith<IllegalArgumentException> {
            ResourceOperationStateMachine.transition(driver.state, AdvancePendingStyleCommit(7L))
        }
        assertIs<PendingClassGates>(driver.record(STYLE_ORDINAL).cursor)
        assertTrue(driver.state.styleCommitStates.isEmpty())

        driver.advanceStyleCommit(STYLE_ORDINAL)

        val validation = assertIs<ValidateBasemapStyle>(driver.actions.single())
        assertEquals(STYLE_ORDINAL, validation.ordinal)
        assertEquals(STYLE_GROUP, validation.groupId)
        assertEquals(content, validation.content)
        assertEquals(
            AwaitingStyleValidation(validation.actionId, STYLE_ORDINAL, STYLE_GROUP, content),
            driver.record(STYLE_ORDINAL).cursor,
        )
        val style = driver.style()
        assertEquals(STYLE_ORDINAL, style.ordinal)
        assertEquals(content, style.stagedContent)
        assertEquals(StyleCompilationStatus.WAITING, style.compilationStatus)
        assertEquals(listOf(ResourceOwnerId(OWNER_A), ResourceOwnerId(OWNER_B)), style.referencingOwnerIds)
        assertTrue(style.ownersWithCompletedNonStyleWork.isEmpty())
        assertFalse(style.writeAcknowledged)
        assertFalse(style.visible)
        assertEquals(listOf(STYLE_ORDINAL), driver.state.activeRouteOrdinals)
        assertTrue(driver.parked.isEmpty())
        assertNull(driver.outcome)
        driver.assertNoStyleCommitWork("validation requested", allowValidation = true)
        driver.assertNoRecoveryActions("validation requested")

        assertFailsWith<IllegalArgumentException> {
            ResourceOperationStateMachine.transition(driver.state, AdvancePendingStyleCommit(STYLE_ORDINAL))
        }
    }

    /**
     * The route manifest is the one thing carried from validation to compilation, and losing it between
     * the two is invisible to the pure core: the compilation would still be emitted, still succeed, and
     * only fail closed later at the firewall when the engine asks for a url nobody preregistered. So it
     * is asserted here, on the action itself.
     *
     * A validated style also releases its own traversal frontier immediately: it announced routes, not
     * occurrences, so there is no child to wait for and [ParkedRouteBarrier.StyleChildren] is satisfied
     * in the same transition it was entered. Its siblings are the frame's own resources, and they are
     * ordered against the style by the *owner* barrier, not by this one.
     */
    @Test
    fun validationCarriesItsRouteManifestIntoCompilationAndReleasesItsTraversalFrontier() {
        val driver = StyleDriver(styleDefinition(concurrency = 1))
        val validation = driver.driveToStyleValidation(ContentProvenance.TRANSPORT_200)

        assertFailsWith<IllegalArgumentException> {
            BasemapStyleValidationOutcome.Valid(styleManifestRoutes() + styleManifestRoutes())
        }
        val valid = BasemapStyleValidationOutcome.Valid(styleManifestRoutes())
        assertNotSame(valid.routes, valid.routes)

        driver.event(BasemapStyleValidationCompleted(validation.actionId, valid))

        val compilation = assertIs<CompileBasemapStyle>(driver.actions.single())
        assertEquals(styleManifestRoutes(), compilation.routes, "the manifest reaches the compilation")
        assertEquals(styleManifestRoutes(), driver.style().styleTimeRoutes)
        assertNotSame(compilation.routes, compilation.routes)

        assertEquals(listOf(STYLE_ORDINAL), driver.state.activeRouteOrdinals)
        assertTrue(driver.parked.isEmpty(), "a route manifest leaves no child to wait for")
        assertTrue(driver.state.traversal.frontierStack.isEmpty())
        assertEquals(ResourceRouteStatus.RUNNING, driver.record(STYLE_ORDINAL).status)
        assertFalse(driver.record(STYLE_ORDINAL).visibilityInstalled)
        assertEquals(0L, driver.state.nextRetirementOrdinal)
        assertTrue(driver.state.bufferedRouteOutcomes.isEmpty())
        assertEquals(StyleCompilationStatus.REQUESTED, driver.style().compilationStatus)
        assertEquals(
            listOf(STICKER_A_ORDINAL, STICKER_B_ORDINAL),
            driver.state.routeRecords
                .filter { it.registration.route.resourceClass == ResourceClass.STICKER_IMAGE }
                .mapNotNull { it.ordinal }
                .sorted(),
            "the style's siblings are released into traversal, and are its own frame's resources",
        )
        // No engine-keyed class ever becomes a route of this operation; that is what a manifest is for.
        assertTrue(
            driver.state.routeRecords.none {
                it.registration.route.resourceClass == ResourceClass.BASEMAP_SPRITE_JSON ||
                    it.registration.route.resourceClass == ResourceClass.BASEMAP_SPRITE_IMAGE
            },
        )

        val compilingState = driver.state
        assertEquals(
            "route completion requires no in-flight adapter action",
            assertFailsWith<IllegalArgumentException> {
                ResourceOperationStateMachine.transition(
                    compilingState,
                    RouteCompleted(STYLE_ORDINAL, ResourceRouteOutcome.Success),
                )
            }.message,
        )
        assertFailsWith<IllegalArgumentException> {
            ResourceOperationStateMachine.transition(compilingState, AdvancePendingStyleCommit(STYLE_ORDINAL))
        }
    }

    @Test
    fun residentStyleUsesItsCompiledGenerationAndNeverCompilesOrWrites() {
        val driver = StyleDriver(styleDefinition(concurrency = 1, styleProvenance = ContentProvenance.RESIDENT))
        val content = driver.driveToStyleOwnerBarrier(ContentProvenance.RESIDENT)

        assertEquals(StyleCompilationStatus.NOT_REQUIRED, driver.style().compilationStatus)
        assertTrue(driver.emitted.filterIsInstance<CompileBasemapStyle>().isEmpty())
        assertEquals(
            listOf(ParkedRoute(STYLE_ORDINAL, ParkedRouteBarrier.StyleOwners(STYLE_GROUP))),
            driver.parked,
        )

        driver.driveOwnerRoutes()

        val install = assertIs<InstallBasemapStyleVisibility>(driver.actions.single())
        assertEquals(content, install.content)
        assertEquals(listOf(ResourceOwnerId(OWNER_A), ResourceOwnerId(OWNER_B)), install.referencingOwnerIds)
        assertTrue(driver.emitted.filterIsInstance<WriteBasemapStyle>().isEmpty())
        assertTrue(driver.emitted.filterIsInstance<WriteStore>().none { it.ordinal == STYLE_ORDINAL })

        driver.event(
            BasemapStyleVisibilityInstallCompleted(install.actionId, STYLE_GROUP, SuppliedInstallOutcome.Succeeded),
        )

        assertTrue(driver.style().visible)
        assertFalse(driver.style().writeAcknowledged)
        assertIs<ResourceOperationOutcome.Success>(driver.outcome)
        assertTrue(driver.emitted.filterIsInstance<WriteBasemapStyle>().isEmpty())
        driver.assertNoRecoveryActions("resident style")
    }

    @Test
    fun storeStyleCompilesPrivatelyAndInstallsWithoutAnyRewrite() {
        val driver = StyleDriver(styleDefinition(concurrency = 1, styleProvenance = ContentProvenance.STORE))
        val content = driver.driveToStyleOwnerBarrier(ContentProvenance.STORE)

        assertEquals(StyleCompilationStatus.SUCCEEDED, driver.style().compilationStatus)
        assertEquals(1, driver.emitted.filterIsInstance<CompileBasemapStyle>().size)
        assertFalse(driver.style().visible)
        assertFalse(driver.record(STYLE_ORDINAL).visibilityInstalled)
        assertTrue(driver.emitted.filterIsInstance<InstallBasemapStyleVisibility>().isEmpty())

        driver.driveOwnerRoutes()

        val install = assertIs<InstallBasemapStyleVisibility>(driver.actions.single())
        assertEquals(content, install.content)
        driver.event(
            BasemapStyleVisibilityInstallCompleted(install.actionId, STYLE_GROUP, SuppliedInstallOutcome.Succeeded),
        )

        assertTrue(driver.style().visible)
        assertFalse(driver.style().writeAcknowledged)
        assertTrue(driver.emitted.filterIsInstance<WriteBasemapStyle>().isEmpty())
        assertIs<ResourceOperationOutcome.Success>(driver.outcome)
        driver.assertNoRecoveryActions("store style")
    }

    @Test
    fun transportedStyleWritesExactlyOnceAfterCompilationAndTheWholeOwnerBarrier() {
        listOf(ContentProvenance.TRANSPORT_200, ContentProvenance.TRANSPORT_304_MERGED).forEach { provenance ->
            val label = provenance.name
            val driver = StyleDriver(styleDefinition(concurrency = 1, styleProvenance = provenance))
            val content = driver.driveToStyleOwnerBarrier(provenance)

            assertEquals(StyleCompilationStatus.SUCCEEDED, driver.style().compilationStatus, label)
            assertTrue(driver.emitted.filterIsInstance<WriteBasemapStyle>().isEmpty(), label)
            assertEquals(
                listOf(ParkedRoute(STYLE_ORDINAL, ParkedRouteBarrier.StyleOwners(STYLE_GROUP))),
                driver.parked,
                label,
            )

            driver.driveOrdinaryRoute(driver.ownerRouteOrdinals()[0])
            assertTrue(driver.emitted.filterIsInstance<WriteBasemapStyle>().isEmpty(), "$label/one owner")
            assertEquals(
                listOf(ResourceOwnerId(OWNER_A)),
                driver.style().ownersWithCompletedNonStyleWork,
                "$label/one owner",
            )
            driver.driveOrdinaryRoute(driver.ownerRouteOrdinals()[1])

            val write = assertIs<WriteBasemapStyle>(driver.actions.single(), label)
            assertEquals(STYLE_ORDINAL, write.ordinal, label)
            assertEquals(STYLE_GROUP, write.groupId, label)
            assertEquals(driver.record(STYLE_ORDINAL).registration.rawKey, write.rawKey, label)
            assertEquals(content.stored, write.resource, label)
            assertEquals(
                AwaitingStyleWrite(write.actionId, STYLE_ORDINAL, STYLE_GROUP, content),
                driver.record(STYLE_ORDINAL).cursor,
                label,
            )
            assertEquals(
                listOf(ResourceOwnerId(OWNER_A), ResourceOwnerId(OWNER_B)),
                driver.style().ownersWithCompletedNonStyleWork,
                label,
            )
            assertFalse(driver.style().writeAcknowledged, label)
            assertTrue(driver.emitted.filterIsInstance<InstallBasemapStyleVisibility>().isEmpty(), label)

            driver.event(BasemapStyleWriteCompleted(write.actionId, STYLE_GROUP, SuppliedCallOutcome.Success(Unit)))
            assertTrue(driver.style().writeAcknowledged, label)

            val install = assertIs<InstallBasemapStyleVisibility>(driver.actions.single(), label)
            assertEquals(
                AwaitingStyleVisibilityInstall(
                    install.actionId,
                    STYLE_ORDINAL,
                    STYLE_GROUP,
                    content,
                    listOf(ResourceOwnerId(OWNER_A), ResourceOwnerId(OWNER_B)),
                ),
                driver.record(STYLE_ORDINAL).cursor,
                label,
            )
            driver.event(
                BasemapStyleVisibilityInstallCompleted(install.actionId, STYLE_GROUP, SuppliedInstallOutcome.Succeeded),
            )

            assertTrue(driver.style().visible, label)
            assertTrue(driver.record(STYLE_ORDINAL).visibilityInstalled, label)
            assertEquals(1, driver.emitted.filterIsInstance<WriteBasemapStyle>().size, label)
            assertEquals(1, driver.emitted.filterIsInstance<CompileBasemapStyle>().size, label)
            assertEquals(1, driver.emitted.filterIsInstance<InstallBasemapStyleVisibility>().size, label)
            assertTrue(driver.emitted.filterIsInstance<WriteStore>().none { it.ordinal == STYLE_ORDINAL }, label)
            assertIs<ResourceOperationOutcome.Success>(driver.outcome, label)
            assertEquals(
                listOf(
                    StyleCommitOrder.VALIDATE,
                    StyleCommitOrder.COMPILE,
                    StyleCommitOrder.WRITE,
                    StyleCommitOrder.INSTALL,
                ),
                driver.styleCommitOrder(),
                label,
            )
            driver.assertNoRecoveryActions(label)
        }
    }

    @Test
    fun styleValidationFailuresMapTheirExactKindExceptUnderStoreProvenance() {
        ContentProvenance.entries.forEach { provenance ->
            StyleFailureKind.entries.forEach { kind ->
                val label = "$provenance/$kind"
                val driver = StyleDriver(styleDefinition(concurrency = 1, styleProvenance = provenance))
                val validation = driver.driveToStyleValidation(provenance)
                val content = driver.candidate(STYLE_ORDINAL)

                driver.event(
                    BasemapStyleValidationCompleted(
                        validation.actionId,
                        BasemapStyleValidationOutcome.Failed(kind),
                    ),
                )

                assertStyleFailure(driver.outcome, provenance, kind, content.resourceKey, label)
                assertFalse(driver.style().visible, label)
                assertFalse(driver.record(STYLE_ORDINAL).visibilityInstalled, label)
                driver.assertNoStyleCommitWork(label, allowValidation = true)
                driver.assertNoRecoveryActions(label)
                assertTrue(driver.emitted.filterIsInstance<CancelRoute>().isEmpty(), label)
            }
        }
    }

    @Test
    fun styleCompilationFailuresMapTheSameKindsWithTheStoreOverrideAndInstallNothing() {
        ContentProvenance.entries.filter { it != ContentProvenance.RESIDENT }.forEach { provenance ->
            StyleFailureKind.entries.forEach { kind ->
                val label = "$provenance/$kind"
                val driver = StyleDriver(styleDefinition(concurrency = 1, styleProvenance = provenance))
                val compilation = driver.driveToStyleCompilation(provenance)
                val content = driver.candidate(STYLE_ORDINAL)

                driver.event(
                    BasemapStyleCompilationCompleted(
                        compilation.actionId,
                        BasemapStyleCompilationOutcome.Failed(kind),
                    ),
                )

                assertStyleFailure(driver.outcome, provenance, kind, content.resourceKey, label)
                assertEquals(StyleCompilationStatus.FAILED, driver.style().compilationStatus, label)
                assertFalse(driver.style().visible, label)
                assertFalse(driver.style().writeAcknowledged, label)
                assertTrue(driver.emitted.filterIsInstance<WriteBasemapStyle>().isEmpty(), label)
                assertTrue(driver.emitted.filterIsInstance<InstallBasemapStyleVisibility>().isEmpty(), label)
                driver.assertNoRecoveryActions(label)
            }
        }
    }

    /**
     * The compilation failure that is **not** a property of the style document: the firewall refused a
     * url RenG never preregistered, or a consumer adapter the engine reached through it failed. Its
     * descriptor is already sanitized and already carries RenG's own resource identity by the time it
     * gets here, so it is forwarded verbatim rather than collapsed into a [StyleFailureKind] — reporting
     * an `AMBIGUOUS_RESOURCE_ROUTE` as `RESOURCE_PARSE_FAILED` would misdescribe a document that parses
     * perfectly well, and would hide a firewall misconfiguration behind a content fault.
     *
     * The `STORE`-provenance override deliberately does **not** apply: that override exists to say "bytes
     * this Store handed us are not what they claim to be", which is a statement about the bytes. An
     * unrouted engine exchange says nothing about the bytes at all, whatever provenance they arrived by.
     */
    @Test
    fun anEngineCompilationFailureIsForwardedVerbatimUnderEveryProvenance() {
        ContentProvenance.entries.filter { it != ContentProvenance.RESIDENT }.forEach { provenance ->
            val label = provenance.name
            val driver = StyleDriver(styleDefinition(concurrency = 1, styleProvenance = provenance))
            val compilation = driver.driveToStyleCompilation(provenance)

            driver.event(
                BasemapStyleCompilationCompleted(
                    compilation.actionId,
                    BasemapStyleCompilationOutcome.EngineFailed(ENGINE_ROUTE_FAILURE),
                ),
            )

            val failure = assertIs<ResourceOperationOutcome.Failure>(driver.outcome, label)
            assertEquals(ENGINE_ROUTE_FAILURE, failure.failure, label)
            assertEquals(RenGErrorCode.AMBIGUOUS_RESOURCE_ROUTE, failure.failure.code, label)
            assertEquals(PipelineStage.RESOURCE_LOOKUP, failure.failure.stage, label)
            assertEquals(StyleCompilationStatus.FAILED, driver.style().compilationStatus, label)
            assertFalse(driver.style().visible, label)
            assertFalse(driver.style().writeAcknowledged, label)
            assertFalse(driver.record(STYLE_ORDINAL).visibilityInstalled, label)
            assertTrue(driver.emitted.filterIsInstance<WriteBasemapStyle>().isEmpty(), label)
            assertTrue(driver.emitted.filterIsInstance<InstallBasemapStyleVisibility>().isEmpty(), label)
            driver.assertNoRecoveryActions(label)
        }
    }

    @Test
    fun styleWriteAndInstallFailuresOrCancellationsInstallNothing() {
        val writeFailureDriver = StyleDriver(styleDefinition(concurrency = 1))
        val write = writeFailureDriver.driveToStyleWrite()
        val content = writeFailureDriver.candidate(STYLE_ORDINAL)
        writeFailureDriver.event(
            BasemapStyleWriteCompleted(write.actionId, STYLE_GROUP, SuppliedCallOutcome.Failed),
        )
        assertResourceFailure(
            outcome = writeFailureDriver.outcome,
            code = RenGErrorCode.STORE_WRITE_FAILED,
            stage = PipelineStage.STORE_WRITE,
            expectedField = null,
            resourceClass = ResourceClass.BASEMAP_STYLE,
            resourceKey = content.resourceKey,
            label = "style write failure",
        )
        assertFalse(writeFailureDriver.style().writeAcknowledged)
        assertFalse(writeFailureDriver.style().visible)
        assertTrue(writeFailureDriver.emitted.filterIsInstance<InstallBasemapStyleVisibility>().isEmpty())

        val writeCancelDriver = StyleDriver(styleDefinition(concurrency = 1))
        val cancelledWrite = writeCancelDriver.driveToStyleWrite()
        writeCancelDriver.event(
            BasemapStyleWriteCompleted(
                cancelledWrite.actionId,
                STYLE_GROUP,
                SuppliedCallOutcome.Cancelled(ADAPTER_CANCELLATION),
            ),
        )
        assertEquals(
            ResourceOperationOutcome.Cancelled(ADAPTER_CANCELLATION),
            writeCancelDriver.outcome,
        )
        assertTrue(writeCancelDriver.emitted.filterIsInstance<InstallBasemapStyleVisibility>().isEmpty())

        val installFailureDriver = StyleDriver(styleDefinition(concurrency = 1))
        val install = installFailureDriver.driveToStyleInstall()
        installFailureDriver.event(
            BasemapStyleVisibilityInstallCompleted(
                install.actionId,
                STYLE_GROUP,
                SuppliedInstallOutcome.Failed(INSTALL_FAILURE),
            ),
        )
        assertEquals(ResourceOperationOutcome.Failure(INSTALL_FAILURE), installFailureDriver.outcome)
        assertFalse(installFailureDriver.style().visible)
        assertFalse(installFailureDriver.record(STYLE_ORDINAL).visibilityInstalled)

        val installCancelDriver = StyleDriver(styleDefinition(concurrency = 1))
        val cancelledInstall = installCancelDriver.driveToStyleInstall()
        installCancelDriver.event(
            BasemapStyleVisibilityInstallCompleted(
                cancelledInstall.actionId,
                STYLE_GROUP,
                SuppliedInstallOutcome.Cancelled(ADAPTER_CANCELLATION),
            ),
        )
        assertEquals(
            ResourceOperationOutcome.Cancelled(ADAPTER_CANCELLATION),
            installCancelDriver.outcome,
        )
        assertFalse(installCancelDriver.style().visible)
        installCancelDriver.assertNoRecoveryActions("style install cancellation")
    }

    @Test
    fun styleValidationCancellationClosesTheRouteWithoutStyleWork() {
        val driver = StyleDriver(styleDefinition(concurrency = 1))
        val validation = driver.driveToStyleValidation(ContentProvenance.TRANSPORT_200)

        driver.event(
            BasemapStyleValidationCompleted(
                validation.actionId,
                BasemapStyleValidationOutcome.Cancelled(ADAPTER_CANCELLATION),
            ),
        )

        assertEquals(ResourceOperationOutcome.Cancelled(ADAPTER_CANCELLATION), driver.outcome)
        assertEquals(CancellationCause.ADAPTER, ADAPTER_CANCELLATION.cause)
        driver.assertNoStyleCommitWork("validation cancellation", allowValidation = true)
        driver.assertNoRecoveryActions("validation cancellation")
    }

    @Test
    fun failingOtherWorkBuffersBehindTheParkedStyleAndInstallsNothing() {
        val driver = StyleDriver(styleDefinition(concurrency = 1))
        driver.driveStyleManifest()
        driver.completeStyleCommit()
        assertEquals(listOf(ParkedRoute(STYLE_ORDINAL, ParkedRouteBarrier.StyleOwners(STYLE_GROUP))), driver.parked)

        driver.driveToPendingContent(STICKER_A_ORDINAL, ContentProvenance.TRANSPORT_200)
        driver.event(AdvancePendingClassGates(STICKER_A_ORDINAL))
        val gate = assertIs<ValidateResourceClass>(driver.actions.single())
        driver.event(ResourceClassValidationCompleted(gate.actionId, SuppliedValidationOutcome.Failed))

        assertResourceFailure(
            outcome = driver.outcome,
            code = RenGErrorCode.RESOURCE_DECODE_FAILED,
            stage = PipelineStage.RESOURCE_DECODING,
            expectedField = DiagnosticField.RESOURCE.wireName,
            resourceClass = ResourceClass.STICKER_IMAGE,
            resourceKey = driver.candidate(STICKER_A_ORDINAL).resourceKey,
            label = "owner work failure",
        )
        assertEquals(
            ResourceTerminalSelection.Route(
                STICKER_A_ORDINAL,
                ResourceRouteOutcome.Failure(assertIs<ResourceOperationOutcome.Failure>(driver.outcome).failure),
            ),
            driver.state.terminalSelection,
        )
        assertTrue(driver.parked.isEmpty())
        assertEquals(ResourceRouteStatus.RESOLVED, driver.record(STYLE_ORDINAL).status)
        assertFalse(driver.record(STYLE_ORDINAL).visibilityInstalled)
        assertFalse(driver.style().visible)
        assertEquals(StyleCompilationStatus.SUCCEEDED, driver.style().compilationStatus)
        assertTrue(driver.emitted.filterIsInstance<WriteBasemapStyle>().isEmpty())
        assertTrue(driver.emitted.filterIsInstance<InstallBasemapStyleVisibility>().isEmpty())
        assertTrue(driver.emitted.filterIsInstance<CancelRoute>().isEmpty())
        driver.assertNoRecoveryActions("owner work failure")
    }

    @Test
    fun aFailureAboveAnUnretiredStyleBuffersAndThenClosesItWithoutWriteOrInstall() {
        val driver = StyleDriver(styleDefinition(concurrency = 2))
        driver.driveStyleManifest()
        val compilation = driver.actions.filterIsInstance<CompileBasemapStyle>().single()
        driver.driveOrdinaryRoute(STICKER_A_ORDINAL)
        assertEquals(
            listOf(BufferedRouteOutcome(STICKER_A_ORDINAL, ResourceRouteOutcome.Success)),
            driver.state.bufferedRouteOutcomes,
        )
        assertEquals(0L, driver.state.nextRetirementOrdinal)

        driver.driveToPendingContent(STICKER_B_ORDINAL, ContentProvenance.TRANSPORT_200)
        driver.event(AdvancePendingClassGates(STICKER_B_ORDINAL))
        val gate = assertIs<ValidateResourceClass>(driver.actions.single())
        driver.event(ResourceClassValidationCompleted(gate.actionId, SuppliedValidationOutcome.Failed))

        assertEquals(
            listOf(STICKER_A_ORDINAL, STICKER_B_ORDINAL),
            driver.state.bufferedRouteOutcomes.map(BufferedRouteOutcome::ordinal),
        )
        assertEquals(0L, driver.state.nextRetirementOrdinal)
        assertNull(driver.state.terminalSelection)
        assertNull(driver.outcome)
        assertIs<AwaitingStyleCompilation>(driver.record(STYLE_ORDINAL).cursor)
        assertEquals(STICKER_B_ORDINAL, driver.state.startCeilingOrdinal)

        driver.event(
            BasemapStyleCompilationCompleted(compilation.actionId, BasemapStyleCompilationOutcome.Succeeded),
        )

        assertIs<ResourceOperationOutcome.Failure>(driver.outcome)
        assertEquals(
            ResourceTerminalSelection.Route(
                STICKER_B_ORDINAL,
                ResourceRouteOutcome.Failure(assertIs<ResourceOperationOutcome.Failure>(driver.outcome).failure),
            ),
            driver.state.terminalSelection,
        )
        assertTrue(driver.parked.isEmpty())
        assertFalse(driver.style().visible)
        assertFalse(driver.record(STYLE_ORDINAL).visibilityInstalled)
        assertEquals(ResourceRouteStatus.RESOLVED, driver.record(STYLE_ORDINAL).status)
        assertTrue(driver.emitted.filterIsInstance<WriteBasemapStyle>().isEmpty())
        assertTrue(driver.emitted.filterIsInstance<InstallBasemapStyleVisibility>().isEmpty())
        driver.assertNoRecoveryActions("failure above the style")
    }

    @Test
    fun completionOrderChangesNeitherCommitOrderNorVisibility() {
        val forward = StyleDriver(styleDefinition(concurrency = 2))
        forward.driveStyleManifest()
        forward.completeStyleCommit()
        forward.driveOrdinaryRoute(STICKER_A_ORDINAL)
        forward.driveOrdinaryRoute(STICKER_B_ORDINAL)
        forward.finishStyleWriteAndInstall()

        val reversed = StyleDriver(styleDefinition(concurrency = 2))
        reversed.driveStyleManifest()
        reversed.completeStyleCommit()
        reversed.driveOrdinaryRoute(STICKER_B_ORDINAL)
        reversed.driveOrdinaryRoute(STICKER_A_ORDINAL)
        reversed.finishStyleWriteAndInstall()

        assertEquals(forward.outcome, reversed.outcome)
        assertEquals(forward.state.visibleResourcesByOwner, reversed.state.visibleResourcesByOwner)
        assertEquals(forward.style().referencingOwnerIds, reversed.style().referencingOwnerIds)
        assertTrue(forward.style().visible)
        assertTrue(reversed.style().visible)
        assertEquals(forward.styleCommitOrder(), reversed.styleCommitOrder())
        assertEquals(
            listOf(
                StyleCommitOrder.VALIDATE,
                StyleCommitOrder.COMPILE,
                StyleCommitOrder.WRITE,
                StyleCommitOrder.INSTALL,
            ),
            reversed.styleCommitOrder(),
        )
    }

    @Test
    fun concurrencyOneParksForItsOwnerBarrierAndKeepsEveryActionInsideOneSlot() {
        val driver = StyleDriver(styleDefinition(concurrency = 1))
        val validation = driver.driveToStyleValidation(ContentProvenance.TRANSPORT_200)
        driver.assertSingleSlot("validation")

        driver.event(
            BasemapStyleValidationCompleted(
                validation.actionId,
                BasemapStyleValidationOutcome.Valid(styleManifestRoutes()),
            ),
        )

        val compilation = assertIs<CompileBasemapStyle>(driver.actions.single())
        assertEquals(STYLE_ORDINAL, compilation.ordinal)
        assertEquals(listOf(STYLE_ORDINAL), driver.state.activeRouteOrdinals)
        assertTrue(driver.parked.isEmpty())
        assertEquals(StyleCompilationStatus.REQUESTED, driver.style().compilationStatus)
        assertEquals(
            listOf(STICKER_A_ORDINAL, STICKER_B_ORDINAL),
            driver.state.routeRecords
                .filter { it.status == ResourceRouteStatus.ELIGIBLE }
                .mapNotNull { it.ordinal }
                .sorted(),
        )
        driver.assertStyleAssignedAndUnretired("compiling")

        driver.event(
            BasemapStyleCompilationCompleted(compilation.actionId, BasemapStyleCompilationOutcome.Succeeded),
        )

        assertEquals(listOf(ParkedRoute(STYLE_ORDINAL, ParkedRouteBarrier.StyleOwners(STYLE_GROUP))), driver.parked)
        assertEquals(listOf(STICKER_A_ORDINAL), driver.state.activeRouteOrdinals)
        assertEquals(StyleCompilationStatus.SUCCEEDED, driver.style().compilationStatus)
        assertFalse(driver.style().visible)
        assertTrue(driver.emitted.filterIsInstance<WriteBasemapStyle>().isEmpty())
        driver.assertStyleAssignedAndUnretired("parked for owners")

        driver.driveOrdinaryRoute(STICKER_A_ORDINAL)
        assertEquals(listOf(ParkedRoute(STYLE_ORDINAL, ParkedRouteBarrier.StyleOwners(STYLE_GROUP))), driver.parked)
        assertEquals(listOf(STICKER_B_ORDINAL), driver.state.activeRouteOrdinals)
        assertEquals(listOf(ResourceOwnerId(OWNER_A)), driver.style().ownersWithCompletedNonStyleWork)
        driver.assertStyleAssignedAndUnretired("one owner complete")

        driver.driveOrdinaryRoute(STICKER_B_ORDINAL)

        val write = assertIs<WriteBasemapStyle>(driver.actions.single())
        assertEquals(listOf(STYLE_ORDINAL), driver.state.activeRouteOrdinals)
        assertTrue(driver.parked.isEmpty())
        driver.event(BasemapStyleWriteCompleted(write.actionId, STYLE_GROUP, SuppliedCallOutcome.Success(Unit)))
        val install = assertIs<InstallBasemapStyleVisibility>(driver.actions.single())
        assertEquals(listOf(STYLE_ORDINAL), driver.state.activeRouteOrdinals)
        driver.event(
            BasemapStyleVisibilityInstallCompleted(install.actionId, STYLE_GROUP, SuppliedInstallOutcome.Succeeded),
        )

        assertEquals(3L, driver.state.nextRetirementOrdinal)
        assertTrue(driver.state.activeRouteOrdinals.isEmpty())
        driver.assertSingleSlot("complete")
        val success = assertIs<ResourceOperationOutcome.Success>(driver.outcome)
        assertEquals(
            listOf(ResourceOwnerId(OWNER_A), ResourceOwnerId(OWNER_B)),
            success.resourceSets.map(OwnerResourceSet::ownerId),
        )
        assertEquals(driver.state.visibleResourcesByOwner, success.resourceSets)
        driver.assertNoRecoveryActions("liveness trace")
    }

    @Test
    fun everyStyleCursorBlocksRetirementWhileItsAdapterActionIsInFlight() {
        val validationDriver = StyleDriver(styleDefinition(concurrency = 1))
        validationDriver.driveToStyleValidation(ContentProvenance.TRANSPORT_200)
        validationDriver.assertRetirementRefused("validation")

        val compilationDriver = StyleDriver(styleDefinition(concurrency = 1))
        compilationDriver.driveToStyleCompilation(ContentProvenance.TRANSPORT_200)
        compilationDriver.assertRetirementRefused("compilation")

        val writeDriver = StyleDriver(styleDefinition(concurrency = 1))
        writeDriver.driveToStyleWrite()
        writeDriver.assertRetirementRefused("write")

        val installDriver = StyleDriver(styleDefinition(concurrency = 1))
        installDriver.driveToStyleInstall()
        installDriver.assertRetirementRefused("install")
    }

    @Test
    fun styleCommitStateRejectsInconsistentProvenanceOwnersAndVisibility() {
        val driver = StyleDriver(styleDefinition(concurrency = 1))
        driver.driveToStyleValidation(ContentProvenance.TRANSPORT_200)
        val content = driver.candidate(STYLE_ORDINAL)
        val owners = listOf(ResourceOwnerId(OWNER_A), ResourceOwnerId(OWNER_B))

        assertFailsWith<IllegalArgumentException> {
            styleCommitState(content, StyleCompilationStatus.NOT_REQUIRED, owners, owners)
        }
        assertFailsWith<IllegalArgumentException> {
            styleCommitState(content, StyleCompilationStatus.WAITING, emptyList(), emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            styleCommitState(
                content,
                StyleCompilationStatus.WAITING,
                owners,
                listOf(ResourceOwnerId(OWNER_B), ResourceOwnerId(OWNER_A)),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            styleCommitState(content, StyleCompilationStatus.WAITING, owners, listOf(ResourceOwnerId(3L)))
        }
        assertFailsWith<IllegalArgumentException> {
            styleCommitState(content, StyleCompilationStatus.WAITING, owners, owners, writeAcknowledged = true)
        }
        assertFailsWith<IllegalArgumentException> {
            styleCommitState(content, StyleCompilationStatus.SUCCEEDED, owners, owners, visible = true)
        }
        assertFailsWith<IllegalArgumentException> {
            styleCommitState(
                content,
                StyleCompilationStatus.WAITING,
                owners,
                owners,
                writeAcknowledged = true,
                visible = true,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            styleCommitState(nonStyleContent(), StyleCompilationStatus.WAITING, owners, owners)
        }

        val ownerInput = owners.toMutableList()
        val staged = StyleCommitState(
            groupId = STYLE_GROUP,
            ordinal = STYLE_ORDINAL,
            stagedContent = content,
            compilationStatus = StyleCompilationStatus.WAITING,
            referencingOwnerIds = ownerInput,
            ownersWithCompletedNonStyleWork = emptyList(),
            writeAcknowledged = false,
            visible = false,
        )
        ownerInput.clear()
        assertEquals(owners, staged.referencingOwnerIds)
        assertNotSame(staged.referencingOwnerIds, staged.referencingOwnerIds)
        assertNotSame(staged.ownersWithCompletedNonStyleWork, staged.ownersWithCompletedNonStyleWork)
        assertEquals(staged, styleCommitState(content, StyleCompilationStatus.WAITING, owners, emptyList()))
        assertEquals(
            staged.hashCode(),
            styleCommitState(content, StyleCompilationStatus.WAITING, owners, emptyList()).hashCode(),
        )
        assertFalse(staged.toString().contains(content.resourceKey.stableId))
        assertFalse(staged.toString().contains(STYLE_LOCATOR))
    }

    @Test
    fun runningStateBindsStyleCommitStatesCursorsAndBarriersToTheirRoutes() {
        val driver = StyleDriver(styleDefinition(concurrency = 1))
        val validation = driver.driveToStyleValidation(ContentProvenance.TRANSPORT_200)
        val content = driver.candidate(STYLE_ORDINAL)
        val validationState = driver.state
        val style = driver.style()

        assertFailsWith<IllegalArgumentException> {
            copyState(validationState, styleCommitStates = emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            copyState(validationState, styleCommitStates = listOf(style, style))
        }
        assertFailsWith<IllegalArgumentException> {
            copyState(
                validationState,
                styleCommitStates = listOf(style.withCompilationStatus(StyleCompilationStatus.REQUESTED)),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            copyState(
                validationState,
                routeRecords = validationState.routeRecords.map { record ->
                    if (record.ordinal == STYLE_ORDINAL) {
                        routeRecord(
                            record,
                            AwaitingStyleCompilation(
                                validation.actionId,
                                STYLE_ORDINAL,
                                STYLE_GROUP,
                                content,
                            ),
                        )
                    } else {
                        record
                    }
                },
            )
        }
        assertFailsWith<IllegalArgumentException> {
            copyState(
                validationState,
                routeRecords = validationState.routeRecords.map { record ->
                    if (record.ordinal == STYLE_ORDINAL) {
                        routeRecord(
                            record,
                            AwaitingStyleWrite(validation.actionId, STYLE_ORDINAL, STYLE_GROUP, content),
                        )
                    } else {
                        record
                    }
                },
            )
        }
        assertFailsWith<IllegalArgumentException> {
            copyState(
                validationState,
                parkedRoutes = listOf(ParkedRoute(STYLE_ORDINAL, ParkedRouteBarrier.StyleChildren(STYLE_GROUP))),
            )
        }

        driver.event(
            BasemapStyleValidationCompleted(
                validation.actionId,
                BasemapStyleValidationOutcome.Valid(styleManifestRoutes()),
            ),
        )
        driver.completeStyleCommit()
        val parkedState = driver.state
        assertFailsWith<IllegalArgumentException> {
            copyState(
                parkedState,
                parkedRoutes = listOf(ParkedRoute(STICKER_A_ORDINAL, ParkedRouteBarrier.StyleOwners(STYLE_GROUP))),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            copyState(
                parkedState,
                parkedRoutes = listOf(ParkedRoute(STICKER_A_ORDINAL, ParkedRouteBarrier.StyleChildren(STYLE_GROUP))),
            )
        }

        val styleInput = mutableListOf(driver.style())
        val visibleInput = mutableListOf(OwnerResourceSet(ResourceOwnerId(OWNER_A), emptyList()))
        val copied = copyState(parkedState, styleCommitStates = styleInput, visibleResourcesByOwner = visibleInput)
        styleInput.clear()
        visibleInput.clear()
        assertEquals(listOf(driver.style()), copied.styleCommitStates)
        assertEquals(listOf(OwnerResourceSet(ResourceOwnerId(OWNER_A), emptyList())), copied.visibleResourcesByOwner)
        assertNotSame(copied.styleCommitStates, copied.styleCommitStates)
        assertNotSame(copied.visibleResourcesByOwner, copied.visibleResourcesByOwner)
        assertFailsWith<IllegalArgumentException> {
            copyState(
                parkedState,
                visibleResourcesByOwner = listOf(
                    OwnerResourceSet(ResourceOwnerId(OWNER_A), emptyList()),
                    OwnerResourceSet(ResourceOwnerId(OWNER_A), emptyList()),
                ),
            )
        }
    }

    @Test
    fun styleActionsAndCursorsRejectForeignClassesAndEmptyOwnerSets() {
        val driver = StyleDriver(styleDefinition(concurrency = 1, styleProvenance = ContentProvenance.RESIDENT))
        driver.driveToStyleValidation(ContentProvenance.RESIDENT)
        val content = driver.candidate(STYLE_ORDINAL)
        val actionId = ResourceActionId(90L)
        val owners = listOf(ResourceOwnerId(OWNER_A))

        assertFailsWith<IllegalArgumentException> {
            AwaitingStyleWrite(actionId, STYLE_ORDINAL, STYLE_GROUP, content)
        }
        assertFailsWith<IllegalArgumentException> {
            AwaitingStyleVisibilityInstall(actionId, STYLE_ORDINAL, STYLE_GROUP, content, emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            InstallBasemapStyleVisibility(actionId, STYLE_ORDINAL, STYLE_GROUP, content, emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            InstallBasemapStyleVisibility(actionId, STYLE_ORDINAL, STYLE_GROUP, content, owners + owners)
        }
        assertFailsWith<IllegalArgumentException> {
            WriteBasemapStyle(
                actionId,
                STYLE_ORDINAL,
                STYLE_GROUP,
                nonStyleRegistration().rawKey,
                content.stored,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ValidateBasemapStyle(actionId, STYLE_ORDINAL, STYLE_GROUP, nonStyleContent())
        }
        assertFailsWith<IllegalArgumentException> {
            AwaitingStyleValidation(actionId, STYLE_ORDINAL, STYLE_GROUP, nonStyleContent())
        }

        val ownerInput = owners.toMutableList()
        val install = InstallBasemapStyleVisibility(actionId, STYLE_ORDINAL, STYLE_GROUP, content, ownerInput)
        ownerInput.clear()
        assertEquals(owners, install.referencingOwnerIds)
        assertNotSame(install.referencingOwnerIds, install.referencingOwnerIds)
        assertEquals(
            install,
            InstallBasemapStyleVisibility(actionId, STYLE_ORDINAL, STYLE_GROUP, content, owners),
        )
        assertEquals(
            install.hashCode(),
            InstallBasemapStyleVisibility(actionId, STYLE_ORDINAL, STYLE_GROUP, content, owners).hashCode(),
        )
        val cursor = AwaitingStyleVisibilityInstall(actionId, STYLE_ORDINAL, STYLE_GROUP, content, owners)
        assertEquals(
            cursor,
            AwaitingStyleVisibilityInstall(actionId, STYLE_ORDINAL, STYLE_GROUP, content, owners),
        )
        assertEquals(
            cursor.hashCode(),
            AwaitingStyleVisibilityInstall(actionId, STYLE_ORDINAL, STYLE_GROUP, content, owners).hashCode(),
        )
        assertNotSame(cursor.referencingOwnerIds, cursor.referencingOwnerIds)
    }

    @Test
    fun basemapStyleOutcomesStayClosedSanitizedAndAdapterScoped() {
        assertFailsWith<IllegalArgumentException> {
            BasemapStyleValidationOutcome.Cancelled(
                CancellationSelection(CancellationCause.CALLER, CancellationId(1L)),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            BasemapStyleCompilationOutcome.Cancelled(
                CancellationSelection(CancellationCause.CANCEL_PREPARATIONS, CancellationId(2L)),
            )
        }
        val routeInput = styleManifestRoutes().toMutableList()
        val valid = BasemapStyleValidationOutcome.Valid(routeInput)
        routeInput.clear()
        assertEquals(styleManifestRoutes(), valid.routes)
        assertEquals(BasemapStyleValidationOutcome.Valid(styleManifestRoutes()), valid)
        assertEquals(BasemapStyleValidationOutcome.Valid(styleManifestRoutes()).hashCode(), valid.hashCode())
        assertFalse(valid.toString().contains(SPRITE_JSON_LOCATOR), "a manifest route can carry a credential")
        assertEquals(
            listOf("PARSE", "UNSUPPORTED_FEATURE"),
            StyleFailureKind.entries.map(StyleFailureKind::name),
        )
        assertEquals(
            listOf("NOT_REQUIRED", "WAITING", "REQUESTED", "SUCCEEDED", "FAILED"),
            StyleCompilationStatus.entries.map(StyleCompilationStatus::name),
        )
    }

    @Test
    fun styleVisibilityAndOwnerSetsCannotContradictTheirRouteOccurrences() {
        val driver = StyleDriver(styleDefinition(concurrency = 1))
        driver.driveToStyleValidation(ContentProvenance.TRANSPORT_200)
        val content = driver.candidate(STYLE_ORDINAL)
        val owners = listOf(ResourceOwnerId(OWNER_A), ResourceOwnerId(OWNER_B))
        val visibilityMessage = "style visibility requires every referencing owner's completed non-style work"

        assertEquals(
            visibilityMessage,
            assertFailsWith<IllegalArgumentException> {
                styleCommitState(
                    content,
                    StyleCompilationStatus.SUCCEEDED,
                    owners,
                    emptyList(),
                    writeAcknowledged = true,
                    visible = true,
                )
            }.message,
        )
        assertEquals(
            visibilityMessage,
            assertFailsWith<IllegalArgumentException> {
                styleCommitState(
                    content,
                    StyleCompilationStatus.SUCCEEDED,
                    owners,
                    listOf(ResourceOwnerId(OWNER_A)),
                    writeAcknowledged = true,
                    visible = true,
                )
            }.message,
        )

        val ownerMessage = "a style commit's referencing owners must be its route's bound occurrence owners"
        assertEquals(
            ownerMessage,
            assertFailsWith<IllegalArgumentException> {
                copyState(
                    driver.state,
                    styleCommitStates = listOf(
                        styleCommitState(
                            content,
                            StyleCompilationStatus.WAITING,
                            listOf(ResourceOwnerId(OWNER_A)),
                            emptyList(),
                        ),
                    ),
                )
            }.message,
        )
        assertEquals(
            ownerMessage,
            assertFailsWith<IllegalArgumentException> {
                copyState(
                    driver.state,
                    styleCommitStates = listOf(
                        styleCommitState(
                            content,
                            StyleCompilationStatus.WAITING,
                            owners + ResourceOwnerId(9L),
                            emptyList(),
                        ),
                    ),
                )
            }.message,
        )
        assertEquals(
            ownerMessage,
            assertFailsWith<IllegalArgumentException> {
                copyState(
                    driver.state,
                    styleCommitStates = listOf(
                        styleCommitState(
                            content,
                            StyleCompilationStatus.WAITING,
                            owners.reversed(),
                            emptyList(),
                        ),
                    ),
                )
            }.message,
        )
        assertEquals(owners, driver.style().referencingOwnerIds)
    }

    @Test
    fun aStyleOwnerBarrierWaitsForInstalledVisibilityNotMereRouteResolution() {
        val driver = StyleDriver(styleDefinition(concurrency = 1))
        driver.driveStyleManifest()
        driver.completeStyleCommit()
        assertEquals(listOf(ParkedRoute(STYLE_ORDINAL, ParkedRouteBarrier.StyleOwners(STYLE_GROUP))), driver.parked)

        driver.state = copyState(
            driver.state,
            routeRecords = driver.state.routeRecords.map { record ->
                if (record.ordinal == STICKER_B_ORDINAL) {
                    RouteRecord(
                        registration = record.registration,
                        joinedOccurrenceIds = record.joinedOccurrenceIds,
                        ordinal = record.ordinal,
                        cursor = null,
                        status = ResourceRouteStatus.RESOLVED,
                        lookup = record.lookup,
                    )
                } else {
                    record
                }
            },
        )
        assertFalse(driver.record(STICKER_B_ORDINAL).visibilityInstalled)

        driver.driveOrdinaryRoute(STICKER_A_ORDINAL)

        assertEquals(listOf(ParkedRoute(STYLE_ORDINAL, ParkedRouteBarrier.StyleOwners(STYLE_GROUP))), driver.parked)
        assertEquals(listOf(ResourceOwnerId(OWNER_A)), driver.style().ownersWithCompletedNonStyleWork)
        assertFalse(driver.style().visible)
        assertFalse(driver.record(STYLE_ORDINAL).visibilityInstalled)
        assertTrue(driver.emitted.filterIsInstance<WriteBasemapStyle>().isEmpty())
        assertTrue(driver.emitted.filterIsInstance<InstallBasemapStyleVisibility>().isEmpty())
        assertNull(driver.outcome)
        driver.assertNoRecoveryActions("uninstalled owner work")
    }

    @Test
    fun aStyleBelowTheStartCeilingStagesNoValidationAndNoManifest() {
        val advanceDriver = StyleDriver(nonDiscoveryStyleDefinition())
        advanceDriver.driveToPendingContent(STYLE_ORDINAL, ContentProvenance.TRANSPORT_200)
        val advanceCeilinged = copyState(advanceDriver.state, startCeilingOrdinal = OTHER_OWNER_ORDINAL)

        val closedAdvance = ResourceOperationStateMachine.transition(
            advanceCeilinged,
            AdvancePendingStyleCommit(STYLE_ORDINAL),
        )

        val closedAdvanceState = requireNotNull(closedAdvance.state)
        assertTrue(closedAdvance.actions.isEmpty())
        assertTrue(closedAdvanceState.styleCommitStates.isEmpty())
        assertTrue(closedAdvanceState.parkedRoutes.isEmpty())
        assertEquals(
            ResourceRouteStatus.RESOLVED,
            closedAdvanceState.routeRecords.single { it.ordinal == STYLE_ORDINAL }.status,
        )
        assertFalse(closedAdvanceState.routeRecords.single { it.ordinal == STYLE_ORDINAL }.visibilityInstalled)
        assertNull(closedAdvance.outcome)

        val validationDriver = StyleDriver(nonDiscoveryStyleDefinition())
        val validation = validationDriver.driveToStyleValidation(ContentProvenance.TRANSPORT_200)
        val validationCeilinged = copyState(
            validationDriver.state,
            startCeilingOrdinal = OTHER_OWNER_ORDINAL,
        )

        val closedValidation = ResourceOperationStateMachine.transition(
            validationCeilinged,
            BasemapStyleValidationCompleted(
                validation.actionId,
                BasemapStyleValidationOutcome.Valid(emptyList()),
            ),
        )

        val closedValidationState = requireNotNull(closedValidation.state)
        assertTrue(closedValidation.actions.isEmpty())
        assertTrue(closedValidationState.parkedRoutes.isEmpty())
        assertEquals(
            ResourceRouteStatus.RESOLVED,
            closedValidationState.routeRecords.single { it.ordinal == STYLE_ORDINAL }.status,
        )
        val style = closedValidationState.styleCommitStates.single()
        assertEquals(StyleCompilationStatus.WAITING, style.compilationStatus)
        assertFalse(style.visible)
        assertFalse(style.writeAcknowledged)
        assertNull(closedValidation.outcome)
    }
}

private const val OWNER_A: Long = 1L
private const val OWNER_B: Long = 2L
private const val SAMPLE_EPOCH_MILLIS: Long = 100L
private const val STYLE_ORDINAL: Long = 0L
private const val STICKER_A_ORDINAL: Long = 1L
private const val STICKER_B_ORDINAL: Long = 2L
private const val OTHER_OWNER_ORDINAL: Long = 1L
private const val STYLE_LOCATOR: String = "locator-a-BASEMAP_STYLE"
private const val SPRITE_JSON_LOCATOR: String = "https://sprites.example/atlas.json"
private const val SPRITE_IMAGE_LOCATOR: String = "https://sprites.example/atlas.png"
private val STYLE_GROUP: StyleGroupId = StyleGroupId(1L)
private val ADAPTER_CANCELLATION: CancellationSelection =
    CancellationSelection(CancellationCause.ADAPTER, CancellationId(7L))
private val STYLE_RESOURCE_KEY: ResourceKey =
    ResourceKey(ResourceKind.EXTERNAL, "a".repeat(64), ResourceClass.BASEMAP_STYLE)
/**
 * A failure of exactly the shape `BasemapEngineHost` produces for an engine exchange this invocation
 * never routed -- the one this test suite forwards through
 * [BasemapStyleCompilationOutcome.EngineFailed].
 */
private val ENGINE_ROUTE_FAILURE: FailureDescriptor = FailureDescriptor(
    code = RenGErrorCode.AMBIGUOUS_RESOURCE_ROUTE,
    stage = PipelineStage.RESOURCE_LOOKUP,
    // Field-for-field what BasemapEngineHost.unplannedEngineExchangeFailure builds: AMBIGUOUS_RESOURCE_ROUTE
    // allowlists DiagnosticField.RESOURCE alone, so naming a resource class here would not construct.
    diagnostic = failureContextDiagnostic(
        stage = PipelineStage.RESOURCE_LOOKUP,
        fieldName = DiagnosticField.RESOURCE,
    ),
)

private val INSTALL_FAILURE: FailureDescriptor = FailureDescriptor(
    code = RenGErrorCode.RESOURCE_UNAVAILABLE,
    stage = PipelineStage.RESOURCE_LOOKUP,
    diagnostic = failureContextDiagnostic(
        stage = PipelineStage.RESOURCE_LOOKUP,
        fieldName = DiagnosticField.RESOURCE,
        resourceClass = ResourceClass.BASEMAP_STYLE,
        resourceKey = STYLE_RESOURCE_KEY,
    ),
)

private enum class StyleCommitOrder { VALIDATE, COMPILE, WRITE, INSTALL }

private class StyleDriver(definition: ResourceOperationDefinition) {
    var state: ResourceOperationState.Running
    var actions: List<ResourceOperationAction>
    var outcome: ResourceOperationOutcome?
    val emitted: MutableList<ResourceOperationAction> = mutableListOf()
    private var maximumActive: Int = 0

    init {
        val transition = ResourceOperationStateMachine.start(definition)
        state = requireNotNull(transition.state)
        actions = transition.actions
        outcome = transition.outcome
        emitted += transition.actions
        maximumActive = state.activeRouteOrdinals.size
    }

    val parked: List<ParkedRoute>
        get() = state.parkedRoutes

    fun beginLookup(ordinal: Long) {
        applyTransition(ResourceOperationStateMachine.beginLookup(state, ordinal))
    }

    fun event(event: ResourceOperationEvent) {
        applyTransition(ResourceOperationStateMachine.transition(state, event))
    }

    fun advanceStyleCommit(ordinal: Long) {
        event(AdvancePendingStyleCommit(ordinal))
    }

    fun record(ordinal: Long): RouteRecord = state.routeRecords.single { it.ordinal == ordinal }

    fun candidate(ordinal: Long): ResolvedResourceContent =
        requireNotNull(record(ordinal).lookup?.selectedContent)

    fun style(groupId: StyleGroupId = STYLE_GROUP): StyleCommitState =
        state.styleCommitStates.single { it.groupId == groupId }

    fun styleCommitOrder(): List<StyleCommitOrder> = emitted.mapNotNull { action ->
        when (action) {
            is ValidateBasemapStyle -> StyleCommitOrder.VALIDATE
            is CompileBasemapStyle -> StyleCommitOrder.COMPILE
            is WriteBasemapStyle -> StyleCommitOrder.WRITE
            is InstallBasemapStyleVisibility -> StyleCommitOrder.INSTALL
            else -> null
        }
    }

    fun assertNoStyleCommitWork(label: String, allowValidation: Boolean = false) {
        if (!allowValidation) {
            assertTrue(emitted.filterIsInstance<ValidateBasemapStyle>().isEmpty(), "$label/validate")
        }
        assertTrue(emitted.filterIsInstance<CompileBasemapStyle>().isEmpty(), "$label/compile")
        assertTrue(emitted.filterIsInstance<WriteBasemapStyle>().isEmpty(), "$label/write")
        assertTrue(emitted.filterIsInstance<InstallBasemapStyleVisibility>().isEmpty(), "$label/install")
    }

    fun assertNoRecoveryActions(label: String) {
        listOf("Retry", "Repair", "Remove", "Fallback", "Rollback").forEach { forbidden ->
            assertTrue(
                emitted.none { it::class.simpleName.orEmpty().contains(forbidden) },
                "$label/$forbidden",
            )
        }
    }

    fun assertSingleSlot(label: String) {
        assertEquals(1, state.definition.maximumConcurrentRoutes, "$label/concurrency")
        assertTrue(maximumActive <= 1, "$label/maximum active $maximumActive")
    }

    fun assertStyleAssignedAndUnretired(label: String) {
        assertEquals(ResourceRouteStatus.RUNNING, record(STYLE_ORDINAL).status, "$label/status")
        assertEquals(STYLE_ORDINAL, record(STYLE_ORDINAL).ordinal, "$label/ordinal")
        assertEquals(0L, state.nextRetirementOrdinal, "$label/retirement")
        assertFalse(record(STYLE_ORDINAL).visibilityInstalled, "$label/visibility")
        assertFalse(style().visible, "$label/style visibility")
        assertNull(outcome, "$label/outcome")
        assertTrue(maximumActive <= 1, "$label/maximum active")
    }

    fun assertRetirementRefused(label: String) {
        assertEquals(
            "route completion requires no in-flight adapter action",
            assertFailsWith<IllegalArgumentException> {
                ResourceOperationStateMachine.transition(
                    state,
                    RouteCompleted(STYLE_ORDINAL, ResourceRouteOutcome.Success),
                )
            }.message,
            "$label/completion",
        )
        assertFailsWith<IllegalArgumentException> {
            ResourceOperationStateMachine.transition(
                state,
                RouteReadyForDiscovery(STYLE_ORDINAL, ResourceOccurrenceId(1L)),
            )
        }
    }

    private fun applyTransition(transition: ResourceOperationTransition) {
        state = requireNotNull(transition.state)
        actions = transition.actions
        outcome = transition.outcome
        emitted += transition.actions
        maximumActive = maxOf(maximumActive, state.activeRouteOrdinals.size)
    }
}

private fun StyleDriver.driveToPendingContent(ordinal: Long, provenance: ContentProvenance) {
    beginLookup(ordinal)
    val sample = assertIs<SampleClock>(actions.single())
    event(ClockSampled(sample.actionId, SAMPLE_EPOCH_MILLIS))
    when (provenance) {
        ContentProvenance.RESIDENT -> {
            val observe = assertIs<ObserveResident>(actions.single())
            event(ResidentObserved(observe.actionId, storedResource(freshUntil = SAMPLE_EPOCH_MILLIS + 1L)))
        }
        ContentProvenance.STORE -> {
            val observe = assertIs<ObserveResident>(actions.single())
            event(ResidentObserved(observe.actionId, null))
            val read = assertIs<ReadStore>(actions.single())
            event(StoreReadCompleted(read.actionId, SuppliedCallOutcome.Success(storedResource(freshUntil = 1L))))
        }
        ContentProvenance.TRANSPORT_200 -> {
            val call = assertIs<CallTransport>(actions.single())
            event(
                TransportCompleted(
                    call.actionId,
                    SuppliedCallOutcome.Success(
                        TransportResponse(
                            statusCode = 200,
                            body = CONTENT_BYTES,
                            metadata = TransportResponseMetadata(etag = "fetched-etag"),
                        ),
                    ),
                ),
            )
        }
        ContentProvenance.TRANSPORT_304_MERGED -> {
            val observe = assertIs<ObserveResident>(actions.single())
            event(
                ResidentObserved(
                    observe.actionId,
                    storedResource(etag = "baseline-etag", freshUntil = SAMPLE_EPOCH_MILLIS),
                ),
            )
            val read = assertIs<ReadStore>(actions.single())
            event(StoreReadCompleted(read.actionId, SuppliedCallOutcome.Success(null)))
            val call = assertIs<CallTransport>(actions.single())
            event(
                TransportCompleted(
                    call.actionId,
                    SuppliedCallOutcome.Success(
                        TransportResponse(
                            statusCode = 304,
                            body = byteArrayOf(),
                            metadata = TransportResponseMetadata(
                                freshUntilEpochMillis = SAMPLE_EPOCH_MILLIS + 500L,
                            ),
                        ),
                    ),
                ),
            )
        }
    }
    assertTrue(actions.isEmpty(), provenance.name)
    assertEquals(provenance, assertIs<PendingClassGates>(record(ordinal).cursor).content.provenance)
}

private fun StyleDriver.ownerRouteOrdinals(): List<Long> = state.routeRecords
    .filter { it.registration.route.resourceClass == ResourceClass.STICKER_IMAGE }
    .mapNotNull(RouteRecord::ordinal)
    .sorted()

private fun StyleDriver.driveOwnerRoutes() {
    ownerRouteOrdinals().forEach(::driveOrdinaryRoute)
}

private fun StyleDriver.driveOrdinaryRoute(ordinal: Long) {
    driveToPendingContent(ordinal, ContentProvenance.TRANSPORT_200)
    event(AdvancePendingClassGates(ordinal))
    val gate = assertIs<ValidateResourceClass>(actions.single())
    event(ResourceClassValidationCompleted(gate.actionId, SuppliedValidationOutcome.Valid))
    val write = assertIs<WriteStore>(actions.single())
    event(StoreWriteCompleted(write.actionId, SuppliedCallOutcome.Success(Unit)))
    val install = assertIs<InstallVisibility>(actions.single())
    event(VisibilityInstallCompleted(install.actionId, SuppliedInstallOutcome.Succeeded))
}

private fun StyleDriver.driveToStyleValidation(provenance: ContentProvenance): ValidateBasemapStyle {
    driveToPendingContent(STYLE_ORDINAL, provenance)
    advanceStyleCommit(STYLE_ORDINAL)
    return assertIs<ValidateBasemapStyle>(actions.single())
}

private fun StyleDriver.driveStyleManifest(routes: List<ResourceRouteKey> = styleManifestRoutes()) {
    val validation = driveToStyleValidation(ContentProvenance.TRANSPORT_200)
    event(BasemapStyleValidationCompleted(validation.actionId, BasemapStyleValidationOutcome.Valid(routes)))
}

private fun StyleDriver.driveToStyleCompilation(provenance: ContentProvenance): CompileBasemapStyle {
    val validation = driveToStyleValidation(provenance)
    event(
        BasemapStyleValidationCompleted(
            validation.actionId,
            BasemapStyleValidationOutcome.Valid(styleManifestRoutes()),
        ),
    )
    return assertIs<CompileBasemapStyle>(actions.filterIsInstance<CompileBasemapStyle>().single())
}

private fun StyleDriver.completeStyleCommit() {
    val compilation = actions.filterIsInstance<CompileBasemapStyle>().single()
    event(BasemapStyleCompilationCompleted(compilation.actionId, BasemapStyleCompilationOutcome.Succeeded))
}

private fun StyleDriver.driveToStyleOwnerBarrier(provenance: ContentProvenance): ResolvedResourceContent {
    val validation = driveToStyleValidation(provenance)
    event(
        BasemapStyleValidationCompleted(
            validation.actionId,
            BasemapStyleValidationOutcome.Valid(styleManifestRoutes()),
        ),
    )
    if (provenance != ContentProvenance.RESIDENT) {
        completeStyleCommit()
    }
    return candidate(STYLE_ORDINAL)
}

private fun StyleDriver.driveToStyleWrite(): WriteBasemapStyle {
    driveToStyleOwnerBarrier(ContentProvenance.TRANSPORT_200)
    driveOwnerRoutes()
    return assertIs<WriteBasemapStyle>(actions.single())
}

private fun StyleDriver.driveToStyleInstall(): InstallBasemapStyleVisibility {
    val write = driveToStyleWrite()
    event(BasemapStyleWriteCompleted(write.actionId, STYLE_GROUP, SuppliedCallOutcome.Success(Unit)))
    return assertIs<InstallBasemapStyleVisibility>(actions.single())
}

private fun StyleDriver.finishStyleWriteAndInstall() {
    val write = assertIs<WriteBasemapStyle>(actions.single())
    event(BasemapStyleWriteCompleted(write.actionId, STYLE_GROUP, SuppliedCallOutcome.Success(Unit)))
    val install = assertIs<InstallBasemapStyleVisibility>(actions.single())
    event(BasemapStyleVisibilityInstallCompleted(install.actionId, STYLE_GROUP, SuppliedInstallOutcome.Succeeded))
}

private fun nonDiscoveryStyleDefinition(): ResourceOperationDefinition = definitionOf(
    concurrency = 2,
    occurrences = listOf(
        styleOccurrence(1L, OWNER_A, ContentProvenance.TRANSPORT_200, discoveryRequired = false),
    ) + ordinaryOccurrences(3L, OWNER_A, 'b'),
)

private fun styleDefinition(
    concurrency: Int,
    styleProvenance: ContentProvenance = ContentProvenance.TRANSPORT_200,
): ResourceOperationDefinition = definitionOf(
    concurrency = concurrency,
    occurrences = listOf(
        styleOccurrence(1L, OWNER_A, styleProvenance, discoveryRequired = true),
        styleOccurrence(2L, OWNER_B, styleProvenance, discoveryRequired = false),
    ) + ordinaryOccurrences(3L, OWNER_A, 'b') + ordinaryOccurrences(4L, OWNER_B, 'c'),
)

/**
 * The manifest a validated style announces: the routes its compilation makes the engine ask for. They
 * are engine-keyed classes RenG never fetches itself, so they never become occurrences or routes of this
 * operation -- the whole point of announcing routes rather than children.
 */
private fun styleManifestRoutes(): List<ResourceRouteKey> = listOf(
    manifestRoute(SPRITE_JSON_LOCATOR, ResourceClass.BASEMAP_SPRITE_JSON),
    manifestRoute(SPRITE_IMAGE_LOCATOR, ResourceClass.BASEMAP_SPRITE_IMAGE),
)

private fun manifestRoute(locator: String, resourceClass: ResourceClass): ResourceRouteKey = ResourceRouteKey(
    accessMode = ResourceAccessMode.NORMAL,
    locator = ResourceLocator(locator),
    resourceClass = resourceClass,
    maximumResponseBytes = 1024L,
)

private fun styleOccurrence(
    id: Long,
    ownerId: Long,
    provenance: ContentProvenance,
    discoveryRequired: Boolean,
): ResourceOccurrence = ResourceOccurrence(
    id = ResourceOccurrenceId(id),
    ownerId = ResourceOwnerId(ownerId),
    registration = registration('a', ResourceClass.BASEMAP_STYLE, accessModeFor(provenance)),
    discoveryRequired = discoveryRequired,
    commitBinding = ResourceCommitBinding.BasemapStyle(STYLE_GROUP),
)

private fun ordinaryOccurrences(
    id: Long,
    ownerId: Long,
    marker: Char,
): List<ResourceOccurrence> = listOf(
    ResourceOccurrence(
        id = ResourceOccurrenceId(id),
        ownerId = ResourceOwnerId(ownerId),
        registration = registration(marker, ResourceClass.STICKER_IMAGE, ResourceAccessMode.RELOAD),
        discoveryRequired = false,
        commitBinding = ResourceCommitBinding.Single,
    ),
)

private fun definitionOf(
    concurrency: Int,
    occurrences: List<ResourceOccurrence>,
): ResourceOperationDefinition = ResourceOperationDefinition(
    maximumConcurrentRoutes = concurrency,
    staticOccurrences = occurrences,
    resourceIdentities = occurrences
        .map { CanonicalIdentityRecord(it.registration.resourceKey, it.registration.canonicalBytes) }
        .distinctBy { it.resourceKey.stableId },
)

private fun accessModeFor(provenance: ContentProvenance): ResourceAccessMode = when (provenance) {
    ContentProvenance.RESIDENT -> ResourceAccessMode.NORMAL
    ContentProvenance.STORE -> ResourceAccessMode.CACHE_ONLY
    ContentProvenance.TRANSPORT_200 -> ResourceAccessMode.RELOAD
    ContentProvenance.TRANSPORT_304_MERGED -> ResourceAccessMode.NORMAL
}

private fun registration(
    marker: Char,
    resourceClass: ResourceClass,
    mode: ResourceAccessMode,
): ResourceRouteRegistration = ResourceRouteRegistration(
    route = ResourceRouteKey(
        accessMode = mode,
        locator = ResourceLocator("locator-$marker-${resourceClass.name}"),
        resourceClass = resourceClass,
        maximumResponseBytes = 4096L,
    ),
    resourceKey = ResourceKey(ResourceKind.EXTERNAL, marker.toString().repeat(64), resourceClass),
    rawKey = RawResourceKey(marker.toString().repeat(63) + "f", resourceClass),
    privateRentileKey = RentilePrivateKey("private-$marker-${resourceClass.name}"),
    canonicalBytes = CanonicalBytes("canonical-$marker-${resourceClass.name}".encodeToByteArray()),
)

private fun storedResource(
    etag: String? = null,
    freshUntil: Long? = null,
): StoredRawResource = StoredRawResource(
    bytes = CONTENT_BYTES,
    contentDigest = CONTENT_DIGEST,
    metadata = StoredRawResourceMetadata(
        contentType = null,
        etag = etag,
        lastModified = null,
        freshUntilEpochMillis = freshUntil,
        storedAtEpochMillis = 1L,
    ),
)

private fun nonStyleRegistration(): ResourceRouteRegistration =
    registration('f', ResourceClass.STICKER_IMAGE, ResourceAccessMode.RELOAD)

private fun nonStyleContent(): ResolvedResourceContent = ResolvedResourceContent(
    route = nonStyleRegistration().route,
    resourceKey = nonStyleRegistration().resourceKey,
    stored = storedResource(freshUntil = 1L),
    provenance = ContentProvenance.TRANSPORT_200,
)

private fun styleCommitState(
    content: ResolvedResourceContent,
    compilationStatus: StyleCompilationStatus,
    referencingOwnerIds: List<ResourceOwnerId>,
    ownersWithCompletedNonStyleWork: List<ResourceOwnerId>,
    writeAcknowledged: Boolean = false,
    visible: Boolean = false,
): StyleCommitState = StyleCommitState(
    groupId = STYLE_GROUP,
    ordinal = STYLE_ORDINAL,
    stagedContent = content,
    compilationStatus = compilationStatus,
    referencingOwnerIds = referencingOwnerIds,
    ownersWithCompletedNonStyleWork = ownersWithCompletedNonStyleWork,
    writeAcknowledged = writeAcknowledged,
    visible = visible,
)

private fun routeRecord(
    record: RouteRecord,
    cursor: ResourceRouteCursor,
): RouteRecord = RouteRecord(
    registration = record.registration,
    joinedOccurrenceIds = record.joinedOccurrenceIds,
    ordinal = record.ordinal,
    cursor = cursor,
    status = record.status,
    lookup = record.lookup,
    visibilityInstalled = record.visibilityInstalled,
)

private fun copyState(
    state: ResourceOperationState.Running,
    routeRecords: List<RouteRecord> = state.routeRecords,
    styleCommitStates: List<StyleCommitState> = state.styleCommitStates,
    parkedRoutes: List<ParkedRoute> = state.parkedRoutes,
    visibleResourcesByOwner: List<OwnerResourceSet> = state.visibleResourcesByOwner,
    startCeilingOrdinal: Long? = state.startCeilingOrdinal,
): ResourceOperationState.Running = ResourceOperationState.Running(
    definition = state.definition,
    occurrences = state.occurrences,
    routeRecords = routeRecords,
    privateRentileKeyClaims = state.privateRentileKeyClaims,
    identityRecords = state.identityRecords,
    transportLatches = state.transportLatches,
    nextActionId = state.nextActionId,
    traversal = state.traversal,
    nextRouteOrdinal = state.nextRouteOrdinal,
    activeRouteOrdinals = state.activeRouteOrdinals,
    nextRetirementOrdinal = state.nextRetirementOrdinal,
    bufferedRouteOutcomes = state.bufferedRouteOutcomes,
    startCeilingOrdinal = startCeilingOrdinal,
    terminalSelection = state.terminalSelection,
    spriteCommitStates = state.spriteCommitStates,
    parkedRoutes = parkedRoutes,
    styleCommitStates = styleCommitStates,
    visibleResourcesByOwner = visibleResourcesByOwner,
)

private fun assertStyleFailure(
    outcome: ResourceOperationOutcome?,
    provenance: ContentProvenance,
    kind: StyleFailureKind,
    resourceKey: ResourceKey,
    label: String,
) {
    if (provenance == ContentProvenance.STORE) {
        assertResourceFailure(
            outcome = outcome,
            code = RenGErrorCode.STORE_INTEGRITY_FAILED,
            stage = PipelineStage.STORE_VALIDATION,
            expectedField = DiagnosticField.RESOURCE.wireName,
            resourceClass = ResourceClass.BASEMAP_STYLE,
            resourceKey = resourceKey,
            label = label,
        )
        return
    }
    val code = when (kind) {
        StyleFailureKind.PARSE -> RenGErrorCode.RESOURCE_PARSE_FAILED
        StyleFailureKind.UNSUPPORTED_FEATURE -> RenGErrorCode.UNSUPPORTED_RESOURCE_FEATURE
    }
    assertResourceFailure(
        outcome = outcome,
        code = code,
        stage = PipelineStage.RESOURCE_PARSING,
        expectedField = DiagnosticField.RESOURCE.wireName,
        resourceClass = ResourceClass.BASEMAP_STYLE,
        resourceKey = resourceKey,
        label = label,
    )
}

private fun assertResourceFailure(
    outcome: ResourceOperationOutcome?,
    code: RenGErrorCode,
    stage: PipelineStage,
    expectedField: String?,
    resourceClass: ResourceClass,
    resourceKey: ResourceKey,
    label: String,
) {
    val failure = assertIs<ResourceOperationOutcome.Failure>(outcome, label).failure
    assertEquals(code, failure.code, label)
    assertEquals(stage, failure.stage, label)
    val diagnostic = requireNotNull(failure.diagnostic) { label }
    assertEquals(expectedField, diagnostic.fieldName, label)
    assertEquals(resourceClass, diagnostic.resourceClass, label)
    assertEquals(resourceKey, diagnostic.resourceKey, label)
    assertNull(diagnostic.statusCode, label)
    assertNull(diagnostic.limit, label)
    assertNull(diagnostic.actual, label)
}

private val CONTENT_BYTES: ByteArray = "abc".encodeToByteArray()
private const val CONTENT_DIGEST: String =
    "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
