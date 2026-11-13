package com.miru.anime.source

import com.miru.anime.net.Http
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Flux vidéo lisible par le lecteur, avec les en-têtes que l'hébergeur exige. */
data class Stream(
    val url: String,
    val headers: Map<String, String>,
    val isHls: Boolean,
)

/** Transforme un lien de lecteur (page « embed ») en lien vidéo direct. */
object Extractors {

    /** Hébergeurs connus, dans l'ordre de préférence par défaut (les plus fiables d'abord). */
    val KNOWN_HOSTS = listOf("Sibnet", "Sendvid", "Vidmoly", "VK", "OneUpload", "Smoothpre", "Movearnpre", "Lpayer")

    fun hostName(url: String): String {
        val host = url.toHttpUrlOrNull()?.host?.lowercase() ?: return "Lecteur"
        return when {
            "sibnet" in host -> "Sibnet"
            "sendvid" in host -> "Sendvid"
            "vidmoly" in host -> "Vidmoly"
            host.startsWith("vk.") || host.contains(".vk.") || "vkvideo" in host -> "VK"
            "oneupload" in host -> "OneUpload"
            "smoothpre" in host -> "Smoothpre"
            "movearnpre" in host -> "Movearnpre"
            "lpayer" in host -> "Lpayer"
            else -> host.removePrefix("www.").substringBeforeLast('.').substringAfterLast('.')
                .replaceFirstChar { it.uppercase() }
        }
    }

    /** Range les lecteurs : hébergeur préféré d'abord, puis les plus fiables. */
    fun sortPlayers(players: List<String>, preferred: String): List<String> {
        fun rank(url: String): Int {
            val name = hostName(url)
            if (preferred != "Auto" && name == preferred) return -1
            val i = KNOWN_HOSTS.indexOf(name)
            return if (i >= 0) i else KNOWN_HOSTS.size
        }
        return players.withIndex().sortedWith(compareBy({ rank(it.value) }, { it.index })).map { it.value }
    }

    suspend fun extract(url: String, referer: String): Stream? = when (hostName(url)) {
        "Sibnet" -> sibnet(url, referer)
        "Sendvid" -> sendvid(url, referer) ?: generic(url, referer)
        "Vidmoly" -> vidmoly(url, referer)
        "VK" -> vk(url, referer) ?: generic(url, referer)
        else -> generic(url, referer)
    }

    private fun origin(url: String): String {
        val u = url.toHttpUrl()
        return "${u.scheme}://${u.host}/"
    }

    // ------------------------------------------------------------------ Sibnet

