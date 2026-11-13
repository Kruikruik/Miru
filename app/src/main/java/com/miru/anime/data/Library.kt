package com.miru.anime.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Anime ajouté à la bibliothèque. */
data class Favorite(val slug: String, val title: String, val cover: String, val added: Long)

/** Avancement d'un épisode (position en ms, durée en ms). */
data class Progress(val position: Long, val duration: Long, val watched: Boolean, val time: Long) {
    val fraction: Float get() = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
}

/** Dernier épisode regardé d'un anime. */
data class HistoryEntry(
    val slug: String,
    val title: String,
    val cover: String,
    val stem: String,
    val seasonName: String,
    val lang: String,
    val index: Int,
    val label: String,
    val time: Long,
)

/** Clé d'un épisode, indépendante de la langue (vu en VF = vu en VOSTFR). */
fun episodeKey(slug: String, stem: String, index: Int) = "$slug/$stem#$index"

/** Bibliothèque, avancement et historique, enregistrés dans des fichiers JSON locaux. */
object Library {
    private lateinit var dir: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saveMutex = Mutex()

    private val _favorites = MutableStateFlow<List<Favorite>>(emptyList())
    val favorites: StateFlow<List<Favorite>> = _favorites

    private val _progress = MutableStateFlow<Map<String, Progress>>(emptyMap())
    val progress: StateFlow<Map<String, Progress>> = _progress

    private val _history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val history: StateFlow<List<HistoryEntry>> = _history

    fun init(context: Context) {
        dir = File(context.filesDir, "library").apply { mkdirs() }
        _favorites.value = read("favorites.json")?.let { arr ->
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Favorite(o.getString("slug"), o.getString("title"), o.optString("cover"), o.optLong("added"))
            }
        }.orEmpty()
        _progress.value = read("progress.json")?.let { arr ->
            (0 until arr.length()).associate { i ->
                val o = arr.getJSONObject(i)
                o.getString("k") to Progress(o.optLong("p"), o.optLong("d"), o.optBoolean("w"), o.optLong("t"))
            }
        }.orEmpty()
        _history.value = read("history.json")?.let { arr ->
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                HistoryEntry(
                    o.getString("slug"), o.getString("title"), o.optString("cover"), o.getString("stem"),
                    o.optString("season"), o.optString("lang", "vostfr"), o.optInt("index"), o.optString("label"),
                    o.optLong("time"),
                )
            }
        }.orEmpty()
    }

    private fun read(name: String): JSONArray? = runCatching {
        val f = File(dir, name)
        if (f.exists()) JSONArray(f.readText()) else null
    }.getOrNull()

    private fun write(name: String, arr: JSONArray) {
        scope.launch {
            saveMutex.withLock {
                val tmp = File(dir, "$name.tmp")
                tmp.writeText(arr.toString())
                tmp.renameTo(File(dir, name))
            }
        }
    }

    // ------------------------------------------------------------ Bibliothèque

    fun isFavorite(slug: String) = _favorites.value.any { it.slug == slug }

    fun toggleFavorite(slug: String, title: String, cover: String) {
        val list = _favorites.value
        _favorites.value = if (list.any { it.slug == slug }) list.filterNot { it.slug == slug }
        else list + Favorite(slug, title, cover, System.currentTimeMillis())
        saveFavorites()
    }

    private fun saveFavorites() {
        val arr = JSONArray()
        _favorites.value.forEach {
            arr.put(JSONObject().put("slug", it.slug).put("title", it.title).put("cover", it.cover).put("added", it.added))
        }
        write("favorites.json", arr)
    }

    // ------------------------------------------------------------ Avancement

    fun saveProgress(key: String, position: Long, duration: Long) {
        val old = _progress.value[key]
        val watched = (old?.watched == true) || (duration > 0 && position >= duration * 0.9)
        _progress.value = _progress.value + (key to Progress(position, duration, watched, System.currentTimeMillis()))
        saveProgressFile()
    }

    fun setWatched(keys: Collection<String>, watched: Boolean) {
        val now = System.currentTimeMillis()
        val map = _progress.value.toMutableMap()
        for (k in keys) {
            val old = map[k]
            map[k] = if (watched) Progress(old?.position ?: 0, old?.duration ?: 0, true, now)
            else Progress(0, old?.duration ?: 0, false, now)
        }
        _progress.value = map
        saveProgressFile()
    }

    private fun saveProgressFile() {
        val arr = JSONArray()
        _progress.value.forEach { (k, p) ->
            arr.put(JSONObject().put("k", k).put("p", p.position).put("d", p.duration).put("w", p.watched).put("t", p.time))
        }
        write("progress.json", arr)
    }

    // ------------------------------------------------------------ Historique

    fun record(entry: HistoryEntry) {
        _history.value = listOf(entry) + _history.value.filterNot { it.slug == entry.slug }.take(199)
        saveHistory()
    }

    fun removeHistory(slug: String) {
        _history.value = _history.value.filterNot { it.slug == slug }
        saveHistory()
    }

    fun clearHistory() {
        _history.value = emptyList()
        saveHistory()
    }

    private fun saveHistory() {
        val arr = JSONArray()
        _history.value.forEach {
            arr.put(
                JSONObject().put("slug", it.slug).put("title", it.title).put("cover", it.cover).put("stem", it.stem)
                    .put("season", it.seasonName).put("lang", it.lang).put("index", it.index).put("label", it.label)
                    .put("time", it.time),
            )
        }
        write("history.json", arr)
    }
}
