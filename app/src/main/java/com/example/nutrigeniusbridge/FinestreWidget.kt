package com.example.nutrigeniusbridge

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Le finestre flottanti dei widget (#156): viste semplici, senza Compose e senza WebView, che
 * si aprono dai dati già sul telefono e si chiudono toccando fuori.
 */
abstract class FinestraWidget : Activity() {
    protected abstract val titolo: String
    protected abstract fun riempi(contenuto: LinearLayout)

    private lateinit var contenuto: LinearLayout
    private lateinit var aggiornato: TextView
    private var ultimi = -1L

    private val ascolto = SharedPreferences.OnSharedPreferenceChangeListener { _, chiave ->
        if (chiave == DatiWidget.CHIAVE_IL) ridisegna()
    }

    protected fun dp(n: Int): Int = (n * resources.displayMetrics.density + 0.5f).toInt()

    protected fun testo(t: CharSequence, sp: Float, colore: Int = TESTO, grassetto: Boolean = false): TextView = TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(colore)
        if (grassetto) typeface = Typeface.DEFAULT_BOLD
    }

    protected fun toccabile(v: View) {
        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        v.setBackgroundResource(tv.resourceId)
        v.isClickable = true
    }

    /** Una vista che cresce con il contenuto fino al massimo, poi scorre. */
    private class Scorrimento(c: Context, private val massimo: Int) : ScrollView(c) {
        override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, MeasureSpec.makeMeasureSpec(massimo, MeasureSpec.AT_MOST))
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setFinishOnTouchOutside(true)
        val schermo = resources.displayMetrics
        val radice = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(10))
        }
        radice.addView(testo(titolo, 18f, grassetto = true))
        contenuto = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scorri = Scorrimento(this, (schermo.heightPixels * 0.72f).toInt()).apply {
            isVerticalScrollBarEnabled = false
            addView(contenuto, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        radice.addView(scorri, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        aggiornato = testo("", 11f, GRIGIO).apply { gravity = Gravity.END; setPadding(0, dp(6), 0, 0) }
        radice.addView(aggiornato)
        setContentView(radice)
        window.setLayout((schermo.widthPixels * 0.92f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
        ridisegna()
    }

    /** Se si sta scrivendo qualcosa i dati nuovi aspettano: non si perde il lavoro. */
    protected open fun puoRidisegnare() = true

    private fun ridisegna() {
        val il = DatiWidget.sp(this).getLong(DatiWidget.CHIAVE_IL, 0L)
        if (il == ultimi || !puoRidisegnare()) return
        ultimi = il
        ricomponi()
    }

    protected fun ricomponi() {
        val il = DatiWidget.sp(this).getLong(DatiWidget.CHIAVE_IL, 0L)
        contenuto.removeAllViews()
        riempi(contenuto)
        aggiornato.text = if (il > 0) "Aggiornato alle ${SimpleDateFormat("HH:mm", Locale.ITALIAN).format(Date(il))}" else ""
    }

    override fun onStart() {
        super.onStart()
        DatiWidget.segnaFinestraUsata(this)
        DatiWidget.sp(this).registerOnSharedPreferenceChangeListener(ascolto)
        DatiWidget.chiediAggiornamento(applicationContext)
    }

    /** Uscendo verso la Home o un'altra app la finestra non resta in giro. */
    override fun onStop() {
        DatiWidget.sp(this).unregisterOnSharedPreferenceChangeListener(ascolto)
        super.onStop()
        finish()
    }

    protected fun apriApp(query: String) {
        startActivity(DatiWidget.intentApp(this, query))
        finish()
    }

    protected fun vuoto(contenuto: LinearLayout, t: String) {
        contenuto.addView(testo(t, 14f, GRIGIO).apply { setPadding(0, dp(12), 0, dp(12)) })
    }

    companion object {
        const val TESTO = 0xFF1E293B.toInt()
        const val GRIGIO = 0xFF64748B.toInt()
        const val VERDE = 0xFF059669.toInt()
        const val INDACO = 0xFF4F46E5.toInt()
        const val AMBRA = 0xFFB45309.toInt()
        const val AZZURRO = 0xFF0284C7.toInt()
    }
}

/** I prossimi 4 pasti, in blocchi separati: quantità sì, valori nutrizionali no. */
class FinestraPasti : FinestraWidget() {
    override val titolo = "Prossimi pasti"

    override fun riempi(contenuto: LinearLayout) {
        val pasti = DatiWidget.prossimiPasti(this, 4)
        if (pasti.isEmpty()) {
            vuoto(contenuto, "Nessun pasto in programma nei prossimi giorni.")
            return
        }
        for (p in pasti) contenuto.addView(blocco(p), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
    }

    private fun blocco(p: JSONObject): View {
        val data = p.optString("data")
        val pasto = p.optString("pasto")
        val b = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.widget_blocco)
            setPadding(dp(12), dp(6), dp(12), dp(8))
        }
        val testata = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            toccabile(this)
            setOnClickListener { apriApp(DatiWidget.queryPasto(data, pasto)) }
        }
        testata.addView(testo(DatiWidget.nomePasto(pasto), 15f, INDACO, true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        testata.addView(testo(DatiWidget.giornoInParole(data), 12.5f, GRIGIO))
        b.addView(testata)
        val voci = p.optJSONArray("voci") ?: return b
        for (i in 0 until voci.length()) b.addView(voce(voci.getJSONObject(i), data, pasto))
        return b
    }

    private fun voce(v: JSONObject, data: String, pasto: String): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val riga = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(5), 0, dp(5))
            toccabile(this)
            setOnClickListener { apriApp(DatiWidget.queryAlimento(data, pasto, v.optString("id"))) }
        }
        riga.addView(testo(v.optString("nome"), 14f), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        riga.addView(testo(v.optString("quantita"), 13f, GRIGIO, true).apply { setPadding(dp(8), 0, 0, 0) })
        col.addView(riga)
        val ingredienti = v.optJSONArray("ingredienti") ?: return col
        val elenco = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(14), 0, 0, dp(4))
        }
        for (i in 0 until ingredienti.length()) {
            val x = ingredienti.getJSONObject(i)
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(2), 0, dp(2)) }
            r.addView(testo(x.optString("nome"), 12.5f, GRIGIO), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(testo(x.optString("quantita"), 12.5f, GRIGIO))
            elenco.addView(r)
        }
        val quanti = ingredienti.length()
        val nome = if (quanti == 1) "ingrediente" else "ingredienti"
        val apri = testo("▸ $quanti $nome", 12.5f, INDACO, true).apply {
            setPadding(0, dp(2), 0, dp(6))
            toccabile(this)
            setOnClickListener {
                val aperto = elenco.visibility == View.VISIBLE
                elenco.visibility = if (aperto) View.GONE else View.VISIBLE
                text = "${if (aperto) "▸" else "▾"} $quanti $nome"
            }
        }
        col.addView(apri)
        col.addView(elenco)
        return col
    }
}
