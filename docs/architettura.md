# Architettura

Documentazione tecnica di Squarciagola. Il README spiega come usarla e come configurarla;
questo documento spiega com'è fatta dentro e perché le scelte sono quelle.

## Il problema

Sapere quale brano sta suonando su Spotify, trovarne il testo sincronizzato, e farlo scorrere
a tempo su due schermi molto diversi: quello dell'auto (largo, basso, guardato di sbieco) e
quello del telefono (stretto, alto, guardato da fermi).

Nessuna delle tre cose è offerta da un'API sola, ed è questo a dare la forma al progetto.

## Vincoli che hanno deciso la forma

Non sono preferenze. Sono limiti verificati, e ogni scelta discutibile del progetto discende
da uno di questi.

### Su Android Auto le app non disegnano

Le app di Android Auto vivono dentro template fissi (lista, pane, griglia, messaggio). Nessuno
di questi sa mostrare testo che scorre a tempo con una riga evidenziata. L'unica superficie di
disegno libera è quella esposta da `SurfaceCallback`, concessa solo alle app che dichiarano
`androidx.car.app.category.NAVIGATION`.

Conseguenza: Squarciagola si dichiara app di navigazione pur non essendo un navigatore. È il
prezzo per avere un karaoke vero invece di un elenco che si aggiorna a scatti, ed è anche il
motivo per cui l'app resta un sideload. Non è debito tecnico da ripagare, è una condizione
permanente.

### Non esiste il widget nella home dell'auto

La home di Android Auto mostra card controllate da Google. Non c'è API per aggiungerne.
L'app compare come icona nel launcher, e basta.

### Spotify non dà i testi

Nessun endpoint pubblico, nessuno scope OAuth. Esiste un endpoint interno raggiungibile
fingendosi il web player: richiede il cookie di sessione dell'account dentro l'app e un codice
TOTP il cui segreto Spotify ruota periodicamente, e usarlo viola i Termini di Servizio.

È stato implementato, provato e poi rimosso: costava due credenziali e una rincorsa continua
per ottenere la stessa granularità di sincronizzazione (per riga) che LRCLIB offre senza
autenticazione. La storia sta nei commit, se un giorno servisse riprenderla.

### La posizione nel brano arriva vecchia

`/v1/me/player` restituisce `progress_ms`, ma quel valore descrive un istante già passato
quando la risposta arriva. Interrogare più spesso non aiuta: avvicina il rate limit senza
migliorare la sincronia. La soluzione è ricostruire la posizione localmente fra un poll e
l'altro.

## Struttura

Modulo Android singolo, processo unico. Kotlin, Compose per il telefono, `androidx.car.app`
per l'auto (la Car App Library esiste solo per Android nativo: non c'era scelta di stack).

```
it.squarciagola
├── Engine.kt                    stato condiviso fra i due schermi
├── MainActivity.kt              schermata di setup e karaoke sul telefono
├── PlaybackService.kt           foreground service, tiene vivo il polling
├── SquarciagolaApp.kt           inizializzazione
├── auth/SpotifyAuth.kt          OAuth PKCE, token cifrati
├── car/
│   ├── SquarciagolaCarAppService.kt   ingresso su Android Auto
│   ├── KaraokeScreen.kt               template e comandi
│   └── CarSurfaceRenderer.kt          ciclo di disegno sulla Surface
├── lyrics/
│   ├── LrcLibSource.kt          recupero testi da lrclib.net
│   ├── LrcParser.kt             parser LRC e ricerca della riga attiva
│   └── LyricsRepository.kt      cache su disco
├── model/Models.kt              tipi di dominio
├── net/Http.kt                  client HTTP minimo
├── playback/
│   ├── PlaybackPoller.kt        interrogazione periodica di Spotify
│   ├── PositionClock.kt         interpolazione della posizione
│   └── AudioOutput.kt           identificazione dell'uscita audio
├── render/
│   ├── KaraokeRenderer.kt       tutto il disegno
│   ├── KaraokeView.kt           contenitore per il telefono
│   └── TextWrapper.kt           mandata a capo
├── ui/Theme.kt                  schema colori Material 3
└── update/
    ├── UpdateChecker.kt         ultima release da GitHub
    └── Updater.kt               download e installazione
```

