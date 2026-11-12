# A transient sticker is dropped when its last frame closes

This decision amends ADR 0045 and ADR 0047 for one kind of generation and changes nothing else.

A video frame drawn as a sticker (ADR 0092) is a new locator used once. Under ADR 0047 its generation
stays in the resident cache after its frame closes, an LRU candidate like any other, and its texture stays
in the content texture pool until that pool's budget evicts it. At 30 frames a second of about 8 MiB each,
those frames are the most recently touched content in both caches and push out the map's tiles and every
other sticker within seconds, only to be evicted themselves later. RenG cannot tell this from content worth
keeping, so the consumer says so: `Sticker.transient`, appended last to the data class and its JSON
surrogate, `false` by default.

## The CPU generation

At `prepare()`, once acquisition has leased every sticker image, each sticker image key is marked on the
generation the frame leased: transient if every sticker in this frame using that locator is transient,
otherwise not. A generation remembers whether it was marked either way since it last had no lease. When
`ResidentCache.releaseLease` lets go of its last lease -- ADR 0045's close of the last frame holding it --
a generation marked only transient is dropped at once, current or retired: its bytes leave the account,
its key leaves the cache if nothing else remains, and it is **never marked freed**, for ADR 0047's reason
(the consumer did not call `freeResources`, so nothing blames them on reload). A generation with any
non-transient mark, or none at all, keeps today's behaviour exactly; the marks are forgotten once the
generation is unleased, so each period of use is judged on its own.

That is the safe choice when frames disagree. One open frame using a locator non-transiently keeps the
generation an LRU candidate, as it would have been without this ADR; the worst a mixed use costs is the
old behaviour. Two frames using it transiently keep it until both close, so a frame never loses what it
leased and the same locator twice in one frame is one lease, one generation and one texture.

Drops are counted in `ResourceResidency.transientDroppedCount` and `transientDroppedBytes`, cumulative like
ADR 0048's eviction counters and kept apart from them: the budget did not ask for these, and folding them
in would make a video export read as a cache under pressure.

## The GPU texture

GL deletes happen on the context thread (ADR 0015), and a frame may close on any thread, so the drop only
queues its `(key, generation)` in the cache. The next draw drains that queue before it touches anything and
retires exactly those generations' allocations in `GlObjectRegistry`: an unleased one is deleted there and
then, a leased one on the release of its last draw lease. Unlike a free's retirement this sets no
high-water mark on the key, so an older generation of the same locator that another open frame still draws
is untouched. A texture is never deleted at the end of the draw that used it -- its generation is still
leased by the open frame, so nothing is queued yet -- and a second draw of a still-open frame reuses it.
Renderer close deletes everything as before and clears the queue; after a context loss the queued
identities name nothing and retire nothing.

## Consequences

- Public API: `Sticker.transient` (component 6) and two `ResourceResidency` fields. The JSON omits
  `transient` at its default, so every existing document reads unchanged.
- A transient sticker is loaded again, from the consumer's `Store` or `Transport`, if a later frame names its
  locator after the last frame using it closed. That is the point: the consumer asked RenG not to keep it.
- A video frame's texture lives from its upload to the start of the next draw after its frame closes,
  which deletes it before uploading anything. With one frame open at a time, at most one video frame is
  resident on each side.
