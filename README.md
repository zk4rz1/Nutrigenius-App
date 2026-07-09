# Nutrigenius-App (Android Wrapper)

Questa applicazione è un wrapper Android per la PWA di Nutrigenius. Ha lo scopo di funzionare da "ponte" tra la Progressive Web App e le API native di Android, in particolar modo le API di **Health Connect**.

## Funzionalità principali

- **WebView Integrata**: Mostra l'applicazione web Nutrigenius (PWA) garantendo la migliore esperienza possibile su dispositivi mobili.
- **Integrazione con Health Connect**: Permette alla PWA di leggere e scrivere i dati relativi alla salute, all'allenamento e all'idratazione in maniera completamente sicura tramite l'app Health Connect nativa di Google.
- **Protezione Anti-Crash (OOM Guard)**: Le richieste ad Health Connect avvengono tramite specifiche fasce orarie. In caso di OOM (Out Of Memory) durante la serializzazione massiva di dati ad alta frequenza (come i battiti cardiaci), il Wrapper intercetta automaticamente il fallimento, fraziona il lasso di tempo in chunk più piccoli, e li invia incrementalmente alla PWA, garantendo zero crash e nessuna perdita di dati.
- **Supporto Dual-Environment (Flavors)**: L'app è progettata con due flavor Gradle, **Dev** (per l'ambiente di sviluppo e testing) e **Prod** (per l'applicazione web di produzione).
- **Notifiche Locali e Allarmi**: Abilita le notifiche push locali per l'app in background (ad esempio per i promemoria di idratazione o l'inserimento dei pasti).
- **Esportazione file e Appunti**: Consente il download locale di file Blob generati dalla PWA, l'esportazione di JSON, e l'accesso agli appunti (clipboard) nativi del dispositivo.

## Come installare l'applicazione

Nella pagina **Releases** di questo repository puoi scaricare due file APK:

1. **app-dev-debug.apk**: L'app configurata per comunicare con il link dell'ambiente di sviluppo di Nutrigenius.
2. **app-prod-debug.apk**: L'app ufficiale, configurata per la web app di produzione.

Scarica il file corrispondente alle tue esigenze e installalo manualmente sul tuo dispositivo Android.
Ricorda di concedere i permessi richiesti per interagire con Health Connect quando richiesto.

## Sviluppo

Per modificare il codice sorgente, ti basta importare questo progetto all'interno di Android Studio e sincronizzare Gradle.
Le build di sviluppo possono essere lanciate direttamente dall'IDE o eseguendo `.\gradlew assembleDebug` da riga di comando.
