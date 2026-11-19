package ru.screentranslator

import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

data class TranslationResult(val sourceLang: String, val translations: List<String>)

/** One implementation per provider. Input and output lists have the same order and length. */
interface Translator {
    suspend fun translate(texts: List<String>, targetLang: String): TranslationResult
}

fun buildTranslator(settings: AppSettings, http: OkHttpClient): Translator = when (settings.provider) {
    Provider.GIGACHAT -> GigaChatTranslator(
        http = http,
        authKey = android.util.Base64.encodeToString(
            "${settings.gigachatClientId}:${settings.gigachatClientSecret}".toByteArray(),
            android.util.Base64.NO_WRAP,
        ),
        scope = settings.gigachatScope,
        model = settings.model(Provider.GIGACHAT),
    )
    Provider.CLOUDFLARE -> CloudflareTranslator(
        http = http,
        accountId = settings.cloudflareAccountId,
        apiToken = settings.cloudflareToken,
        model = settings.model(Provider.CLOUDFLARE),
    )
}

/** Shared prompt and parsing, so every provider behaves the same way. */
object Prompt {
    private val SYSTEM = """
        You are a translation engine for on-screen text (UI, subtitles, chats, menus).
        1. Detect the source language of the input texts. It may differ between items.
        2. Translate each text into the target language. Keep the meaning, names, numbers and punctuation.
        3. Do not explain, comment or add anything.
        Reply ONLY with JSON of this exact shape:
        {"source_lang": "<source language name>", "translations": ["...", "..."]}
        The translations array must have the same length and order as the input array.
    """.trimIndent()

    fun messages(texts: List<String>, targetLang: String): JSONArray = JSONArray()
        .put(JSONObject().put("role", "system").put("content", SYSTEM))
        .put(
            JSONObject().put("role", "user")
                .put("content", "Target language: $targetLang\nInput: ${JSONArray(texts)}")
        )

    fun parse(raw: String, expected: Int): TranslationResult {
        // Some models (e.g. Qwen 3) emit reasoning in <think> blocks; drop them.
        val cleaned = raw.replace(Regex("(?s)<think>.*?</think>"), "")
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        require(start >= 0 && end > start) { "Модель вернула не JSON" }
        val obj = JSONObject(cleaned.substring(start, end + 1))
        val arr = obj.getJSONArray("translations")
        require(arr.length() == expected) { "Модель вернула ${arr.length()} переводов вместо $expected" }
        return TranslationResult(
            sourceLang = obj.optString("source_lang", "unknown"),
            translations = List(arr.length()) { arr.getString(it) },
        )
    }
}
