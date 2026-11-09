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
import android.util.SizeF
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
import java.time.LocalTime
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
    private val MARCHE = intArrayOf(R.id.marca_0, R.id.marca_1, R.id.marca_2, R.id.marca_3, R.id.marca_4, R.id.marca_5, R.id.marca_6, R.id.marca_7, R.id.marca_8, R.id.marca_9, R.id.marca_10, R.id.marca_11, R.id.marca_12, R.id.marca_13, R.id.marca_14, R.id.marca_15, R.id.marca_16, R.id.marca_17, R.id.marca_18, R.id.marca_19, R.id.marca_20)
    /** Chi usa le finestre solo dal menu dell'icona ha i dati aggiornati per un mese dall'ultima volta. */
    private const val FINESTRA_VALE_MS = 30 * 86_400_000L
    /** Aprendo una finestra si chiede l'aggiornamento al server, ma non più spesso di così. */
    private const val INTERVALLO_LETTURA_MS = 5 * 60_000L

    const val AZIONE_BEVI = "com.example.nutrigeniusbridge.WIDGET_BEVI"
    const val AZIONE_SPUNTA = "com.example.nutrigeniusbridge.WIDGET_SPUNTA"
    const val EXTRA_TIPO = "tipo"
    const val EXTRA_FOOD = "food"
    const val CHIAVE_IL = "il"

    private val blocco = Any()
    fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val provider = listOf(
        WidgetAcqua::class.java, WidgetAcquaUno::class.java, WidgetAcquaDue::class.java,
        WidgetSpesa::class.java, WidgetSpesaUno::class.java, WidgetPasti::class.java, WidgetPastiUno::class.java,
        WidgetIcona::class.java,
    )

    /** C'è almeno un widget, o si usano le finestre dal menu dell'icona? Senza, non si calcola e non si scarica niente. */
    fun presenti(c: Context): Boolean {
        val m = AppWidgetManager.getInstance(c)
        if (provider.any { m.getAppWidgetIds(ComponentName(c, it)).isNotEmpty() }) return true
        return System.currentTimeMillis() - sp(c).getLong("finestra", 0L) < FINESTRA_VALE_MS
    }

    fun segnaFinestraUsata(c: Context) = sp(c).edit().putLong("finestra", System.currentTimeMillis()).apply()

    fun ridisegnaAcqua(c: Context) {
        WidgetAcqua.disegna(c)
        WidgetAcquaUno.disegna(c)
        WidgetAcquaDue.disegna(c)
    }

    /** Mette i dati sulla barra: livello, triangolino e colore (rosso se sei indietro, come nell'app). */
    fun livello(v: RemoteViews, a: Acqua) {
        val massimo = maxOf(a.target, 1)
        v.setProgressBar(R.id.acqua_livello, massimo, minOf(a.ml, massimo), false)
        val atteso = a.atteso()
        v.setColorStateList(R.id.acqua_livello, "setProgressTintList", ColorStateList.valueOf(Color.parseColor(when {
            a.ml >= massimo -> "#10B981"
            atteso != null && a.ml < atteso -> "#F87171"
            else -> "#3B82F6"
        })))
        if (atteso != null) v.setViewVisibility(MARCHE[Math.round(atteso / massimo * (MARCHE.size - 1)).toInt().coerceIn(0, MARCHE.size - 1)], View.VISIBLE)
    }

    fun badge(v: RemoteViews, id: Int, n: Int) {
        v.setTextViewText(id, "$n")
        v.setViewVisibility(id, if (n > 0) View.VISIBLE else View.GONE)
    }

    fun ridisegnaSpesa(c: Context) {
        WidgetSpesa.disegna(c)
        WidgetSpesaUno.disegna(c)
    }

    fun ridisegna(c: Context) {
        ridisegnaAcqua(c)
        ridisegnaSpesa(c)
        WidgetPasti.disegna(c)
        WidgetPastiUno.disegna(c)
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

    /** La lista con sopra le operazioni ancora in coda. */
    fun spesa(c: Context): JSONObject? {
        val s = oggetto(c, "spesa") ?: return null
        val q = codaSpesa(c)
        for (i in 0 until q.length()) runCatching { SpesaWidget.applica(s, q.getJSONObject(i)) }
        return s
    }

    private fun codaSpesa(c: Context): JSONArray =
        runCatching { JSONArray(sp(c).getString("coda_spesa", "[]")) }.getOrElse { JSONArray() }

    /** L'operazione della spunta, con la quantità tonda proposta dall'app. */
    fun opMetti(r: JSONObject, lunga: Boolean): JSONObject = JSONObject().put("op", "metti")
        .put("acquisto", "acq-${UUID.randomUUID()}").put("foodId", r.optString("foodId")).put("nome", r.optString("nome"))
        .put("quantita", r.optDouble("proposta", r.optDouble("daComprare", 0.0))).put("lunga", lunga)

    /**
     * Un'operazione sulla lista: sul disco subito, così la finestra e il widget la mostrano
     * senza rete. Le correzioni consecutive della stessa quantità ne diventano una sola.
     */
    fun opSpesa(c: Context, o: JSONObject) {
        synchronized(blocco) {
            val menu = oggetto(c, "spesa")?.optString("menuId").orEmpty()
            if (menu.isEmpty()) return
            o.put("id", UUID.randomUUID().toString()).put("menu", menu)
            if (!o.has("il")) o.put("il", System.currentTimeMillis())
            val q = codaSpesa(c)
            if (q.length() >= MAX_CODA) return
            val tieni = JSONArray()
            for (i in 0 until q.length()) {
                val x = q.getJSONObject(i)
                if (o.optString("op") == "cambia" && x.optString("op") == "cambia" && x.optString("acquisto") == o.optString("acquisto")) {
                    for (k in listOf("quantita", "lunga")) if (!o.has(k) && x.has(k)) o.put(k, x.get(k))
                } else {
                    tieni.put(x)
                }
            }
            tieni.put(o)
            sp(c).edit().putString("coda_spesa", tieni.toString()).commit()
        }
        programmaInvio(c)
        ridisegnaSpesa(c)
    }

    /** Dal widget: spunta o togli la spunta di un prodotto da comprare. */
    fun spunta(c: Context, foodId: String) {
        if (foodId.isEmpty()) return
        val r = SpesaWidget.riga(spesa(c), 0, foodId) ?: return
        val carrello = r.optJSONObject("carrello")
        if (carrello != null) opSpesa(c, JSONObject().put("op", "togli").put("acquisto", carrello.optString("id")))
        else opSpesa(c, opMetti(r, false))
    }

    /** I prossimi pasti: quelli già finiti escono da soli, anche se i dati sono di ore fa. */
    fun prossimiPasti(c: Context, quanti: Int): List<JSONObject> {
        val a = runCatching { JSONArray(sp(c).getString("pasti", "[]")) }.getOrElse { JSONArray() }
        val ora = System.currentTimeMillis()
        return (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optLong("fine") > ora }.take(quanti)
    }

    data class Acqua(
        val attiva: Boolean, val ml: Int, val target: Int, val glass: Int, val flask: Int, val bottle: Int,
        val usatoGlass: Int, val usatoFlask: Int, val usatoBottle: Int,
        val gestisci: Boolean, val inizio: String?, val fine: String?,
        /** Dati con il livello atteso: quelli della 1.7.0 non l'hanno. */
        val completa: Boolean,
    ) {
        fun misura(t: String) = when (t) { "bottle" -> bottle; "flask" -> flask; else -> glass }
        fun usato(t: String) = when (t) { "bottle" -> usatoBottle; "flask" -> usatoFlask; else -> usatoGlass }
        /** Le porzioni di quel contenitore bevute oggi, come sulla tendina dell'app. */
        fun porzioni(t: String): Int = if (misura(t) > 0) Math.round(usato(t).toDouble() / misura(t)).toInt() else 0

        private fun minuti(s: String?, predefinito: String): Int {
            val p = (s?.takeIf { it.isNotEmpty() } ?: predefinito).split(":")
            return (p.getOrNull(0)?.toIntOrNull() ?: 0) * 60 + (p.getOrNull(1)?.toIntOrNull() ?: 0)
        }

        /** Quanto dovresti aver bevuto adesso, come il triangolino dell'app; null se non è attivo. */
        fun atteso(): Double? {
            if (!gestisci || target <= 0) return null
            val ora = LocalTime.now()
            val adesso = ora.hour * 60 + ora.minute
            val i = minuti(inizio, "08:00")
            val f = minuti(fine, "22:00")
            return when {
                adesso >= f -> target.toDouble()
                adesso <= i -> 0.0
                else -> target.toDouble() * (adesso - i) / (f - i)
            }
        }

        /** Le fasce del promemoria: quelle predefinite non sono le stesse del triangolino, come nell'app. */
        fun inizioMs() = minuti(inizio, "8:00") * 60_000L
        fun fineMs() = minuti(fine, "20:00") * 60_000L
    }

    /** L'acqua di oggi: quella dei dati, se sono di oggi, più le bevute non ancora inviate. */
    fun acqua(c: Context): Acqua? {
        val a = oggetto(c, "acqua") ?: return null
        val oggi = LocalDate.now().toString()
        val diOggi = a.optString("giorno") == oggi
        val base = if (diOggi) a.optInt("ml") else 0
        val usati = if (diOggi) a.optJSONObject("usati") else null
        val coda = coda(c)
        var inCoda = 0
        val perTipo = HashMap<String, Int>()
        for (i in 0 until coda.length()) {
            val b = coda.getJSONObject(i)
            if (b.optString("at").startsWith(oggi)) {
                inCoda += b.optInt("ml")
                perTipo[b.optString("tipo")] = (perTipo[b.optString("tipo")] ?: 0) + b.optInt("ml")
            }
        }
        fun usato(t: String) = (usati?.optInt(t) ?: 0) + (perTipo[t] ?: 0)
        return Acqua(
            a.optBoolean("attiva"), base + inCoda, a.optInt("target", 2000), a.optInt("glass", 200), a.optInt("flask", 500), a.optInt("bottle", 1000),
            usato("glass"), usato("flask"), usato("bottle"),
            a.optBoolean("gestisci"), a.optString("inizio").takeIf { a.has("inizio") && !a.isNull("inizio") }, a.optString("fine").takeIf { a.has("fine") && !a.isNull("fine") },
            a.has("usati"),
        )
    }

    /** Il contenitore del menu rapido: l'ultimo bevuto o scelto, qui o nell'app. */
    fun tipoRapido(c: Context): String {
        val locale = sp(c).getString("rapido", null)
        val il = sp(c).getLong("rapido_il", 0L)
        val a = oggetto(c, "acqua")
        val altrove = a?.takeIf { !it.isNull("ultimo") }?.optString("ultimo")?.takeIf { it.isNotEmpty() }
        val ilAltrove = a?.optLong("ultimoIl") ?: 0L
        return if (locale != null && (altrove == null || il >= ilAltrove)) locale else altrove ?: "glass"
    }

    fun scegliRapido(c: Context, tipo: String) = sp(c).edit().putString("rapido", tipo).putLong("rapido_il", System.currentTimeMillis()).apply()

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
            sp(c).edit().putString("coda", q.toString()).putString("rapido", tipo).putLong("rapido_il", System.currentTimeMillis()).commit()
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
    suspend fun invia(c: Context): Boolean {
        val acqua = inviaAcqua(c)
        val lista = inviaSpesa(c)
        return acqua && lista
    }

    private suspend fun inviaSpesa(c: Context): Boolean = withContext(Dispatchers.IO) {
        val daInviare = synchronized(blocco) { codaSpesa(c) }
        if (daInviare.length() == 0) return@withContext true
        val (server, gettone) = Avvisi.credenziali(c) ?: return@withContext true
        val corpo = JSONObject().put("azione", "apk_spesa").put("gettone", gettone).put("ops", daInviare).toString()
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
            val vecchia = codaSpesa(c)
            val resta = JSONArray()
            for (i in 0 until vecchia.length()) {
                val b = vecchia.getJSONObject(i)
                if (b.optString("id") !in inviate) resta.put(b)
            }
            sp(c).edit().putString("coda_spesa", resta.toString()).commit()
        }
        val dati = r.optJSONObject("dati")
        if (dati != null) salva(c, dati, r.optLong("versione")) else ridisegnaSpesa(c)
        true
    }

    private suspend fun inviaAcqua(c: Context): Boolean = withContext(Dispatchers.IO) {
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
                r.optJSONObject("usati")?.let { a.put("usati", it) }
                if (r.has("ultimo")) {
                    a.put("ultimo", r.opt("ultimo"))
                    a.put("ultimoIl", r.optLong("ultimoIl"))
                }
                e.putString("acqua", a.toString())
            }
            e.commit()
        }
        ridisegnaAcqua(c)
        true
    }

    // ------------------------------------------------------------------ dal server

    /** I dati aggiornati dal server, ad app chiusa. */
    suspend fun aggiorna(c: Context) = withContext(Dispatchers.IO) {
        val (server, gettone) = Avvisi.credenziali(c) ?: return@withContext
        val s = sp(c)
        // Dati del formato di prima (1.6.0): si chiedono comunque quelli nuovi.
        val vecchi = oggetto(c, "spesa")?.optJSONArray("sezioni")?.optJSONObject(0)?.optJSONArray("righe")?.optJSONObject(0)?.has("foodId") == false
        val corpo = JSONObject().put("azione", "apk_widget").put("gettone", gettone)
            .put("versione", if (vecchi) -1L else s.getLong("versione", -1L)).put("il", s.getLong(CHIAVE_IL, 0L)).toString()
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

private fun beviPi(c: Context, tipo: String): PendingIntent = PendingIntent.getBroadcast(
    c, 7010 + listOf("glass", "bottle", "flask").indexOf(tipo),
    Intent(c, WidgetAcqua::class.java).setAction(DatiWidget.AZIONE_BEVI).putExtra(DatiWidget.EXTRA_TIPO, tipo),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
)

/** Il pulsante unico: a scelta, con la conferma se chiesta. Cambiare con la matita del widget. */
class WidgetAcquaUno : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        disegna(c)
        DatiWidget.chiediAggiornamento(c)
    }

    override fun onDeleted(c: Context, ids: IntArray) = ConfAcqua.togli(c, ids)

    companion object {
        fun disegna(c: Context) {
            val m = AppWidgetManager.getInstance(c)
            val a = DatiWidget.acqua(c)
            for (id in m.getAppWidgetIds(ComponentName(c, WidgetAcquaUno::class.java))) {
                val v = RemoteViews(c.packageName, R.layout.widget_acqua_uno)
                val tipo = ConfAcqua.tipo(c, id)
                v.setImageViewResource(R.id.uno_icona, ConfAcqua.icona(tipo))
                if (a == null || !a.attiva) {
                    v.setTextViewText(R.id.uno_testo, "—")
                    v.setProgressBar(R.id.acqua_livello, 100, 0, false)
                    v.setOnClickPendingIntent(R.id.uno_radice, apriNormale(c, 7300 + id))
                } else {
                    v.setTextViewText(R.id.uno_testo, "${a.ml}")
                    DatiWidget.livello(v, a)
                    DatiWidget.badge(v, R.id.uno_badge, a.porzioni(tipo))
                    v.setOnClickPendingIntent(R.id.uno_radice, if (ConfAcqua.conferma(c, id)) PendingIntent.getActivity(
                        c, 7300 + id, Intent(c, ConfermaAcqua::class.java).putExtra(DatiWidget.EXTRA_TIPO, tipo).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    ) else beviPi(c, tipo))
                }
                m.updateAppWidget(id, v)
            }
        }
    }
}

