# Separate frame preparation from drawing

Acquiring what a frame needs and drawing that frame are two separate public operations. Preparation
is suspending, performs every network read, decode, and parse, and issues no GL call whatsoever;
drawing is non-suspending, issues only GL calls, and must run on the thread holding the current
**Render Context**. A **Prepared Frame** is therefore network-free by construction, and CLAUDE.md's
description of a render loop that "takes only a `FramePlan`" is amended: the render loop takes a
**Prepared Frame**.

Preparation is free-threaded and may be called at any time, for any **Frame Plan**, concurrently and
out of order. A consumer is expected to prepare a whole sequence of frames ahead of the draw loop so
that drawing never waits on acquisition, which is the specific reason this split exists rather than a
single suspending `draw`. Consequently many **Prepared Frame**s are alive at once; identical resources
are shared between them rather than duplicated, each resource stays resident while any live
**Prepared Frame** needs it, and drawing does not consume a **Prepared Frame** — the same one may be
drawn again, into a different **Render Target**, or never drawn at all.

Preparation is cancellable through one operation that cancels every preparation currently in flight.
There are no per-preparation cancellation handles: a consumer that abandons a sequence — the timeline
was scrubbed, the plan was replaced — wants all of the queued work gone, and structured concurrency
already covers cancelling one specific call by cancelling the coroutine that made it. Every affected
preparation fails with `CancellationException` propagated unchanged, never a typed RenG error. The
call cancels only what is in flight when it is made and is not a latch, so a preparation started
afterwards proceeds normally. Resources already acquired when cancellation lands stay cached, because
they are content-keyed and valid regardless of which preparation asked for them, and discarding them
would make cancelling a sequence more expensive than completing it.

A single suspending `draw` was rejected on two counts. An OpenGL context is bound to one thread, so a
suspension point inside a frame can resume the continuation on another thread and leave every
subsequent GL call undefined; the split removes that hazard by construction instead of documenting it
as the caller's problem. And a draw call that can await a tile fetch cannot hold a frame budget, which
defeats the pipelining a consumer needs. Drawing only what happens to be resident and reporting the
rest was also rejected: the macOS harness encodes a deterministic MP4 and must be able to assert that
a frame is complete, not discover afterwards that it silently rendered without its models.
