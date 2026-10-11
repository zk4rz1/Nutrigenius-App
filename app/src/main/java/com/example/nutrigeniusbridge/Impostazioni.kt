package com.example.nutrigeniusbridge

import android.content.Context

/**
 * Le impostazioni dell'APK, sul telefono: l'indirizzo dell'app web e lo stato
 * degli aggiornamenti.
 *
 * L'indirizzo di serie è la produzione su ServeJS; si cambia dalle impostazioni
 * dell'app (pressione lunga sull'icona › «Impostazioni dell'app»), per esempio
 * per tornare ad AI Studio senza ricompilare l'APK.
 */
object Impostazioni {
    private const val FILE = "nutrigenius"
    private const val K_INDIRIZZO = "indirizzo_app"
    private const val K_ULTIMO_CONTROLLO = "aggiornamenti_ultimo_controllo"
    private const val K_ESITO = "aggiornamenti_esito"

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** L'indirizzo dell'app web, sempre con la barra finale. */
    fun indirizzo(c: Context): String = normalizza(sp(c).getString(K_INDIRIZZO, null) ?: BuildConfig.WEBAPP_URL)

    /** Vuoto o uguale a quello di serie: si torna a quello di serie. */
    fun impostaIndirizzo(c: Context, url: String?) {
        val u = url?.trim().orEmpty()
        sp(c).edit().apply {
            if (u.isEmpty() || normalizza(u) == normalizza(BuildConfig.WEBAPP_URL)) remove(K_INDIRIZZO) else putString(K_INDIRIZZO, normalizza(u))
        }.apply()
    }

    fun indirizzoDiSerie(c: Context): Boolean = indirizzo(c) == normalizza(BuildConfig.WEBAPP_URL)

    private fun normalizza(u: String): String {
        var s = u.trim()
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "https://$s"
        return if (s.endsWith("/")) s else "$s/"
    }

    var Context.ultimoControllo: Long
        get() = sp(this).getLong(K_ULTIMO_CONTROLLO, 0L)
        set(v) = sp(this).edit().putLong(K_ULTIMO_CONTROLLO, v).apply()

    /** L'esito dell'ultimo controllo degli aggiornamenti, in una riga. */
    var Context.esitoAggiornamenti: String
        get() = sp(this).getString(K_ESITO, "").orEmpty()
        set(v) = sp(this).edit().putString(K_ESITO, v).apply()
}
