package com.example.nutrigeniusbridge

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * I widget della schermata Home (1.6.0, #156): acqua, lista della spesa, prossimi pasti.
 *
 * Tutto si disegna da dati già pronti tenuti sul telefono, senza rete e senza WebView:
 * - li scrive la pagina a ogni cambiamento, con l'app aperta (ponte «widgetDati»);
 * - ad app chiusa li chiede al server il lavoro periodico di 15 minuti, con il gettone;
 * - una bevuta segnata dal widget entra subito nel conto (coda sul disco) e si manda al
 *   server appena c'è rete, con un id che non la fa contare due volte.
 *
 * I widget mostrano sempre i dati del proprio account, mai quelli di uno spazio delegato.
 */
object DatiWidget {
    private const val FILE = "widget"
    private const val LAVORO_INVIO = "widget-invio"
    private const val LAVORO_AGGIORNA = "widget-aggiorna"
    private const val MAX_CODA = 200
    /** Aprendo una finestra si chiede l'aggiornamento al server, ma non più spesso di così. */
    private const val INTERVALLO_LETTURA_MS = 5 * 60_000L

    const val AZIONE_BEVI = "com.example.nutrigeniusbridge.WIDGET_BEVI"
    const val EXTRA_TIPO = "tipo"
    const val CHIAVE_IL = "il"

    private val blocco = Any()
    fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val provider = listOf(WidgetAcqua::class.java, WidgetSpesa::class.java, WidgetPasti::class.java)

    /** C'è almeno un widget sulla schermata Home? Senza, non si calcola e non si scarica niente. */
    fun presenti(c: Context): Boolean {
        val m = AppWidgetManager.getInstance(c)
        return provider.any { m.getAppWidgetIds(ComponentName(c, it)).isNotEmpty() }
    }

    fun ridisegna(c: Context) {
        WidgetAcqua.disegna(c)
        WidgetSpesa.disegna(c)
        WidgetPasti.disegna(c)
    }

    // ------------------------------------------------------------------ i dati

    private fun salva(c: Context, d: JSONObject, versione: Long?) {
        if (d.optInt("v") != 1) return
        synchronized(blocco) {
            val e = sp(c).edit()
                .putLong(CHIAVE_IL, d.optLong("il"))
                .putString("acqua", d.optJSONObject("acqua")?.toString())
                .putString("spesa", d.optJSONObject("spesa")?.toString())
                .putString("pasti", d.optJSONArray("pasti")?.toString() ?: "[]")
            if (versione != null) e.putLong("versione", versione) else e.remove("versione")
            e.commit()
        }
        ridisegna(c)
    }

    /** Dalla pagina: solo i dati del proprio spazio. */
    fun daPagina(c: Context, json: String) {
        if (!Salute.nelProprioSpazio()) return
        runCatching { salva(c, JSONObject(json), null) }
    }

    /** All'uscita dall'account i dati spariscono dal telefono, anche quelli non ancora inviati. */
    fun azzera(c: Context) {
        synchronized(blocco) { sp(c).edit().clear().commit() }
        WorkManager.getInstance(c).cancelUniqueWork(LAVORO_INVIO)
        WorkManager.getInstance(c).cancelUniqueWork(LAVORO_AGGIORNA)
        ridisegna(c)
    }

    fun collegato(c: Context): Boolean = sp(c).contains("acqua")

    private fun oggetto(c: Context, chiave: String): JSONObject? =
        sp(c).getString(chiave, null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    fun spesa(c: Context): JSONObject? = oggetto(c, "spesa")

    /** I prossimi pasti: quelli già finiti escono da soli, anche se i dati sono di ore fa. */
    fun prossimiPasti(c: Context, quanti: Int): List<JSONObject> {
        val a = runCatching { JSONArray(sp(c).getString("pasti", "[]")) }.getOrElse { JSONArray() }
        val ora = System.currentTimeMillis()
        return (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optLong("fine") > ora }.take(quanti)
    }

    data class Acqua(val attiva: Boolean, val ml: Int, val target: Int, val glass: Int, val flask: Int, val bottle: Int)

    /** L'acqua di oggi: quella dei dati, se sono di oggi, più le bevute non ancora inviate. */
    fun acqua(c: Context): Acqua? {
        val a = oggetto(c, "acqua") ?: return null
        val oggi = LocalDate.now().toString()
        val base = if (a.optString("giorno") == oggi) a.optInt("ml") else 0
        val coda = coda(c)
        var inCoda = 0
        for (i in 0 until coda.length()) {
            val b = coda.getJSONObject(i)
            if (b.optString("at").startsWith(oggi)) inCoda += b.optInt("ml")
        }
        return Acqua(a.optBoolean("attiva"), base + inCoda, a.optInt("target", 2000), a.optInt("glass", 200), a.optInt("flask", 500), a.optInt("bottle", 1000))
    }

    private fun coda(c: Context): JSONArray =
        runCatching { JSONArray(sp(c).getString("coda", "[]")) }.getOrElse { JSONArray() }

    // ------------------------------------------------------------------ le bevute

    /** Una bevuta: sul disco subito, così il widget si ridisegna senza aspettare la rete. */
    fun bevi(c: Context, tipo: String) {
        synchronized(blocco) {
            val a = oggetto(c, "acqua") ?: return
            if (!a.optBoolean("attiva")) return
            val ml = a.optInt(tipo)
            if (ml <= 0) return
            val q = coda(c)
            if (q.length() >= MAX_CODA) return
            val at = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            q.put(JSONObject().put("id", UUID.randomUUID().toString()).put("tipo", tipo).put("ml", ml).put("at", at))
            sp(c).edit().putString("coda", q.toString()).commit()
        }
        programmaInvio(c)
    }

    /** L'invio aspetta la rete; tocchi ravvicinati partono insieme. */
    private fun programmaInvio(c: Context) {
        val vincoli = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val lavoro = OneTimeWorkRequestBuilder<InvioWidget>()
            .setConstraints(vincoli)
            .setInitialDelay(2, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(c).enqueueUniqueWork(LAVORO_INVIO, ExistingWorkPolicy.REPLACE, lavoro)
    }

    /** true se non resta niente da fare, false se conviene riprovare. */
    suspend fun invia(c: Context): Boolean = withContext(Dispatchers.IO) {
        val daInviare = synchronized(blocco) { coda(c) }
        if (daInviare.length() == 0) return@withContext true
        val (server, gettone) = Avvisi.credenziali(c) ?: return@withContext true
        val corpo = JSONObject().put("azione", "apk_acqua").put("gettone", gettone).put("bevute", daInviare).toString()
        val (codice, testo) = runCatching { Avvisi.chiama(server, corpo) }.getOrElse { return@withContext false }
        if (codice == 403) {
            Avvisi.spegni(c)
            return@withContext true
        }
        if (codice != 200) return@withContext false
        val r = runCatching { JSONObject(testo) }.getOrElse { return@withContext false }
        val ricevute = r.optJSONArray("ricevute") ?: JSONArray()
        val inviate = (0 until ricevute.length()).map { ricevute.getString(it) }.toSet()
        synchronized(blocco) {
            val vecchia = coda(c)
            val resta = JSONArray()
            for (i in 0 until vecchia.length()) {
                val b = vecchia.getJSONObject(i)
                if (b.optString("id") !in inviate) resta.put(b)
            }
            val e = sp(c).edit().putString("coda", resta.toString())
            // Il totale del server, che comprende quelle appena arrivate.
            oggetto(c, "acqua")?.let { a ->
                a.put("giorno", r.optString("giorno", a.optString("giorno")))
                a.put("ml", r.optInt("ml", a.optInt("ml")))
                e.putString("acqua", a.toString())
            }
            e.commit()
        }
        ridisegna(c)
        true
    }

    // ------------------------------------------------------------------ dal server

    /** I dati aggiornati dal server, ad app chiusa. */
    suspend fun aggiorna(c: Context) = withContext(Dispatchers.IO) {
        val (server, gettone) = Avvisi.credenziali(c) ?: return@withContext
        val s = sp(c)
        val corpo = JSONObject().put("azione", "apk_widget").put("gettone", gettone)
            .put("versione", s.getLong("versione", -1L)).put("il", s.getLong(CHIAVE_IL, 0L)).toString()
        val (codice, testo) = Avvisi.chiama(server, corpo)
        if (codice == 403) {
            Avvisi.spegni(c)
            return@withContext
        }
        if (codice != 200) return@withContext
        s.edit().putLong("letto", System.currentTimeMillis()).apply()
        val r = JSONObject(testo)
        r.optJSONObject("dati")?.let { salva(c, it, r.optLong("versione")) }
    }

    /** Chiede i dati al server in background, se non l'ha fatto da poco. */
    fun chiediAggiornamento(c: Context) {
        if (System.currentTimeMillis() - sp(c).getLong("letto", 0L) < INTERVALLO_LETTURA_MS) return
        val vincoli = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val lavoro = OneTimeWorkRequestBuilder<AggiornaWidget>().setConstraints(vincoli).build()
        WorkManager.getInstance(c).enqueueUniqueWork(LAVORO_AGGIORNA, ExistingWorkPolicy.KEEP, lavoro)
    }

    // ------------------------------------------------------------------ aprire l'app

    fun intentApp(c: Context, query: String): Intent = Intent(c, MainActivity::class.java).apply {
        action = Intent.ACTION_VIEW
        putExtra(MainActivity.EXTRA_AZIONE, MainActivity.AZIONE_WIDGET)
        putExtra(MainActivity.EXTRA_WIDGET, query)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    fun queryPasto(data: String, pasto: String) = "widget=pasto&data=${Uri.encode(data)}&pasto=${Uri.encode(pasto)}"

    fun queryAlimento(data: String, pasto: String, log: String) =
        "widget=alimento&data=${Uri.encode(data)}&pasto=${Uri.encode(pasto)}&log=${Uri.encode(log)}"

    /** «Oggi», «Domani», «Martedì 11 novembre». */
    fun giornoInParole(data: String): String {
        val d = runCatching { LocalDate.parse(data) }.getOrNull() ?: return data
        val oggi = LocalDate.now()
        return when (d) {
            oggi -> "Oggi"
            oggi.plusDays(1) -> "Domani"
            else -> d.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ITALIAN)).replaceFirstChar { it.uppercase() }
        }
    }

    fun nomePasto(p: String) = p.replaceFirstChar { it.uppercase() }
}

class InvioWidget(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result = if (DatiWidget.invia(applicationContext)) Result.success() else Result.retry()
}

class AggiornaWidget(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result {
        runCatching { DatiWidget.aggiorna(applicationContext) }
        return Result.success()
    }
}

private fun apriApp(c: Context, codice: Int, query: String): PendingIntent = PendingIntent.getActivity(
    c, codice, DatiWidget.intentApp(c, query), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
)

private fun apriNormale(c: Context, codice: Int): PendingIntent = PendingIntent.getActivity(
    c, codice, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
)

private fun apriFinestra(c: Context, codice: Int, attivita: Class<*>): PendingIntent = PendingIntent.getActivity(
    c, codice, Intent(c, attivita).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
)

private fun aggiornaTutti(c: Context, provider: Class<*>, viste: RemoteViews) {
    val m = AppWidgetManager.getInstance(c)
    val ids = m.getAppWidgetIds(ComponentName(c, provider))
    if (ids.isNotEmpty()) m.updateAppWidget(ids, viste)
}

class WidgetAcqua : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        disegna(c)
        DatiWidget.chiediAggiornamento(c)
    }

    override fun onReceive(c: Context, i: Intent) {
        if (i.action == DatiWidget.AZIONE_BEVI) {
            DatiWidget.bevi(c, i.getStringExtra(DatiWidget.EXTRA_TIPO).orEmpty())
            disegna(c)
            return
        }
        super.onReceive(c, i)
    }

    companion object {
        private val CONTENITORI = listOf(
            Triple("glass", R.id.acqua_glass, R.id.acqua_glass_ml),
            Triple("bottle", R.id.acqua_bottle, R.id.acqua_bottle_ml),
            Triple("flask", R.id.acqua_flask, R.id.acqua_flask_ml),
        )

        private fun bevi(c: Context, tipo: String, codice: Int): PendingIntent = PendingIntent.getBroadcast(
            c, codice, Intent(c, WidgetAcqua::class.java).setAction(DatiWidget.AZIONE_BEVI).putExtra(DatiWidget.EXTRA_TIPO, tipo),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        fun disegna(c: Context) {
            val v = RemoteViews(c.packageName, R.layout.widget_acqua)
            val a = DatiWidget.acqua(c)
            if (a == null || !a.attiva) {
                v.setTextViewText(R.id.acqua_testo, if (a == null) "Apri NutriGenius per collegare il widget" else "Acqua non attiva nelle impostazioni")
                v.setTextViewText(R.id.acqua_percento, "")
                v.setProgressBar(R.id.acqua_livello, 100, 0, false)
                val apri = apriNormale(c, 7000)
                v.setOnClickPendingIntent(R.id.acqua_totale, apri)
                for ((_, bottone, testo) in CONTENITORI) {
                    v.setOnClickPendingIntent(bottone, apri)
                    v.setTextViewText(testo, "—")
                }
            } else {
                val massimo = maxOf(a.target, 1)
                val raggiunto = a.ml >= massimo
                v.setTextViewText(R.id.acqua_testo, "${a.ml} / ${a.target} ml")
                v.setTextViewText(R.id.acqua_percento, "${a.ml * 100 / massimo}%")
                v.setProgressBar(R.id.acqua_livello, massimo, minOf(a.ml, massimo), false)
                v.setColorStateList(R.id.acqua_livello, "setProgressTintList", ColorStateList.valueOf(Color.parseColor(if (raggiunto) "#10B981" else "#3B82F6")))
                v.setOnClickPendingIntent(R.id.acqua_totale, apriApp(c, 7001, "widget=acqua"))
                CONTENITORI.forEachIndexed { n, (tipo, bottone, testo) ->
                    v.setOnClickPendingIntent(bottone, bevi(c, tipo, 7010 + n))
                    v.setTextViewText(testo, "${when (tipo) { "glass" -> a.glass; "bottle" -> a.bottle; else -> a.flask }} ml")
                }
            }
            aggiornaTutti(c, WidgetAcqua::class.java, v)
        }
    }
}

class WidgetSpesa : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        disegna(c)
        DatiWidget.chiediAggiornamento(c)
    }

    companion object {
        fun disegna(c: Context) {
            val v = RemoteViews(c.packageName, R.layout.widget_spesa)
            val righe = DatiWidget.spesa(c)?.optJSONArray("sezioni")?.optJSONObject(0)?.optJSONArray("righe")
            val daFare = if (righe == null) emptyList() else (0 until righe.length()).map { righe.getJSONObject(it) }.filter { !it.optBoolean("fatto") }
            v.setTextViewText(R.id.spesa_numero, when {
                !DatiWidget.collegato(c) -> "Apri NutriGenius per collegare il widget"
                daFare.isEmpty() -> "Niente da comprare"
                daFare.size == 1 -> "1 da comprare"
                else -> "${daFare.size} da comprare"
            })
            v.setTextViewText(R.id.spesa_anteprima, daFare.take(4).joinToString(", ") { it.optString("nome") })
            v.setViewVisibility(R.id.spesa_anteprima, if (daFare.isEmpty()) View.GONE else View.VISIBLE)
            v.setOnClickPendingIntent(R.id.spesa_radice, if (DatiWidget.collegato(c)) apriFinestra(c, 7020, FinestraSpesa::class.java) else apriNormale(c, 7021))
            aggiornaTutti(c, WidgetSpesa::class.java, v)
        }
    }
}

