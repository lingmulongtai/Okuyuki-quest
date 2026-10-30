package com.okuyuki.quest

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import com.okuyuki.protocol.FrameCorrectionMatcher
import com.okuyuki.protocol.FrameTimestampMap
import com.okuyuki.protocol.FrameTimestampToken
import com.okuyuki.protocol.OsmoStabilization
import com.okuyuki.protocol.OsmoDecodedFrame
import com.okuyuki.protocol.OsmoFramePairer
import com.okuyuki.protocol.OsmoPairAction
import com.okuyuki.protocol.PanoramaLensCalibration
import com.okuyuki.protocol.PanoramaStereoCalibration
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Resizes the SDK-owned Surface's producer buffers without taking ownership of it. */
internal object OsmoProjectionSurface {
    init { System.loadLibrary("okuyuki_projection") }
    external fun setBufferSize(surface: Surface, width: Int, height: Int): Boolean
}

/** Attempt the requested output size first and retain the proven-size fallback. */
internal fun selectProjectionSize(highResolution: Boolean, create: (Int, Int) -> Boolean): Pair<Int, Int>? {
    if (highResolution && create(4096, 2048)) return 4096 to 2048
    return if (create(2048, 1024)) 2048 to 1024 else null
}

/** Converts raw Osmo fisheye frames into the equirectangular surface used by Spatial SDK. */
internal class OsmoSphericalRenderer(
    private val output: Surface,
    private val proxy: Boolean,
    private val calibration: PanoramaStereoCalibration?,
    private val stabilization: OsmoStabilization?,
    private val highResolution: Boolean,
    private val timestamps: Array<FrameTimestampMap>,
    private val onReady: (List<Surface>) -> Unit,
    private val onError: () -> Unit,
    private val onSynchronizationFailure: () -> Unit,
    private val onStabilizationUnavailable: () -> Unit,
    private val onQualityFallback: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("Osmo sphere GPU").apply { start() }
    private val handler = Handler(thread.looper)
    @Volatile private var closed = false
    @Volatile private var failed = false
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private val ids = IntArray(2)
    private val textures = arrayOfNulls<SurfaceTexture>(2)
    private val surfaces = arrayOfNulls<Surface>(2)
    private val matrices = Array(2) { FloatArray(16) }
    private val pending = BooleanArray(2)
    private val received = BooleanArray(2)
    private var visible = false
    private var stabilizationEnabled = stabilization != null
    private val frameMatcher = if (proxy) stabilization?.let { FrameCorrectionMatcher(timestamps[0], it) } else null
    private val pairer = if (proxy) null else OsmoFramePairer().apply { reset() }
    private var pendingPresentation: OsmoPairAction.Present? = null
    private var lastPairAtMs = 0L
    private var expectFrames = true
    private var drained = false
    private var endingTokens: List<FrameTimestampToken>? = null
    private val lastLatched = LongArray(2) { Long.MIN_VALUE }
    private var lastPresentedPts: Long? = null
    private val watchdog = object : Runnable {
        override fun run() {
            if (!closed && !failed && visible && !proxy && expectFrames) {
                if (SystemClock.elapsedRealtime() - lastPairAtMs >= 5000L) { fail(synchronization = true); return }
                handler.postDelayed(this, 500)
            }
        }
    }
    private val proxyRetry = Runnable {
        if (!closed && !failed && visible) try { drawProxy() } catch (_: Throwable) { fail() }
    }
    private val count = if (proxy) 1 else 2
    private val quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0)
    }

    init { handler.post { try { initialize() } catch (_: Throwable) { fail() } } }

    private fun initialize() {
        check(!closed && output.isValid)
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1))
        val configs = arrayOfNulls<EGLConfig>(1); val countOut = IntArray(1)
        val attrs = intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL14.EGL_NONE)
        check(EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, countOut, 0) && countOut[0] == 1)
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT)
        val size = checkNotNull(selectProjectionSize(highResolution && !proxy) { width, height ->
            createOutputSurface(requireNotNull(configs[0]), width, height)
        })
        if (highResolution && !proxy && size.first < 4096) main.post { if (!closed && !failed) onQualityFallback() }
        check(GLES20.glGetString(GLES20.GL_EXTENSIONS)?.split(' ')?.contains("GL_OES_EGL_image_external") == true)
        program = linkProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        GLES20.glViewport(0, 0, size.first, size.second)
        GLES20.glGenTextures(count, ids, 0)
        repeat(count) { index ->
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[index])
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            textures[index] = SurfaceTexture(ids[index]).also { texture ->
                texture.setOnFrameAvailableListener({
                    if (!closed) {
                        try {
                            if (proxy) { pending[index] = true; drawProxy() }
                            else { pairer!!.frameAvailable(index, pairer.generation()); pumpPairs() }
                        } catch (_: Throwable) { fail() }
                    }
                }, handler)
                surfaces[index] = Surface(texture)
            }
        }
        main.post { if (!closed && !failed) onReady((0 until count).map { requireNotNull(surfaces[it]) }) }
    }

    private fun createOutputSurface(config: EGLConfig, width: Int, height: Int): Boolean {
        if (!OsmoProjectionSurface.setBufferSize(output, width, height)) return false
        val candidate = EGL14.eglCreateWindowSurface(display, config, output, intArrayOf(EGL14.EGL_NONE), 0)
        if (candidate == EGL14.EGL_NO_SURFACE) { EGL14.eglGetError(); return false }
        val current = EGL14.eglMakeCurrent(display, candidate, candidate, context)
        val actualWidth = IntArray(1); val actualHeight = IntArray(1); val viewport = IntArray(2)
        val dimensions = current && EGL14.eglQuerySurface(display, candidate, EGL14.EGL_WIDTH, actualWidth, 0) &&
            EGL14.eglQuerySurface(display, candidate, EGL14.EGL_HEIGHT, actualHeight, 0)
        if (dimensions) GLES20.glGetIntegerv(GLES20.GL_MAX_VIEWPORT_DIMS, viewport, 0)
        if (dimensions && actualWidth[0] == width && actualHeight[0] == height && viewport[0] >= width && viewport[1] >= height) {
            GLES20.glViewport(0, 0, width, height)
            GLES20.glClearColor(0f, 0f, 0f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            if (GLES20.glGetError() == GLES20.GL_NO_ERROR && EGL14.eglSwapBuffers(display, candidate)) {
                window = candidate
                return true
            }
        }
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, candidate)
        EGL14.eglGetError()
        return false
    }

    fun setVisible(value: Boolean) {
        handler.post {
            if (!closed && visible != value) {
                visible = value
                handler.removeCallbacks(watchdog)
                if (visible) {
                    lastPairAtMs = SystemClock.elapsedRealtime(); expectFrames = !drained
                    if (!proxy && expectFrames) handler.postDelayed(watchdog, 500)
                    try { if (proxy) drawProxy() else pumpPairs() } catch (_: Throwable) { fail() }
                }
            }
        }
    }

    /** Called after both decoders end; late SurfaceTexture callbacks still have to drain. */
    fun finishWhenEnded(tokens: List<FrameTimestampToken?>) {
        handler.post {
            if (closed || failed) return@post
            if (proxy) { drained = true; expectFrames = false; handler.removeCallbacks(watchdog); return@post }
            if (tokens.size != 2 || tokens.any { it?.presentationUs == null } ||
                tokens[0]?.presentationUs != tokens[1]?.presentationUs) { fail(synchronization = true); return@post }
            endingTokens = tokens.filterNotNull()
            lastPairAtMs = SystemClock.elapsedRealtime()
            checkDrainComplete()
        }
    }
    private fun checkDrainComplete() {
        val final = endingTokens ?: return
        if (lastPresentedPts == final[0].presentationUs && lastLatched[0] == final[0].releaseNs && lastLatched[1] == final[1].releaseNs) {
            drained = true; expectFrames = false; handler.removeCallbacks(watchdog)
        }
    }

    private fun drawProxy() {
        if (pending[0]) {
            val frame = requireNotNull(textures[0]); frame.updateTexImage(); frame.getTransformMatrix(matrices[0])
            pending[0] = false; received[0] = true
        }
        if (!visible || !received[0]) return
        val matched = frameMatcher?.frame(requireNotNull(textures[0]).timestamp)
        handler.removeCallbacks(proxyRetry)
        if (stabilizationEnabled && matched?.waiting == true) {
            handler.postDelayed(proxyRetry, 100)
            return
        }
        if (stabilizationEnabled && matched?.waiting != true && matched?.correction == null) {
            stabilizationEnabled = false
            main.post { if (!closed && !failed) onStabilizationUnavailable() }
        }
        drawFrame(if (stabilizationEnabled) matched?.correction else null)
    }

    private fun pumpPairs() {
        val coordinator = requireNotNull(pairer)
        repeat(16) {
            val pendingPair = pendingPresentation
            if (pendingPair != null) {
                if (!visible) return
                val correction = if (stabilizationEnabled) stabilization?.correctionAt(pendingPair.presentationTimeUs) else null
                if (stabilizationEnabled && correction == null) {
                    stabilizationEnabled = false
                    main.post { if (!closed && !failed) onStabilizationUnavailable() }
                }
                drawFrame(correction)
                coordinator.presented(pendingPair.presentationTimeUs, pendingPair.generation)
                pendingPresentation = null
                lastPresentedPts = pendingPair.presentationTimeUs
                lastPairAtMs = SystemClock.elapsedRealtime()
                checkDrainComplete()
            }
            when (val action = coordinator.next()) {
                is OsmoPairAction.Latch -> {
                    val frame = requireNotNull(textures[action.side])
                    frame.updateTexImage(); frame.getTransformMatrix(matrices[action.side])
                    val pts = timestamps[action.side].lookup(frame.timestamp)
                    if (pts == null) coordinator.discarded(action.side, action.generation)
                    else {
                        coordinator.latched(action.side, action.generation, OsmoDecodedFrame(frame.timestamp, pts))
                        lastLatched[action.side] = frame.timestamp
                    }
                }
                is OsmoPairAction.Present -> pendingPresentation = action
                OsmoPairAction.Wait, OsmoPairAction.Ended -> return
                OsmoPairAction.Invalid -> { fail(synchronization = true); return }
            }
        }
        handler.post { if (!closed && !failed) try { pumpPairs() } catch (_: Throwable) { fail() } }
    }

    private fun drawFrame(correction: FloatArray?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        val position = GLES20.glGetAttribLocation(program, "position")
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glUniform4fv(GLES20.glGetUniformLocation(program, "motion"), 1, correction ?: floatArrayOf(1f, 0f, 0f, 0f), 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "proxy"), if (proxy) 1f else 0f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "calibrated"), if (calibration == null) 0f else 1f)
        fun setLens(prefix: String, lens: PanoramaLensCalibration) {
            GLES20.glUniform4fv(GLES20.glGetUniformLocation(program, prefix + "Intrinsics"), 1, lens.intrinsics.map { it / 3840f }.toFloatArray(), 0)
            GLES20.glUniform4fv(GLES20.glGetUniformLocation(program, prefix + "Distortion"), 1, lens.distortion.toFloatArray(), 0)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, prefix + "K5"), lens.distortion[4])
            GLES20.glUniform4fv(GLES20.glGetUniformLocation(program, prefix + "Quaternion"), 1, lens.quaternion.toFloatArray(), 0)
        }
        calibration?.let { setLens("left", it.left); setLens("right", it.right) }
        repeat(2) { index ->
            val source = if (proxy) 0 else index
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "texMatrix$index"), 1, false, matrices[source], 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + index)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[source])
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "video$index"), index)
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position)
        check(EGL14.eglSwapBuffers(display, window))
    }

    private fun fail(synchronization: Boolean = false) {
        if (!closed && !failed) {
            failed = true
            main.post { if (!closed) { if (synchronization) onSynchronizationFailure() else onError() } }
            cleanup()
        }
    }
    fun close() { if (!closed) { closed = true; handler.post { cleanup() } } }
    private fun cleanup() {
        handler.removeCallbacks(watchdog)
        handler.removeCallbacks(proxyRetry)
        repeat(count) { index -> surfaces[index]?.release(); surfaces[index] = null; textures[index]?.release(); textures[index] = null }
        if (display != EGL14.EGL_NO_DISPLAY) {
            if (program != 0) GLES20.glDeleteProgram(program)
            GLES20.glDeleteTextures(count, ids, 0)
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            // EGL_DEFAULT_DISPLAY is shared with Spatial SDK; do not terminate it.
            EGL14.eglReleaseThread()
            display = EGL14.EGL_NO_DISPLAY
        }
        thread.quitSafely()
    }

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec2 position;
            varying vec2 screen;
            void main() { screen = position; gl_Position = vec4(position, 0.0, 1.0); }
        """
        internal const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            varying vec2 screen;
            uniform samplerExternalOES video0;
            uniform samplerExternalOES video1;
            uniform mat4 texMatrix0;
            uniform mat4 texMatrix1;
            uniform float proxy;
            uniform float calibrated;
            uniform vec4 motion;
            uniform vec4 leftIntrinsics, rightIntrinsics;
            uniform vec4 leftDistortion, rightDistortion;
            uniform float leftK5, rightK5;
            uniform vec4 leftQuaternion, rightQuaternion;
            vec3 rotateQuaternion(vec4 q, vec3 v) {
                return v + 2.0 * cross(q.yzw, cross(q.yzw, v) + q.x * v);
            }
            void main() {
                float longitude = screen.x * 3.14159265359;
                float latitude = screen.y * 1.57079632679;
                vec3 ray = vec3(sin(longitude) * cos(latitude), sin(latitude), cos(longitude) * cos(latitude));
                vec3 body = rotateQuaternion(motion, vec3(-ray.x, -ray.z, ray.y));
                ray = vec3(-body.x, body.z, -body.y);
                bool back = body.y > 0.0;
                if (calibrated > 0.5) {
                    vec4 intr = back ? rightIntrinsics : leftIntrinsics;
                    vec4 k = back ? rightDistortion : leftDistortion;
                    float k5 = back ? rightK5 : leftK5;
                    vec4 q = back ? rightQuaternion : leftQuaternion;
                    vec3 lens = rotateQuaternion(q, body);
                    float xy = length(lens.xy);
                    float theta = atan(xy, lens.z);
                    float t2 = theta * theta;
                    float radius = theta * (1.0 + t2 * (k.x + t2 * (k.y + t2 * (k.z + t2 * (k.w + t2 * k5)))));
                    vec2 unit = xy > 0.0001 ? lens.xy / xy : vec2(0.0);
                    vec2 sensor = intr.zw + intr.xy * radius * unit;
                    if (sensor.x < 0.0 || sensor.x > 1.0 || sensor.y < 0.0 || sensor.y > 1.0) {
                        gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0); return;
                    }
                    vec2 uv = vec2(proxy > 0.5 ? (sensor.x + (back ? 1.0 : 0.0)) * 0.5 : sensor.x, 1.0 - sensor.y);
                    if (back && proxy < 0.5) gl_FragColor = texture2D(video1, (texMatrix1 * vec4(uv, 0.0, 1.0)).xy);
                    else gl_FragColor = texture2D(video0, (texMatrix0 * vec4(uv, 0.0, 1.0)).xy);
                    return;
                }
                vec2 plane = vec2(back ? -ray.x : ray.x, ray.y);
                float theta = acos(clamp(abs(ray.z), 0.0, 1.0));
                float lengthXY = length(plane);
                vec2 radial = lengthXY > 0.0001 ? plane / lengthXY * (theta / 1.7453293) : vec2(0.0);
                vec2 uv = proxy > 0.5 ? vec2((back ? 0.75 : 0.25) + radial.x * 0.25, 0.5 + radial.y * 0.5) : vec2(0.5) + radial * 0.5;
                if (back && proxy < 0.5) gl_FragColor = texture2D(video1, (texMatrix1 * vec4(uv, 0.0, 1.0)).xy);
                else gl_FragColor = texture2D(video0, (texMatrix0 * vec4(uv, 0.0, 1.0)).xy);
            }
                """
        private fun linkProgram(vertex: String, fragment: String): Int {
            fun compile(type: Int, source: String): Int {
                val shader = GLES20.glCreateShader(type)
                GLES20.glShaderSource(shader, source); GLES20.glCompileShader(shader)
                val result = IntArray(1); GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, result, 0)
                check(result[0] != 0) { GLES20.glGetShaderInfoLog(shader) }
                return shader
            }
            val vs = compile(GLES20.GL_VERTEX_SHADER, vertex); val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragment)
            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vs); GLES20.glAttachShader(program, fs); GLES20.glLinkProgram(program)
            GLES20.glDeleteShader(vs); GLES20.glDeleteShader(fs)
            val result = IntArray(1); GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, result, 0)
            check(result[0] != 0) { GLES20.glGetProgramInfoLog(program) }
            return program
        }
    }
}
