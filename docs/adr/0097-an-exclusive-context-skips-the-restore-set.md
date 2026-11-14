# An exclusive context skips the Restore Set

RenG normally shares the consumer's GL context. ADR 0006 therefore requires every operation to save
and restore all state RenG touches, and ADR 0023 requires it to drain `glGetError` on entry and after
its work. In WebGL those queries are synchronous trips to the GPU process. An OffscreenCanvas render
worker can instead give RenG a context no other code ever uses, making both protections pure overhead.

## Decision

`RendererConfiguration.contextOwnership` defaults to `SHARED`, whose calls and guarantees are
unchanged. `EXCLUSIVE` means only RenG draws into the context: RenG establishes every state value it
relies on but does not capture or restore prior state, and calls `glGetError` only around actionable
object allocation and framebuffer checks. `EXCLUSIVE_CHECKED` skips state restoration but retains the
full error checks for driver diagnosis.

This amends ADR 0006 and ADR 0023 only for consumers that explicitly select an exclusive mode. The web
demo selects `EXCLUSIVE` for `?worker=on`; `?context=shared|exclusive` overrides that choice.

## Consequences

The default remains byte-identical and safe for a shared context. An exclusive consumer must not issue
its own GL calls on that context for the renderer's lifetime. RenG may leave its last bindings and state
behind in exclusive modes, and unchecked draw-time driver errors can surface only through their visible
effect; context adoption, allocation, framebuffer completeness, and shader/program status remain
checked.
