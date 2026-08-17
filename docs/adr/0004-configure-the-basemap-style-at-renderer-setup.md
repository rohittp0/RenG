# Configure the basemap style at renderer setup

The basemap style is named once, in the renderer's setup configuration, beside the **Transport** and
**Store**. One renderer instance draws one basemap style for its whole lifetime, and a **Frame Plan**
never names a style. Changing style means building another renderer. A renderer configured with no
style draws no ground at all, which is how a consumer gets frames it can composite over something
else.

This bounds what "a **Frame Plan** is a complete definition of on-screen state" claims. The claim is
about frame content — camera, stickers, models, geometries, and their placements — not about the
renderer's configuration. Two renderers configured with different styles will draw different pixels
from the identical **Frame Plan**, and that is intended: a plan is complete with respect to the
renderer that draws it, and the harness records the style alongside the plan sequence rather than
inside each plan.

Putting the style in each **Frame Plan** was the alternative, and it would have made a plan
self-describing and allowed a day-to-night change mid-sequence at no per-frame cost, since a prepared
style is content-keyed and compiled once. It was rejected in favour of the smaller plan and the
simpler setup path: a style change is a rare, coarse event, not per-frame state, and treating it as
per-frame state invites consumers to vary it accidentally. The consequence to accept is that a
mid-sequence style change costs a new renderer and a cold GPU cache, and that whether a single frame
can suppress an otherwise-configured basemap is deliberately left undecided until the basemap
sub-project needs an answer. That later answer is `FramePlan.drawBasemap`, defaulting to true: it may suppress
the configured basemap for one frame without selecting or changing the renderer's style.
