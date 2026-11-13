package com.example.booksender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shared helper code for the automatic sender. */
object BookCore {

    const val WHATSAPP = "com.whatsapp"
    const val MAX_SIZE_BYTES = 100L * 1024 * 1024
    private const val CHANNEL_ID = "auto_sender"
    private const val COOLDOWN_MS = 10 * 60 * 1000L

    // Same settings file as the old screens, so the book folder is shared
    fun prefs(ctx: Context) = ctx.getSharedPreferences("booksender", Context.MODE_PRIVATE)

    // ---------- Settings ----------

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("auto_enabled", false)

    fun setEnabled(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean("auto_enabled", value).apply()
    }

    fun groupName(ctx: Context): String = prefs(ctx).getString("auto_group", "") ?: ""

    fun trigger(ctx: Context): String = prefs(ctx).getString("auto_trigger", "book:") ?: "book:"

    fun saveSettings(ctx: Context, group: String, trigger: String) {
        prefs(ctx).edit()
            .putString("auto_group", group.trim())
            .putString("auto_trigger", trigger.trim())
            .apply()
    }

    // The last WhatsApp title seen, so you can copy the exact group name
    fun lastTitle(ctx: Context): String = prefs(ctx).getString("auto_last_title", "") ?: ""

    fun setLastTitle(ctx: Context, title: String) {
        prefs(ctx).edit().putString("auto_last_title", title).apply()
    }

    // ---------- Text helpers ----------

    /** Lowercase, and turn any run of spaces or underscores into one space. */
    fun clean(text: String): String =
        text.lowercase(Locale.ROOT).replace(Regex("[_\\s]+"), " ").trim()

    /** Same as clean, but also drops a .pdf or .epub ending. */
    fun fileKey(name: String): String {
        var t = clean(name)
        if (t.endsWith(".pdf")) {
            t = t.removeSuffix(".pdf")
        } else if (t.endsWith(".epub")) {
            t = t.removeSuffix(".epub")
        }
        return t.trim()
    }

    /** Splits a WhatsApp notification into (group name, message text). */
    fun parseNotification(title: String, text: String): Pair<String, String> {
        var group = title.replace(Regex("\\s*\\(\\d+ messages?\\)$"), "").trim()
        var message = text
        if (group.contains(" @ ")) {
            // Format: "Sender @ Group"
            group = group.substringAfter(" @ ").trim()
        } else {
            // Format: title = group, text = "Sender: message"
            val i = text.indexOf(": ")
            if (i > 0) message = text.substring(i + 2)
        }
        return Pair(group, message.trim())
    }

    /** Returns the book name if the message starts with the trigger word. */
    fun extractBookName(message: String, trigger: String): String? {
        if (trigger.isBlank()) return null
        val m = message.trim()
        if (!m.lowercase(Locale.ROOT).startsWith(trigger.lowercase(Locale.ROOT))) return null
        val name = m.substring(trigger.length).trim()
        return if (name.isEmpty()) null else name
    }

    // ---------- Searching the book folder ----------

    fun folderUri(ctx: Context): Uri? =
        prefs(ctx).getString("folder_uri", null)?.let { Uri.parse(it) }

    fun hasFolderPermission(ctx: Context, uri: Uri): Boolean =
        ctx.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    /** Finds matching books. Throws an Exception with a readable message on problems. */
    fun findBooks(ctx: Context, query: String): List<DocumentFile> {
        val uri = folderUri(ctx)
            ?: throw Exception("No book folder chosen. Open Book Sender > Settings and choose it.")
        if (!hasFolderPermission(ctx, uri)) {
            throw Exception("Folder permission lost. Choose the folder again in Book Sender > Settings.")
        }
        val root = DocumentFile.fromTreeUri(ctx, uri)
            ?: throw Exception("Cannot open the book folder.")
        val wanted = fileKey(query)
        val out = ArrayList<DocumentFile>()
        collect(root, wanted, out, 0)
        // If exactly one file name matches fully, prefer it over partial matches
        val exact = out.filter { fileKey(it.name ?: "") == wanted }
        return if (exact.size == 1) exact else out
    }

