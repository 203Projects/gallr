package com.gallr.shared.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.gallr.shared.observability.AppLog
import com.gallr.shared.route.RouteVisitTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/** Persisted as `draftId|yyyy-MM-dd|HH:mm`; renaming the key or the format needs a migration. */
private val ROUTE_VISIT_TIME_KEY = stringPreferencesKey("route_visit_time")

private val visitTimeLog = AppLog.tagged("RouteVisitTime")

/** [RouteVisitTimeRepository] in the app's preferences DataStore. */
class DataStoreRouteVisitTimeRepository(
    private val dataStore: DataStore<Preferences>,
) : RouteVisitTimeRepository {
    override val visitTime: Flow<StoredVisitTime?> =
        dataStore.data.map { preferences -> preferences[ROUTE_VISIT_TIME_KEY]?.let(::decode) }

    override suspend fun set(
        draftId: String,
        visitTime: RouteVisitTime?,
    ) {
        dataStore.edit { preferences ->
            if (visitTime == null) {
                preferences.remove(ROUTE_VISIT_TIME_KEY)
            } else {
                preferences[ROUTE_VISIT_TIME_KEY] = "$draftId$SEPARATOR${visitTime.day}$SEPARATOR${visitTime.start}"
            }
        }
    }

    private fun decode(stored: String): StoredVisitTime? =
        try {
            val (draftId, day, start) = stored.split(SEPARATOR).also { require(it.size == 3) }
            StoredVisitTime(draftId, RouteVisitTime(LocalDate.parse(day), LocalTime.parse(start)))
        } catch (error: IllegalArgumentException) {
            visitTimeLog.warn("route_visit_time_read_failed", error)
            null
        }
}

private const val SEPARATOR = "|"
