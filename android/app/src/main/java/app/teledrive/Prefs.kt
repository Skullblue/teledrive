package app.teledrive

import android.content.Context
import android.os.Build

object Intervals {
    /** minutes to label. WorkManager's minimum for periodic work is 15 minutes. */
    val options = listOf(15 to "15 min", 30 to "30 min", 60 to "1 hour", 180 to "3 hours", 360 to "6 hours", 720 to "12 hours", 1440 to "24 hours")
    fun label(min: Int) = options.firstOrNull { it.first == min }?.second ?: "$min min"
}

class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("teledrive", Context.MODE_PRIVATE)

    /** From my.telegram.org – required by Telegram for every non-bot client. */
    var apiId: String
        get() = sp.getString("apiId", "") ?: ""
        set(v) = sp.edit().putString("apiId", v).apply()
    var apiHash: String
        get() = sp.getString("apiHash", "") ?: ""
        set(v) = sp.edit().putString("apiHash", v).apply()
    /** Telegram login session (like a "device" in Settings → Devices). Stored privately in the app's storage. */
    var session: String
        get() = sp.getString("session", "") ?: ""
        set(v) = sp.edit().putString("session", v).apply()
    /** JSON of the private "Teledrive Storage" channel. */
    var storage: String
        get() = sp.getString("storage", "") ?: ""
        set(v) = sp.edit().putString("storage", v).apply()
    var accountName: String
        get() = sp.getString("accountName", "") ?: ""
        set(v) = sp.edit().putString("accountName", v).apply()

    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) = sp.edit().putBoolean("enabled", v).apply()
    var intervalMin: Int
        get() = sp.getInt("intervalMin", 360)
        set(v) = sp.edit().putInt("intervalMin", v).apply()
    var wifiOnly: Boolean
        get() = sp.getBoolean("wifiOnly", true)
        set(v) = sp.edit().putBoolean("wifiOnly", v).apply()
    var charging: Boolean
        get() = sp.getBoolean("charging", false)
        set(v) = sp.edit().putBoolean("charging", v).apply()

    var photos: Boolean
        get() = sp.getBoolean("photos", true)
        set(v) = sp.edit().putBoolean("photos", v).apply()
    var videos: Boolean
        get() = sp.getBoolean("videos", true)
        set(v) = sp.edit().putBoolean("videos", v).apply()
    var audio: Boolean
        get() = sp.getBoolean("audio", false)
        set(v) = sp.edit().putBoolean("audio", v).apply()

    /** Persisted SAF tree URIs (any folder the user picked – documents, downloads, WhatsApp, ...). */
    var folders: Set<String>
        get() = sp.getStringSet("folders", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("folders", v).apply()

    /** If > 0, only files added/modified after this unix-second timestamp are backed up. */
    var sinceSec: Long
        get() = sp.getLong("sinceSec", 0L)
        set(v) = sp.edit().putLong("sinceSec", v).apply()

    var lastRunMs: Long
        get() = sp.getLong("lastRunMs", 0L)
        set(v) = sp.edit().putLong("lastRunMs", v).apply()
    var lastStatus: String
        get() = sp.getString("lastStatus", "") ?: ""
        set(v) = sp.edit().putString("lastStatus", v).apply()

    val configured get() = session.isNotBlank() && storage.isNotBlank()

    val deviceName: String
        get() = (Build.MODEL ?: "Android").replace(Regex("[^A-Za-z0-9 _.-]"), "").trim().ifEmpty { "Android" }

    /** Forgets the login and settings but keeps API ID/hash so signing in again is quick. */
    fun disconnect() { val id = apiId; val h = apiHash; sp.edit().clear().putString("apiId", id).putString("apiHash", h).apply() }
}
