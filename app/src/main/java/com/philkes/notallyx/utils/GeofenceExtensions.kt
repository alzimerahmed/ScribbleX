package com.philkes.notallyx.utils

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.philkes.notallyx.data.model.LocationReminder
import com.philkes.notallyx.presentation.activity.note.reminders.ReminderReceiver
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val TAG = "GeofenceExtensions"

/**
 * Pure geofence math, kept free of Android dependencies so it can be unit-tested without
 * Robolectric.
 */
object GeofenceLogic {

    private const val EARTH_RADIUS_METERS = 6_371_000.0

    /** Great-circle distance between two coordinates in meters (haversine formula). */
    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val haversine =
            sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) *
                    cos(Math.toRadians(lat2)) *
                    sin(dLng / 2) *
                    sin(dLng / 2)
        return 2 * EARTH_RADIUS_METERS * asin(kotlin.math.sqrt(haversine))
    }

    fun isWithinRadius(
        lat1: Double,
        lng1: Double,
        lat2: Double,
        lng2: Double,
        radiusMeters: Float,
    ): Boolean = distanceMeters(lat1, lng1, lat2, lng2) <= radiusMeters

    /** Radius clamped to the range [LocationReminder.MIN_RADIUS_METERS]..[MAX_RADIUS_METERS]. */
    fun clampRadius(radiusMeters: Float): Float = LocationReminder.clampRadius(radiusMeters)
}

/**
 * FOSS geofencing via AOSP [LocationManager] proximity alerts — no Google Play Services.
 *
 * Rationale: [LocationManager.addProximityAlert] is system-managed (the OS wakes the app only on
 * boundary crossings), so it is more battery-respectful than periodic WorkManager polling and works
 * on any FOSS ROM without GMS. Requires [Manifest.permission.ACCESS_FINE_LOCATION] and, on Android
 * 10+, [Manifest.permission.ACCESS_BACKGROUND_LOCATION] because alerts fire while the app is in the
 * background.
 */
fun Context.hasLocationPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

fun Context.hasBackgroundLocationPermission(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    } else {
        hasLocationPermission()
    }

@SuppressLint("MissingPermission")
fun Context.scheduleLocationReminder(noteId: Long, reminderId: Long, location: LocationReminder) {
    if (!hasLocationPermission()) {
        Log.w(TAG, "scheduleLocationReminder: missing location permission, skipping")
        return
    }
    val locationManager = getSystemService<LocationManager>() ?: return
    val radius = GeofenceLogic.clampRadius(location.radius)
    val pendingIntent = createLocationReminderIntent(noteId, reminderId)
    try {
        // -1L = never expires until explicitly cancelled or the reminder is deleted.
        locationManager.addProximityAlert(
            location.latitude,
            location.longitude,
            radius,
            -1L,
            pendingIntent,
        )
        Log.d(
            TAG,
            "scheduleLocationReminder: noteId: $noteId reminderId: $reminderId lat: ${location.latitude} lng: ${location.longitude} radius: $radius",
        )
    } catch (e: SecurityException) {
        Log.w(TAG, "scheduleLocationReminder: permission revoked", e)
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "scheduleLocationReminder: invalid provider/arguments", e)
    }
}

fun Context.cancelLocationReminder(noteId: Long, reminderId: Long) {
    val locationManager = getSystemService<LocationManager>() ?: return
    val pendingIntent = createLocationReminderIntent(noteId, reminderId)
    try {
        locationManager.removeProximityAlert(pendingIntent)
    } catch (e: SecurityException) {
        Log.w(TAG, "cancelLocationReminder: permission revoked", e)
    }
    pendingIntent.cancel()
}

private fun Context.createLocationReminderIntent(noteId: Long, reminderId: Long): PendingIntent {
    val intent =
        Intent(this, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_LOCATION_REMINDER
            putExtra(ReminderReceiver.EXTRA_NOTE_ID, noteId)
            putExtra(ReminderReceiver.EXTRA_REMINDER_ID, reminderId)
        }
    return PendingIntent.getBroadcast(
        this,
        requestCode(noteId, reminderId),
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )
}

/**
 * Stable request code per (noteId, reminderId) so re-registering a proximity alert replaces the old
 * one instead of stacking duplicates.
 */
fun requestCode(noteId: Long, reminderId: Long): Int = (noteId * 31 + reminderId).toInt()
