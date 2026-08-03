package it.squarciagola

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import it.squarciagola.render.KaraokeView
import it.squarciagola.update.Release
import it.squarciagola.update.UpdateChecker
import it.squarciagola.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    /** Id del download in corso, per sapere quale notifica di completamento riguarda noi. */
    private var downloadId: Long = -1L

    private val downloadDone = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val done = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (done != -1L && done == downloadId) Updater.install(this@MainActivity, done)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Engine.init(this)
        handleAuthRedirect(intent)
        ContextCompat.registerReceiver(
            this,
            downloadDone,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED,
        )
        setContent { MaterialTheme { Root() } }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(downloadDone) }
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAuthRedirect(intent)
    }

    /** Ritorno del browser dopo il consenso: nell'URL c'e' il codice da scambiare. */
    private fun handleAuthRedirect(intent: Intent?) {
        val code = intent?.data?.takeIf { it.scheme == "it.squarciagola" }?.getQueryParameter("code")
            ?: return
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { Engine.auth.exchangeCode(code) }
            if (ok) PlaybackService.start(this@MainActivity)
        }
    }

    // --- interfaccia ----------------------------------------------------------------------

    @Composable
    private fun Root() {
        var karaokeAperto by remember { mutableStateOf(false) }
        if (karaokeAperto) {
            Box(Modifier.fillMaxSize()) {
                // Anche il karaoke rispetta gli inserti: senza, titolo e barra finirebbero
                // sotto l'orologio di sistema e sotto la barra dei gesti.
                AndroidView(
                    factory = { KaraokeView(it) },
                    modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                )
                OutlinedButton(
                    onClick = { karaokeAperto = false },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .safeDrawingPadding()
                        .padding(12.dp),
                ) { Text("Chiudi") }
            }
        } else {
            Setup(onApriKaraoke = { karaokeAperto = true })
        }
    }

    @Composable
    private fun Setup(onApriKaraoke: () -> Unit) {
        var clientId by remember { mutableStateOf(Engine.auth.clientId) }
        var spDc by remember { mutableStateOf(Engine.auth.spDc) }
        var totp by remember { mutableStateOf(Engine.auth.totpSecretHex) }
        var usaSpotify by remember { mutableStateOf(Engine.useSpotifyLyrics) }
        var offset by remember { mutableStateOf(Engine.offsetMs) }
        var esito by remember { mutableStateOf("") }
        var aggiornamento by remember { mutableStateOf<Release?>(null) }
        var statoRicerca by remember { mutableStateOf("") }

        // Controllo all'avvio, silenzioso: se non c'e' rete o non c'e' nulla di nuovo,
        // la scheda non compare e non si vede alcun errore.
        LaunchedEffect(Unit) {
            val trovato = withContext(Dispatchers.IO) { UpdateChecker.latest() }
            if (trovato != null && trovato.versionCode > BuildConfig.VERSION_CODE) {
                aggiornamento = trovato
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Squarciagola", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Versione ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodySmall,
            )

            aggiornamento?.let { SchedaAggiornamento(it) { messaggio -> statoRicerca = messaggio } }

            OutlinedTextField(
                value = clientId,
                onValueChange = { clientId = it; Engine.auth.clientId = it },
                label = { Text("Client ID Spotify") },
                supportingText = { Text("Da developer.spotify.com, con redirect URI it.squarciagola://auth") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = {
                    val url = Engine.auth.buildAuthorizeUrl()
                    if (url == null) {
                        esito = "Manca il Client ID"
                    } else {
                        CustomTabsIntent.Builder().build().launchUrl(this@MainActivity, Uri.parse(url))
                    }
                }) { Text(if (Engine.auth.isLoggedIn) "Riaccedi" else "Accedi a Spotify") }

                OutlinedButton(onClick = {
                    Engine.auth.logout()
                    esito = "Sessione rimossa"
                }) { Text("Esci") }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Usa i testi di Spotify")
                        Switch(checked = usaSpotify, onCheckedChange = {
                            usaSpotify = it
                            Engine.useSpotifyLyrics = it
                        })
                    }
                    Text(
                        "Endpoint interno, contro i Termini di Servizio di Spotify. Si rompe a ogni " +
                            "rotazione del segreto del web player: quando succede, il testo torna " +
                            "automaticamente da LRCLIB. Spento, si usa solo LRCLIB.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (usaSpotify) {
                        OutlinedTextField(
                            value = spDc,
                            onValueChange = { spDc = it; Engine.auth.spDc = it },
                            label = { Text("Cookie sp_dc") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = totp,
                            onValueChange = { totp = it; Engine.auth.totpSecretHex = it },
                            label = { Text("Segreto TOTP (esadecimale)") },
                            supportingText = { Text("Vedi README per come ricavarlo e quando aggiornarlo") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Sincronia: $offset ms")
                    Text(
                        "Positivo se il testo va in ritardo rispetto all'audio. Il Bluetooth " +
                            "introduce un ritardo che cambia da impianto a impianto.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { Engine.offsetMs -= 50; offset = Engine.offsetMs }) {
                            Text("-50")
                        }
                        OutlinedButton(onClick = { Engine.offsetMs += 50; offset = Engine.offsetMs }) {
                            Text("+50")
                        }
                        OutlinedButton(onClick = { Engine.offsetMs = 0; offset = 0 }) { Text("Azzera") }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = {
                    PlaybackService.start(this@MainActivity)
                    esito = "Servizio avviato"
                }) { Text("Avvia") }
                OutlinedButton(onClick = {
                    PlaybackService.stop(this@MainActivity)
                    esito = "Servizio fermato"
                }) { Text("Ferma") }
                OutlinedButton(onClick = {
                    Engine.clearLyricsCache()
                    esito = "Cache svuotata"
                }) { Text("Svuota cache") }
            }

            Button(onClick = onApriKaraoke, modifier = Modifier.fillMaxWidth()) {
                Text("Apri il karaoke")
            }

            OutlinedButton(
                onClick = {
                    statoRicerca = "Controllo in corso"
                    lifecycleScope.launch {
                        val trovato = withContext(Dispatchers.IO) { UpdateChecker.latest() }
                        statoRicerca = when {
                            trovato == null -> "Impossibile contattare GitHub"
                            trovato.versionCode > BuildConfig.VERSION_CODE -> {
                                aggiornamento = trovato
                                "Disponibile la ${trovato.versionName}"
                            }

                            else -> "Gia' aggiornata"
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Controlla aggiornamenti") }

            if (statoRicerca.isNotEmpty()) {
                Text(statoRicerca, style = MaterialTheme.typography.bodySmall)
            }
            if (esito.isNotEmpty()) Text(esito, style = MaterialTheme.typography.bodySmall)
        }
    }

    @Composable
    private fun SchedaAggiornamento(release: Release, onStato: (String) -> Unit) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Aggiornamento disponibile: ${release.versionName}")
                if (release.notes.isNotBlank()) {
                    Text(release.notes.take(400), style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = {
                    if (!Updater.canInstall(this@MainActivity)) {
                        onStato("Concedi il permesso di installare, poi ripremi Aggiorna")
                        Updater.openInstallPermissionSettings(this@MainActivity)
                    } else {
                        downloadId = Updater.download(this@MainActivity, release)
                        onStato("Download avviato, l'installazione parte da sola alla fine")
                    }
                }) { Text("Aggiorna") }
            }
        }
    }
}
