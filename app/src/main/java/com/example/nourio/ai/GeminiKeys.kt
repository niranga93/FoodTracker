package com.example.nourio.ai

import androidx.core.content.edit
import android.content.Context
import com.example.nourio.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class KeyStatus { OK, LIMITED, INVALID, OFFLINE }

object GeminiKeys {
    const val SLOTS = 10

    @Volatile private var appContext: Context? = null

    /** Bumped whenever keys change, so cooldowns can be reset. */
    @Volatile var version: Int = 0
        private set

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // Set to false to see the locked screens in a debug build, as they appear in a release build
    private const val USE_DEV_KEYS_IN_DEBUG = true

    private val _available = MutableStateFlow(false)

    /** True when at least one usable key exists. The UI watches this. */
    val available: StateFlow<Boolean> = _available.asStateFlow()

    private fun refresh() {
        _available.value = list().isNotEmpty()
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        refresh()
    }

    private fun prefs() = appContext?.getSharedPreferences("gemini_user_keys", Context.MODE_PRIVATE)

    // Decrypted slots, kept in memory until a key is saved or removed, so requests skip the Keystore
    @Volatile private var cache: List<String?>? = null

    /** All slots, decrypted. Empty slots are null. */
    fun slots(): List<String?> {
        cache?.let { return it }
        val p = prefs() ?: return List(SLOTS) { null }
        return (0 until SLOTS).map { i ->
            p.getString("k$i", null)?.let { Crypto.decrypt(it) }?.takeIf { it.isNotBlank() }
        }.also { cache = it }
    }

    /** How many of the [SLOTS] hold a key. */
    fun count(): Int = slots().count { it != null }

    fun save(key: String): Boolean {
        val p = prefs() ?: return false
        val current = slots()
        if (current.contains(key)) return false
        val i = current.indexOfFirst { it == null }
        if (i < 0) return false
        p.edit { putString("k$i", Crypto.encrypt(key)) }
        cache = null
        version++
        refresh()
        return true
    }

    fun remove(index: Int) {
        prefs()?.edit { remove("k$index") }
        cache = null
        version++
        refresh()
    }

    private fun builtIn(): List<String> = listOf(
        BuildConfig.GEMINI_API_KEY, BuildConfig.GEMINI_API_KEY_2, BuildConfig.GEMINI_API_KEY_3
    ).filter { it.isNotBlank() }

    /** The user's own keys first. Keys from local.properties are used in debug builds only. */
    fun list(): List<String> =
        slots().filterNotNull() + if (BuildConfig.DEBUG && USE_DEV_KEYS_IN_DEBUG) builtIn() else emptyList()

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
