package com.cameramapper3d.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.content.FileProvider
import com.cameramapper3d.core.DecodedImage
import com.cameramapper3d.core.GlbWriter
import com.cameramapper3d.core.ImageDecoder
import com.cameramapper3d.core.MeshData
import com.cameramapper3d.core.MeshImporter
import com.cameramapper3d.core.ObjWriter
import com.cameramapper3d.core.PlyReader
import com.cameramapper3d.core.PlyWriter
import com.cameramapper3d.core.ThumbnailRenderer
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saved scans live in app storage as PLY files, each with a PNG thumbnail of the same name.
 * Exports are written to the cache and shared.
 */
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
        writeThumbnail(mesh, thumbnailFile(file))
        return file
    }

    /** Saves a processed version of [source] as a new scan named "<source name> <suffix>". */
    fun saveDerived(context: Context, mesh: MeshData, source: File, suffix: String): File =
        saveNamed(context, mesh, "${displayName(source)} $suffix")

    /** Saves [mesh] as a new scan called [baseName] (or "baseName 2", "baseName 3"… if taken). */
    fun saveNamed(context: Context, mesh: MeshData, baseName: String): File {
        val file = save(context, mesh, voxelSize = 0f)
        val base = baseName.ifBlank { "Imported scan" }
        var n = 1
        while (true) {
            val name = if (n == 1) base else "$base $n"
            try {
                return rename(file, name)
            } catch (e: IllegalArgumentException) {
                if (++n > 99) return file
            }
        }
    }

    fun load(file: File): MeshData = file.inputStream().use { PlyReader.read(it) }

    /** Reads a GLB, PLY or OBJ file from another app or scanner, keeping its colours (and texture colours). */
    fun import(input: InputStream): MeshData = MeshImporter.read(input, AndroidImageDecoder)

    /** Decodes texture pictures for import, shrinking huge ones so they fit in memory. */
    private object AndroidImageDecoder : ImageDecoder {
        override fun decode(bytes: ByteArray): DecodedImage? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            return DecodedImage(bmp.width, bmp.height, px).also { bmp.recycle() }
        }
    }

    /** Writes the scan in [format] to [out] (e.g. a file the user picked with "Save to device"). */
    fun writeExport(scan: File, mesh: MeshData, format: Format, out: OutputStream) {
        when (format) {
            Format.PLY -> scan.inputStream().use { it.copyTo(out, 1 shl 16) }
            Format.OBJ -> ObjWriter.write(mesh, out)
            Format.GLB -> GlbWriter.write(mesh, out, name = displayName(scan))
        }
        out.flush()
    }

    fun thumbnailFile(scan: File) = File(scan.parentFile, scan.nameWithoutExtension + ".png")

    /** Returns the scan's thumbnail, rendering it first if it is missing (e.g. scans made before thumbnails). */
    fun ensureThumbnail(scan: File): File {
        val thumb = thumbnailFile(scan)
        if (!thumb.exists()) writeThumbnail(load(scan), thumb)
        return thumb
    }

    private fun writeThumbnail(mesh: MeshData, out: File) {
        val size = THUMBNAIL_SIZE
        val pixels = ThumbnailRenderer.render(mesh, size = size)
        val bitmap = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
        val tmp = File(out.path + ".tmp")
        tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        tmp.renameTo(out)
    }

    /**
     * Renames a scan's file (and its thumbnail) to [newName], so exports and shares carry it too.
     * @throws IllegalArgumentException with a message for the user if the name can't be used.
     */
    fun rename(scan: File, newName: String): File {
        val clean = newName.trim().replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(80).trim()
        require(clean.isNotEmpty() && clean != "." && clean != "..") { "Please enter a name." }
        if (clean == scan.nameWithoutExtension) return scan
        val target = File(scan.parentFile, "$clean.ply")
        require(!target.exists()) { "A scan called \"$clean\" already exists." }
        val oldThumb = thumbnailFile(scan)
        require(scan.renameTo(target)) { "Could not rename the scan." }
        if (oldThumb.exists()) oldThumb.renameTo(thumbnailFile(target))
        return target
    }

    fun delete(scan: File) {
        thumbnailFile(scan).delete()
        scan.delete()
    }

    fun displayName(file: File): String {
        val name = file.nameWithoutExtension
        if (!AUTO_NAME.matches(name)) return name
        return try {
            val date = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(name.removePrefix("scan_"))!!
            "Scan " + SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(date)
        } catch (e: Exception) {
            name
        }
    }

    private val AUTO_NAME = Regex("scan_\\d{8}_\\d{6}")
    private const val THUMBNAIL_SIZE = 192

    enum class Format(val extension: String, val mime: String) {
        /** Colour shows in almost every viewer (Windows, Blender, phones, web). */
        GLB("glb", "model/gltf-binary"),
        PLY("ply", "application/octet-stream"),
        OBJ("obj", "text/plain"),
    }

    /** Writes the export (for OBJ) and returns an intent that shares it with other apps. */
    fun shareIntent(context: Context, source: File, mesh: MeshData, format: Format): Intent {
        val file = when (format) {
            Format.PLY -> source
            Format.OBJ, Format.GLB -> {
                val exports = File(context.cacheDir, "exports").apply { mkdirs() }
                exports.listFiles()?.forEach { it.delete() }
                File(exports, "${source.nameWithoutExtension}.${format.extension}").also { f ->
                    f.outputStream().use { writeExport(source, mesh, format, it) }
                }
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
