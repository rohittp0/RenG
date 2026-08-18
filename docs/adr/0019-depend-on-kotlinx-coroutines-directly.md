# Depend on kotlinx-coroutines directly

RenG declares `org.jetbrains.kotlinx:kotlinx-coroutines-core` as its own dependency, with the version
recorded in RenG's version catalogue, and `tools/check_repository_policy.py` is amended to permit exactly
that one coordinate while continuing to forbid every other library it lists. This is the second
first-party dependency RenG has ever taken and it is meant to be the last one Cycle C takes.

The public contract already assumes it. `Renderer.prepare` and `prepareBatch` are `suspend`; the
**Preparation Budget** promises "at most `maximumConcurrentResourceOperations` independent resource
operations", default eight; and `CONTEXT.md` describes cancellation as an unwrapped coroutine
cancellation that "Kotlin stack recovery may copy while retaining the original as its immediate cause" —
stack-trace recovery is a kotlinx-coroutines feature, not a language one. The Kotlin standard library
gives sequential composition of `suspend` functions and nothing else: no scope, no structured
concurrency, no `Mutex`, and no way to observe the caller's `Job`. Without the library RenG cannot
honour a promise Cycle B already published, and cannot safely share the resident cache between a draw, a
resource query, a free, and a prepared-frame close that the contract explicitly permits to overlap.

Relying on the transitive copy was rejected for the reason the PNG research gives for Skiko. Rentile
declares coroutines as `implementation`, so on Android it lands in `androidRuntimeElements` only and is
not compile-visible to a `commonMain` that must compile for all six targets, while on the native targets
klib linkage puts it in `ApiElements`. Compiling against another library's transitive dependency also
makes a downstream patch release able to move RenG's floor without RenG noticing. If RenG uses it, RenG
owns its version.

Two alternatives were considered and rejected. A stdlib-only sequential design, where RenG launches
nothing and all concurrency is Rentile's, would make a plan with two hundred stickers two hundred
sequential round trips and would walk back the meaning of a published configuration field. Building a
minimal scope, job and semaphore on `kotlin.coroutines.startCoroutine` keeps the promise with no new
coordinate, but such a runtime cannot see the caller's `Job`, so caller cancellation would be observable
only when an adapter happened to throw — and it is a novel correctness surface of exactly the kind the
PNG research declined when it refused to hand-write inflate.

The costs are real and worth stating. The forbidden-dependency rule exists because dependencies are
permanent, and one exception invites the next; the amendment is therefore to a single named coordinate
rather than to the rule's principle. Coroutines is Apache-2.0, so the license check is unaffected, and it
adds no new coordinate to any consumer's resolution because Rentile already places it on all six targets.
