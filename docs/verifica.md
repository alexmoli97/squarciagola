# Verifiche prima di una release

Due livelli: quello che decide una macchina e quello che serve un telefono. Il primo è
automatico e obbligatorio, il secondo è una lista da percorrere a mano.

## Automatiche

```bash
./check.sh
```

Esegue test unitari, lint Android e compilazione. Fallisce, oltre che sull'ovvio, anche se la
suite non esegue nessun test: una suite vuota esce con successo e sembrerebbe tutto a posto.

Copre 27 casi su quattro punti, scelti perché lì un difetto resta silenzioso invece di
manifestarsi come errore:

| Cosa | Perché è testato |
|---|---|
| `PositionClock` | Un errore qui non genera nulla di visibile, solo testo fuori sincrono |
| `LrcParser` | Timestamp malformati e millesimi scambiati per centesimi passano inosservati |
| `TextWrapper` | Una parola più larga della riga mandava in ciclo infinito il taglio |
| `UpdateChecker` | Un tag interpretato male significa aggiornamenti che non arrivano mai |

`release.sh` lo invoca da solo: non serve lanciarlo prima a mano.

Quello che le automatiche **non** possono dire: se a schermo si vede qualcosa, se Android Auto
accetta l'app, se il giro OAuth si chiude, se l'aggiornamento si installa. Tutto questo esce
verde anche con l'app completamente rotta.

## Manuali sul telefono

Servono un telefono collegato e Spotify in riproduzione. Con `adb` disponibile:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n it.squarciagola/.MainActivity
```

Su WSL l'adb di Linux non vede l'USB. Si usa quello di Windows, che il telefono lo vede
nativamente: `/mnt/c/platform-tools/adb.exe`. La strada con usbipd è stata provata e si rompe
a ogni operazione, non vale la pena insistere.

Per una schermata:

```bash
adb shell screencap -p /sdcard/s.png && adb pull /sdcard/s.png .
```

### 1. Primo avvio senza sessione

- [ ] La schermata si apre senza chiudersi da sola
- [ ] Compare "Non ancora collegata" e il pulsante **Canta** è spento
- [ ] Se il Client ID non è nel build, il campo è visibile; se c'è, il campo non compare

### 2. Accesso a Spotify

- [ ] **Collega Spotify** apre il browser sulla pagina di consenso
- [ ] Dopo il consenso si torna nell'app da soli, senza passaggi manuali
- [ ] La sezione Spotify passa a "Collegata"
- [ ] L'ascolto parte da solo: non serve premere **Avvia ascolto**

### 3. Rilevamento del brano

Con qualcosa in riproduzione su Spotify:

- [ ] Entro pochi secondi compaiono titolo e artista
- [ ] Le tre barrette si muovono; messa la pausa, si posano
- [ ] **Canta** diventa attivo

### 4. Karaoke sul telefono

- [ ] Il testo scorre e la riga corrente è evidenziata con l'alone verde
- [ ] Le righe lunghe vanno a capo, non vengono troncate con i puntini
- [ ] La barra di avanzamento e il tempo salgono
- [ ] In basso a destra compare la sorgente del testo
- [ ] **Chiudi** è raggiungibile e non si sovrappone ad altro
- [ ] Con un brano senza testo compare il messaggio, non una schermata vuota

### 5. Sincronia

- [ ] I passi da 50 ms muovono il valore e il testo si sposta di conseguenza
- [ ] Sotto il valore c'è il nome dell'uscita audio in uso
- [ ] Collegando il Bluetooth, il nome cambia e il valore diventa quello di quell'uscita
- [ ] Riavviando l'app il valore è quello di prima

### 6. Android Auto

Serve la macchina oppure il Desktop Head Unit dell'SDK. Prerequisito: **Sorgenti sconosciute**
attivo nelle impostazioni sviluppatore di Android Auto.

- [ ] Squarciagola compare nel launcher di Android Auto
- [ ] Aprendola parte il karaoke, anche se l'app sul telefono non era stata aperta
- [ ] Il testo scorre e resta dentro l'area visibile, senza finire sotto i controlli di sistema
- [ ] I due pulsanti da 100 ms cambiano la sincronia sul posto
- [ ] Passando ad un'altra app in auto e tornando indietro, il disegno riprende

### 7. Aggiornamento

Va provato con una versione installata **inferiore** a quella dell'ultima release, altrimenti
non succede niente ed è corretto così.

- [ ] All'apertura compare la scheda con nome versione e note
- [ ] **Aggiorna** senza il permesso di installare porta nella schermata di sistema giusta
- [ ] Concesso il permesso, il download parte e si vede la notifica di avanzamento
- [ ] Al termine parte l'installer e l'app si aggiorna mantenendo la sessione Spotify

### 8. Casi limite

- [ ] In aereo o senza rete: resta l'ultimo testo, non si svuota lo schermo
- [ ] Spotify che suona su un altro dispositivo: messaggio di attesa
- [ ] Schermo ruotato: il testo si ridimensiona e continua ad andare a capo

## Se qualcosa non torna

I messaggi dell'app sono distinti apposta e dicono a che punto si è fermata la catena:

| Messaggio | Dove guardare |
|---|---|
| Configura il Client ID | Manca in `local.properties` e nel campo |
| Accedi a Spotify dal telefono | Nessuna sessione salvata |
| Sessione scaduta | Il refresh token non è più valido, rifare l'accesso |
| Nessuna connessione | Rete assente o API di Spotify irraggiungibile |
| Nessun brano in riproduzione | Spotify fermo, oppure attivo su un altro dispositivo |
| Di questo brano non si trova il testo | LRCLIB non ha quel brano |

Log in tempo reale:

```bash
adb logcat --pid=$(adb shell pidof -s it.squarciagola)
```
