package com.example.nutrigeniusbridge

import android.graphics.PorterDuff
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.method.DigitsKeyListener
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.round

/**
 * La lista della spesa senza rete (1.7.0, #157): i dati arrivano calcolati dalla pagina o dal
 * server; qui si applicano sopra le operazioni ancora in coda, così una spunta si vede subito.
 * Quando l'operazione arriva al server, sono i dati nuovi a dire come stanno le cose.
 */
object SpesaWidget {
    fun numeroBreve(v: Double): String {
        val a = abs(v)
        if (a == 0.0) return "0"
        if (a >= 9.95) return round(v).toLong().toString()
        if (a < 0.05) return if (v < 0) ">-0.1" else "<0.1"
        val s = String.format(Locale.ROOT, "%.1f", v)
        return s.removeSuffix(".0")
    }

    /** Il testo di una casella: due decimali al massimo, come nell'app. */
    fun testoDi(v: Double): String {
        val x = round(v * 100) / 100
        return if (x == floor(x)) x.toLong().toString() else x.toString()
    }

    fun numero(t: String): Double = t.trim().replace(",", ".").toDoubleOrNull() ?: Double.NaN

    /** Quanta unità di base vale una unità di conteggio. */
    fun fattore(r: JSONObject, u: String): Double {
        val a = r.optJSONArray("unita") ?: return 1.0
        for (i in 0 until a.length()) {
            val x = a.getJSONObject(i)
            if (x.optString("n") == u) return x.optDouble("f", 1.0)
        }
        return 1.0
    }

    private fun q(r: JSONObject, n: Double, u: String) = "${numeroBreve(n / fattore(r, u))} $u"

    private fun inParole(r: JSONObject, n: Double, u: String): String {
        val base = r.optString("base")
        if (u != base) return "${numeroBreve(n / fattore(r, u))} $u · ${numeroBreve(n)} $base"
        val testo = "${numeroBreve(n)} $base"
        val p = r.optJSONObject("porzione") ?: return testo
        return "$testo · ${numeroBreve(n / p.optDouble("v", 1.0))} ${p.optString("n")}"
    }

    /** Il testo sotto il nome, come nell'app, dai numeri della riga. */
    fun dettaglio(sezione: Int, r: JSONObject): String {
        if (!r.has("foodId")) return r.optString("dettaglio")
        val u = r.optString("scelta")
        val daComprare = r.optDouble("daComprare", 0.0)
        val presente = r.optDouble("presente", 0.0)
        val necessaria = r.optDouble("necessaria", 0.0)
        return when (sezione) {
            0 -> {
                val c = r.optJSONObject("carrello")
                if (c != null) "nel carrello ${inParole(r, c.optDouble("quantita", 0.0), u)}${if (daComprare > 0) " · ne servivano ${q(r, daComprare, u)}" else ""}"
                else "${inParole(r, daComprare, u)}${if (presente > 0) " (in dispensa ${q(r, presente, u)}, ne servono ${numeroBreve(necessaria / fattore(r, u))})" else ""}"
            }
            1 -> "presente ${inParole(r, presente, u)} · ne servono ${numeroBreve(necessaria / fattore(r, u))} $u"
            else -> "${inParole(r, presente, u)}${r.optString("nota").takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""}"
        }
    }

    private fun righe(spesa: JSONObject, sezione: Int): JSONArray? =
        spesa.optJSONArray("sezioni")?.optJSONObject(sezione)?.optJSONArray("righe")

    private inline fun ogniRiga(spesa: JSONObject, fai: (Int, JSONObject) -> Unit) {
        val sezioni = spesa.optJSONArray("sezioni") ?: return
        for (s in 0 until sezioni.length()) {
            val a = sezioni.getJSONObject(s).optJSONArray("righe") ?: continue
            for (i in 0 until a.length()) fai(s, a.getJSONObject(i))
        }
    }

    fun riga(spesa: JSONObject?, sezione: Int, foodId: String): JSONObject? {
        val a = spesa?.let { righe(it, sezione) } ?: return null
        for (i in 0 until a.length()) if (a.getJSONObject(i).optString("foodId") == foodId) return a.getJSONObject(i)
        return null
    }

