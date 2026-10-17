package com.example.nourio.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import com.example.nourio.data.room.MealsDatabase
import com.example.nourio.data.room.WeightsDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Existing installs keep every meal and weigh-in when the app moves to Room. Each test writes a
 * database exactly as the old SQLiteOpenHelper code did, then opens it through Room.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomMigrationTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before @After fun fresh() {
        MealsDatabase.closeForTests(); WeightsDatabase.closeForTests()
        ctx.deleteDatabase("meals.db"); ctx.deleteDatabase("weights.db"); ctx.deleteDatabase("food_cache.db")
    }

    /** The old meals.db, as version 1 (no index) or 2 (with the time index) created it. */
    private fun oldMeals(version: Int, rows: List<Triple<Long, Long, String>>) {
        object : SQLiteOpenHelper(ctx, "meals.db", null, version) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL("CREATE TABLE meals (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, description TEXT NOT NULL, items_json TEXT NOT NULL)")
                if (version >= 2) db.execSQL("CREATE INDEX IF NOT EXISTS idx_meals_ts ON meals(ts)")
            }
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
        }.writableDatabase.use { db ->
            rows.forEach { (id, ts, desc) ->
                val items = JSONArray().put(FoodItem("Rice", "Rice, white, cooked", 150.0, 130.0, 2.7, 0.3, 0.1, 28.0, 0.1, 0.4, 1.0).toJson())
                db.execSQL("INSERT INTO meals (id, ts, description, items_json) VALUES (?, ?, ?, ?)", arrayOf<Any>(id, ts, desc, items.toString()))
            }
        }
    }

    private val sample = listOf(Triple(3L, 1_700_000_000_000L, "Lunch"), Triple(7L, 1_700_000_500_000L, "Dinner"), Triple(12L, 1_699_999_000_000L, "Breakfast"))

    private fun checkMealsSurvived() {
        val db = MealDb(ctx)
        val meals = db.all()
        assertEquals(listOf(7L, 3L, 12L), meals.map { it.id })                 // newest first, ids kept
        assertEquals(listOf("Dinner", "Lunch", "Breakfast"), meals.map { it.description })
        assertEquals(150.0, meals.first().items.single().grams, 1e-9)            // food items intact
        assertEquals(195.0, meals.first().totals.kcal, 1e-6)
        // New meals continue after the highest old id, so nothing is overwritten
        val newId = db.insert("Snack", emptyList())
        assertTrue("new id $newId", newId > 12)
        assertEquals(4, db.count())
    }

    @Test fun currentInstallsKeepEveryMeal() { oldMeals(2, sample); checkMealsSurvived() }

    @Test fun olderInstallsKeepEveryMeal() { oldMeals(1, sample); checkMealsSurvived() }

    @Test fun weighInsSurvive() {
        object : SQLiteOpenHelper(ctx, "weights.db", null, 1) {
            override fun onCreate(db: SQLiteDatabase) =
                db.execSQL("CREATE TABLE weights (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, kg REAL NOT NULL)")
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
        }.writableDatabase.use { db ->
            db.execSQL("INSERT INTO weights (id, ts, kg) VALUES (5, 1000, 80.4)")
            db.execSQL("INSERT INTO weights (id, ts, kg) VALUES (9, 2000, 79.8)")
        }
        val w = WeightDb(ctx)
        assertEquals(listOf(80.4, 79.8), w.all().map { it.kg })
        assertEquals(listOf(5L, 9L), w.all().map { it.id })
        assertTrue(w.add(3000, 79.5) > 9)
    }

    @Test fun matchCorrectionsSurvive() {
        object : SQLiteOpenHelper(ctx, "food_cache.db", null, 1) {
            override fun onCreate(db: SQLiteDatabase) =
                db.execSQL("CREATE TABLE cache (key TEXT PRIMARY KEY, json TEXT NOT NULL, ts INTEGER NOT NULL)")
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
        }.writableDatabase.use { db ->
            db.execSQL("INSERT INTO cache (key, json, ts) VALUES (?, ?, ?)", arrayOf<Any>("fix|dal", "lentils-json", 1L))
            db.execSQL("INSERT INTO cache (key, json, ts) VALUES (?, ?, ?)", arrayOf<Any>("v1|rice", "rice-json", 2L))
        }
        val cache = com.example.nourio.nutrition.FoodCache(ctx)
        assertEquals(mapOf("fix|dal" to "lentils-json"), cache.dumpPrefix("fix|"))
        assertEquals(mapOf("v1|rice" to "rice-json"), cache.dumpPrefix("v1|"))
        cache.putRaw("fix|dal", "{}")                                          // replaces, not duplicates
        assertEquals(1, cache.dumpPrefix("fix|").size)
    }

    @Test fun freshInstallsWork() {
        val db = MealDb(ctx)
        val id = db.insert("First meal", emptyList(), 1000)
        assertEquals("First meal", db.getById(id)!!.description)
        db.update(id, "Edited", emptyList(), 2000)
        assertEquals(2000L, db.getById(id)!!.timestamp)
        db.replaceAll(listOf(Meal(0, 5, "Restored", emptyList())))
        assertEquals(listOf("Restored"), db.all().map { it.description })
    }

    @Test fun flowsUpdateWhenMealsChange() = runBlocking {
        val db = MealDb(ctx)
        assertEquals(0, db.observeBetween(0, 10_000).first().size)
        db.insert("Lunch", emptyList(), 5_000)
        assertEquals(listOf("Lunch"), db.observeBetween(0, 10_000).first().map { it.description })
    }
}
