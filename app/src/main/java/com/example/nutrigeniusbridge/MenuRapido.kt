package com.example.nutrigeniusbridge

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RemoteViews
import android.widget.TextView
import android.app.PendingIntent

/**
 * Promemoria dell'acqua (#158): la pagina lo programma in anticipo con l'acqua di allora,
 * quindi quando scatta si rifà il conto (lo stesso di lib/promemoriaAcqua.ts) con quella di adesso.
 */
object PromemoriaAcqua {
    sealed class Esito {
        object Niente : Esito()
        class Dopo(val fra: Long, val body: String) : Esito()
        class Adesso(val body: String) : Esito()
    }

    /** null: non si sa abbastanza (nessun widget, o dati vecchi) e il promemoria resta com'era. */
    fun controlla(c: Context): Esito? {
        if (!DatiWidget.presenti(c)) return null
        val a = DatiWidget.acqua(c) ?: return null
        if (!a.completa || !a.attiva) return null
        val mezzanotte = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        return valuta(a.ml, a.target, a.glass, a.flask, a.bottle, mezzanotte + a.inizioMs(), mezzanotte + a.fineMs(), System.currentTimeMillis())
    }

    fun valuta(ml: Int, target: Int, glass: Int, flask: Int, bottle: Int, inizio: Long, fine: Long, adesso: Long): Esito {
        if (fine <= inizio || adesso >= fine) return Esito.Niente
        val soglia = minOf(glass, flask, bottle)
        val daRaggiungere = ml + soglia
        if (daRaggiungere >= target) return Esito.Niente
        val ritmo = target.toDouble() / (fine - inizio)
        val atteso = if (adesso <= inizio) 0.0 else (adesso - inizio) * ritmo
        val differenza = atteso - ml
        if (differenza >= soglia) {
            val quanto = if (differenza >= bottle) "una bottiglia" else if (differenza >= flask) "una borraccia" else "un bicchiere"
            return Esito.Adesso("Sei indietro di circa $quanto d'acqua. Bevi un sorso!")
        }
        val quando = inizio + (daRaggiungere / ritmo).toLong()
        return Esito.Dopo(quando - adesso, "Sei in ritardo sulla tabella di marcia d'acqua per oggi.")
    }
}

/** Il widget che sembra l'icona dell'app: apre il menu rapido. */
class WidgetIcona : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        val v = RemoteViews(c.packageName, R.layout.widget_icona)
        v.setOnClickPendingIntent(R.id.icona_radice, PendingIntent.getActivity(
            c, 7500, Intent(c, MenuRapido::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        ))
        m.updateAppWidget(ComponentName(c, WidgetIcona::class.java), v)
        DatiWidget.chiediAggiornamento(c)
    }
}

/** La barra dell'acqua con il triangolino del livello atteso. */
class BarraAcqua(c: Context) : View(c) {
    private var frazione = 0f
    private var atteso: Float? = null
    private var colore = 0
    private val dp = c.resources.displayMetrics.density
    private val pennello = Paint(Paint.ANTI_ALIAS_FLAG)
    private val triangolo = Path()
    private val spazio = RectF()

    fun imposta(a: DatiWidget.Acqua) {
        val massimo = maxOf(a.target, 1).toFloat()
        val x = a.atteso()
        frazione = (a.ml / massimo).coerceIn(0f, 1f)
        atteso = x?.let { (it.toFloat() / massimo).coerceIn(0f, 1f) }
        colore = Color.parseColor(when {
            a.ml >= massimo -> "#10B981"
            x != null && a.ml < x -> "#F87171"
            else -> "#3B82F6"
        })
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (18 * dp).toInt())

