package com.cameramapper3d.app.gl

import android.opengl.GLES30

/** Draws measurement points and the line between them, always on top of the model. */
class MarkerRenderer {
    private var program = 0
    private var uMvp = 0
    private var uColor = 0
    private var uSize = 0
    private var uRound = 0

    fun createOnGlThread() {
        program = GlUtil.program(VERTEX, FRAGMENT)
        uMvp = GLES30.glGetUniformLocation(program, "u_mvp")
        uColor = GLES30.glGetUniformLocation(program, "u_color")
        uSize = GLES30.glGetUniformLocation(program, "u_size")
        uRound = GLES30.glGetUniformLocation(program, "u_round")
    }

    /** [points] holds xyz triples. Draws each point and, when [connect] is set, a line through them. */
    fun draw(mvp: FloatArray, points: FloatArray, connect: Boolean, pointSizePx: Float) {
        if (points.isEmpty()) return
        val buf = GlUtil.floatBuffer(points)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 0, buf)
        val count = points.size / 3
        if (connect && count >= 2) {
            GLES30.glLineWidth(6f)
            GLES30.glUniform1i(uRound, 0)
            GLES30.glUniform4f(uColor, 1f, 0.85f, 0.2f, 1f)
            GLES30.glDrawArrays(GLES30.GL_LINE_STRIP, 0, count)
        }
        GLES30.glUniform1i(uRound, 1)
        GLES30.glUniform1f(uSize, pointSizePx * 1.5f)
        GLES30.glUniform4f(uColor, 0f, 0f, 0f, 1f)
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, count)
        GLES30.glUniform1f(uSize, pointSizePx)
        GLES30.glUniform4f(uColor, 1f, 0.85f, 0.2f, 1f)
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, count)
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    private companion object {
        const val VERTEX = """#version 300 es
            uniform mat4 u_mvp;
            uniform float u_size;
            layout(location = 0) in vec3 a_pos;
            void main() {
                gl_Position = u_mvp * vec4(a_pos, 1.0);
                gl_PointSize = u_size;
            }
        """
        const val FRAGMENT = """#version 300 es
            precision mediump float;
            uniform vec4 u_color;
            uniform int u_round;
            out vec4 o_color;
            void main() {
                if (u_round == 1) {
                    vec2 d = gl_PointCoord - vec2(0.5);
                    if (dot(d, d) > 0.25) discard;
                }
                o_color = u_color;
            }
        """
    }
}
