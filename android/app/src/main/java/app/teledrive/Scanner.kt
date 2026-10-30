package app.teledrive

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile

data class Item(
    val key: String,
    val uri: Uri,
    val name: String,
    val size: Long,
    val mime: String,
    val modifiedMs: Long,
    /** relative folder inside "/Phone/<device>/", e.g. "Photos/DCIM/Camera" */
    val folder: String,
)

object Scanner {
    fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    fun collect(ctx: Context, p: Prefs): List<Item> {
        val since = if (p.sinceSec > 0) p.sinceSec else 0L
        val out = ArrayList<Item>()
        if (p.photos) out += media(ctx, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "Photos", since)
        if (p.videos) out += media(ctx, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "Videos", since)
        if (p.audio) out += media(ctx, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "Audio", since)
        for (f in p.folders) runCatching { out += tree(ctx, Uri.parse(f), since * 1000) }
        return out
    }

    private fun media(ctx: Context, collection: Uri, kind: String, sinceSec: Long): List<Item> {
        val proj = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.RELATIVE_PATH,
        )
        val sel = if (sinceSec > 0) "${MediaStore.MediaColumns.DATE_ADDED} > ?" else null
        val args = if (sinceSec > 0) arrayOf(sinceSec.toString()) else null
        val out = ArrayList<Item>()
        ctx.contentResolver.query(collection, proj, sel, args, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val iMime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val iMod = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val iRel = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            while (c.moveToNext()) {
                val size = c.getLong(iSize)
                if (size <= 0) continue
                val id = c.getLong(iId)
                val name = c.getString(iName) ?: "file_$id"
                val rel = (c.getString(iRel) ?: "").trim('/')
                out += Item(
                    key = "ms:$kind:$id:$size",
                    uri = ContentUris.withAppendedId(collection, id),
                    name = name, size = size,
                    mime = c.getString(iMime) ?: guessMime(name),
                    modifiedMs = c.getLong(iMod) * 1000,
                    folder = if (rel.isEmpty()) kind else "$kind/$rel",
                )
            }
        }
        return out
    }

    private fun tree(ctx: Context, treeUri: Uri, sinceMs: Long): List<Item> {
        val root = DocumentFile.fromTreeUri(ctx, treeUri) ?: return emptyList()
        val rootName = root.name ?: "Folder"
        val out = ArrayList<Item>()
        fun walk(dir: DocumentFile, rel: String) {
            for (f in dir.listFiles()) {
                if (f.isDirectory) { walk(f, rel + "/" + (f.name ?: "folder")); continue }
                if (!f.isFile) continue
                val size = f.length()
                val lm = f.lastModified()
                if (size <= 0) continue
                if (sinceMs > 0 && lm <= sinceMs) continue
                val name = f.name ?: "file"
                out += Item("saf:${f.uri}:$lm:$size", f.uri, name, size, f.type ?: guessMime(name), lm, "Folders/$rootName$rel")
            }
        }
        walk(root, "")
        return out
    }
}
