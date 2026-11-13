package com.miru.anime.source

import com.miru.anime.data.Prefs
import com.miru.anime.model.AnimeCard
import com.miru.anime.model.AnimeDetails
import com.miru.anime.model.CataloguePage
import com.miru.anime.model.Episode
import com.miru.anime.model.HomeSection
import com.miru.anime.model.Langs
import com.miru.anime.model.Season
import com.miru.anime.net.Http
import com.miru.anime.net.HttpException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Source Anime-Sama : lecture des pages du site (accueil, catalogue, fiche, saisons)
 * et des fichiers `episodes.js` qui listent les lecteurs de chaque épisode.
 */
object AnimeSama {

    /** Adresses connues, essayées dans l'ordre quand l'adresse actuelle ne répond plus. */
    private val KNOWN_DOMAINS = listOf(
        "anime-sama.to", "anime-sama.tv", "anime-sama.si", "anime-sama.org", "anime-sama.fr", "anime-sama.eu",
    )

    val base: String get() = "https://${Prefs.domain}"

    fun cover(slug: String) = "https://cdn.jsdelivr.net/gh/Anime-Sama/IMG@img/contenu/$slug.jpg"

    private val detailsCache = ConcurrentHashMap<String, AnimeDetails>()
    private val episodesCache = ConcurrentHashMap<String, List<Episode>>()
    private val domainMutex = Mutex()

    // ---------------------------------------------------------------- Adresse du site

