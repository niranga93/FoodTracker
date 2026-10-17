package com.example.nourio.data.room

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

// The same files and tables the app used with raw SQLite, now managed by Room. Existing data is moved
// over by the migrations at the bottom; the column names are unchanged.

// ------------------------------------------------------------------ meals.db

@Entity(tableName = "meals", indices = [Index(value = ["ts"], name = "idx_meals_ts")])
data class MealRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val description: String,
    @ColumnInfo(name = "items_json") val itemsJson: String,
)

@Dao
interface MealDao {
    @Insert fun insert(row: MealRow): Long
    @Insert fun insertAll(rows: List<MealRow>)

    @Query("SELECT * FROM meals WHERE id = :id") fun byId(id: Long): MealRow?
    @Query("UPDATE meals SET ts = :ts, description = :description, items_json = :itemsJson WHERE id = :id")
    fun update(id: Long, ts: Long, description: String, itemsJson: String)
    @Query("UPDATE meals SET ts = :ts WHERE id = :id") fun updateTimestamp(id: Long, ts: Long)
    @Query("DELETE FROM meals WHERE id = :id") fun delete(id: Long)
    @Query("DELETE FROM meals") fun deleteAll()

    @Query("SELECT * FROM meals WHERE ts >= :start AND ts < :end ORDER BY ts DESC")
    fun between(start: Long, end: Long): List<MealRow>

    /** The same, updating whenever a meal in the database changes. */
    @Query("SELECT * FROM meals WHERE ts >= :start AND ts < :end ORDER BY ts DESC")
    fun observeBetween(start: Long, end: Long): Flow<List<MealRow>>

    @Query("SELECT * FROM meals ORDER BY ts DESC LIMIT :limit") fun latest(limit: Int): List<MealRow>
    @Query("SELECT ts FROM meals ORDER BY ts") fun timestamps(): List<Long>
    @Query("SELECT COUNT(*) FROM meals") fun count(): Int

    /**
     * Emits on every change to the meals table (Room re-runs the query each time, even when the count
     * is the same), so a screen can refresh whatever it shows from meals.
     */
    @Query("SELECT COUNT(*) FROM meals")
    fun observeChanges(): Flow<Int>

    @Transaction
    fun replaceAll(rows: List<MealRow>) { deleteAll(); insertAll(rows) }
}

@Database(entities = [MealRow::class], version = 3, exportSchema = false)
abstract class MealsDatabase : RoomDatabase() {
    abstract fun meals(): MealDao

    companion object {
        @Volatile private var instance: MealsDatabase? = null
        @Volatile private var owner: Context? = null

        // One database per app process. Tests start a new app each time, so a change of app reopens it.
        fun get(context: Context): MealsDatabase = instance?.takeIf { owner === context.applicationContext } ?: synchronized(this) {
            if (owner !== context.applicationContext) { instance?.close(); instance = null; owner = context.applicationContext }
            instance ?: Room.databaseBuilder(context.applicationContext, MealsDatabase::class.java, "meals.db")
                .addMigrations(MEALS_1_3, MEALS_2_3)
                // Some screens still read on the main thread, as they did with raw SQLite; they move to
                // Flows step by step
                .allowMainThreadQueries()
                .build().also { instance = it }
        }

        /** For tests: forget the open database so the next [get] starts again. */
        internal fun closeForTests() = synchronized(this) { instance?.close(); instance = null }
    }
}

// ------------------------------------------------------------------ weights.db

@Entity(tableName = "weights")
data class WeightRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val kg: Double,
)

@Dao
interface WeightDao {
    @Insert fun insert(row: WeightRow): Long
    @Insert fun insertAll(rows: List<WeightRow>)
    @Query("DELETE FROM weights WHERE id = :id") fun delete(id: Long)
    @Query("DELETE FROM weights") fun deleteAll()
    @Query("SELECT * FROM weights ORDER BY ts ASC") fun all(): List<WeightRow>
    @Query("SELECT * FROM weights ORDER BY ts ASC") fun observeAll(): Flow<List<WeightRow>>

    @Transaction
    fun replaceAll(rows: List<WeightRow>) { deleteAll(); insertAll(rows) }
}

@Database(entities = [WeightRow::class], version = 2, exportSchema = false)
abstract class WeightsDatabase : RoomDatabase() {
    abstract fun weights(): WeightDao