    /** Applica sui dati un'operazione ancora in coda. */
    fun applica(spesa: JSONObject, o: JSONObject) {
        val foodId = o.optString("foodId")
        val acquisto = o.optString("acquisto")
        when (o.optString("op")) {
            "metti" -> riga(spesa, 0, foodId)?.let {
                it.put("carrello", JSONObject().put("id", acquisto).put("quantita", o.optDouble("quantita", 0.0)).put("lunga", o.optBoolean("lunga")))
                it.put("fatto", true)
            }
            "togli" -> ogniRiga(spesa) { _, r ->
                if (r.optJSONObject("carrello")?.optString("id") == acquisto) {
                    r.remove("carrello")
                    r.remove("fatto")
                }
            }
            "cambia" -> ogniRiga(spesa) { _, r ->
                r.optJSONObject("carrello")?.takeIf { it.optString("id") == acquisto }?.let { c ->
                    if (o.has("quantita")) c.put("quantita", o.optDouble("quantita", 0.0))
                    if (o.has("lunga")) c.put("lunga", o.optBoolean("lunga"))
                }
                r.optJSONArray("voci")?.let { v ->
                    for (i in 0 until v.length()) {
                        val x = v.getJSONObject(i)
                        if (x.optString("id") == acquisto && o.has("lunga")) x.put("lunga", o.optBoolean("lunga"))
                    }
                }
            }
            "salva" -> ogniRiga(spesa) { _, r ->
                if (r.optString("foodId") == foodId) {
                    val aggiunta = o.optDouble("aggiunta", 0.0)
                    r.put("presente", if (aggiunta > 1e-9) r.optDouble("presente", 0.0) + aggiunta else o.optDouble("totale", 0.0))
                }
            }
            "finito" -> ogniRiga(spesa) { _, r -> if (r.optString("foodId") == foodId) r.put("presente", 0.0) }
            "ciSono", "restano" -> ogniRiga(spesa) { _, r ->
                val c = r.optJSONArray("confermare") ?: return@ogniRiga
                val resta = JSONArray()
                for (i in 0 until c.length()) {
                    val x = c.getJSONObject(i)
                    if (x.optString("id") != acquisto) {
                        resta.put(x)
                    } else if (o.optString("op") == "restano") {
                        r.put("presente", max(0.0, r.optDouble("presente", 0.0) + o.optDouble("quantita", 0.0) - x.optDouble("resta", 0.0)))
                    }
                }
                r.put("confermare", resta)
            }
            "scadenza" -> ogniRiga(spesa) { _, r ->
                if (r.optString("foodId") == foodId) {
                    val g = o.optInt("giorni", 0)
                    if (g > 0) {
                        r.put("deroga", g)
                        r.put("giorni", g)
                    } else {
                        r.remove("deroga")
                        r.put("giorni", spesa.optInt("diSerie", 7))
                    }
                }
            }
            "unita" -> ogniRiga(spesa) { _, r -> if (r.optString("foodId") == foodId) r.put("scelta", o.optString("unita")) }
            "spesaFinita" -> righe(spesa, 0)?.let { a ->
                for (i in a.length() - 1 downTo 0) if (a.getJSONObject(i).has("carrello")) a.remove(i)
            }
        }
    }

    fun nuovoId(): String = UUID.randomUUID().toString()
}

/** La lista: spunte e correzioni come nell'app, anche senza rete. */
class FinestraSpesa : FinestraWidget() {
    override val titolo = "Lista della spesa"

    /** Il riquadro aperto sotto ogni riga: «editor», «scadenza» o «usi». */
    private val aperto = HashMap<String, String>()
    /** La lunga scadenza scelta prima della spunta (#92). */
    private val lungaPrima = HashSet<String>()
    private val viste = HashMap<String, LinearLayout>()
    private var spesaFinita: View? = null

    private fun chiave(sezione: Int, foodId: String) = "$sezione|$foodId"

    override fun puoRidisegnare() = aperto.isEmpty()

