package com.miru.anime.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.miru.anime.BuildConfig
import com.miru.anime.data.AppRelease
import com.miru.anime.data.AppUpdater
import com.miru.anime.data.Prefs
import com.miru.anime.model.Langs
import com.miru.anime.source.AnimeSama
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun MoreScreen() {
    val scope = rememberCoroutineScope()
    var domain by remember { mutableStateOf(Prefs.domain) }
    var domainInput by remember { mutableStateOf(Prefs.domain) }
    var domainStatus by remember { mutableStateOf<String?>(if (Prefs.domainManual) "Adresse fixée à la main" else null) }
    var lang by remember { mutableStateOf(Prefs.preferredLang) }
    var vaInput by remember { mutableStateOf(Prefs.voiranimeDomain) }
    var vaStatus by remember { mutableStateOf<String?>(null) }
    var host by remember { mutableStateOf(Prefs.preferredHost) }
    var updateStatus by remember { mutableStateOf<String?>(null) }
    var release by remember { mutableStateOf<AppRelease?>(null) }

    Scaffold(topBar = { TopAppBar(title = { Text("Plus") }) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Section("Adresse d'Anime-Sama")
            Text(
                "Le site change souvent d'adresse. Miru cherche la nouvelle tout seul quand l'ancienne ne répond plus.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Text(
                "Adresse actuelle : $domain",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    domainStatus = "Recherche…"
                    scope.launch {
                        val found = try {
                            Prefs.domainManual = false
                            AnimeSama.detectDomain()
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (_: Exception) {
                            null
                        }
                        if (found != null) {
                            Prefs.domain = found
                            domain = found
                            domainInput = found
                            domainStatus = "Trouvée : $found"
                        } else {
                            domainStatus = "Aucune adresse ne répond. Vérifie ta connexion ou entre l'adresse à la main."
                        }
                    }
                }) { Text("Détecter") }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = domainInput,
                    onValueChange = { domainInput = it },
                    singleLine = true,
                    label = { Text("Adresse à la main") },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = {
                    val clean = domainInput.trim().removePrefix("https://").removePrefix("http://")
                        .removePrefix("www.").trimEnd('/')
                    if (clean.contains('.')) {
                        Prefs.domain = clean
                        Prefs.domainManual = true
                        domain = clean
                        domainStatus = "Adresse fixée à la main"
                    }
                }) { Text("OK") }
            }
            domainStatus?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            HorizontalDivider(Modifier.padding(top = 16.dp))
            Section("Adresse de Voiranime")
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = vaInput,
                    onValueChange = { vaInput = it },
                    singleLine = true,
                    label = { Text("Adresse") },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = {
                    val clean = vaInput.trim().removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')
                    if (clean.contains('.')) {
                        Prefs.voiranimeDomain = clean
                        vaInput = clean
                        vaStatus = "Enregistrée"
                    }
                }) { Text("OK") }
            }
            vaStatus?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }

            HorizontalDivider(Modifier.padding(top = 16.dp))
            Section("Lecture")
            Label("Langue par défaut")
            ChipRow(listOf("vostfr", "vf"), lang, { Langs.label(it) }) { lang = it; Prefs.preferredLang = it }
            Label("Lecteur essayé en premier")
            ChipRow(listOf("Auto", "Vidmoly", "Sibnet", "Voe", "Moon", "Stape"), host, { it }) { host = it; Prefs.preferredHost = it }
            HorizontalDivider(Modifier.padding(top = 8.dp))
            Section("Application")
            Text(
                "Miru ${BuildConfig.VERSION_NAME}",
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    updateStatus = "Vérification…"
                    scope.launch {
                        try {
                            val r = AppUpdater.check()
                            if (r == null) updateStatus = "Tu as déjà la dernière version." else {
                                updateStatus = null
                                release = r
                            }
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (e: Exception) {
                            updateStatus = "Impossible de vérifier : ${errorText(e)}"
                        }
                    }
                },
                modifier = Modifier.padding(horizontal = 16.dp),
            ) { Text("Mettre à jour l'appli") }
            updateStatus?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
    }
    release?.let { AppUpdateDialog(it, onDismiss = { release = null }) }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
    )
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        Modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { o ->
            FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) })
        }
    }
}
