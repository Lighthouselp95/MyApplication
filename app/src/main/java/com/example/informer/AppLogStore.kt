package com.example.informer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object AppLogStore {
    private const val PREF_NAME = "AppLogHistory"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 300
    private const val RETENTION_MS = 7L * 24L * 60L * 60L * 1000L

    private data class LogEntry(
        val timestampMs: Long,
        val text: String,
    )

    fun load(context: Context): List<String> {
        val safeContext = context.deviceProtectedContext()
        val prefs = safeContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        synchronized(this) {
            val now = System.currentTimeMillis()
            val parsed = readEntries(prefs.getString(KEY_ENTRIES, "[]"))
            val cleaned = cleanup(parsed, now).takeLast(MAX_ENTRIES)
            if (cleaned != parsed) {
                writeEntries(prefs, cleaned)
            }
            return cleaned.map { it.text }
        }
    }

    fun append(context: Context, entry: String) {
        append(context, System.currentTimeMillis(), entry)
    }

    fun append(context: Context, timestampMs: Long, entry: String) {
        val safeContext = context.deviceProtectedContext()
        val prefs = safeContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        synchronized(this) {
            val existing = readEntries(prefs.getString(KEY_ENTRIES, "[]"))
            val merged = cleanup(existing, timestampMs).toMutableList()
            merged.add(LogEntry(timestampMs, entry))
            val cleaned = cleanup(merged, timestampMs).takeLast(MAX_ENTRIES)
            writeEntries(prefs, cleaned)
        }
    }

    fun clear(context: Context) {
        val safeContext = context.deviceProtectedContext()
        safeContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_ENTRIES)
            .commit()
    }

    fun pruneExpired(context: Context) {
        val safeContext = context.deviceProtectedContext()
        val prefs = safeContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        synchronized(this) {
            val now = System.currentTimeMillis()
            val cleaned = cleanup(readEntries(prefs.getString(KEY_ENTRIES, "[]")), now).takeLast(MAX_ENTRIES)
            writeEntries(prefs, cleaned)
        }
    }

    private fun readEntries(raw: String?): List<LogEntry> {
        val source = raw ?: "[]"
        return try {
            val array = JSONArray(source)
            buildList {
                for (i in 0 until array.length()) {
                    when (val value = array.opt(i)) {
                        is JSONObject -> {
                            val text = value.optString("text").ifBlank {
                                value.optString("message")
                            }
                            if (text.isBlank()) continue
                            val timestamp = when {
                                value.has("ts") -> value.optLong("ts", 0L)
                                value.has("timestampMs") -> value.optLong("timestampMs", 0L)
                                else -> 0L
                            }.takeIf { it > 0L } ?: System.currentTimeMillis()
                            add(LogEntry(timestamp, text))
                        }
                        is String -> {
                            if (value.isNotBlank()) {
                                add(LogEntry(System.currentTimeMillis(), value))
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun cleanup(entries: List<LogEntry>, nowMs: Long): List<LogEntry> {
        val cutoff = nowMs - RETENTION_MS
        return entries
            .filter { it.timestampMs >= cutoff }
            .sortedBy { it.timestampMs }
    }

    private fun writeEntries(prefs: android.content.SharedPreferences, entries: List<LogEntry>) {
        val array = JSONArray()
        entries.takeLast(MAX_ENTRIES).forEach { entry ->
            array.put(
                JSONObject()
                    .put("ts", entry.timestampMs)
                    .put("text", entry.text)
            )
        }
        prefs.edit()
            .putString(KEY_ENTRIES, array.toString())
            .commit()
    }
}
