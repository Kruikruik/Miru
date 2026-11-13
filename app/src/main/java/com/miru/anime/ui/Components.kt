package com.miru.anime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.miru.anime.source.AnimeSama

@Composable
fun Poster(url: String, title: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        AsyncImage(
            model = url,
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
fun AnimeTile(
    title: String,
    cover: String,
    subtitle: String? = null,
    badge: String? = null,
    progress: Float? = null,
    width: Dp? = null,
    onClick: () -> Unit,
) {
    Column(
        (if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(4.dp)
    ) {
        Box {
            Poster(cover, title, Modifier.fillMaxWidth())
            if (badge != null) {
                Text(
                    badge,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (progress != null && progress > 0f) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(3.dp),
                    trackColor = Color.Black.copy(alpha = 0.5f),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier.fillMaxSize()) {
    Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun ErrorBox(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier.fillMaxSize(), onSettings: (() -> Unit)? = null) {
    Column(
        modifier.padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(
            "Adresse utilisée : ${AnimeSama.base.removePrefix("https://")}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRetry) { Text("Réessayer") }
        if (onSettings != null) {
            OutlinedButton(onClick = onSettings) { Text("Changer l'adresse du site") }
        }
    }
}

@Composable
fun EmptyBox(message: String, modifier: Modifier = Modifier.fillMaxSize()) {
    Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
        Text(message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

fun errorText(e: Throwable): String = when (e) {
    is java.net.UnknownHostException -> "Pas de connexion, ou le site est bloqué par ton opérateur."
    is java.net.SocketTimeoutException -> "Le site met trop de temps à répondre."
    is com.miru.anime.net.HttpException -> "Le site a répondu une erreur (${e.code})."
    else -> e.message ?: e.toString()
}

fun timeAgo(time: Long): String {
    val diff = (System.currentTimeMillis() - time) / 1000
    return when {
        diff < 60 -> "à l'instant"
        diff < 3600 -> "il y a ${diff / 60} min"
        diff < 86400 -> "il y a ${diff / 3600} h"
        diff < 86400 * 30 -> "il y a ${diff / 86400} j"
        else -> "il y a ${diff / (86400 * 30)} mois"
    }
}
