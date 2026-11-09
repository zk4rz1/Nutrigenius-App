package com.example.nutrigeniusbridge

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Quando la lista si chiude il carrello passa in dispensa, anche senza rete (#159). */
class SpesaFinitaTest {
    private fun riga(id: String, daComprare: Double, q: Double) = JSONObject()
        .put("foodId", id).put("daComprare", daComprare).put("presente", 0.0).put("necessaria", daComprare)
        .put("carrello", JSONObject().put("id", "a-$id").put("quantita", q))

    private fun spesa(vararg r: JSONObject): JSONObject {
        val da = org.json.JSONArray()
        r.forEach { da.put(it) }
        return JSONObject().put("sezioni", org.json.JSONArray()
            .put(JSONObject().put("righe", da))
            .put(JSONObject().put("righe", org.json.JSONArray()))
            .put(JSONObject().put("righe", org.json.JSONArray())))
    }

    private fun righe(s: JSONObject, n: Int) = s.getJSONArray("sezioni").getJSONObject(n).getJSONArray("righe")

    @Test
    fun comprato_abbastanza_passa_fra_i_gia_acquistati() {
        val s = spesa(riga("pasta", 500.0, 500.0))
        SpesaWidget.applica(s, JSONObject().put("op", "spesaFinita"))
        assertEquals(0, righe(s, 0).length())
        val r = righe(s, 1).getJSONObject(0)
        assertEquals("pasta", r.getString("foodId"))
        assertEquals(500.0, r.getDouble("presente"), 0.001)
        assertFalse(r.has("carrello"))
    }

    @Test
    fun comprato_meno_del_necessario_resta_da_comprare() {
        val s = spesa(riga("pasta", 500.0, 200.0))
        SpesaWidget.applica(s, JSONObject().put("op", "spesaFinita"))
        assertEquals(0, righe(s, 1).length())
        val r = righe(s, 0).getJSONObject(0)
        assertEquals(300.0, r.getDouble("daComprare"), 0.001)
        assertEquals(200.0, r.getDouble("presente"), 0.001)
        assertFalse(r.has("carrello"))
    }

    @Test
    fun il_carrello_che_la_lista_non_chiede_piu_esce_soltanto() {
        val s = spesa(JSONObject().put("foodId", "x").put("daComprare", 0.0).put("presente", 0.0).put("necessaria", 0.0)
            .put("carrello", JSONObject().put("id", "a").put("quantita", 100.0)))
        SpesaWidget.applica(s, JSONObject().put("op", "spesaFinita"))
        assertEquals(0, righe(s, 0).length())
        assertEquals(0, righe(s, 1).length())
    }
}
