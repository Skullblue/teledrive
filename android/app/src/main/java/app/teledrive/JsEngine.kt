package app.teledrive

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class JsError(message: String) : IOException(message)
data class LoginEvent(val seq: Int, val step: String, val message: String)

/**
 * Runs the Teledrive engine (GramJS / MTProto, logs in as *you* with your phone number – no bot) inside a hidden WebView
 * and talks to it through a tiny bridge. File bytes are read natively and handed to JS part by part.
 */
object JsEngine {
    private lateinit var app: Context
    private var web: WebView? = null
    private var loaded = CompletableDeferred<Unit>()
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<String>>()
    private val callIds = AtomicInteger(1)
    private val eventSeq = AtomicInteger(0)
    private val handles = ConcurrentHashMap<Int, Pair<ParcelFileDescriptor, FileChannel>>()
    private val writeHandles = ConcurrentHashMap<Int, java.io.OutputStream>()
    private val handleIds = AtomicInteger(1)

    val events = MutableStateFlow(LoginEvent(0, "", ""))
    @Volatile var onUploadProgress: ((Long) -> Unit)? = null

    // credentials used to (re)initialise the engine whenever the WebView is (re)created
    private var creds: Triple<String, String, String>? = null
    private var storageJson: String? = null
    private var initDone = false

