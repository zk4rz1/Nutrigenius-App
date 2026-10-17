package com.example.nutrigeniusbridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.webkit.JavascriptInterface
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * NutriGenius multiutente nell'APK (1.3.0).
 *
 * La pagina parla con il codice nativo attraverso il ponte «NutriGeniusApk»:
 *
 * - **avvisi del server** (richieste di accesso, deleghe, registrazioni…): la WebView
 *   non riceve il Web Push e ad app chiusa il codice nativo non può firmare, perché la
 *   chiave del dispositivo sta nella WebView. La pagina passa un gettone che serve solo
 *   a leggere gli avvisi del proprio account; WorkManager li chiede ogni 15 minuti
 *   (il minimo di Android) e li mostra come notifiche;
 * - **chi usa l'app**: utente e spazio aperto. Connessione Salute si legge solo per il
 *   suo proprietario (il primo utente che la usa nel proprio spazio), mai nello spazio
 *   di un altro.
 */
class PonteApk(private val c: Context) {
    @JavascriptInterface
    fun avvisi(json: String) {
        val o = runCatching { JSONObject(json) }.getOrElse { JSONObject() }
        if (o.optString("gettone").isEmpty()) {
            Avvisi.spegni(c)
        } else {
            Avvisi.imposta(c, o.optString("server"), o.optString("gettone"), o.optLong("ultimo"), o.optString("dispositivo"))
        }
    }

    @JavascriptInterface
    fun statoAvvisi(): String = Avvisi.stato(c).toString()

    @JavascriptInterface
    fun utente(json: String) {
        val o = runCatching { JSONObject(json) }.getOrElse { JSONObject() }
        Salute.imposta(c, o.optLong("utente", 0L), o.optLong("spazio", 0L))
    }
}

object Avvisi {
    private const val FILE = "avvisi"
    private const val CANALE = "avvisi-account"
    private const val LAVORO = "avvisi-account"
    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun imposta(c: Context, server: String, gettone: String, ultimo: Long, dispositivo: String) {
        sp(c).edit().putString("server", server).putString("gettone", gettone).putLong("ultimo", ultimo)
            .putString("dispositivo", dispositivo).apply()
        programma(c, ExistingPeriodicWorkPolicy.UPDATE)
    }

    fun spegni(c: Context) {
        sp(c).edit().clear().apply()
        WorkManager.getInstance(c).cancelUniqueWork(LAVORO)
    }

    /** Lo stato per la pagina: mai il gettone. */
    fun stato(c: Context): JSONObject = JSONObject().apply {
        val s = sp(c)
        if (!s.getString("gettone", null).isNullOrEmpty()) {
            put("server", s.getString("server", ""))
            put("dispositivo", s.getString("dispositivo", ""))
        }
    }

    /** All'avvio dell'app: se c'è un gettone, il controllo periodico resta programmato. */
    fun programma(c: Context, politica: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.KEEP) {
        if (sp(c).getString("gettone", null).isNullOrEmpty()) return
        val vincoli = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val lavoro = PeriodicWorkRequestBuilder<ControlloAvvisi>(15, TimeUnit.MINUTES).setConstraints(vincoli).build()
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(LAVORO, politica, lavoro)
    }

    /** Chiede gli avvisi nuovi e li mostra (ad app aperta li mostra già la pagina). */
    suspend fun controlla(c: Context) = withContext(Dispatchers.IO) {
        val s = sp(c)
        val server = s.getString("server", null) ?: return@withContext
        val gettone = s.getString("gettone", null) ?: return@withContext
        val ultimo = s.getLong("ultimo", 0L)
        val corpo = JSONObject().put("azione", "apk_eventi").put("gettone", gettone).put("da", ultimo).toString()
        val con = (URL(server).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            requestMethod = "POST"
            doOutput = true
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            con.outputStream.use { it.write(corpo.toByteArray()) }
            val codice = con.responseCode
            if (codice == 403) {
                // Dispositivo tolto o utente bloccato: il gettone non vale più.
                spegni(c)
                return@withContext
            }
            if (codice != 200) return@withContext
            val r = JSONObject(con.inputStream.bufferedReader().use { it.readText() })
            val eventi = r.optJSONArray("eventi") ?: return@withContext
            var max = ultimo
            for (i in 0 until eventi.length()) {
                val e = eventi.getJSONObject(i)
                val id = e.optLong("id")
                if (id > max) max = id
                if (!Aggiornamenti.inPrimoPiano) notifica(c, id, e.optString("tipo"), e.optString("titolo"), e.optString("testo"))
            }
            if (max > ultimo) s.edit().putLong("ultimo", max).apply()
        } finally {
            con.disconnect()
        }
    }

    private fun notifica(c: Context, id: Long, tipo: String, titolo: String, testo: String) {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CANALE, "Avvisi dell'account", NotificationManager.IMPORTANCE_HIGH))
        val apri = Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val b = NotificationCompat.Builder(c, CANALE)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(titolo.ifEmpty { "NutriGenius" })
            .setContentText(testo)
            .setStyle(NotificationCompat.BigTextStyle().bigText(testo))
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(c, 5000, apri, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        // Lo stesso tipo si sostituisce (come il «tag» del Web Push), tranne le richieste di accesso.
        val nid = if (tipo == "accesso") 5100 + (id % 100).toInt() else 5000 + (tipo.hashCode() and 0x3f)
        nm.notify(nid, b.build())
    }
}

class ControlloAvvisi(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result {
        runCatching { Avvisi.controlla(applicationContext) }
        return Result.success()
    }
}

object Salute {
    private const val FILE = "salute"
    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    @Volatile private var utente = 0L
    @Volatile private var spazio = 0L

    fun imposta(c: Context, u: Long, s: Long) {
        utente = u
        spazio = s
    }

    /** Il proprietario dei dati di Connessione Salute su questo telefono (0: nessuno). */
    fun proprietario(c: Context): Long = sp(c).getLong("proprietario", 0L)

    fun dimentica(c: Context) = sp(c).edit().remove("proprietario").apply()

    /**
     * Si possono leggere o scrivere i dati di Connessione Salute adesso? Solo nel
     * proprio spazio e solo per il proprietario; il primo che li usa lo diventa.
     * Senza utente (una pagina che non lo dice) si lascia fare, come prima.
     */
    fun consentita(c: Context): Boolean {
        val u = utente
        if (u == 0L) return true
        if (spazio != 0L && spazio != u) return false
        val p = proprietario(c)
        if (p == 0L) {
            sp(c).edit().putLong("proprietario", u).apply()
            return true
        }
        return p == u
    }

    fun descrizione(c: Context): String {
        val p = proprietario(c)
        return if (p == 0L) "Connessione Salute: nessun proprietario ancora" else "Connessione Salute: dati dell'utente n. $p"
    }
}