    /** Cherche l'adresse qui marche (les anciennes adresses redirigent vers la nouvelle). */
    suspend fun detectDomain(): String? = domainMutex.withLock {
        val candidates = (listOf(Prefs.domain) + KNOWN_DOMAINS).distinct()
        for (d in candidates) {
            try {
                val page = Http.page("https://$d/", http = Http.quickClient)
                if (page.body.contains("anime-sama", ignoreCase = true) && page.body.contains("/catalogue")) {
                    val host = page.finalUrl.toHttpUrl().host.removePrefix("www.")
                    Prefs.lastDomainCheck = System.currentTimeMillis()
                    return@withLock host
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
            }
        }
        null
    }

    /** Vérification discrète au démarrage (au plus une fois par jour). */
    suspend fun refreshDomainIfStale() {
        if (Prefs.domainManual) return
        if (System.currentTimeMillis() - Prefs.lastDomainCheck < 24 * 3600_000L) return
        detectDomain()?.let { Prefs.domain = it }
    }

    /** Exécute la requête ; si le site ne répond plus, cherche sa nouvelle adresse et réessaie une fois. */
    private suspend fun <T> withDomain(block: suspend (String) -> T): T {
        try {
            return block(base)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: IOException) {
            if (e is HttpException && e.code == 404) throw e
            if (Prefs.domainManual) throw e
            val found = detectDomain() ?: throw e
            if (found == Prefs.domain) throw e
            Prefs.domain = found
            return block(base)
        }
    }

    // ---------------------------------------------------------------- Liens du site

    private data class Link(val slug: String, val stem: String?, val lang: String?)

    private val LINK_RE = Regex("""/catalogue/([A-Za-z0-9][A-Za-z0-9._%\-]*)/?([^?#]*)""")
    private val LANG_RE = Regex("""v[a-z]{1,4}\d?""")

    private fun parseLink(href: String): Link? {
        val m = LINK_RE.find(href) ?: return null
        val slug = m.groupValues[1]
        val rest = m.groupValues[2].trim('/')
        if (rest.contains("scan", ignoreCase = true)) return null
        val parts = rest.split('/').filter { it.isNotBlank() }
        if (parts.isEmpty()) return Link(slug, null, null)
        val last = parts.last().lowercase()
        return if (last in Langs.all || LANG_RE.matches(last)) {
            Link(slug, parts.dropLast(1).joinToString("/").ifBlank { null }, last)
        } else {
            Link(slug, parts.joinToString("/"), null)
        }
    }

    fun prettySlug(slug: String): String =
        slug.split('-').filter { it.isNotBlank() }.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    /** « saison2 » → « Saison 2 », « film » → « Film », « oav » → « OAV ». */
    fun seasonLabel(stem: String): String {
        val s = stem.substringAfterLast('/')
        Regex("""saison\s*(\d+(?:-\d+)?)(.*)""", RegexOption.IGNORE_CASE).matchEntire(s)?.let { m ->
            val extra = m.groupValues[2].trim().let { if (it.isEmpty()) "" else " " + it.uppercase() }
            return "Saison ${m.groupValues[1]}$extra"
        }
        return when (s.lowercase()) {
            "film", "films" -> "Film"
            "oav", "ova" -> "OAV"
            else -> s.replaceFirstChar { it.uppercase() }
        }
    }

    private fun linkSubtitle(link: Link): String? {
        val parts = listOfNotNull(link.stem?.let { seasonLabel(it) }, link.lang?.let { Langs.label(it) })
        return parts.joinToString(" · ").ifBlank { null }
    }

    private fun cardTitle(a: Element, slug: String): String {
        val candidates = listOfNotNull(
            a.selectFirst("h1, h2, h3, h4, .card-title")?.text(),
            a.selectFirst("img[alt]")?.attr("alt"),
            a.attr("title"),
            a.text(),
        )
        return candidates.map { it.trim() }.firstOrNull { it.length in 2..90 } ?: prettySlug(slug)
    }

    private fun cardImage(a: Element, slug: String): String {
        val img = a.select("img").map { it.absUrl("src").ifBlank { it.absUrl("data-src") } }
            .firstOrNull { it.contains("/contenu/") }
        return img ?: cover(slug)
    }

    private fun stripComments(text: String): String = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""(?m)^\s*//.*$"""), "")

    // ---------------------------------------------------------------- Accueil

    suspend fun home(): List<HomeSection> = withDomain { b ->
        val html = Http.getString("$b/")
        withContext(Dispatchers.Default) { parseHome(Jsoup.parse(html, "$b/")) }
    }

    private class Bucket(val title: String) {
        val items = LinkedHashMap<String, AnimeCard>()
    }

    private fun parseHome(doc: Document): List<HomeSection> {
        val buckets = mutableListOf(Bucket("À l'affiche"))
        val skip = doc.select("header a, nav a, footer a").toSet()
        for (el in doc.body().select("h1, h2, h3, a[href]")) {
            if (el.tagName() != "a") {
                if (el.parents().any { it.tagName() == "a" }) continue
                val t = el.text().trim()
                if (t.length in 2..80) buckets += Bucket(t)
                continue
            }
            if (el in skip) continue
            val link = parseLink(el.attr("href")) ?: continue
            val bucket = buckets.last()
            val existing = bucket.items[link.slug]
            if (existing == null) {
                bucket.items[link.slug] = AnimeCard(
                    slug = link.slug,
                    title = cardTitle(el, link.slug),
                    cover = cardImage(el, link.slug),
                    subtitle = linkSubtitle(link),
                    season = link.stem,
                )
            } else if (link.lang != null && existing.subtitle != null) {
                val l = Langs.label(link.lang)
                if (!existing.subtitle.contains(l)) {
                    bucket.items[link.slug] = existing.copy(subtitle = existing.subtitle + "/" + l)
                }
            }
        }
        // Les grandes cartes « à la une » ont chacune leur titre : on les regroupe.
        val featured = LinkedHashMap<String, AnimeCard>()
        val out = mutableListOf<HomeSection>()
        for (b in buckets) {
            if (b.items.size <= 1) b.items.values.forEach { featured.putIfAbsent(it.slug, it) }
            else out += HomeSection(b.title, b.items.values.toList())
        }
        if (featured.size >= 2) out.add(0, HomeSection("À l'affiche", featured.values.toList()))
        return out
    }

    // ---------------------------------------------------------------- Catalogue et recherche

    /** Genres proposés comme filtres (orthographe du site). */
    val GENRES = listOf(
        "Action", "Aventure", "Comédie", "Drame", "Fantasy", "Fantastique", "Horreur", "Isekai", "Mystère",
        "Psychologique", "Romance", "School Life", "Science-fiction", "Seinen", "Shônen", "Shôjo",
        "Slice of Life", "Sport", "Surnaturel", "Thriller",
    )

    suspend fun catalogue(query: String, page: Int, vfOnly: Boolean, genres: Set<String>): CataloguePage =
        withDomain { b ->
            val url = "$b/catalogue/".toHttpUrl().newBuilder().apply {
                addQueryParameter("type[]", "Anime")
                if (vfOnly) addQueryParameter("langue[]", "VF")
                genres.forEach { addQueryParameter("genre[]", it) }
                addQueryParameter("search", query)
                if (page > 1) addQueryParameter("page", page.toString())
            }.build().toString()
            val html = Http.getString(url)
            withContext(Dispatchers.Default) { parseCatalogue(Jsoup.parse(html, url), page) }
        }

    private fun parseCatalogue(doc: Document, page: Int): CataloguePage {
        val root = doc.selectFirst("#list_catalog") ?: doc.body()
        val skip = doc.select("header a, nav a, footer a").toSet()
        val items = LinkedHashMap<String, AnimeCard>()
        for (a in root.select("a[href]")) {
            if (a in skip) continue
            val link = parseLink(a.attr("href")) ?: continue
            if (link.stem != null) continue
            if (link.slug !in items) {
                items[link.slug] = AnimeCard(link.slug, cardTitle(a, link.slug), cardImage(a, link.slug))
            }
        }
        val pageRe = Regex("""[?&]page=(\d+)""")
        val maxPage = doc.select("a[href*=page=]")
            .mapNotNull { pageRe.find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull() ?: 0
        return CataloguePage(items.values.toList(), hasNext = items.isNotEmpty() && maxPage > page)
    }

    // ---------------------------------------------------------------- Fiche d'un anime

    private val PANEL_RE = Regex("""panneauAnime\(\s*["']([^"']*)["']\s*,\s*["']([^"']*)["']\s*\)""")

    fun cachedDetails(slug: String): AnimeDetails? = detailsCache[slug]

    suspend fun details(slug: String, force: Boolean = false): AnimeDetails {
        if (!force) detailsCache[slug]?.let { return it }
        val d = withDomain { b ->
            val url = "$b/catalogue/$slug/"
            val html = Http.getString(url)
            withContext(Dispatchers.Default) { parseDetails(slug, html, url) }
        }
        detailsCache[slug] = d
        return d
    }

    private fun parseDetails(slug: String, html: String, url: String): AnimeDetails {
        val doc = Jsoup.parse(html, url)
        val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?.substringBefore(" - Anime-Sama")?.substringBefore(" | ")?.trim()
        val title = doc.selectFirst("#titreOeuvre")?.text()?.trim()?.ifBlank { null }
            ?: ogTitle?.ifBlank { null }
            ?: doc.selectFirst("h1")?.text()?.trim()?.ifBlank { null }
            ?: prettySlug(slug)
        val alt = doc.selectFirst("#titreAlter")?.text()?.trim()?.ifBlank { null }
        val cover = doc.selectFirst("#coverOeuvre")?.absUrl("src")?.ifBlank { null }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
            ?: cover(slug)

        fun afterHeading(word: String): Element? {
            val h = doc.select("h2, h3").firstOrNull { it.text().contains(word, ignoreCase = true) } ?: return null
            return h.nextElementSibling()
        }

        val synopsis = afterHeading("synopsis")?.text()?.trim()?.ifBlank { null }
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()?.ifBlank { null }
        val genres = afterHeading("genre")?.let { el ->
            val links = el.select("a").map { it.text().trim() }.filter { it.isNotEmpty() }
            links.ifEmpty { el.text().split(',', '-', '•').map { it.trim() }.filter { it.isNotEmpty() } }
        }.orEmpty()

        // Saisons : appels panneauAnime("Saison 1", "saison1/vostfr") dans les scripts de la page.
        val seasons = LinkedHashMap<String, Season>()
        for (m in PANEL_RE.findAll(stripComments(html))) {
            val name = m.groupValues[1].trim()
            val path = m.groupValues[2].trim()
            if (name.equals("nom", true) || path.equals("url", true) || path.isBlank()) continue
            val link = parseLink("/catalogue/$slug/$path") ?: continue
            val stem = link.stem ?: continue
            seasons.putIfAbsent(stem, Season(name.ifBlank { seasonLabel(stem) }, stem))
        }
        // Secours : liens directs vers les saisons.
        if (seasons.isEmpty()) {
            for (a in doc.select("a[href]")) {
                val link = parseLink(a.attr("href")) ?: continue
                if (link.slug != slug) continue
                val stem = link.stem ?: continue
                seasons.putIfAbsent(stem, Season(seasonLabel(stem), stem))
            }
        }
        return AnimeDetails(slug, title, alt, cover, synopsis, genres, seasons.values.toList())
    }

    // ---------------------------------------------------------------- Épisodes

    fun cachedEpisodes(slug: String, stem: String, lang: String): List<Episode>? = episodesCache["$slug/$stem/$lang"]

    /** Épisodes d'une saison dans une langue, ou null si cette langue n'existe pas pour la saison. */
    suspend fun episodes(slug: String, stem: String, lang: String, force: Boolean = false): List<Episode>? {
        val key = "$slug/$stem/$lang"
        if (!force) episodesCache[key]?.let { return it }
        val list = withDomain { b -> fetchEpisodes(b, slug, stem, lang) } ?: return null
        episodesCache[key] = list
        return list
    }

    /** Toutes les langues disponibles pour une saison, chargées en parallèle. */
    suspend fun allLangs(slug: String, stem: String, force: Boolean = false): Map<String, List<Episode>> =
        coroutineScope {
            val results = Langs.all.map { lang ->
                async {
                    lang to try {
                        episodes(slug, stem, lang, force)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Exception) {
                        if (lang == "vostfr") throw e else null
                    }
                }
            }.awaitAll()
            results.mapNotNull { (lang, eps) -> eps?.takeIf { it.isNotEmpty() }?.let { lang to it } }.toMap()
        }

    private suspend fun fetchEpisodes(b: String, slug: String, stem: String, lang: String): List<Episode>? {
        val seasonUrl = "$b/catalogue/$slug/$stem/$lang/"
        val page = try {
            Http.getString(seasonUrl, mapOf("Referer" to "$b/catalogue/$slug/"))
        } catch (e: HttpException) {
            if (e.code == 404) return null else throw e
        }
        if (page.contains("<title>Page introuvable", ignoreCase = true)) return null
        // Les listes de lecteurs (eps1, eps2…) sont maintenant écrites directement dans la page de la saison.
        var arrays = withContext(Dispatchers.Default) { parseEpisodesJs(page) }
        if (arrays.isEmpty()) {
            // Ancien format : fichier episodes.js à côté de la page.
            val js = try {
                Http.getString(seasonUrl + "episodes.js", mapOf("Referer" to seasonUrl))
            } catch (e: HttpException) {
                if (e.code == 404 || e.code == 403) return null else throw e
            }
            arrays = withContext(Dispatchers.Default) { parseEpisodesJs(js) }
        }
        return withContext(Dispatchers.Default) {
            val count = arrays.maxOfOrNull { it.size } ?: 0
            if (count == 0) return@withContext null
            val labels = parseLabels(page, count, stem.contains("film", true))
            (0 until count).map { i ->
                val players = arrays.mapNotNull { arr -> arr.getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() } }
                Episode(i, labels[i], players)
            }
        }
    }

    /** Adresse de la page d'une saison sur le site (c'est elle qui contient le lecteur). */
    fun seasonUrl(slug: String, stem: String, lang: String) = "$base/catalogue/$slug/$stem/$lang/"

    /** `var eps1 = ['url', 'url', …];` → une liste par lecteur. */
    private fun parseEpisodesJs(js: String): List<List<String>> {
        val clean = stripComments(js)
        val arrRe = Regex("""\b(eps\d+)\s*=\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
        val strRe = Regex("""'([^']*)'|"([^"]*)"""")
        val seen = HashSet<String>()
        return arrRe.findAll(clean).mapNotNull { m ->
            if (!seen.add(m.groupValues[1])) return@mapNotNull null
            strRe.findAll(m.groupValues[2]).map { s -> s.groupValues[1].ifEmpty { s.groupValues[2] }.trim() }.toList()
        }.filter { arr -> arr.any { it.isNotBlank() } }.toList()
    }

