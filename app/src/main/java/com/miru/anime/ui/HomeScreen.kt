package com.miru.anime.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.miru.anime.data.Library
import com.miru.anime.data.episodeKey
import com.miru.anime.model.HomeSection
import com.miru.anime.player.PlayerActivity
import kotlinx.coroutines.CancellationException

/** Dernier contenu de l'accueil, gardé en mémoire pour ne pas recharger à chaque changement d'onglet. */
private object HomeCache {
    var sections: List<HomeSection>? = null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpen: (slug: String, season: String?) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    var sections by remember { mutableStateOf(HomeCache.sections) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val history by Library.history.collectAsState()
    val progress by Library.progress.collectAsState()

    LaunchedEffect(reload) {
        if (sections != null && reload == 0) return@LaunchedEffect
        error = null
        try {
            val s = com.miru.anime.source.AnimeSama.home()
            HomeCache.sections = s
            sections = s
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            if (sections == null) error = errorText(e)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Miru", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onSearch) { Icon(Icons.Default.Search, "Rechercher") }
                    IconButton(onClick = { reload++ }) { Icon(Icons.Default.Refresh, "Actualiser") }
                },
            )
        },
    ) { padding ->
        val current = sections
        when {
            current == null && error != null -> ErrorBox(
                error!!,
                onRetry = { reload++ },
                modifier = Modifier.fillMaxSize().padding(padding),
                onSettings = onSettings,
            )
            current == null -> LoadingBox(Modifier.fillMaxSize().padding(padding))
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                val resume = history.take(12)
                if (resume.isNotEmpty()) {
                    item(key = "resume") {
                        Shelf("Reprendre") {
                            items(resume, key = { it.slug }) { h ->
                                val p = progress[episodeKey(h.slug, h.stem, h.index)]
                                AnimeTile(
                                    title = h.title,
                                    cover = h.cover,
                                    subtitle = "${h.seasonName} · ${h.label}",
                                    progress = p?.fraction,
                                    width = 116.dp,
                                    onClick = {
                                        PlayerActivity.start(
                                            context, h.slug, h.title, h.cover, h.stem, h.seasonName, h.lang, h.index,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
                if (current.isEmpty()) {
                    item { EmptyBox("Rien trouvé sur la page d'accueil. Utilise le catalogue pour chercher.") }
                }
                current.forEachIndexed { i, section ->
                    item(key = "s$i") {
                        Shelf(section.title) {
                            items(section.items, key = { it.slug }) { card ->
                                AnimeTile(
                                    title = card.title,
                                    cover = card.cover,
                                    subtitle = card.subtitle,
                                    width = 116.dp,
                                    onClick = { onOpen(card.slug, card.season) },
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
private fun Shelf(title: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column(Modifier.padding(top = 12.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            content = content,
        )
    }
}
