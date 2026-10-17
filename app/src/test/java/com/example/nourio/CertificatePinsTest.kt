package com.example.nourio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.time.LocalDate
import java.util.Base64
import javax.xml.parsers.DocumentBuilderFactory

/** Guards the certificate pins in res/xml/network_security_config.xml. */
class CertificatePinsTest {
    private val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
        listOf(File("src/main/res/xml/network_security_config.xml"), File("app/src/main/res/xml/network_security_config.xml")).first { it.exists() }
    )

    private fun all(tag: String) = doc.getElementsByTagName(tag).let { l -> (0 until l.length).map { l.item(it) as Element } }

    @Test fun everyFoodDatabaseHostIsPinned() {
        val domains = all("domain").map { it.textContent.trim() }.toSet()
        // The hosts NutritionLookup and OpenFoodFacts call
        assertEquals(setOf("api.nal.usda.gov", "world.openfoodfacts.org", "search.openfoodfacts.org"), domains)
    }

    @Test fun pinsAreRealSha256HashesWithBackups() {
        val pins = all("pin")
        assertTrue("a backup pin is required", pins.size >= 2)
        pins.forEach {
            assertEquals("SHA-256", it.getAttribute("digest"))
            assertEquals(32, Base64.getDecoder().decode(it.textContent.trim()).size)
        }
        assertEquals(pins.size, pins.map { it.textContent.trim() }.toSet().size)
    }

    /** Fails a build within 90 days of the expiry, as a reminder to refresh the pins in a release. */
    @Test fun pinsDontExpireSoon() {
        val expiry = LocalDate.parse(all("pin-set").single().getAttribute("expiration"))
        assertTrue("Pins expire on $expiry: check the hosts' chains and renew", expiry.isAfter(LocalDate.now().plusDays(90)))
    }
}
