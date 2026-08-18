// Throwaway spike: can a GitHub macos-latest runner create a headless GL
// context at all, and under which pixel format? Answers ADR 0011 (can the
// conformance suite run in CI on macosArm64) and re-measures ADR 0008 and
// ADR 0006 there. Not part of RenG; never committed to main.
#define GL_SILENCE_DEPRECATION 1
#include <OpenGL/OpenGL.h>
#include <OpenGL/gl3.h>
#include <stdio.h>
#include <string.h>

static int try_shader(const char *label, const char *src) {
    GLuint s = glCreateShader(GL_FRAGMENT_SHADER);
    glShaderSource(s, 1, &src, NULL);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    char log[1024] = {0};
    GLsizei n = 0;
    glGetShaderInfoLog(s, sizeof(log) - 1, &n, log);
    for (GLsizei i = 0; i < n; i++) if (log[i] == '\n') log[i] = ' ';
    printf("  SHADER %-14s compile=%s%s%s\n", label, ok ? "OK" : "FAIL",
           n > 0 ? " log=" : "", n > 0 ? log : "");
    glDeleteShader(s);
    return ok;
}

// Each candidate is a full attribute list. The point is to find the WEAKEST
// requirement a CI runner will satisfy, not to insist on the strongest.
static CGLContextObj attempt(const char *name, CGLPixelFormatAttribute *attrs) {
    CGLPixelFormatObj pix = NULL;
    GLint npix = 0;
    CGLError e = CGLChoosePixelFormat(attrs, &pix, &npix);
    if (e != kCGLNoError || pix == NULL) {
        printf("ATTEMPT %-28s pixelformat=FAIL cgl=%d (%s)\n", name, e, CGLErrorString(e));
        return NULL;
    }
    CGLContextObj ctx = NULL;
    e = CGLCreateContext(pix, NULL, &ctx);
    CGLDestroyPixelFormat(pix);
    if (e != kCGLNoError || ctx == NULL) {
        printf("ATTEMPT %-28s pixelformat=ok create=FAIL cgl=%d (%s)\n", name, e, CGLErrorString(e));
        return NULL;
    }
    printf("ATTEMPT %-28s pixelformat=ok create=ok npix=%d\n", name, npix);
    return ctx;
}

int main(void) {
    CGLPixelFormatAttribute accel_core4[] = {
        kCGLPFAAccelerated, kCGLPFAOpenGLProfile,
        (CGLPixelFormatAttribute)kCGLOGLPVersion_GL4_Core,
        kCGLPFAColorSize, (CGLPixelFormatAttribute)24,
        kCGLPFADepthSize, (CGLPixelFormatAttribute)24, (CGLPixelFormatAttribute)0,
    };
    CGLPixelFormatAttribute core4[] = {
        kCGLPFAOpenGLProfile, (CGLPixelFormatAttribute)kCGLOGLPVersion_GL4_Core,
        kCGLPFAColorSize, (CGLPixelFormatAttribute)24,
        kCGLPFADepthSize, (CGLPixelFormatAttribute)24, (CGLPixelFormatAttribute)0,
    };
    CGLPixelFormatAttribute core32[] = {
        kCGLPFAOpenGLProfile, (CGLPixelFormatAttribute)kCGLOGLPVersion_3_2_Core,
        kCGLPFAColorSize, (CGLPixelFormatAttribute)24,
        kCGLPFADepthSize, (CGLPixelFormatAttribute)24, (CGLPixelFormatAttribute)0,
    };
    CGLPixelFormatAttribute core4_software[] = {
        kCGLPFARendererID, (CGLPixelFormatAttribute)kCGLRendererGenericFloatID,
        kCGLPFAOpenGLProfile, (CGLPixelFormatAttribute)kCGLOGLPVersion_GL4_Core,
        (CGLPixelFormatAttribute)0,
    };
    CGLPixelFormatAttribute core32_software[] = {
        kCGLPFARendererID, (CGLPixelFormatAttribute)kCGLRendererGenericFloatID,
        kCGLPFAOpenGLProfile, (CGLPixelFormatAttribute)kCGLOGLPVersion_3_2_Core,
        (CGLPixelFormatAttribute)0,
    };
    CGLPixelFormatAttribute minimal[] = { (CGLPixelFormatAttribute)0 };

    struct { const char *name; CGLPixelFormatAttribute *attrs; } ladder[] = {
        {"accelerated+4.1core", accel_core4},
        {"4.1core (no accel req)", core4},
        {"3.2core (no accel req)", core32},
        {"4.1core software renderer", core4_software},
        {"3.2core software renderer", core32_software},
        {"minimal (legacy profile)", minimal},
    };

    CGLContextObj ctx = NULL;
    const char *winner = NULL;
    for (size_t i = 0; i < sizeof(ladder) / sizeof(ladder[0]); i++) {
        ctx = attempt(ladder[i].name, ladder[i].attrs);
        if (ctx) { winner = ladder[i].name; break; }
    }
    if (!ctx) {
        printf("RESULT context=UNAVAILABLE every_candidate_failed=yes\n");
        return 2;
    }

    CGLSetCurrentContext(ctx);
    printf("RESULT context=CREATED via=\"%s\" headless=yes\n", winner);
    printf("GL_VERSION=%s\n", (const char *)glGetString(GL_VERSION));
    printf("GL_RENDERER=%s\n", (const char *)glGetString(GL_RENDERER));
    printf("GL_VENDOR=%s\n", (const char *)glGetString(GL_VENDOR));
    printf("GL_SHADING_LANGUAGE_VERSION=%s\n", (const char *)glGetString(GL_SHADING_LANGUAGE_VERSION));

    const char *body =
        "precision mediump float;\nin vec2 uv;\nout vec4 c;\nuniform sampler2D t;\n"
        "uniform int n;\nvoid main() { c = texture(t, uv) * float(n); }\n";
    char es300[512], core330[512];
    snprintf(es300, sizeof(es300), "#version 300 es\n%s", body);
    snprintf(core330, sizeof(core330), "#version 330 core\n%s", body);
    int a = try_shader("300-es", es300);
    int b = try_shader("330-core-sub", core330);
    printf("ADR0008 unsubstituted_fails=%s substituted_compiles=%s\n",
           a ? "no" : "yes", b ? "yes" : "no");

    GLint vp[4] = {0}, fbo = -1, prog = -1, vao = -1, packa = -1, unpacka = -1;
    GLboolean depth = 0, blend = 0, cmask[4] = {0};
    glGetIntegerv(GL_VIEWPORT, vp);
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &fbo);
    glGetIntegerv(GL_CURRENT_PROGRAM, &prog);
    glGetIntegerv(GL_VERTEX_ARRAY_BINDING, &vao);
    glGetIntegerv(GL_PACK_ALIGNMENT, &packa);
    glGetIntegerv(GL_UNPACK_ALIGNMENT, &unpacka);
    glGetBooleanv(GL_DEPTH_TEST, &depth);
    glGetBooleanv(GL_BLEND, &blend);
    glGetBooleanv(GL_COLOR_WRITEMASK, cmask);
    printf("ADR0006 vp=%d,%d,%d,%d fbo=%d prog=%d vao=%d pack=%d unpack=%d depth=%d blend=%d cmask=%d%d%d%d err=0x%x\n",
           vp[0], vp[1], vp[2], vp[3], fbo, prog, vao, packa, unpacka,
           depth, blend, cmask[0], cmask[1], cmask[2], cmask[3], glGetError());

    CGLSetCurrentContext(NULL);
    CGLDestroyContext(ctx);
    return 0;
}
