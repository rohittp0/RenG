# Draw the map regime before compositing the screen regime

`CONTEXT.md` already fixes ordering *within* each draw regime — greater z-index composites on top, and
ties break by stable plan order (stickers before models, later entries on top within each list). What
stayed open until now was the rule *between* the two regimes: when a screen-anchored sticker and a
map-anchored model both want the same region of the frame, which one wins. The answer is that the map
regime draws first, depth-tested against the whole 3D scene, and the screen regime then composites on top
as a single ordered stack, with no depth test against the map regime at all. This ADR is narrow because
it settles exactly the one rule the existing within-regime ordering left unstated.

The rejected alternative deserves more space than the chosen one, because it looked reasonable and fails
in a way that never announces itself. Splitting the screen regime by the sign of its z-index — negative
sinks behind the map, non-negative composites in front — would overload the sign of one number with two
unrelated meanings: which regime a thing belongs to, and where it sits within that regime. A consumer
placing two screen-anchored things at `z = -5` and `z = -3` would still see `-3` correctly composite above
`-5`, because the within-regime rule is sign-agnostic, while both silently jumped behind the map the
moment either value went negative. Nothing in the API or the type system flags that jump; a caller
adjusting a z-index purely for relative ordering against other screen-anchored things discovers the regime
change only by looking at the rendered frame, if at all. Anything that genuinely needs to sit behind the
map already has an honest way to say so: map anchoring with an appropriate altitude, depth-tested against
the scene like everything else in that regime.

This rule is fully testable in the cycle that introduces it even though the basemap itself has not shipped
yet, because the map regime already contains map-anchored things depth-testing one another; the basemap is
one more depth-tested surface joining that same regime later, and drawing it changes nothing about this
ordering.

**The same interface this ordering rule shares a cycle with carries a silent-rename hazard worth recording
here rather than leaving implicit.** ADR 0008 binds a documented attribute or uniform name to a compiled
shader program only when that program declares the name itself — RenG never fails a shader for omitting a
documented binding, which is correct for a shader pair that simply has no use for, say, `uFrameIndex`. But
it also means that if a documented name is ever renamed in a later revision of the shader interface, every
consumer shader still declaring the old name keeps compiling and keeps drawing — without that value ever
reaching it. There is no error and no warning; the binding silently stops happening and the visible result
is simply wrong. Of every class of breaking change available to this interface, a rename is the one that
does not announce itself, which is why the documented attribute and uniform names deserve care beyond the
ordinary weight of an internal-release ABI change.
