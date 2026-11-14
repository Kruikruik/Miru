package com.miru.anime.source

import com.miru.anime.model.AnimeDetails
import com.miru.anime.model.CataloguePage
import com.miru.anime.model.Episode
import com.miru.anime.model.HomeSection
import com.miru.anime.model.Season

/** Comment afficher le lecteur d'une page du site. */
data class PlayerConfig(
    /** Bloc de la page qui contient la vidéo (affiché en plein écran, le reste est caché). */
    val container: String,
    /** Menu des lecteurs (hébergeurs) de la page. */
    val lecteurSelect: String,
    /** Menu des épisodes de la page, si une seule page sert pour toute la saison. */
    val episodeSelect: String?,
)

/** Un site d'animes. */
interface Source {
    val id: String
    val name: String
    val base: String
    val genres: List<String>
    val hasVfFilter: Boolean
    val player: PlayerConfig

    suspend fun home(): List<HomeSection>
    suspend fun catalogue(query: String, page: Int, vfOnly: Boolean, genres: Set<String>): CataloguePage
    fun cachedDetails(slug: String): AnimeDetails?
    suspend fun details(slug: String, force: Boolean = false): AnimeDetails
    suspend fun episodes(slug: String, stem: String, lang: String, force: Boolean = false): List<Episode>?
    suspend fun allLangs(slug: String, stem: String, force: Boolean = false): Map<String, List<Episode>>
    fun nextSeason(slug: String, stem: String): Season?

    /** Page à ouvrir pour regarder cet épisode. */
    fun playerUrl(slug: String, stem: String, lang: String, episode: Episode): String
}

object Sources {
    val all: List<Source> = listOf(AnimeSama, Voiranime)

    /** Les animes de Voiranime ont un identifiant qui commence par « va: ». */
    fun of(slug: String): Source = if (slug.startsWith(Voiranime.PREFIX)) Voiranime else AnimeSama

    fun byId(id: String): Source = all.firstOrNull { it.id == id } ?: AnimeSama
}
