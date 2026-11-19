package com.cameramapper3d.app.gl

import android.opengl.GLES30
import com.cameramapper3d.core.MeshData
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** How mesh surfaces are coloured. */
enum class ShadeMode(val id: Int) { COLOR(0), SHADED(1) }

/**
 * Renders a set of meshes keyed by id (one per voxel block while scanning, or a single
 * finished model in the viewer). Call [sync] on the GL thread to upload changes.
 */
class MeshRenderer {
    private class GpuMesh(val source: MeshData, val vbo: Int, val ibo: Int, val vao: Int, val count: Int)

    private var program = 0
    private var uMvp = 0
    private var uMode = 0
    private var uAlpha = 0
    private var uLight = 0
    private val meshes = HashMap<Long, GpuMesh>()

    val triangleCount: Int get() = meshes.values.sumOf { it.count / 3 }

    fun createOnGlThread() {
        program = GlUtil.program(VERTEX, FRAGMENT)
        uMvp = GLES30.glGetUniformLocation(program, "u_mvp")
        uMode = GLES30.glGetUniformLocation(program, "u_mode")
        uAlpha = GLES30.glGetUniformLocation(program, "u_alpha")
        uLight = GLES30.glGetUniformLocation(program, "u_light")
    }

    /**
     * Makes the GPU contents match [current]. Meshes whose [MeshData] instance changed are re-uploaded,
     * at most [maxUploads] per call so a burst of updates cannot stall a frame.
     */
    fun sync(current: Map<Long, MeshData>, maxUploads: Int = Int.MAX_VALUE) {
        val it = meshes.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (!current.containsKey(e.key)) { release(e.value); it.remove() }
        }
        var uploads = 0
        for ((key, data) in current) {
            val existing = meshes[key]
            if (existing != null && existing.source === data) continue
            if (uploads >= maxUploads) break
            existing?.let(::release)
            meshes.remove(key)
            if (data.isEmpty) continue
            meshes[key] = upload(data)
            uploads++
        }
    }

    fun clear() {
        meshes.values.forEach(::release)
        meshes.clear()
    }

    /** @param drawOnTop pull the surface slightly towards the camera so it shows over the same geometry (highlights). */
    fun draw(mvp: FloatArray, mode: ShadeMode, alpha: Float, lightDir: FloatArray, drawOnTop: Boolean = false) {
        if (meshes.isEmpty()) return
        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES30.glUniform1i(uMode, mode.id)
        GLES30.glUniform1f(uAlpha, alpha)
        GLES30.glUniform3fv(uLight, 1, lightDir, 0)
        if (alpha < 1f) {
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        }
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        if (drawOnTop) {
            GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
            GLES30.glPolygonOffset(-2f, -8f)
        }
        for (m in meshes.values) {
            GLES30.glBindVertexArray(m.vao)
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, m.count, GLES30.GL_UNSIGNED_INT, 0)
        }
        GLES30.glBindVertexArray(0)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
    }

    private fun upload(mesh: MeshData): GpuMesh {
        val n = mesh.vertexCount
        val vb = ByteBuffer.allocateDirect(n * STRIDE).order(ByteOrder.nativeOrder())
        for (i in 0 until n) {
            vb.putFloat(mesh.positions[i * 3]); vb.putFloat(mesh.positions[i * 3 + 1]); vb.putFloat(mesh.positions[i * 3 + 2])
            vb.putFloat(mesh.normals[i * 3]); vb.putFloat(mesh.normals[i * 3 + 1]); vb.putFloat(mesh.normals[i * 3 + 2])
            vb.put(mesh.colors[i * 3]); vb.put(mesh.colors[i * 3 + 1]); vb.put(mesh.colors[i * 3 + 2]); vb.put(-1)
        }
        vb.position(0)
        val ib = ByteBuffer.allocateDirect(mesh.indices.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
        ib.put(mesh.indices); ib.position(0)

        val ids = IntArray(3)
        GLES30.glGenVertexArrays(1, ids, 0)
        GLES30.glGenBuffers(2, ids, 1)
        GLES30.glBindVertexArray(ids[0])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ids[1])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, n * STRIDE, vb, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, STRIDE, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, STRIDE, 12)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 4, GLES30.GL_UNSIGNED_BYTE, true, STRIDE, 24)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ids[2])
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, mesh.indices.size * 4, ib, GLES30.GL_STATIC_DRAW)
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
        return GpuMesh(mesh, ids[1], ids[2], ids[0], mesh.indices.size)
    }

    private fun release(m: GpuMesh) {
        GLES30.glDeleteBuffers(2, intArrayOf(m.vbo, m.ibo), 0)
        GLES30.glDeleteVertexArrays(1, intArrayOf(m.vao), 0)
    }

    private companion object {
        const val STRIDE = 28

        const val VERTEX = """#version 300 es
            uniform mat4 u_mvp;
            layout(location = 0) in vec3 a_pos;
            layout(location = 1) in vec3 a_nrm;
            layout(location = 2) in vec4 a_col;
            out vec3 v_nrm;
            out vec3 v_col;
            void main() {
                gl_Position = u_mvp * vec4(a_pos, 1.0);
                v_nrm = a_nrm;
                v_col = a_col.rgb;
            }
        """

        const val FRAGMENT = """#version 300 es
            precision mediump float;
            uniform int u_mode;
            uniform float u_alpha;
            uniform vec3 u_light;
            in vec3 v_nrm;
            in vec3 v_col;
            out vec4 o_color;
            void main() {
                vec3 n = normalize(v_nrm);
                float diffuse = 0.35 + 0.65 * max(dot(n, u_light), 0.0);
                vec3 c = u_mode == 0 ? v_col * (0.8 + 0.2 * diffuse) : vec3(0.82, 0.86, 0.9) * diffuse;
                o_color = vec4(c, u_alpha);
            }
        """
    }
}
