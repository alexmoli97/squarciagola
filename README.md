# Squarciagola

Karaoke dei brani riprodotti da Spotify, sullo schermo di Android Auto e su quello del telefono.
Uso personale, installazione manuale, nessuna pubblicazione su store.

## Cosa fa

Legge dalla Web API di Spotify quale brano sta suonando e a che punto è, recupera il testo
sincronizzato e lo fa scorrere evidenziando la riga in corso. Lo stesso motore di disegno
alimenta sia la Surface di Android Auto sia la vista sul telefono.

## Cosa non fa, e perché

**Nessun widget sulla home di Android Auto.** La home mostra card controllate da Google e non
esiste API per aggiungerne di terze parti. L'app compare come icona nel launcher di Android Auto.

**Non finirà mai sul Play Store.** Per disegnare liberamente serve una Surface, che l'host
concede solo alle app di categoria navigazione. Squarciagola si dichiara tale pur non essendo
un navigatore: funziona in sideload, non supererebbe una review.

**Non usa i testi di Spotify.** Non esistono via API, e la strada non ufficiale è stata
valutata e scartata. Vedi sotto.

## Documentazione

- [docs/architettura.md](docs/architettura.md): com'è fatta dentro, i vincoli che ne hanno
  deciso la forma, le alternative scartate
- [docs/verifica.md](docs/verifica.md): cosa va provato prima di una release, automatico e
  manuale

## Compilazione

Servono JDK 17 o superiore e l'SDK Android (piattaforma 35).

```bash
./gradlew :app:assembleDebug     # APK in app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest # test di PositionClock e del parser LRC
```

Se l'SDK non è nella posizione predefinita, indicalo in `local.properties` con `sdk.dir=...`.

## Dove gira

| Piattaforma | Stato |
|---|---|
| Telefono Android | supportata, e' la superficie principale |
| Android Auto | supportata via sideload, categoria navigazione |
| Android TV e Fire TV | supportata: launcher leanback, banner, tasto Indietro chiude il karaoke |
| Web | non supportata, richiederebbe una riscrittura separata |
| Trasmissione a Chromecast | non supportata, richiede un application id registrato e un receiver |

Su televisore l'app si installa via sideload come sulle altre superfici. Il karaoke a schermo
pieno e' esattamente cio' che serve li', e l'interfaccia era gia' scura e centrata: l'unica
aggiunta e' stata la voce nel launcher e il tasto Indietro, perche' sul telecomando non c'e'
un dito che raggiunge il pulsante di chiusura.

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

Serve per sapere cosa sta suonando. La registrazione dell'app non è aggirabile: senza un
Client ID non esiste OAuth.