    fun attach(ctx: Context) { app = ctx.applicationContext }
    fun q(s: String): String = JSONObject.quote(s)

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun ensureWeb() {
        if (web == null) withContext(Dispatchers.Main) {
            if (web == null) {
                val w = WebView(app)
                w.settings.javaScriptEnabled = true
                w.settings.domStorageEnabled = true
                w.addJavascriptInterface(Bridge(), "Android")
                w.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) { loaded.complete(Unit) }
                }
                w.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(m: ConsoleMessage): Boolean { Log.d("TGE", m.message()); return true }
                }
                w.resumeTimers()
                w.loadUrl("file:///android_asset/engine.html")
                web = w
            }
        }
        loaded.await()
    }

    /** Evaluates a JS expression (which may return a Promise) and returns its JSON-encoded result. */
    private suspend fun raw(js: String): String {
        ensureWeb()
        val id = callIds.getAndIncrement()
        val d = CompletableDeferred<String>()
        pending[id] = d
        try {
            withContext(Dispatchers.Main) { web?.evaluateJavascript("TGE.run($id, function(){ return ($js); })", null) }
            return d.await()
        } finally { pending.remove(id) }
    }

    /** Connects and verifies the saved login. Throws JsError("SIGNED_OUT") if Telegram ended the session. */
    suspend fun ready() {
        ensureWeb()
        if (!initDone) {
            val c = creds ?: throw JsError("Not signed in")
            val r = JSONObject(raw("TGE.init({apiId:${q(c.first)},apiHash:${q(c.second)},session:${q(c.third)}})"))
            if (!r.optBoolean("authorized")) throw JsError("SIGNED_OUT")
            storageJson?.let { raw("TGE.setStorage($it)") }
            initDone = true
        }
    }

    private fun str(json: String): String = JSONTokener(json).nextValue() as? String ?: ""

    // ── login ────────────────────────────────────────────────────────────────
    suspend fun beginLogin(apiId: String, apiHash: String) {
        creds = Triple(apiId, apiHash, ""); storageJson = null; initDone = false
        events.value = LoginEvent(eventSeq.incrementAndGet(), "", "")
        ensureWeb()
        raw("TGE.init({apiId:${q(apiId)},apiHash:${q(apiHash)},session:''})")
        initDone = true
    }
    suspend fun startLogin(phone: String) {
        ensureWeb()
        withContext(Dispatchers.Main) { web?.evaluateJavascript("TGE.login(${q(phone)}).catch(function(){})", null) }
    }
    suspend fun submitCode(code: String) = withContext(Dispatchers.Main) { web?.evaluateJavascript("TGE.submitCode(${q(code)})", null); Unit }
    suspend fun submitPassword(pw: String) = withContext(Dispatchers.Main) { web?.evaluateJavascript("TGE.submitPassword(${q(pw)})", null); Unit }
    suspend fun sessionString(): String = str(raw("TGE.session()"))
    suspend fun me(): JSONObject = JSONObject(raw("TGE.me()"))
    /** Finds or creates the private "Teledrive Storage" channel. Returns it as a JSON string to persist. */
    suspend fun ensureStorage(): String = raw("TGE.ensureStorage()")

    // ── normal operation ─────────────────────────────────────────────────────
    fun configure(apiId: String, apiHash: String, session: String, storage: String) {
        val changed = creds != Triple(apiId, apiHash, session) || storageJson != storage
        creds = Triple(apiId, apiHash, session); storageJson = storage
        if (changed) initDone = false
    }

    /** Uploads one file; returns the index record. */
    suspend fun upload(item: Item, folder: String, onProgress: (Long) -> Unit): JSONObject {
        ready()
        val pfd = app.contentResolver.openFileDescriptor(item.uri, "r") ?: throw IOException("Cannot open ${item.name}")
        val h = handleIds.getAndIncrement()
        handles[h] = pfd to FileInputStream(pfd.fileDescriptor).channel
        onUploadProgress = onProgress
        try {
            return JSONObject(raw("TGE.uploadAndroid($h,${q(item.name)},${item.size},${q(item.mime)},${q(folder)},${item.modifiedMs})"))
        } finally {
            onUploadProgress = null
            handles.remove(h)?.first?.close()
        }
    }

    suspend fun syncIndex(records: List<JSONObject>) {
        ready()
        raw("TGE.syncIndex(${JSONArray(records)})")
    }

    suspend fun refreshIndex(): JSONObject {
        ready()
        return JSONObject(raw("TGE.refreshAndCleanIndex()"))
    }

    suspend fun deleteFileRecord(id: String, mids: List<Int>): Boolean {
        ready()
        val r = raw("TGE.deleteFileRecord(${q(id)}, ${JSONArray(mids)})")
        return r == "true"
    }

    suspend fun download(mid: Int, out: java.io.OutputStream, onProgress: ((Long) -> Unit)? = null) {
        ready()
        val h = handleIds.getAndIncrement()
        writeHandles[h] = out
        onUploadProgress = onProgress
        try {
            raw("TGE.downloadAndroid($h, $mid)")
        } finally {
            writeHandles.remove(h)
            onUploadProgress = null
        }
    }

    suspend fun uploadUri(name: String, size: Long, mime: String, folder: String, uri: Uri, onProgress: (Long) -> Unit): JSONObject {
        ready()
        val pfd = app.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("Cannot open $name")
        val h = handleIds.getAndIncrement()
        handles[h] = pfd to FileInputStream(pfd.fileDescriptor).channel
        onUploadProgress = onProgress
        try {
            val rec = JSONObject(raw("TGE.uploadAndroid($h,${q(name)},$size,${q(mime)},${q(folder)},${System.currentTimeMillis()})"))
            syncIndex(listOf(rec))
            return rec
        } finally {
            onUploadProgress = null
            handles.remove(h)?.first?.close()
        }
    }

    suspend fun logout() { runCatching { ready() }; runCatching { raw("TGE.logout()") }; reset(); creds = null; storageJson = null }
    suspend fun disconnect() { if (initDone) runCatching { raw("TGE.disconnect()") }; initDone = false }

    /** Kills the WebView (aborts any in-flight upload). The next call re-creates and re-initialises it. */
    suspend fun reset() {
        initDone = false
        pending.values.forEach { it.completeExceptionally(JsError("Stopped")) }
        pending.clear()
        withContext(Dispatchers.Main) {
            web?.apply { stopLoading(); destroy() }
            web = null
            loaded = CompletableDeferred()
        }
        handles.values.forEach { runCatching { it.first.close() } }
        handles.clear()
        writeHandles.values.forEach { runCatching { it.close() } }
        writeHandles.clear()
    }

    // ── bridge called from JavaScript ────────────────────────────────────────
    class Bridge {
        @JavascriptInterface fun resolve(id: Int, json: String) { pending[id]?.complete(json) }
        @JavascriptInterface fun reject(id: Int, msg: String) { pending[id]?.completeExceptionally(JsError(msg)) }
        @JavascriptInterface fun onStep(step: String, msg: String) { events.value = LoginEvent(eventSeq.incrementAndGet(), step, msg) }
        @JavascriptInterface fun progress(h: Int, bytes: Double) { onUploadProgress?.invoke(bytes.toLong()) }
        @JavascriptInterface fun readPart(h: Int, offset: Double, len: Int): String {
            val ch = handles[h]?.second ?: return ""
            return try {
                val buf = ByteBuffer.allocate(len)
                var pos = offset.toLong()
                while (buf.hasRemaining()) { val n = ch.read(buf, pos); if (n < 0) break; pos += n }
                Base64.encodeToString(buf.array(), 0, buf.position(), Base64.NO_WRAP)
            } catch (e: Exception) { "" }
        }
        @JavascriptInterface fun writePart(h: Int, b64: String) {
            val out = writeHandles[h] ?: return
            try {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                out.write(bytes)
            } catch (e: Exception) {
                Log.e("TGE", "writePart error", e)
            }
        }
    }
}