/** Il formato 2x1: aggiungi e la quantità del prossimo aggiungi. */
class WidgetAcquaDue : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        disegna(c)
        DatiWidget.chiediAggiornamento(c)
    }

    override fun onDeleted(c: Context, ids: IntArray) = ConfAcqua.togli(c, ids)

    companion object {
        fun disegna(c: Context) {
            val m = AppWidgetManager.getInstance(c)
            val a = DatiWidget.acqua(c)
            for (id in m.getAppWidgetIds(ComponentName(c, WidgetAcquaDue::class.java))) {
                val v = RemoteViews(c.packageName, R.layout.widget_acqua_due)
                val tipo = ConfAcqua.tipo(c, id)
                v.setImageViewResource(R.id.due_icona, ConfAcqua.icona(tipo))
                if (a == null || !a.attiva) {
                    v.setTextViewText(R.id.acqua_testo, if (a == null) "Apri NutriGenius" else "Acqua non attiva")
                    v.setTextViewText(R.id.due_ml, "—")
                    v.setProgressBar(R.id.acqua_livello, 100, 0, false)
                    val apri = apriNormale(c, 7400 + id)
                    v.setOnClickPendingIntent(R.id.acqua_totale, apri)
                    v.setOnClickPendingIntent(R.id.due_aggiungi, apri)
                    v.setOnClickPendingIntent(R.id.due_scegli, apri)
                } else {
                    v.setTextViewText(R.id.acqua_testo, "${a.ml} / ${a.target} ml")
                    v.setTextViewText(R.id.due_ml, "+${ConfAcqua.ml(a, tipo)}")
                    DatiWidget.livello(v, a)
                    DatiWidget.badge(v, R.id.due_badge, a.porzioni(tipo))
                    v.setOnClickPendingIntent(R.id.acqua_totale, apriApp(c, 7001, "widget=acqua"))
                    v.setOnClickPendingIntent(R.id.due_aggiungi, beviPi(c, tipo))
                    v.setOnClickPendingIntent(R.id.due_scegli, PendingIntent.getActivity(
                        c, 7400 + id, Intent(c, ScegliAcqua::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    ))
                }
                m.updateAppWidget(id, v)
            }
        }
    }
}

