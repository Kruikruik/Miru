package com.miru.anime.source

import com.miru.anime.data.Prefs
import com.miru.anime.model.AnimeCard
import com.miru.anime.model.AnimeDetails
import com.miru.anime.model.CataloguePage
import com.miru.anime.model.Episode
import com.miru.anime.model.HomeSection
import com.miru.anime.model.Season
import com.miru.anime.net.Http
import com.miru.anime.net.HttpException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.ConcurrentHashMap

/**
 * Source Voiranime (site WordPress « Madara ») : une page par anime, la VF est une page à part
 * dont l'adresse finit par « -vf », et une page par épisode avec un menu de lecteurs.
 */
object Voiranime : Source {
    const val PREFIX = "va:"
    private const val SEASON = "all"

    override val id = "voiranime"
    override val name = "Voiranime"
    override val base: String get() = "https://${Prefs.voiranimeDomain}"
    override val genres: List<String> = emptyList()
    override val hasVfFilter = false
    override val player = PlayerConfig(
        container = "#chapter-video-frame",
        lecteurSelect = "select.host-select",
        episodeSelect = null,
    )

    private val detailsCache = ConcurrentHashMap<String, AnimeDetails>()
    private val episodesCache = ConcurrentHashMap<String, List<Episode>>()

    private fun raw(slug: String) = slug.removePrefix(PREFIX)

    /** Page de l'anime dans la langue voulue (la VF a sa propre page « …-vf »). */
    private fun pageSlug(slug: String, lang: String): String {
        val r = raw(slug)
        return if (lang == "vf") (if (r.endsWith("-vf")) r else "$r-vf") else r.removeSuffix("-vf")
    }

    private val SLUG_RE = Regex("""/anime/([^/?#]+)/?""")

    /** Miniature WordPress (…-110x150.jpg) → image d'origine. */
    private fun fullImage(url: String): String = url.replace(Regex("""-\d+x\d+(\.\w+)$"""), "$1")

    private fun image(el: Element?): String? {
        val img = el?.selectFirst("img") ?: return null
        val src = listOf("data-src", "data-lazy-src", "src").map { img.absUrl(it) }.firstOrNull { it.startsWith("http") }
        return src?.let { fullImage(it.substringBefore('?')) }
    }

    private fun cards(doc: Document): List<AnimeCard> {
        val out = LinkedHashMap<String, AnimeCard>()
        for (item in doc.select(".c-tabs-item__content, .page-item-detail, .manga-item")) {
            val a = item.selectFirst(".post-title a, h3 a, h5 a") ?: item.selectFirst("a[href*=/anime/]") ?: continue
            val r = SLUG_RE.find(a.attr("href"))?.groupValues?.get(1) ?: continue
            val slug = PREFIX + r
            if (slug in out) continue
            val latest = item.selectFirst(".chapter a, .list-chapter a, .latest-chap a")?.text()?.trim()
            out[slug] = AnimeCard(
                slug = slug,
                title = a.text().trim().ifBlank { AnimeSama.prettySlug(r) },
                cover = image(item) ?: "",
                subtitle = latest?.let { episodeLabel(it) },
            )
        }
        return out.values.toList()
    }

    override suspend fun home(): List<HomeSection> {
        val url = "$base/"
        val html = Http.getString(url)
        return withContext(Dispatchers.Default) {
            val items = cards(Jsoup.parse(html, url))
            if (items.isEmpty()) emptyList() else listOf(HomeSection("Derniers épisodes", items))
        }
    }

    override suspend fun catalogue(query: String, page: Int, vfOnly: Boolean, genres: Set<String>): CataloguePage {
        val url = (if (page > 1) "$base/page/$page/" else "$base/").toHttpUrl().newBuilder()
            .addQueryParameter("s", query)
            .addQueryParameter("post_type", "wp-manga")
            .build().toString()
        val html = try {
            Http.getString(url)
        } catch (e: HttpException) {
            if (e.code == 404) return CataloguePage(emptyList(), false) else throw e
        }
        return withContext(Dispatchers.Default) {
            val doc = Jsoup.parse(html, url)
            val items = cards(doc)
            val hasNext = items.isNotEmpty() && doc.select("a[href*=/page/${page + 1}/]").isNotEmpty()
            CataloguePage(items, hasNext)
        }
    }

