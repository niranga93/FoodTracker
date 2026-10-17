package com.example.nourio.data

import android.content.Context
import com.example.nourio.data.room.WeightRow
import com.example.nourio.data.room.WeightsDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class WeightEntry(val id: Long, val ts: Long, val kg: Double)

/** Weigh-ins. Backed by Room ([WeightsDatabase], the same weights.db file), with the methods the app always used. */
class WeightDb(context: Context) {
    private val dao = WeightsDatabase.get(context.applicationContext).weights()

    private fun WeightRow.toEntry() = WeightEntry(id, ts, kg)

    fun add(ts: Long, kg: Double): Long = dao.insert(WeightRow(ts = ts, kg = kg))

    fun delete(id: Long) = dao.delete(id)

    /** Oldest first. */
    fun all(): List<WeightEntry> = dao.all().map { it.toEntry() }

    /** Oldest first, re-emitted whenever a weigh-in is added or removed. */
    fun observeAll(): Flow<List<WeightEntry>> = dao.observeAll().map { rows -> rows.map { it.toEntry() } }

    fun replaceAll(entries: List<WeightEntry>) = dao.replaceAll(entries.map { WeightRow(ts = it.ts, kg = it.kg) })

    /** The database is shared and stays open for the app's lifetime; kept so existing callers compile. */
    fun close() {}
}