class WidgetAcqua : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        disegna(c)
        DatiWidget.chiediAggiornamento(c)
    }

    override fun onReceive(c: Context, i: Intent) {
        if (i.action == DatiWidget.AZIONE_BEVI) {
            DatiWidget.bevi(c, i.getStringExtra(DatiWidget.EXTRA_TIPO).orEmpty())
            DatiWidget.ridisegnaAcqua(c)
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
                v.setTextViewText(R.id.acqua_testo, "${a.ml} / ${a.target} ml")
                v.setTextViewText(R.id.acqua_percento, "${a.ml * 100 / massimo}%")
                DatiWidget.livello(v, a)
                DatiWidget.badge(v, R.id.acqua_glass_badge, a.porzioni("glass"))
                DatiWidget.badge(v, R.id.acqua_bottle_badge, a.porzioni("bottle"))
                DatiWidget.badge(v, R.id.acqua_flask_badge, a.porzioni("flask"))
                v.setOnClickPendingIntent(R.id.acqua_totale, apriApp(c, 7001, "widget=acqua"))
                CONTENITORI.forEach { (tipo, bottone, testo) ->
                    v.setOnClickPendingIntent(bottone, beviPi(c, tipo))
                    v.setTextViewText(testo, "${when (tipo) { "glass" -> a.glass; "bottle" -> a.bottle; else -> a.flask }} ml")
                }
            }
            aggiornaTutti(c, WidgetAcqua::class.java, v)
        }
    }
}

