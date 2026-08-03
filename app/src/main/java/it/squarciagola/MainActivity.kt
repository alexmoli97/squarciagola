package it.squarciagola

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import it.squarciagola.model.PlaybackState
import it.squarciagola.render.KaraokeView
import it.squarciagola.ui.SquarciagolaTheme
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
        setContent {
            val accento by Engine.accento.collectAsStateWithLifecycle()
            SquarciagolaTheme(Color(accento)) { Root() }
        }
    }

    /**
     * L'ascolto parte qui e non in onCreate: al primo collegamento l'app Spotify deve
     * mostrare la richiesta di autorizzazione, e per farlo la finestra deve essere gia' in
     * primo piano. In onCreate il dialogo non comparirebbe e la connessione fallirebbe
     * chiedendo proprio quell'autorizzazione.
     *
     * App Remote non richiede l'accesso OAuth: anche senza sessione Web API l'app puo' gia'
     * sapere cosa sta suonando. La chiamata e' protetta contro il doppio avvio.
     */
    override fun onResume() {
        super.onResume()
        Engine.start(this)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(downloadDone) }
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAuthRedirect(intent)
    }

    /** Ritorno del browser dopo il consenso: nell'URL c'è il codice da scambiare. */
    private fun handleAuthRedirect(intent: Intent?) {
        val code = intent?.data?.takeIf { it.scheme == "it.squarciagola" }?.getQueryParameter("code")
            ?: return
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { Engine.auth.exchangeCode(code) }
            esitoLogin = if (ok) "Collegato a Spotify" else "Scambio del codice fallito"
            if (ok) PlaybackService.start(this@MainActivity)
        }
    }

    /** Messaggio dell'ultimo tentativo di accesso, mostrato in schermata. */
    private var esitoLogin by mutableStateOf<String?>(null)

    // --- struttura ------------------------------------------------------------------------

    @Composable
    private fun Root() {
        var karaokeAperto by remember { mutableStateOf(false) }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (karaokeAperto) Karaoke(onChiudi = { karaokeAperto = false })
            else Setup(onCanta = { karaokeAperto = true })
        }
    }

    @Composable
    private fun Karaoke(onChiudi: () -> Unit) {
        // Su televisore e in auto non c'e' un dito che raggiunge il pulsante: il tasto
        // Indietro deve chiudere il karaoke invece di uscire dall'app.
        BackHandler(onBack = onChiudi)
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                factory = { KaraokeView(it) },
                modifier = Modifier.fillMaxSize().safeDrawingPadding(),
            )
            TextButton(
                onClick = onChiudi,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .safeDrawingPadding()
                    .padding(8.dp)
                    .heightIn(min = 48.dp),
            ) { Text("Chiudi") }
        }
    }

    @Composable
    private fun Setup(onCanta: () -> Unit) {
        val playback by Engine.playback.collectAsStateWithLifecycle()
        var esito by remember { mutableStateOf<String?>(null) }
        var aggiornamento by remember { mutableStateOf<Release?>(null) }

        // Il servizio si avvia quando una delle due sorgenti ha attaccato: prima sarebbe una
        // notifica persistente per un ascolto che non sta avvenendo.
        val origine by Engine.origine.collectAsStateWithLifecycle()
        LaunchedEffect(origine) {
            if (origine.isNotEmpty()) PlaybackService.start(this@MainActivity)
        }

        // Controllo all'avvio, silenzioso: senza rete o senza novità non compare nulla.
        LaunchedEffect(Unit) {
            val trovato = withContext(Dispatchers.IO) { UpdateChecker.latest() }
            if (trovato != null && trovato.versionCode > BuildConfig.VERSION_CODE) {
                aggiornamento = trovato
            }
        }

        Box(Modifier.fillMaxSize()) {
            SfondoDelBrano()

            Column(
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp)
                    .padding(top = 24.dp, bottom = 40.dp),
            ) {
                Text(
                    "Squarciagola",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )

                Spacer(Modifier.height(24.dp))
                Palco(playback, onCanta)

                aggiornamento?.let {
                    Spacer(Modifier.height(26.dp))
                    AvvisoAggiornamento(it) { messaggio -> esito = messaggio }
                }

                Spacer(Modifier.height(46.dp))
                Text(
                    "Impostazioni",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(18.dp))

                Sezione("Spotify") { Connessione { messaggio -> esito = messaggio } }
                Spacer(Modifier.height(26.dp))

                Sezione("Sincronia") { Sincronia() }
                Spacer(Modifier.height(26.dp))

                Sezione("Manutenzione") { Manutenzione { messaggio -> esito = messaggio } }

                esitoLogin?.let {
                    Spacer(Modifier.height(20.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
                esito?.let {
                    Spacer(Modifier.height(20.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }

    /**
     * La copertina sfocata dietro alla schermata, la stessa del karaoke.
     *
     * Prima la home era nera e piatta mentre il karaoke era pieno di colore: due stanze della
     * stessa casa arredate da persone diverse. Ora il brano si sente anche prima di premere.
     */
    @Composable
    private fun SfondoDelBrano() {
        val copertina by Engine.artwork.collectAsStateWithLifecycle()
        val immagine = copertina ?: return
        if (immagine.isRecycled) return
        Canvas(Modifier.fillMaxSize()) {
            drawImage(
                image = immagine.asImageBitmap(),
                dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                filterQuality = FilterQuality.Medium,
            )
            // Stesso velo del karaoke: la copertina si intuisce, il testo resta leggibile.
            drawRect(Color(0xCC000000))
        }
    }

    /**
     * Il blocco che risponde alla domanda vera, e con il peso che quella domanda merita:
     * cosa sta suonando, e posso cantarlo adesso.
     */
    @Composable
    private fun Palco(playback: PlaybackState, onCanta: () -> Unit) {
        val track = playback.track
        val animazioni = animazioniAttive()

        // Il pulsante respira mentre la musica va: un solo movimento, legato a uno stato, non
        // un effetto sparso sulla schermata.
        val respiro = if (track != null && playback.isPlaying && animazioni) {
            val transizione = rememberInfiniteTransition(label = "respiro")
            transizione.animateFloat(
                initialValue = 1f,
                targetValue = 1.035f,
                animationSpec = infiniteRepeatable(
                    tween(1400, easing = LinearEasing),
                    RepeatMode.Reverse,
                ),
                label = "scala",
            ).value
        } else {
            1f
        }

        AnimatedContent(
            targetState = track,
            transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(260)) },
            label = "palco",
        ) { brano ->
            Column {
                if (brano == null) {
                    Text(
                        if (Engine.auth.isLoggedIn) "Silenzio in cabina" else "Non ancora collegata",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (Engine.auth.isLoggedIn) "Fai partire qualcosa su Spotify."
                        else "Collega Spotify qui sotto, poi torna su.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Engine.problem.collectAsStateWithLifecycle().value
                        ?.takeIf { Engine.auth.isLoggedIn }
                        ?.let {
                            Spacer(Modifier.height(10.dp))
                            Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Equalizzatore(playback.isPlaying)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            brano.artist,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        brano.title,
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        lineHeight = MaterialTheme.typography.displaySmall.fontSize * 1.1f,
                        // I titoli con dentro mezza scheda del disco esistono: oltre le tre
                        // righe spingerebbero l'azione principale fuori dalla prima schermata.
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Spacer(Modifier.height(26.dp))
        Button(
            onClick = onCanta,
            enabled = track != null,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .scale(respiro),
        ) {
            Text(
                if (track == null) "Canta" else "Canta a squarciagola",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }

    /** Rispetta l'impostazione di sistema per la rimozione delle animazioni. */
    @Composable
    private fun animazioniAttive(): Boolean {
        val context = LocalContext.current
        return remember {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) != 0f
        }
    }

    /**
     * Tre barrette che si muovono mentre la musica va e si posano quando è in pausa.
     * Comunica uno stato, non decora: da lontano si capisce se il polling sta ricevendo.
     */
    @Composable
    private fun Equalizzatore(inRiproduzione: Boolean) {
        val muoviti = inRiproduzione && animazioniAttive()
        val transizione = rememberInfiniteTransition(label = "equalizzatore")
        val altezze = listOf(620, 900, 740).mapIndexed { indice, durata ->
            if (!muoviti) {
                null
            } else {
                transizione.animateFloat(
                    initialValue = if (indice % 2 == 0) 0.35f else 0.85f,
                    targetValue = if (indice % 2 == 0) 1f else 0.3f,
                    animationSpec = infiniteRepeatable(
                        tween(durata, easing = LinearEasing),
                        RepeatMode.Reverse,
                    ),
                    label = "barra$indice",
                )
            }
        }
        val colore = if (inRiproduzione) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outline

        Canvas(Modifier.size(width = 22.dp, height = 30.dp)) {
            val larghezzaBarra = size.width / 5f
            altezze.forEachIndexed { indice, animata ->
                val frazione = animata?.value ?: 0.28f
                val altezza = size.height * frazione
                drawRoundRectBar(
                    x = indice * larghezzaBarra * 2f,
                    larghezza = larghezzaBarra,
                    altezza = altezza,
                    colore = colore,
                )
            }
        }
    }

    private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRoundRectBar(
        x: Float,
        larghezza: Float,
        altezza: Float,
        colore: Color,
    ) {
        drawRoundRect(
            color = colore,
            topLeft = androidx.compose.ui.geometry.Offset(x, size.height - altezza),
            size = androidx.compose.ui.geometry.Size(larghezza, altezza),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(larghezza / 2f),
        )
    }

    @Composable
    private fun Sezione(titolo: String, contenuto: @Composable () -> Unit) {
        Text(
            titolo,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(6.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(14.dp))
        contenuto()
    }

    @Composable
    private fun Connessione(onEsito: (String) -> Unit) {
        var clientId by remember { mutableStateOf(Engine.auth.clientId) }
        val collegata = Engine.auth.isLoggedIn

        Column {
            Text(
                if (collegata) "Collegata. Il brano in riproduzione arriva da qui."
                else "Serve una app registrata su developer.spotify.com con redirect " +
                    "it.squarciagola://auth.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!Engine.auth.clientIdFromBuild) {
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = clientId,
                    onValueChange = { clientId = it; Engine.auth.clientId = it },
                    label = { Text("Client ID") },
                    supportingText = { Text("Messo in local.properties, questo campo sparisce.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(
                    onClick = {
                        val url = Engine.auth.buildAuthorizeUrl()
                        if (url == null) {
                            onEsito("Manca il Client ID")
                        } else {
                            CustomTabsIntent.Builder().build()
                                .launchUrl(this@MainActivity, Uri.parse(url))
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(if (collegata) "Riaccedi" else "Collega Spotify") }

                if (collegata) {
                    TextButton(
                        onClick = {
                            Engine.auth.logout()
                            onEsito("Sessione rimossa")
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Scollega") }
                }
            }
        }
    }

    @Composable
    private fun Sincronia() {
        var offset by remember { mutableStateOf(Engine.offsetMs) }

        Column {
            Text(
                "La latenza di rete è già compensata da sola. Questa manopola copre il " +
                    "ritardo audio dell'impianto, che nessuna API espone: si tara una volta " +
                    "per uscita e torna da sola al collegamento dopo.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                PassoOffset("−50") { Engine.offsetMs -= 50; offset = Engine.offsetMs }
                Column(
                    Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "${if (offset > 0) "+" else ""}$offset ms",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        Engine.outputName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
                PassoOffset("+50") { Engine.offsetMs += 50; offset = Engine.offsetMs }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                "Alzalo se il testo arriva in ritardo rispetto a quello che senti.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (offset != 0L) {
                TextButton(
                    onClick = { Engine.offsetMs = 0; offset = 0 },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Azzera") }
            }
        }
    }

    @Composable
    private fun PassoOffset(etichetta: String, onClick: () -> Unit) {
        FilledTonalButton(
            onClick = onClick,
            modifier = Modifier.size(width = 74.dp, height = 52.dp),
            contentPadding = ButtonDefaults.TextButtonContentPadding,
        ) { Text(etichetta, style = MaterialTheme.typography.titleMedium) }
    }

    @Composable
    private fun Manutenzione(onEsito: (String) -> Unit) {
        val sorgente by Engine.origine.collectAsStateWithLifecycle()
        Column {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(
                    onClick = {
                        PlaybackService.start(this@MainActivity)
                        onEsito("Ascolto avviato")
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Avvia ascolto") }
                TextButton(
                    onClick = {
                        PlaybackService.stop(this@MainActivity)
                        onEsito("Ascolto fermato")
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Ferma") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(
                    onClick = {
                        Engine.clearLyricsCache()
                        onEsito("Cache dei testi svuotata")
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Svuota cache") }
                TextButton(
                    onClick = {
                        onEsito("Controllo aggiornamenti")
                        lifecycleScope.launch {
                            val trovato = withContext(Dispatchers.IO) { UpdateChecker.latest() }
                            onEsito(
                                when {
                                    trovato == null -> "GitHub non risponde"
                                    trovato.versionCode > BuildConfig.VERSION_CODE ->
                                        "C'è la ${trovato.versionName}, riapri la schermata"

                                    else -> "Già all'ultima versione"
                                }
                            )
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Cerca aggiornamenti") }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Versione ${BuildConfig.VERSION_NAME} · testi da LRCLIB" +
                    if (sorgente.isEmpty()) "" else " · riproduzione da $sorgente",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    @Composable
    private fun AvvisoAggiornamento(release: Release, onEsito: (String) -> Unit) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(18.dp)) {
                Text(
                    "Disponibile la ${release.versionName}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                if (release.notes.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        release.notes.take(300),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = {
                        if (!Updater.canInstall(this@MainActivity)) {
                            onEsito("Concedi il permesso di installare, poi ripremi Aggiorna")
                            Updater.openInstallPermissionSettings(this@MainActivity)
                        } else {
                            downloadId = Updater.download(this@MainActivity, release)
                            onEsito("Scarico. L'installazione parte da sola alla fine.")
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Aggiorna") }
            }
        }
    }
}
