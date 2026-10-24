package com.example.nourio.nutrition

import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Connects to the real food databases with the pins. Skipped when PINS_LIVE isn't set (needs internet). */
class PinsLiveTest {
    @Test
    fun pinsMatchTheLiveCertificates() {
        assumeTrue(System.getenv("PINS_LIVE") != null)
        val hosts = listOf("world.openfoodfacts.org", "search.openfoodfacts.org", "api.nal.usda.gov")
        val pinner = CertificatePinner.Builder().apply { hosts.forEach { h -> LetsEncryptPins.ROOTS.forEach { add(h, it) } } }.build()
        val client = OkHttpClient.Builder().certificatePinner(pinner).build()
        hosts.forEach { h -> client.newCall(Request.Builder().url("https://$h/").head().build()).execute().close() }
    }
}