## Flusso dei dati

```
PlaybackService (foreground)
        │
        ▼
PlaybackPoller ──── ogni 4 s ──▶ GET /v1/me/player
        │                              │
        │◀─── progress_ms, round-trip ──┘
        ▼
   PlaybackState  ───▶ StateFlow
        │                  │
        │                  └──▶ al cambio di trackId ──▶ LyricsRepository
        │                                                     │
        │                                          cache su disco, poi LRCLIB
        │                                                     ▼
        │                                                  Lyrics
        ▼                                                     │
   PositionClock ◀──── offset per uscita audio               │
        │                                                     │
        └──────────────▶ Engine.currentFrame() ◀──────────────┘
                                 │
                    ┌────────────┴────────────┐
                    ▼                         ▼
           CarSurfaceRenderer            KaraokeView
            (Surface auto, 30 fps)      (telefono, vsync)
                    └────────────┬────────────┘
                                 ▼
                         KaraokeRenderer
```

Il punto chiave: la posizione non viaggia su un flusso. La calcolano i disegnatori chiamando
`Engine.currentFrame()` a ogni fotogramma. Pubblicarla su uno `StateFlow` trenta volte al
secondo farebbe ricomporre la UI del telefono senza motivo.

## I componenti

### PositionClock

Logica pura, senza dipendenze Android, testata.

```kotlin
positionMs = if (inRiproduzione) progressMs + (adesso - istanteCampionamento) + offset
             else progressMs + offset
```

Il risultato è limitato alla durata del brano. In pausa resta fermo comunque passi il tempo.

È il pezzo dove un difetto non si vede: nessun crash, nessun errore, solo testo leggermente
fuori sincrono. Per questo è isolato e coperto da test.

### PlaybackPoller

Interroga `/v1/me/player` ogni 4 secondi e pubblica uno `PlaybackState` che contiene, oltre ai
metadati, l'istante in cui `progress_ms` è stato campionato.

Quell'istante non è "adesso": è

```
elapsedRealtime() - roundTrip / 2
```

perché il server ha risposto a metà del viaggio. È la compensazione automatica della
latenza di rete, e non ha nessuna manopola.

Pubblica anche un `problem`, la stringa da mostrare al posto del testo quando qualcosa non va.
Distingue i casi (manca il Client ID, sessione scaduta, niente rete, nessuna riproduzione)
perché in auto quel messaggio è l'unica diagnostica disponibile.

Quando la richiesta fallisce, l'ultimo stato valido resta: si preferisce un testo leggermente
vecchio a uno schermo che si svuota a ogni singhiozzo di rete.

### SpotifyAuth

OAuth 2.0 con PKCE, scritto a mano invece di usare AppAuth: sono un code verifier, uno scambio
e un refresh, in tutto una novantina di righe, contro una dipendenza con la sua configurazione
nel manifest. Se un domani servissero più provider, AppAuth diventa la scelta giusta.

I token stanno in `EncryptedSharedPreferences`: sono credenziali di accesso all'account.

Il Client ID arriva da `local.properties` attraverso `BuildConfig`, con ricaduta sul valore
scritto a mano nell'app. In PKCE non è un segreto, quindi può stare nel build; il senso è
non doverlo digitare sul telefono a ogni installazione.

Spotify non rimanda sempre il refresh token quando si rinnova: se manca, si conserva il
precedente. Perderlo significherebbe un logout silenzioso dopo un'ora.

### LrcLibSource e LyricsRepository

La ricerca esatta usa artista, titolo, album e durata in secondi. La durata fa parte della
chiave perché distingue versioni diverse dello stesso brano (singolo, album, remaster).

