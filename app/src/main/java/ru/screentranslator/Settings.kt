package ru.screentranslator

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

enum class Provider(val label: String, val models: List<String>) {
    GIGACHAT(
        "GigaChat (Сбер)",
        listOf("GigaChat", "GigaChat-Pro", "GigaChat-Max", "GigaChat-Ultra"),
    ),
    CLOUDFLARE(
        "Cloudflare Workers AI",
        listOf(
            "@cf/meta/llama-3.3-70b-instruct-fp8-fast",
            "@cf/mistralai/mistral-small-3.1-24b-instruct",
            "@cf/qwen/qwen3-30b-a3b-fp8",
            "@cf/openai/gpt-oss-120b",
        ),
    ),
}

enum class OcrScript(val label: String) {
    LATIN("Латиница (англ., европ.)"),
    CHINESE("Китайский"),
    JAPANESE("Японский"),
    KOREAN("Корейский"),
    DEVANAGARI("Деванагари (хинди и др.)"),
}

/** Selected screen area in screen pixels. */
data class Region(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** All settings. API keys are stored in EncryptedSharedPreferences, never in the APK. */
class AppSettings(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "secure_settings",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private fun str(key: String, default: String = ""): String = prefs.getString(key, default) ?: default
    private fun put(key: String, value: String) = prefs.edit().putString(key, value).apply()

    var provider: Provider
        get() = Provider.entries.firstOrNull { it.name == str("provider") } ?: Provider.GIGACHAT
        set(v) = put("provider", v.name)

    var gigachatAuthKey: String
        get() = str("gc_auth")
        set(v) = put("gc_auth", v)

    var gigachatScope: String
        get() = str("gc_scope", "GIGACHAT_API_PERS")
        set(v) = put("gc_scope", v)

    var cloudflareAccountId: String
        get() = str("cf_account")
        set(v) = put("cf_account", v)

    var cloudflareToken: String
        get() = str("cf_token")
        set(v) = put("cf_token", v)

    fun model(p: Provider): String = str("model_${p.name}", p.models.first())
    fun setModel(p: Provider, model: String) = put("model_${p.name}", model)

    var targetLang: String
        get() = str("target_lang", "Russian")
        set(v) = put("target_lang", v)

    var ocrScript: OcrScript
        get() = OcrScript.entries.firstOrNull { it.name == str("ocr", "LATIN") } ?: OcrScript.LATIN
        set(v) = put("ocr", v.name)

    var region: Region?
        get() = str("region").split(",")
            .takeIf { it.size == 4 }
            ?.map { it.toInt() }
            ?.let { Region(it[0], it[1], it[2], it[3]) }
        set(v) {
            val editor = prefs.edit()
            if (v == null) editor.remove("region")
            else editor.putString("region", "${v.left},${v.top},${v.right},${v.bottom}")
            editor.apply()
        }
}
