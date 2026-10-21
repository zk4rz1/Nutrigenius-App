package com.example.nutrigeniusbridge

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Gli avvisi del server con Firebase Cloud Messaging (1.4.0).
 *
 * Il server manda messaggi di soli dati (api/_lib/fcm.php):
 * - {id, tipo, titolo, testo}: un avviso, lo stesso che darebbe apk_eventi;
 * - {azione: "chiudi", tipo}: la notifica di quel tipo non serve più (la risposta
 *   dell'agente letta su un altro dispositivo).
 *
 * Senza la configurazione di Firebase nell'APK (google-services.json passato a Gradle)
 * tutto questo resta spento e gli avvisi arrivano con il controllo periodico.
 */
object Fcm {
    private const val TAG = "NutriGeniusFcm"

    fun attivo(c: Context): Boolean = runCatching { FirebaseApp.getApps(c).isNotEmpty() }.getOrDefault(false)

    /** Chiede il token e lo registra al server, se non l'ha già con il gettone di adesso. */
    fun registra(c: Context) {
        if (!attivo(c)) return
        val app = c.applicationContext
        runCatching {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                if (!token.isNullOrEmpty() && Avvisi.tokenDaRegistrare(app, token)) {
                    CoroutineScope(Dispatchers.IO).launch { runCatching { Avvisi.registraFcm(app, token) }.onFailure { Log.w(TAG, "registrazione non riuscita", it) } }
                }
            }
        }.onFailure { Log.w(TAG, "token non disponibile", it) }
    }
}

class MessaggiFcm : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        // Il servizio è già su un thread suo: la registrazione si fa qui, senza lasciarla a metà.
        runBlocking { runCatching { Avvisi.registraFcm(applicationContext, token) } }
    }

    override fun onMessageReceived(m: RemoteMessage) {
        val d = m.data
        if (d["azione"] == "chiudi") {
            Avvisi.chiudi(applicationContext, d["tipo"].orEmpty())
            return
        }
        val tipo = d["tipo"] ?: return
        Avvisi.daFcm(applicationContext, d["id"]?.toLongOrNull() ?: 0L, tipo, d["titolo"].orEmpty(), d["testo"].orEmpty())
    }
}
