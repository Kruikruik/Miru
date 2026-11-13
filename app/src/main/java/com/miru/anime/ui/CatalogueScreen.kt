package com.miru.anime.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.miru.anime.model.AnimeCard
import com.miru.anime.source.AnimeSama
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** État du catalogue, gardé en mémoire tant que l'appli est ouverte. */
private object CatalogueModel {
    var query by mutableStateOf("")
    var vfOnly by mutableStateOf(false)
    var genres by mutableStateOf(setOf<String>())
    var items by mutableStateOf<List<AnimeCard>>(emptyList())
    var page by mutableStateOf(0)
    var hasNext by mutableStateOf(true)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    private var job: Job? = null

    fun reset(scope: CoroutineScope) {
        job?.cancel()
        items = emptyList()
        page = 0
        hasNext = true
        error = null
        loading = false
        loadMore(scope)
    }

    fun loadMore(scope: CoroutineScope) {
        if (loading || !hasNext) return
        loading = true
        error = null
        val next = page + 1
        job = scope.launch {
            try {
                val res = AnimeSama.catalogue(query.trim(), next, vfOnly, genres)
                val known = items.map { it.slug }.toSet()
                val fresh = res.items.filter { it.slug !in known }
                items = items + fresh
                page = next
                hasNext = res.hasNext && fresh.isNotEmpty()
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                error = errorText(e)
            } finally {
                loading = false
            }
        }
    }
}

@Composable
fun CatalogueScreen(onOpen: (String) -> Unit, onSettings: () -> Unit) {
    val m = CatalogueModel
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val grid = rememberLazyGridState()
    var text by remember { mutableStateOf(m.query) }

    LaunchedEffect(Unit) {
        if (m.items.isEmpty() && m.page == 0) m.reset(scope)
    }

    val nearEnd by remember {
        derivedStateOf {
            val last = grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= grid.layoutInfo.totalItemsCount - 6
        }
    }
    LaunchedEffect(nearEnd, m.items.size) {
        if (nearEnd && m.items.isNotEmpty()) m.loadMore(scope)
    }

    fun search() {
        focus.clearFocus()
        m.query = text
        m.reset(scope)
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("Rechercher un anime") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (text.isNotEmpty()) {
                    IconButton(onClick = { text = ""; search() }) { Icon(Icons.Default.Clear, "Effacer") }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { search() }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                FilterChip(
                    selected = m.vfOnly,
                    onClick = { m.vfOnly = !m.vfOnly; m.reset(scope) },
                    label = { Text("VF") },
                )
            }
            items(AnimeSama.GENRES) { g ->
                FilterChip(
                    selected = g in m.genres,
                    onClick = {
                        m.genres = if (g in m.genres) m.genres - g else m.genres + g
                        m.reset(scope)
                    },
                    label = { Text(g) },
                )
            }
        }

        Box(Modifier.fillMaxSize()) {
            when {
                m.items.isEmpty() && m.loading -> LoadingBox()
                m.items.isEmpty() && m.error != null -> ErrorBox(m.error!!, onRetry = { m.reset(scope) }, onSettings = onSettings)
                m.items.isEmpty() && !m.hasNext -> EmptyBox("Aucun résultat.")
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(108.dp),
                    state = grid,
                    contentPadding = PaddingValues(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(m.items, key = { it.slug }) { card ->
                        AnimeTile(title = card.title, cover = card.cover, onClick = { onOpen(card.slug) })
                    }
                    if (m.loading || m.error != null) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                if (m.loading) CircularProgressIndicator()
                                else androidx.compose.material3.TextButton(onClick = { m.loadMore(scope) }) {
                                    Text("Erreur, réessayer")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
