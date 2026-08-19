/*
 * Copyright (C) 2019-2023 qwq233 <qwq233@qwq2333.top>
 * https://github.com/qwq233/Nullgram
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this software.
 *  If not, see
 * <https://www.gnu.org/licenses/>
 */

package xyz.nextalone.nnngram.translate.providers

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.json.JSONObject
import xyz.nextalone.gen.Config
import xyz.nextalone.nnngram.translate.BaseTranslator
import xyz.nextalone.nnngram.utils.Log
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * @author NextAlone
 * @date 2024/11/04 01:14
 *
 */
object DeepLxTranslator : BaseTranslator() {

    const val API_TOKEN_PLACEHOLDER = "(API_TOKEN)"

    private val targetLanguages = listOf(
        "ar", "bg", "cs", "da", "de", "de-CH", "el", "en-GB", "en-US",
        "es", "es-419", "et", "fi", "fr", "fr-CA", "he", "hu", "id", "it",
        "ja", "ko", "lt", "lv", "nb", "nl", "pl", "pt-BR", "pt-PT", "ro",
        "ru", "sk", "sl", "sv", "tr", "uk", "vi", "zh-Hans", "zh-Hant"
    )

    override fun getTargetLanguages(): List<String> = targetLanguages

    override fun convertLanguageCode(language: String, country: String?): String {
        val languageLowerCase = language.lowercase(Locale.ROOT)
        val countryUpperCase = country?.uppercase(Locale.ROOT).orEmpty()
        return when (languageLowerCase) {
            "en" -> if (countryUpperCase == "GB") "en-GB" else "en-US"
            "pt" -> if (countryUpperCase == "PT") "pt-PT" else "pt-BR"
            "zh" -> if (countryUpperCase in setOf("TW", "HK", "MO")) "zh-Hant" else "zh-Hans"
            "de" -> if (countryUpperCase == "CH") "de-CH" else "de"
            "fr" -> if (countryUpperCase == "CA") "fr-CA" else "fr"
            "no" -> "nb"
            "iw" -> "he"
            "in" -> "id"
            else -> canonicalTargetLanguage(languageLowerCase)
        }
    }

    override fun supportLanguage(language: String): Boolean =
        targetLanguages.contains(canonicalTargetLanguage(language))

    override fun getTargetLanguage(language: String): String = if (language == "app") {
        getCurrentAppLanguage()
    } else {
        canonicalTargetLanguage(language)
    }

    private fun canonicalTargetLanguage(language: String): String {
        val normalized = language.replace('_', '-').lowercase(Locale.ROOT)
        return when (normalized) {
            "en" -> "en-US"
            "pt" -> "pt-BR"
            "zh", "zh-cn", "zh-sg" -> "zh-Hans"
            "zh-tw", "zh-hk", "zh-mo" -> "zh-Hant"
            "no" -> "nb"
            "iw" -> "he"
            "in" -> "id"
            else -> targetLanguages.firstOrNull { it.equals(normalized, ignoreCase = true) } ?: normalized
        }
    }

    override suspend fun translateText(text: String, from: String, to: String): RequestResult {
        Log.d("text: $text")
        Log.d("from: $from")
        Log.d("to: $to")
        if (from == to) {
            return RequestResult(from, text)
        }
        val apiUrl = resolveApiUrl()

        val response = try {
            client.post(apiUrl) {
                contentType(ContentType.Application.Json)
                setBody(getRequestBody(text, from, to))
            }
        } catch (e: Exception) {
            // A request exception may contain the expanded URL. Do not leak API tokens to logs/UI.
            throw IOException("DeepLX request failed (${e.javaClass.simpleName})")
        }
        response.let {
            when (it.status) {
                HttpStatusCode.OK -> {
                    val jsonObject = JSONObject(it.bodyAsText())
                    if (jsonObject.has("error")) {
                        throw IOException(jsonObject.getString("message"))
                    }
                    return RequestResult(
                        jsonObject.optString("source_lang", from),
                        jsonObject.getString("data")
                    )
                }

                else -> {
                    Log.w(it.bodyAsText())
                    return RequestResult(from, null, it.status)
                }
            }
        }
    }

    private fun resolveApiUrl(): String {
        val configuredUrl = Config.deepLxApi.trim()
        if (configuredUrl.isEmpty()) {
            throw IOException("DeepLX API URL is empty")
        }
        val configuredToken = Config.deepLxApiToken.trim()
        if (configuredUrl.contains(API_TOKEN_PLACEHOLDER) && configuredToken.isEmpty()) {
            throw IOException("DeepLX API token is empty")
        }
        val encodedToken = URLEncoder.encode(configuredToken, StandardCharsets.UTF_8.toString())
            .replace("+", "%20")
        val resolvedUrl = configuredUrl.replace(API_TOKEN_PLACEHOLDER, encodedToken)
        val uri = runCatching { URI(resolvedUrl) }.getOrNull()
        if (uri == null || uri.host.isNullOrEmpty() ||
            (!uri.scheme.equals("http", ignoreCase = true) && !uri.scheme.equals("https", ignoreCase = true))) {
            throw IOException("DeepLX API URL is invalid")
        }
        return resolvedUrl
    }

    private fun getRequestBody(text: String, from: String, to: String): String {
        val params = JSONObject().apply {
            put("text", text)
            put("source_lang", from)
            put("target_lang", to)
        }

        return params.toString()
    }
}
