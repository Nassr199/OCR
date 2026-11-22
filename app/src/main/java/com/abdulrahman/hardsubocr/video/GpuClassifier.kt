package com.abdulrahman.hardsubocr.video

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLES31
import com.abdulrahman.hardsubocr.core.LumaGrid
import com.abdulrahman.hardsubocr.core.ScanParams
import com.abdulrahman.hardsubocr.core.SubStyle
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * GPU (OpenGL ES 3.1 compute) version of [com.abdulrahman.hardsubocr.core.ScanMask.classify] — the
 * per-pixel stage of the text detector: local contrast (3×3 min), thin-stroke (ridge) test and
 * strong/weak classification. Runs natively on Adreno (and Mali / PowerVR / Xclipse) without NDK.
 *
 * Integer-only arithmetic, so the result is bit-identical to the CPU version; the scanner still
 * compares both on the first frames and switches the GPU off on the slightest difference or error.
 * The GL context lives on its own thread; [classify] may be called from any worker thread.
 */
class GpuClassifier private constructor(private val gl: ExecutorService) {

    var renderer: String = ""
        private set
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var bufIn = 0
    private var bufOut = 0
    private var capacity = 0
    private var locSize = -1
    private var locTh = -1
    private var locMode = -1

    private fun init(): Boolean {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return false
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) return false
        val attribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) || num[0] < 1) return false
        val cfg = configs[0] ?: return false
        context = EGL14.eglCreateContext(display, cfg, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        if (context == EGL14.EGL_NO_CONTEXT) return false
        surface = EGL14.eglCreatePbufferSurface(display, cfg, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        if (surface == EGL14.EGL_NO_SURFACE) return false
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return false
        val version = GLES20.glGetString(GLES20.GL_VERSION) ?: ""
        renderer = (GLES20.glGetString(GLES20.GL_RENDERER) ?: "GPU") + " • " + version
        // compute shaders need OpenGL ES 3.1+
        val es = Regexless.esVersion(version)
        if (es < 31) return false

        val sh = GLES20.glCreateShader(GLES31.GL_COMPUTE_SHADER)
        GLES20.glShaderSource(sh, SHADER)
        GLES20.glCompileShader(sh)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            renderer += " (shader: " + GLES20.glGetShaderInfoLog(sh) + ")"
            GLES20.glDeleteShader(sh)
            return false
        }
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, sh)
        GLES20.glLinkProgram(program)
        GLES20.glDeleteShader(sh)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) return false
        locSize = GLES20.glGetUniformLocation(program, "uSize")
        locTh = GLES20.glGetUniformLocation(program, "uTh")
        locMode = GLES20.glGetUniformLocation(program, "uMode")
        val ids = IntArray(2)
        GLES20.glGenBuffers(2, ids, 0)
        bufIn = ids[0]; bufOut = ids[1]
        return GLES20.glGetError() == GLES20.GL_NO_ERROR
    }

    private fun ensure(bytes: Int) {
        if (bytes <= capacity) return
        capacity = bytes
        GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, bufIn)
        GLES20.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, capacity, null, GLES20.GL_DYNAMIC_DRAW)
        GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, bufOut)
        GLES20.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, capacity, null, GLES30.GL_DYNAMIC_READ)
    }

    private fun run(g: LumaGrid, p: ScanParams): ByteArray {
        val n = g.width * g.height
        val words = (n + 3) / 4
        ensure(words * 4)
        val inBuf = ByteBuffer.allocateDirect(words * 4).order(ByteOrder.nativeOrder())
        inBuf.put(g.data, 0, n)
        inBuf.position(0)
        GLES20.glUseProgram(program)
        GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, bufIn)
        GLES20.glBufferSubData(GLES31.GL_SHADER_STORAGE_BUFFER, 0, words * 4, inBuf)
        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, bufIn)
        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, bufOut)
        GLES20.glUniform4i(locSize, g.width, g.height, n, p.ridgeRadius)
        GLES20.glUniform4i(locTh, p.brightHigh, p.contrastHigh, p.brightLow, p.contrastLow)
        GLES20.glUniform2i(locMode, if (p.style == SubStyle.DARK) 1 else 0, p.radius.coerceAtLeast(1))
        GLES31.glDispatchCompute((words + 63) / 64, 1, 1)
        GLES31.glMemoryBarrier(GLES31.GL_BUFFER_UPDATE_BARRIER_BIT or GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
        GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, bufOut)
        val mapped = GLES30.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0, words * 4, GLES30.GL_MAP_READ_BIT) as? ByteBuffer
            ?: throw IllegalStateException("GPU readback failed")
        val out = ByteArray(n)
        mapped.order(ByteOrder.nativeOrder()).get(out, 0, n)
        GLES30.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
        val err = GLES20.glGetError()
        if (err != GLES20.GL_NO_ERROR) throw IllegalStateException("GL error $err")
        return out
    }

    /** Same bytes as ScanMask.classify(g, p). Thread-safe (serialised on the GL thread). */
    fun classify(g: LumaGrid, p: ScanParams): ByteArray = gl.submit(Callable { run(g, p) }).get()

    fun close() {
        runCatching {
            gl.submit {
                runCatching {
                    if (program != 0) GLES20.glDeleteProgram(program)
                    if (bufIn != 0) GLES20.glDeleteBuffers(2, intArrayOf(bufIn, bufOut), 0)
                }
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                    EGL14.eglReleaseThread()
                    EGL14.eglTerminate(display)
                }
            }.get(2, TimeUnit.SECONDS)
        }
        gl.shutdownNow()
    }

    companion object {
        private const val EGL_OPENGL_ES3_BIT = 0x40

        /** Null when the device has no usable OpenGL ES 3.1 compute support; [why] gets the reason. */
        fun create(why: (String) -> Unit = {}): GpuClassifier? {
            val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "subscan-gpu").apply { isDaemon = true } }
            val g = GpuClassifier(exec)
            val ok = runCatching { exec.submit(Callable { g.init() }).get(5, TimeUnit.SECONDS) }
            if (ok.getOrNull() == true) return g
            why(ok.exceptionOrNull()?.message ?: "OpenGL ES 3.1 غير متاح (${g.renderer})")
            g.close()
            return null
        }

        /**
         * One invocation = 4 pixels (one packed uint), so writes never race. Mirrors ScanMask.classify:
         * v = luma (inverted for dark text); d = v - min3x3 (edges clamped); strong (2) when
         * v >= brightHigh && d >= contrastHigh && ridge(v - contrastHigh); weak (1) when v >= brightLow &&
         * d >= contrastLow. ridge = darker pixel left AND right, or above AND below, within r.
         */
        private const val SHADER = """#version 310 es
layout(local_size_x = 64) in;
layout(std430, binding = 0) readonly buffer In { uint lum[]; };
layout(std430, binding = 1) writeonly buffer Out { uint cls[]; };
uniform ivec4 uSize;   // w, h, n, ridgeRadius
uniform ivec4 uTh;     // brightHigh, contrastHigh, brightLow, contrastLow
uniform ivec2 uMode;   // invert, minRadius

int px(int x, int y) {
    int i = y * uSize.x + x;
    int v = int((lum[i >> 2] >> uint((i & 3) * 8)) & 255u);
    return uMode.x == 1 ? 255 - v : v;
}

bool ridge(int x, int y, int limit) {
    int w = uSize.x; int h = uSize.y; int r = uSize.w;
    bool a = false; bool b = false;
    for (int k = 1; k <= r; k++) {
        if (!a && x - k >= 0 && px(x - k, y) <= limit) a = true;
        if (!b && x + k < w && px(x + k, y) <= limit) b = true;
        if (a && b) return true;
    }
    a = false; b = false;
    for (int k = 1; k <= r; k++) {
        if (!a && y - k >= 0 && px(x, y - k) <= limit) a = true;
        if (!b && y + k < h && px(x, y + k) <= limit) b = true;
        if (a && b) return true;
    }
    return false;
}

void main() {
    int word = int(gl_GlobalInvocationID.x);
    int w = uSize.x; int h = uSize.y; int n = uSize.z; int mr = uMode.y;
    if (word * 4 >= n) return;
    uint outWord = 0u;
    for (int k = 0; k < 4; k++) {
        int i = word * 4 + k;
        if (i >= n) break;
        if (w < 3 || h < 3) continue;
        int x = i % w; int y = i / w;
        int c = px(x, y);
        uint cl = 0u;
        if (c >= uTh.z) {
            int m = 255;
            for (int yy = max(0, y - mr); yy <= min(h - 1, y + mr); yy++)
                for (int xx = max(0, x - mr); xx <= min(w - 1, x + mr); xx++)
                    m = min(m, px(xx, yy));
            int d = c - m;
            if (c >= uTh.x && d >= uTh.y && ridge(x, y, c - uTh.y)) cl = 2u;
            else if (d >= uTh.w) cl = 1u;
        }
        outWord |= cl << uint(k * 8);
    }
    cls[word] = outWord;
}
"""
    }
}

/** "OpenGL ES 3.2 V@…" → 32 (no regex: Android ICU regex differences bit us once). */
internal object Regexless {
    fun esVersion(s: String): Int {
        val i = s.indexOf("ES ")
        if (i < 0 || i + 6 > s.length) return 0
        val major = s[i + 3]
        val minor = s[i + 5]
        if (!major.isDigit() || !minor.isDigit()) return 0
        return (major - '0') * 10 + (minor - '0')
    }
}