Quando la ricerca esatta fallisce, cosa frequente proprio sui remaster, si ripiega su una
ricerca per solo artista e titolo prendendo il primo risultato con testo sincronizzato.

Il repository mette in cache su disco anche l'esito negativo. Senza, ogni brano senza testo
verrebbe ricercato di nuovo a ogni riproduzione, e in auto significa attesa a ogni cambio di
canzone. La cache è un file JSON per traccia in `filesDir`, niente database: sono letture per
chiave singola, nessuna query, un database qui sarebbe impianto senza uso.

### LrcParser

Regge i casi che si incontrano davvero: più timestamp sulla stessa riga (ritornelli),
centesimi o millesimi di secondo, tag di metadata da ignorare, righe vuote che nel formato
indicano le pause strumentali e vanno conservate perché occupano spazio nello scorrimento.

`activeIndex` è una ricerca binaria: viene invocata a ogni fotogramma.

### KaraokeRenderer

Riceve `(canvas, area, frame)` e disegna. Non conosce né Android Auto né Compose, ed è per
questo che lo stesso karaoke gira su due schermi senza duplicare nulla.

Scelte che contano:

- **Dimensione del testo dal fattore più stretto** fra larghezza e altezza. Tarare solo
  sull'altezza funziona in auto e produce testo enorme in verticale sul telefono.
- **Righe mandate a capo, mai troncate.** La spezzatura sta in `TextWrapper`, che riceve la
  funzione di misura dall'esterno: così è verificabile con un test normale, senza framework
  grafico. Regge anche la parola più larga della riga intera, che altrimenti manderebbe in
  ciclo infinito il taglio.
- **Layout a blocchi, non a righe fisse.** Una riga di testo può occuparne tre a schermo, e
  lo scorrimento continuo sposta il blocco intero.
- **Il testo vive in una fascia, non sullo schermo intero.** Si centra fra intestazione e
  barra di avanzamento: centrarlo sull'area completa lasciava un vuoto sotto il titolo e
  schiacciava le righe sopra la barra.
- **Quante righe di contorno si vedono lo decide lo spazio**, non una costante: si riempie
  finché c'è fascia. Sul telefono in verticale si vede molto contesto, sullo schermo basso
  dell'auto poche righe, senza due tarature separate. La costante che resta è solo un tetto
  di sicurezza al ciclo.
- **Paint come campi, non variabili locali.** Il metodo gira trenta volte al secondo e
  allocare nel ciclo di disegno si vede.
- **Alone sulla riga corrente** (`setShadowLayer`), per staccarla con lo sguardo di sbieco.

Il testo senza timestamp viene fatto scorrere in proporzione alla posizione nel brano. Non è
sincronia ed è dichiarato come tale nell'etichetta della sorgente: è un compromesso per non
lasciare fermo un muro di testo.

### Il lato auto

`SquarciagolaCarAppService` dichiara la categoria navigazione. `KaraokeScreen` restituisce un
`NavigationTemplate` che serve solo a reggere la Surface e due comandi per la sincronia.
`CarSurfaceRenderer` tiene il ciclo di disegno a circa 30 fotogrammi al secondo.

Si disegna dentro l'area visibile comunicata dall'host, non sull'intera Surface: parte dello
schermo può essere coperta dai controlli di sistema.

`HostValidator.ALLOW_ALL_HOSTS_VALIDATOR` va bene solo perché l'installazione è manuale su
un dispositivo personale. Un'app distribuita dovrebbe elencare le firme note.

### PlaybackService

Foreground service di tipo `dataSync`. Serve davvero: in Android Auto l'Activity del telefono
non è mai in primo piano, e senza il servizio il sistema sospende le coroutine dopo poco,
lasciando il testo fermo mentre la musica va avanti.

Viene avviato anche dall'apertura della schermata in auto, così funziona pure se l'app sul
telefono non è mai stata toccata in quella sessione.

