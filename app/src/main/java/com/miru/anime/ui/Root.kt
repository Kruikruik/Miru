package com.miru.anime.ui

import android.net.Uri
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.miru.anime.data.AppRelease
import com.miru.anime.data.AppUpdater
import com.miru.anime.data.Prefs
import com.miru.anime.source.AnimeSama
import kotlinx.coroutines.CancellationException

private val MiruColors = darkColorScheme(
    primary = Color(0xFF8C9BFF),
    onPrimary = Color(0xFF0D1240),
    primaryContainer = Color(0xFF2B3270),
    onPrimaryContainer = Color(0xFFDDE1FF),
    secondary = Color(0xFFFF8FB1),
    secondaryContainer = Color(0xFF5C2338),
    onSecondaryContainer = Color(0xFFFFD9E2),
    background = Color(0xFF0F1015),
    surface = Color(0xFF0F1015),
    surfaceVariant = Color(0xFF242633),
    surfaceContainer = Color(0xFF181A22),
)

@Composable
fun MiruTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = MiruColors, content = content)
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

fun animeRoute(slug: String, season: String? = null): String =
    "anime/${Uri.encode(slug)}" + (season?.let { "?season=${Uri.encode(it)}" } ?: "")

fun NavHostController.openAnime(slug: String, season: String? = null) = navigate(animeRoute(slug, season))

@Composable
fun MiruRoot() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val tabs = remember {
        listOf(
            Tab("home", "Accueil", Icons.Default.Home),
            Tab("catalogue", "Catalogue", Icons.Default.Search),
            Tab("library", "Biblio", Icons.Default.Favorite),
            Tab("history", "Historique", MiruIcons.History),
            Tab("more", "Plus", Icons.Default.Settings),
        )
    }

    // Adresse du site et mise à jour de l'appli, vérifiées discrètement au démarrage.
    var release by remember { mutableStateOf<AppRelease?>(null) }
    LaunchedEffect(Unit) {
        runCatching { AnimeSama.refreshDomainIfStale() }
        val now = System.currentTimeMillis()
        if (now - Prefs.lastAppUpdateCheck > 6 * 3600_000L) {
            Prefs.lastAppUpdateCheck = now
            try {
                release = AppUpdater.check()
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
            }
        }
    }
    release?.let { AppUpdateDialog(it, onDismiss = { release = null }) }

    fun goTab(target: String) {
        if (route != target) {
            nav.navigate(target) {
                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (tabs.any { it.route == route }) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = { goTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = "home",
            modifier = Modifier.padding(padding),
        ) {
            composable("home") {
                HomeScreen(
                    onOpen = { slug, season -> nav.openAnime(slug, season) },
                    onSearch = { goTab("catalogue") },
                    onSettings = { goTab("more") },
                )
            }
            composable("catalogue") {
                CatalogueScreen(
                    onOpen = { slug -> nav.openAnime(slug) },
                    onSettings = { goTab("more") },
                )
            }
            composable("library") {
                LibraryScreen(onOpen = { slug -> nav.openAnime(slug) })
            }
            composable("history") {
                HistoryScreen(onOpen = { slug, season -> nav.openAnime(slug, season) })
            }
            composable("more") {
                MoreScreen()
            }
            composable(
                "anime/{slug}?season={season}",
                arguments = listOf(
                    navArgument("slug") { type = NavType.StringType },
                    navArgument("season") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) { entry ->
                DetailsScreen(
                    slug = entry.arguments?.getString("slug").orEmpty(),
                    initialSeason = entry.arguments?.getString("season"),
                    onBack = { nav.popBackStack() },
                    onSettings = { goTab("more") },
                )
            }
        }
    }
}
