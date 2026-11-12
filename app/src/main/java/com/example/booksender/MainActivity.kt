package com.example.booksender

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Html
import android.text.InputType
import android.view.Gravity
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

    private lateinit var searchScreen: LinearLayout
    private lateinit var historyScreen: LinearLayout
    private lateinit var settingsScreen: LinearLayout

    private lateinit var requestInput: EditText
    private lateinit var statusText: TextView
    private lateinit var resultsBox: LinearLayout
    private lateinit var recentBox: LinearLayout
    private lateinit var replySection: LinearLayout
    private lateinit var selectedBookText: TextView
    private lateinit var replyInput: EditText
    private lateinit var historyBox: LinearLayout
    private lateinit var folderText: TextView

    private var selectedBook: DocumentFile? = null
    @Volatile private var backgroundTask: Thread? = null

    private data class RankedMatch(
        val file: DocumentFile,
        val exactTitleInRequest: Boolean,
        val sharedWords: Int,
        val similarity: Double
    )

    private data class ScanBudget(var visited: Int = 0, var limitReached: Boolean = false)

    private val ignoredWords = setOf(
        "a", "an", "and", "are", "as", "at", "be", "book", "can", "could", "do", "does",
        "for", "from", "have", "hello", "hey", "hi", "i", "in", "is", "it", "me", "my",
        "of", "on", "or", "pdf", "please", "send", "share", "the", "this", "to", "want",
        "with", "would", "you", "your", "epub", "copy", "need", "looking"
    )

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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun weightParams() =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun makeColumn(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
    }

    private fun makeText(text: String, size: Float = 16f): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun makeButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Book Sender"

        val root = makeColumn().apply { setPadding(dp(16), dp(16), dp(16), dp(16)) }

        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabs.addView(makeButton("Search") { showScreen(0) }, weightParams())
        tabs.addView(makeButton("History") { showScreen(1) }, weightParams())
        tabs.addView(makeButton("Settings") { showScreen(2) }, weightParams())
        root.addView(tabs)

        searchScreen = makeColumn()
        searchScreen.addView(
            makeText(
                "Paste or type a book request, or explicitly share text from WhatsApp to Book Sender. " +
                    "This app does not read your chats.",
                14f
            )
        )
        requestInput = EditText(this).apply {
            hint = "Book request or title"
            setSingleLine(false)
            minLines = 2
            maxLines = 5
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        searchScreen.addView(requestInput)
        searchScreen.addView(makeButton("Find matching books") { doSearch() })
        statusText = makeText("Choose your book folder in Settings to get started.")
        searchScreen.addView(statusText)
        resultsBox = makeColumn()
        searchScreen.addView(resultsBox)

        replySection = makeColumn().apply { visibility = View.GONE }
        selectedBookText = makeText("Selected book:", 15f)
        replySection.addView(selectedBookText)
        replySection.addView(makeText("Reply message (you can edit this):", 14f))
        replyInput = EditText(this).apply {
            hint = "Optional message to send with the book"
            setSingleLine(false)
            minLines = 2
            maxLines = 4
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        replySection.addView(replyInput)
        replySection.addView(makeButton("Open personal WhatsApp to send") { openWhatsAppForSelectedBook() })
        replySection.addView(
            makeText("You choose the recipient and tap Send in WhatsApp. Book Sender cannot see whether it was sent.", 13f)
        )
        searchScreen.addView(replySection)

        searchScreen.addView(makeText("Recent books", 18f))
        recentBox = makeColumn()
        searchScreen.addView(recentBox)
        root.addView(searchScreen)

        historyScreen = makeColumn()
        historyScreen.addView(makeButton("Clear history") {
            prefs.edit().remove("history").apply()
            refreshHistory()
        })
        historyBox = makeColumn()
        historyScreen.addView(historyBox)
        root.addView(historyScreen)

        settingsScreen = makeColumn()
        folderText = makeText("")
        settingsScreen.addView(folderText)
        settingsScreen.addView(makeButton("Choose book folder") { folderPicker.launch(null) })
        settingsScreen.addView(
            makeText("Book Sender only searches filenames in this folder. It does not scan WhatsApp or upload your library.", 14f)
        )
        root.addView(settingsScreen)

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)

        showScreen(0)
        handleIncomingRequest(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingRequest(intent)
    }

    override fun onDestroy() {
        backgroundTask?.interrupt()
        super.onDestroy()
    }

    private fun postToUi(action: () -> Unit) {
        if (isFinishing || isDestroyed) return
        runOnUiThread {
            if (!isFinishing && !isDestroyed) action()
        }
    }

    private fun handleIncomingRequest(incoming: Intent?) {
        if (incoming?.action != Intent.ACTION_SEND) return
        val isTextMime = incoming.type?.startsWith("text/", ignoreCase = true) == true
        val hasTextExtra = incoming.hasExtra(Intent.EXTRA_TEXT) || incoming.hasExtra(Intent.EXTRA_HTML_TEXT)
        if (!isTextMime && !hasTextExtra) return
        val subject = incoming.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString()?.trim().orEmpty()
        val title = incoming.getCharSequenceExtra(Intent.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val body = incoming.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim()
            ?: incoming.getStringExtra(Intent.EXTRA_HTML_TEXT)?.let {
                Html.fromHtml(it, Html.FROM_HTML_MODE_COMPACT).toString().trim()
            }.orEmpty()
        val request = listOf(subject, title, body).filter { it.isNotBlank() }.distinct().joinToString("\n")
        if (request.isBlank()) return

        showScreen(0)
        requestInput.setText(request)
        doSearch()
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

    private fun getFolderUri(): Uri? = prefs.getString("folder_uri", null)?.let(Uri::parse)

    private fun hasFolderPermission(uri: Uri): Boolean = contentResolver.persistedUriPermissions.any {
        it.uri == uri && it.isReadPermission
    }

    private fun refreshSettings() {
        val uri = getFolderUri()
        folderText.text = if (uri == null) {
            "No folder chosen yet."
        } else {
            val note = if (hasFolderPermission(uri)) "" else "\n\nPermission lost. Please choose the folder again."
            "Current folder:\n${Uri.decode(uri.toString())}$note"
        }
    }

    private fun meaningfulTokens(text: String): List<String> =
        Regex("[\\p{L}\\p{N}]+")
            .findAll(text.lowercase(Locale.ROOT))
            .map { it.value }
            .filter { it.length > 1 && it !in ignoredWords }
            .toList()

    private fun collectMatches(
        dir: DocumentFile,
        requestTokens: List<String>,
        requestPhrase: String,
        out: MutableList<RankedMatch>,
        budget: ScanBudget,
        depth: Int
    ) {
        if (depth > 8 || budget.limitReached || Thread.currentThread().isInterrupted) return
        for (file in dir.listFiles()) {
            if (budget.limitReached || Thread.currentThread().isInterrupted) return
            budget.visited++
            if (budget.visited > MAX_SEARCH_FILES) {
                budget.limitReached = true
                return
            }
            if (file.isDirectory) {
                collectMatches(file, requestTokens, requestPhrase, out, budget, depth + 1)
                continue
            }

            val name = file.name ?: continue
            val lowerName = name.lowercase(Locale.ROOT)
            if (!lowerName.endsWith(".pdf") && !lowerName.endsWith(".epub")) continue

            val titleTokens = meaningfulTokens(name).distinct()
            if (titleTokens.isEmpty()) continue
            val titlePhrase = titleTokens.joinToString(" ")
            val exact = " $requestPhrase ".contains(" $titlePhrase ")
            val overlap = titleTokens.toSet().intersect(requestTokens.toSet()).size
            val denominator = minOf(titleTokens.toSet().size, requestTokens.toSet().size)
            if (denominator == 0) continue
            val similarity = overlap.toDouble() / denominator
            val requiredOverlap = minOf(2, denominator)
            if (exact || (overlap >= requiredOverlap && similarity >= 0.5)) {
                out.add(RankedMatch(file, exact, overlap, similarity))
            }
        }
    }

    private fun doSearch() {
        val request = requestInput.text.toString().trim()
        resultsBox.removeAllViews()
        replySection.visibility = View.GONE
        selectedBook = null

        if (request.isEmpty()) {
            statusText.text = "Paste or type a book request first."
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

        val tokens = meaningfulTokens(request).distinct()
        if (tokens.isEmpty()) {
            statusText.text = "Please include one or more words from the book title."
            return
        }
        statusText.text = "Searching book filenames..."

        backgroundTask?.interrupt()
        val budget = ScanBudget()
        val worker = Thread {
            val matches = ArrayList<RankedMatch>()
            var error: String? = null
            try {
                val rootDir = DocumentFile.fromTreeUri(this, folderUri)
                if (rootDir != null) {
                    collectMatches(rootDir, tokens, tokens.joinToString(" "), matches, budget, 0)
                }
            } catch (e: Exception) {
                error = e.message ?: "Unknown error"
            }
            if (Thread.currentThread().isInterrupted) return@Thread
            val sorted = matches.sortedWith(
                compareByDescending<RankedMatch> { it.exactTitleInRequest }
                    .thenByDescending { it.sharedWords }
                    .thenByDescending { it.similarity }
                    .thenBy { it.file.name ?: "" }
            ).map { it.file }

            postToUi {
                if (error != null) {
                    statusText.text = "Search error: $error"
                } else {
                    showResults(request, sorted, budget.limitReached)
                }
            }
        }
        backgroundTask = worker
        worker.start()
    }

    private fun showResults(request: String, matches: List<DocumentFile>, scanLimited: Boolean) {
        resultsBox.removeAllViews()
        if (matches.isEmpty()) {
            statusText.text = if (scanLimited) {
                "No match in the first $MAX_SEARCH_FILES files scanned. Narrow the request or organize the folder and try again."
            } else {
                "No matching PDF or EPUB filenames found for that request."
            }
            addHistory(request, "No matching book files found")
            return
        }

        statusText.text = if (scanLimited) {
            "${matches.size} matching file(s) in the first $MAX_SEARCH_FILES scanned. Narrow the request to search more precisely."
        } else {
            "${matches.size} matching file(s). Tap a book to prepare a reply."
        }
        addHistory(request, "Found ${matches.size} matching book file(s)")
        for (file in matches) {
            val mb = file.length() / (1024 * 1024)
            val button = makeButton("Prepare reply for:\n${file.name} ($mb MB)") {
                prepareReply(file)
            }
            button.textSize = 16f
            resultsBox.addView(button)
        }
    }

    private fun displayTitle(fileName: String): String = fileName
        .replace(Regex("(?i)\\.(pdf|epub)$"), "")
        .replace('_', ' ')
        .trim()

    private fun prepareReply(file: DocumentFile) {
        val name = file.name ?: "book"
        selectedBook = file
        selectedBookText.text = "Selected book: $name"
        replyInput.setText("I found “${displayTitle(name)}” in my library. Sending it now.")
        replySection.visibility = View.VISIBLE
        statusText.text = "Review the reply, then open WhatsApp. You will choose the recipient and confirm Send there."
    }

    private fun openWhatsAppForSelectedBook() {
        val file = selectedBook
        if (file == null) {
            statusText.text = "Choose a book first."
            return
        }
        val request = requestInput.text.toString().trim()
        val reply = replyInput.text.toString().trim()
        val name = file.name ?: "book"
        if (file.length() > 100L * 1024 * 1024) {
            Toast.makeText(this, "This file is over 100 MB. WhatsApp may reject it.", Toast.LENGTH_LONG).show()
        }
        statusText.text = "Preparing the selected file..."

        backgroundTask?.interrupt()
        val worker = Thread {
            var shareDir: File? = null
            var targetFile: File? = null
            try {
                val sharedRoot = File(cacheDir, "shared")
                if (!sharedRoot.exists() && !sharedRoot.mkdirs()) throw Exception("Cannot create share cache")
                val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
                sharedRoot.listFiles()?.filter { it.isDirectory && it.lastModified() < cutoff }
                    ?.forEach { it.deleteRecursively() }

                val sourceLength = file.length()
                val available = cacheDir.usableSpace
                val reserveBytes = 8L * 1024 * 1024
                if (sourceLength > 0 && available > 0 &&
                    (sourceLength > available || available - sourceLength < reserveBytes)
                ) {
                    throw Exception("Not enough free storage to prepare this book")
                }

                // Keep each share in its own cache directory so a later selection cannot overwrite
                // a file that WhatsApp is still displaying.
                val newShareDir = File(sharedRoot, System.nanoTime().toString())
                shareDir = newShareDir
                if (!newShareDir.mkdirs()) throw Exception("Cannot prepare share folder")
                val safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                val target = File(newShareDir, safeName)
                targetFile = target
                contentResolver.openInputStream(file.uri).use { input ->
                    if (input == null) throw Exception("Cannot open the selected book")
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (Thread.currentThread().isInterrupted) throw InterruptedException()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
                if (!Thread.currentThread().isInterrupted) {
                    postToUi { launchWhatsAppShare(target, name, request, reply, file.uri.toString()) }
                }
            } catch (_: InterruptedException) {
                targetFile?.delete()
                shareDir?.deleteRecursively()
            } catch (e: Exception) {
                targetFile?.delete()
                shareDir?.deleteRecursively()
                if (!Thread.currentThread().isInterrupted) {
                    postToUi { statusText.text = "Could not prepare file: ${e.message}" }
                }
            }
        }
        backgroundTask = worker
        worker.start()
    }

    private fun launchWhatsAppShare(target: File, name: String, request: String, reply: String, sourceUri: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", target)
        val mime = if (name.lowercase(Locale.ROOT).endsWith(".epub")) {
            "application/epub+zip"
        } else {
            "application/pdf"
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            if (reply.isNotBlank()) putExtra(Intent.EXTRA_TEXT, reply)
            clipData = ClipData.newUri(contentResolver, name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setPackage(PERSONAL_WHATSAPP_PACKAGE)
        }

        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            if (!name.lowercase(Locale.ROOT).endsWith(".epub")) {
                showWhatsAppUnavailable()
                return
            }
            // Some WhatsApp versions do not register the EPUB-specific MIME type. Retry only
            // against the same personal WhatsApp package, still requiring the user to send.
            intent.type = "application/octet-stream"
            try {
                startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                showWhatsAppUnavailable()
                return
            }
        }
        addHistory(request, "Opened personal WhatsApp share for $name; delivery is not confirmed")
        addRecent(name, sourceUri)
        statusText.text = "WhatsApp opened. Choose the recipient and tap Send there; delivery is not confirmed here."
    }

    private fun showWhatsAppUnavailable() {
        statusText.text = "Personal WhatsApp is not installed or could not open. No message was sent."
        Toast.makeText(this, "Install or open personal WhatsApp to continue.", Toast.LENGTH_LONG).show()
    }

    private fun loadArray(key: String): JSONArray = try {
        JSONArray(prefs.getString(key, "[]") ?: "[]")
    } catch (_: Exception) {
        JSONArray()
    }

    private fun addHistory(request: String, result: String) {
        val old = loadArray("history")
        val list = JSONArray()
        val entry = JSONObject().apply {
            put("time", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()))
            put("query", request)
            put("result", result)
        }
        list.put(entry)
        for (i in 0 until old.length()) {
            if (list.length() >= 200) break
            list.put(old.getJSONObject(i))
        }
        prefs.edit().putString("history", list.toString()).apply()
    }

    private fun refreshHistory() {
        historyBox.removeAllViews()
        val history = loadArray("history")
        if (history.length() == 0) {
            historyBox.addView(makeText("No history yet."))
            return
        }
        for (i in 0 until history.length()) {
            val entry = history.getJSONObject(i)
            historyBox.addView(
                makeText(
                    "${entry.getString("time")}\nRequest: ${entry.getString("query")}\n${entry.getString("result")}",
                    14f
                )
            )
        }
    }

    private fun addRecent(name: String, uriStr: String) {
        val old = loadArray("recent")
        val list = JSONArray()
        list.put(JSONObject().put("name", name).put("uri", uriStr))
        for (i in 0 until old.length()) {
            if (list.length() >= 5) break
            val entry = old.getJSONObject(i)
            if (entry.optString("name") != name) list.put(entry)
        }
        prefs.edit().putString("recent", list.toString()).apply()
    }

    private fun refreshRecent() {
        recentBox.removeAllViews()
        val recent = loadArray("recent")
        if (recent.length() == 0) {
            recentBox.addView(makeText("No recent books yet."))
            return
        }
        for (i in 0 until recent.length()) {
            val entry = recent.getJSONObject(i)
            val name = entry.optString("name")
            val uriString = entry.optString("uri")
            recentBox.addView(makeButton(name) {
                try {
                    val file = DocumentFile.fromSingleUri(this, Uri.parse(uriString))
                    if (file != null && file.exists()) {
                        prepareReply(file)
                    } else {
                        Toast.makeText(this, "File not found. Choose the book folder again.", Toast.LENGTH_SHORT).show()
                    }
                } catch (_: Exception) {
                    Toast.makeText(this, "File not found. Choose the book folder again.", Toast.LENGTH_SHORT).show()
                }
            })
        }
    }

    companion object {
        private const val PERSONAL_WHATSAPP_PACKAGE = "com.whatsapp"
        private const val MAX_SEARCH_FILES = 20_000
    }
}