    override fun onDraw(c: Canvas) {
        val alta = 10 * dp
        val l = width.toFloat()
        pennello.color = Color.parseColor("#DBEAFE")
        spazio.set(0f, 0f, l, alta)
        c.drawRoundRect(spazio, alta / 2, alta / 2, pennello)
        if (frazione > 0f) {
            pennello.color = colore
            spazio.set(0f, 0f, maxOf(alta, l * frazione), alta)
            c.drawRoundRect(spazio, alta / 2, alta / 2, pennello)
        }
        atteso?.let {
            val x = (l * it).coerceIn(4 * dp, l - 4 * dp)
            triangolo.reset()
            triangolo.moveTo(x, alta + 1 * dp)
            triangolo.lineTo(x + 4 * dp, alta + 7 * dp)
            triangolo.lineTo(x - 4 * dp, alta + 7 * dp)
            triangolo.close()
            pennello.color = Color.parseColor("#475569")
            c.drawPath(triangolo, pennello)
        }
    }
}

/**
 * Il menu rapido: app, spesa, pasti e acqua. Si apre dal widget a icona e, come «impostazioni»
 * del widget, dal tocco prolungato sui widget: per questo è anche l'attività di configurazione.
 */
class MenuRapido : FinestraBreve() {
    private lateinit var numero: TextView
    private lateinit var barra: BarraAcqua
    private lateinit var iconaTipo: ImageView
    private lateinit var badgeTipo: TextView
    private lateinit var scelte: LinearLayout

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setFinishOnTouchOutside(true)
        // Lanciato come configurazione: il widget resta anche se si chiude il menu.
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id != AppWidgetManager.INVALID_APPWIDGET_ID) setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        val r = radice().apply { setPadding(dp(12), dp(12), dp(12), dp(12)) }
        r.addView(riga(
            voce(R.mipmap.ic_launcher_round, "NutriGenius") { apri(Intent(this, MainActivity::class.java)) },
            voce(R.drawable.ic_widget_allenamento, "Allenamento") { apri(Intent(Intent.ACTION_VIEW, Uri.parse(INDIRIZZO_ALLENAMENTO))) },
        ))
        r.addView(riga(
            voce(R.drawable.ic_widget_spesa, "Lista della spesa") { apri(Intent(this, FinestraSpesa::class.java)) },
            voce(R.drawable.ic_widget_pasti, "Prossimi pasti") { apri(Intent(this, FinestraPasti::class.java)) },
        ))
        val a = DatiWidget.acqua(this)
        if (a != null && a.attiva) r.addView(acqua())
        setContentView(r)
        posiziona()
        aggiornaAcqua()
    }

    private fun apri(i: Intent) {
        runCatching { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        finish()
    }

    private fun riga(a: View, b: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(a, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun tocco(v: View) {
        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        v.setBackgroundResource(tv.resourceId)
    }

    private fun voce(icona: Int, nome: String, fai: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(4), dp(10), dp(4), dp(10))
        tocco(this)
        setOnClickListener { fai() }
        addView(ImageView(this@MenuRapido).apply { setImageResource(icona); contentDescription = nome }, LinearLayout.LayoutParams(dp(40), dp(40)))
        addView(testo(nome).apply { textSize = 12.5f; gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0) })
    }

    /** Un contenitore con il numero di porzioni bevute. */
    private fun conBadge(tipo: String, a: DatiWidget.Acqua?): Pair<FrameLayout, TextView> {
        val f = FrameLayout(this)
        f.addView(ImageView(this).apply { setImageResource(ConfAcqua.icona(tipo)) }, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
        val b = badge()
        f.addView(b, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, dp(16), Gravity.TOP or Gravity.END))
        scriviBadge(b, a?.porzioni(tipo) ?: 0)
        return f to b
    }

    private fun badge() = TextView(this).apply {
        setBackgroundResource(R.drawable.widget_badge)
        setTextColor(Color.WHITE)
        textSize = 10f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        minWidth = dp(16)
        setPadding(dp(3), 0, dp(3), 0)
    }

    private fun scriviBadge(b: TextView, n: Int) {
        b.text = "$n"
        b.visibility = if (n > 0) View.VISIBLE else View.INVISIBLE
    }

    private fun acqua(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.widget_blocco)
            setPadding(dp(12), dp(10), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
        }
        barra = BarraAcqua(this)
        box.addView(barra, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val riga = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        numero = testo("").apply {
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), dp(8), dp(4), dp(8))
            tocco(this)
            contentDescription = "Apri il dettaglio dell'acqua"
            setOnClickListener { apri(DatiWidget.intentApp(this@MenuRapido, "widget=acqua")) }
        }
        riga.addView(numero, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        riga.addView(testo("+", true, FinestraWidget.VERDE).apply {
            textSize = 24f
            gravity = Gravity.CENTER
            contentDescription = "Aggiungi"
            tocco(this)
            setOnClickListener {
                DatiWidget.bevi(this@MenuRapido, DatiWidget.tipoRapido(this@MenuRapido))
                DatiWidget.ridisegnaAcqua(this@MenuRapido)
                aggiornaAcqua()
            }
        }, LinearLayout.LayoutParams(dp(48), dp(44)))
        val tipo = FrameLayout(this).apply {
            contentDescription = "Scegli un'altra quantità"
            tocco(this)
            setOnClickListener { scelte.visibility = if (scelte.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        }
        iconaTipo = ImageView(this)
        tipo.addView(iconaTipo, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
        badgeTipo = badge()
        tipo.addView(badgeTipo, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, dp(16), Gravity.TOP or Gravity.END))
        riga.addView(tipo, LinearLayout.LayoutParams(dp(48), dp(44)))
        box.addView(riga)
        scelte = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE }
        box.addView(scelte)
        return box
    }

    private fun aggiornaAcqua() {
        if (!::numero.isInitialized) return
        val a = DatiWidget.acqua(this) ?: return
        val tipo = DatiWidget.tipoRapido(this)
        numero.text = "${a.ml} / ${a.target} ml"
        barra.imposta(a)
        iconaTipo.setImageResource(ConfAcqua.icona(tipo))
        iconaTipo.contentDescription = ConfAcqua.nome(tipo)
        scriviBadge(badgeTipo, a.porzioni(tipo))
        scelte.removeAllViews()
        for ((t, nome) in ConfAcqua.CONTENITORI) {
            val v = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, dp(4))
                tocco(this)
                setOnClickListener {
                    DatiWidget.scegliRapido(this@MenuRapido, t)
                    scelte.visibility = View.GONE
                    aggiornaAcqua()
                }
            }
            v.addView(conBadge(t, a).first, LinearLayout.LayoutParams(dp(44), dp(36)))
            v.addView(testo("$nome ${a.misura(t)} ml", false, if (t == tipo) FinestraWidget.INDACO else FinestraWidget.GRIGIO).apply { textSize = 11f; gravity = Gravity.CENTER })
            scelte.addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    /** Vicino al widget che l'ha aperto, sopra o sotto a seconda di dov'è nello schermo. */
    private fun posiziona() {
        val schermo = resources.displayMetrics
        val larga = minOf(dp(310), (schermo.widthPixels * 0.92f).toInt())
        window.setLayout(larga, WindowManager.LayoutParams.WRAP_CONTENT)
        val b = intent.sourceBounds ?: return
        val p = window.attributes
        p.x = (b.centerX() - larga / 2).coerceIn(dp(8), maxOf(dp(8), schermo.widthPixels - larga - dp(8)))
        if (b.centerY() > schermo.heightPixels / 2) {
            p.gravity = Gravity.BOTTOM or Gravity.START
            p.y = schermo.heightPixels - b.top + dp(6)
        } else {
            p.gravity = Gravity.TOP or Gravity.START
            p.y = b.bottom + dp(6)
        }
        window.attributes = p
    }

    companion object {
        const val INDIRIZZO_ALLENAMENTO = "https://allenamento.casascoiattolo.com/"
    }
}
