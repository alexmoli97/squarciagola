package it.squarciagola.playback

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * Identifica su cosa sta uscendo l'audio, per tenere una calibrazione separata per impianto.
 *
 * Il ritardo audio non e' misurabile dall'app: sta a valle di Spotify, in un percorso che non
 * controlliamo. L'unica cosa sensata e' non farlo tarare due volte. La chiave e' il nome del
 * dispositivo, cosi' l'impianto della macchina e le cuffie mantengono valori distinti e si
 * riapplicano da soli al collegamento.
 */
object AudioOutput {

    /** Nome leggibile dell'uscita attiva, oppure "telefono" se non c'e' nulla di collegato. */
    fun currentName(context: Context): String {
        val manager = context.getSystemService(AudioManager::class.java) ?: return DEFAULT
        val outputs = runCatching { manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }
            .getOrNull() ?: return DEFAULT

        // Ordine di preferenza: quello che un'auto usa davvero viene prima.
        val preferred = outputs.firstOrNull { it.type in EXTERNAL_TYPES } ?: return DEFAULT
        val name = preferred.productName?.toString()?.trim().orEmpty()
        return if (name.isEmpty()) DEFAULT else name
    }

    const val DEFAULT = "telefono"

    private val EXTERNAL_TYPES = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    )
}
