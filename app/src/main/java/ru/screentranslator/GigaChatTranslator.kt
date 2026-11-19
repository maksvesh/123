package ru.screentranslator

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID

/**
 * GigaChat (Sber).
 * authKey: the "Authorization key" (Base64) from the Sber developer console.
 * scope: GIGACHAT_API_PERS or GIGACHAT_API_CORP, depending on your subscription.
 * model: exact model name from your console, e.g. GigaChat, GigaChat-Pro, GigaChat-Max.
 */
class GigaChatTranslator(
    private val http: OkHttpClient,
    private val authKey: String,
    private val scope: String,
    private val model: String,
) : Translator {

    private val mutex = Mutex()
    private var token: String? = null
    private var tokenExpiresAt = 0L

    private suspend fun accessToken(): String {
        return mutex.withLock {
            val cached = token
            if (cached != null && System.currentTimeMillis() < tokenExpiresAt - 60_000) {
                return@withLock cached
            }
            val req = Request.Builder()
                .url("https://ngw.devices.sberbank.ru:9443/api/v2/oauth")
                .header("Authorization", "Basic $authKey")
                .header("RqUID", UUID.randomUUID().toString())
                .post(FormBody.Builder().add("scope", scope).build())
                .build()
            val json = http.jsonCall(req)
            val fresh = json.getString("access_token")
            token = fresh
            tokenExpiresAt = json.getLong("expires_at") // milliseconds
            fresh
        }
    }

    override suspend fun translate(texts: List<String>, targetLang: String): TranslationResult {
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0.1)
            .put("messages", Prompt.messages(texts, targetLang))

        val req = Request.Builder()
            .url("https://gigachat.devices.sberbank.ru/api/v1/chat/completions")
            .header("Authorization", "Bearer ${accessToken()}")
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()

        val content = http.jsonCall(req)
            .getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content")
        return Prompt.parse(content, texts.size)
    }
}
