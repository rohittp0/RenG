# Ready-to-file Mesa bug report draft — cross-#version-dialect `glLinkProgram` SIGSEGV

**Status: drafted, NOT filed.** This document is written to be pasted into a new issue on
`gitlab.freedesktop.org/mesa/mesa` as-is. Filing it is the repository owner's decision; nothing in this
document has been submitted anywhere. No upstream search for an existing matching report has been
performed yet — that should happen before filing (see the companion research record,
`docs/research/2026-08-19-mesa-cross-dialect-link-segfault.md`, "What remains unproven").

---

## Summary

`glLinkProgram` SIGSEGVs inside `libgallium` on Mesa 25.2.8 (llvmpipe / softpipe, surfaceless EGL) when a
process holds two or more EGL contexts and at least one of them is GLES-profile, provided at least one
context in the process's history performs a shader link where the vertex/fragment `#version` directive
does not match the bound API's dialect (an ES `#version 3xx es` shader linked under a desktop-profile
context, or vice versa). Mesa 23.2.1 does not exhibit this; it is a regression introduced somewhere
between the two.

## Affected versions

| Component | Version |
|---|---|
| Mesa (affected) | `25.2.8-0ubuntu0.24.04.2` (Ubuntu 24.04's `libegl-mesa0`) |
| Mesa (unaffected) | `23.2.1` (Ubuntu 22.04's `libegl-mesa0`) |
| LLVM (affected build) | 20.1.2 |
| `libegl1` (glvnd dispatch) | `1.7.0-1build1` |
| `libgles2` | `1.7.0-1build1` |
| Driver | llvmpipe (software rasterizer), both `GL_RENDERER` reports observed: `llvmpipe (LLVM 20.1.2, 256 bits)` |
| EGL platform | `EGL_PLATFORM_SURFACELESS_MESA` (`EGL_MESA_platform_surfaceless`), no display server, no GBM device, no DRM node |

The exact regression range between 23.2.1 and 25.2.8 has not been bisected; no intermediate Ubuntu
release (23.10, 24.10) was tried.

## Hardware and platforms tested

- **linux/arm64**, native (Apple Silicon host, Docker Desktop's Linux VM, no QEMU translation).
- **linux/amd64**, under QEMU emulation on the same arm64 host (Docker Desktop's `--platform linux/amd64`).

Both reproduce the crash. No physical x86-64 or discrete-GPU hardware was tested; llvmpipe is a pure
software rasterizer, so no GPU or kernel driver is implicated.

## Minimal reproducer

Standalone C, no Kotlin, no application framework, no cinterop-generated code — `dlopen`s `libEGL.so.1`
directly and resolves every entry point through `eglGetProcAddress` with a `dlsym` fallback, mirroring
exactly what a glvnd-dispatched application does. Save as `repro.c`.

```c
// Minimal C reproducer mirroring SurfacelessEglContext.kt's exact EGL sequence,
// with an optional GL workload mirroring the conformance suite's shader-dialect
// matrix negative-path (a deliberate wrong-dialect glLinkProgram expected to fail).
//
// Usage: ./repro <mode> <use_gl> <release_thread>
//   mode: "GDG" (GLES,DESKTOP,GLES) or "GGG" or "DDD"
//   use_gl: 0 = bare context churn only, 1 = run shader compile/link workload
//   release_thread: 0 = do not call eglReleaseThread between contexts, 1 = do
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>

typedef unsigned int EGLBoolean;
typedef void *EGLDisplay;
typedef void *EGLContext;
typedef void *EGLConfig;
typedef void *EGLSurface;
typedef intptr_t EGLAttrib;
typedef int32_t EGLint;
typedef void (*__eglMustCastToProperFunctionPointerType)(void);

#define EGL_NONE 0x3038
#define EGL_PLATFORM_SURFACELESS_MESA 0x31DD
#define EGL_OPENGL_ES_API 0x30A0
#define EGL_OPENGL_API 0x30A2
#define EGL_RENDERABLE_TYPE 0x3040
#define EGL_SURFACE_TYPE 0x3033
#define EGL_PBUFFER_BIT 0x0001
#define EGL_OPENGL_ES3_BIT 0x0040
#define EGL_OPENGL_BIT 0x0008
#define EGL_CONTEXT_MAJOR_VERSION 0x3098
#define EGL_CONTEXT_MINOR_VERSION 0x30FB
#define EGL_CONTEXT_OPENGL_PROFILE_MASK 0x30FD
#define EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT 0x0001

typedef EGLDisplay (*GetPlatformDisplayFn)(unsigned int, void *, const EGLAttrib *);
typedef EGLBoolean (*InitializeFn)(EGLDisplay, EGLint *, EGLint *);
typedef EGLBoolean (*BindAPIFn)(unsigned int);
typedef EGLBoolean (*ChooseConfigFn)(EGLDisplay, const EGLint *, EGLConfig *, EGLint, EGLint *);
typedef EGLContext (*CreateContextFn)(EGLDisplay, EGLConfig, EGLContext, const EGLint *);
typedef EGLBoolean (*MakeCurrentFn)(EGLDisplay, EGLSurface, EGLSurface, EGLContext);
typedef EGLContext (*GetCurrentContextFn)(void);
typedef EGLBoolean (*DestroyContextFn)(EGLDisplay, EGLContext);
typedef EGLBoolean (*TerminateFn)(EGLDisplay);
typedef EGLBoolean (*ReleaseThreadFn)(void);
typedef __eglMustCastToProperFunctionPointerType (*GetProcAddressFn)(const char *);

static void *lib;
static GetPlatformDisplayFn eglGetPlatformDisplay_;
static InitializeFn eglInitialize_;
static BindAPIFn eglBindAPI_;
static ChooseConfigFn eglChooseConfig_;
static CreateContextFn eglCreateContext_;
static MakeCurrentFn eglMakeCurrent_;
static GetCurrentContextFn eglGetCurrentContext_;
static DestroyContextFn eglDestroyContext_;
static TerminateFn eglTerminate_;
static ReleaseThreadFn eglReleaseThread_;
static GetProcAddressFn eglGetProcAddress_;

typedef unsigned int GLuint;
typedef int GLint;
typedef unsigned int GLenum;
typedef int GLsizei;
typedef char GLchar;

typedef GLuint (*CreateShaderFn)(GLenum);
typedef void (*ShaderSourceFn)(GLuint, GLsizei, const GLchar *const *, const GLint *);
typedef void (*CompileShaderFn)(GLuint);
typedef void (*GetShaderivFn)(GLuint, GLenum, GLint *);
typedef void (*GetShaderInfoLogFn)(GLuint, GLsizei, GLsizei *, GLchar *);
typedef GLuint (*CreateProgramFn)(void);
typedef void (*AttachShaderFn)(GLuint, GLuint);
typedef void (*LinkProgramFn)(GLuint);
typedef void (*GetProgramivFn)(GLuint, GLenum, GLint *);
typedef void (*GetProgramInfoLogFn)(GLuint, GLsizei, GLsizei *, GLchar *);
typedef void (*DeleteShaderFn)(GLuint);
typedef void (*DeleteProgramFn)(GLuint);

#define GL_VERTEX_SHADER 0x8B31
#define GL_FRAGMENT_SHADER 0x8B30
#define GL_COMPILE_STATUS 0x8B81
#define GL_LINK_STATUS 0x8B82

/* A representative GLSL ES 3.00 body: precision qualifier, in/out, layout(location=...),
 * texture()/textureSize(), an integer uniform, and a mat4 uniform. The "desktop" variant is
 * produced the same way an application's version-directive substitution would: swap only the
 * `#version 300 es` directive line for `#version 330 core` and change nothing else in the body. */
static const char *GLES_VERTEX =
    "#version 300 es\n"
    "layout(location = 0) in vec3 rengConformancePosition;\n"
    "uniform mat4 rengConformanceMatrix;\n"
    "out vec2 rengConformanceUv;\n"
    "void main() {\n"
    "    rengConformanceUv = rengConformancePosition.xy;\n"
    "    gl_Position = rengConformanceMatrix * vec4(rengConformancePosition, 1.0);\n"
    "}\n";
static const char *GLES_FRAGMENT =
    "#version 300 es\n"
    "precision mediump float;\n"
    "uniform sampler2D rengConformanceTexture;\n"
    "uniform int rengConformanceLevel;\n"
    "in vec2 rengConformanceUv;\n"
    "layout(location = 0) out vec4 rengConformanceColour;\n"
    "void main() {\n"
    "    vec2 size = vec2(textureSize(rengConformanceTexture, rengConformanceLevel));\n"
    "    rengConformanceColour = texture(rengConformanceTexture, rengConformanceUv / max(size, vec2(1.0)));\n"
    "}\n";

static const char *DESKTOP_VERTEX =
    "#version 330 core\n"
    "layout(location = 0) in vec3 rengConformancePosition;\n"
    "uniform mat4 rengConformanceMatrix;\n"
    "out vec2 rengConformanceUv;\n"
    "void main() {\n"
    "    rengConformanceUv = rengConformancePosition.xy;\n"
    "    gl_Position = rengConformanceMatrix * vec4(rengConformancePosition, 1.0);\n"
    "}\n";
static const char *DESKTOP_FRAGMENT =
    "#version 330 core\n"
    "precision mediump float;\n"
    "uniform sampler2D rengConformanceTexture;\n"
    "uniform int rengConformanceLevel;\n"
    "in vec2 rengConformanceUv;\n"
    "layout(location = 0) out vec4 rengConformanceColour;\n"
    "void main() {\n"
    "    vec2 size = vec2(textureSize(rengConformanceTexture, rengConformanceLevel));\n"
    "    rengConformanceColour = texture(rengConformanceTexture, rengConformanceUv / max(size, vec2(1.0)));\n"
    "}\n";

static void die(const char *msg) {
    fprintf(stderr, "FATAL: %s\n", msg);
    exit(2);
}

static void *resolve(const char *name) {
    void *p = (void *) eglGetProcAddress_(name);
    if (!p) p = dlsym(lib, name);
    if (!p) { fprintf(stderr, "missing %s\n", name); die("resolve"); }
    return p;
}

typedef enum { GLES, DESKTOP } Dialect;

typedef struct {
    EGLDisplay display;
    EGLContext context;
} Ctx;

static Ctx create_context(Dialect dialect) {
    EGLDisplay display = eglGetPlatformDisplay_(EGL_PLATFORM_SURFACELESS_MESA, NULL, NULL);
    if (!display) die("eglGetPlatformDisplay failed");
    EGLint major, minor;
    if (!eglInitialize_(display, &major, &minor)) die("eglInitialize failed");

    unsigned int renderableBit = dialect == GLES ? EGL_OPENGL_ES3_BIT : EGL_OPENGL_BIT;
    unsigned int api = dialect == GLES ? EGL_OPENGL_ES_API : EGL_OPENGL_API;
    if (!eglBindAPI_(api)) die("eglBindAPI failed");

    EGLint configAttrs[] = {
        EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_RENDERABLE_TYPE, (EGLint) renderableBit,
        EGL_NONE,
    };
    EGLConfig config;
    EGLint configCount = 0;
    if (!eglChooseConfig_(display, configAttrs, &config, 1, &configCount) || configCount <= 0) {
        die("eglChooseConfig failed");
    }

    EGLint contextAttrs[8];
    if (dialect == GLES) {
        contextAttrs[0] = EGL_CONTEXT_MAJOR_VERSION; contextAttrs[1] = 3;
        contextAttrs[2] = EGL_CONTEXT_MINOR_VERSION; contextAttrs[3] = 0;
        contextAttrs[4] = EGL_NONE;
    } else {
        contextAttrs[0] = EGL_CONTEXT_MAJOR_VERSION; contextAttrs[1] = 3;
        contextAttrs[2] = EGL_CONTEXT_MINOR_VERSION; contextAttrs[3] = 3;
        contextAttrs[4] = EGL_CONTEXT_OPENGL_PROFILE_MASK; contextAttrs[5] = EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT;
        contextAttrs[6] = EGL_NONE;
    }
    EGLContext context = eglCreateContext_(display, config, NULL, contextAttrs);
    if (!context) die("eglCreateContext failed");
    if (!eglMakeCurrent_(display, NULL, NULL, context)) die("eglMakeCurrent failed");

    Ctx c; c.display = display; c.context = context;
    return c;
}

static void destroy_context(Ctx c, int release_thread) {
    eglMakeCurrent_(c.display, NULL, NULL, NULL);
    eglDestroyContext_(c.display, c.context);
    eglTerminate_(c.display);
    if (release_thread) eglReleaseThread_();
}

static GLuint compile(CreateShaderFn createShader, ShaderSourceFn shaderSource, CompileShaderFn compileShader,
                       GetShaderivFn getShaderiv, GetShaderInfoLogFn getShaderInfoLog,
                       GLenum type, const char *src, int *ok) {
    GLuint shader = createShader(type);
    shaderSource(shader, 1, &src, NULL);
    compileShader(shader);
    GLint status = 0;
    getShaderiv(shader, GL_COMPILE_STATUS, &status);
    *ok = status != 0;
    return shader;
}

// Link the matching dialect (expect success), delete it, then attempt to link the OPPOSITE
// dialect's sources on this same context (expect a clean link failure on GLES contexts, and on
// DESKTOP contexts lacking ES3 compat; expect a tolerated success on DESKTOP contexts that
// advertise GL_ARB_ES3_compatibility).
static void run_shader_dialect_matrix(Dialect dialect, const char *tag) {
    CreateShaderFn createShader = (CreateShaderFn) resolve("glCreateShader");
    ShaderSourceFn shaderSource = (ShaderSourceFn) resolve("glShaderSource");
    CompileShaderFn compileShader = (CompileShaderFn) resolve("glCompileShader");
    GetShaderivFn getShaderiv = (GetShaderivFn) resolve("glGetShaderiv");
    GetShaderInfoLogFn getShaderInfoLog = (GetShaderInfoLogFn) resolve("glGetShaderInfoLog");
    CreateProgramFn createProgram = (CreateProgramFn) resolve("glCreateProgram");
    AttachShaderFn attachShader = (AttachShaderFn) resolve("glAttachShader");
    LinkProgramFn linkProgram = (LinkProgramFn) resolve("glLinkProgram");
    GetProgramivFn getProgramiv = (GetProgramivFn) resolve("glGetProgramiv");
    GetProgramInfoLogFn getProgramInfoLog = (GetProgramInfoLogFn) resolve("glGetProgramInfoLog");
    DeleteShaderFn deleteShader = (DeleteShaderFn) resolve("glDeleteShader");
    DeleteProgramFn deleteProgramFn = (DeleteProgramFn) resolve("glDeleteProgram");

    const char *matchV = dialect == GLES ? GLES_VERTEX : DESKTOP_VERTEX;
    const char *matchF = dialect == GLES ? GLES_FRAGMENT : DESKTOP_FRAGMENT;
    const char *oppV = dialect == GLES ? DESKTOP_VERTEX : GLES_VERTEX;
    const char *oppF = dialect == GLES ? DESKTOP_FRAGMENT : GLES_FRAGMENT;

    int ok;
    fprintf(stderr, "[%s] compiling matching-dialect pair\n", tag);
    GLuint v1 = compile(createShader, shaderSource, compileShader, getShaderiv, getShaderInfoLog, GL_VERTEX_SHADER, matchV, &ok);
    GLuint f1 = compile(createShader, shaderSource, compileShader, getShaderiv, getShaderInfoLog, GL_FRAGMENT_SHADER, matchF, &ok);
    GLuint p1 = createProgram();
    attachShader(p1, v1); attachShader(p1, f1);
    fprintf(stderr, "[%s] linking matching-dialect program\n", tag);
    linkProgram(p1);
    GLint linkStatus = 0;
    getProgramiv(p1, GL_LINK_STATUS, &linkStatus);
    fprintf(stderr, "[%s] matching link status=%d\n", tag, linkStatus);
    deleteShader(v1); deleteShader(f1); deleteProgramFn(p1);

    if (getenv("REPRO_SKIP_OPPOSITE")) {
        fprintf(stderr, "[%s] REPRO_SKIP_OPPOSITE set: skipping the cross-dialect link entirely\n", tag);
        return;
    }

    fprintf(stderr, "[%s] compiling OPPOSITE-dialect pair (negative path)\n", tag);
    GLuint v2 = compile(createShader, shaderSource, compileShader, getShaderiv, getShaderInfoLog, GL_VERTEX_SHADER, oppV, &ok);
    GLuint f2 = compile(createShader, shaderSource, compileShader, getShaderiv, getShaderInfoLog, GL_FRAGMENT_SHADER, oppF, &ok);
    GLuint p2 = createProgram();
    attachShader(p2, v2); attachShader(p2, f2);
    fprintf(stderr, "[%s] linking OPPOSITE-dialect program (this is where the reported crash occurs)\n", tag);
    fflush(stderr);
    linkProgram(p2);
    fprintf(stderr, "[%s] survived opposite link\n", tag);
    GLint linkStatus2 = 0;
    getProgramiv(p2, GL_LINK_STATUS, &linkStatus2);
    fprintf(stderr, "[%s] opposite link status=%d\n", tag, linkStatus2);
    deleteShader(v2); deleteShader(f2); deleteProgramFn(p2);
}

int main(int argc, char **argv) {
    const char *mode = argc > 1 ? argv[1] : "GDG";
    int use_gl = argc > 2 ? atoi(argv[2]) : 1;
    int release_thread = argc > 3 ? atoi(argv[3]) : 0;
    int repeats_per_context = argc > 4 ? atoi(argv[4]) : 1;

    lib = dlopen("libEGL.so.1", RTLD_NOW);
    if (!lib) die("dlopen libEGL.so.1 failed");
    eglGetProcAddress_ = (GetProcAddressFn) dlsym(lib, "eglGetProcAddress");
    if (!eglGetProcAddress_) die("no eglGetProcAddress");

    eglGetPlatformDisplay_ = (GetPlatformDisplayFn) dlsym(lib, "eglGetPlatformDisplay");
    eglInitialize_ = (InitializeFn) dlsym(lib, "eglInitialize");
    eglBindAPI_ = (BindAPIFn) dlsym(lib, "eglBindAPI");
    eglChooseConfig_ = (ChooseConfigFn) dlsym(lib, "eglChooseConfig");
    eglCreateContext_ = (CreateContextFn) dlsym(lib, "eglCreateContext");
    eglMakeCurrent_ = (MakeCurrentFn) dlsym(lib, "eglMakeCurrent");
    eglGetCurrentContext_ = (GetCurrentContextFn) dlsym(lib, "eglGetCurrentContext");
    eglDestroyContext_ = (DestroyContextFn) dlsym(lib, "eglDestroyContext");
    eglTerminate_ = (TerminateFn) dlsym(lib, "eglTerminate");
    eglReleaseThread_ = (ReleaseThreadFn) dlsym(lib, "eglReleaseThread");

    for (int i = 0; mode[i] != '\0'; i++) {
        Dialect dialect = mode[i] == 'G' ? GLES : DESKTOP;
        char tag[32];
        snprintf(tag, sizeof(tag), "ctx%d:%s", i + 1, dialect == GLES ? "GLES" : "DESKTOP");
        fprintf(stderr, "=== creating %s ===\n", tag);
        Ctx c = create_context(dialect);
        fprintf(stderr, "=== %s current, EGLDisplay=%p EGLContext=%p ===\n", tag, c.display, c.context);
        if (use_gl) {
            for (int rep = 0; rep < repeats_per_context; rep++) {
                char reptag[48];
                snprintf(reptag, sizeof(reptag), "%s/rep%d", tag, rep + 1);
                run_shader_dialect_matrix(dialect, reptag);
            }
        }
        fprintf(stderr, "=== destroying %s (release_thread=%d) ===\n", tag, release_thread);
        destroy_context(c, release_thread);
    }
    fprintf(stderr, "=== ALL CONTEXTS COMPLETED WITHOUT CRASH ===\n");
    return 0;
}
```

## Build and run commands

```
docker run --rm --platform linux/arm64 -v "$PWD:/repro" ubuntu:24.04 bash -c '
  apt-get update -qq
  apt-get install -y --no-install-recommends libegl1 libegl-mesa0 libgles2 gcc libc6-dev
  cd /repro
  gcc -O0 -g -o repro repro.c -ldl
  ./repro GD 1 0
  echo "exit: $?"
'
```

`mode` is a string of `G`/`D` characters, one EGL context created (and torn down) per character in
order, left to right — `GD` means "create a GLES context, run the workload, destroy it; then create a
DESKTOP context, run the workload, destroy it." `use_gl=1` runs `run_shader_dialect_matrix` on each
context; `use_gl=0` only churns contexts with no GL calls. The same binary and command reproduce the
crash unmodified under `--platform linux/amd64` (QEMU) on the same host.

## The crash

`GD` (one GLES context, then one DESKTOP context, in that order) reliably SIGSEGVs during the second
context's cross-dialect `glLinkProgram` call — the desktop context accepting `#version 300 es` under its
`GL_ARB_ES3_compatibility` extension:

```
=== creating ctx1:GLES ===
=== ctx1:GLES current, EGLDisplay=0x... EGLContext=0x... ===
[ctx1:GLES/rep1] compiling matching-dialect pair
[ctx1:GLES/rep1] linking matching-dialect program
[ctx1:GLES/rep1] matching link status=1
[ctx1:GLES/rep1] compiling OPPOSITE-dialect pair (negative path)
[ctx1:GLES/rep1] linking OPPOSITE-dialect program (this is where the reported crash occurs)
[ctx1:GLES/rep1] survived opposite link
[ctx1:GLES/rep1] opposite link status=0
=== destroying ctx1:GLES (release_thread=0) ===
=== creating ctx2:DESKTOP ===
=== ctx2:DESKTOP current, EGLDisplay=0x... EGLContext=0x... ===
[ctx2:DESKTOP/rep1] compiling matching-dialect pair
[ctx2:DESKTOP/rep1] linking matching-dialect program
[ctx2:DESKTOP/rep1] matching link status=1
[ctx2:DESKTOP/rep1] compiling OPPOSITE-dialect pair (negative path)
[ctx2:DESKTOP/rep1] linking OPPOSITE-dialect program (this is where the reported crash occurs)
Segmentation fault
```

A `gdb` backtrace on the crashing process places frame 0 inside `libgallium-25.2.8-0ubuntu0.24.04.2.so`
(stripped, no symbols in the distro package), called directly from the process's `linkProgram(p2)` call
— the deliberate cross-dialect link shown above. Thread accounting at crash time shows llvmpipe's
rasterizer thread pool being cleanly spawned and torn down between the two contexts, with no leaked
threads, which points at an internal compiler data structure (a static/global symbol table, IR allocator,
or version-compatibility cache) not being correctly reset when a new gallium screen is created after an
old one has been destroyed, rather than an externally visible resource-management error.

Suggested `gdb` invocation:

```
gdb -batch -ex 'set pagination off' -ex 'run GD 1 0' -ex 'bt full' -ex 'thread apply all bt' -ex quit ./repro
```

## Crash-rate matrix

All counts are `crashes/n`, gathered by running the binary above in a loop inside one long-lived
container and counting exit codes ≥128 (signal death) — see "A note on reproducibility" below for an
important caveat about this methodology:

| Sequence | With the cross-dialect link | Cross-dialect link skipped (matching-dialect only, `REPRO_SKIP_OPPOSITE=1`) |
|---|---|---|
| `G` (one GLES context, alone) | 0/20 | — |
| `D` (one DESKTOP context, alone) | 0/20 | — |
| `GG` | 15/15 | — |
| `DD` | 0/15 | — |
| `GD` (one of each, in order) | 29/30 | 0/20 |
| `DG` (one of each, reverse order) | 20/20 | — |
| `GDG` | 15/15 | — |
| `DGD` | 10/10 | — |
| `GGG` | 10/10 | — |
| `DDD`, and `DD` ×10 | 0/10, 0/5 | — |
| The negative link repeated 2–5× inside one never-recreated context (either dialect) | 0/10 each | — |

## A note on reproducibility and environment sensitivity

The crash is memory-layout-sensitive rather than perfectly deterministic — a full OS-process fork (no
`exec`) still crashed once in one trial on the very first context created in the very first forked child,
while the same single-context workload run as a genuinely fresh `exec`'d process never crashed in 20/20
trials, which is a signature of ASLR/heap-state-dependent corruption. Separately, during independent
verification of a proposed workaround, we observed that a container that has already run several prior
trials (i.e., an environment where Mesa's on-disk shader cache directory already has entries from earlier
processes) reproduces the two-context `GD`/`DG` sequences far more reliably than a freshly created
container running the identical binary and command for the first time; the `GG`/`GDG`/`GGG` (2 or more
GLES-profile contexts) rows appear robust regardless of container/cache freshness. We have not
root-caused this environment sensitivity and are not asserting a mechanism for it (possibly:
`MESA_SHADER_CACHE_DISABLE`/`$XDG_CACHE_HOME` state, possibly something else); we are flagging it because
it may materially affect how reliably others can reproduce the two-context cases specifically, and
because it means the matrix above (gathered with trials looped inside one long-lived container, matching
how the matrix was originally produced) may overstate the crash rate of a single fresh invocation of the
`GD`/`DG` sequences specifically. The `GG`-family rows (2+ GLES contexts) were not observed to be
sensitive to this in our follow-up checks.

## Order-independence and process-isolation resistance

- `GD` and `DG` both crash at effectively the same rate — reordering which profile is created first does
  not avoid it.
- Extra EGL teardown discipline does not avoid it: adding `eglReleaseThread()` after every context
  destroy still crashes at the same rate; omitting `eglTerminate()` entirely (leaving the process-wide
  surfaceless display permanently initialized) fixes the pure two-GLES-context case but not the
  GLES-then-DESKTOP case.
- Full OS-process isolation (forking a fresh child per context) does not reliably avoid it either — see
  the reproducibility note above.
- A single test/work unit that itself creates one context of each profile in its own body (analogous to
  `GD` inside one function) crashes at effectively the same rate as when that logic is spread across
  separate top-level invocations in the same process — the trigger is about how many contexts of each
  profile a process has created over its lifetime, not about test/function boundaries.
