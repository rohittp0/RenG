# Make renderer close an owner-wide terminal transition

A renderer, its Prepared Frames, its Render Targets, and every resource generation form one ownership domain.
Renderer close therefore transitions that whole domain to `CLOSED`: it clears frame history, invalidates
frames and targets for drawing, releases CPU state, and deletes every live GL object under the exact-context
rule in ADR 0015. A Prepared Frame cannot keep a renderer partially alive after close. Its own later
`close()` remains an idempotent context-free release marker, but it can no longer authorize drawing.

Three states are sufficient. `LIVE` has one adopted exact context. `AWAITING_CONTEXT_ADOPTION` follows the
consumer's GPU-object-loss declaration, retains CPU state, owns no live GL handle, and permits GL-free
preparation, query, free, history, and cancellation work. Explicit adoption alone returns it to `LIVE`.
`CLOSED` is terminal. Repeated renderer close, cancellation with no active preparation, GPU-loss notification,
resource query/free, and Prepared Frame close are harmless after closure; query and free return empty
point-in-time results. Every operation that would create, mutate, adopt, mint, prepare, or draw instead fails
`RENDERER_CLOSED`.

Preparation remains the one serialized history transaction, but it need not block unrelated GL-free or GL
work. Query and Prepared Frame close linearize through renderer state while preparation or drawing proceeds;
an in-flight draw takes a temporary lease before a concurrent frame close can release the caller's lease.
Drawing may overlap preparation. The consumer still serializes GL-bound renderer calls, and GPU-object-loss
notification serializes with an in-flight GL call before forgetting handles. Free and query report the state
at their own linearization point rather than attempting to predict a concurrent last-lease release.

Validation order is part of the observable contract. Active-preparation interference wins first for history
clear, free, and renderer close, before any context inspection. Terminal renderer state follows, then
context-adoption state, Prepared Frame ownership and closed state, Render Target ownership and context
generation, exact current-context validation, deferred deletion, and operation-specific work. This makes a
closed renderer report closure regardless of stale arguments, while a renderer awaiting adoption reports that
state before an old target can report stale generation.

Allowing Prepared Frames to outlive renderer ownership was rejected because close could no longer promise to
release everything. Making every post-close call fail was also rejected because it would break idempotent
cleanup in ordinary `finally` blocks and make safe reporting needlessly fragile. The selected split keeps
cleanup/query operations harmless while making every operation capable of producing or using renderer state
fail explicitly.
