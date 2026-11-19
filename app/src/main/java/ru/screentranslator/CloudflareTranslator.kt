package ru.screentranslator

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Cloudflare Workers AI.
 * model examples: @cf/meta/llama-3.3-70b-instruct-fp8-fast, @cf/mistralai/mistral-small-3.1-24b-instruct,
 * @cf/qwen/qwen3-30b-a3b-fp8, @cf/openai/gpt-oss-120b
 */
class CloudflareTranslator(
    private val http: OkHttpClient,
    private val accountId: String,
    private val apiToken: String,
    private val model: String,
) : Translator {

    override suspend fun translate(texts: List<String>, targetLang: String): TranslationResult {
        val url = "https://api.cloudflare.com/client/v4/accounts/$accountId/ai/run/$model"
        val body = JSONObject()
            .put("messages", Prompt.messages(texts, targetLang))
            .put("temperature", 0.1)
            .put("max_tokens", 2048)

        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiToken")
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()

        val result = http.jsonCall(req).getJSONObject("result")
        return Prompt.parse(extractText(result), texts.size)
    }

    /** Workers AI models answer in slightly different shapes; handle the common ones. */
    private fun extractText(result: JSONObject): String {
        if (result.has("response")) return result.getString("response")
        if (result.has("choices")) {
            return result.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
        }
        // Responses-style output (e.g. gpt-oss): take the first "message" item.
        val output = result.getJSONArray("output")
        for (i in 0 until output.length()) {
            val item = output.getJSONObject(i)
            if (item.optString("type") == "message") {
                return item.getJSONArray("content").getJSONObject(0).getString("text")
            }
        }
        error("Неизвестный формат ответа Cloudflare")
    }
}
