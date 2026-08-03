# Squarciagola

Karaoke dei brani riprodotti da Spotify, sullo schermo di Android Auto e su quello del telefono.
Uso personale, installazione manuale, nessuna pubblicazione su store.

## Cosa fa

Legge dalla Web API di Spotify quale brano sta suonando e a che punto e', recupera il testo
sincronizzato e lo fa scorrere evidenziando la riga in corso. Lo stesso motore di disegno
alimenta sia la Surface di Android Auto sia la vista sul telefono.

## Cosa non fa, e perche'

**Nessun widget sulla home di Android Auto.** La home mostra card controllate da Google e non
esiste API per aggiungerne di terze parti. L'app compare come icona nel launcher di Android Auto.

**Non finira' mai sul Play Store.** Per disegnare liberamente serve una Surface, che l'host
concede solo alle app di categoria navigazione. Squarciagola si dichiara tale pur non essendo
un navigatore: funziona in sideload, non supererebbe una review.

**I testi di Spotify sono opzionali e fragili.** Vedi sotto.

## Compilazione

Servono JDK 17 o superiore e l'SDK Android (piattaforma 35).

```bash
./gradlew :app:assembleDebug     # APK in app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest # test di PositionClock e del parser LRC
```

Se l'SDK non e' nella posizione predefinita, indicalo in `local.properties` con `sdk.dir=...`.

## Installazione

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Poi, per vederla in auto, in Android Auto sul telefono: tocca dieci volte "Versione" per
sbloccare le opzioni sviluppatore, quindi attiva **Sorgenti sconosciute**. L'app compare nel
launcher al collegamento successivo. Per provarla senza salire in macchina si usa il Desktop
Head Unit dell'SDK.

## Configurazione

### Spotify Web API (obbligatoria)

Serve per sapere cosa sta suonando. La registrazione dell'app non e' aggirabile: senza un
Client ID non esiste OAuth.

