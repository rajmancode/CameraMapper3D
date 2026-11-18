package com.cameramapper3d.app.gl

import android.opengl.GLES11Ext
import android.opengl.GLES30
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import java.nio.FloatBuffer

/** Draws the ARCore camera feed full-screen. */
class BackgroundRenderer {
    var textureId = -1
        private set

    private var program = 0
    private var posAttr = 0
    private var uvAttr = 0
    private val quad: FloatBuffer = GlUtil.floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val uvs: FloatBuffer = GlUtil.floatBuffer(FloatArray(8))

    fun createOnGlThread() {
        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        textureId = tex[0]
        val target = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
        GLES30.glBindTexture(target, textureId)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)

        program = GlUtil.program(VERTEX, FRAGMENT)
        posAttr = GLES30.glGetAttribLocation(program, "a_pos")
        uvAttr = GLES30.glGetAttribLocation(program, "a_uv")
    }

    fun draw(frame: Frame) {
        if (frame.hasDisplayGeometryChanged()) {
            quad.position(0); uvs.position(0)
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quad,
                Coordinates2d.TEXTURE_NORMALIZED, uvs,
            )
            quad.position(0); uvs.position(0)
        }
        if (frame.timestamp == 0L) return // camera has not produced a frame yet

        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES30.glVertexAttribPointer(posAttr, 2, GLES30.GL_FLOAT, false, 0, quad)
        GLES30.glVertexAttribPointer(uvAttr, 2, GLES30.GL_FLOAT, false, 0, uvs)
        GLES30.glEnableVertexAttribArray(posAttr)
        GLES30.glEnableVertexAttribArray(uvAttr)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(posAttr)
        GLES30.glDisableVertexAttribArray(uvAttr)
        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    private companion object {
        const val VERTEX = """
            attribute vec4 a_pos;
            attribute vec2 a_uv;
            varying vec2 v_uv;
            void main() {
                gl_Position = a_pos;
                v_uv = a_uv;
            }
        """
        const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 v_uv;
            uniform samplerExternalOES u_tex;
            void main() {
                gl_FragColor = texture2D(u_tex, v_uv);
            }
        """
    }
}
