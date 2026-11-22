package com.stellar.autohub.upload

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.charset.Charset
import java.util.zip.ZipInputStream

class RawFile(val path: String, val bytes: ByteArray)

/** Everything the user picked, flattened into repo-relative paths (before ignore/strip rules). */
class Payload(
    val files: List<RawFile>,
    val label: String,
    /** True when it came from a zip or folder (likely a whole project), false for loose files. */
    val structured: Boolean,
)

object PayloadReader {
    private const val MAX_TOTAL = 400L * 1024 * 1024

    fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { return it }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    /** Picked or shared files. Zips are expanded; loose files go into [targetFolder]. */
    fun fromUris(context: Context, uris: List<Uri>, targetFolder: String): Payload {
        val out = mutableListOf<RawFile>()
        var zips = 0
        var loose = 0
        var total = 0L
        val folder = PathRules.normalize(targetFolder) ?: ""
        val names = mutableListOf<String>()
        for (uri in uris) {
            val name = displayName(context, uri)
            names += name
            val mime = context.contentResolver.getType(uri) ?: ""
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IOException("Couldn't read $name")
            total += bytes.size
            if (total > MAX_TOTAL) throw IOException("Upload is over 400 MB, that's too big for one commit.")
            if (name.endsWith(".zip", true) || mime == "application/zip" || mime == "application/x-zip-compressed") {
                zips++
                out += readZip(bytes)
            } else {
                loose++
                out += RawFile(if (folder.isEmpty()) name else "$folder/$name", bytes)
            }
        }
        val label = when {
            names.size == 1 -> names[0]
            else -> "${names.size} items"
        }
        return Payload(out, label, structured = zips > 0 && loose == 0)
    }

    /** A whole folder picked with the system folder picker (hidden folders like .github included). */
    fun fromTree(context: Context, treeUri: Uri): Payload {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: throw IOException("Can't open that folder")
        val out = mutableListOf<RawFile>()
        var total = 0L
        fun walk(dir: DocumentFile, prefix: String) {
            for (f in dir.listFiles()) {
                val n = f.name ?: continue
                val path = if (prefix.isEmpty()) n else "$prefix/$n"
                if (f.isDirectory) {
                    walk(f, path)
                } else {
                    val bytes = context.contentResolver.openInputStream(f.uri)?.use { it.readBytes() } ?: continue
                    total += bytes.size
                    if (total > MAX_TOTAL) throw IOException("Folder is over 400 MB, that's too big for one commit.")
                    out += RawFile(path, bytes)
                }
            }
        }
        walk(root, "")
        return Payload(out, root.name ?: "folder", structured = true)
    }

    fun readZip(bytes: ByteArray): List<RawFile> = try {
        readZip(bytes, Charsets.UTF_8)
    } catch (e: IllegalArgumentException) {
        // Old Windows zips use CP437 file names
        readZip(bytes, Charset.forName("IBM437"))
    }

    private fun readZip(bytes: ByteArray, cs: Charset): List<RawFile> {
        val out = mutableListOf<RawFile>()
        ZipInputStream(ByteArrayInputStream(bytes), cs).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                if (!e.isDirectory) out += RawFile(e.name, zin.readBytes())
                zin.closeEntry()
            }
        }
        if (out.isEmpty()) throw IOException("That zip is empty or not a valid zip.")
        return out
    }
}
