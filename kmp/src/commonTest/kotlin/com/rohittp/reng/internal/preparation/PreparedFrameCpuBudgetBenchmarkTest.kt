package com.rohittp.reng.internal.preparation

import com.rohittp.reng.ResourceClass
import com.rohittp.reng.ResourceKey
import com.rohittp.reng.ResourceKind
import com.rohittp.reng.StoredRawResource
import com.rohittp.reng.StoredRawResourceMetadata
import com.rohittp.reng.internal.bench.RengBench
import com.rohittp.reng.internal.cache.ResidentGeneration
import com.rohittp.reng.internal.cache.ResidentGenerationId
import kotlin.test.Test
import kotlin.test.assertIs

/**
 * B3: admitting one frame's resident generations to [PreparedFrameCpuBudget] one at a time, the way a
 * preparation charges them as it reaches them, then releasing the reservation.
 *
 * Every addition looks its generation up among the reservation's own claims, so the cost grows with the
 * claims already made. Two shapes:
 * - `labels64x3`: 64 labels of 3 textures each -- each label's textures admitted together with
 *   [PreparedFrameCpuBudget.Reservation.tryAddGenerations], then each re-projected once more with
 *   [PreparedFrameCpuBudget.Reservation.tryProjectGeneration], as a decode reserves its expansion;
 * - `generations512`: 512 generations admitted singly with
 *   [PreparedFrameCpuBudget.Reservation.tryAddGeneration], then each re-projected.
 *
 * [OTHER_FRAMES] older frames hold reservations on the same generations throughout, untimed, as frames in
 * flight do; their claims sit in the same global accounts this frame's join.
 */
class PreparedFrameCpuBudgetBenchmarkTest {
    @Test
    fun admittingAndReleasingAFramesGenerations() {
        measureLabels()
        measureSingles()
    }

    private fun measureLabels() {
        val generations = generations(64 * 3)
        val labels = generations.chunked(3)
        measureAdmission("b3.cpu_budget.admit_release.labels64x3", generations) { reservation ->
            var accepted = 0
            for (label in labels) {
                if (reservation.tryAddGenerations(label) == PreparedFrameCpuBudget.Update.Accepted) accepted += 1
                for (generation in label) {
                    if (reservation.tryProjectGeneration(generation, generation.byteSize * 2) ==
                        PreparedFrameCpuBudget.Update.Accepted
                    ) {
                        accepted += 1
                    }
                }
            }
            accepted
        }
    }

    private fun measureSingles() {
        val generations = generations(512)
        measureAdmission("b3.cpu_budget.admit_release.generations512", generations) { reservation ->
            var accepted = 0
            for (generation in generations) {
                if (reservation.tryAddGeneration(generation) == PreparedFrameCpuBudget.Update.Accepted) accepted += 1
            }
            for (generation in generations) {
                if (reservation.tryProjectGeneration(generation, generation.byteSize * 2) ==
                    PreparedFrameCpuBudget.Update.Accepted
                ) {
                    accepted += 1
                }
            }
            accepted
        }
    }

    private fun measureAdmission(
        name: String,
        generations: List<ResidentGeneration>,
        admit: (PreparedFrameCpuBudget.Reservation) -> Int,
    ) {
        val budget = PreparedFrameCpuBudget(maximumBytes = Long.MAX_VALUE / 2)
        val others = List(OTHER_FRAMES) {
            assertIs<PreparedFrameCpuBudget.Admission.Admitted>(budget.tryReserve(generations, 1024L)).reservation
        }
        RengBench.info(name, "generations=${generations.size} other_frames=$OTHER_FRAMES")
        RengBench.measure(name, warmupIterations = 20, minimumIterations = 50) {
            timed {
                val admission = budget.tryReserve(emptyList(), frameOwnedBytes = 4096L)
                val reservation = (admission as PreparedFrameCpuBudget.Admission.Admitted).reservation
                fold(admit(reservation))
                fold(budget.outstandingBytes())
                reservation.release()
            }
            fold(budget.outstandingBytes())
        }
        others.forEach { it.release() }
    }

    private fun generations(count: Int): List<ResidentGeneration> = List(count) { index ->
        ResidentGeneration(
            id = ResidentGenerationId(index + 1L),
            key = ResourceKey(ResourceKind.EXTERNAL, index.toString(16).padStart(64, 'a'), ResourceClass.MODEL_TEXTURE),
            stored = StoredRawResource(
                bytes = ByteArray(64) { index.toByte() },
                contentDigest = index.toString(16).padStart(64, 'b'),
                metadata = StoredRawResourceMetadata(storedAtEpochMillis = 1L),
            ),
            decoded = null,
        )
    }

    private companion object {
        const val OTHER_FRAMES = 2
    }
}