    override fun riempi(contenuto: LinearLayout) {
        viste.clear()
        val s = DatiWidget.spesa(this)
        val sezioni = s?.optJSONArray("sezioni")
        if (s == null || sezioni == null) {
            vuoto(contenuto, "La lista non è ancora disponibile: apri NutriGenius e scegli il menu della lista.")
            return
        }
        s.optString("periodo").takeIf { it.isNotEmpty() }?.let { contenuto.addView(testo(it, 12f, GRIGIO)) }
        var carrello = false
        for (i in 0 until sezioni.length()) {
            val sez = sezioni.getJSONObject(i)
            val righe = sez.optJSONArray("righe")
            val tutte = if (righe == null) emptyList() else (0 until righe.length()).map { righe.getJSONObject(it) }
            val visibili = if (i == 2) tutte.filter { it.optDouble("presente", 0.0) > 1e-9 } else tutte
            if (visibili.isEmpty() && i > 0) continue
            contenuto.addView(testo("${sez.optString("titolo").uppercase(Locale.ITALIAN)}${if (visibili.isNotEmpty()) " (${visibili.size})" else ""}", 12f, GRIGIO, true).apply { setPadding(0, dp(12), 0, dp(4)) })
            if (visibili.isEmpty()) {
                contenuto.addView(testo("Niente da comprare per i pasti di questi giorni.", 13f, GRIGIO))
                continue
            }
            if (i == 0 && visibili.any { it.has("carrello") }) carrello = true
            for (r in visibili) {
                val v = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
                viste[chiave(i, r.optString("foodId"))] = v
                componi(v, i, r)
                contenuto.addView(v)
            }
        }
        // C'è sempre: compare con la prima spunta, senza rifare tutta la lista.
        val fine = pulsante("Spesa finita: metti il carrello in dispensa", VERDE, true) {
            DatiWidget.opSpesa(this, JSONObject().put("op", "spesaFinita"))
            aperto.clear()
            ricomponi()
        }.apply {
            setPadding(0, dp(12), 0, dp(6))
            visibility = if (carrello) View.VISIBLE else View.GONE
        }
        spesaFinita = fine
        contenuto.addView(fine)
    }

    private fun aggiornaSpesaFinita() {
        val righe = DatiWidget.spesa(this)?.optJSONArray("sezioni")?.optJSONObject(0)?.optJSONArray("righe")
        val c = righe != null && (0 until righe.length()).any { righe.getJSONObject(it).has("carrello") }
        spesaFinita?.visibility = if (c) View.VISIBLE else View.GONE
    }

    private fun ricomponiRiga(sezione: Int, foodId: String) {
        val v = viste[chiave(sezione, foodId)] ?: return
        val r = SpesaWidget.riga(DatiWidget.spesa(this), sezione, foodId) ?: return
        v.removeAllViews()
        componi(v, sezione, r)
    }

    private fun pulsante(t: String, colore: Int, grassetto: Boolean = true, fai: () -> Unit): TextView = testo(t, 13.5f, colore, grassetto).apply {
        setPadding(dp(10), dp(6), dp(10), dp(6))
        toccabile(this)
        setOnClickListener { fai() }
    }

    private fun icona(risorsa: Int, descrizione: String, colore: Int, fai: () -> Unit): ImageView = ImageView(this).apply {
        setImageResource(risorsa)
        setColorFilter(colore, PorterDuff.Mode.SRC_IN)
        contentDescription = descrizione
        setPadding(dp(7), dp(7), dp(7), dp(7))
        toccabile(this)
        setOnClickListener { fai() }
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
    }

    private fun spunta(r: JSONObject) {
        val c = r.optJSONObject("carrello")
        val foodId = r.optString("foodId")
        if (c != null) {
            DatiWidget.opSpesa(this, JSONObject().put("op", "togli").put("acquisto", c.optString("id")))
            aperto.remove(chiave(0, foodId))
        } else {
            DatiWidget.opSpesa(this, DatiWidget.opMetti(r, lungaPrima.contains(foodId)))
            aperto[chiave(0, foodId)] = "editor"
        }
        ricomponiRiga(0, foodId)
        aggiornaSpesaFinita()
    }

    private fun alterna(sezione: Int, foodId: String, pannello: String) {
        val k = chiave(sezione, foodId)
        if (aperto[k] == pannello) aperto.remove(k) else aperto[k] = pannello
        ricomponiRiga(sezione, foodId)
    }

