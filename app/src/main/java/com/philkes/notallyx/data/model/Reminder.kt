package com.philkes.notallyx.data.model

import android.os.Parcelable
import java.util.Date
import kotlinx.parcelize.Parcelize

@Parcelize
data class Reminder(
    var id: Long,
    var dateTime: Date,
    var repetition: Repetition?,
    var isNotificationVisible: Boolean = false,
    /**
     * If set, this reminder is a location-based reminder that fires when the device enters the
     * given radius around [LocationReminder.latitude]/[LocationReminder.longitude]. [dateTime] is
     * ignored for location reminders.
     */
    var location: LocationReminder? = null,
) : Parcelable

@Parcelize
data class LocationReminder(
    var latitude: Double,
    var longitude: Double,
    /** Radius in meters. */
    var radius: Float,
    var label: String? = null,
) : Parcelable {
    companion object {
        const val MIN_RADIUS_METERS = 50f
        const val MAX_RADIUS_METERS = 10_000f

        fun clampRadius(radius: Float): Float =
            radius.coerceIn(MIN_RADIUS_METERS, MAX_RADIUS_METERS)
    }
}

@Parcelize
data class Repetition(
    var value: Int,
    var unit: RepetitionTimeUnit,
    /**
     * If unit is MONTHS this can be set to repeat every nth occurrence of dayOfWeek in the month.
     */
    var occurrence: Int? = null,
    /**
     * If unit is MONTHS and occurence is set this can be set to repeat every nth occurrence of
     * dayOfWeek in the month.
     */
    var dayOfWeek: Int? = null,
) : Parcelable

enum class RepetitionTimeUnit {
    MINUTES,
    HOURS,
    DAYS,
    WEEKS,
    MONTHS,
    YEARS,
}
