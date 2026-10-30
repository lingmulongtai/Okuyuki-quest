package com.okuyuki.quest.window

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.view.MotionEvent
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Displays a complete equirectangular image as a perspective view. */
class PanoramaPhotoView(context: Context, private val bitmap: Bitmap, private val onError: () -> Unit) : GLSurfaceView(context) {
    private val renderer = PhotoRenderer()
    private var lastX = 0f
    private var lastY = 0f
    @Volatile private var closed = false

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
    fun close() { closed = true }

    private inner class PhotoRenderer : Renderer {
        @Volatile var yaw = 0f
        @Volatile var pitch = 0f
        private var aspect = 1f
        private var program = 0
        private var textureId = 0
        private val quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0)
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            try {
                program = linkProgram(VERTEX_SHADER, FRAGMENT_SHADER)
                val limit = IntArray(1)
                GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, limit, 0)
                check(bitmap.width <= limit[0] && bitmap.height <= limit[0])
                val ids = IntArray(1)
                GLES20.glGenTextures(1, ids, 0); textureId = ids[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
                check(GLES20.glGetError() == GLES20.GL_NO_ERROR)
            } catch (_: RuntimeException) {
                program = 0
                post { if (!closed) onError() }
            }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            aspect = width.toFloat() / height.coerceAtLeast(1)
        }

        override fun onDrawFrame(gl: GL10?) {
            if (program == 0) return
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)
            val position = GLES20.glGetAttribLocation(program, "position")
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, quad)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "aspect"), aspect)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "yaw"), yaw)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "pitch"), pitch)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "photo"), 0)
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
            precision mediump float;
            varying vec2 screen;
            uniform sampler2D photo;
            uniform float aspect, yaw, pitch;
            const float PI = 3.141592653589793;
            void main() {
                vec3 ray = normalize(vec3(screen.x * aspect, screen.y, 1.0));
                float cp = cos(pitch), sp = sin(pitch);
                ray = vec3(ray.x, cp * ray.y + sp * ray.z, -sp * ray.y + cp * ray.z);
                float cy = cos(yaw), sy = sin(yaw);
                ray = vec3(cy * ray.x + sy * ray.z, ray.y, -sy * ray.x + cy * ray.z);
                float u = fract(0.5 + atan(ray.x, ray.z) / (2.0 * PI));
                float v = 0.5 - asin(clamp(ray.y, -1.0, 1.0)) / PI;
                gl_FragColor = texture2D(photo, vec2(u, v));
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