class WidgetPasti : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        disegna(c)
        DatiWidget.chiediAggiornamento(c)
    }

    companion object {
        fun disegna(c: Context) {
            val v = RemoteViews(c.packageName, R.layout.widget_pasti)
            val prossimo = DatiWidget.prossimiPasti(c, 1).firstOrNull()
            v.setTextViewText(R.id.pasti_prossimo, when {
                !DatiWidget.collegato(c) -> "Apri NutriGenius per collegare il widget"
                prossimo == null -> "Nessun pasto in programma"
                else -> "${DatiWidget.nomePasto(prossimo.optString("pasto"))} · ${DatiWidget.giornoInParole(prossimo.optString("data"))}"
            })
            val voci = prossimo?.optJSONArray("voci")
            val nomi = if (voci == null) "" else (0 until minOf(voci.length(), 4)).joinToString(", ") { voci.getJSONObject(it).optString("nome") }
            v.setTextViewText(R.id.pasti_voci, nomi)
            v.setViewVisibility(R.id.pasti_voci, if (nomi.isEmpty()) View.GONE else View.VISIBLE)
            v.setOnClickPendingIntent(R.id.pasti_radice, if (DatiWidget.collegato(c)) apriFinestra(c, 7030, FinestraPasti::class.java) else apriNormale(c, 7031))
            aggiornaTutti(c, WidgetPasti::class.java, v)
        }
    }
}
