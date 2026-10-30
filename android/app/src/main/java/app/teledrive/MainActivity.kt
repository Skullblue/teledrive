package app.teledrive

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.text.format.DateUtils
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = Prefs(this)
        JsEngine.attach(this)
        setContent { TeledriveTheme { Root(prefs) } }
    }
}

@Composable
fun TeledriveTheme(content: @Composable () -> Unit) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val scheme = if (dark) darkColorScheme(
        primary = Color(0xFF3CB4EE), onPrimary = Color(0xFF00212F), background = Color(0xFF0E141B), surface = Color(0xFF161E27),
        surfaceVariant = Color(0xFF1D2833), onSurface = Color(0xFFE8EEF5), onBackground = Color(0xFFE8EEF5), onSurfaceVariant = Color(0xFF8FA0B3),
        primaryContainer = Color(0xFF12303F), onPrimaryContainer = Color(0xFF9FDCF7),
    ) else lightColorScheme(
        primary = Color(0xFF229ED9), onPrimary = Color.White, background = Color(0xFFF5F7FA), surface = Color.White,
        surfaceVariant = Color(0xFFEEF2F7), onSurface = Color(0xFF0F1722), onBackground = Color(0xFF0F1722), onSurfaceVariant = Color(0xFF6B7686),
        primaryContainer = Color(0xFFE3F4FC), onPrimaryContainer = Color(0xFF0B5F85),
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun Root(prefs: Prefs) {
    var configured by remember { mutableStateOf(prefs.configured) }
    if (!configured) SetupScreen(prefs) { configured = true }
    else MainNavigationScreen(prefs) { configured = false }
}

// ───────────────────────── sign in with your phone number ─────────────────────────

private enum class Step { API, PHONE, CODE, PASSWORD, FINISH }

@Composable
fun SetupScreen(prefs: Prefs, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(Step.API) }
    var apiId by remember { mutableStateOf(prefs.apiId) }
    var apiHash by remember { mutableStateOf(prefs.apiHash) }
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var setupCode by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(if (prefs.lastStatus.contains("sign in again")) prefs.lastStatus else "") }
    var busy by remember { mutableStateOf(false) }
    val ev by JsEngine.events.collectAsState()

    fun fail(e: Throwable) { error = e.message ?: "Something went wrong"; busy = false }

    LaunchedEffect(ev.seq) {
        when (ev.step) {
            "code" -> { step = Step.CODE; error = ev.message; busy = false; code = "" }
            "password" -> { step = Step.PASSWORD; error = ev.message; busy = false; password = "" }
            "error" -> { step = Step.PHONE; error = ev.message; busy = false }
            "done" -> {
                step = Step.FINISH; busy = true
                try {
                    val session = JsEngine.sessionString()
                    val me = JsEngine.me()
                    val storage = JsEngine.ensureStorage()
                    prefs.apiId = apiId; prefs.apiHash = apiHash
                    prefs.session = session; prefs.storage = storage; prefs.accountName = me.optString("name")
                    prefs.lastStatus = ""
                    onDone()
                } catch (e: Exception) { step = Step.PHONE; fail(e) }
            }
        }
    }
    LaunchedEffect(Unit) {
        if (apiId.isNotBlank() && apiHash.isNotBlank()) {
            busy = true
            try { JsEngine.beginLogin(apiId, apiHash); step = Step.PHONE; busy = false } catch (e: Exception) { fail(e) }
        }
    }

    Scaffold { pad ->
        Column(
            Modifier.padding(pad).padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Icon(Icons.Default.CloudUpload, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            Text("Teledrive", fontSize = 30.sp, fontWeight = FontWeight.Bold)

            when (step) {
                Step.API -> {
                    Text(
                        "Sign in with your own Telegram account – no bot needed.\n\n" +
                            "Telegram asks every app for a free API ID and API hash (one time, 1 minute): open my.telegram.org/apps in a browser, log in with your phone number, create an application (any name) and copy api_id and api_hash here.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(apiId, { apiId = it.trim() }, label = { Text("API ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(apiHash, { apiHash = it.trim() }, label = { Text("API hash") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        setupCode, {
                            setupCode = it
                            runCatching {
                                val o = JSONObject(String(Base64.decode(it.trim(), Base64.DEFAULT)))
                                apiId = o.getString("a"); apiHash = o.getString("h")
                            }
                        },
                        label = { Text("Setup code from the web app (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                }
                Step.PHONE -> {
                    Text("Enter the phone number of your Telegram account. Telegram will send a login code to your Telegram app.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(phone, { phone = it }, label = { Text("Phone (+8801712345678)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                Step.CODE -> {
                    Text("Enter the code Telegram sent to $phone (check the \"Telegram\" chat in your Telegram app, or SMS).", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(code, { code = it.trim() }, label = { Text("Login code") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                Step.PASSWORD -> {
                    Text("Your account has two-step verification. Enter your Telegram cloud password.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                }
                Step.FINISH -> Text("Setting up your private storage channel…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)

            Button(
                onClick = {
                    error = ""
                    when (step) {
                        Step.API -> {
                            if (!apiId.all { it.isDigit() } || apiId.isEmpty() || apiHash.length != 32) { error = "Enter the numeric API ID and the 32-character API hash."; return@Button }
                            busy = true
                            scope.launch { try { JsEngine.beginLogin(apiId, apiHash); step = Step.PHONE; busy = false } catch (e: Exception) { fail(e) } }
                        }
                        Step.PHONE -> {
                            val p = phone.replace(Regex("[\\s()-]"), "")
                            if (!Regex("^\\+\\d{7,15}$").matches(p)) { error = "Use the international format, e.g. +8801712345678."; return@Button }
                            phone = p; busy = true
                            scope.launch { try { JsEngine.startLogin(p) } catch (e: Exception) { fail(e) } }
                        }
                        Step.CODE -> { if (code.isNotEmpty()) { busy = true; scope.launch { JsEngine.submitCode(code) } } }
                        Step.PASSWORD -> { if (password.isNotEmpty()) { busy = true; scope.launch { JsEngine.submitPassword(password) } } }
                        Step.FINISH -> {}
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp),
            ) {
                Text(
                    if (busy) "Working…" else when (step) {
                        Step.API -> "Continue"; Step.PHONE -> "Send code"; Step.CODE -> "Verify"; Step.PASSWORD -> "Unlock"; Step.FINISH -> "Please wait"
                    }
                )
            }
            if (step == Step.CODE || step == Step.PASSWORD) {
                TextButton(onClick = { step = Step.PHONE; error = ""; busy = false }) { Text("Use a different number") }
            }
        }
    }
}

// ───────────────────────── main navigation ─────────────────────────

@Composable
fun MainNavigationScreen(prefs: Prefs, onDisconnect: () -> Unit) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Cloud, contentDescription = "Dashboard") },
                    label = { Text("Dashboard") },
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Sync, contentDescription = "Auto Backup") },
                    label = { Text("Auto Backup") },
                )
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (selectedTab == 0) {
                DashboardScreen(prefs)
            } else {
                BackupScreen(prefs, onDisconnect)
            }
        }
    }
}

// ───────────────────────── models & helpers ─────────────────────────

data class DriveFile(
    val id: String,
    val name: String,
    val size: Long,
    val mime: String,
    val folder: String,
    val ts: Long,
    val src: String,
    val chunks: List<Int>,
)

fun fmtSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    val gb = mb / 1024.0
    return "%.2f GB".format(gb)
}

fun fmtDate(ms: Long): String {
    if (ms <= 0) return ""
    val sdf = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    return sdf.format(Date(ms))
}

fun fileCat(mime: String, name: String): String {
    val m = mime.lowercase()
    val n = name.lowercase()
    return when {
        m.startsWith("image/") -> "image"
        m.startsWith("video/") -> "video"
        m.startsWith("audio/") -> "audio"
        m.contains("pdf") || m.contains("word") || m.contains("sheet") || m.contains("text/") ||
                Regex("\\.(docx?|xlsx?|pptx?|txt|md|csv|pdf|odt|rtf)$").containsMatchIn(n) -> "doc"
        m.contains("zip") || m.contains("rar") || m.contains("tar") || m.contains("gzip") ||
                Regex("\\.(zip|rar|7z|tar|gz)$").containsMatchIn(n) -> "archive"
        else -> "other"
    }
}

fun categoryIcon(cat: String): ImageVector = when (cat) {
    "image" -> Icons.Default.Image
    "video" -> Icons.Default.VideoLibrary
    "audio" -> Icons.Default.MusicNote
    "doc" -> Icons.Default.Description
    "archive" -> Icons.Default.Archive
    else -> Icons.Default.InsertDriveFile
}

fun queryUriMeta(ctx: Context, uri: Uri): Pair<String, Long> {
    var name = "file_${System.currentTimeMillis()}"
    var size = 0L
    ctx.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (cursor.moveToFirst()) {
            if (nameIdx >= 0) name = cursor.getString(nameIdx) ?: name
            if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
        }
    }
    return name to size
}

// ───────────────────────── Dashboard Screen ─────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(prefs: Prefs) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf<List<DriveFile>>(emptyList()) }
    var folders by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var statusText by remember { mutableStateOf("") }
    var selectedCat by remember { mutableStateOf("all") }
    var currentPath by remember { mutableStateOf("/") }
    var searchQuery by remember { mutableStateOf("") }
    var selectedFile by remember { mutableStateOf<DriveFile?>(null) }
    var showDeleteConfirm by remember { mutableStateOf<DriveFile?>(null) }
    var opBusy by remember { mutableStateOf(false) }
    var opProgress by remember { mutableStateOf("") }

    fun refresh() {
        loading = true
        statusText = "Syncing with Telegram…"
        scope.launch {
            try {
                JsEngine.configure(prefs.apiId, prefs.apiHash, prefs.session, prefs.storage)
                val json = JsEngine.refreshIndex()
                val filesArr = json.optJSONArray("files")
                val parsedFiles = mutableListOf<DriveFile>()
                if (filesArr != null) {
                    for (i in 0 until filesArr.length()) {
                        val obj = filesArr.optJSONObject(i) ?: continue
                        val chunksArr = obj.optJSONArray("chunks")
                        val chunks = mutableListOf<Int>()
                        if (chunksArr != null) {
                            for (j in 0 until chunksArr.length()) {
                                val c = chunksArr.optJSONObject(j)
                                if (c != null && c.has("mid")) chunks.add(c.getInt("mid"))
                            }
                        }
                        parsedFiles.add(
                            DriveFile(
                                id = obj.optString("id"),
                                name = obj.optString("name", "Unknown"),
                                size = obj.optLong("size", 0L),
                                mime = obj.optString("mime", "application/octet-stream"),
                                folder = obj.optString("folder", "/"),
                                ts = obj.optLong("up", obj.optLong("ts", 0L)),
                                src = obj.optString("src", "web"),
                                chunks = chunks,
                            )
                        )
                    }
                }
                val foldersArr = json.optJSONArray("folders")
                val parsedFolders = mutableListOf<String>()
                if (foldersArr != null) {
                    for (i in 0 until foldersArr.length()) {
                        parsedFolders.add(foldersArr.optString(i))
                    }
                }
                files = parsedFiles
                folders = parsedFolders
                statusText = ""
            } catch (e: Exception) {
                statusText = "Failed to sync: ${e.message}"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val (name, size) = queryUriMeta(ctx, uri)
            val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
            opBusy = true
            opProgress = "Uploading $name…"
            scope.launch {
                try {
                    JsEngine.uploadUri(name, size, mime, currentPath, uri) { sent ->
                        if (size > 0) opProgress = "Uploading $name (${sent * 100 / size}%)"
                    }
                    Toast.makeText(ctx, "Uploaded $name", Toast.LENGTH_SHORT).show()
                    refresh()
                } catch (e: Exception) {
                    Toast.makeText(ctx, "Upload failed: ${e.message}", Toast.LENGTH_LONG).show()
                } finally {
                    opBusy = false
                    opProgress = ""
                }
            }
        }
    }

    BackHandler(enabled = currentPath != "/" && selectedCat == "all" && searchQuery.isEmpty()) {
        val lastSlash = currentPath.lastIndexOf('/')
        currentPath = if (lastSlash <= 0) "/" else currentPath.substring(0, lastSlash)
    }

    val totalSize = remember(files) { files.sumOf { it.size } }
    val counts = remember(files) {
        val map = mutableMapOf<String, Int>()
        for (f in files) {
            val c = fileCat(f.mime, f.name)
            map[c] = (map[c] ?: 0) + 1
            if (f.src == "android" || f.folder.startsWith("/Phone")) {
                map["phone"] = (map["phone"] ?: 0) + 1
            }
        }
        map
    }

    val displayedFolders = remember(folders, files, currentPath, selectedCat, searchQuery) {
        if (selectedCat != "all" || searchQuery.isNotEmpty()) emptyList()
        else {
            val base = if (currentPath == "/") "/" else "$currentPath/"
            val set = mutableSetOf<String>()
            for (p in (folders + files.map { it.folder })) {
                if (p != currentPath && p.startsWith(base)) {
                    val seg = p.substring(base.length).split('/')[0]
                    if (seg.isNotEmpty()) set.add(seg)
                }
            }
            set.sorted()
        }
    }

    val filteredFiles = remember(files, selectedCat, currentPath, searchQuery) {
        val q = searchQuery.trim().lowercase()
        var list = files
        if (q.isNotEmpty()) {
            list = list.filter { it.name.lowercase().contains(q) || it.folder.lowercase().contains(q) }
        } else when (selectedCat) {
            "all" -> list = list.filter { it.folder == currentPath }
            "phone" -> list = list.filter { it.src == "android" || it.folder.startsWith("/Phone") }
            else -> list = list.filter { fileCat(it.mime, it.name) == selectedCat }
        }
        list.sortedByDescending { it.ts }
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { uploadLauncher.launch("*/*") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Upload, "Upload")
                    Spacer(Modifier.width(6.dp))
                    Text("Upload", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            // Storage Overview Card
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Teledrive Cloud", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text(
                                "${fmtSize(totalSize)} in ${files.size} file" + (if (files.size == 1) "" else "s"),
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                            )
                        }
                        IconButton(onClick = { refresh() }) {
                            Icon(Icons.Default.Refresh, "Refresh", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                    if (statusText.isNotEmpty()) {
                        Text(statusText, fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search files & folders…") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Close, "Clear") }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            // Category Chips
            val cats = listOf(
                "all" to "All files" to Icons.Default.Folder,
                "image" to "Photos" to Icons.Default.Image,
                "video" to "Videos" to Icons.Default.VideoLibrary,
                "audio" to "Audio" to Icons.Default.MusicNote,
                "doc" to "Documents" to Icons.Default.Description,
                "phone" to "Phone backups" to Icons.Default.PhoneAndroid,
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(cats) { (pair, icon) ->
                    val (key, label) = pair
                    val count = if (key == "all") files.size else counts[key] ?: 0
                    FilterChip(
                        selected = selectedCat == key,
                        onClick = { selectedCat = key; searchQuery = "" },
                        leadingIcon = { Icon(icon, null, modifier = Modifier.size(18.dp)) },
                        label = { Text("$label ($count)") },
                    )
                }
            }

            // Breadcrumb navigation for "all"
            if (selectedCat == "all" && searchQuery.isEmpty() && currentPath != "/") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    IconButton(
                        onClick = {
                            val lastSlash = currentPath.lastIndexOf('/')
                            currentPath = if (lastSlash <= 0) "/" else currentPath.substring(0, lastSlash)
                        },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(currentPath, fontWeight = FontWeight.Medium, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            // File & Folder List
            if (loading && files.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Text("Loading your Telegram drive…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else if (displayedFolders.isEmpty() && filteredFiles.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.CloudQueue, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                        Text("No files found", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text("Upload files or turn on Auto Backup", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Folders
                    items(displayedFolders) { dirName ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().clickable {
                                currentPath = if (currentPath == "/") "/$dirName" else "$currentPath/$dirName"
                            },
                        ) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(dirName, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("Folder", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    // Files
                    items(filteredFiles) { file ->
                        val cat = fileCat(file.mime, file.name)
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().clickable { selectedFile = file },
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier.size(42.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(10.dp)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(categoryIcon(cat), null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(24.dp))
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(file.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "${fmtSize(file.size)} · ${fmtDate(file.ts)}" + if (selectedCat != "all" && file.folder != "/") " · ${file.folder}" else "",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = { selectedFile = file }) {
                                    Icon(Icons.Default.MoreVert, "Actions", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Action Dialog for a tapped file
    selectedFile?.let { file ->
        val cat = fileCat(file.mime, file.name)
        AlertDialog(
            onDismissRequest = { selectedFile = null },
            icon = { Icon(categoryIcon(cat), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp)) },
            title = { Text(file.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 18.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Size: ${fmtSize(file.size)}", fontSize = 13.sp)
                    Text("Folder: ${file.folder}", fontSize = 13.sp)
                    Text("Date: ${fmtDate(file.ts)}", fontSize = 13.sp)
                    Text("Type: ${file.mime}", fontSize = 13.sp)
                    if (opBusy) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(opProgress.ifEmpty { "Working…" }, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = file
                        selectedFile = null
                        showDeleteConfirm = target
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        enabled = !opBusy,
                        onClick = {
                            opBusy = true
                            opProgress = "Downloading ${file.name}…"
                            scope.launch {
                                try {
                                    val (uri, out) = saveToDownloads(ctx, file.name)
                                    out.use { stream ->
                                        for ((idx, mid) in file.chunks.withIndex()) {
                                            opProgress = "Downloading part ${idx + 1}/${file.chunks.size}…"
                                            JsEngine.download(mid, stream)
                                        }
                                    }
                                    Toast.makeText(ctx, "Saved to Downloads/Teledrive/${file.name}", Toast.LENGTH_LONG).show()
                                    selectedFile = null
                                } catch (e: Exception) {
                                    Toast.makeText(ctx, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                                } finally {
                                    opBusy = false
                                    opProgress = ""
                                }
                            }
                        },
                    ) {
                        Icon(Icons.Default.Download, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Save")
                    }

                    OutlinedButton(
                        enabled = !opBusy,
                        onClick = {
                            opBusy = true
                            opProgress = "Loading preview…"
                            scope.launch {
                                try {
                                    val previewDir = File(ctx.cacheDir, "preview").apply { mkdirs() }
                                    val cacheFile = File(previewDir, file.name)
                                    FileOutputStream(cacheFile).use { stream ->
                                        for (mid in file.chunks) {
                                            JsEngine.download(mid, stream)
                                        }
                                    }
                                    openFileWithSystem(ctx, cacheFile, file.mime)
                                    selectedFile = null
                                } catch (e: Exception) {
                                    Toast.makeText(ctx, "Preview failed: ${e.message}", Toast.LENGTH_LONG).show()
                                } finally {
                                    opBusy = false
                                    opProgress = ""
                                }
                            }
                        },
                    ) {
                        Icon(Icons.Default.Visibility, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("View")
                    }
                }
            },
        )
    }

    // Delete Confirmation Dialog
    showDeleteConfirm?.let { file ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            icon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Delete file?") },
            text = { Text("Delete \"${file.name}\" from your Telegram channel and cloud drive?") },
            confirmButton = {
                Button(
                    onClick = {
                        val toDelete = file
                        showDeleteConfirm = null
                        scope.launch {
                            try {
                                JsEngine.deleteFileRecord(toDelete.id, toDelete.chunks)
                                Toast.makeText(ctx, "Deleted ${toDelete.name}", Toast.LENGTH_SHORT).show()
                                refresh()
                            } catch (e: Exception) {
                                Toast.makeText(ctx, "Delete failed: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) { Text("Cancel") }
            },
        )
    }
}

fun saveToDownloads(ctx: Context, name: String): Pair<Uri, java.io.OutputStream> {
    if (Build.VERSION.SDK_INT >= 29) {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
            put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/Teledrive")
        }
        val uri = ctx.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Cannot create download entry")
        val out = ctx.contentResolver.openOutputStream(uri) ?: throw IOException("Cannot write to download file")
        return uri to out
    } else {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Teledrive")
        dir.mkdirs()
        val file = File(dir, name)
        return Uri.fromFile(file) to FileOutputStream(file)
    }
}

fun openFileWithSystem(ctx: Context, file: File, mime: String) {
    try {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.provider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime.ifEmpty { "*/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(ctx, "No app found to open this file", Toast.LENGTH_SHORT).show()
    }
}

// ───────────────────────── Auto Backup Screen ─────────────────────────

private fun requiredPermissions(photos: Boolean, videos: Boolean, audio: Boolean): List<String> {
    val l = ArrayList<String>()
    if (Build.VERSION.SDK_INT >= 33) {
        if (photos) l += Manifest.permission.READ_MEDIA_IMAGES
        if (videos) l += Manifest.permission.READ_MEDIA_VIDEO
        if (audio) l += Manifest.permission.READ_MEDIA_AUDIO
        l += Manifest.permission.POST_NOTIFICATIONS
    } else if (photos || videos || audio) {
        l += Manifest.permission.READ_EXTERNAL_STORAGE
    }
    return l
}

private fun missing(ctx: Context, perms: List<String>) =
    perms.filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(prefs: Prefs, onDisconnect: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(prefs.enabled) }
    var interval by remember { mutableIntStateOf(prefs.intervalMin) }
    var wifi by remember { mutableStateOf(prefs.wifiOnly) }
    var charging by remember { mutableStateOf(prefs.charging) }
    var photos by remember { mutableStateOf(prefs.photos) }
    var videos by remember { mutableStateOf(prefs.videos) }
    var audio by remember { mutableStateOf(prefs.audio) }
    var onlyNew by remember { mutableStateOf(prefs.sinceSec > 0) }
    val folders = remember { mutableStateListOf<String>().apply { addAll(prefs.folders) } }
    var permTick by remember { mutableIntStateOf(0) }
    val progress by BackupStatus.state.collectAsState()

    val missingPerms = remember(permTick, photos, videos, audio) { missing(ctx, requiredPermissions(photos, videos, audio)) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permTick++ }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            if (uri.toString() !in folders) { folders.add(uri.toString()); prefs.folders = folders.toSet() }
            Scheduler.apply(ctx, prefs)
        }
    }

    fun save() { Scheduler.apply(ctx, prefs) }
    fun ensurePerms(p: Boolean = photos, v: Boolean = videos, a: Boolean = audio) {
        val m = missing(ctx, requiredPermissions(p, v, a))
        if (m.isNotEmpty()) permLauncher.launch(m.toTypedArray())
    }
    val uploadedCount = remember(progress.running, permTick) { Store(ctx).uploadedCount() }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Sync, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Auto Backup", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(prefs.accountName.ifEmpty { "Telegram" }, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
        }

        // status card
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            shape = RoundedCornerShape(20.dp),
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Auto backup", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(
                            if (enabled) "Every ${Intervals.label(interval)}" + (if (wifi) " · Wi-Fi only" else "") + (if (charging) " · charging" else "") else "Off",
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    Switch(checked = enabled, onCheckedChange = {
                        enabled = it; prefs.enabled = it
                        if (it) ensurePerms()
                        save()
                    })
                }
                if (progress.running) {
                    if (progress.total > 0) LinearProgressIndicator(progress = { progress.done.toFloat() / progress.total }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        if (progress.total > 0) "Uploading ${progress.done + 1} of ${progress.total} · ${progress.current}" else progress.message.ifEmpty { "Working…" },
                        color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 2,
                    )
                } else {
                    Text(
                        buildString {
                            append("$uploadedCount files backed up")
                            if (prefs.lastRunMs > 0) append(" · last run ${DateUtils.getRelativeTimeSpanString(prefs.lastRunMs)}")
                            if (prefs.lastStatus.isNotEmpty()) append("\n${prefs.lastStatus}")
                        },
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (progress.running) {
                        OutlinedButton(onClick = { BackupStatus.stopRequested = true; scope.launch { JsEngine.reset() } }) { Text("Stop") }
                    } else {
                        Button(onClick = { ensurePerms(); Scheduler.runNow(ctx, prefs) }, shape = RoundedCornerShape(12.dp)) { Text("Back up now") }
                    }
                }
            }
        }

        if (missingPerms.isNotEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Permission needed to read your files and show progress.", Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                    TextButton(onClick = { permLauncher.launch(missingPerms.toTypedArray()) }) { Text("Grant") }
                }
            }
        }

        Section("What to back up") {
            ToggleRow(Icons.Default.Image, "Photos", "Camera roll, screenshots, downloads", photos) { photos = it; prefs.photos = it; if (it) ensurePerms(p = true); save() }
            ToggleRow(Icons.Default.VideoLibrary, "Videos", "Can be large – Wi-Fi recommended", videos) { videos = it; prefs.videos = it; if (it) ensurePerms(v = true); save() }
            ToggleRow(Icons.Default.MusicNote, "Audio", "Music, recordings, voice notes", audio) { audio = it; prefs.audio = it; if (it) ensurePerms(a = true); save() }
            Text("Other files & folders", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
            Text("Pick any folder (Documents, Downloads, WhatsApp…). Subfolders are included.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            folders.forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text(DocumentFile.fromTreeUri(ctx, Uri.parse(f))?.name ?: f, Modifier.weight(1f), maxLines = 1)
                    IconButton(onClick = {
                        folders.remove(f); prefs.folders = folders.toSet()
                        runCatching { ctx.contentResolver.releasePersistableUriPermission(Uri.parse(f), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                        save()
                    }) { Icon(Icons.Default.Close, "Remove") }
                }
            }
            OutlinedButton(onClick = { folderLauncher.launch(null) }) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Add folder")
            }
        }

        Section("How often") {
            Text("Check for new files every…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Intervals.options.forEach { (min, label) ->
                    FilterChip(selected = interval == min, onClick = { interval = min; prefs.intervalMin = min; save() }, label = { Text(label) })
                }
            }
            Text("Android runs background work at its own pace, so the real time can be slightly later than selected.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }

        Section("Conditions") {
            ToggleRow(Icons.Default.Wifi, "Wi-Fi only", "Don't use mobile data", wifi) { wifi = it; prefs.wifiOnly = it; save() }
            ToggleRow(Icons.Default.BatteryChargingFull, "Only while charging", "Saves battery", charging) { charging = it; prefs.charging = it; save() }
            ToggleRow(Icons.Default.NewReleases, "Only new files", "Skip everything that exists today", onlyNew) {
                onlyNew = it; prefs.sinceSec = if (it) System.currentTimeMillis() / 1000 else 0L
            }
        }

        Section("Account") {
            Text("Files go to the private channel “Teledrive Storage” in your Telegram account. The same files appear in the Teledrive web app.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            OutlinedButton(
                onClick = {
                    scope.launch {
                        Scheduler.cancelAll(ctx)
                        runCatching { JsEngine.configure(prefs.apiId, prefs.apiHash, prefs.session, prefs.storage); JsEngine.logout() }
                        Store(ctx).reset(); prefs.disconnect(); onDisconnect()
                    }
                },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Log out of Telegram") }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun ToggleRow(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
