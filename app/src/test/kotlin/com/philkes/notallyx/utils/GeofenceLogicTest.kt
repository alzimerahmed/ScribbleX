package com.philkes.notallyx.utils

import com.philkes.notallyx.data.model.LocationReminder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeofenceLogicTest {

    @Test
    fun `distance between identical points is zero`() {
        assertEquals(0.0, GeofenceLogic.distanceMeters(52.5200, 13.4050, 52.5200, 13.4050), 0.001)
    }

    @Test
    fun `distance Berlin to Paris is roughly known value`() {
        // Berlin (52.5200, 13.4050) -> Paris (48.8566, 2.3522) ~ 878 km great-circle
        val distance = GeofenceLogic.distanceMeters(52.5200, 13.4050, 48.8566, 2.3522)
        assertEquals(878_000.0, distance, 5_000.0)
    }

    @Test
    fun `one degree of latitude is about 111 km`() {
        val distance = GeofenceLogic.distanceMeters(0.0, 0.0, 1.0, 0.0)
        assertEquals(111_195.0, distance, 500.0)
    }

    @Test
    fun `point within radius is detected`() {
        // ~75m north of center
        val within = GeofenceLogic.isWithinRadius(52.5200, 13.4050, 52.52067, 13.4050, 100f)
        assertTrue(within)
    }

    @Test
    fun `point outside radius is rejected`() {
        // ~150m north of center
        val outside = GeofenceLogic.isWithinRadius(52.5200, 13.4050, 52.52135, 13.4050, 100f)
        assertFalse(outside)
    }

    @Test
    fun `boundary at exactly radius is inside`() {
        val radius = GeofenceLogic.distanceMeters(0.0, 0.0, 0.0, 0.001).toFloat() + 0.5f
        assertTrue(GeofenceLogic.isWithinRadius(0.0, 0.0, 0.0, 0.001, radius))
    }

    @Test
    fun `radius is clamped to minimum`() {
        assertEquals(LocationReminder.MIN_RADIUS_METERS, GeofenceLogic.clampRadius(1f))
    }

    @Test
    fun `radius is clamped to maximum`() {
        assertEquals(LocationReminder.MAX_RADIUS_METERS, GeofenceLogic.clampRadius(999_999f))
    }

    @Test
    fun `valid radius is unchanged`() {
        assertEquals(250f, GeofenceLogic.clampRadius(250f))
    }

    @Test
    fun `request code is stable per note and reminder`() {
        assertEquals(requestCode(12L, 34L), requestCode(12L, 34L))
        assertFalse(requestCode(12L, 34L) == requestCode(12L, 35L))
    }
}
