package com.ryan.launcher

import android.content.Context
import org.json.JSONObject

/** Settings stored as one JSON blob; the phone customizer reads/writes the same JSON. */
class Cfg(ctx: Context) {
    private val sp = ctx.getSharedPreferences("lite", Context.MODE_PRIVATE)

    @Volatile
    var o: JSONObject = try { JSONObject(sp.getString("cfg", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        private set

    fun s(k: String, d: String): String = o.optString(k, d)
    fun b(k: String, d: Boolean): Boolean = o.optBoolean(k, d)
    fun f(k: String, d: Double): Float = o.optDouble(k, d).toFloat()
    fun list(k: String): List<String>? {
        val a = o.optJSONArray(k) ?: return null
        return (0 until a.length()).map { a.optString(it) }
    }

    @Synchronized
    fun replace(n: JSONObject) {
        o = n
        sp.edit().putString("cfg", n.toString()).apply()
    }
}
