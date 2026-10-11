# Nutrigenius-App (Android Wrapper)

Questa applicazione è un wrapper Android per la PWA di Nutrigenius. Ha lo scopo di funzionare da "ponte" tra la Progressive Web App e le API native di Android, in particolar modo le API di **Health Connect**.

## Funzionalità principali

- **WebView Integrata**: Mostra l'applicazione web Nutrigenius (PWA) garantendo la migliore esperienza possibile su dispositivi mobili.
- **Integrazione con Health Connect**: Permette alla PWA di leggere e scrivere i dati relativi alla salute, all'allenamento e all'idratazione in maniera completamente sicura tramite l'app Health Connect nativa di Google.
- **Protezione Anti-Crash (OOM Guard)**: Le richieste ad Health Connect avvengono tramite specifiche fasce orarie. In caso di OOM (Out Of Memory) durante la serializzazione massiva di dati ad alta frequenza (come i battiti cardiaci), il Wrapper intercetta automaticamente il fallimento, fraziona il lasso di tempo in chunk più piccoli, e li invia incrementalmente alla PWA, garantendo zero crash e nessuna perdita di dati.
- **Un solo APK, la produzione** (dalla 1.2.0): apre `https://nutrigenius.casascoiattolo.com/Nutrigenius/app/` (la PWA ospitata su ServeJS). L'indirizzo si cambia senza ricompilare da **Impostazioni dell'app** (tenere premuta l'icona › «Impostazioni», oppure dalla finestra che compare se la pagina non si apre): serve per tornare ad AI Studio. Le prove della PWA si fanno nel browser, nella copia di prova su ServeJS.
- **Aggiornamenti automatici** (dalla 1.2.0, come ChatGist): `Aggiornamenti.kt` legge `…/Nutrigenius/app/apk/latest.json` all'apertura (al massimo ogni 6 ore) e ogni 12 ore in background, scarica l'APK, ne controlla lo SHA-256 e lo consegna all'installatore di Android (senza conferma quando Android lo permette, altrimenti con la sua finestra o una notifica).
- **Certificati**: un certificato SSL non valido non si accetta più (prima la WebView proseguiva «per test»).
- **Notifiche Locali e Allarmi**: Abilita le notifiche push locali per l'app in background (ad esempio per i promemoria di idratazione o l'inserimento dei pasti).
- **Esportazione file e Appunti**: Consente il download locale di file Blob generati dalla PWA, l'esportazione di JSON, e l'accesso agli appunti (clipboard) nativi del dispositivo.
- **Scorciatoia «Scatta foto»** (dalla 1.1): tenendo premuta l'icona dell'app compare la voce «Scatta foto per l'assistente», che si può trascinare nella Home come icona a sé. Apre la PWA su `?scatta=1` (o, se l'app è già aperta, le manda l'evento `nutrigenius-scatta` senza ricaricare): la PWA sceglie il pasto dall'ora, apre la chat con l'assistente IA e subito la fotocamera a raffica. La scorciatoia è registrata dal codice (`ShortcutManagerCompat`), così il pacchetto di destinazione è giusto anche per il flavor *dev* (suffisso `.dev`). `MainActivity` è `singleTask`, per riusare la WebView già aperta.

## Come installare l'applicazione

Dalla 1.2.0 l'APK si scarica da `https://nutrigenius.casascoiattolo.com/Nutrigenius/app/apk/` (l'ultimo è indicato in `latest.json`) e poi si aggiorna da solo.

La 1.2.0 è firmata con una chiave nuova (`nutrigenius-release.p12`, copia in ServeJS `/Chatgist/keystore`): **sopra la 1.1.x non si installa**, prima va disinstallata la versione vecchia (i dati dell'app stanno nella PWA e nella sincronizzazione con ServeJS; i permessi di Connessione Salute vanno ridati).

### Compilare e pubblicare (sul server degli agenti)

`/share/ai-agents/strumenti/android-build/script/build-nutrigenius.sh <versione>` (con `NOTE="…"` per le note) compila e firma in `/tmp/rel-ng-<versione>/` l'APK e il suo `latest.json`; si caricano in ServeJS `/Nutrigenius/app/apk/`, **`latest.json` per ultimo**.

## Prima della 1.2.0


Nella pagina **Releases** di questo repository puoi scaricare due file APK:

1. **app-dev-debug.apk**: L'app configurata per comunicare con il link dell'ambiente di sviluppo di Nutrigenius.
2. **app-prod-debug.apk**: L'app ufficiale, configurata per la web app di produzione.

Scarica il file corrispondente alle tue esigenze e installalo manualmente sul tuo dispositivo Android.
Ricorda di concedere i permessi richiesti per interagire con Health Connect quando richiesto.

## Sviluppo

Per modificare il codice sorgente, ti basta importare questo progetto all'interno di Android Studio e sincronizzare Gradle.
Le build di sviluppo possono essere lanciate direttamente dall'IDE o eseguendo `.\gradlew assembleDebug` da riga di comando.
