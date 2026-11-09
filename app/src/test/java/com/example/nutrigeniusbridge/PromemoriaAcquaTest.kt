package com.example.nutrigeniusbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Il promemoria dell'acqua ricalcolato quando scatta (#158): lo stesso conto di lib/promemoriaAcqua.ts. */
class PromemoriaAcquaTest {
    private val ora = 3_600_000L
    private val inizio = 8 * ora
    private val fine = 20 * ora
    private fun valuta(ml: Int, adesso: Long) = PromemoriaAcqua.valuta(ml, 2400, 200, 500, 1000, inizio, fine, adesso)

    @Test
    fun indietro_avvisa_adesso() {
        // Alle 14 si dovrebbero aver bevuti 1200 ml.
        val e = valuta(300, 14 * ora)
        assertTrue(e is PromemoriaAcqua.Esito.Adesso)
        assertEquals("Sei indietro di circa una borraccia d'acqua. Bevi un sorso!", (e as PromemoriaAcqua.Esito.Adesso).body)
    }

    @Test
    fun bevuto_dal_widget_non_avvisa_ma_rimanda() {
        // Lo stesso momento, con l'acqua segnata dal widget: non si è indietro.
        val e = valuta(1200, 14 * ora)
        assertTrue(e is PromemoriaAcqua.Esito.Dopo)
        // Il previsto arriverà a 1400 ml (1200 + un bicchiere) alle 15.
        assertEquals(ora, (e as PromemoriaAcqua.Esito.Dopo).fra)
    }

    @Test
    fun obiettivo_quasi_raggiunto_o_fuori_fascia_niente() {
        assertTrue(valuta(2300, 14 * ora) is PromemoriaAcqua.Esito.Niente)
        assertTrue(valuta(0, 21 * ora) is PromemoriaAcqua.Esito.Niente)
    }

    @Test
    fun prima_della_fascia_conta_dall_inizio() {
        val e = valuta(0, 7 * ora)
        assertTrue(e is PromemoriaAcqua.Esito.Dopo)
        // 200 ml a 200 ml l'ora: un'ora dopo l'inizio, quindi due ore da adesso.
        assertEquals(2 * ora, (e as PromemoriaAcqua.Esito.Dopo).fra)
    }
}
