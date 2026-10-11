package com.example.nutrigeniusbridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.nutrigeniusbridge.Impostazioni.esitoAggiornamenti
import com.example.nutrigeniusbridge.Impostazioni.ultimoControllo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * L'APK si aggiorna da sé, come ChatGist.
 *
 * Legge `latest.json` dalla produzione su ServeJS (BuildConfig.AGGIORNAMENTI_URL,
 * pubblico come il resto dell'app):
 *
 *   {"versionCode": 5, "versionName": "1.2.0", "apk": "NutriGenius-1.2.0.apk",
 *    "sha256": "…", "note": "…"}
 *
 * Se c'è una versione più nuova la scarica, ne controlla l'impronta e la
 * consegna all'installatore di Android. Da Android 12 un'app che aggiorna se
 * stessa può farlo senza conferma; quando Android la vuole lo stesso (la prima
 * volta, o se l'app era stata installata da un altro programma) la chiede lui:
 * con l'app aperta compare la sua finestra, altrimenti una notifica.
 *
 * Si controlla all'apertura (al massimo ogni 6 ore) e ogni 12 ore in
 * background, con la rete.
 */
object Aggiornamenti {
    private const val CANALE = "aggiornamenti"
    private const val NOTIFICA = 4201
    private const val LAVORO = "controllo-aggiornamenti"
    private const val OGNI_APERTURA_MS = 6 * 60 * 60 * 1000L

    /** Programma il controllo periodico (una volta sola: se c'è già, resta quello). */
    fun programma(c: Context) {
        val vincoli = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val lavoro = PeriodicWorkRequestBuilder<ControlloAggiornamenti>(12, TimeUnit.HOURS).setConstraints(vincoli).build()
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(LAVORO, ExistingPeriodicWorkPolicy.KEEP, lavoro)
    }

    /** All'apertura: un controllo se l'ultimo è di più di 6 ore fa. */
    fun suApertura(c: Context): Boolean = System.currentTimeMillis() - c.ultimoControllo > OGNI_APERTURA_MS

    /**
     * Controlla e, se serve, aggiorna. Torna una riga per l'utente (usata dal
     * pulsante «Controlla aggiornamenti» delle impostazioni).
     */
    suspend fun controlla(c: Context): String = withContext(Dispatchers.IO) {
        c.ultimoControllo = System.currentTimeMillis()
        val esito = try {
            val ultima = JSONObject(scarica(BuildConfig.AGGIORNAMENTI_URL))
            val codice = ultima.optInt("versionCode", 0)
            val nome = ultima.optString("versionName", "?")
            if (codice <= BuildConfig.VERSION_CODE) {
                "Nessun aggiornamento: questa è la versione più recente (${BuildConfig.VERSION_NAME})."
            } else {
                val apk = scaricaApk(c, ultima)
                installa(c, apk, nome)
                "Aggiornamento alla $nome scaricato: lo installa Android."
            }
        } catch (e: Exception) {
            "Controllo non riuscito: ${e.message ?: e.javaClass.simpleName}"
        }
        c.esitoAggiornamenti = esito
        esito
    }

    private fun connessione(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 120_000
            useCaches = false
            setRequestProperty("Cache-Control", "no-cache")
        }

    private fun scarica(url: String): String {
        val con = connessione(url)
        try {
            if (con.responseCode != 200) throw IllegalStateException("$url: HTTP ${con.responseCode}")
            return con.inputStream.bufferedReader().use { it.readText() }
        } finally {
            con.disconnect()
        }
    }

    /** L'APK nella cartella della cache, con l'impronta controllata (quello già scaricato si riusa). */
    private fun scaricaApk(c: Context, ultima: JSONObject): File {
        val nome = ultima.optString("apk").takeIf { it.matches(Regex("[A-Za-z0-9._-]+\\.apk")) } ?: throw IllegalStateException("latest.json senza un nome di APK valido")
        val attesa = ultima.optString("sha256").lowercase()
        if (!attesa.matches(Regex("[0-9a-f]{64}"))) throw IllegalStateException("latest.json senza impronta sha256")
        val dir = File(c.cacheDir, "aggiornamenti").apply { mkdirs() }
        val file = File(dir, nome)
        if (file.exists() && sha256(file) == attesa) return file
        dir.listFiles()?.forEach { it.delete() }
        val url = URL(URL(BuildConfig.AGGIORNAMENTI_URL), nome).toString()
        val con = connessione(url)
        try {
            if (con.responseCode != 200) throw IllegalStateException("$url: HTTP ${con.responseCode}")
            con.inputStream.use { input -> file.outputStream().use { input.copyTo(it, 64 * 1024) } }
        } finally {
            con.disconnect()
        }
        val trovata = sha256(file)
        if (trovata != attesa) {
            file.delete()
            throw IllegalStateException("l'APK scaricato non corrisponde all'impronta di latest.json")
        }
        return file
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun installa(c: Context, file: File, versione: String) {
        if (!c.packageManager.canRequestPackageInstalls()) {
            val impostazioni = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            notifica(c, "Aggiornamento $versione pronto", "Per installarlo, permetti a NutriGenius di installare app: tocca qui.", impostazioni)
            throw IllegalStateException("manca il permesso di installare app (te l'ho chiesto con una notifica)")
        }
        val installatore = c.packageManager.packageInstaller
        val parametri = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(c.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installatore.createSession(parametri)
        try {
            installatore.openSession(id).use { sessione ->
                sessione.openWrite("nutrigenius.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out, 64 * 1024) }
                    sessione.fsync(out)
                }
                val esito = Intent(c, EsitoInstallazione::class.java).setAction(EsitoInstallazione.AZIONE).putExtra(EsitoInstallazione.VERSIONE, versione)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                sessione.commit(PendingIntent.getBroadcast(c, id, esito, flags).intentSender)
            }
        } catch (e: Exception) {
            runCatching { installatore.abandonSession(id) }
            throw e
        }
    }

    fun notifica(c: Context, titolo: String, testo: String, azione: Intent?) {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CANALE, "Aggiornamenti dell'app", NotificationManager.IMPORTANCE_DEFAULT))
        val b = NotificationCompat.Builder(c, CANALE)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(titolo)
            .setContentText(testo)
            .setStyle(NotificationCompat.BigTextStyle().bigText(testo))
            .setAutoCancel(true)
        if (azione != null) b.setContentIntent(PendingIntent.getActivity(c, NOTIFICA, azione, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        nm.notify(NOTIFICA, b.build())
    }

    /** true mentre l'app è in primo piano (lo segna MainActivity): la conferma di Android si può mostrare subito. */
    @Volatile var inPrimoPiano = false
}

/** L'esito che Android comunica dopo aver ricevuto l'APK. */
class EsitoInstallazione : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val versione = intent.getStringExtra(VERSIONE).orEmpty()
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val conferma = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT))
                    ?: return
                conferma.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (Aggiornamenti.inPrimoPiano) runCatching { context.startActivity(conferma) }
                // La notifica resta comunque: se la finestra non si apre, l'aggiornamento non si perde.
                Aggiornamenti.notifica(context, "Aggiornamento $versione pronto", "Tocca per installare la nuova versione di NutriGenius.", conferma)
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            PackageInstaller.STATUS_FAILURE_ABORTED -> Unit
            else -> {
                val perche = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                Aggiornamenti.notifica(context, "Aggiornamento $versione non installato", perche.ifEmpty { "Android non l'ha installato." }, null)
            }
        }
    }

    companion object {
        const val AZIONE = "com.example.nutrigeniusbridge.ESITO_INSTALLAZIONE"
        const val VERSIONE = "versione"
    }
}

/** Il controllo periodico, in background. */
class ControlloAggiornamenti(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Aggiornamenti.controlla(applicationContext)
        return Result.success()
    }
}
