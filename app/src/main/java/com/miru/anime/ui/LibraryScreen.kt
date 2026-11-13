package com.miru.anime.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.miru.anime.data.Library
import com.miru.anime.data.episodeKey

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onOpen: (String) -> Unit) {
    val favorites by Library.favorites.collectAsState()
    val history by Library.history.collectAsState()
    val progress by Library.progress.collectAsState()
    // Les animes regardés récemment d'abord.
    val sorted = remember(favorites, history) {
        val order = history.withIndex().associate { it.value.slug to it.index }
        favorites.sortedWith(compareBy({ order[it.slug] ?: Int.MAX_VALUE }, { -it.added }))
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Bibliothèque") }) }) { padding ->
        if (sorted.isEmpty()) {
            EmptyBox(
                "Ta bibliothèque est vide.\nOuvre un anime et appuie sur « Ajouter ».",
                Modifier.fillMaxSize().padding(padding),
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(108.dp),
                contentPadding = PaddingValues(8.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                items(sorted, key = { it.slug }) { fav ->
                    val h = history.firstOrNull { it.slug == fav.slug }
                    val p = h?.let { progress[episodeKey(it.slug, it.stem, it.index)] }
                    AnimeTile(
                        title = fav.title,
                        cover = fav.cover,
                        subtitle = h?.let { "${it.seasonName} · ${it.label}" },
                        progress = p?.takeIf { !it.watched }?.fraction,
                        onClick = { onOpen(fav.slug) },
                    )
                }
            }
        }
    }
}
