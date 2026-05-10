package com.mayegg.anisub.wordnote

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AppLogger {
    private const val baseTag = "Pstankidroid"
    private const val maxEntries = 200

    data class LogEntry(
        val level: String,
        val scope: String,
        val message: String,
        val timestamp: Long = System.currentTimeMillis(),
    )

    private val entriesState = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = entriesState.asStateFlow()

    fun d(scope: String, message: String) {
        append("D", scope, message)
        Log.d("$baseTag/$scope", message)
    }

    fun i(scope: String, message: String) {
        append("I", scope, message)
        Log.i("$baseTag/$scope", message)
    }

    fun w(scope: String, message: String, throwable: Throwable? = null) {
        append("W", scope, formatMessage(message, throwable))
        Log.w("$baseTag/$scope", message, throwable)
    }

    fun e(scope: String, message: String, throwable: Throwable? = null) {
        append("E", scope, formatMessage(message, throwable))
        Log.e("$baseTag/$scope", message, throwable)
    }

    fun clear() {
        entriesState.value = emptyList()
    }

    private fun append(level: String, scope: String, message: String) {
        entriesState.value = (entriesState.value + LogEntry(level, scope, message)).takeLast(maxEntries)
    }

    private fun formatMessage(message: String, throwable: Throwable?): String =
        if (throwable == null) {
            message
        } else {
            "$message\n${throwable.stackTraceToString()}"
        }
}
