package it.squarciagola.playback

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.protocol.types.Image
import com.spotify.protocol.types.PlayerState as SpotifyPlayerState
import it.squarciagola.model.PlaybackState
import it.squarciagola.model.TrackMeta
import it.squarciagola.render.AlbumArt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Stato di riproduzione letto direttamente dall'app Spotify installata, via App Remote.
 *
 * È la sorgente preferita dove esiste, per un motivo che non è di comodità: gli aggiornamenti
 * arrivano spinti al cambiamento e in locale, quindi non c'è nessuna latenza di rete da
 * compensare e nessun polling da tarare. La posizione che riceviamo è quella vera nel momento
 * in cui la riceviamo.
 *
 * Non funziona ovunque: serve l'app Spotify sullo stesso dispositivo e un account Premium.
 * Sui televisori non c'è, ed è la ragione per cui la Web API resta come ripiego invece di
 * essere stata sostituita.
 */
class AppRemoteSource(
    private val clientId: () -> String,
    private val scope: CoroutineScope,
) {

    private var remote: SpotifyAppRemote? = null
    private var ultimoBrano: String? = null

    private val _state = MutableStateFlow(PlaybackState.IDLE)
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _artwork = MutableStateFlow<Bitmap?>(null)
    val artwork: StateFlow<Bitmap?> = _artwork.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /**
     * Tenta la connessione. L'esito non è un errore da mostrare: su un televisore, o senza
     * l'app Spotify, il fallimento è la normalità e chi chiama passa alla Web API.
     */
    fun connect(context: Context, onEsito: (Boolean) -> Unit) {
        val id = clientId()
        if (id.isEmpty()) {
            onEsito(false)
            return
        }
        if (remote?.isConnected == true) {
            onEsito(true)
            return
        }

        val params = ConnectionParams.Builder(id)
            .setRedirectUri(REDIRECT_URI)
            .showAuthView(true)
            .build()

        SpotifyAppRemote.connect(context, params, object : Connector.ConnectionListener {
            override fun onConnected(appRemote: SpotifyAppRemote) {
                remote = appRemote
                _connected.value = true
                sottoscrivi(appRemote)
                onEsito(true)
            }

            override fun onFailure(error: Throwable) {
                android.util.Log.i("Squarciagola", "App Remote non collegato: ${error.message}")
                remote = null
                _connected.value = false
                onEsito(false)
            }
        })
    }

    fun disconnect() {
        remote?.let { SpotifyAppRemote.disconnect(it) }
        remote = null
        _connected.value = false
        _state.value = PlaybackState.IDLE
        _artwork.value = null
        ultimoBrano = null
    }

    private fun sottoscrivi(appRemote: SpotifyAppRemote) {
        appRemote.playerApi.subscribeToPlayerState().setEventCallback { player ->
            aggiorna(appRemote, player)
        }
    }

    private fun aggiorna(appRemote: SpotifyAppRemote, player: SpotifyPlayerState) {
        val track = player.track
        if (track == null) {
            _state.value = PlaybackState.IDLE
            _artwork.value = null
            ultimoBrano = null
            return
        }

        val id = track.uri.substringAfterLast(':')
        _state.value = PlaybackState(
            track = TrackMeta(
                id = id,
                title = track.name.orEmpty(),
                artist = track.artists?.mapNotNull { it.name }?.joinToString(", ")
                    ?: track.artist?.name.orEmpty(),
                album = track.album?.name.orEmpty(),
                durationMs = track.duration,
            ),
            progressMs = player.playbackPosition,
            isPlaying = !player.isPaused,
            // Nessuna correzione da applicare: il dato non ha attraversato la rete.
            sampledAtElapsedRealtime = SystemClock.elapsedRealtime(),
        )

        if (id != ultimoBrano) {
            ultimoBrano = id
            caricaCopertina(appRemote, track.imageUri, id)
        }
    }

    /** La copertina arriva già come Bitmap dall'app Spotify: nessuna richiesta HTTP. */
    private fun caricaCopertina(appRemote: SpotifyAppRemote, uri: com.spotify.protocol.types.ImageUri?, id: String) {
        if (uri == null) {
            _artwork.value = null
            return
        }
        appRemote.imagesApi.getImage(uri, Image.Dimension.LARGE).setResultCallback { bitmap ->
            if (bitmap == null) return@setResultCallback
            // La sfocatura è lavoro di CPU: fuori dal thread su cui l'SDK consegna il risultato.
            scope.launch(Dispatchers.Default) {
                _artwork.value = AlbumArt.blurred(id, bitmap)
            }
        }
    }

    private companion object {
        const val REDIRECT_URI = "it.squarciagola://auth"
    }
}
