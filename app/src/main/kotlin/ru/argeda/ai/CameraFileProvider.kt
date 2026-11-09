package ru.argeda.ai

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/** Serves photos taken by the camera from the app cache dir (cache/camera/<name>). */
class CameraFileProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "ru.argeda.ai.files"

        fun uriFor(f: File): Uri = uriFor("camera", f)
        fun uriFor(kind: String, f: File): Uri =
            Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(kind).appendPath(f.name).build()
    }

    @Throws(FileNotFoundException::class)
    private fun fileFor(uri: Uri): File {
        val seg = uri.pathSegments
        val kind = if (seg.size == 2) seg[0] else ""
        if (kind != "camera" && kind != "share" && kind != "update") throw FileNotFoundException()
        val dir = File(context!!.cacheDir, kind)
        val f = File(dir, uri.lastPathSegment ?: throw FileNotFoundException())
        try {
            if (!f.canonicalPath.startsWith(dir.canonicalPath + File.separator)) throw FileNotFoundException()
        } catch (e: IOException) { throw FileNotFoundException() }
        return f
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val f = fileFor(uri)
        val m = if (mode == "r") ParcelFileDescriptor.MODE_READ_ONLY
        else ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
        return ParcelFileDescriptor.open(f, m)
    }

    override fun query(uri: Uri, projection: Array<String>?, sel: String?, args: Array<String>?, order: String?): Cursor? {
        val f = try { fileFor(uri) } catch (e: FileNotFoundException) { return null }
        val c = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
        c.addRow(arrayOf<Any>(f.name, f.length()))
        return c
    }

    override fun getType(uri: Uri): String {
        val n = (uri.lastPathSegment ?: "").lowercase()
        val ext = if (n.contains('.')) n.substring(n.lastIndexOf('.') + 1) else ""
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    override fun insert(uri: Uri, v: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?): Int = 0
}