/** Quanti prodotti restano da comprare (senza quelli già nel carrello). */
private fun daComprare(c: Context): List<JSONObject> {
    val a = DatiWidget.spesa(c)?.optJSONArray("sezioni")?.optJSONObject(0)?.optJSONArray("righe") ?: return emptyList()
    return (0 until a.length()).map { a.getJSONObject(it) }.filter { !it.has("carrello") }
}

private const val MAX_RIGHE_LISTA = 60

/** Allungabile: basso è una riga di riepilogo, alto mostra la lista da spuntare. */
class WidgetSpesa : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        disegna(c)
        DatiWidget.chiediAggiornamento(c)
    }

    override fun onReceive(c: Context, i: Intent) {
        if (i.action == DatiWidget.AZIONE_SPUNTA) {
            DatiWidget.spunta(c, i.getStringExtra(DatiWidget.EXTRA_FOOD).orEmpty())
            return
        }
        super.onReceive(c, i)
    }

    companion object {
        private fun compatta(c: Context): RemoteViews {
            val v = RemoteViews(c.packageName, R.layout.widget_spesa)
            val daFare = daComprare(c)
            v.setTextViewText(R.id.spesa_numero, when {
                !DatiWidget.collegato(c) -> "Apri NutriGenius per collegare il widget"
                daFare.isEmpty() -> "Niente da comprare"
                daFare.size == 1 -> "1 da comprare"
                else -> "${daFare.size} da comprare"
            })
            v.setTextViewText(R.id.spesa_anteprima, daFare.take(4).joinToString(", ") { it.optString("nome") })
            v.setViewVisibility(R.id.spesa_anteprima, if (daFare.isEmpty()) View.GONE else View.VISIBLE)
            v.setOnClickPendingIntent(R.id.spesa_radice, if (DatiWidget.collegato(c)) apriFinestra(c, 7020, FinestraSpesa::class.java) else apriNormale(c, 7021))
            return v
        }

        private fun lista(c: Context): RemoteViews {
            val v = RemoteViews(c.packageName, R.layout.widget_lista)
            val righe = DatiWidget.spesa(c)?.optJSONArray("sezioni")?.optJSONObject(0)?.optJSONArray("righe")
            val tutte = if (righe == null) emptyList() else (0 until righe.length()).map { righe.getJSONObject(it) }
            v.setImageViewResource(R.id.lista_icona, R.drawable.ic_widget_spesa)
            v.setTextViewText(R.id.lista_titolo, "Lista della spesa")
            v.setTextViewText(R.id.lista_numero, if (tutte.isEmpty()) "" else "${tutte.count { !it.has("carrello") }} da comprare")
            v.setTextViewText(R.id.lista_vuota, if (DatiWidget.collegato(c)) "Niente da comprare" else "Apri NutriGenius per collegare il widget")
            v.setOnClickPendingIntent(R.id.lista_testa, if (DatiWidget.collegato(c)) apriFinestra(c, 7020, FinestraSpesa::class.java) else apriNormale(c, 7021))
            v.setEmptyView(R.id.lista, R.id.lista_vuota)
            v.setPendingIntentTemplate(R.id.lista, PendingIntent.getBroadcast(
                c, 7040, Intent(c, WidgetSpesa::class.java).setAction(DatiWidget.AZIONE_SPUNTA),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            ))
            val b = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(1)
            for (r in tutte.take(MAX_RIGHE_LISTA)) {
                val riga = RemoteViews(c.packageName, R.layout.widget_riga_spesa)
                val nel = r.has("carrello")
                riga.setImageViewResource(R.id.riga_spunta, if (nel) R.drawable.ic_widget_spunta_si else R.drawable.ic_widget_spunta_no)
                riga.setTextViewText(R.id.riga_nome, r.optString("nome"))
                riga.setTextViewText(R.id.riga_dettaglio, SpesaWidget.dettaglio(0, r))
                riga.setOnClickFillInIntent(R.id.riga_radice, Intent().putExtra(DatiWidget.EXTRA_FOOD, r.optString("foodId")))
                b.addItem(r.optString("foodId").hashCode().toLong(), riga)
            }
            v.setRemoteAdapter(R.id.lista, b.build())
            return v
        }

        fun disegna(c: Context) {
            aggiornaTutti(c, WidgetSpesa::class.java, RemoteViews(mapOf(SizeF(100f, 40f) to compatta(c), SizeF(100f, 100f) to lista(c))))
        }
    }
}

