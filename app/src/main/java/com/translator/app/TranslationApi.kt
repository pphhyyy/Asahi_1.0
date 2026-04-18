package com.translator.app

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 大模型 API 翻译客户端，兼容 OpenAI 格式
 */
class TranslationApi(private val config: AppConfig) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    /**
     * 调用大模型翻译文本
     */
    suspend fun translate(text: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val sourceLang = config.sourceLang
            val targetLang = config.targetLang

            val systemPrompt = "你是一个专业翻译器。将用户输入的${sourceLang}文本翻译成${targetLang}。" +
                "只输出翻译结果，不要添加任何解释、注释或额外内容。" +
                "输入中可能包含 |||| 分隔符，表示不同的文字区域。" +
                "你必须保留所有 |||| 分隔符在输出中的对应位置，每个区域独立翻译。"

            val requestBody = JsonObject().apply {
                addProperty("model", config.apiModel)
                add("messages", gson.toJsonTree(listOf(
                    mapOf("role" to "system", "content" to systemPrompt),
                    mapOf("role" to "user", "content" to text)
                )))
                addProperty("temperature", 0.3)
                addProperty("max_tokens", 4096)
            }

            val request = Request.Builder()
                .url(config.apiUrl)
                .addHeader("Authorization", "Bearer ${config.apiKey}")
                .addHeader("Content-Type", "application/json")
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext Result.failure(Exception("空响应"))

            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("API 错误 ${response.code}: $body"))
            }

            val json = gson.fromJson(body, JsonObject::class.java)
            val content = json
                .getAsJsonArray("choices")
                ?.get(0)?.asJsonObject
                ?.getAsJsonObject("message")
                ?.get("content")?.asString
                ?: return@withContext Result.failure(Exception("解析响应失败"))

            Result.success(content.trim())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
