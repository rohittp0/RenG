# Fix the output pixel size at renderer setup

A renderer is configured with one output pixel size for its lifetime, beside its **Transport**,
**Store**, and **Basemap Style**. Preparation and drawing are both size-free: preparing takes a
**Frame Plan** and nothing else, and drawing takes a **Prepared Frame** and a framebuffer identity.
A consumer whose output size changes builds another renderer.

Output size cannot be discovered at draw time, because which **Basemap Tile**s a frame needs and at
which zoom depends on how many pixels the frame covers, and tiles are acquired during preparation.
The alternatives all pay for that somewhere: passing the size to preparation makes a **Prepared Frame**
valid for exactly one size and forces a re-prepare on resize, putting it in the **Frame Plan** means a
resize rewrites every plan in a sequence, and preparing for a range of sizes over-acquires tiles and
blurs what a prepared frame guarantees. Fixing it at setup keeps a **Frame Plan** pure content and a
**Prepared Frame** unambiguous, at the cost of a cold renderer on resize.

Two consequences follow. The offscreen surface of ADR 0005 is allocated once at the configured size and
never resized, so the resize path disappears entirely. And RenG takes the caller's word that the
framebuffer it is handed matches the configured size rather than interrogating its attachments on every
frame; a mismatch is a consumer error whose symptom is a wrongly-scaled frame, not corruption.
