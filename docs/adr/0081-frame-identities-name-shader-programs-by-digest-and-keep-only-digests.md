# Frame identities name shader programs by digest and keep only digests

ADR 0018 made two promises about Frame Identities that this ADR withdraws, on the project owner's decision
(2026-10-09). Resource identities are untouched: their roots, their stable ids and `preRegister`'s
byte-level collision check stay exactly as ADR 0018 states them.

**A geometry no longer carries its shader sources.** `Geometry` tag 3 (`shaderPair`, the vertex and fragment
sources) is retired. Tag 9 is the geometry program's stable id: the lowercase-hex SHA-256 of the
geometry-program resource root ADR 0018 already defines -- profile version plus exact vertex and fragment
sources -- encoded as exact UTF-8. A Frame Identity therefore still changes when any shader byte changes, now
through a 64-character digest instead of the sources themselves. RenG already derives that id to key the
program and remembers it in `ShaderPairMemo`, so encoding a geometry hashes nothing. Following ADR 0018's
append-only rule, tag 3 is retired, never reused, and tag 9 encodes last. `Backdrop.Shader` keeps its sources
(its tag 3): one per frame, not worth a second rule.

**The frame registry keeps digests only.** `CanonicalIdentityRegistry` held every frame's complete canonical
encoding beside its digest until `clearFrameHistory`, so that a different encoding with an equal digest -- a
SHA-256 collision -- could fail `IDENTITY_COLLISION`. It now holds a set of digests: a repeated digest is the
same frame, and frame planning no longer fails with `IDENTITY_COLLISION`. The error code remains for
resource identities.

**Why.** On an OPPO CPH2629 (2026-10-09), exporting a project whose motion blur prepares every output frame up
to six times, `prepare` became the export's bottleneck at about 30 ms a sample, and the whole-plan canonical
encoding was about 5 ms of it: every geometry copied both full shader sources into its record, the geometries
list, the root, the structural diff's comparison and the registry. The registry's retained encodings were
also the 88 MiB of Java heap a long export held until its history was cleared. A SHA-256 collision is not a
practical risk for an in-process cache key, and the check paid for that guard with time and memory on every
frame. The same change remembers each external route's key and Rentile private key (`RouteMemo`), adopts
freshly computed digests without a copy, builds a digest's hex only when read, and keeps one `MessageDigest`
per thread on Android; those leave every byte as it was.

**Fixture.** The representative frame fixture loses its two geometries' sources and gains their program ids:
1,554 bytes to 1,438, identity `reng-frame-v1:42e78dd27ae55d9b38370c226615d5a4892bccd97a0f15b60c0d0090eb5b880e`.
It was re-derived outside RenG with Python's `hashlib` by a script that first reproduced the previous
fixture's digest and the pinned `("v", "f")` geometry-program digest, as ADR 0018's errata require. The minimal
fixture, which has no geometries, does not move.

**Rejected.** Keeping the collision check by retaining one copy of each distinct shader source keyed by its
digest would have kept most of the speed, but not the reason the check existed, and the owner chose to relax
it. Reusing a geometry's encoding when it equals the previous frame's was also considered; with uniforms that
change every sample it would rarely hit.

## Note, 2026-10-22: what remained of identity encoding after this decision

On an OPPO CPH2629 (2026-10-09), exporting a project whose motion blur prepares every output frame up to
six times, resource-key derivation was 13% of RenG's time (`RouteMemo` now derives each external
reference's key once) and frame encoding a fifth, of which uniforms were 79% once shader sources left the
encoding (`UniformListEncoding` now writes the list in two passes with no intermediate objects; its
redundant finiteness check alone was about a sixth of plan identity encoding).
