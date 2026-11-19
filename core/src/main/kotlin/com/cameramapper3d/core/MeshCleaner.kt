package com.cameramapper3d.core

/** Removes small loose fragments ("floating pieces") from a scan. */
object MeshCleaner {

    class Result(val mesh: MeshData, val removedPieces: Int, val removedArea: Double)

    /**
     * Deletes every connected piece whose surface area is below [minArea] m². The largest piece is
     * always kept, so the scan never ends up empty.
     */
    fun removeSmallPieces(mesh: MeshData, minArea: Float): Result {
        if (mesh.isEmpty) return Result(mesh, 0, 0.0)
        // Union-find over vertices: vertices of a triangle belong to the same piece.
        val parent = IntArray(mesh.vertexCount) { it }
        fun find(v: Int): Int {
            var r = v
            while (parent[r] != r) r = parent[r]
            var c = v
            while (parent[c] != r) { val next = parent[c]; parent[c] = r; c = next }
            return r
        }
        val idx = mesh.indices
        for (t in 0 until mesh.triangleCount) {
            val a = find(idx[t * 3])
            for (k in 1..2) {
                val b = find(idx[t * 3 + k])
                if (a != b) parent[b] = a
            }
        }
        val pieceArea = HashMap<Int, Double>()
        val triArea = DoubleArray(mesh.triangleCount)
        val p = mesh.positions
        for (t in 0 until mesh.triangleCount) {
            val a = idx[t * 3] * 3; val b = idx[t * 3 + 1] * 3; val c = idx[t * 3 + 2] * 3
            val ux = p[b] - p[a]; val uy = p[b + 1] - p[a + 1]; val uz = p[b + 2] - p[a + 2]
            val vx = p[c] - p[a]; val vy = p[c + 1] - p[a + 1]; val vz = p[c + 2] - p[a + 2]
            val cx = uy * vz - uz * vy; val cy = uz * vx - ux * vz; val cz = ux * vy - uy * vx
            triArea[t] = 0.5 * Math.sqrt((cx * cx + cy * cy + cz * cz).toDouble())
            pieceArea.merge(find(idx[t * 3]), triArea[t], Double::plus)
        }
        val largest = pieceArea.maxBy { it.value }.key
        val keepPiece = pieceArea.filter { it.key == largest || it.value >= minArea }.keys
        val removedPieces = pieceArea.size - keepPiece.size
        if (removedPieces == 0) return Result(mesh, 0, 0.0)

        // Copy the kept triangles and the vertices they use.
        val remap = IntArray(mesh.vertexCount) { -1 }
        val pos = FloatList(); val nrm = FloatList(); val col = ByteList(); val out = IntList()
        var removedArea = 0.0
        for (t in 0 until mesh.triangleCount) {
            if (find(idx[t * 3]) !in keepPiece) { removedArea += triArea[t]; continue }
            for (k in 0 until 3) {
                val v = idx[t * 3 + k]
                if (remap[v] < 0) {
                    remap[v] = pos.size / 3
                    for (a in 0 until 3) { pos.push(mesh.positions[v * 3 + a]); nrm.push(mesh.normals[v * 3 + a]); col.push(mesh.colors[v * 3 + a]) }
                }
                out.push(remap[v])
            }
        }
        return Result(MeshData(pos.toArray(), nrm.toArray(), col.toArray(), out.toArray()), removedPieces, removedArea)
    }
}