1. Su [developer.spotify.com](https://developer.spotify.com/dashboard) crea un'app.
2. Aggiungi come Redirect URI esattamente `it.squarciagola://auth`.
3. Copia il Client ID in `local.properties`:

```properties
spotify.clientId=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

Ricompila e il campo sparisce dalla schermata: resta solo **Accedi a Spotify**. Il Client ID
in PKCE non è un segreto, quindi sta tranquillamente nel build; `local.properties` è comunque
fuori dal repository.

Chi installa l'APK senza ricompilare trova il campo e può incollarlo a mano.

Gli scope richiesti sono `user-read-playback-state` e `user-read-currently-playing`.
Non serve il client secret: l'autenticazione usa PKCE.

### Perche' l'SDK Android di Spotify non e' usato

Entrambi i suoi pezzi sono stati provati sul dispositivo e rimossi.

**App Remote** prometteva una sincronia migliore, leggendo la posizione dall'app Spotify senza
rete di mezzo. La connessione non si e' mai stabilita: prima falliva chiedendo
un'autorizzazione che non compariva, poi, dopo aver registrato package e impronta sul
dashboard, restava appesa senza risposta. In cambio voleva un binario chiuso da scaricare a
parte e una logica di ripiego con timeout.

**La libreria di autorizzazione** presenta il consenso dentro l'app Spotify, il che sarebbe
piu' comodo del browser. Il consenso passava, ma lo scambio del codice falliva con
`invalid_request: Invalid client secret`: la libreria non inoltra i parametri liberi in cui
viaggia il code challenge, quindi il codice veniva emesso senza PKCE e riscattarlo avrebbe
richiesto un client secret, che in un APK non ci va.

Resta quindi OAuth PKCE nel browser, scritto a mano: nessun segreto, refresh token conservato,
e funziona ovunque, televisori compresi. La storia sta nei commit fino alla v14.

Registrare package e impronta sul dashboard non serve piu', ma non fa danno ed e' gia' fatto.

### Testi

I testi arrivano da [LRCLIB](https://lrclib.net): pubblico, senza autenticazione, sincronizzato
riga per riga. Non c'è niente da configurare.

Spotify non espone i testi tramite API. Esiste un endpoint interno raggiungibile fingendosi il
web player, ma la strada è stata valutata e scartata: costa il cookie di sessione dell'account
dentro l'app, un segreto anti-automazione da rincorrere a ogni rotazione, e una violazione dei
Termini di Servizio, per ottenere la stessa granularità di sincronizzazione che LRCLIB dà
gratis. Quando LRCLIB non ha un brano, l'app lo dice e passa oltre.

### Sincronia

Il disallineamento ha due cause, e solo una è misurabile.

**Latenza di rete: automatica.** La posizione riportata da Spotify descrive un istante già
passato quando la risposta arriva. L'app misura il round-trip di ogni richiesta e colloca
l'istante di campionamento mezzo round-trip indietro. Nessuna taratura, si adatta da solo al
variare della connessione.

**Ritardo audio dell'impianto: una taratura per dispositivo.** È a valle di Spotify, in un
percorso audio che l'app non controlla, e nessuna API lo espone: non esiste modo software di
misurarlo. Quello che l'app fa è non farlo tarare due volte, tenendo un valore separato per
ogni uscita audio (impianto dell'auto, cuffie, altoparlante del telefono). Calibri una volta e
al collegamento successivo il valore torna da solo.

Si regola dal telefono a passi di 50 ms e in auto con i due pulsanti sulla barra dei comandi, a
passi di 100 ms. Positivo se il testo va in ritardo rispetto a quello che senti. La scheda sul
telefono mostra a quale uscita si riferisce il valore che stai modificando.

### Interfaccia

Tema scuro unico, per la schermata e per il karaoke. Non è una preferenza estetica: quest'app
si guarda al buio, in macchina di sera o col telefono nel supporto, e uno schermo chiaro in
quelle condizioni acceca.

Due pagine distinte. La **home** porta il nome dell'app, la copertina del disco a fuoco e in
grande, il brano in riproduzione e il pulsante per cantarlo, con sotto una riga sottile per
sapere a che punto sei senza aprire il karaoke. La copertina riempie la pagina con qualcosa di
vero invece che con ornamenti, e l'alone attorno prende il colore del brano: anche da fermi si
capisce da dove viene la tinta di tutta l'interfaccia. Senza copertina resta la sagoma, cosi'
la pagina non cambia forma al cambio di brano.
Le **impostazioni** stanno dietro una porta, perche' la configurazione si tocca una volta e il
canto ogni giorno. Il passaggio fra le due scorre lungo un asse orizzontale, a destra entrando
e a sinistra tornando, cosi' il movimento dice dove sei finito.

La copertina sfocata fa da sfondo a tutte e tre le schermate, e il pulsante respira mentre la
musica va.

**L'accento lo detta la musica.** Resta la regola di sempre, un solo colore che marca la riga
in corso e l'azione principale, ma quel colore viene ricavato dalla copertina del brano: ogni
canzone tinge l'app della sua. Il colore dominante si sceglie per quanto è vivo e non per
quanto è diffuso, altrimenti vincerebbe sempre il grigio del fondo copertina, e viene poi
riportato dentro una finestra di saturazione e luminosità che garantisce almeno 4,5:1 di
contrasto sul fondo scuro. Una copertina senza colore ricade sul verde menta.

**La riga in corso e' piu' grande delle altre.** Il layout pero' non cambia mai: ogni riga si
prende comunque l'altezza della misura grande, e quelle di contorno vengono disegnate piu'
piccole al centro del loro spazio. Fare altrimenti significherebbe ricalcolare le posizioni a
ogni cambio riga, e quel riflusso si vedrebbe come uno scatto nello scorrimento.

**La riga in corso si riempie mentre viene cantata**, da sinistra a destra: la parte già
passata prende il colore del brano, quella ancora da dire resta chiara. LRCLIB dà l'attacco
di ogni riga, mai la sua durata né i tempi delle singole parole. Spalmare il riempimento su
tutto l'intervallo fino alla riga dopo sembra la cosa ovvia ed è sbagliata: fra due righe ci
sta spesso uno stacco strumentale, e il riempimento striscerebbe lento mentre chi canta ha già
finito. La durata si stima quindi dal numero di caratteri, a una velocità di canto plausibile,
limitata allo spazio realmente disponibile.

Resta una stima: su una riga strascicata o sputata in fretta lo scarto si vede. La manopola è
`CARATTERI_AL_SECONDO` in `KaraokeRenderer`, alzala se il riempimento ritarda. La cura vera
sarebbe una sincronia per parola, che la sorgente non fornisce.

Le tre barrette accanto al titolo si muovono mentre la musica va e si posano in pausa, così da
lontano si vede se il polling sta ricevendo; seguono l'impostazione di sistema per la rimozione
delle animazioni.

In basso a destra nel karaoke c'è scritto da dove arriva il testo, e se è sincronizzato o no.

## Aggiornamenti senza store

L'app si aggiorna da sola dalle release di GitHub. Non c'è server, non c'è manifest da
mantenere: pubblicare una versione significa creare una release con l'APK allegato.

**Regola unica**: il tag della release è `v<versionCode>`, e quel numero deve coincidere con
il `versionCode` in `app/build.gradle.kts`. Release `v3` vuol dire `versionCode = 3`.

Il repository di riferimento è la costante `REPOSITORY` in `update/UpdateChecker.kt`.
Va messa sul tuo repo prima della prima release, altrimenti il controllo cerca un indirizzo
che non esiste.

### Pubblicare una versione

```bash
# 1. alza versionCode e versionName in app/build.gradle.kts
./release.sh "Cosa cambia in questa versione"
```

Il tag non si passa a mano di proposito: lo script lo ricava dal `versionCode` dichiarato nel
build. È l'unico errore capace di rompere l'aggiornamento in silenzio, e toglierlo di mezzo
costa meno che accorgersene dopo.

Prima di pubblicare lo script rifiuta di procedere se non sei su `main`, se ci sono modifiche
non committate, se il tag esiste già o se il `versionCode` non è superiore all'ultima release.
Poi esegue `./check.sh`, pubblica, e alla fine rilegge l'API di GitHub per controllare che la
release sia davvero come l'app se la aspetta.

### Cosa succede sul telefono

All'apertura l'app interroga GitHub in silenzio: se non c'è rete o non c'è niente di nuovo
non compare nulla. Quando trova una versione con numero più alto mostra una scheda con le
note di rilascio e il pulsante **Aggiorna**. C'è anche **Controlla aggiornamenti** per
forzare il controllo a mano.

Premendo Aggiorna il download passa da DownloadManager, con la sua notifica di avanzamento, e
al termine parte l'installer di sistema. La conferma finale la chiede Android: un'app non
può sostituirsi da sola in silenzio.

La prima volta serve concedere a Squarciagola il permesso di installare app ("Installa app
sconosciute"). Il pulsante ci porta direttamente nella schermata giusta; concesso il permesso,
si ripreme Aggiorna.

Le firme devono combaciare: se cambi il keystore fra una versione e l'altra, Android rifiuta
l'aggiornamento e va disinstallata e reinstallata. Restando sulla build di debug il problema
non si pone.

## Come è fatto

| File | Ruolo |
|---|---|
| `playback/PositionClock.kt` | Interpola la posizione tra due poll. Logica pura, testata |
| `playback/PlaybackPoller.kt` | Poll di `/v1/me/player` ogni 4 secondi |
| `lyrics/LrcParser.kt` | Parser LRC e ricerca binaria della riga attiva. Testato |
| `lyrics/LrcLibSource.kt` | Sorgente dei testi, con ricerca di ripiego sui metadati |
| `lyrics/LyricsRepository.kt` | Cache su disco, esiti negativi inclusi |
| `ui/Theme.kt` | Schema colori Material 3, solo scuro |
| `render/KaraokeRenderer.kt` | Tutto il disegno. Non conosce né l'auto né Compose |
| `render/TextWrapper.kt` | Righe lunghe mandate a capo. Testato senza framework grafico |
| `render/AlbumArt.kt` | Copertina sfocata con media mobile, sfondo del testo |
| `ui/Accento.kt` | Colore dominante della copertina, riportato in leggibilità. Testato |
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
installazione dell'aggiornamento) non è verificabile senza dispositivo: va provato a mano, con
il Desktop Head Unit o in macchina.
