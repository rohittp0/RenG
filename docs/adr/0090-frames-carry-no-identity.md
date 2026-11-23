# Frames carry no identity

This decision supersedes the frame-identity parts of ADR 0014, ADR 0018, ADR 0042 and ADR 0081. Resource
identities are untouched: their canonical roots, their stable ids, `preRegister`'s byte-level collision
check and `IDENTITY_COLLISION` stay exactly as ADR 0018 and ADR 0081 leave them.

Every `prepare` encoded the whole **Frame Plan** into a canonical root (`FramePlanCanonicalEncoder`),
took its SHA-256, compared its eleven segment payloads with the previous frame's into a
`FrameStructuralDiff`, registered the digest in `CanonicalIdentityRegistry`, and kept the encoding in
**Frame History** as the next frame's baseline. A batch threaded each item's encoding to the next, and
`clearFrameHistory()` dropped the baseline and rebuilt the registry.

An audit of every consumer (2026-11-10) found no reader. No rendering, acquisition, caching, lease, draw,
diagnostic, metric or public API path reads the digest, the root bytes, the diff or the registry's verdict:
`changedSegments` was read only by `PlannedFrameCore.equals`, the registry's result was discarded, and
nothing public exposes a frame identity (`PreparedFrame`, metrics, diagnostics and the ABI dump carry none).
The "structural reuse" ADR 0014 planned the diff for was never built: reuse across frames is content-keyed,
through Resource Keys, tile keys, program keys and label identities, none of which derive from the frame
encoding. The JSON round trip ADR 0042 promised would preserve the identity was never tested against it.
The ordered-preparation reducer that also threaded it is referenced only by tests.

The work was not free. ADR 0081 measured the whole-plan encoding at about 5 ms of a 30 ms `prepare` on an
OPPO CPH2629, and after that ADR moved shader sources out of it, uniforms were 79% of what remained: every
geometry -- and the consumer app expands each label into a uniform-heavy geometry -- was encoded, copied
into its list, the segment, the root, hashed, and byte-compared against the last frame on every `prepare`,
up to six times an output frame under motion blur.

## Decision

A frame is no longer encoded, hashed, diffed or registered. Removed: the frame encoder and its uniform-list
writer, `EncodedFramePlan`, `FramePlanSegment`, `FrameStructuralDiff` and its differ,
`CanonicalIdentityRegistry`, the encoded plan and diff on `FramePlanningRequest` and `PlannedFrameCore`,
the encoded plan in `PreparationHistory` and the reducer's `CommittedFrameHistory`, and the renderer's
batch, commit and clear plumbing for them. **Frame History** is now the frame index, the selected LOD,
label fade and the settled label tiles; `clearFrameHistory()` clears exactly those, as before.

`CanonicalRootKind.FRAME` stays as a reserved, never-written value, so root-kind byte `1` is never given to
another kind and no resource root's kind byte moves. `CanonicalBinary`, `CanonicalBytes`, the SHA-256
implementations, `HashedCanonicalBytes`, `ResourceKeyDeriver`, `ShaderPairMemo`, `CanonicalIdentityRecord`,
`LabelIdentity` and JSON serialization are unchanged.

**The one observable effect is kept on purpose.** Encoding wrote every uniform and texture name as canonical
UTF-8, which refuses an isolated UTF-16 surrogate. Every other string a plan carries is already validated
where it is constructed (`ResourceLocator`, `ShaderPair`, `StickerShader`'s source, `AnimationSelector.Name`)
and every number is canonicalised at construction, but those map keys were checked only for reserved names
and counts, so the encoding was their only Unicode check. `FramePlanningCore.plan` now checks the names of
every sticker program, geometry and shader backdrop explicitly, at the same point -- after spatial planning,
before the static resource traversal -- with the same `IllegalArgumentException` and message
(`canonical UTF-8 value must contain Unicode scalar values`). A spatial failure still takes precedence, and
no private resource key is resolved for a refused plan.

**Rejected.** Computing the identity lazily keeps the format, its golden fixtures and its documentation alive
for a caller that does not exist; a future cache keyed by frame would choose its key for that use. Moving the
surrogate check into the `Geometry`, `StickerShader` and `Backdrop.Shader` constructors would refuse such a
name earlier, at construction and at JSON decode, which changes when and where the failure surfaces.

## Consequences

- `prepare` no longer encodes, hashes or compares the plan; a long export no longer retains each batch's
  provisional roots. The saving is to be measured with `RendererFrameBenchmarkTest`'s B7 cases.
- Nothing public changes: `checkKotlinAbi` passes against the unchanged dump.
- ADR 0018's Frame Plan field-tag table, its representative frame fixture and its frame `reng-frame-v1:`
  form are retired with the encoder; ADR 0018's errata for frames, and the notes in ADRs 0037, 0082,
  0083, 0085, 0086 and 0087 that kept frame identities stable, describe a format that no longer exists. Those ADRs' rendering decisions are unaffected.
- A frame plan that carried a pathologically large encoding no longer fails planning with the encoder's
  size `IllegalArgumentException`; such a plan would normally exhaust memory long before.