    private fun collect(dir: DocumentFile, wanted: String, out: MutableList<DocumentFile>, depth: Int) {
        if (depth > 6) return
        for (f in dir.listFiles()) {
            if (f.isDirectory) {
                collect(f, wanted, out, depth + 1)
            } else {
                val name = f.name ?: continue
                val lower = name.lowercase(Locale.ROOT)
                if (lower.endsWith(".pdf") || lower.endsWith(".epub")) {
                    if (fileKey(name).contains(wanted)) out.add(f)
                }
            }
        }
    }

    // ---------- Preparing and sharing ----------

    /** Copies the book into the app cache so WhatsApp can read it. */
    fun prepareFile(ctx: Context, file: DocumentFile): File {
        val dir = File(ctx.cacheDir, "shared")
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val safeName = (file.name ?: "book").replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val target = File(dir, safeName)
        ctx.contentResolver.openInputStream(file.uri).use { input ->
            if (input == null) throw Exception("Cannot open the file")
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target
    }

    /** Builds the share screen request that goes straight to WhatsApp. */
    fun buildShareIntent(ctx: Context, target: File): Intent {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", target)
        val mime = if (target.name.lowercase(Locale.ROOT).endsWith(".epub")) {
            "application/epub+zip"
        } else {
            "application/pdf"
        }
        val intent = Intent(Intent.ACTION_SEND)
        intent.type = mime
        intent.putExtra(Intent.EXTRA_STREAM, uri)
        intent.clipData = ClipData.newRawUri("book", uri)
        intent.setPackage(WHATSAPP)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent
    }

    // ---------- Log ----------

    private fun loadList(ctx: Context, key: String): JSONArray =
        try {
            JSONArray(prefs(ctx).getString(key, "[]") ?: "[]")
        } catch (e: Exception) {
            JSONArray()
        }

    @Synchronized
    fun addLog(ctx: Context, text: String) {
        val stamp = SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date())
        val old = loadList(ctx, "auto_log")
        val list = JSONArray()
        list.put("$stamp  $text")
        for (i in 0 until old.length()) {
            if (list.length() >= 100) break
            list.put(old.getString(i))
        }
        prefs(ctx).edit().putString("auto_log", list.toString()).apply()
    }

    fun getLog(ctx: Context): List<String> {
        val arr = loadList(ctx, "auto_log")
        return (0 until arr.length()).map { arr.getString(it) }
    }

    fun clearLog(ctx: Context) {
        prefs(ctx).edit().remove("auto_log").apply()
    }

    // ---------- 10 minute rule: never repeat the same request ----------

    private fun loadSent(ctx: Context): JSONObject =
        try {
            JSONObject(prefs(ctx).getString("auto_sent", "{}") ?: "{}")
        } catch (e: Exception) {
            JSONObject()
        }

    @Synchronized
    fun isOnCooldown(ctx: Context, key: String): Boolean {
        val last = loadSent(ctx).optLong(key, 0L)
        return System.currentTimeMillis() - last < COOLDOWN_MS
    }

    @Synchronized
    fun markNow(ctx: Context, key: String) {
        val old = loadSent(ctx)
        val now = System.currentTimeMillis()
        val fresh = JSONObject()
        val keys = old.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (now - old.optLong(k, 0L) < COOLDOWN_MS) fresh.put(k, old.getLong(k))
        }
        fresh.put(key, now)
        prefs(ctx).edit().putString("auto_sent", fresh.toString()).apply()
    }

    // ---------- Notification to you ----------

    fun notifyUser(ctx: Context, text: String) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Book Sender Auto", NotificationManager.IMPORTANCE_DEFAULT)
            )
            val n = Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Book Sender")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build()
            nm.notify((System.currentTimeMillis() % 100000).toInt(), n)
        } catch (e: Exception) {
            // Notification permission may be missing; the log still has the message
        }
    }
}
