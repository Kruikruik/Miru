package com.miru.anime.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.miru.anime.data.HistoryEntry
import com.miru.anime.data.Library
import com.miru.anime.data.Prefs
import com.miru.anime.data.episodeKey
import com.miru.anime.model.Episode
import com.miru.anime.model.Langs
import com.miru.anime.net.Http
import com.miru.anime.source.AnimeSama
import com.miru.anime.source.Extractors
import com.miru.anime.source.Stream
import com.miru.anime.ui.MiruIcons
import com.miru.anime.ui.MiruTheme
import com.miru.anime.ui.errorText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@androidx.annotation.OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    companion object {
        private const val SLUG = "slug"
        private const val TITLE = "title"
        private const val COVER = "cover"
        private const val STEM = "stem"
        private const val SEASON = "season"
        private const val LANG = "lang"
        private const val INDEX = "index"

        fun start(
            context: Context,
            slug: String,
            title: String,
            cover: String,
            stem: String,
            seasonName: String,
            lang: String,
            index: Int,
        ) {
            val intent = Intent(context, PlayerActivity::class.java)
                .putExtra(SLUG, slug)
                .putExtra(TITLE, title)
                .putExtra(COVER, cover)
                .putExtra(STEM, stem)
                .putExtra(SEASON, seasonName)
                .putExtra(LANG, lang)
                .putExtra(INDEX, index)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    private lateinit var player: ExoPlayer

    private var slug = ""
    private var title by mutableStateOf("")
    private var cover = ""
    private var stem by mutableStateOf("")
    private var seasonName by mutableStateOf("")
    private var lang by mutableStateOf("vostfr")
    private var episodes by mutableStateOf<List<Episode>>(emptyList())
    private var index by mutableIntStateOf(0)

    /** Message affiché pendant le chargement (null = la vidéo joue). */
    private var status by mutableStateOf<String?>(null)
    private var failure by mutableStateOf<String?>(null)
    private var currentPlayer by mutableStateOf<String?>(null)
    private var inPip by mutableStateOf(false)

    private var candidates: List<String> = emptyList()
    private var candidateIndex = 0
    private var pendingSeek = 0L
    private var loadJob: Job? = null

    private val currentEpisode: Episode? get() = episodes.getOrNull(index)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readIntent(intent)
        goFullscreen()

        player = ExoPlayer.Builder(this)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.addListener(listener)

        setContent { MiruTheme { PlayerScreen() } }

        openEpisode(index)

        // Sauvegarde régulière de la position.
        lifecycleScope.launch {
            while (isActive) {
                delay(5_000)
                saveProgress()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        saveProgress()
        readIntent(intent)
        openEpisode(index)
    }

    private fun readIntent(i: Intent) {
        slug = i.getStringExtra(SLUG).orEmpty()
        title = i.getStringExtra(TITLE).orEmpty()
        cover = i.getStringExtra(COVER).orEmpty()
        stem = i.getStringExtra(STEM).orEmpty()
        seasonName = i.getStringExtra(SEASON).orEmpty()
        lang = i.getStringExtra(LANG) ?: "vostfr"
        index = i.getIntExtra(INDEX, 0)
        episodes = emptyList()
    }

    private fun goFullscreen() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ------------------------------------------------------------------ Chargement

    private fun key(i: Int) = episodeKey(slug, stem, i)

    private fun openEpisode(i: Int, preferred: String? = null) {
        saveProgress()
        loadJob?.cancel()
        player.stop()
        player.clearMediaItems()
        failure = null
        status = "Chargement…"
        index = i
        loadJob = lifecycleScope.launch {
            try {
                if (episodes.isEmpty()) {
                    episodes = AnimeSama.episodes(slug, stem, lang)
                        ?: AnimeSama.episodes(slug, stem, "vostfr")?.also { lang = "vostfr" }
                        ?: emptyList()
                }
                val ep = currentEpisode
                if (ep == null) {
                    status = null
                    failure = "Épisode introuvable."
                    return@launch
                }
                Library.record(
                    HistoryEntry(slug, title, cover, stem, seasonName, lang, ep.index, ep.label, System.currentTimeMillis()),
                )
                val ordered = Extractors.sortPlayers(ep.players, Prefs.preferredHost)
                candidates = if (preferred != null) listOf(preferred) + ordered.filter { it != preferred } else ordered
                candidateIndex = 0
                val saved = Library.progress.value[key(i)]
                pendingSeek = if (saved != null && !saved.watched && saved.position > 5_000 &&
                    (saved.duration <= 0 || saved.position < saved.duration - 20_000)
                ) saved.position else 0L
                tryCandidates()
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                status = null
                failure = errorText(e)
            }
        }
    }

    /** Essaie les lecteurs un par un jusqu'à en trouver un qui donne une vidéo. */
    private suspend fun tryCandidates() {
        while (candidateIndex < candidates.size) {
            val url = candidates[candidateIndex]
            currentPlayer = url
            status = "Lecteur ${Extractors.hostName(url)} (${candidateIndex + 1}/${candidates.size})…"
            val stream = try {
                Extractors.extract(url, AnimeSama.base + "/")
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                null
            }
            if (stream != null) {
                play(stream)
                return
            }
            candidateIndex++
        }
        status = null
        failure = if (candidates.isEmpty()) "Aucun lecteur pour cet épisode." else
            "Aucun lecteur n'a marché.\nEssaie le lecteur web : il ouvre la page du lecteur directement."
    }

    private fun play(stream: Stream) {
        val dataSource = OkHttpDataSource.Factory(Http.client)
            .setUserAgent(Http.USER_AGENT)
            .setDefaultRequestProperties(stream.headers)
        val item = MediaItem.Builder()
            .setUri(stream.url)
            .apply { if (stream.isHls) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()
        player.setMediaSource(DefaultMediaSourceFactory(dataSource).createMediaSource(item))
        player.prepare()
        if (pendingSeek > 0) player.seekTo(pendingSeek)
        player.playWhenReady = true
        status = "Chargement de la vidéo…"
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_READY -> status = null
                Player.STATE_ENDED -> onEnded()
                else -> {}
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            // On garde la position et on passe au lecteur suivant.
            if (player.currentPosition > 5_000) pendingSeek = player.currentPosition
            candidateIndex++
            loadJob?.cancel()
            loadJob = lifecycleScope.launch { tryCandidates() }
        }
    }

    private fun onEnded() {
        val dur = player.duration
        if (dur > 0 && episodes.isNotEmpty()) Library.saveProgress(key(index), dur, dur)
        if (Prefs.autoNext) next(auto = true)
    }

    private fun saveProgress() {
        if (!::player.isInitialized || episodes.isEmpty()) return
        val dur = player.duration
        val pos = player.currentPosition
        if (dur == C.TIME_UNSET || dur <= 0 || pos <= 0) return
        Library.saveProgress(key(index), pos, dur)
    }

    private fun previous() {
        if (index > 0) openEpisode(index - 1)
    }

    /** Épisode suivant, ou premier épisode de la saison suivante. */
    private fun next(auto: Boolean = false) {
        if (index + 1 < episodes.size) {
            openEpisode(index + 1)
            return
        }
        lifecycleScope.launch {
            try {
                AnimeSama.details(slug)
                val s = AnimeSama.nextSeason(slug, stem)
                if (s == null) {
                    if (!auto) toast("C'était le dernier épisode.")
                    return@launch
                }
                var newLang = lang
                val eps = AnimeSama.episodes(slug, s.stem, lang)
                    ?: AnimeSama.episodes(slug, s.stem, "vostfr")?.also { newLang = "vostfr" }
                if (eps.isNullOrEmpty()) {
                    toast("${s.name} n'a pas encore d'épisode.")
                    return@launch
                }
                saveProgress()
                stem = s.stem
                seasonName = s.name
                lang = newLang
                episodes = eps
                openEpisode(0)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                toast(errorText(e))
            }
        }
    }

    private fun skipIntro() {
        val target = player.currentPosition + Prefs.skipSeconds * 1000L
        val dur = player.duration
        player.seekTo(if (dur > 0) minOf(target, dur - 1000) else target)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------------ Image dans l'image

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (::player.isInitialized && player.isPlaying) enterPip()
    }

    private fun enterPip() {
        runCatching {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
    }

    override fun onStop() {
        super.onStop()
        saveProgress()
        if (!inPip) player.pause()
    }

    override fun onDestroy() {
        saveProgress()
        player.removeListener(listener)
        player.release()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ Interface

    @Composable
    private fun PlayerScreen() {
        var controlsVisible by remember { mutableStateOf(true) }
        var showPlayers by remember { mutableStateOf(false) }
        var showEpisodes by remember { mutableStateOf(false) }
        val ep = currentEpisode
        val busy = status != null || failure != null

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = this@PlayerActivity.player
                        setShowNextButton(false)
                        setShowPreviousButton(false)
                        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        controllerShowTimeoutMs = 3500
                        keepScreenOn = true
                        setControllerVisibilityListener(
                            PlayerView.ControllerVisibilityListener { v -> controlsVisible = v == View.VISIBLE },
                        )
                    }
                },
                update = { it.useController = !inPip },
                modifier = Modifier.fillMaxSize(),
            )

            if (inPip) return@Box

            if (controlsVisible || busy) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { finish() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour", tint = Color.White)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(seasonName, ep?.label, Langs.label(lang)).joinToString(" · "),
                            color = Color.White.copy(alpha = 0.75f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                        )
                    }
                    TextButton(onClick = { showPlayers = true }) {
                        Text(currentPlayer?.let { Extractors.hostName(it) } ?: "Lecteurs", color = Color.White)
                    }
                    IconButton(onClick = { showEpisodes = true }) {
                        Icon(Icons.AutoMirrored.Filled.List, "Épisodes", tint = Color.White)
                    }
                    IconButton(onClick = { previous() }, enabled = index > 0) {
                        Icon(MiruIcons.SkipPrevious, "Épisode précédent", tint = if (index > 0) Color.White else Color.Gray)
                    }
                    IconButton(onClick = { next() }) {
                        Icon(MiruIcons.SkipNext, "Épisode suivant", tint = Color.White)
                    }
                }
            }

            if (controlsVisible && !busy) {
                Button(
                    onClick = { skipIntro() },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 24.dp, bottom = 96.dp),
                ) {
                    Icon(MiruIcons.FastForward, null)
                    Spacer(Modifier.width(6.dp))
                    Text("+${Prefs.skipSeconds} s")
                }
            }

            val st = status
            val fail = failure
            if (fail != null) {
                Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(fail, color = Color.White, textAlign = TextAlign.Center)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { openEpisode(index) }) { Text("Réessayer") }
                        val web = currentPlayer ?: ep?.players?.firstOrNull()
                        if (web != null) {
                            OutlinedButton(onClick = { WebPlayerActivity.start(this@PlayerActivity, web) }) {
                                Text("Lecteur web", color = Color.White)
                            }
                        }
                        if ((ep?.players?.size ?: 0) > 1) {
                            OutlinedButton(onClick = { showPlayers = true }) { Text("Choisir", color = Color.White) }
                        }
                    }
                }
            } else if (st != null) {
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.width(8.dp))
                    Text(st, color = Color.White, modifier = Modifier.padding(top = 12.dp))
                }
            }
        }

        if (showPlayers && ep != null) {
            AlertDialog(
                onDismissRequest = { showPlayers = false },
                title = { Text("Lecteurs") },
                text = {
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        itemsIndexed(ep.players) { i, url ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showPlayers = false
                                        openEpisode(index, preferred = url)
                                    }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("${i + 1}. ${Extractors.hostName(url)}", Modifier.weight(1f))
                                if (url == currentPlayer) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        showPlayers = false
                        (currentPlayer ?: ep.players.firstOrNull())?.let { WebPlayerActivity.start(this, it) }
                    }) { Text("Lecteur web") }
                },
                dismissButton = { TextButton(onClick = { showPlayers = false }) { Text("Fermer") } },
            )
        }

        if (showEpisodes) {
            val listState = rememberLazyListState(initialFirstVisibleItemIndex = (index - 2).coerceAtLeast(0))
            AlertDialog(
                onDismissRequest = { showEpisodes = false },
                title = { Text(seasonName) },
                text = {
                    LazyColumn(Modifier.heightIn(max = 360.dp), state = listState) {
                        itemsIndexed(episodes) { i, e ->
                            val seen = Library.progress.value[key(i)]?.watched == true
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showEpisodes = false
                                        openEpisode(i)
                                    }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    e.label,
                                    Modifier.weight(1f),
                                    color = when {
                                        i == index -> MaterialTheme.colorScheme.primary
                                        seen -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                        else -> MaterialTheme.colorScheme.onSurface
                                    },
                                    fontWeight = if (i == index) FontWeight.Bold else FontWeight.Normal,
                                )
                                if (seen) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showEpisodes = false }) { Text("Fermer") } },
            )
        }
    }
}
