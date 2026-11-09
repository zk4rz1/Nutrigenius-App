package com.example.nutrigeniusbridge

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView

/** Le scelte dei widget dell'acqua, per ogni widget: non si cancellano uscendo dall'account. */
object ConfAcqua {
    private fun sp(c: Context) = c.getSharedPreferences("widget_conf", Context.MODE_PRIVATE)
    fun tipo(c: Context, id: Int): String = sp(c).getString("tipo_$id", "glass") ?: "glass"
    fun conferma(c: Context, id: Int): Boolean = sp(c).getBoolean("conferma_$id", false)
    fun imposta(c: Context, id: Int, tipo: String, conferma: Boolean) = sp(c).edit().putString("tipo_$id", tipo).putBoolean("conferma_$id", conferma).apply()
    fun scegli(c: Context, id: Int, tipo: String) = sp(c).edit().putString("tipo_$id", tipo).apply()
    fun configurato(c: Context, id: Int) = sp(c).contains("tipo_$id")
    fun togli(c: Context, ids: IntArray) {
        val e = sp(c).edit()
        for (id in ids) e.remove("tipo_$id").remove("conferma_$id")
        e.apply()
    }

    val CONTENITORI = listOf("glass" to "Bicchiere", "bottle" to "Bottiglia", "flask" to "Borraccia")
    fun nome(tipo: String) = CONTENITORI.firstOrNull { it.first == tipo }?.second ?: "Bicchiere"
    fun icona(tipo: String) = when (tipo) {
        "bottle" -> R.drawable.ic_widget_bottiglia
        "flask" -> R.drawable.ic_widget_borraccia
        else -> R.drawable.ic_widget_bicchiere
    }
    fun ml(a: DatiWidget.Acqua?, tipo: String): Int = when {
        a == null -> 0
        tipo == "bottle" -> a.bottle
        tipo == "flask" -> a.flask
        else -> a.glass
    }
}

/** Una finestrella con il tema dei widget. */
abstract class FinestraBreve : Activity() {
    protected fun dp(n: Int): Int = (n * resources.displayMetrics.density + 0.5f).toInt()

    protected fun radice(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(12))
    }

    protected fun testo(t: String, grande: Boolean = false, colore: Int = FinestraWidget.TESTO): TextView = TextView(this).apply {
        text = t
        textSize = if (grande) 17f else 14.5f
        setTextColor(colore)
        if (grande) typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    protected fun bottone(t: String, colore: Int, fai: () -> Unit): TextView = testo(t, false, colore).apply {
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(dp(14), dp(10), dp(14), dp(10))
        val tv = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        setBackgroundResource(tv.resourceId)
        setOnClickListener { fai() }
    }

    protected fun larghezza() = window.setLayout((resources.displayMetrics.widthPixels * 0.8f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)

    override fun onStop() {
        super.onStop()
        finish()
    }
}

/** Il widget 1x1: quale contenitore, e se chiedere conferma prima di aggiungere. */
class ConfiguraAcquaUno : FinestraBreve() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setFinishOnTouchOutside(false)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val r = radice()
        r.addView(testo("Pulsante del widget", true))
        val gruppo = RadioGroup(this)
        val attuale = ConfAcqua.tipo(this, id)
        val a = DatiWidget.acqua(this)
        val mappa = HashMap<Int, String>()
        ConfAcqua.CONTENITORI.forEachIndexed { n, (tipo, nome) ->
            val b = RadioButton(this).apply {
                this.id = 100 + n
                val ml = ConfAcqua.ml(a, tipo)
                text = if (ml > 0) "$nome · $ml ml" else nome
                textSize = 15f
                isChecked = tipo == attuale
            }
            mappa[100 + n] = tipo
            gruppo.addView(b)
        }
        r.addView(gruppo)
        val conferma = CheckBox(this).apply {
            text = "Chiedi conferma prima di aggiungere"
            textSize = 15f
            isChecked = ConfAcqua.conferma(this@ConfiguraAcquaUno, id)
        }
        r.addView(conferma)
        val azioni = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        // La voce del tocco prolungato: da qui, a widget già messo, si apre anche il menu rapido.
        if (ConfAcqua.configurato(this, id)) azioni.addView(bottone("Menu rapido", FinestraWidget.INDACO) {
            startActivity(Intent(this, MenuRapido::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        })
        azioni.addView(bottone("Annulla", FinestraWidget.GRIGIO) { finish() })
        azioni.addView(bottone("Salva", FinestraWidget.VERDE) {
            ConfAcqua.imposta(this, id, mappa[gruppo.checkedRadioButtonId] ?: "glass", conferma.isChecked)
            DatiWidget.ridisegnaAcqua(this)
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
            finish()
        })
        r.addView(azioni)
        setContentView(r)
        larghezza()
    }
}

/** La conferma opzionale del widget 1x1. */
class ConfermaAcqua : FinestraBreve() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setFinishOnTouchOutside(true)
        val tipo = intent.getStringExtra(DatiWidget.EXTRA_TIPO) ?: "glass"
        val a = DatiWidget.acqua(this)
        val r = radice()
        r.addView(testo("Aggiungere ${ConfAcqua.nome(tipo).lowercase()} da ${ConfAcqua.ml(a, tipo)} ml?", true))
        val azioni = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, dp(8), 0, 0) }
        azioni.addView(bottone("Annulla", FinestraWidget.GRIGIO) { finish() })
        azioni.addView(bottone("Aggiungi", FinestraWidget.VERDE) {
            DatiWidget.bevi(this, tipo)
            DatiWidget.ridisegnaAcqua(this)
            finish()
        })
        r.addView(azioni)
        setContentView(r)
        larghezza()
    }
}

/** Il widget 2x1: la quantità del prossimo «aggiungi». */
class ScegliAcqua : FinestraBreve() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setFinishOnTouchOutside(true)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val attuale = ConfAcqua.tipo(this, id)
        val a = DatiWidget.acqua(this)
        val r = radice()
        r.addView(testo("Quanto aggiungi?", true))
        for ((tipo, nome) in ConfAcqua.CONTENITORI) {
            val riga = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
                val tv = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                setBackgroundResource(tv.resourceId)
                setOnClickListener {
                    ConfAcqua.scegli(this@ScegliAcqua, id, tipo)
                    DatiWidget.ridisegnaAcqua(this@ScegliAcqua)
                    finish()
                }
            }
            riga.addView(android.widget.ImageView(this).apply { setImageResource(ConfAcqua.icona(tipo)) }, LinearLayout.LayoutParams(dp(26), dp(26)))
            riga.addView(testo(nome, false, if (tipo == attuale) FinestraWidget.INDACO else FinestraWidget.TESTO).apply { setPadding(dp(10), 0, 0, 0) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            riga.addView(testo("${ConfAcqua.ml(a, tipo)} ml", false, FinestraWidget.GRIGIO))
            r.addView(riga)
        }
        setContentView(r)
        larghezza()
    }
}