    override fun cachedDetails(slug: String): AnimeDetails? = detailsCache[slug]

    override suspend fun details(slug: String, force: Boolean): AnimeDetails {
        if (!force) detailsCache[slug]?.let { return it }
        val url = "$base/anime/${raw(slug)}/"
        val html = Http.getString(url)
        val d = withContext(Dispatchers.Default) {
            val doc = Jsoup.parse(html, url)
            val title = doc.selectFirst(".post-title h1")?.text()?.trim()?.ifBlank { null }
                ?: AnimeSama.prettySlug(raw(slug))
            val info = doc.select(".post-content_item").associate {
                it.selectFirst(".summary-heading")?.text()?.trim().orEmpty() to it.selectFirst(".summary-content")?.text()?.trim().orEmpty()
            }
            val alt = listOfNotNull(info["English"], info["Romaji"]).filter { it.isNotBlank() && it != title }.distinct()
                .joinToString(" · ").ifBlank { null }
            val synopsis = doc.selectFirst(".description-summary .summary__content, .description-summary")?.text()
                ?.replace(Regex("""\s*(Show more|Afficher plus|Lire la suite)\s*$""", RegexOption.IGNORE_CASE), "")?.trim()
                ?.ifBlank { null }
            val genres = doc.select(".genres-content a").map { it.text().trim() }.filter { it.isNotEmpty() }
            AnimeDetails(
                slug = slug,
                title = title,
                altTitles = alt,
                cover = image(doc.selectFirst(".summary_image")) ?: "",
                synopsis = synopsis,
                genres = genres,
                seasons = listOf(Season(info["Type"]?.takeIf { it.isNotBlank() } ?: "Épisodes", SEASON)),
            )
        }
        detailsCache[slug] = d
        return d
    }

    /** « Romelia War Chronicle - 07 VOSTFR - 07 » → « Épisode 07 ». */
    private fun episodeLabel(text: String): String {
        val last = text.substringAfterLast(" - ").trim()
        return if (last.isNotEmpty() && last.first().isDigit()) "Épisode $last" else text.trim()
    }

    override suspend fun episodes(slug: String, stem: String, lang: String, force: Boolean): List<Episode>? {
        val key = "$slug/$lang"
        if (!force) episodesCache[key]?.let { return it }
        val url = "$base/anime/${pageSlug(slug, lang)}/"
        val html = try {
            Http.getString(url)
        } catch (e: HttpException) {
            if (e.code == 404) return null else throw e
        }
        val list = withContext(Dispatchers.Default) {
            val doc = Jsoup.parse(html, url)
            // Le site liste du plus récent au plus ancien.
            doc.select("li.wp-manga-chapter a[href]").reversed().mapIndexed { i, a ->
                Episode(i, episodeLabel(a.text()), listOf(a.absUrl("href")))
            }
        }
        if (list.isEmpty()) return null
        episodesCache[key] = list
        return list
    }

    override suspend fun allLangs(slug: String, stem: String, force: Boolean): Map<String, List<Episode>> = coroutineScope {
        val main = if (raw(slug).endsWith("-vf")) "vf" else "vostfr"
        val other = if (main == "vf") "vostfr" else "vf"
        val otherJob = async {
            try {
                episodes(slug, stem, other, force)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                null
            }
        }
        val mainEps = episodes(slug, stem, main, force)
        val otherEps = otherJob.await()
        listOf("vostfr" to (if (main == "vostfr") mainEps else otherEps), "vf" to (if (main == "vf") mainEps else otherEps))
            .mapNotNull { (l, e) -> e?.takeIf { it.isNotEmpty() }?.let { l to it } }
            .toMap()
    }

    override fun nextSeason(slug: String, stem: String): Season? = null

    override fun playerUrl(slug: String, stem: String, lang: String, episode: Episode): String =
        episode.players.firstOrNull() ?: "$base/anime/${pageSlug(slug, lang)}/"
}
