package com.example.foodtracker.backup

import android.content.Context
import android.content.IntentSender
import android.util.Log
import com.example.foodtracker.data.FoodItem
import com.example.foodtracker.data.GoalStore
import com.example.foodtracker.data.Goals
import com.example.foodtracker.data.Meal
import com.example.foodtracker.data.MealDb
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// ---------------------------------------------------------------- local settings

class BackupPrefs(context: Context) {
    private val p = context.getSharedPreferences("backup", Context.MODE_PRIVATE)

    var email: String?
        get() = p.getString("email", null)
        set(v) { p.edit().putString("email", v).apply() }

    // True once the user has chosen "back up" or "restore" on THIS phone.
    // Auto-backup stays off until then, so a fresh phone can never overwrite the cloud copy.
    var baselineSet: Boolean
        get() = p.getBoolean("baseline", false)
        set(v) { p.edit().putBoolean("baseline", v).apply() }

    var lastBackup: Long
        get() = p.getLong("last", 0L)
        set(v) { p.edit().putLong("last", v).apply() }

    fun clear() { p.edit().clear().apply() }
}

// ---------------------------------------------------------------- Google authorization

sealed interface AuthResult {
    data class Token(val value: String) : AuthResult
    data class NeedsConsent(val sender: IntentSender) : AuthResult
    data class Failure(val message: String) : AuthResult
}

object DriveAuth {
    // Only this app's own hidden folder in the user's Drive
    private val SCOPE = Scope("https://www.googleapis.com/auth/drive.appdata")

    suspend fun authorize(context: Context): AuthResult {
        return try {
            val request = AuthorizationRequest.builder()
                .setRequestedScopes(listOf(SCOPE))
                .build()
            val result = Identity.getAuthorizationClient(context).authorize(request).await()
            val pending = result.pendingIntent
            val token = result.accessToken
            when {
                result.hasResolution() && pending != null ->
                    AuthResult.NeedsConsent(pending.intentSender)
                token != null -> AuthResult.Token(token)
                else -> AuthResult.Failure("Google did not return an access token")
            }
        } catch (e: Exception) {
            AuthResult.Failure(e.message ?: "Google authorization failed")
        }
    }
}

// ---------------------------------------------------------------- Drive REST (blocking: call on IO)

object DriveApi {
    private const val FILE_NAME = "foodtracker-backup.json"
    private const val BASE = "https://www.googleapis.com/drive/v3"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun call(request: Request): String =
        http.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("Google Drive error ${resp.code}: ${body.take(200)}")
            body
        }

    private fun req(url: okhttp3.HttpUrl, token: String) =
        Request.Builder().url(url).header("Authorization", "Bearer $token")

    fun userEmail(token: String): String? {
        val url = "$BASE/about".toHttpUrl().newBuilder()
            .addQueryParameter("fields", "user(emailAddress)").build()
        val body = call(req(url, token).build())
        return JSONObject(body).optJSONObject("user")
            ?.optString("emailAddress")?.takeIf { it.isNotBlank() }
    }

    fun findFileId(token: String): String? {
        val url = "$BASE/files".toHttpUrl().newBuilder()
            .addQueryParameter("spaces", "appDataFolder")
            .addQueryParameter("q", "name = '$FILE_NAME' and trashed = false")
            .addQueryParameter("fields", "files(id)")
            .addQueryParameter("pageSize", "1")
            .build()
        val files = JSONObject(call(req(url, token).build())).optJSONArray("files")
        return if (files != null && files.length() > 0) files.getJSONObject(0).getString("id") else null
    }

    fun download(token: String, fileId: String): String {
        val url = "$BASE/files/$fileId".toHttpUrl().newBuilder()
            .addQueryParameter("alt", "media").build()
        return call(req(url, token).build())
    }

    fun upload(token: String, existingId: String?, json: String) {
        if (existingId != null) {
            val url = "https://www.googleapis.com/upload/drive/v3/files/$existingId?uploadType=media"
                .toHttpUrl()
            call(
                req(url, token)
                    .patch(json.toRequestBody("application/json".toMediaType()))
                    .build()
            )
        } else {
            val meta = JSONObject()
                .put("name", FILE_NAME)
                .put("parents", JSONArray().put("appDataFolder"))
                .toString()
            val body = MultipartBody.Builder()
                .setType("multipart/related".toMediaType())
                .addPart(meta.toRequestBody("application/json; charset=UTF-8".toMediaType()))
                .addPart(json.toRequestBody("application/json".toMediaType()))
                .build()
            val url = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart"
                .toHttpUrl()
            call(req(url, token).post(body).build())
        }
    }
}

