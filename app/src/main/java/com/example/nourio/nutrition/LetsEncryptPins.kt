package com.example.nourio.nutrition

/**
 * Public-key pins for Let's Encrypt's root certificates, which Open Food Facts and USDA FoodData Central
 * use. Pinning the roots rather than each site's own certificate keeps working when the site certificate
 * renews (every ~90 days). The roots last for years: Root YR to 2032, X1 to 2035, X2 to 2040.
 * Check with: openssl s_client -showcerts, then the SHA-256 of each certificate's public key.
 */
internal object LetsEncryptPins {
    val ROOTS = listOf(
        "sha256/fk6IOKit1ild5647BH06ujSIq5XbCgqlbYl6ANhhi88=",   // ISRG Root YR
        "sha256/C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M=",   // ISRG Root X1
        "sha256/diGVwiVYbubAI3RW4hB9xU8e/CH2GnkuvVFZE8zmgzI=",   // ISRG Root X2
    )
}
