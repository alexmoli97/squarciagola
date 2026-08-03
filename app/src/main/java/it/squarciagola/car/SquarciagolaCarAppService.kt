package it.squarciagola.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import it.squarciagola.PlaybackService

/**
 * Punto di ingresso su Android Auto.
 *
 * L'app si dichiara di categoria navigazione perche' e' l'unica che concede una Surface su
 * cui disegnare liberamente: nessun template standard sa mostrare testo che scorre a tempo.
 * E' una scelta consapevole e definitiva, ed e' anche il motivo per cui questa app resta
 * un sideload e non puo' finire sul Play Store.
 */
class SquarciagolaCarAppService : CarAppService() {

    /**
     * Solo installazione manuale su un dispositivo personale, quindi qualunque host va bene.
     * Un'app distribuita dovrebbe usare HostValidator con le firme note, altrimenti si
     * accettano connessioni da host arbitrari.
     */
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen {
            // Avvia il polling anche quando l'app sul telefono non e' mai stata aperta.
            PlaybackService.start(carContext)
            return KaraokeScreen(carContext)
        }
    }
}
