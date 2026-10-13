package com.example.foodtracker.ai

import android.content.Context
import com.example.foodtracker.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class KeyStatus { OK, LIMITED, INVALID, OFFLINE }

object GeminiKeys {
    private const val SLOTS = 3

    @Volatile private var appContext: Context? = null

    /** Bumped whenever keys change, so cooldowns can be reset. */
    @Volatile var version: Int = 0
        private set

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun prefs() = appContext?.getSharedPreferences("gemini_user_keys", Context.MODE_PRIVATE)

    /** Three slots, decrypted. Empty slots are null. */
    fun slots(): List<String?> {
        val p = prefs()
        return (0 until SLOTS).map { i ->
            p?.getString("k$i", null)?.let { Crypto.decrypt(it) }?.takeIf { it.isNotBlank() }
        }
    }

    fun save(key: String): Boolean {
        val p = prefs() ?: return false
        val current = slots()
        if (current.contains(key)) return false
        val i = current.indexOfFirst { it == null }
        if (i < 0) return false
        p.edit().putString("k$i", Crypto.encrypt(key)).apply()
        version++
        return true
    }

    fun remove(index: Int) {
        prefs()?.edit()?.remove("k$index")?.apply()
        version++
    }

    private fun builtIn(): List<String> = listOf(
        BuildConfig.GEMINI_API_KEY, BuildConfig.GEMINI_API_KEY_2, BuildConfig.GEMINI_API_KEY_3
    ).filter { it.isNotBlank() }

    /** The user's own keys first. Keys from local.properties are used in debug builds only. */
    fun list(): List<String> = slots().filterNotNull() + if (BuildConfig.DEBUG) builtIn() else emptyList()

    fun hasAny(): Boolean = list().isNotEmpty()

    fun mask(key: String): String = key.take(4) + "••••" + key.takeLast(4)

    /** Lists models: free, and it doesn't use up your generation quota. */
    suspend fun test(key: String): KeyStatus = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1")
                .header("x-goog-api-key", key)
                .build()
            client.newCall(request).execute().use { r ->
                when {
                    r.isSuccessful -> KeyStatus.OK
                    r.code == 429 -> KeyStatus.LIMITED
                    r.code in listOf(400, 401, 403) -> KeyStatus.INVALID
                    else -> KeyStatus.OFFLINE
                }
            }
        } catch (e: IOException) {
            KeyStatus.OFFLINE
        }
    }
}
