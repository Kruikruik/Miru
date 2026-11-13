package com.miru.anime.model

/** Un anime dans une liste (accueil, catalogue). */
data class AnimeCard(
    val slug: String,
    val title: String,
    val cover: String,
    val subtitle: String? = null,
    /** Saison mise en avant par le lien (ex. « saison2 »), si le lien pointait vers une saison. */
    val season: String? = null,
)

data class HomeSection(val title: String, val items: List<AnimeCard>)

data class CataloguePage(val items: List<AnimeCard>, val hasNext: Boolean)

/** Une saison, un film ou un OAV. `stem` est le chemin sur le site, ex. « saison1 », « film », « saison2-4 ». */
data class Season(val name: String, val stem: String) {
    val isFilm: Boolean get() = stem.contains("film", ignoreCase = true)
}

data class AnimeDetails(
    val slug: String,
    val title: String,
    val altTitles: String?,
    val cover: String,
    val synopsis: String?,
    val genres: List<String>,
    val seasons: List<Season>,
)

/** Un épisode : son numéro dans la saison, son nom et les liens des différents lecteurs. */
data class Episode(val index: Int, val label: String, val players: List<String>)

/** Langues proposées par Anime-Sama, dans l'ordre d'affichage. */
object Langs {
    val all = listOf("vostfr", "vf", "vf1", "vf2")
    fun label(lang: String): String = lang.uppercase()
}
