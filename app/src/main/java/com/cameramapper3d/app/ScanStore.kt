package com.cameramapper3d.app

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.cameramapper3d.core.MeshData
import com.cameramapper3d.core.ObjWriter
import com.cameramapper3d.core.PlyReader
import com.cameramapper3d.core.PlyWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saved scans live in app storage as PLY files; exports are written to the cache and shared. */
object ScanStore {
    private fun dir(context: Context) = File(context.filesDir, "scans").apply { mkdirs() }

    fun list(context: Context): List<File> =
        dir(context).listFiles { f -> f.extension == "ply" }?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun save(context: Context, mesh: MeshData, voxelSize: Float): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir(context), "scan_$stamp.ply")
        val tmp = File(file.path + ".tmp")
        tmp.outputStream().use { PlyWriter.write(mesh, it, comment = "voxel_size_m ${voxelSize}") }
        tmp.renameTo(file)
        return file
    }

    fun load(file: File): MeshData = file.inputStream().use { PlyReader.read(it) }

    fun displayName(file: File): String {
        val stamp = file.nameWithoutExtension.removePrefix("scan_")
        return try {
            val date = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(stamp)!!
            "Scan " + SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(date)
        } catch (e: Exception) {
            file.nameWithoutExtension
        }
    }

    enum class Format(val extension: String, val mime: String) {
        PLY("ply", "application/octet-stream"),
        OBJ("obj", "text/plain"),
    }

    /** Writes the export (for OBJ) and returns an intent that shares it with other apps. */
    fun shareIntent(context: Context, source: File, mesh: MeshData, format: Format): Intent {
        val file = when (format) {
            Format.PLY -> source
            Format.OBJ -> {
                val exports = File(context.cacheDir, "exports").apply { mkdirs() }
                exports.listFiles()?.forEach { it.delete() }
                File(exports, source.nameWithoutExtension + ".obj").also { f -> f.outputStream().use { ObjWriter.write(mesh, it) } }
            }
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = format.mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, file.name)
    }
}