### Aggiornamenti

`UpdateChecker` legge l'ultima release dall'API di GitHub. Convenzione unica: il tag è
`v<versionCode>`, e quel numero è quanto viene confrontato. Nessun server, nessun file
manifest da mantenere.

`Updater` scarica con `DownloadManager`, che gestisce da solo notifica di avanzamento, ripresa
e rete intermittente, e restituisce un content URI già condivisibile con l'installer: è il
motivo per cui non serve configurare un FileProvider. L'installazione la esegue Android e
chiede conferma; un'app non può sostituirsi da sola in silenzio.

Il repository deve essere pubblico: l'API delle release su repo privati richiede
autenticazione, e l'unico modo per averla sarebbe un token dentro l'APK.

## La sincronia, per esteso

Il disallineamento fra testo e audio ha due cause. Una si misura, l'altra no, e la
distinzione è la ragione per cui la taratura manuale non è stata eliminata.

**Latenza di rete.** Misurabile: si cronometra il round-trip e si colloca l'istante di
campionamento a metà. Automatica, si adatta al variare della connessione, nessuna manopola.

**Ritardo audio dell'impianto.** Non misurabile: nasce a valle di Spotify, in un percorso
audio che l'app non controlla, e nessuna API Android lo espone per la riproduzione di
un'altra applicazione. Qualunque automatismo sarebbe un numero inventato.

Quello che si può fare, ed è quello che l'app fa, è non farlo tarare due volte: l'offset è
memorizzato per uscita audio, identificata dal nome del dispositivo. Si calibra una volta per
l'impianto dell'auto, una per le cuffie, e ogni collegamento successivo riapplica il valore
giusto da solo.

Il nome dell'uscita viene tenuto in cache un paio di secondi: viene letto a ogni fotogramma
attraverso l'offset, e interrogare `AudioManager` trenta volte al secondo per un dato che
cambia quando colleghi il Bluetooth sarebbe spreco puro.

Se un giorno si volesse togliere anche questa taratura, l'unica strada seria è una
calibrazione a orecchio: parte un brano noto, l'utente preme un tasto sull'attacco, l'app
misura lo scarto.

## Interfaccia

Tema Material 3 solo scuro, per la schermata e per il karaoke. Non è gusto: l'app si guarda
al buio, in macchina di sera o col telefono nel supporto, e uno schermo chiaro in quelle
condizioni acceca.

Il verde menta è l'unico accento e non viene speso per decorare: marca la riga che si sta
cantando e l'azione principale. Tutto il resto vive di neutri.

Due cose si muovono, entrambe perché comunicano uno stato:

- le tre barrette accanto al titolo si agitano mentre la musica va e si posano in pausa, così
  da lontano si vede se il polling sta ricevendo davvero o è fermo su un dato vecchio;
  seguono l'impostazione di sistema per la rimozione delle animazioni
- il dissolvimento al cambio di brano, con lo stato agganciato alla traccia intera e non al
  suo identificatore, altrimenti il contenuto uscente mostrerebbe già i dati nuovi

## Cosa è verificato e cosa no

I test coprono i punti dove un difetto resta silenzioso: `PositionClock`, `LrcParser`,
`TextWrapper`, `UpdateChecker`. Sono logica pura, girano senza dispositivo.

Rendering, integrazione con Android Auto, giro OAuth completo e installazione
dell'aggiornamento non sono verificabili in automatico. La procedura manuale è in
[verifica.md](verifica.md).

## Limiti noti

- Nessun widget nella home di Android Auto: non esiste l'API
- Non pubblicabile su Play Store: la categoria dichiarata non è quella reale
- Solo Spotify: il rilevamento passa dalla sua Web API
- Evidenziazione per riga, non per parola: LRCLIB sincronizza così, e il formato esteso ha
  copertura quasi nulla
- Il ritardo audio dell'impianto va tarato una volta per uscita
