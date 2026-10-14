package com.example.nourio

import androidx.core.net.toUri
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast

// Fill these in once. They appear on the About page and in support emails.
const val DEVELOPER_NAME = "NNRZONE"
const val SUPPORT_EMAIL = "foodtracker777@gmail.com"
const val WEBSITE_URL = ""                                // optional; leave empty to hide
const val PRIVACY_URL = "https://niranga93.github.io/FoodTracker/"     // hosted on GitHub Pages
const val TERMS_URL = ""                                  // optional; leave empty to hide

data class VersionInfo(val name: String, val code: Long)

@Suppress("DEPRECATION")
private fun packageInfo(context: Context) =
    if (Build.VERSION.SDK_INT >= 33) {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }

@Suppress("DEPRECATION")
fun appVersion(context: Context): VersionInfo {
    val info = packageInfo(context)
    val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    return VersionInfo(info.versionName ?: "?", code)
}

private fun start(context: Context, intent: Intent, failMessage: String) {
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, failMessage, Toast.LENGTH_SHORT).show()
    }
}

fun openUrl(context: Context, url: String) =
    start(context, Intent(Intent.ACTION_VIEW, url.toUri()), "No app can open this link")

fun openPrivacyPolicy(context: Context) = openUrl(context, PRIVACY_URL)

/** Prefilled email with the app version and device model only: no personal data. */
fun sendSupportEmail(context: Context) {
    val v = appVersion(context)
    val body = "\n\n---\nNourio ${v.name} (${v.code})\n" +
        "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
        "${Build.MANUFACTURER} ${Build.MODEL}"
    val intent = Intent(Intent.ACTION_SENDTO, "mailto:".toUri()).apply {
        putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT_EMAIL))
        putExtra(Intent.EXTRA_SUBJECT, "Nourio feedback (v${v.name})")
        putExtra(Intent.EXTRA_TEXT, body)
    }
    start(context, intent, "No email app found")
}

private fun storeUrl(context: Context) =
    "https://play.google.com/store/apps/details?id=${context.packageName}"

fun openStorePage(context: Context) {
    val market = Intent(Intent.ACTION_VIEW, "market://details?id=${context.packageName}".toUri())
    try {
        context.startActivity(market)
    } catch (e: ActivityNotFoundException) {
        openUrl(context, storeUrl(context))
    }
}

fun shareApp(context: Context) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, "Try Nourio: ${storeUrl(context)}")
    }
    start(context, Intent.createChooser(send, "Share Nourio"), "Nothing to share with")
}
