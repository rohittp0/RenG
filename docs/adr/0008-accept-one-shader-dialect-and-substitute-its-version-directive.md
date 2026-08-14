# Accept one shader dialect and substitute its version directive

A consumer writes every **Geometry** shader as a GLSL ES 3.00 body beginning with `#version 300 es`.
RenG detects the **Render Context**'s shading language at setup and, on a desktop OpenGL context,
replaces that one line with `#version 330 core` before compiling. Nothing else is added, removed, or
rewritten: no includes, no uniform preamble, no defines, no reordering. On a GLES context the source
compiles byte-for-byte as written. A source whose first non-blank, non-comment line is not
`#version 300 es` is rejected with a typed error rather than guessed at.

No single version directive compiles everywhere, and this is driver-confirmed rather than assumed. On
a headless Apple Silicon core-profile context reporting `4.1 Metal - 90.5`, `#version 300 es` fails
with "version '300' is not supported"; on the legacy 2.1 profile only GLSL 120 is accepted and both
`300 es` and `410 core` fail. But the shader *body* ports intact — a fragment shader using
`precision mediump float;`, `in`/`out`, `texture()`, `textureSize()`, `layout(location = …)`, and
integer uniforms compiles and links unchanged under a substituted directive. The incompatibility is one
line wide, so RenG substitutes one line.

"No injected preamble" does not mean "no interface". RenG documents a fixed set of attribute and
uniform *names*; a **Shader Pair** that wants the model-view-projection matrix or the quad's texture
coordinates declares them itself, exactly as it would in any other OpenGL program, and RenG binds them
by name when the compiled program actually declares them. A shader that declares none of them compiles
and draws; a shader that declares one gets it set. RenG never prepends a line to consumer source, and
the documented names are a contract the consumer opts into rather than text RenG inserts.

The substituted version is `330 core` rather than the `410` macOS reports, because GLSL 330 is the
desktop equivalent of GLSL ES 3.00 and supports everything the profile needs, so a Linux consumer
supplying a GL 3.3 context is served by the same source. This amends CLAUDE.md's description of
geometry shaders as "plain, fully self-contained OpenGL shaders": they remain self-contained in every
respect except the version line, which RenG owns. The alternatives were requiring consumers to supply
one variant per dialect, which duplicates every shader forever, and shipping ANGLE to make GLES3
universal on Apple, which adds per-target native binaries to the published artifact to avoid rewriting
a single line.
