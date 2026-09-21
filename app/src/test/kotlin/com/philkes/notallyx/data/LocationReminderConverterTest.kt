package com.philkes.notallyx.data

import com.philkes.notallyx.data.model.ConverterErrorReporter
import com.philkes.notallyx.data.model.Converters
import com.philkes.notallyx.data.model.LocationReminder
import com.philkes.notallyx.data.model.Reminder
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocationReminderConverterTest {

    private val converters = Converters

    @Test
    fun `reminder with location round-trips through JSON`() {
        val reminder =
            Reminder(
                id = 3L,
                dateTime = Date(1700000000000L),
                repetition = null,
                isNotificationVisible = false,
                location = LocationReminder(48.8588443, 2.2943506, 250f, "Eiffel Tower"),
            )

        val json = converters.remindersToJson(listOf(reminder))
        val result = converters.jsonToReminders(json)

        assertEquals(1, result.size)
        val parsed = result[0]
        assertEquals(3L, parsed.id)
        assertEquals(48.8588443, parsed.location!!.latitude, 1e-9)
        assertEquals(2.2943506, parsed.location!!.longitude, 1e-9)
        assertEquals(250f, parsed.location!!.radius)
        assertEquals("Eiffel Tower", parsed.location!!.label)
    }

    @Test
    fun `reminder without location round-trips and has null location`() {
        val reminder = Reminder(1L, Date(1700000000000L), null)

        val json = converters.remindersToJson(listOf(reminder))
        val parsed = converters.jsonToReminders(json)

        assertEquals(1, parsed.size)
        assertNull(parsed[0].location)
        assertEquals(1L, parsed[0].id)
        assertEquals(Date(1700000000000L), parsed[0].dateTime)
    }

    @Test
    fun `legacy JSON without location key parses with null location`() {
        val legacyJson =
            """
            [
                {
                    "id": 7,
                    "dateTime": 1700000000000,
                    "repetition": null,
                    "isNotificationVisible": false
                }
            ]
        """
                .trimIndent()

        val parsed = converters.jsonToReminders(legacyJson)

        assertEquals(1, parsed.size)
        assertNull(parsed[0].location)
    }

    @Test
    fun `location radius is clamped to valid range on parse`() {
        val json =
            """
            [
                {
                    "id": 1,
                    "dateTime": 0,
                    "repetition": null,
                    "isNotificationVisible": false,
                    "location": {"latitude": 1.0, "longitude": 2.0, "radius": 99999999}
                }
            ]
        """
                .trimIndent()

        val parsed = converters.jsonToReminders(json)

        assertEquals(LocationReminder.MAX_RADIUS_METERS, parsed[0].location!!.radius)
    }

    @Test
    fun `malformed location JSON results in null location not crash`() {
        // Disable error reporting: LiveData.postValue is unavailable in plain JUnit tests.
        ConverterErrorReporter.enabled.set(false)
        try {
            val json =
                """
            [
                {
                    "id": 1,
                    "dateTime": 0,
                    "repetition": null,
                    "isNotificationVisible": false,
                    "location": {"latitude": "not-a-number"}
                }
            ]
        """
                    .trimIndent()

            val parsed = converters.jsonToReminders(json)

            assertEquals(1, parsed.size)
            assertNull(parsed[0].location)
        } finally {
            ConverterErrorReporter.enabled.set(true)
        }
    }

    @Test
    fun `location without optional label round-trips`() {
        val reminder =
            Reminder(
                1L,
                Date(0L),
                null,
                location = LocationReminder(-33.8567844, 151.2153063, 50f, null),
            )

        val parsed = converters.jsonToReminders(converters.remindersToJson(listOf(reminder)))

        assertNull(parsed[0].location!!.label)
        assertEquals(50f, parsed[0].location!!.radius)
    }
}
