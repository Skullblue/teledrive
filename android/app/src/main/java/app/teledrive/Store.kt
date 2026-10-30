package app.teledrive

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Tiny append-only files: which items were uploaded, and index records not yet pushed to Telegram. */
class Store(context: Context) {
    private val uploaded = File(context.filesDir, "uploaded.txt")
    private val pending = File(context.filesDir, "pending.jsonl")

    fun loadUploaded(): HashSet<String> =
        if (uploaded.exists()) uploaded.useLines { it.filter { l -> l.isNotEmpty() }.toHashSet() } else HashSet()

    fun markUploaded(key: String) = uploaded.appendText(key + "\n")
    fun uploadedCount(): Int = if (uploaded.exists()) uploaded.useLines { it.count() } else 0

    fun addPending(rec: JSONObject) = pending.appendText(rec.toString() + "\n")
    fun readPending(): List<JSONObject> =
        if (pending.exists()) pending.readLines().filter { it.isNotBlank() }.mapNotNull { runCatching { JSONObject(it) }.getOrNull() } else emptyList()
    fun clearPending() { pending.delete() }

    fun reset() { uploaded.delete(); pending.delete() }
}
