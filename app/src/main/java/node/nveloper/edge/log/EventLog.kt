package node.nveloper.edge.log

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** In-memory ring buffer shown in the UI; every entry is mirrored to logcat under `Edge/<tag>`. */
class EventLog(private val capacity: Int = 300) {
    enum class Level { INFO, WARN, ERROR }

    data class Entry(val timeMs: Long, val level: Level, val tag: String, val message: String)

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries

    fun clear() {
        _entries.value = emptyList()
    }

    fun i(tag: String, message: String) = add(Level.INFO, tag, message, null)
    fun w(tag: String, message: String, error: Throwable? = null) = add(Level.WARN, tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = add(Level.ERROR, tag, message, error)

    private fun add(level: Level, tag: String, message: String, error: Throwable?) {
        val logTag = "Edge/$tag"
        when (level) {
            Level.INFO -> Log.i(logTag, message)
            Level.WARN -> Log.w(logTag, message, error)
            Level.ERROR -> Log.e(logTag, message, error)
        }
        val text = if (error != null) "$message: ${error.javaClass.simpleName}: ${error.message}" else message
        val entry = Entry(System.currentTimeMillis(), level, tag, text)
        _entries.update { (it + entry).takeLast(capacity) }
    }
}
