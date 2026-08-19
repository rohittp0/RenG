# Mesa cross-dialect `glLinkProgram` segfault, and why the negative check is now driver-conditional — 2026-08-19

**Status: the driver is at fault, RenG is exonerated, and the approved gate is implemented exactly as
scoped — but it does not make `LinuxGlConformanceTest` crash-free on Mesa 25.2.8.** A second, independent
path to the same driver defect remains: `theSameBinaryDetectsTwoDialectsOnOneTarget` creates a second
GLES-profile context in-process, and two-or-more GLES contexts each running the (correctly) unchanged
negative check is its own reliable trigger this gate was never scoped to touch. See "What
post-implementation verification actually found" below.

Task 17's Linux conformance fixture (`LinuxGlConformanceTest`, `SurfacelessEglContext`) intermittently
SIGSEGVs inside the driver rather than failing an assertion. This record states what was observed, the
exact trigger, the version matrix, why RenG's own code is exonerated, and why
`assertShaderDialectMatrix` in `GlConformanceSuite.kt` now skips its deliberate cross-dialect
`glLinkProgram` on drivers that advertise `GL_ARB_ES3_compatibility`. The full investigation, including
the statistical method behind every count below, is
`.superpowers/sdd/2026-08-18-cycle-d-gl-foundation/task-17-segfault-report.md`; this document distills
the parts that matter to anyone reading the gated code later, plus the parts that remain open.

## What was observed

Running more than one of `LinuxGlConformanceTest`'s test methods in the same process — or running
`theSameBinaryDetectsTwoDialectsOnOneTarget` alone, since its own body creates both context types —
crashes the test binary with a `SIGSEGV` deep inside `libgallium-25.2.8-0ubuntu0.24.04.2.so` (stripped,
no symbols). A backtrace on both the original Kotlin `test.kexe` and a minimized standalone C
reproducer places frame 0 inside `libgallium`, called directly from the deliberate cross-dialect
`glLinkProgram` call in `assertShaderDialectMatrix`'s negative path (`GlShaderCompiler.kt`'s
`compileShaderProgram`, invoked with the shader dialect opposite the adopted context's own). Thread
accounting at crash time shows llvmpipe's rasterizer thread pool being cleanly spawned and torn down
between contexts, with no leaked threads — the corruption is not an externally visible resource leak,
consistent with an internal compiler data structure (a static/global symbol table, IR allocator, or
version-compatibility cache) not being correctly reset across a "new gallium screen created after an
old one died" boundary.

## The exact trigger

A ~250-line standalone C program (`repro.c`, not checked into this repository — see the companion bug
report draft, `docs/research/2026-08-19-mesa-bug-report-draft.md`, for its full text) isolates the
trigger with no Kotlin, no cinterop, and no RenG code at all: it `dlopen`s `libEGL.so.1` and hand-calls
the same EGL and GL entry points `SurfacelessEglContext.kt` and `GlShaderCompiler.kt` call, including the
identical version-directive substitution `ShaderProfilePlan` performs (swap only the `#version 300 es`
line for `#version 330 core`, change nothing else, per ADR 0008).

The crash requires all three of:

1. Two or more EGL contexts created and destroyed across the process's lifetime.
2. At least one of those contexts is GLES-profile.
3. At least one of those contexts performs the shader-dialect-matrix's deliberate cross-`#version`
   link — compiling and linking a shader whose `#version` directive does not match the bound API.

It is **order-independent**: GLES-then-DESKTOP and DESKTOP-then-GLES both crash at effectively the same
rate. Extra EGL teardown calls (`eglReleaseThread`, omitting `eglTerminate`) do not fix it. Full OS-process
isolation via `fork()` does not fix it either — a forked child still crashed on its first context in one
trial, while a genuinely fresh `exec()`'d single-context process never crashed in 20/20 trials, which is
the signature of memory-layout-sensitive corruption rather than a documented API-misuse the fixture could
avoid.