    private fun componi(v: LinearLayout, sezione: Int, r: JSONObject) {
        // Dati del formato di prima (1.6.0), finché non arrivano quelli nuovi: si possono solo leggere.
        if (!r.has("foodId")) {
            v.addView(testo(r.optString("nome"), 15f, TESTO, true))
            v.addView(testo(r.optString("dettaglio"), 12.5f, GRIGIO))
            return
        }
        val foodId = r.optString("foodId")
        val c = r.optJSONObject("carrello")
        val riga = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (sezione == 0) riga.addView(CheckBox(this).apply {
            isChecked = c != null
            contentDescription = "Comprato ${r.optString("nome")}"
            setOnClickListener { spunta(r) }
        })
        val colonna = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            toccabile(this)
            setOnClickListener {
                if (sezione == 0 && c == null) spunta(r) else alterna(sezione, foodId, "editor")
            }
        }
        val det = testo(SpesaWidget.dettaglio(sezione, r), 12.5f, if (c != null) VERDE else GRIGIO)
        colonna.addView(testo(r.optString("nome"), 15f, TESTO, true))
        colonna.addView(det)
        riga.addView(colonna, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if ((r.optJSONArray("usi")?.length() ?: 0) > 0) riga.addView(icona(R.drawable.ic_widget_pasti, "Dove si usa", GRIGIO) { alterna(sezione, foodId, "usi") })
        val lunga = lunghe(sezione, r).any { it.third }
        riga.addView(icona(if (lunga) R.drawable.ic_widget_neve else R.drawable.ic_widget_scadenza, "Scadenza", if (lunga) AZZURRO else if (r.has("deroga")) AMBRA else GRIGIO) { alterna(sezione, foodId, "scadenza") })
        v.addView(riga)
        when (aperto[chiave(sezione, foodId)]) {
            "editor" -> if (sezione != 0 || c != null) v.addView(editor(sezione, r, det))
            "scadenza" -> v.addView(scadenza(sezione, r))
            "usi" -> v.addView(usi(r))
        }
        r.optJSONArray("confermare")?.let { x -> for (i in 0 until x.length()) v.addView(conferma(sezione, r, x.getJSONObject(i))) }
    }

