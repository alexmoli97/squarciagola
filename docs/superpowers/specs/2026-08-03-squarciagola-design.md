# Squarciagola, design

Data: 2026-08-03
Stato: bozza. Le decisioni nella tabella "Decisioni prese" sono scelte esplicite
dell'utente; il documento nel suo insieme e' in attesa di revisione.

## Obiettivo

App Android personale che mostra i testi sincronizzati del brano riprodotto da Spotify,
con rendering karaoke sia sullo schermo di Android Auto sia sul telefono.
Uso personale, distribuzione via sideload, nessuna pubblicazione su store.

## Vincoli di piattaforma accertati

Questi vincoli non sono opinioni di design, sono limiti della piattaforma verificati
prima di scrivere il documento. Definiscono la forma della soluzione.

### Nessun widget sulla home di Android Auto

La schermata home di Android Auto mostra card controllate da Google (navigazione,
media, Assistant). Non esiste API pubblica per aggiungere widget di terze parti.
La richiesta iniziale prevedeva un widget in home: non e' realizzabile in nessuna
forma. L'app compare come icona nel launcher di Android Auto e si apre a schermo pieno.

### UI custom solo tramite categoria navigazione

Le app Android Auto sono chiuse dentro template fissi (lista, pane, griglia, messaggio).
Nessun template consente testo che scorre a tempo con evidenziazione della riga corrente.
L'unica superficie di disegno libera e' quella esposta da `SurfaceCallback`, disponibile
solo alle app che dichiarano `androidx.car.app.category.NAVIGATION`.

Conseguenza accettata: l'app si dichiara di categoria navigazione pur non essendo un
navigatore. Funziona in sideload con Android Auto in modalita' sviluppatore, e non sara'
mai pubblicabile su Play Store (categoria impropria piu' linee guida sulla distrazione
del guidatore). Questo e' un vincolo permanente, non un debito da ripagare.

### Spotify non espone i testi ufficialmente

La Web API di Spotify non ha endpoint per i testi e nessuno scope OAuth li sblocca.
I testi provengono da Musixmatch tramite accordo commerciale e restano interni al client.

L'unica via e' l'endpoint interno `spclient.wg.spotify.com/color-lyrics/v2/track/{id}`,
che richiede un token del web player, diverso dal token OAuth dell'app registrata.
Dal 14 marzo 2025 la richiesta di quel token richiede una verifica TOTP, il cui segreto
va estratto dal bundle JavaScript del web player, ruotato periodicamente da Spotify
(versione tracciata a gennaio 2026: v61).

Conseguenze accettate e messe per iscritto:

- l'accesso ai testi Spotify e' contro i Termini di Servizio di Spotify
- richiede il cookie di sessione `sp_dc` dell'account memorizzato nell'app
- si rompe a ogni rotazione del segreto, in modo imprevedibile
- comporta un rischio, per quanto basso, di provvedimenti sull'account

La mitigazione e' il fallback automatico su LRCLIB, pubblico e senza autenticazione,
che ha la stessa granularita' di sincronizzazione (per riga) e differisce solo per
copertura del catalogo.

## Decisioni prese

| Ambito | Decisione | Alternativa scartata |
|---|---|---|
| Rilevamento riproduzione | Spotify Web API, polling di `/v1/me/player` | MediaSession locale via NotificationListenerService |
| Rendering Android Auto | `NavigationTemplate` con Surface e disegno su Canvas | Template standard, solo telefono |
| UI telefono | Karaoke completo a schermo pieno, piu' schermata di setup | Solo schermata di setup |
| Sorgente testi | Spotify primaria, LRCLIB come fallback automatico | Solo LRCLIB |
| Persistenza | File JSON in `filesDir`, chiave l'ID traccia | Database Room |
| Linguaggio e stack | Kotlin, Compose sul telefono, `androidx.car.app` per l'auto | Nessuna: la Car App Library e' solo nativa |

## Architettura

Modulo Android singolo, processo unico. Un `PlaybackService` in foreground mantiene
attivo il polling anche quando ne' l'Activity ne' la schermata auto sono in primo piano.
Senza foreground service il sistema sospende il polling appena l'utente passa ad
un'altra app, e in Android Auto l'Activity del telefono non e' mai in foreground.

### Componenti