// ---------------------------------------------------------------- snapshot format

data class Remote(
    val fileId: String,
    val savedAt: Long,
    val goals: Goals,
    val meals: List<Meal>
)

object BackupCodec {
    fun encode(meals: List<Meal>, goals: Goals): String {
        val arr = JSONArray()
        meals.forEach { m ->
            val items = JSONArray()
            m.items.forEach { items.put(it.toJson()) }
            arr.put(
                JSONObject()
                    .put("ts", m.timestamp)
                    .put("description", m.description)
                    .put("items", items)
            )
        }
        return JSONObject()
            .put("version", 1)
            .put("savedAt", System.currentTimeMillis())
            .put(
                "goals",
                JSONObject()
                    .put("kcal", goals.kcal).put("sugar", goals.sugar).put("fat", goals.fat)
                    .put("satFat", goals.satFat).put("sodium", goals.sodiumMg).put("carbs", goals.carbs)
                    .put("protein", goals.protein).put("fiber", goals.fiber)
            )
            .put("meals", arr)
            .toString()
    }

    fun decode(fileId: String, json: String): Remote {
        val root = JSONObject(json)
        if (root.optInt("version", 0) != 1) error("Unsupported backup file version")
        val g = root.optJSONObject("goals")
        val goals = Goals(
            kcal = g?.optDouble("kcal", 0.0) ?: 0.0,
            sugar = g?.optDouble("sugar", 0.0) ?: 0.0,
            fat = g?.optDouble("fat", 0.0) ?: 0.0,
            satFat = g?.optDouble("satFat", 0.0) ?: 0.0,
            sodiumMg = g?.optDouble("sodium", 0.0) ?: 0.0,
            carbs = g?.optDouble("carbs", 0.0) ?: 0.0,
            protein = g?.optDouble("protein", 0.0) ?: 0.0,
            fiber = g?.optDouble("fiber", 0.0) ?: 0.0
        )
        val arr = root.getJSONArray("meals")
        val meals = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val items = o.getJSONArray("items")
            Meal(
                id = 0,
                timestamp = o.getLong("ts"),
                description = o.optString("description"),
                items = (0 until items.length()).map { FoodItem.fromJson(items.getJSONObject(it)) }
            )
        }
        return Remote(fileId, root.optLong("savedAt", 0L), goals, meals)
    }
}

// ---------------------------------------------------------------- operations

object BackupService {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    fun fetchRemote(token: String): Remote? {
        val id = DriveApi.findFileId(token) ?: return null
        return BackupCodec.decode(id, DriveApi.download(token, id))
    }

    fun backupNow(token: String, db: MealDb, goals: Goals, prefs: BackupPrefs) {
        val json = BackupCodec.encode(db.all(), goals)
        DriveApi.upload(token, DriveApi.findFileId(token), json)
        prefs.lastBackup = System.currentTimeMillis()
    }

    fun applyRemote(db: MealDb, goalStore: GoalStore, remote: Remote, prefs: BackupPrefs) {
        db.replaceAll(remote.meals)
        goalStore.save(remote.goals)
        prefs.lastBackup = remote.savedAt
        prefs.baselineSet = true
    }

    /** Fire-and-forget. Call after any change to meals or goals. Silent on any failure. */
    fun autoBackup(context: Context) {
        val app = context.applicationContext
        val prefs = BackupPrefs(app)
        if (prefs.email == null || !prefs.baselineSet) return
        scope.launch {
            mutex.withLock {
                val db = MealDb(app)
                try {
                    // Never overwrite a cloud copy with an empty phone
                    if (db.count() == 0) return@withLock
                    val token = (DriveAuth.authorize(app) as? AuthResult.Token)?.value
                        ?: return@withLock           // needs the user to consent again: skip silently
                    backupNow(token, db, GoalStore(app).load(), prefs)
                } catch (e: Exception) {
                    Log.w("Backup", "auto-backup failed", e)
                } finally {
                    db.close()
                }
            }
        }
    }
}