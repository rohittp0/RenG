// Throwaway spike: can a GitHub macos-latest runner create a headless
// core-profile GL context, and which shader dialects compile there?
// Answers ADR 0011 (conformance against real contexts in CI) and gives
// ADR 0008 CI-side evidence. Not part of RenG; never committed to main.
#include <OpenGL/OpenGL.h>
#include <OpenGL/gl3.h>
#include <stdio.h>

static int try_shader(const char *label, const char *src, GLenum stage) {
    GLuint s = glCreateShader(stage);
    glShaderSource(s, 1, &src, NULL);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    char log[2048] = {0};
    GLsizei n = 0;
    glGetShaderInfoLog(s, sizeof(log) - 1, &n, log);
    printf("SHADER %-18s compile=%s%s%s\n", label, ok ? "OK" : "FAIL",
           n > 0 ? " log=" : "", n > 0 ? log : "");
    glDeleteShader(s);
    return ok;
}

int main(void) {
    CGLPixelFormatAttribute attrs[] = {
        kCGLPFAAccelerated, kCGLPFAOpenGLProfile,
        (CGLPixelFormatAttribute)kCGLOGLPVersion_GL4_Core,
        kCGLPFAColorSize, (CGLPixelFormatAttribute)24,
        kCGLPFADepthSize, (CGLPixelFormatAttribute)24,
        (CGLPixelFormatAttribute)0,
    };
    CGLPixelFormatObj pix = NULL;
    GLint npix = 0;
    CGLError e = CGLChoosePixelFormat(attrs, &pix, &npix);
    if (e != kCGLNoError || pix == NULL) {
        printf("RESULT context=UNAVAILABLE CGLChoosePixelFormat=%d (%s)\n", e, CGLErrorString(e));
        return 2;
    }
    CGLContextObj ctx = NULL;
    e = CGLCreateContext(pix, NULL, &ctx);
    if (e != kCGLNoError || ctx == NULL) {
        printf("RESULT context=UNAVAILABLE CGLCreateContext=%d (%s)\n", e, CGLErrorString(e));
        return 3;
    }
    CGLSetCurrentContext(ctx);
    printf("RESULT context=CREATED headless=yes\n");
    printf("GL_VERSION=%s\n", (const char *)glGetString(GL_VERSION));
    printf("GL_RENDERER=%s\n", (const char *)glGetString(GL_RENDERER));
    printf("GL_VENDOR=%s\n", (const char *)glGetString(GL_VENDOR));
    printf("GL_SHADING_LANGUAGE_VERSION=%s\n", (const char *)glGetString(GL_SHADING_LANGUAGE_VERSION));

    // ADR 0008: the exact substitution claim, on CI hardware.
    const char *es300 =
        "#version 300 es\nprecision mediump float;\nin vec2 uv;\nout vec4 c;\n"
        "uniform sampler2D t;\nvoid main() { c = texture(t, uv); }\n";
    const char *core330 =
        "#version 330 core\nprecision mediump float;\nin vec2 uv;\nout vec4 c;\n"
        "uniform sampler2D t;\nvoid main() { c = texture(t, uv); }\n";
    int a = try_shader("300-es", es300, GL_FRAGMENT_SHADER);
    int b = try_shader("330-core-sub", core330, GL_FRAGMENT_SHADER);
    printf("ADR0008 unsubstituted_fails=%s substituted_compiles=%s\n",
           a ? "no" : "yes", b ? "yes" : "no");

    // ADR 0006: is the state RenG touches actually queryable here?
    GLint vp[4] = {0}, fbo = -1, tex = -1, prog = -1, vao = -1;
    GLboolean depth = 0, blend = 0;
    glGetIntegerv(GL_VIEWPORT, vp);
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &fbo);
    glGetIntegerv(GL_TEXTURE_BINDING_2D, &tex);
    glGetIntegerv(GL_CURRENT_PROGRAM, &prog);
    glGetIntegerv(GL_VERTEX_ARRAY_BINDING, &vao);
    glGetBooleanv(GL_DEPTH_TEST, &depth);
    glGetBooleanv(GL_BLEND, &blend);
    printf("ADR0006 queryable viewport=%d,%d,%d,%d fbo=%d tex2d=%d prog=%d vao=%d depth=%d blend=%d err=0x%x\n",
           vp[0], vp[1], vp[2], vp[3], fbo, tex, prog, vao, depth, blend, glGetError());

    CGLSetCurrentContext(NULL);
    CGLDestroyContext(ctx);
    CGLDestroyPixelFormat(pix);
    return 0;
}
