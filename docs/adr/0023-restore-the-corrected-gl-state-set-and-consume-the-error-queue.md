# Restore the corrected GL state set and consume the error queue

ADR 0006 documents the state RenG reads before it draws and restores before it returns, but measuring it
against a real driver found the list incomplete. The corrected **Restore Set** supersedes ADR 0006's list:
alongside every binding, blend, depth, cull, viewport, and scissor item ADR 0006 already named, it adds the
colour write mask; the pixel-store unpack alignment, row length, skip rows, and skip pixels; the pack
alignment; the colour and depth clear values; the array buffer binding; and the pixel unpack buffer
binding. The element array buffer binding needs no explicit restore, because it is per-VAO state that the
vertex array binding already restores implicitly when it is rebound; the array buffer binding is not
captured by the VAO and must be saved and restored on its own.

`GL_ACTIVE_TEXTURE` is captured first and reinstated last, with every per-unit texture and sampler read and
write nested inside that span. Reading any unit's binding other than the one already active requires making
that unit active first, so capturing texture state in any other order would overwrite the very value being
captured before it is read.

The measured default unpack and pack alignment is `4`, not `1`, confirmed on both llvmpipe and a hosted
macOS runner. An implementation that assumed `1` corrupts non-aligned rows on every context it touches and
leaves the restored state wrong rather than merely incomplete.

The save and restore code is dialect-aware because two of its queries do not exist on every context, not
because the **Restore Set** itself differs by dialect in kind: `GL_DRAW_BUFFER` and `GL_LINE_SMOOTH` are
queryable on a desktop core profile and raise `GL_INVALID_ENUM` on ES. An unconditional query list would
leave a spurious error flag on every ES context RenG touches, which is exactly the corruption this whole
set exists to prevent. RenG also sets `GL_FRAMEBUFFER_SRGB` explicitly rather than inheriting it, and
restores the caller's value, because Mesa hands it enabled on an ES context and disabled on its desktop
core context; inheriting it would make RenG's output depend on which kind of context the consumer happened
to create.

`glGetError` is destructive — reading it clears the flag, and no flag can be pushed back once read — so it
is the one piece of state the **Restore Set** cannot preserve. This is the one stated exception to ADR
0006's no-modification guarantee. RenG drains the error queue on entry to every operation, treats any flag
found there as the consumer's, and consumes it; the alternative, never calling `glGetError`, would give up
all of RenG's own internal error detection. The exception is declared here rather than left for a future
reader to discover as an inconsistency between ADR 0006's prose and RenG's own behaviour.

The whole corrected set is verified rather than asserted: a save, perturb, and restore round trip is
byte-exact against a real ES context and a real desktop context in the Cycle D conformance suite, not
merely against the recording fake used for the rest of this cycle's tests.
