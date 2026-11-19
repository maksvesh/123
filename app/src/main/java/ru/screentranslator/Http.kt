package ru.screentranslator

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

/** Executes the request on IO and parses the JSON body. Throws on HTTP errors. */
suspend fun OkHttpClient.jsonCall(request: Request): JSONObject = withContext(Dispatchers.IO) {
    newCall(request).execute().use { resp ->
        val body = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) error("HTTP ${resp.code}: ${body.take(300)}")
        JSONObject(body)
    }
}