    private suspend fun sibnet(url: String, referer: String): Stream? {
        val id = Regex("""videoid=(\d+)""").find(url)?.groupValues?.get(1)
        val shell = if (id != null) "https://video.sibnet.ru/shell.php?videoid=$id" else url
        val html = Http.getString(shell, mapOf("Referer" to referer))
        val path = Regex("""player\.src\(\[\{\s*src:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            ?: Regex("""["'](/v/[^"']+\.mp4)["']""").find(html)?.groupValues?.get(1)
            ?: return null
        val video = if (path.startsWith("http")) path else "https://video.sibnet.ru$path"
        return Stream(video, mapOf("Referer" to shell), isHls = false)
    }

    // ------------------------------------------------------------------ Sendvid

    private suspend fun sendvid(url: String, referer: String): Stream? {
        val html = Http.getString(url, mapOf("Referer" to referer))
        val video = Regex("""<source[^>]+src=["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            ?: Regex("""property=["']og:video(?::url)?["']\s+content=["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            ?: Regex("""video_source\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            ?: return null
        val abs = if (video.startsWith("//")) "https:$video" else video
        return Stream(abs, mapOf("Referer" to origin(url)), isHls = abs.contains(".m3u8"))
    }

    // ------------------------------------------------------------------ Vidmoly

    private suspend fun vidmoly(url: String, referer: String): Stream? {
        // L'ancienne adresse ne répond parfois plus : on essaie aussi le miroir .biz.
        val variants = listOf(url, url.replace(Regex("""vidmoly\.(to|net|me|org)"""), "vidmoly.biz")).distinct()
        for (v in variants) {
            val stream = try {
                generic(v, referer)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                null
            }
            if (stream != null) return stream
        }
        return null
    }

    // ------------------------------------------------------------------ VK

    private suspend fun vk(url: String, referer: String): Stream? {
        val html = Http.getString(url, mapOf("Referer" to referer))
        fun field(name: String) = Regex(""""$name"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)?.replace("\\/", "/")
        for (q in listOf("url1080", "url720", "url480", "url360", "url240")) {
            field(q)?.let { return Stream(it, mapOf("Referer" to "https://vk.com/"), isHls = false) }
        }
        val hls = field("hls") ?: field("hls_ondemand") ?: return null
        return Stream(hls, mapOf("Referer" to "https://vk.com/"), isHls = true)
    }

    // ------------------------------------------------------------------ Générique

    private val PATTERNS = listOf(
        Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']"""),
        Regex("""sources\s*:\s*\[\s*\{\s*(?:src|file)\s*:\s*["']([^"']+)["']"""),
        Regex("""sources\s*:\s*\[\s*["']([^"']+)["']"""),
        Regex(""""hls\d*"\s*:\s*"([^"]+)""""),
        Regex("""file\s*:\s*["']([^"']+\.mp4[^"']*)["']"""),
        Regex("""<source[^>]+src=["']([^"']+)["']"""),
        Regex("""(https?:)?//[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*"""),
        Regex("""https?://[^\s"'<>\\]+\.mp4[^\s"'<>\\]*"""),
    )

    /** Pour la plupart des hébergeurs : lien m3u8/mp4 dans la page, parfois caché dans un script « packé ». */
    private suspend fun generic(url: String, referer: String, depth: Int = 0): Stream? {
        val page = Http.page(url, mapOf("Referer" to referer))
        val html = page.body
        val texts = listOf(html) + unpackAll(html)
        for (text in texts) {
            for (re in PATTERNS) {
                for (m in re.findAll(text)) {
                    val raw = (if (m.groupValues.size > 1 && m.groupValues[1].length > 6) m.groupValues[1] else m.value)
                        .replace("\\/", "/")
                    val abs = resolve(page.finalUrl, raw) ?: continue
                    if (abs.contains("thumb", true) || abs.endsWith(".jpg") || abs.endsWith(".png")) continue
                    return Stream(
                        abs,
                        mapOf("Referer" to origin(page.finalUrl), "Origin" to origin(page.finalUrl).trimEnd('/')),
                        isHls = abs.contains(".m3u8") || abs.contains("/hls"),
                    )
                }
            }
        }
        // Lecteur dans une iframe : on suit une fois.
        if (depth == 0) {
            val iframe = Regex("""<iframe[^>]+src=["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            val abs = iframe?.let { resolve(page.finalUrl, it) }
            if (abs != null && abs != url) return generic(abs, page.finalUrl, depth + 1)
        }
        return null
    }

    private fun resolve(baseUrl: String, raw: String): String? {
        val t = raw.trim()
        return when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.startsWith("//") -> "https:$t"
            t.startsWith("/") -> baseUrl.toHttpUrlOrNull()?.resolve(t)?.toString()
            else -> null
        }
    }

    // ------------------------------------------------------------------ Décodeur eval(function(p,a,c,k,e,d))

    private val PACKED = Regex(
        """\}\s*\(\s*'((?:[^'\\]|\\.)*)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'((?:[^'\\]|\\.)*)'\.split\('\|'\)""",
        RegexOption.DOT_MATCHES_ALL,
    )

    private fun unpackAll(html: String): List<String> {
        if (!html.contains("eval(function(p,a,c,k,e,")) return emptyList()
        return PACKED.findAll(html).mapNotNull { m ->
            runCatching {
                unpack(m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3].toInt(), m.groupValues[4].split('|'))
            }.getOrNull()
        }.toList()
    }

    private const val DIGITS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

    private fun toBase(n: Int, radix: Int): String {
        if (n == 0) return "0"
        var v = n
        val sb = StringBuilder()
        while (v > 0) {
            sb.append(DIGITS[v % radix])
            v /= radix
        }
        return sb.reverse().toString()
    }

    private fun unpack(payload: String, radix: Int, count: Int, words: List<String>): String {
        val p = payload.replace("\\'", "'").replace("\\\\", "\\")
        val dict = HashMap<String, String>(count * 2)
        for (i in 0 until count) {
            val key = toBase(i, radix)
            dict[key] = words.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: key
        }
        return Regex("""\b\w+\b""").replace(p) { dict[it.value] ?: it.value }
    }
}
