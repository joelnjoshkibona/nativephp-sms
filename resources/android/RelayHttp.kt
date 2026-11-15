package com.blutrixx.plugins.nativephp_sms

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** The relay's HTTP calls (BACKEND Relay module). An interface so tests can stand in for the server. */
interface RelayHttp {
    class Failed(val status: Int, val code: String, message: String) : RuntimeException(message)

    /** POST or GET `{url}/api{path}`, with the device token when given. Throws Failed on a non-2xx answer. */
    fun call(url: String, method: String, path: String, body: JSONObject? = null, token: String? = null): JSONObject

    companion object {
        @Volatile var current: RelayHttp = UrlRelayHttp
    }
}

object UrlRelayHttp : RelayHttp {
    override fun call(url: String, method: String, path: String, body: JSONObject?, token: String?): JSONObject {
        val conn = URL(url.trimEnd('/') + "/api" + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Accept", "application/json")
            token?.let { conn.setRequestProperty("X-Relay-Token", it) }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            val text = (if (status in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (status !in 200..299) {
                throw RelayHttp.Failed(status, json.optString("code", "http_$status"), json.optString("message", "The relay answered $status."))
            }
            return json
        } finally {
            conn.disconnect()
        }
    }
}
