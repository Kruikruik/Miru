package com.miru.anime.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.miru.anime.data.Library
import com.miru.anime.data.Prefs
import com.miru.anime.data.episodeKey
import com.miru.anime.model.AnimeDetails
import com.miru.anime.model.Episode
import com.miru.anime.model.Langs
import com.miru.anime.player.PlayerActivity
import com.miru.anime.source.AnimeSama
import kotlinx.coroutines.CancellationException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailsScreen(slug: String, initialSeason: String?, onBack: () -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val history by Library.history.collectAsState()
    val favorites by Library.favorites.collectAsState()
    val progress by Library.progress.collectAsState()
    val lastWatched = history.firstOrNull { it.slug == slug }

    var details by remember { mutableStateOf(AnimeSama.cachedDetails(slug)) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var stem by remember { mutableStateOf(initialSeason ?: lastWatched?.stem) }
    var langs by remember { mutableStateOf<Map<String, List<Episode>>?>(null) }
    var langError by remember { mutableStateOf<String?>(null) }
    var lang by remember { mutableStateOf<String?>(null) }
    var synopsisOpen by remember { mutableStateOf(false) }

    LaunchedEffect(reload) {
        error = null
        try {
            val d = AnimeSama.details(slug, force = reload > 0)
            details = d
            if (stem == null || d.seasons.none { it.stem == stem }) {
                stem = d.seasons.firstOrNull()?.stem
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            if (details == null) error = errorText(e)
        }
    }

    LaunchedEffect(stem, reload) {
        val s = stem ?: return@LaunchedEffect
        langs = null
        langError = null
        try {
            val map = AnimeSama.allLangs(slug, s, force = reload > 0)
            langs = map
            val wanted = listOfNotNull(
                lang,
                lastWatched?.takeIf { it.stem == s }?.lang,
                Prefs.preferredLang,
            )
            lang = wanted.firstOrNull { it in map } ?: map.keys.firstOrNull()
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            langError = errorText(e)
        }
    }

    val d = details
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(d?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") }
                },
                actions = {
                    IconButton(onClick = { reload++ }) { Icon(Icons.Default.Refresh, "Actualiser") }
                },
            )
        },
    ) { padding ->
        when {
            d == null && error != null -> ErrorBox(error!!, onRetry = { reload++ }, Modifier.fillMaxSize().padding(padding), onSettings)
            d == null -> LoadingBox(Modifier.fillMaxSize().padding(padding))
            else -> {
                val season = d.seasons.firstOrNull { it.stem == stem }
                val episodes = lang?.let { langs?.get(it) }.orEmpty()

                fun play(ep: Episode) {
                    val s = season ?: return
                    val l = lang ?: return
                    PlayerActivity.start(context, slug, d.title, d.cover, s.stem, s.name, l, ep.index)
                }

                LazyColumn(
                    Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    item { Header(d, isFav = favorites.any { it.slug == slug }) }

                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val isFav = favorites.any { it.slug == slug }
                            OutlinedButton(onClick = { Library.toggleFavorite(slug, d.title, d.cover) }) {
                                Icon(if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null)
                                Spacer(Modifier.width(6.dp))
                                Text(if (isFav) "Dans la biblio" else "Ajouter")
                            }
                            if (lastWatched != null) {
                                Button(onClick = {
                                    PlayerActivity.start(
                                        context, slug, d.title, d.cover, lastWatched.stem, lastWatched.seasonName,
                                        lastWatched.lang, lastWatched.index,
                                    )
                                }) {
                                    Icon(Icons.Default.PlayArrow, null)
                                    Spacer(Modifier.width(4.dp))
                                    Text("Reprendre · ${lastWatched.label}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            } else if (episodes.isNotEmpty()) {
                                Button(onClick = { play(episodes.first()) }) {
                                    Icon(Icons.Default.PlayArrow, null)
                                    Spacer(Modifier.width(4.dp))
                                    Text("Regarder")
                                }
                            }
                        }
                    }

                    if (!d.synopsis.isNullOrBlank()) {
                        item {
                            Text(
                                d.synopsis,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = if (synopsisOpen) Int.MAX_VALUE else 4,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { synopsisOpen = !synopsisOpen }
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                    }

                    if (d.seasons.isEmpty()) {
                        item { EmptyBox("Aucune saison trouvée pour cet anime.", Modifier.fillMaxWidth()) }
                    } else {
                        item {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                items(d.seasons, key = { it.stem }) { s ->
                                    FilterChip(
                                        selected = s.stem == stem,
                                        onClick = { stem = s.stem },
                                        label = { Text(s.name) },
                                    )
                                }
                            }
                        }
                    }

                    val map = langs
                    when {
                        season == null -> {}
                        langError != null -> item {
                            ErrorBox(langError!!, onRetry = { reload++ }, Modifier.fillMaxWidth())
                        }
                        map == null -> item { LoadingBox(Modifier.fillMaxWidth().height(120.dp)) }
                        map.isEmpty() -> item { EmptyBox("Pas encore d'épisode pour cette saison.", Modifier.fillMaxWidth()) }
                        else -> {
                            item {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    map.keys.forEach { l ->
                                        FilterChip(
                                            selected = l == lang,
                                            onClick = { lang = l },
                                            label = { Text(Langs.label(l)) },
                                        )
                                    }
                                    Spacer(Modifier.weight(1f))
                                    val keys = episodes.map { episodeKey(slug, season.stem, it.index) }
                                    val allSeen = keys.isNotEmpty() && keys.all { progress[it]?.watched == true }
                                    TextButton(onClick = { Library.setWatched(keys, !allSeen) }) {
                                        Text(if (allSeen) "Tout non vu" else "Tout vu")
                                    }
                                }
                            }
                            items(episodes, key = { "${season.stem}-${it.index}" }) { ep ->
                                val key = episodeKey(slug, season.stem, ep.index)
                                EpisodeRow(
                                    ep = ep,
                                    progress = progress[key],
                                    isLast = lastWatched?.stem == season.stem && lastWatched.index == ep.index,
                                    onClick = { play(ep) },
                                    onToggleSeen = { Library.setWatched(listOf(key), progress[key]?.watched != true) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(d: AnimeDetails, isFav: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Poster(d.cover, d.title, Modifier.width(112.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(d.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (!d.altTitles.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    d.altTitles,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (d.genres.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    d.genres.joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "${d.seasons.size} saison${if (d.seasons.size > 1) "s" else ""}" + if (isFav) " · ❤ biblio" else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatTime(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

@Composable
private fun EpisodeRow(
    ep: Episode,
    progress: com.miru.anime.data.Progress?,
    isLast: Boolean,
    onClick: () -> Unit,
    onToggleSeen: () -> Unit,
) {
    val seen = progress?.watched == true
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = ep.players.isNotEmpty(), onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                ep.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal,
                color = when {
                    ep.players.isEmpty() -> MaterialTheme.colorScheme.outline
                    seen -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    isLast -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            val sub = when {
                ep.players.isEmpty() -> "Pas encore disponible"
                seen -> "Vu"
                progress != null && progress.position > 0 -> "Arrêté à ${formatTime(progress.position)}"
                else -> "${ep.players.size} lecteur${if (ep.players.size > 1) "s" else ""}"
            }
            Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!seen && progress != null && progress.fraction > 0.01f) {
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier.fillMaxWidth(0.6f).height(3.dp),
                )
            }
        }
        IconButton(onClick = onToggleSeen) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = if (seen) "Marquer non vu" else "Marquer vu",
                    tint = if (seen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

@Suppress("unused")
@Composable
private fun SmallSpinner() = CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