    private fun riquadro(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.widget_blocco)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
    }

    private fun casella(t: String, descrizione: String): EditText = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        keyListener = DigitsKeyListener.getInstance("0123456789.,")
        setText(t)
        setSelectAllOnFocus(true)
        gravity = Gravity.END
        contentDescription = descrizione
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
        minWidth = dp(64)
    }

    private fun quando(e: EditText, fai: () -> Unit) = e.addTextChangedListener(object : TextWatcher {
        override fun afterTextChanged(s: Editable?) = fai()
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    })

    /** Aggiungi e totale, con l'unità: nel carrello vale subito, altrimenti con Salva. */
    private fun editor(sezione: Int, r: JSONObject, det: TextView): View {
        val foodId = r.optString("foodId")
        val nome = r.optString("nome")
        val c = r.optJSONObject("carrello")
        val dalVivo = c != null
        val presente = r.optDouble("presente", 0.0)
        var u = r.optString("scelta")
        var f = SpesaWidget.fattore(r, u)
        val box = riquadro()
        val aggiunta = casella(SpesaWidget.testoDi(if (dalVivo) c!!.optDouble("quantita", 0.0) / f else 0.0), "Quantità comprata di $nome")
        val totale = casella("", "Totale in dispensa di $nome")
        totale.setText(SpesaWidget.testoDi(presente / f + max(0.0, SpesaWidget.numero(aggiunta.text.toString()))))
        var ignora = false
        val aggiorna: () -> Unit = {
            if (dalVivo) {
                val n = SpesaWidget.numero(aggiunta.text.toString())
                if (!n.isNaN() && n >= 0) {
                    DatiWidget.opSpesa(this, JSONObject().put("op", "cambia").put("acquisto", c!!.optString("id")).put("quantita", n * f))
                    SpesaWidget.riga(DatiWidget.spesa(this), sezione, foodId)?.let { det.text = SpesaWidget.dettaglio(sezione, it) }
                }
            }
        }
        quando(aggiunta) {
            if (!ignora) {
                ignora = true
                val n = SpesaWidget.numero(aggiunta.text.toString())
                if (!n.isNaN()) totale.setText(SpesaWidget.testoDi(presente / f + max(0.0, n)))
                ignora = false
                aggiorna()
            }
        }
        quando(totale) {
            if (!ignora) {
                ignora = true
                val n = SpesaWidget.numero(totale.text.toString())
                if (!n.isNaN()) aggiunta.setText(SpesaWidget.testoDi(max(0.0, n - presente / f)))
                ignora = false
                aggiorna()
            }
        }
        val riga = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        riga.addView(testo("Aggiungi", 12.5f, GRIGIO))
        riga.addView(aggiunta)
        riga.addView(testo("Totale", 12.5f, GRIGIO).apply { setPadding(dp(8), 0, 0, 0) })
        riga.addView(totale)
        val nomi = r.optJSONArray("unita")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("n") } } ?: listOf(u)
        if (nomi.size > 1) {
            val scelta = Spinner(this).apply {
                adapter = ArrayAdapter(this@FinestraSpesa, android.R.layout.simple_spinner_dropdown_item, nomi)
                setSelection(maxOf(0, nomi.indexOf(u)))
                contentDescription = "Unità di $nome"
            }
            scelta.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, w: View?, pos: Int, id: Long) {
                    val nuova = nomi[pos]
                    if (nuova == u) return
                    val nf = SpesaWidget.fattore(r, nuova)
                    ignora = true
                    SpesaWidget.numero(aggiunta.text.toString()).takeIf { !it.isNaN() }?.let { aggiunta.setText(SpesaWidget.testoDi(it * f / nf)) }
                    SpesaWidget.numero(totale.text.toString()).takeIf { !it.isNaN() }?.let { totale.setText(SpesaWidget.testoDi(it * f / nf)) }
                    ignora = false
                    u = nuova
                    f = nf
                    DatiWidget.opSpesa(this@FinestraSpesa, JSONObject().put("op", "unita").put("foodId", foodId).put("unita", nuova))
                    SpesaWidget.riga(DatiWidget.spesa(this@FinestraSpesa), sezione, foodId)?.let { det.text = SpesaWidget.dettaglio(sezione, it) }
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
            riga.addView(scelta)
        } else {
            riga.addView(testo(u, 13f, GRIGIO).apply { setPadding(dp(6), 0, 0, 0) })
        }
        box.addView(riga)
        if (dalVivo) return box
        val lunga = CheckBox(this).apply { text = "Lunga scadenza (quello che aggiungi)"; textSize = 12.5f }
        box.addView(lunga)
        val azioni = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        val chiudi = {
            aperto.remove(chiave(sezione, foodId))
            ricomponiRiga(sezione, foodId)
        }
        val salva: (Double, Double, Boolean) -> Unit = { a, t, l ->
            DatiWidget.opSpesa(this, JSONObject().put("op", "salva").put("foodId", foodId).put("acquisto", SpesaWidget.nuovoId()).put("nome", nome).put("aggiunta", a).put("totale", t).put("lunga", l))
            aperto.remove(chiave(sezione, foodId))
            ricomponi()
        }
        if (presente > 0) azioni.addView(pulsante("Finito", GRIGIO) { salva(0.0, 0.0, false) })
        azioni.addView(pulsante("Annulla", GRIGIO) { chiudi() })
        azioni.addView(pulsante("Salva", VERDE) {
            val a = SpesaWidget.numero(aggiunta.text.toString())
            val t = SpesaWidget.numero(totale.text.toString())
            if (!a.isNaN() && !t.isNaN() && a >= 0 && t >= 0) salva(if (max(0.0, t * f - presente) > 1e-9) a * f else 0.0, t * f, lunga.isChecked)
        })
        box.addView(azioni)
        return box
    }

    /** Per ogni acquisto: id, testo e se è a lunga scadenza. Nel carrello e prima della spunta è «quello che compri». */
    private fun lunghe(sezione: Int, r: JSONObject): List<Triple<String, String, Boolean>> {
        if (sezione == 0) {
            val c = r.optJSONObject("carrello")
            return listOf(Triple(c?.optString("id").orEmpty(), "quello che compri", if (c != null) c.optBoolean("lunga") else lungaPrima.contains(r.optString("foodId"))))
        }
        val v = r.optJSONArray("voci") ?: return emptyList()
        return (0 until v.length()).map { v.getJSONObject(it) }.map { Triple(it.optString("id"), it.optString("testo"), it.optBoolean("lunga")) }
    }

    private fun scadenza(sezione: Int, r: JSONObject): View {
        val foodId = r.optString("foodId")
        val box = riquadro()
        val deroga = r.optInt("deroga", 0)
        box.addView(testo("Scadenza di ${r.optString("nome")}", 13f, TESTO, true))
        for ((id, nome, attiva) in lunghe(sezione, r)) box.addView(CheckBox(this).apply {
            text = "Lunga scadenza · $nome"
            textSize = 12.5f
            isChecked = attiva
            setOnClickListener {
                if (id.isEmpty()) {
                    if (isChecked) lungaPrima.add(foodId) else lungaPrima.remove(foodId)
                } else {
                    DatiWidget.opSpesa(this@FinestraSpesa, JSONObject().put("op", "cambia").put("acquisto", id).put("lunga", isChecked))
                }
                ricomponiRiga(sezione, foodId)
            }
        })
        val giorni = casella((if (deroga > 0) deroga else r.optInt("giorni", 7)).toString(), "Giorni di scadenza")
        giorni.inputType = InputType.TYPE_CLASS_NUMBER
        val riga = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        riga.addView(testo("Dopo", 12.5f, GRIGIO))
        riga.addView(giorni)
        riga.addView(testo("giorni dall'acquisto", 12.5f, GRIGIO))
        box.addView(riga)
        box.addView(testo("Di serie ${DatiWidget.spesa(this)?.optInt("diSerie", 7) ?: 7} giorni. Vale per tutti i suoi acquisti, tranne quelli a lunga scadenza.", 11.5f, GRIGIO))
        val azioni = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        val chiudi = {
            aperto.remove(chiave(sezione, foodId))
            ricomponiRiga(sezione, foodId)
        }
        val salva: (Int) -> Unit = { g ->
            DatiWidget.opSpesa(this, JSONObject().put("op", "scadenza").put("foodId", foodId).apply { if (g > 0) put("giorni", g) else put("giorni", JSONObject.NULL) })
            chiudi()
        }
        if (deroga > 0) azioni.addView(pulsante("Togli la deroga", GRIGIO) { salva(0) })
        azioni.addView(pulsante("Chiudi", GRIGIO) { chiudi() })
        azioni.addView(pulsante("Salva", VERDE) { giorni.text.toString().trim().toIntOrNull()?.let { if (it > 0) salva(it) } })
        box.addView(azioni)
        return box
    }

    private fun usi(r: JSONObject): View {
        val box = riquadro()
        val a = r.optJSONArray("usi") ?: return box
        for (i in 0 until a.length()) {
            val x = a.getJSONObject(i)
            box.addView(testo(x.optString("testo"), 12.5f, INDACO).apply {
                setPadding(0, dp(4), 0, dp(4))
                toccabile(this)
                setOnClickListener { apriApp(DatiWidget.queryPasto(x.optString("data"), x.optString("pasto"))) }
            })
        }
        return box
    }

    /** «Dai pasti segnati ne restano…: è così?» */
    private fun conferma(sezione: Int, r: JSONObject, c: JSONObject): View {
        val foodId = r.optString("foodId")
        val id = c.optString("id")
        val box = riquadro()
        box.addView(testo(c.optString("testo"), 12.5f, AMBRA))
        val riga = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val fatto = { ricomponiRiga(sezione, foodId) }
        riga.addView(pulsante("Sì", AMBRA) {
            DatiWidget.opSpesa(this, JSONObject().put("op", "ciSono").put("acquisto", id))
            fatto()
        })
        riga.addView(pulsante("No, finiti", AMBRA) {
            DatiWidget.opSpesa(this, JSONObject().put("op", "restano").put("acquisto", id).put("quantita", 0.0))
            fatto()
        })
        val u = r.optString("scelta")
        val f = SpesaWidget.fattore(r, u)
        val quanti = casella(SpesaWidget.testoDi(c.optDouble("resta", 0.0) / f), "Quanti ne restano")
        riga.addView(quanti)
        riga.addView(pulsante("Ne restano $u", AMBRA) {
            val n = SpesaWidget.numero(quanti.text.toString())
            if (!n.isNaN() && n >= 0) {
                DatiWidget.opSpesa(this, JSONObject().put("op", "restano").put("acquisto", id).put("quantita", n * f))
                fatto()
            }
        })
        box.addView(riga)
        return box
    }
}
