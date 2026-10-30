package com.okuyuki.quest

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import android.view.Surface
import androidx.media3.exoplayer.ExoPlayer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Approximate dual-fisheye preview. Camera calibration and stabilization are not applied. */
class DualFisheyeView(context: Context, private val player: ExoPlayer, private val onError: () -> Unit) : GLSurfaceView(context) {
    private val renderer = FisheyeRenderer()
    private var lastX = 0f
    private var lastY = 0f
    @Volatile private var closed = false
    private var surface: Surface? = null
    private var texture: SurfaceTexture? = null

    init {
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

    /** Call on the UI thread after pausing the GL thread. */
    fun close() {
        closed = true
        player.clearVideoSurface()
        surface?.release(); surface = null
        texture?.release(); texture = null
    }

    private inner class FisheyeRenderer : Renderer {
        @Volatile var yaw = 0f
        @Volatile var pitch = 0f
        private val quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0)
        }
        private var program = 0
        private var textureId = 0
        private var currentTexture: SurfaceTexture? = null
        private var aspect = 1f
        private val textureMatrix = FloatArray(16)

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            try {
                initializeSurface()
            } catch (_: RuntimeException) {
                post { if (!closed) onError() }
            }
        }

        private fun initializeSurface() {
            check(GLES20.glGetString(GLES20.GL_EXTENSIONS)?.split(' ')?.contains("GL_OES_EGL_image_external") == true)
            program = linkProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            textureId = ids[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            val nextTexture = SurfaceTexture(textureId)
            currentTexture = nextTexture
            nextTexture.setOnFrameAvailableListener { if (!closed) requestRender() }
            val nextSurface = Surface(nextTexture)
            post {
                if (closed) { nextSurface.release(); nextTexture.release() }
                else {
                    player.clearVideoSurface()
                    surface?.release(); texture?.release()
                    surface = nextSurface; texture = nextTexture
                    player.setVideoSurface(nextSurface)
                }
            }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            aspect = width.toFloat() / height.coerceAtLeast(1)
        }

        override fun onDrawFrame(gl: GL10?) {
            val frame = currentTexture ?: return
            try { frame.updateTexImage(); frame.getTransformMatrix(textureMatrix) } catch (_: RuntimeException) { return }
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)
            val position = GLES20.glGetAttribLocation(program, "position")
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, quad)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "aspect"), aspect)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "yaw"), yaw)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "pitch"), pitch)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "texMatrix"), 1, false, textureMatrix, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "video"), 0)
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
        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 screen;
            uniform samplerExternalOES video;
            uniform mat4 texMatrix;
            uniform float aspect;
            uniform float yaw;
            uniform float pitch;
            void main() {
                vec3 ray = normalize(vec3(screen.x * aspect, screen.y, 1.0));
                float cp = cos(pitch), sp = sin(pitch);
                ray = vec3(ray.x, cp * ray.y + sp * ray.z, -sp * ray.y + cp * ray.z);
                float cy = cos(yaw), sy = sin(yaw);
                ray = vec3(cy * ray.x + sy * ray.z, ray.y, -sy * ray.x + cy * ray.z);
                bool back = ray.z < 0.0;
                vec2 plane = vec2(back ? -ray.x : ray.x, ray.y);
                float theta = acos(clamp(abs(ray.z), 0.0, 1.0));
                float lengthXY = length(plane);
                vec2 radial = lengthXY > 0.0001 ? plane / lengthXY * (theta / 1.7453293) : vec2(0.0);
                vec2 uv = vec2((back ? 0.75 : 0.25) + radial.x * 0.25, 0.5 + radial.y * 0.5);
                gl_FragColor = texture2D(video, (texMatrix * vec4(uv, 0.0, 1.0)).xy);
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