Critically, **per-test-method process isolation does not fix this gate**, because
`theSameBinaryDetectsTwoDialectsOnOneTarget` creates both a GLES and a DESKTOP context inside its own
single method body — that is the entire point of the test, proving one binary handles both dialects.
Run completely alone, in its own fresh process, with nothing else in the binary's history, that one test
still crashed 14/15 times.

## The crash-rate matrix

All counts are `crashes/n` from the C reproducer, exit code ≥128 counted as a signal death:

| Sequence | With the cross-dialect link | Cross-dialect link skipped (matching-dialect only) |
|---|---|---|
| G (one GLES context, alone) | 0/20 | — |
| D (one DESKTOP context, alone) | 0/20 | — |
| GG | 15/15 | — |
| DD | 0/15 | — |
| GD (one of each, in order) | 29/30 | 0/20 |
| DG (one of each, reverse order) | 20/20 | — |
| GDG | 15/15 | — |
| DGD | 10/10 | — |
| GGG | 10/10 | — |
| DDD, and DD×10 | 0/10, 0/5 | — |
| The negative link repeated 2–5× inside one never-recreated context (either dialect) | 0/10 each | — |

A process containing only DESKTOP contexts, however many, is completely safe across 45+ trials. Skipping
the cross-dialect link and only ever linking matching-dialect shaders is safe even across repeated
GLES+DESKTOP context recreation (0/20 for the `GD` row's second column). This is the specific empirical
basis for the fix below: `GD` with the cross-dialect link skipped on the second (Mesa-ES3-compatible)
context is the exact configuration `assertShaderDialectMatrix` now runs, and it measured 0/20.

## The version matrix

The identical `GD` sequence was run against two Mesa releases on the same arm64 host:

| Mesa version | Ubuntu release | Crash rate |
|---|---|---|
| 23.2.1 (`libegl-mesa0`) | 22.04 | 0/15 |
| 25.2.8-0ubuntu0.24.04.2 | 24.04 | 29/30 |

This is a genuine regression somewhere in Mesa's GLSL-compiler / version-validation code between those
two releases, not a permanent architectural limitation of Mesa or llvmpipe.

## RenG production code is exonerated

RenG never performs the operation that crashes. `ShaderProfilePlan` substitutes `#version 300 es` for
`#version 330 core` only when the *runtime-queried* context dialect requires it (ADR 0008); it never
knowingly links a mismatched shader. Only `GlConformanceSuite.kt`'s deliberate negative check — the
suite's own proof that "the wrong `#version` directive fails to link" — performs the crashing operation,
and it does so on purpose, as a test.

Reading the production code independently confirms there is no cross-context state that could leak and
explain the crash as a RenG bug: `LinuxGlBinding` re-resolves all 84 entry points fresh via
`eglGetProcAddress` on every `openPlatformGlBinding()` call
(`kmp/src/linuxMain/kotlin/com/rohittp/reng/internal/gl/LinuxGlBinding.kt:23-39`); `GlObjectRegistry` and
`GlProgramCache` are plain instance fields with no companion or singleton state
(`kmp/src/commonMain/kotlin/com/rohittp/reng/internal/gl/GlProgramCache.kt`); and every conformance-suite
call site that could otherwise carry state across contexts constructs a fresh
`GlObjectRegistry()`/`GlProgramCache()` per invocation. `assertShaderDialectMatrix` itself doesn't touch
`GlProgramCache` at all — it calls `compileShaderProgram` directly, which creates and deletes its GL
objects locally with no cache. There is no vector for a stale handle from one context to reach another.

The fixture's EGL teardown was also ruled out directly: `SurfacelessEglContext.destroy()` already follows
the textbook-correct sequence (`eglMakeCurrent(NULL)` → `eglDestroyContext` → `eglTerminate`), and none of
the three teardown variants tested (adding `eglReleaseThread`, omitting `eglTerminate`, full process
forking) reliably fixes the crash — see the trigger section above.

## Why the conformance check is now driver-conditional

`assertShaderDialectMatrix` in `GlConformanceSuite.kt` already carries a tolerance branch,
`oppositeShouldLink`, gated on `profile.supportsEs3Compatibility`: a driver advertising
`GL_ARB_ES3_compatibility` is entitled to accept `#version 300 es` unchanged on a desktop context, so the
suite treats that as success rather than the usual rejection. Mesa's desktop core profile is exactly such
a driver (it advertises `GL_ARB_ES3_compatibility`, along with ES2/ES3.1/ES3.2 compatibility extensions);
its GLES profile is not (it has no need to advertise an ARB desktop-compatibility extension). That
tolerance branch's own cross-dialect link is precisely the call the crash-rate matrix shows crashing at
29/30 in the `GD` sequence — the *tolerated, successful* link performed on the second (DESKTOP,
ES3-compatible) context.

So on a driver advertising `GL_ARB_ES3_compatibility`, the negative expectation this check exists to
prove — "the wrong directive must fail to link" — is already not the correct expectation, since the
driver is entitled not to reject it; and forcing the link anyway is exactly the call proven to crash Mesa
25.2.8. `assertShaderDialectMatrix` therefore skips performing the link at all on such a driver, and
instead compiles (never links) the opposite-dialect shader to prove the capability probe itself is
sound — that `#version 300 es` genuinely is accepted at the compile stage — rather than silently skipping
with nothing asserted. This was believed, at design time, to fall outside the trigger, since the GDB
backtrace names `linkProgram` specifically. **Post-implementation verification (below) found this
belief incomplete**: compiling without linking does not, by itself, fully avoid the crash in the real
suite, though not for the reason the compile/link distinction would predict — see "What
post-implementation verification actually found."

Where the driver does not advertise `GL_ARB_ES3_compatibility` — Mesa's GLES profile, and Apple's 4.1 core
profile, which lacks the extension entirely — the negative check runs completely unchanged: the
assertion is valid there (the driver has no entitlement to accept the mismatch), and a non-ES3-compatible
driver was never observed to be part of the crash's "at least one context is entitled to accept
cross-dialect input" precondition in the way Mesa's desktop core profile is.

## What post-implementation verification actually found

The gate above was implemented and then verified under Docker (`ubuntu:24.04`, `linux/arm64` native, the
actual compiled `linuxArm64` `test.kexe`, real `libegl-mesa0` `25.2.8-0ubuntu0.24.04.2`). The honest
result: **the gate does not eliminate the crash in `LinuxGlConformanceTest` as currently written**, and
the reason is precise and traceable, not mysterious.

Filtering to only the two single-context test methods —
`theSuitePassesOnARealEsContext` then `theSuitePassesOnARealDesktopCoreContext`, one GLES context and one
DESKTOP context, exactly the `GD` sequence — passes cleanly and repeatably: 0 crashes across 12 trials in
fresh, single-use containers (`docker run --rm`, default settings, no environment overrides), both before
and after the gate. That two-context case was never the residual problem.

Running the **full three-method suite** (adding `theSameBinaryDetectsTwoDialectsOnOneTarget`) crashes
**15/15**, in fresh single-use containers, with the gate applied — statistically indistinguishable from
15/15 on the unmodified pre-gate code under the identical methodology. Reading the failure output shows
the same shape every time: `theSuitePassesOnARealEsContext` and `theSuitePassesOnARealDesktopCoreContext`
both report `OK`, and the crash occurs inside `theSameBinaryDetectsTwoDialectsOnOneTarget`, which is
un-modifiable per this task's constraints. That test creates its own GLES context (its `runOn(GLES)`
call) and its own DESKTOP context (`runOn(DESKTOP)`) *inside one method body*, specifically to prove one
binary handles both dialects. By the time it runs, the process already contains one prior GLES context
(from the first test method) and one prior DESKTOP context (from the second); adding its own GLES context
makes **two** GLES-profile contexts that have each performed `assertShaderDialectMatrix`'s negative
check — and the crash-rate matrix's `GG` row (two GLES contexts, each running the unmodified negative
check, with no DESKTOP context involved at all) already shows this configuration crashing 15/15 on its
own. The gate only ever touches the branch that runs when `profile.dialect == DESKTOP &&
profile.supportsEs3Compatibility`; it cannot and does not change what happens when a *second*
GLES-profile context performs its own (correctly unchanged, per the task's explicit instruction) negative
check. That second GLES context's negative check is exactly what this task was told not to alter — "keep
the negative check exactly as it is" where the driver does not advertise ES3 compatibility, which GLES
contexts on Mesa never do.

A further, unexpected finding surfaced while isolating this: **compiling a cross-dialect shader without
ever linking it was not, by itself, sufficient to avoid the crash** in a small-scale minimal-C-reproducer
check (a patched `repro.c` variant that compiles the opposite-dialect pair on the DESKTOP context but
never calls `glCreateProgram`/`glAttachShader`/`glLinkProgram` for it) — contradicting this record's
original assumption that the bug is confined to the link stage. That check was run inside a container
that had already accumulated state from prior trials, which a follow-up comparison showed materially
inflates crash frequency for genuinely single-context-pair (`GD`) cases specifically (see the companion
bug report draft's "A note on reproducibility and environment sensitivity" for the details and the
important caveat that this was not root-caused). The `GG`-shaped case central to the residual crash above
was **not** sensitive to that variable in the checks performed here — it reproduced at the same very high
rate in both fresh, single-use containers and in containers reused across many trials — so the conclusion
that the residual crash is the second GLES context's own negative check, not an artifact of test
methodology, stands on the cleanest evidence gathered.

**What this means going in:** the approved gate is correctly and narrowly scoped to what it was designed
to change — the `DESKTOP`-and-`ES3`-compatible tolerance branch — and it does change that branch exactly
as specified, without weakening the negative check anywhere it remains valid. It is not, on its own,
sufficient to make `LinuxGlConformanceTest` crash-free on Mesa 25.2.8, because `GG` (two-or-more
GLES-profile contexts) is a second, independent way to reach the same driver defect that this gate was
never scoped to address, and that Mesa defect is exercised by `theSameBinaryDetectsTwoDialectsOnOneTarget`
regardless of anything `assertShaderDialectMatrix` does on the DESKTOP side. Closing that gap would require
either changing what `theSameBinaryDetectsTwoDialectsOnOneTarget` proves or a different mitigation
entirely (for instance, gating on "is this the second-or-later GLES-profile context in the process",
which is a materially different and more invasive design than the one approved for this task) — a
decision squarely inside the scope this task was told belongs to whoever owns the Cycle D Linux gate
design, not to this fix.

## What remains unproven

- **Not observed on a real GitHub Actions `ubuntu-latest` runner.** All evidence here is Docker
  `ubuntu:24.04` (`linux/arm64` native and `linux/amd64` under QEMU) with the exact three packages
  `ci.yml`/`publish.yml` install (`libegl1`, `libegl-mesa0`, `libgles2`). `ubuntu-latest`'s exact Mesa
  point release was not independently checked from here.
- **No upstream Mesa bug search was performed.** Mesa's GitLab issue tracker was not searched for an
  existing report matching this signature before drafting the ready-to-file report in
  `docs/research/2026-08-19-mesa-bug-report-draft.md`.
- **The regression range is unbisected.** Only the two endpoints were tested — 23.2.1 (unaffected, 0/15)
  and 25.2.8 (affected, 29/30). No intermediate Ubuntu release (23.10, 24.10) was tried, so the exact
  Mesa version that introduced the defect is unknown.
- **`LinuxGlConformanceTest` still crashes on Mesa 25.2.8 after this gate**, at `15/15` in fresh-container
  verification, via `theSameBinaryDetectsTwoDialectsOnOneTarget`'s own second GLES-profile context — see
  "What post-implementation verification actually found" above. This gate closes only the branch it was
  scoped to close.
- **The container/cache environment-sensitivity noted above is not root-caused.** Whether it is Mesa's
  on-disk shader cache specifically, some other piece of per-container state, or coincidence across a
  modest sample size was not determined, and it was only checked for the two-context (`GD`/`DG`) cases,
  not systematically across the whole matrix.
