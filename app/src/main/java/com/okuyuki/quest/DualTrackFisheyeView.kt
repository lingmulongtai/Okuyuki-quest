package com.okuyuki.quest

import com.okuyuki.protocol.PanoramaStereoCalibration
import com.okuyuki.protocol.PanoramaLensCalibration

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import android.view.Surface
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import com.okuyuki.protocol.FrameTimestampMap
import com.okuyuki.protocol.OsmoDecodedFrame
import com.okuyuki.protocol.OsmoFramePairer
import com.okuyuki.protocol.OsmoPairAction
import com.okuyuki.protocol.OsmoStabilization
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReferenceArray
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Dual-track preview with optional validated camera calibration. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class DualTrackFisheyeView(context: Context, private val players: List<ExoPlayer>, private val calibration: PanoramaStereoCalibration? = null,
    private val stabilization: OsmoStabilization? = null, private val onStabilizationUnavailable: () -> Unit = {}, private val onError: () -> Unit) : GLSurfaceView(context) {
    private val renderer = FisheyeRenderer()
    private var lastX = 0f
    private var lastY = 0f
    @Volatile private var closed = false
    @Volatile private var framesVisible = false
    @Volatile private var stabilizationEnabled = false
    @Volatile private var resetting = false
    private val surfaces = arrayOfNulls<Surface>(2)
    private val textures = arrayOfNulls<SurfaceTexture>(2)
    private val currentTextures = AtomicReferenceArray<SurfaceTexture>(2)
    private val signals = Array(2) { AtomicBoolean(false) }
    private val freezeRequested = AtomicBoolean(false)
    private val frozen = AtomicBoolean(false)
    private val timestamps = Array(2) { FrameTimestampMap() }
    private val latchedTokens = Array(2) { AtomicLong(Long.MIN_VALUE) }
    private val presentedPts = AtomicLong(Long.MIN_VALUE)
    private val presentedCount = AtomicLong(0)
    private val surfaceGeneration = AtomicLong(0)
    private val metadataListeners = List(2) { i -> VideoFrameMetadataListener { pts, releaseNs, _, _ -> timestamps[i].record(releaseNs, pts) } }

    init {
        require(players.size == 2)
        players.forEachIndexed { i, player -> player.setVideoFrameMetadataListener(metadataListeners[i]) }
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = event.x; lastY = event.y; return true }
            MotionEvent.ACTION_MOVE -> {
                renderer.yaw -= (event.x - lastX) * 0.006f
                renderer.pitch = (renderer.pitch + (event.y - lastY) * 0.006f).coerceIn(-1.5f, 1.5f)
                lastX = event.x; lastY = event.y
                requestRender()
                return true
            }
            MotionEvent.ACTION_UP -> { performClick(); return true }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return false
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    fun setFrameVisibility(visible: Boolean) {
        if (framesVisible != visible) { framesVisible = visible; requestRender() }
    }

    fun setStabilizationEnabled(enabled: Boolean) { stabilizationEnabled = enabled; requestRender() }
    fun freezeAtPair() { freezeRequested.set(true); requestRender() }
    fun isFrozen(): Boolean = frozen.get()
    fun resumeFrames() { freezeRequested.set(false); frozen.set(false); requestRender() }

    fun frameCount(): Long = presentedCount.get()
    fun finalFramesPresented(): Boolean {
        val left = timestamps[0].latest() ?: return false
        val right = timestamps[1].latest() ?: return false
        val pts = left.presentationUs ?: return false
        return pts == right.presentationUs && pts == presentedPts.get() &&
            left.releaseNs == latchedTokens[0].get() && right.releaseNs == latchedTokens[1].get()
    }

    /** Reset on the GL thread before issuing a seek on the players. */
    fun resetForSeek(afterReset: () -> Unit) {
        resetting = true
        surfaceGeneration.incrementAndGet()
        players.forEach { it.clearVideoSurface() }
        queueEvent {
            renderer.reset(0L)
            try { renderer.replaceSurfaces(afterReset) } catch (_: RuntimeException) { post { if (!closed) onError() } }
        }
        requestRender()
    }

    /** Call on the UI thread after pausing the GL thread. */
    fun close() {
        closed = true
        surfaceGeneration.incrementAndGet()
        players.forEachIndexed { i, player -> player.clearVideoFrameMetadataListener(metadataListeners[i]); player.clearVideoSurface() }
        for (i in 0..1) {
            surfaces[i]?.release(); surfaces[i] = null
            textures[i]?.release(); textures[i] = null
        }
    }

    private inner class FisheyeRenderer : Renderer {
        @Volatile var yaw = 0f
        @Volatile var pitch = 0f
        private val quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0)
        }
        private var program = 0
        private val textureIds = IntArray(2)
        private val pairer = OsmoFramePairer()
        private var pendingPair: OsmoPairAction.Present? = null
        private var frameValid = false
        private var pairedIntact = false
        private var pairedPts: Long? = null
        private val frameBuffer = IntArray(1)
        private val frameTexture = IntArray(1)
        private var copyProgram = 0
        private var aspect = 1f
        private val textureMatrices = Array(2) { FloatArray(16) }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            try {
                initializeSurface()
            } catch (_: RuntimeException) {
                post { if (!closed) onError() }
            }
        }

        private fun initializeSurface() {
            reset(0)
            check(GLES20.glGetString(GLES20.GL_EXTENSIONS)?.split(' ')?.contains("GL_OES_EGL_image_external") == true)
            program = linkProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            copyProgram = linkProgram(VERTEX_SHADER, COPY_SHADER)
            GLES20.glGenFramebuffers(1, frameBuffer, 0)
            GLES20.glGenTextures(1, frameTexture, 0)
            createSurfaces(afterReset = { })
        }

        fun replaceSurfaces(afterReset: () -> Unit) {
            createSurfaces(afterReset, textureIds.copyOf())
        }

        private fun createSurfaces(afterReset: () -> Unit, staleIds: IntArray? = null) {
            val generation = surfaceGeneration.incrementAndGet()
            GLES20.glGenTextures(2, textureIds, 0)
            val newTextures = arrayOfNulls<SurfaceTexture>(2)
            val newSurfaces = arrayOfNulls<Surface>(2)
            for (i in 0..1) {
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureIds[i])
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                val nextTexture = SurfaceTexture(textureIds[i])
                currentTextures.set(i, nextTexture)
                newTextures[i] = nextTexture
                nextTexture.setOnFrameAvailableListener {
                    if (!closed && currentTextures.get(i) === nextTexture) { signals[i].set(true); requestRender() }
                }
                val nextSurface = Surface(nextTexture)
                newSurfaces[i] = nextSurface
            }
            post {
                if (closed || generation != surfaceGeneration.get()) {
                    newSurfaces.forEach { it?.release() }; newTextures.forEach { it?.release() }
                    if (!closed) afterReset()
                } else {
                    afterReset()
                    for (i in 0..1) {
                        players[i].clearVideoSurface()
                        surfaces[i]?.release(); textures[i]?.release()
                        surfaces[i] = newSurfaces[i]; textures[i] = newTextures[i]
                        players[i].setVideoSurface(newSurfaces[i])
                    }
                    if (staleIds != null) queueEvent { GLES20.glDeleteTextures(2, staleIds, 0) }
                    resetting = false
                    requestRender()
                }
            }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            aspect = width.toFloat() / height.coerceAtLeast(1)
            frameValid = false
            if (width <= 0 || height <= 0) return
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frameTexture[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frameBuffer[0])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, frameTexture[0], 0)
            val complete = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            if (!complete) post { if (!closed) onError() }
        }

        fun reset(minimumUs: Long) {
            pairer.reset(minimumUs); pendingPair = null; frameValid = false; pairedIntact = false; pairedPts = null
            signals.forEach { it.set(false) }; timestamps.forEach { it.clear() }
            freezeRequested.set(false); frozen.set(false)
            latchedTokens.forEach { it.set(Long.MIN_VALUE) }; presentedPts.set(Long.MIN_VALUE)
        }

        override fun onDrawFrame(gl: GL10?) {
            try { drawSynchronized() } catch (_: RuntimeException) { post { if (!closed) onError() } }
        }

        private fun drawSynchronized() {
            if (resetting) { GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT); return }
            if (freezeRequested.get() && pairedIntact) frozen.set(true)
            if (!frozen.get()) for (i in 0..1) if (signals[i].getAndSet(false)) pairer.frameAvailable(i, pairer.generation())
            for (iteration in 0 until 16) {
                if (frozen.get()) break
                val ready = pendingPair
                if (ready != null) {
                    if (!framesVisible) break
                    val correction = if (stabilizationEnabled) stabilization?.correctionAt(ready.presentationTimeUs) else null
                    if (stabilizationEnabled && correction == null) {
                        stabilizationEnabled = false
                        post { if (!closed && !stabilizationEnabled) onStabilizationUnavailable() }
                    }
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frameBuffer[0])
                    drawProjection(correction)
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                    frameValid = true
                    pairer.presented(ready.presentationTimeUs, ready.generation)
                    presentedPts.set(ready.presentationTimeUs); presentedCount.incrementAndGet()
                    pairedIntact = true; pairedPts = ready.presentationTimeUs
                    pendingPair = null
                    if (freezeRequested.get()) { frozen.set(true); break }
                }
                when (val action = pairer.next()) {
                    is OsmoPairAction.Latch -> {
                        val frame = currentTextures.get(action.side) ?: return
                        frame.updateTexImage(); frame.getTransformMatrix(textureMatrices[action.side])
                        val pts = timestamps[action.side].lookup(frame.timestamp)
                        if (pts == null || latchedTokens[action.side].get() != frame.timestamp) pairedIntact = false
                        if (pts == null) pairer.discarded(action.side, action.generation)
                        else { pairer.latched(action.side, action.generation, OsmoDecodedFrame(frame.timestamp, pts)); latchedTokens[action.side].set(frame.timestamp) }
                    }
                    is OsmoPairAction.Present -> pendingPair = action
                    OsmoPairAction.Invalid -> { post { if (!closed) onError() }; return }
                    OsmoPairAction.Wait, OsmoPairAction.Ended -> break
                }
            }
            if (framesVisible && pairedIntact && pairedPts != null) {
                val correction = if (stabilizationEnabled) stabilization?.correctionAt(pairedPts!!) else null
                if (stabilizationEnabled && correction == null) {
                    stabilizationEnabled = false
                    post { if (!closed && !stabilizationEnabled) onStabilizationUnavailable() }
                }
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frameBuffer[0])
                drawProjection(correction)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                frameValid = true
            }
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            if (!framesVisible || !frameValid) return
            GLES20.glUseProgram(copyProgram)
            val position = GLES20.glGetAttribLocation(copyProgram, "position")
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, quad)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frameTexture[0])
            GLES20.glUniform1i(GLES20.glGetUniformLocation(copyProgram, "cachedFrame"), 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(position)
        }

        private fun drawProjection(correction: FloatArray?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)
            val position = GLES20.glGetAttribLocation(program, "position")
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, quad)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "aspect"), aspect)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "yaw"), yaw)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "pitch"), pitch)
            GLES20.glUniform4fv(GLES20.glGetUniformLocation(program, "motionCorrection"), 1, correction ?: floatArrayOf(1f, 0f, 0f, 0f), 0)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "calibrated"), if (calibration == null) 0f else 1f)
            calibration?.let { value ->
                fun setLens(prefix: String, lens: PanoramaLensCalibration) {
                    val intrinsics = lens.intrinsics.map { it / 3840f }.toFloatArray()
                    GLES20.glUniform4fv(GLES20.glGetUniformLocation(program, prefix + "Intrinsics"), 1, intrinsics, 0)
                    GLES20.glUniform4fv(GLES20.glGetUniformLocation(program, prefix + "Distortion"), 1, lens.distortion.toFloatArray(), 0)
                    GLES20.glUniform1f(GLES20.glGetUniformLocation(program, prefix + "K5"), lens.distortion[4])
                    GLES20.glUniform4fv(GLES20.glGetUniformLocation(program, prefix + "Quaternion"), 1, lens.quaternion.toFloatArray(), 0)
                }
                setLens("left", value.left); setLens("right", value.right)
            }
            for (i in 0..1) {
                GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "texMatrix$i"), 1, false, textureMatrices[i], 0)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + i)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureIds[i])
                GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "video$i"), i)
            }
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(position)
        }
    }

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec2 position;
            varying vec2 screen;
            void main() { screen = position; gl_Position = vec4(position, 0.0, 1.0); }
        """
        private const val COPY_SHADER = """
            precision mediump float;
            varying vec2 screen;
            uniform sampler2D cachedFrame;
            void main() { gl_FragColor = texture2D(cachedFrame, (screen + 1.0) * 0.5); }
        """
        private const val FRAGMENT_SHADER = """
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
            uniform float aspect;
            uniform float yaw;
            uniform float pitch;
            uniform float calibrated;
            uniform vec4 leftIntrinsics, rightIntrinsics;
            uniform vec4 leftDistortion, rightDistortion;
            uniform float leftK5, rightK5;
            uniform vec4 leftQuaternion, rightQuaternion;
            uniform vec4 motionCorrection;
            vec3 rotateQuaternion(vec4 q, vec3 v) {
                return v + 2.0 * cross(q.yzw, cross(q.yzw, v) + q.x * v);
            }
            void main() {
                vec3 ray = normalize(vec3(screen.x * aspect, screen.y, 1.0));
                float cp = cos(pitch), sp = sin(pitch);
                ray = vec3(ray.x, cp * ray.y + sp * ray.z, -sp * ray.y + cp * ray.z);
                float cy = cos(yaw), sy = sin(yaw);
                ray = vec3(cy * ray.x + sy * ray.z, ray.y, -sy * ray.x + cy * ray.z);
                vec3 body = rotateQuaternion(motionCorrection, vec3(-ray.x, -ray.z, ray.y));
                ray = vec3(-body.x, body.z, -body.y);
                bool back = ray.z < 0.0;
                if (calibrated > 0.5) {
                    vec4 intr = back ? rightIntrinsics : leftIntrinsics;
                    vec4 k = back ? rightDistortion : leftDistortion;
                    float k5 = back ? rightK5 : leftK5;
                    vec4 q = back ? rightQuaternion : leftQuaternion;
                    vec3 lens = rotateQuaternion(q, vec3(-ray.x, -ray.z, ray.y));
                    float xy = length(lens.xy);
                    float theta = atan(xy, lens.z);
                    float t2 = theta * theta;
                    float radius = theta * (1.0 + t2 * (k.x + t2 * (k.y + t2 * (k.z + t2 * (k.w + t2 * k5)))));
                    vec2 unit = xy > 0.0001 ? lens.xy / xy : vec2(0.0);
                    vec2 sensor = intr.zw + intr.xy * radius * unit;
                    if (sensor.x < 0.0 || sensor.x > 1.0 || sensor.y < 0.0 || sensor.y > 1.0) {
                        gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0); return;
                    }
                    vec2 uv = vec2(sensor.x, 1.0 - sensor.y);
                    if (back) gl_FragColor = texture2D(video1, (texMatrix1 * vec4(uv, 0.0, 1.0)).xy);
                    else gl_FragColor = texture2D(video0, (texMatrix0 * vec4(uv, 0.0, 1.0)).xy);
                    return;
                }
                vec2 plane = vec2(back ? -ray.x : ray.x, ray.y);
                float theta = acos(clamp(abs(ray.z), 0.0, 1.0));
                float lengthXY = length(plane);
                vec2 radial = lengthXY > 0.0001 ? plane / lengthXY * (theta / 1.7453293) : vec2(0.0);
                vec2 uv = vec2(0.5) + radial * 0.5;
                if (back) gl_FragColor = texture2D(video1, (texMatrix1 * vec4(uv, 0.0, 1.0)).xy);
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
            val vs = compile(GLES20.GL_VERTEX_SHADER, vertex)
            val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragment)
            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vs); GLES20.glAttachShader(program, fs); GLES20.glLinkProgram(program)
            GLES20.glDeleteShader(vs); GLES20.glDeleteShader(fs)
            val result = IntArray(1); GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, result, 0)
            check(result[0] != 0) { GLES20.glGetProgramInfoLog(program) }
            return program
        }
    }
}