1. Su [developer.spotify.com](https://developer.spotify.com/dashboard) crea un'app.
2. Aggiungi come Redirect URI esattamente `it.squarciagola://auth`.
3. Copia il Client ID in `local.properties`:

```properties
spotify.clientId=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

Ricompila e il campo sparisce dalla schermata: resta solo **Accedi a Spotify**. Il Client ID
in PKCE non e' un segreto, quindi sta tranquillamente nel build; `local.properties` e' comunque
fuori dal repository.

Chi installa l'APK senza ricompilare trova il campo e puo' incollarlo a mano.

Gli scope richiesti sono `user-read-playback-state` e `user-read-currently-playing`.
Non serve il client secret: l'autenticazione usa PKCE.

### Testi

Di base i testi arrivano da [LRCLIB](https://lrclib.net): pubblico, senza autenticazione,
sincronizzato riga per riga. Non c'e' nulla da configurare.

L'interruttore **Usa i testi di Spotify** aggiunge come sorgente primaria l'endpoint interno
`color-lyrics` del client Spotify, con LRCLIB che resta come riserva automatica.
Prima di attivarlo, sappi che:

- l'endpoint non fa parte della Web API pubblica e usarlo viola i Termini di Servizio di Spotify
- richiede il cookie di sessione `sp_dc` del tuo account salvato dentro l'app
- dal marzo 2025 la richiesta del token del web player richiede un codice TOTP, il cui segreto
  Spotify ruota periodicamente: a ogni rotazione la sorgente smette di funzionare
- comporta un rischio, per quanto basso, di provvedimenti sull'account

Quando si rompe non resti a bocca asciutta: la sorgente restituisce "non disponibile" e il
testo continua ad arrivare da LRCLIB. Te ne accorgi dal fatto che i testi tornano a essere
quelli di LRCLIB, non da un errore a schermo.

Per configurarla servono due valori presi dal web player. Vale la pena sapere cosa sono, perche'
non sono impostazioni ma credenziali e contromisure:

- **`sp_dc`** e' il cookie di sessione del tuo account sul web player, nei cookie di
  `open.spotify.com` con la sessione aperta. Equivale a essere loggato come te: chi ce l'ha puo'
  agire come il tuo account finche' non scade. Per questo l'app lo tiene in
  EncryptedSharedPreferences e non fra le preferenze normali.
- **segreto TOTP**, in esadecimale. Da marzo 2025 la richiesta del token del web player deve
  portare un codice a sei cifre calcolato dall'ora corrente piu' un segreto incorporato nel
  bundle JavaScript del player: e' una misura anti-automazione, serve a dimostrare che chi
  chiama sta eseguendo il codice vero del web player. I progetti open source che seguono
  l'endpoint pubblicano la coppia segreto/versione a ogni rotazione. La versione attesa dal
  codice sta nella costante `TOTP_VERSION` in `SpotifyLyricsSource.kt` e va tenuta allineata al
  segreto incollato nelle impostazioni: se divergono, il token viene rifiutato.

Entrambi esistono solo per fingersi il web player ufficiale, ed e' esattamente il motivo per
cui questa strada viola i ToS ed e' fragile per costruzione.

### Sincronia

Il disallineamento ha due cause, e solo una e' misurabile.

**Latenza di rete: automatica.** La posizione riportata da Spotify descrive un istante gia'
passato quando la risposta arriva. L'app misura il round-trip di ogni richiesta e colloca
l'istante di campionamento mezzo round-trip indietro. Nessuna taratura, si adatta da solo al
variare della connessione.

**Ritardo audio dell'impianto: una taratura per dispositivo.** E' a valle di Spotify, in un
percorso audio che l'app non controlla, e nessuna API lo espone: non esiste modo software di
misurarlo. Quello che l'app fa e' non farlo tarare due volte, tenendo un valore separato per
ogni uscita audio (impianto dell'auto, cuffie, altoparlante del telefono). Calibri una volta e
al collegamento successivo il valore torna da solo.

Si regola dal telefono a passi di 50 ms e in auto con i due pulsanti sulla barra dei comandi, a
passi di 100 ms. Positivo se il testo va in ritardo rispetto a quello che senti. La scheda sul
telefono mostra a quale uscita si riferisce il valore che stai modificando.

### Quale sorgente sta lavorando

In alto a destra nel karaoke c'e' scritto da dove arriva il testo mostrato. Serve ad accorgersi
quando la sorgente Spotify smette di rispondere e sta lavorando LRCLIB di riserva: il ripiego e'
silenzioso per non interrompere l'ascolto, e senza questa indicazione non sarebbe visibile.

Cambiando l'interruttore della sorgente la cache dei testi viene svuotata, altrimenti i brani
gia' visti resterebbero con il testo della sorgente precedente.

## Aggiornamenti senza store

L'app si aggiorna da sola dalle release di GitHub. Non c'e' server, non c'e' manifest da
mantenere: pubblicare una versione significa creare una release con l'APK allegato.

**Regola unica**: il tag della release e' `v<versionCode>`, e quel numero deve coincidere con
il `versionCode` in `app/build.gradle.kts`. Release `v3` vuol dire `versionCode = 3`.

Il repository di riferimento e' la costante `REPOSITORY` in `update/UpdateChecker.kt`.
Va messa sul tuo repo prima della prima release, altrimenti il controllo cerca un indirizzo
che non esiste.

### Pubblicare una versione

```bash
# 1. alza versionCode e versionName in app/build.gradle.kts
./gradlew :app:assembleDebug
gh release create v3 app/build/outputs/apk/debug/app-debug.apk --title "0.3" --notes "Cosa cambia"
```

### Cosa succede sul telefono

All'apertura l'app interroga GitHub in silenzio: se non c'e' rete o non c'e' niente di nuovo
non compare nulla. Quando trova una versione con numero piu' alto mostra una scheda con le
note di rilascio e il pulsante **Aggiorna**. C'e' anche **Controlla aggiornamenti** per
forzare il controllo a mano.

Premendo Aggiorna il download passa da DownloadManager, con la sua notifica di avanzamento, e
al termine parte l'installer di sistema. La conferma finale la chiede Android: un'app non
puo' sostituirsi da sola in silenzio.

La prima volta serve concedere a Squarciagola il permesso di installare app ("Installa app
sconosciute"). Il pulsante ci porta direttamente nella schermata giusta; concesso il permesso,
si ripreme Aggiorna.

Le firme devono combaciare: se cambi il keystore fra una versione e l'altra, Android rifiuta
l'aggiornamento e va disinstallata e reinstallata. Restando sulla build di debug il problema
non si pone.

## Come e' fatto

| File | Ruolo |
|---|---|
| `playback/PositionClock.kt` | Interpola la posizione tra due poll. Logica pura, testata |
| `playback/PlaybackPoller.kt` | Poll di `/v1/me/player` ogni 4 secondi |
| `lyrics/LrcParser.kt` | Parser LRC e ricerca binaria della riga attiva. Testato |
| `lyrics/LrcLibSource.kt` | Sorgente pubblica, con ricerca di ripiego sui metadati |
| `lyrics/SpotifyLyricsSource.kt` | Sorgente interna Spotify, TOTP e cookie di sessione |
| `lyrics/LyricsRepository.kt` | Ordine delle sorgenti e cache su disco, esiti negativi inclusi |
| `render/KaraokeRenderer.kt` | Tutto il disegno. Non conosce ne' l'auto ne' Compose |
| `render/TextWrapper.kt` | Righe lunghe mandate a capo. Testato senza framework grafico |
| `update/UpdateChecker.kt` | Legge l'ultima release da GitHub. Testato |
| `update/Updater.kt` | Download e avvio dell'installer di sistema |
| `render/KaraokeView.kt` | Contenitore per il telefono |
| `car/CarSurfaceRenderer.kt` | Contenitore per la Surface dell'auto, 30 fotogrammi al secondo |
| `Engine.kt` | Stato condiviso tra i due schermi |
| `PlaybackService.kt` | Foreground service, tiene vivo il polling fuori dal primo piano |

## Stato della verifica

Test unitari eseguiti e verdi: 27 casi su `PositionClock`, parser LRC, `TextWrapper` e
`UpdateChecker`.

Il resto (rendering a schermo, integrazione con Android Auto, flusso OAuth completo, download e
installazione dell'aggiornamento) non e' verificabile senza dispositivo: va provato a mano, con
il Desktop Head Unit o in macchina.