    companion object {
        @Volatile private var instance: WeightsDatabase? = null
        @Volatile private var owner: Context? = null

        fun get(context: Context): WeightsDatabase = instance?.takeIf { owner === context.applicationContext } ?: synchronized(this) {
            if (owner !== context.applicationContext) { instance?.close(); instance = null; owner = context.applicationContext }
            instance ?: Room.databaseBuilder(context.applicationContext, WeightsDatabase::class.java, "weights.db")
                .addMigrations(WEIGHTS_1_2)
                .allowMainThreadQueries()
                .build().also { instance = it }
        }

        internal fun closeForTests() = synchronized(this) { instance?.close(); instance = null }
    }
}

// ------------------------------------------------------------------ food_cache.db
//
// Not only a cache: rows whose key starts with the corrections prefix are the matches the user chose
// by hand, so the migration keeps every row.

@Entity(tableName = "cache")
data class CacheRow(
    @PrimaryKey val key: String,
    val json: String,
    val ts: Long,
)

@Dao
interface CacheDao {
    @Query("SELECT json FROM cache WHERE `key` = :key") fun json(key: String): String?
    @androidx.room.Upsert fun upsert(row: CacheRow)
    @Query("SELECT * FROM cache WHERE `key` LIKE :prefix || '%'") fun withPrefix(prefix: String): List<CacheRow>
    @Query("DELETE FROM cache WHERE `key` LIKE :prefix || '%'") fun deletePrefix(prefix: String)
}

@Database(entities = [CacheRow::class], version = 2, exportSchema = false)
abstract class FoodCacheDatabase : RoomDatabase() {
    abstract fun cache(): CacheDao

    companion object {
        @Volatile private var instance: FoodCacheDatabase? = null
        @Volatile private var owner: Context? = null

        fun get(context: Context): FoodCacheDatabase = instance?.takeIf { owner === context.applicationContext } ?: synchronized(this) {
            if (owner !== context.applicationContext) { instance?.close(); instance = null; owner = context.applicationContext }
            instance ?: Room.databaseBuilder(context.applicationContext, FoodCacheDatabase::class.java, "food_cache.db")
                .addMigrations(CACHE_1_2)
                .allowMainThreadQueries()
                .build().also { instance = it }
        }
    }
}

// ------------------------------------------------------------------ migrations
//
// Room checks the table layout exactly. The old tables declared `id INTEGER PRIMARY KEY AUTOINCREMENT`
// without NOT NULL, which Room reads as a different column, so each table is rebuilt in Room's form
// and every row copied across with its id, inside the migration's transaction.

private fun rebuildMeals(db: SupportSQLiteDatabase) {
    db.execSQL(
        "CREATE TABLE `meals_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `ts` INTEGER NOT NULL, " +
            "`description` TEXT NOT NULL, `items_json` TEXT NOT NULL)"
    )
    db.execSQL("INSERT INTO `meals_new` (id, ts, description, items_json) SELECT id, ts, description, items_json FROM `meals`")
    db.execSQL("DROP TABLE `meals`")
    db.execSQL("ALTER TABLE `meals_new` RENAME TO `meals`")
    db.execSQL("CREATE INDEX IF NOT EXISTS `idx_meals_ts` ON `meals` (`ts`)")
}

/** Installs from before the time index existed. */
val MEALS_1_3 = object : Migration(1, 3) { override fun migrate(db: SupportSQLiteDatabase) = rebuildMeals(db) }
val MEALS_2_3 = object : Migration(2, 3) { override fun migrate(db: SupportSQLiteDatabase) = rebuildMeals(db) }

val CACHE_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE `cache_new` (`key` TEXT NOT NULL, `json` TEXT NOT NULL, `ts` INTEGER NOT NULL, PRIMARY KEY(`key`))")
        db.execSQL("INSERT INTO `cache_new` (`key`, json, ts) SELECT `key`, json, ts FROM `cache` WHERE `key` IS NOT NULL")
        db.execSQL("DROP TABLE `cache`")
        db.execSQL("ALTER TABLE `cache_new` RENAME TO `cache`")
    }
}

val WEIGHTS_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE `weights_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `ts` INTEGER NOT NULL, `kg` REAL NOT NULL)")
        db.execSQL("INSERT INTO `weights_new` (id, ts, kg) SELECT id, ts, kg FROM `weights`")
        db.execSQL("DROP TABLE `weights`")
        db.execSQL("ALTER TABLE `weights_new` RENAME TO `weights`")
    }
}
