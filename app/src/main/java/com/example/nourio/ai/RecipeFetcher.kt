package com.example.nourio.ai

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.Inet6Address
import java.util.concurrent.TimeUnit

/** Turns a shared link into recipe text. Plain text passes through unchanged. */
object RecipeFetcher {
    private val URL_REGEX = Regex("https?://\\S+")
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        // Shared links come from other apps, so never let one reach this phone or its local network.
        // Checked on the connected address, which also covers redirects and DNS names.
        .addNetworkInterceptor { chain ->
            val addr = chain.connection()?.route()?.socketAddress?.address
            if (addr == null || addr.isLoopbackAddress || addr.isSiteLocalAddress ||
                addr.isLinkLocalAddress || addr.isAnyLocalAddress || addr.isMulticastAddress ||
                (addr is Inet6Address && (addr.address[0].toInt() and 0xfe) == 0xfc)      // IPv6 unique local
            ) throw IOException("Blocked local address")
            chain.proceed(chain.request())
        }
        .build()

    fun expand(text: String): String {
        val found = URL_REGEX.find(text)?.value?.trimEnd('.', ',', ')', ']') ?: return text
        // Cleartext is blocked app-wide, so ask for the https version of an http link
        val url = if (found.startsWith("http://")) "https://" + found.removePrefix("http://") else found
        AiProgress.event("Reading the recipe page")
        val page = runCatching { download(url) }.getOrNull()
            ?: error("Couldn't read that page. Copy the ingredients and share them as text instead.")
        val body = fromJsonLd(page) ?: plainText(page)
        com.example.nourio.achievements.Achievements.markRecipeLink()
        val intro = text.replace(found, "").trim()
        return (if (intro.isNotBlank()) "$intro\n\n" else "") + body.take(6000)
    }

    private fun download(url: String): String {
        val request = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) Nourio/1.0")
            .header("Accept", "text/html,application/xhtml+xml")
            .build()
        return client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            r.peekBody(2_000_000).string()
        }
    }

    // Most recipe sites describe the recipe in a standard JSON block (schema.org Recipe)
    private fun fromJsonLd(html: String): String? {
        val scripts = Regex(
            "<script[^>]*application/ld\\+json[^>]*>(.*?)</script>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        for (m in scripts.findAll(html)) {
            val raw = m.groupValues[1].trim()
            val node: Any = runCatching {
                if (raw.startsWith("[")) JSONArray(raw) else JSONObject(raw)
            }.getOrNull() ?: continue
            val recipe = findRecipe(node) ?: continue
            return describe(recipe)
        }
        return null
    }

    private fun findRecipe(node: Any?): JSONObject? {
        when (node) {
            is JSONObject -> {
                val t = node.opt("@type")
                val isRecipe = (t is String && t == "Recipe") ||
                    (t is JSONArray && (0 until t.length()).any { t.optString(it) == "Recipe" })
                if (isRecipe) return node
                node.optJSONArray("@graph")?.let { g -> findRecipe(g)?.let { return it } }
            }
            is JSONArray -> for (i in 0 until node.length()) findRecipe(node.opt(i))?.let { return it }
        }
        return null
    }

    private fun describe(r: JSONObject): String {
        val servings = when (val y = r.opt("recipeYield")) {
            is JSONArray -> (0 until y.length()).joinToString(" / ") { y.optString(it) }
            null -> ""
            else -> y.toString()
        }
        val ings = r.optJSONArray("recipeIngredient")
        val list = if (ings == null) emptyList() else (0 until ings.length()).map { ings.optString(it) }
        val kcal = r.optJSONObject("nutrition")?.optString("calories")
        return buildString {
            appendLine("Recipe: ${r.optString("name")}")
            if (servings.isNotBlank()) appendLine("Yield: $servings")
            if (!kcal.isNullOrBlank()) appendLine("Stated calories per serving: $kcal")
            appendLine("Ingredients:")
            list.forEach { appendLine("- $it") }
        }
    }

    private fun plainText(html: String): String =
        html.replace(Regex("<(script|style)[^>]*>.*?</\\1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), " ")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ").replace("&amp;", "&")
            .replace(Regex("\\s+"), " ")
            .trim()
}
