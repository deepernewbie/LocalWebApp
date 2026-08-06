package com.localwebapp

import android.content.Context
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// ═══════════════════════════════════════════════════════════════════════════════
// Vault — the one place data survives everything.
//
// The problem it solves: CouchFlow derives its localhost port from the folder
// URI, so a new folder is a new origin with empty localStorage, and a fresh
// unzip has an empty data/ directory. Between them, nothing carries over, and
// the user has to hand-copy their settings on every update.
//
// This is app-private storage — filesDir, outside any picked folder. It
// survives choosing a different folder, unzipping a new build, and clearing
// the browser store. It does not survive uninstalling the app, which is why
// the folder copy still exists: the two protect against different accidents.
//
// Deliberately small-payload only. Settings, tasks, memories, profile — the
// things that hurt to lose and total a few hundred kilobytes. Sessions and
// reports stay in the folder where the user can see them.
// ═══════════════════════════════════════════════════════════════════════════════
class VaultBridge(private val context: Context) {

    private val dir: File
        get() = File(context.filesDir, "vault").apply { if (!exists()) mkdirs() }

    /** Keys are paths like "data/settings.json"; flatten them for the filesystem. */
    private fun fileFor(key: String): File {
        val safe = key.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
        return File(dir, "$safe.json")
    }

    private val MAX_BYTES = 2 * 1024 * 1024

    /** JSON: {"found":true,"content":"…","savedAt":123} */
    @JavascriptInterface
    fun vaultGet(key: String): String = try {
        val f = fileFor(key)
        if (!f.exists()) {
            JSONObject().put("found", false).toString()
        } else {
            val stored = JSONObject(f.readText())
            JSONObject()
                .put("found", true)
                .put("content", stored.optString("content"))
                .put("savedAt", stored.optLong("savedAt"))
                .toString()
        }
    } catch (e: Exception) {
        DebugLog.error("vaultGet ${'$'}key: ${'$'}{e.message}")
        JSONObject().put("found", false).put("error", e.message ?: "read failed").toString()
    }

    @JavascriptInterface
    fun vaultSet(key: String, content: String, savedAt: String): String = try {
        if (content.length > MAX_BYTES) {
            JSONObject().put("saved", false).put("error", "too large for the vault").toString()
        } else {
            fileFor(key).writeText(
                JSONObject()
                    .put("content", content)
                    .put("savedAt", savedAt.toLongOrNull() ?: System.currentTimeMillis())
                    .toString()
            )
            JSONObject().put("saved", true).toString()
        }
    } catch (e: Exception) {
        DebugLog.error("vaultSet ${'$'}key: ${'$'}{e.message}")
        JSONObject().put("saved", false).put("error", e.message ?: "write failed").toString()
    }

    @JavascriptInterface
    fun vaultList(): String = try {
        val arr = JSONArray()
        dir.listFiles()?.forEach { f ->
            try {
                val stored = JSONObject(f.readText())
                arr.put(JSONObject()
                    .put("file", f.name)
                    .put("savedAt", stored.optLong("savedAt"))
                    .put("bytes", f.length()))
            } catch (e: Exception) { /* skip an unreadable entry */ }
        }
        JSONObject().put("entries", arr).toString()
    } catch (e: Exception) {
        JSONObject().put("entries", JSONArray()).put("error", e.message ?: "").toString()
    }

    @JavascriptInterface
    fun vaultDelete(key: String): String = try {
        JSONObject().put("deleted", fileFor(key).delete()).toString()
    } catch (e: Exception) {
        JSONObject().put("deleted", false).put("error", e.message ?: "").toString()
    }

    /** Total size, so the app can show what is being kept safe. */
    @JavascriptInterface
    fun vaultSize(): String = try {
        val files = dir.listFiles() ?: emptyArray()
        JSONObject()
            .put("count", files.size)
            .put("bytes", files.sumOf { it.length() })
            .toString()
    } catch (e: Exception) {
        JSONObject().put("count", 0).put("bytes", 0).toString()
    }
}
