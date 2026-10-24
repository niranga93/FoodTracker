package com.example.nourio

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.captureToImage
import androidx.test.core.app.ApplicationProvider
import com.example.nourio.ai.GeminiKeys
import com.example.nourio.backup.BackupService
import com.example.nourio.data.FeaturePrefs
import com.example.nourio.data.FoodItem
import com.example.nourio.data.GoalStore
import com.example.nourio.data.Goals
import com.example.nourio.data.MealDb
import com.example.nourio.data.Profile
import com.example.nourio.data.ProfileStore
import com.example.nourio.data.WaterStore
import com.example.nourio.data.WeightDb
import com.example.nourio.energy.EnergyRepo
import com.example.nourio.health.DayActivity
import com.example.nourio.plan.PlanStore
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Calendar
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Renders the store screenshots from the real screens, with a fictional demo user, on the PC.
 * Skipped unless STORE_SHOTS=1:
 *   set STORE_SHOTS=1 && gradlew :app:testDebugUnitTest --tests "*StoreScreenshotsTest*"
 * Output: release/store/screenshots/raw-render/, framed afterwards by release/tools/FrameScreenshots.java.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h731dp-night-420dpi")
class StoreScreenshotsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val out = File("../release/store/screenshots/raw-render").apply { mkdirs() }
    private lateinit var db: MealDb
    private lateinit var goals: GoalStore

    // name, kcal, protein, fat, satFat, carbs, sugar, fiber, sodium mg (per 100 g)
    private val foods = mapOf(
        "yogurt" to listOf("Greek yogurt", 97, 9, 5, 3.3, 3.6, 3.6, 0, 36),
        "granola" to listOf("Granola", 471, 10, 20, 3, 64, 24, 7, 26),
        "berries" to listOf("Blueberries", 57, 0.7, 0.3, 0, 14.5, 10, 2.4, 1),
        "oats" to listOf("Oatmeal", 71, 2.5, 1.5, 0.3, 12, 0.3, 1.7, 4),
        "banana" to listOf("Banana", 89, 1.1, 0.3, 0.1, 22.8, 12.2, 2.6, 1),
        "egg" to listOf("Boiled eggs", 155, 13, 11, 3.3, 1.1, 1.1, 0, 124),
        "toast" to listOf("Whole grain toast", 247, 13, 3.4, 0.7, 41, 6, 7, 450),
        "avocado" to listOf("Avocado", 160, 2, 14.7, 2.1, 8.5, 0.7, 6.7, 7),
        "chicken" to listOf("Grilled chicken breast", 165, 31, 3.6, 1, 0, 0, 0, 74),
        "rice" to listOf("Brown rice", 123, 2.7, 1, 0.2, 25.6, 0.2, 1.6, 4),
        "broccoli" to listOf("Steamed broccoli", 35, 2.4, 0.4, 0.1, 7.2, 1.4, 3.3, 41),
        "salmon" to listOf("Baked salmon", 206, 22, 12, 2.5, 0, 0, 0, 61),
        "quinoa" to listOf("Quinoa", 120, 4.4, 1.9, 0.2, 21.3, 0.9, 2.8, 7),
        "salad" to listOf("Mixed greens", 17, 1.3, 0.2, 0, 3.3, 1.2, 2.1, 28),
        "lentils" to listOf("Lentil soup", 56, 3.6, 1.1, 0.2, 8, 1.2, 3.2, 300),
        "apple" to listOf("Apple", 52, 0.3, 0.2, 0, 13.8, 10.4, 2.4, 1),
        "almonds" to listOf("Almonds", 579, 21, 50, 3.8, 21.6, 4.4, 12.5, 1),
        "ramen" to listOf("Instant ramen", 188, 4.5, 7.5, 3.4, 26, 1, 1, 1150),
        "pizza" to listOf("Pepperoni pizza", 266, 11, 10, 4.5, 33, 3.6, 2.3, 640)
    )

    private fun item(key: String, grams: Double): FoodItem {
        val f = foods.getValue(key)
        fun d(i: Int) = (f[i] as Number).toDouble()
        return FoodItem(f[0] as String, f[0] as String, grams, d(1), d(2), d(3), d(4), d(5), d(6), d(7), d(8))
    }

    private fun at(offset: Int, hour: Int, minute: Int): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, offset); set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Before
    fun seed() {
        assumeTrue("Set STORE_SHOTS=1 to render store screenshots", System.getenv("STORE_SHOTS") == "1")
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        BackupService.init(ctx); EnergyRepo.init(ctx); GeminiKeys.init(ctx)
        db = MealDb(ctx); goals = GoalStore(ctx)
        val rnd = Random(7)

        ProfileStore(ctx).save(Profile("Alex Morgan", 1992, "Male", 178.0, 78.1))
        goals.save(Goals(kcal = 2100.0, addedSugar = 30.0, fat = 70.0, satFat = 20.0, sodiumMg = 2300.0,
            carbs = 260.0, protein = 110.0, fiber = 30.0, steps = 9000.0))
        PlanStore(ctx).apply { targetKg = 75.0; paceKgPerWeek = 0.25; useTargetGoal = true; carryOver = true }
        FeaturePrefs(ctx).set("in_coach", false)          // the coach needs a Gemini key

        val water = WaterStore(ctx).apply { goalMl = 2200 }
        val weights = WeightDb(ctx)
        var kg = 80.2
        val salty = setOf(-26, -22, -19, -15, -12, -8, -5, -2)     // higher-sodium days for the Insights demo
        for (off in -29..0) {
            val wiggle = 0.92 + rnd.nextDouble() * 0.16
            fun g(base: Int) = (base * wiggle).roundToInt().toDouble()
            when ((off + 30) % 3) {
                0 -> {
                    db.insert("Greek yogurt with granola and berries", listOf(item("yogurt", 200.0), item("granola", 45.0), item("berries", 80.0)), at(off, 8, 5))
                    db.insert("Chicken, brown rice and broccoli", listOf(item("chicken", g(150)), item("rice", g(170)), item("broccoli", 120.0)), at(off, 12, 50))
                    if (off < 0) db.insert("Salmon with quinoa salad", listOf(item("salmon", g(160)), item("quinoa", g(150)), item("salad", 60.0)), at(off, 19, 30))
                }
                1 -> {
                    db.insert("Avocado toast with boiled eggs", listOf(item("toast", 70.0), item("avocado", 70.0), item("egg", 100.0)), at(off, 7, 45))
                    db.insert("Lentil soup and salad", listOf(item("lentils", g(400)), item("salad", 80.0), item("toast", 40.0)), at(off, 13, 10))
                    if (off < 0) db.insert("Chicken stir-fry with rice", listOf(item("chicken", g(140)), item("rice", g(180)), item("broccoli", 100.0)), at(off, 19, 40))
                }
                else -> {
                    db.insert("Oatmeal with banana", listOf(item("oats", 300.0), item("banana", 110.0)), at(off, 8, 15))
                    db.insert("Quinoa bowl with salmon", listOf(item("quinoa", g(180)), item("salmon", g(120)), item("salad", 60.0)), at(off, 12, 40))
                    if (off < 0) db.insert("Pizza night", listOf(item("pizza", g(250)), item("salad", 60.0)), at(off, 20, 5))
                }
            }
            if (off in salty) db.insert("Instant ramen", listOf(item("ramen", 120.0)), at(off, 16, 0))
            if (off < 0) db.insert("Apple and almonds", listOf(item("apple", 180.0), item("almonds", 25.0)), at(off, 16, 30))
            water.add(off, if (off == 0) 1500 else 1800 + rnd.nextInt(6) * 100)
            // A slow downward trend, with water weight the morning after salty days
            weights.add(at(off, 7, 10), (kg + if (off - 1 in salty) 0.6 else 0.0 + rnd.nextDouble() * 0.2).let { (it * 10).roundToInt() / 10.0 })
            kg -= 0.08
        }
        // Steps and activity in place of Health Connect
        EnergyRepo.activityOverride = { off ->
            if (off < -40) null else {
                val steps = if (off == 0) 7_840L else (6_500 + Random(off).nextInt(6_500)).toLong()
                DayActivity(steps, steps * 0.045, 0.0)
            }
        }
    }

    @After
    fun tearDown() {
        EnergyRepo.activityOverride = null
        if (::db.isInitialized) db.close()
    }

    @Composable
    private fun App(tab: NavTab) = AppTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            MainScaffold(tab, {}, db, goals, 0, {}, {}, {}, {}, {}, {}, {}, {}, {})
        }
    }

    private fun waitFor(text: String) =
        rule.waitUntil(20_000) { rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    private fun save(name: String) {
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun s1_home() {
        rule.setContent { App(NavTab.Home) }
        waitFor("TODAY"); waitFor("YOUR BODY")
        save("01-home")
    }

    @Test fun s2_dashboard() {
        rule.setContent { App(NavTab.Home) }
        waitFor("YOUR BODY")
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(3)
        waitFor("This week")
        save("02-dashboard")
    }

    @Test fun s3_intake() {
        rule.setContent { App(NavTab.Intake) }
        rule.waitForIdle()
        save("03-intake")
    }

    @Test fun s4_burned() {
        rule.setContent { App(NavTab.Burned) }
        waitFor("Eaten vs burned")
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(2)
        save("04-burned")
    }

    @Test fun s5_insights() {
        rule.setContent { AppTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { PageScreen("insights", db, goals) {} } } }
        waitFor("Your health patterns")
        save("05-insights")
    }

    @Test fun s5b_insightsLinks() {
        rule.setContent { AppTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { PageScreen("insights", db, goals) {} } } }
        waitFor("Your health patterns")
        // Scroll so the trends heading is at the bottom and the link charts fill the screen
        rule.onNode(hasText("LAST 2 WEEKS VS YOUR USUAL")).performScrollTo()
        save("06-insights-links")
    }

    @Test fun s6_profile() {
        rule.setContent { App(NavTab.Profile) }
        waitFor("Estimated (food − burn)")   // the chart loads after the card
        save("07-profile")
    }
}
