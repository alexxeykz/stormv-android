package com.stormv.vpn.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class LogLevel(val label: String, val priority: Int) {
    DEBUG("DBG", 0),
    INFO("INF", 1),
    WARNING("WRN", 2),
    ERROR("ERR", 3);
}

private val logEntryCounter = java.util.concurrent.atomic.AtomicLong(0)

data class LogEntry(
    val id: Long = logEntryCounter.getAndIncrement(),
    val time: Date = Date(),
    val level: LogLevel,
    val tag: String,
    val message: String,
) {
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    val formatted: String get() = "[${fmt.format(time)}] [${level.label}] [$tag] $message"
}

/**
 * Потокобезопасный логгер: память + файл + StateFlow для Compose.
 * Файл ротируется при > 5 МБ.
 */
object AppLogger {
    private val lock = ReentrantLock()
    private val _entries = ArrayDeque<LogEntry>(2000)
    private val _flow = MutableStateFlow<List<LogEntry>>(emptyList())
    val flow: StateFlow<List<LogEntry>> = _flow.asStateFlow()

    private var logFile: File? = null
    private val fileFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private const val MAX_MEMORY = 2000
    private const val MAX_FILE_BYTES = 5 * 1024 * 1024L // 5 MB

    fun init(filesDir: File) {
        val dir = File(filesDir, "logs").also { it.mkdirs() }
        val file = File(dir, "stormv.log")
        if (file.exists() && file.length() > MAX_FILE_BYTES) {
            val backup = File(dir, "stormv.log.old")
            backup.delete()
            file.renameTo(backup)
        }
        logFile = file
        i("Logger", "StormV запущен, лог: ${file.absolutePath}")
    }

    fun d(tag: String, msg: String) = write(LogLevel.DEBUG, tag, msg)
    fun i(tag: String, msg: String) = write(LogLevel.INFO, tag, msg)
    fun w(tag: String, msg: String) = write(LogLevel.WARNING, tag, msg)
    fun e(tag: String, msg: String) = write(LogLevel.ERROR, tag, msg)

    fun write(level: LogLevel, tag: String, message: String) {
        // Маскируем IP/UUID перед записью в память, UI и файл
        val safeMessage = EncryptionHelper.maskSensitive(message)
        val entry = LogEntry(level = level, tag = tag, message = safeMessage)

        // В Logcat — тоже маскированная версия
        when (level) {
            LogLevel.DEBUG   -> Log.d(tag, safeMessage)
            LogLevel.INFO    -> Log.i(tag, safeMessage)
            LogLevel.WARNING -> Log.w(tag, safeMessage)
            LogLevel.ERROR   -> Log.e(tag, safeMessage)
        }

        lock.withLock {
            if (_entries.size >= MAX_MEMORY) repeat(50) { _entries.removeFirst() }
            _entries.addLast(entry)
            try { logFile?.appendText(entry.formatted + "\n") } catch (_: Exception) {}
        }

        _flow.value = lock.withLock { _entries.toList() }
    }

    fun getFiltered(minLevel: LogLevel, search: String = ""): List<LogEntry> = lock.withLock {
        _entries.filter { e ->
            e.level.priority >= minLevel.priority &&
            (search.isEmpty() ||
             e.message.contains(search, ignoreCase = true) ||
             e.tag.contains(search, ignoreCase = true))
        }
    }

    fun clear() {
        lock.withLock {
            _entries.clear()
            try { logFile?.writeText("") } catch (_: Exception) {}
        }
        _flow.value = emptyList()
        i("Logger", "Лог очищен")
    }

    fun getLogFilePath(): String = logFile?.absolutePath ?: "не инициализирован"

    fun copyToString(minLevel: LogLevel = LogLevel.DEBUG, search: String = ""): String =
        getFiltered(minLevel, search).joinToString("\n") { it.formatted }
}