    /**
     * Noms des épisodes à partir des appels du site (creerListe, newSP, newSPF, finirListe).
     * Si ça ne colle pas, on numérote simplement 1, 2, 3…
     */
    private fun parseLabels(page: String, count: Int, film: Boolean): List<String> {
        val default = (0 until count).map { if (film) "Film ${it + 1}" else "Épisode ${it + 1}" }
        val labels = mutableListOf<String>()
        val callRe = Regex("""\b(creerListe|finirListe|newSPF|newSP)\(([^)]*)\)""")
        fun arg(s: String): String? {
            val t = s.trim()
            return when {
                t.length >= 2 && (t.startsWith("\"") || t.startsWith("'")) -> t.substring(1, t.length - 1).trim()
                t.toDoubleOrNull() != null -> t
                else -> null // définition de fonction (paramètre nommé) : on ignore
            }
        }
        for (m in callRe.findAll(stripComments(page))) {
            val args = m.groupValues[2].split(',')
            when (m.groupValues[1]) {
                "creerListe" -> {
                    val a = args.getOrNull(0)?.trim()?.toIntOrNull() ?: continue
                    val z = args.getOrNull(1)?.trim()?.toIntOrNull() ?: continue
                    if (z < a || z - a > 5000) continue
                    for (k in a..z) labels += "Épisode $k"
                }
                "finirListe" -> {
                    var a = args.getOrNull(0)?.trim()?.toIntOrNull() ?: continue
                    while (labels.size < count) labels += "Épisode ${a++}"
                }
                "newSP" -> arg(m.groupValues[2])?.let { labels += "Épisode $it" }
                "newSPF" -> arg(m.groupValues[2])?.let { labels += it }
            }
        }
        if (labels.isEmpty()) return default
        return (0 until count).map { labels.getOrNull(it) ?: default[it] }
    }

    /** Saison suivante (hors films) pour enchaîner automatiquement. */
    fun nextSeason(slug: String, stem: String): Season? {
        val seasons = detailsCache[slug]?.seasons ?: return null
        val i = seasons.indexOfFirst { it.stem == stem }
        if (i < 0) return null
        return seasons.drop(i + 1).firstOrNull { !it.isFilm }
    }
}