class WidgetSpesaUno : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        disegna(c)
        DatiWidget.chiediAggiornamento(c)
    }

    companion object {
        fun disegna(c: Context) {
            val v = RemoteViews(c.packageName, R.layout.widget_uno)
            v.setImageViewResource(R.id.uno_icona, R.drawable.ic_widget_spesa)
            v.setTextViewText(R.id.uno_testo, if (DatiWidget.collegato(c)) "${daComprare(c).size}" else "—")
            v.setOnClickPendingIntent(R.id.uno_radice, if (DatiWidget.collegato(c)) apriFinestra(c, 7020, FinestraSpesa::class.java) else apriNormale(c, 7021))
            aggiornaTutti(c, WidgetSpesaUno::class.java, v)
        }
    }
}

/** Allungabile: basso è una riga di riepilogo, alto mostra i prossimi 4 pasti. */
class WidgetPasti : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        disegna(c)
        DatiWidget.chiediAggiornamento(c)
    }

    companion object {
        private fun compatta(c: Context): RemoteViews {
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
            return v
        }

        private fun lista(c: Context): RemoteViews {
            val v = RemoteViews(c.packageName, R.layout.widget_lista)
            val pasti = DatiWidget.prossimiPasti(c, 4)
            v.setImageViewResource(R.id.lista_icona, R.drawable.ic_widget_pasti)
            v.setTextViewText(R.id.lista_titolo, "Prossimi pasti")
            v.setTextViewText(R.id.lista_numero, "")
            v.setTextViewText(R.id.lista_vuota, if (DatiWidget.collegato(c)) "Nessun pasto in programma" else "Apri NutriGenius per collegare il widget")
            v.setOnClickPendingIntent(R.id.lista_testa, if (DatiWidget.collegato(c)) apriFinestra(c, 7030, FinestraPasti::class.java) else apriNormale(c, 7031))
            v.setEmptyView(R.id.lista, R.id.lista_vuota)
            v.setPendingIntentTemplate(R.id.lista, PendingIntent.getActivity(
                c, 7050,
                Intent(c, MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra(MainActivity.EXTRA_AZIONE, MainActivity.AZIONE_WIDGET)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            ))
            val b = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(2)
            var righe = 0
            for (p in pasti) {
                val data = p.optString("data")
                val pasto = p.optString("pasto")
                val testa = RemoteViews(c.packageName, R.layout.widget_riga_pasto)
                testa.setTextViewText(R.id.riga_nome, DatiWidget.nomePasto(pasto))
                testa.setTextViewText(R.id.riga_giorno, DatiWidget.giornoInParole(data))
                testa.setOnClickFillInIntent(R.id.riga_radice, Intent().putExtra(MainActivity.EXTRA_WIDGET, DatiWidget.queryPasto(data, pasto)))
                b.addItem("$data|$pasto".hashCode().toLong(), testa)
                val voci = p.optJSONArray("voci") ?: continue
                for (i in 0 until voci.length()) {
                    if (righe++ >= MAX_RIGHE_LISTA) break
                    val x = voci.getJSONObject(i)
                    val riga = RemoteViews(c.packageName, R.layout.widget_riga_voce)
                    riga.setTextViewText(R.id.riga_nome, x.optString("nome"))
                    riga.setTextViewText(R.id.riga_quantita, x.optString("quantita"))
                    val ingredienti = x.optJSONArray("ingredienti")
                    if (ingredienti != null) {
                        riga.setTextViewText(R.id.riga_ingredienti, (0 until ingredienti.length()).joinToString(", ") {
                            val y = ingredienti.getJSONObject(it)
                            "${y.optString("nome")} ${y.optString("quantita")}"
                        })
                        riga.setViewVisibility(R.id.riga_ingredienti, View.VISIBLE)
                    }
                    riga.setOnClickFillInIntent(R.id.riga_radice, Intent().putExtra(MainActivity.EXTRA_WIDGET, DatiWidget.queryAlimento(data, pasto, x.optString("id"))))
                    b.addItem(x.optString("id").hashCode().toLong(), riga)
                }
            }
            v.setRemoteAdapter(R.id.lista, b.build())
            return v
        }

        fun disegna(c: Context) {
            aggiornaTutti(c, WidgetPasti::class.java, RemoteViews(mapOf(SizeF(100f, 40f) to compatta(c), SizeF(100f, 100f) to lista(c))))
        }
    }
}

class WidgetPastiUno : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        disegna(c)
        DatiWidget.chiediAggiornamento(c)
    }

    companion object {
        fun disegna(c: Context) {
            val v = RemoteViews(c.packageName, R.layout.widget_uno)
            val prossimo = DatiWidget.prossimiPasti(c, 1).firstOrNull()
            v.setImageViewResource(R.id.uno_icona, R.drawable.ic_widget_pasti)
            v.setTextViewText(R.id.uno_testo, if (prossimo != null) DatiWidget.nomePasto(prossimo.optString("pasto")) else "—")
            v.setOnClickPendingIntent(R.id.uno_radice, if (DatiWidget.collegato(c)) apriFinestra(c, 7030, FinestraPasti::class.java) else apriNormale(c, 7031))
            aggiornaTutti(c, WidgetPastiUno::class.java, v)
        }
    }
}
