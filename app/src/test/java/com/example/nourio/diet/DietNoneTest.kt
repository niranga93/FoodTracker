package com.example.nourio.diet

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DietNoneTest {
    @Test
    fun noDietAndNoAllergiesAreRemembered() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val store = DietStore(ctx)
        store.save(DietSettings(noDiet = true, noAllergies = true))
        val back = store.load()
        assertTrue(back.noDiet && back.noAllergies)
        // Nothing to warn about, so food checks and the coach treat it as no restrictions
        assertTrue(back.isEmpty())
        store.save(back.copy(noAllergies = false, allergens = setOf("milk")))
        assertFalse(store.load().noAllergies)
    }
}