| Componente | Responsabilita' | Dipendenze |
|---|---|---|
| `SpotifyAuth` | OAuth PKCE via AppAuth, refresh token in EncryptedSharedPreferences | rete |
| `PlaybackPoller` | Poll di `/v1/me/player` ogni 4 secondi, emette `PlaybackState` su StateFlow | `SpotifyAuth` |
| `PositionClock` | Interpola la posizione tra due poll, applica l'offset di calibrazione | nessuna, logica pura |
| `LyricsSource` | Interfaccia: dai metadati traccia restituisce righe sincronizzate o null | nessuna |
| `SpotifyLyricsSource` | Implementazione con cookie `sp_dc` e token TOTP del web player | rete |
| `LrcLibSource` | Implementazione su `lrclib.net/api/get`, senza autenticazione | rete |
| `LyricsRepository` | Cache su disco, orchestrazione delle sorgenti in ordine, parser LRC | sorgenti, disco |
| `KaraokeRenderer` | Disegna lo stato su un Canvas data un'area visibile | nessuna, logica pura |
| `KaraokeSurfaceCallback` | Ciclo di disegno a 30 fps sulla Surface dell'auto | `KaraokeRenderer` |
| `KaraokeView` | `View` che invoca lo stesso renderer in `onDraw` sul telefono | `KaraokeRenderer` |
| `SquarciagolaCarAppService` | `CarAppService` di categoria navigazione, espone la schermata karaoke | `KaraokeSurfaceCallback` |
| `MainActivity` | Compose: login, stato, calibrazione offset, karaoke a schermo pieno | tutto il resto |

Il renderer non conosce ne' Android Auto ne' Compose. Riceve
`(canvas, area, righe, posizioneMs, metadati)` e disegna. E' la ragione per cui avere il
karaoke su entrambi gli schermi costa poco: una sola implementazione, due chiamanti.

### Flusso dati

1. `PlaybackPoller` interroga Spotify e memorizza `progressMs` insieme al valore di
   `SystemClock.elapsedRealtime()` al momento del campionamento.
2. `PositionClock` restituisce `progressMs + (adesso - istanteCampionamento) + offset`
   mentre la riproduzione e' attiva, e resta congelato in pausa.
3. Al cambio di `trackId`, `LyricsRepository` carica le righe: prima la cache su disco,
   poi `SpotifyLyricsSource`, poi `LrcLibSource`.
4. Il ciclo di disegno a 30 fps interroga il clock, individua la riga attiva con una
   ricerca binaria sui timestamp e disegna riga corrente evidenziata piu' contesto
   sopra e sotto.

### Calibrazione dell'offset

La latenza di rete rende `progress_ms` gia' vecchio quando arriva, e il collegamento
Bluetooth aggiunge un ritardo audio variabile per impianto. L'offset regolabile non e'
una comodita', compensa una deriva fisica che il modello non puo' dedurre.
Valore singolo globale in SharedPreferences, default 0 ms, regolabile dal telefono
in passi da 50 ms.

## Gestione degli errori

| Situazione | Comportamento |
|---|---|
| Nessuna rete | Si usa la cache; se vuota, schermata con titolo e artista |
| Token OAuth scaduto | Refresh automatico; se fallisce, in auto compare "accedi dal telefono" |
| Token web player TOTP non valido | Passaggio silenzioso a `LrcLibSource`, evento registrato nel log |
| Nessun testo trovato | Metadati e avviso; esito negativo messo in cache, un solo tentativo per traccia |
| Spotify fermo o su altro dispositivo | Schermata di attesa |

L'OAuth non si puo' completare guidando, quindi ogni errore di autenticazione in auto
si risolve in un messaggio che rimanda al telefono, mai in un tentativo di login.

## Test

Test unitari JUnit locali sui due punti dove un difetto resta silenzioso:

- `PositionClock`: interpolazione, comportamento in pausa, applicazione dell'offset
- parser LRC: timestamp malformati, righe vuote, tag di metadata, ordinamento

Rendering e integrazione con Android Auto non sono verificabili in automatico
nell'ambiente di sviluppo. Vanno provati sul dispositivo con Desktop Head Unit
o in macchina. Questo limite e' dichiarato, non aggirato.

## Fuori scope

- Widget sulla home di Android Auto: API inesistente
- Pubblicazione su Play Store: incompatibile con la categoria dichiarata
- Evidenziazione parola per parola: LRCLIB sincronizza per riga, il formato esteso
  ha copertura quasi nulla
- Player diversi da Spotify
- Utenti multipli
