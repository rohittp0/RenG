# The boundary copy is the only copy worth making

`selectContent` wraps the resource it was handed in `copyStored(stored)` before putting it in a
`ResolvedResourceContent`. That is a full array copy of every resource, on every route, on every
frame — and it copies something nothing outside this machine can reach.

Every caller already passes a private copy. Checked, not assumed, at all four call sites:

- the two resident paths pass `resident`, which is `event.resource?.let(::copyStored)` — copied at the
  moment the observation crosses into this machine;
- the two store paths pass `validated`, which is `copyValidStoredResource(...)`'s result — a resource
  that function constructs itself.

So the copy inside `selectContent` duplicates an object whose only other reference is the local that
was just handed to it.

## What the copy was defending is defended anyway

The habit this comes from is sound and stays: a value that crosses into RenG from a consumer's
`Store` or `Transport` is copied at that boundary, because the consumer keeps a reference and may
mutate the array afterwards. **That copy is untouched** — line 385's `copyStored` on the observation,
and `copyValidStoredResource`'s own construction, both remain.

What is removed is the *second* copy, taken from a value that never left. And `StoredRawResource`
defends itself independently of either: it keeps `storedBytes` private and `bytes` hands out
`freshCopy()`, so a caller reading the content this selects cannot reach the array behind it however
many times it was or was not copied on the way in.

## Why this is a decision and not a tidy-up

Removing a defensive copy is exactly the kind of change that is right until someone adds a fifth
caller that passes a borrowed array, and then it is a silent aliasing bug rather than a loud one.

The rule this records is therefore about the *boundary*, not about this function: **a resource is
copied where it enters RenG, and nowhere after.** A future caller of `selectContent` is bound by the
same rule its four existing callers already follow, and a caller that cannot honour it should copy at
its own boundary rather than ask this function to copy for everyone.

## Erratum, 2026-10-03 — the boundary is the constructor, so the observation copy goes too

This ADR kept two copies as "the boundary": `copyStored` on the resident observation, and
`copyValidStoredResource`'s own construction. **Neither was a boundary.** Both copied from a
`StoredRawResource`, never from a raw `ByteArray`, and a `StoredRawResource` is already immutable: its
public constructor copies whatever array the caller passed, keeps that copy private, never mutates it,
and copies it out of every `bytes` read. So a consumer — or the resident cache, or the engine — that
keeps a reference to a resource can reach nothing behind it, and re-copying the resource defended the
same nothing the removed `selectContent` copy did.

They were not free. On an OPPO CPH2629 exporting a 28 s 720p24 project, simpleperf charged 2.2% of all
of the app's CPU to `memcpy` under `StoredRawResource.<init>`, reached through `PreparationDriver.run`:
every resident tile, DEM and style was copied once a frame, and the garbage those copies made fed a
collector that took another 8.3%.

So the rule stands, sharpened: **a resource is copied where a `ByteArray` enters RenG, which is the
public `StoredRawResource` constructor, and nowhere after.** RenG re-wraps a resource it already holds
with the internal `StoredRawResource.withSharedBytes(metadata)`, which shares the snapshot. It takes no
`ByteArray`, so it cannot be handed a borrowed one — the aliasing bug this ADR warned about cannot be
written through it. `copyStored`, `copyValidStoredResource` and the 304 merge now use it. The public
constructor, `bytes` and the ABI are unchanged.

The same reasoning, applied to `CanonicalBytes`: an encoder's freshly built output, and a resource's
snapshot read only to be hashed, are now wrapped by the internal `CanonicalBytes.adopting` instead of
copied, and `CanonicalIdentityRegistry.register` retains the identity it is given instead of
deep-copying it — `CanonicalBytes` and `Sha256Digest` are immutable on the same terms.
