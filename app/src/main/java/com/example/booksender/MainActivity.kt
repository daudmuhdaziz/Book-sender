package com.example.booksender

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("booksender", MODE_PRIVATE) }

    // Three screens inside one activity
    private lateinit var searchScreen: LinearLayout
    private lateinit var historyScreen: LinearLayout
    private lateinit var settingsScreen: LinearLayout

    // Search screen views
    private lateinit var queryInput: EditText
    private lateinit var statusText: TextView
    private lateinit var resultsBox: LinearLayout
    private lateinit var recentBox: LinearLayout

    // Settings and history views
    private lateinit var folderText: TextView
    private lateinit var historyBox: LinearLayout

    // Folder picker: the user chooses the book folder once, permission is saved
    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                prefs.edit().putString("folder_uri", uri.toString()).apply()
                refreshSettings()
                Toast.makeText(this, "Folder saved", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Could not save folder: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---------- Small UI helpers ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun weightParams() =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun makeColumn(): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        return l
    }

    private fun makeText(text: String, size: Float = 16f): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = size
        t.setPadding(0, dp(6), 0, dp(6))
        return t
    }

    private fun makeButton(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        return b
    }

    // ---------- Screen setup ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Book Sender"

        val root = makeColumn()
        root.setPadding(dp(16), dp(16), dp(16), dp(16))

        // Top tab buttons
        val tabs = LinearLayout(this)
        tabs.orientation = LinearLayout.HORIZONTAL
        tabs.addView(makeButton("Search") { showScreen(0) }, weightParams())
        tabs.addView(makeButton("History") { showScreen(1) }, weightParams())
        tabs.addView(makeButton("Settings") { showScreen(2) }, weightParams())
        root.addView(tabs)

        // Search screen
        searchScreen = makeColumn()
        queryInput = EditText(this)
        queryInput.hint = "Book name"
        queryInput.setSingleLine(true)
        searchScreen.addView(queryInput)
        searchScreen.addView(makeButton("Search") { doSearch() })
        statusText = makeText("Type a book name and press Search.")
        searchScreen.addView(statusText)
        resultsBox = makeColumn()
        searchScreen.addView(resultsBox)
        searchScreen.addView(makeText("Recent books", 18f))
        recentBox = makeColumn()
        searchScreen.addView(recentBox)
        root.addView(searchScreen)

        // History screen
        historyScreen = makeColumn()
        historyScreen.addView(makeButton("Clear history") {
            prefs.edit().remove("history").apply()
            refreshHistory()
        })
        historyBox = makeColumn()
        historyScreen.addView(historyBox)
        root.addView(historyScreen)

        // Settings screen
        settingsScreen = makeColumn()
        folderText = makeText("")
        settingsScreen.addView(folderText)
        settingsScreen.addView(makeButton("Choose book folder") { folderPicker.launch(null) })
        root.addView(settingsScreen)

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)

        showScreen(0)
    }

    private fun showScreen(index: Int) {
        searchScreen.visibility = if (index == 0) View.VISIBLE else View.GONE
        historyScreen.visibility = if (index == 1) View.VISIBLE else View.GONE
        settingsScreen.visibility = if (index == 2) View.VISIBLE else View.GONE
        when (index) {
            0 -> refreshRecent()
            1 -> refreshHistory()
            2 -> refreshSettings()
        }
    }

    // ---------- Folder and permission ----------

    private fun getFolderUri(): Uri? {
        val s = prefs.getString("folder_uri", null) ?: return null
        return Uri.parse(s)
    }

    private fun hasFolderPermission(uri: Uri): Boolean {
        return contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
    }

    private fun refreshSettings() {
        val uri = getFolderUri()
        if (uri == null) {
            folderText.text = "No folder chosen yet."
        } else {
            val note = if (hasFolderPermission(uri)) "" else "\n\nPermission lost. Please choose the folder again."
            folderText.text = "Current folder:\n" + Uri.decode(uri.toString()) + note
        }
    }

    // ---------- Search ----------

    private fun normalize(text: String): String {
        var t = text.lowercase(Locale.ROOT)
        if (t.endsWith(".pdf")) t = t.removeSuffix(".pdf")
        else if (t.endsWith(".epub")) t = t.removeSuffix(".epub")
        return t.replace(Regex("[_\\s]+"), " ").trim()
    }

    private fun collectMatches(
        dir: DocumentFile, wanted: String, out: MutableList<DocumentFile>, depth: Int
    ) {
        if (depth > 6) return
        for (f in dir.listFiles()) {
            if (f.isDirectory) {
                collectMatches(f, wanted, out, depth + 1)
            } else {
                val name = f.name ?: continue
                val lower = name.lowercase(Locale.ROOT)
                if (lower.endsWith(".pdf") || lower.endsWith(".epub")) {
                    if (normalize(name).contains(wanted)) out.add(f)
                }
            }
        }
    }

    private fun doSearch() {
        val query = queryInput.text.toString().trim()
        resultsBox.removeAllViews()
        if (query.isEmpty()) {
            statusText.text = "Type a book name first."
            return
        }
        val folderUri = getFolderUri()
        if (folderUri == null) {
            statusText.text = "No folder chosen. Open Settings and choose your book folder."
            return
        }
        if (!hasFolderPermission(folderUri)) {
            statusText.text = "Folder permission was lost. Open Settings and choose the folder again."
            return
        }
        statusText.text = "Searching..."
        Thread {
            val matches = ArrayList<DocumentFile>()
            var error: String? = null
            try {
                val rootDir = DocumentFile.fromTreeUri(this, folderUri)
                if (rootDir != null) collectMatches(rootDir, normalize(query), matches, 0)
            } catch (e: Exception) {
                error = e.message ?: "Unknown error"
            }
            runOnUiThread {
                if (error != null) {
                    statusText.text = "Error: $error"
                } else {
                    showResults(query, matches)
                }
            }
        }.start()
    }

    private fun showResults(query: String, matches: List<DocumentFile>) {
        resultsBox.removeAllViews()
        if (matches.isEmpty()) {
            statusText.text = "Not found: \"$query\""
            addHistory(query, "Not found", false)
            return
        }
        statusText.text = if (matches.size == 1) "1 file found:" else "${matches.size} files found. Tap the right one:"
        addHistory(query, "Found ${matches.size}: " + matches.joinToString(", ") { it.name ?: "?" }, false)
        for (f in matches) {
            val mb = f.length() / (1024 * 1024)
            val b = makeButton("Send in WhatsApp:\n${f.name} ($mb MB)") { shareFile(f, query) }
            b.textSize = 16f
            resultsBox.addView(b)
        }
    }

    // ---------- Sharing ----------

    private fun shareFile(file: DocumentFile, query: String) {
        val name = file.name ?: "book"
        if (file.length() > 100L * 1024 * 1024) {
            Toast.makeText(this, "This file is over 100 MB. WhatsApp may reject it.", Toast.LENGTH_LONG).show()
        }
        statusText.text = "Preparing file..."
        Thread {
            try {
                // Copy the file to the app cache so WhatsApp can read it safely
                val dir = File(cacheDir, "shared")
                dir.mkdirs()
                dir.listFiles()?.forEach { it.delete() }
                val safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                val target = File(dir, safeName)
                contentResolver.openInputStream(file.uri).use { input ->
                    if (input == null) throw Exception("Cannot open file")
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                runOnUiThread { launchShare(target, name, query, file.uri.toString()) }
            } catch (e: Exception) {
                runOnUiThread { statusText.text = "Error: ${e.message}" }
            }
        }.start()
    }

    private fun launchShare(target: File, name: String, query: String, sourceUri: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", target)
        val mime = if (name.lowercase(Locale.ROOT).endsWith(".epub")) "application/epub+zip" else "application/pdf"
        val intent = Intent(Intent.ACTION_SEND)
        intent.type = mime
        intent.putExtra(Intent.EXTRA_STREAM, uri)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            // Try WhatsApp directly
            val direct = Intent(intent)
            direct.setPackage("com.whatsapp")
            startActivity(direct)
        } catch (e: ActivityNotFoundException) {
            // WhatsApp not installed: use the normal share sheet
            startActivity(Intent.createChooser(intent, "Share book"))
        }
        addHistory(query, "Opened share screen: $name", true)
        addRecent(name, sourceUri)
        statusText.text = "Choose the group in WhatsApp and press send."
    }

    // ---------- History and recent books ----------

    private fun loadArray(key: String): JSONArray {
        return try {
            JSONArray(prefs.getString(key, "[]") ?: "[]")
        } catch (e: Exception) {
            JSONArray()
        }
    }

    private fun addHistory(query: String, result: String, shared: Boolean) {
        val old = loadArray("history")
        val list = JSONArray()
        val e = JSONObject()
        e.put("time", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()))
        e.put("query", query)
        e.put("result", result)
        e.put("shared", shared)
        list.put(e)
        for (i in 0 until old.length()) {
            if (list.length() >= 200) break
            list.put(old.getJSONObject(i))
        }
        prefs.edit().putString("history", list.toString()).apply()
    }

    private fun refreshHistory() {
        historyBox.removeAllViews()
        val arr = loadArray("history")
        if (arr.length() == 0) {
            historyBox.addView(makeText("No history yet."))
            return
        }
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            historyBox.addView(
                makeText(o.getString("time") + "\nSearch: " + o.getString("query") + "\n" + o.getString("result"), 14f)
            )
        }
    }

    private fun addRecent(name: String, uriStr: String) {
        val old = loadArray("recent")
        val list = JSONArray()
        val first = JSONObject()
        first.put("name", name)
        first.put("uri", uriStr)
        list.put(first)
        for (i in 0 until old.length()) {
            if (list.length() >= 5) break
            val o = old.getJSONObject(i)
            if (o.getString("name") != name) list.put(o)
        }
        prefs.edit().putString("recent", list.toString()).apply()
    }

    private fun refreshRecent() {
        recentBox.removeAllViews()
        val arr = loadArray("recent")
        if (arr.length() == 0) {
            recentBox.addView(makeText("No recent books yet."))
            return
        }
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val name = o.getString("name")
            val uriStr = o.getString("uri")
            recentBox.addView(makeButton(name) {
                val f = DocumentFile.fromSingleUri(this, Uri.parse(uriStr))
                if (f != null) {
                    shareFile(f, name)
                } else {
                    Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show()
                }
            })
        }
    }
}
