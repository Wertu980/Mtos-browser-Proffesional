package com.mtos.web.browser.data

import com.mtos.web.browser.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

object GeminiHelper {
    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent"
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    suspend fun evaluatePasswordSecurity(password: String, username: String, url: String): String = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            return@withContext "Error: Gemini API Key not configured. Please add it to the Secrets Panel."
        }

        val prompt = """
            You are a cybersecurity expert specializing in Password Auditing and Security Intelligence.
            
            Evaluate this credential for website/app context:
            - Website/App: $url
            - Username/Email: $username
            - Password length: ${password.length} characters
            
            Perform a professional yet easy-to-understand security assessment:
            1. Analyze security features (entropy, variety of characters, visual patterns).
            2. Check for potential vulnerabilities relevant to $url (e.g., standard login vectors, phishing targets, spray attacks).
            3. Explicitly state the estimated cracking time index or vulnerability level.
            4. Suggest 3 concrete actionable visual or textual improvements or generate a better domain-specific password idea for $url that is memorable yet extremely secure.
            
            Keep the report direct, structured with markdown points, professional, and do not use generic AI greetings. Under 200 words.
        """.trimIndent()

        try {
            val jsonPayload = JSONObject().apply {
                put("contents", org.json.JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", org.json.JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", prompt)
                            })
                        })
                    })
                })
            }

            val requestBody = jsonPayload.toString().toRequestBody("application/json".toMediaType())
            
            val request = Request.Builder()
                .url("$BASE_URL?key=$apiKey")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext "Authentication or connection error. Please verify Gemini API key settings in your build dashboard."
                }
                val body = response.body?.string() ?: return@withContext "No response body received from Gemini servers."
                val jsonResponse = JSONObject(body)
                val candidates = jsonResponse.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val firstCandidate = candidates.getJSONObject(0)
                    val content = firstCandidate.optJSONObject("content")
                    val parts = content?.optJSONArray("parts")
                    if (parts != null && parts.length() > 0) {
                        return@withContext parts.getJSONObject(0).optString("text", "Error parsing text.")
                    }
                }
                "Response format was unrecognized. Please retry."
            }
        } catch (e: Exception) {
            e.printStackTrace()
            "Error contacting Gemini intelligence: ${e.localizedMessage ?: "Connection Timeout"}"
        }
    }
}
