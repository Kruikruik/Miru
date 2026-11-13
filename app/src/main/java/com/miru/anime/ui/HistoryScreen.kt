package com.miru.anime.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.miru.anime.data.episodeKey
import com.miru.anime.model.Langs
import com.miru.anime.player.PlayerActivity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onOpen: (slug: String, season: String?) -> Unit) {
    val context = LocalContext.current
    val history by Library.history.collectAsState()
    val progress by Library.progress.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Historique") },
                actions = {
                    if (history.isNotEmpty()) {
                        IconButton(onClick = { confirmClear = true }) { Icon(Icons.Default.Delete, "Tout effacer") }
                    }
                },
            )
        },
    ) { padding ->
        if (history.isEmpty()) {
            EmptyBox("Rien regardé pour l'instant.", Modifier.fillMaxSize().padding(padding))
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(history, key = { it.slug }) { h ->
                    val p = progress[episodeKey(h.slug, h.stem, h.index)]
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                PlayerActivity.start(context, h.slug, h.title, h.cover, h.stem, h.seasonName, h.lang, h.index)
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Poster(h.cover, h.title, Modifier.width(56.dp).clickable { onOpen(h.slug, h.stem) })
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(h.title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${h.seasonName} · ${h.label} · ${Langs.label(h.lang)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                timeAgo(h.time) + if (p?.watched == true) " · vu" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (p != null && !p.watched && p.fraction > 0.01f) {
                                Spacer(Modifier.height(4.dp))
                                LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth(0.7f).height(3.dp))
                            }
                        }
                        IconButton(onClick = { Library.removeHistory(h.slug) }) {
                            Icon(Icons.Default.Close, "Retirer", tint = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Effacer l'historique ?") },
            text = { Text("La progression des épisodes est gardée.") },
            confirmButton = {
                TextButton(onClick = { Library.clearHistory(); confirmClear = false }) { Text("Effacer") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Annuler") } },
        )
    }
}
