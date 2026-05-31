package qhybrid.android.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import qhybrid.android.db.WaypointEntity

/**
 * WP-TRACKER — unit tests for the PURE [GpxWriter]. Plain JUnit (no Android) — it's string-only.
 */
class GpxWriterTest {

    @Test
    fun emptyListProducesValidEmptyGpx() {
        val gpx = GpxWriter.toGpx(emptyList())
        assertTrue(gpx.contains("<gpx"))
        assertTrue(gpx.contains("</gpx>"))
        assertFalse(gpx.contains("<wpt"))
    }

    @Test
    fun waypointBecomesWptWithLatLonTimeNameType() {
        val gpx = GpxWriter.toGpx(
            listOf(
                WaypointEntity(
                    id = 7, watchMac = "AA", kind = "MAJOR",
                    lat = 48.8584, lon = 2.2945, accuracyM = 4.2f,
                    capturedAt = 0L, note = "Eiffel",
                )
            )
        )
        assertTrue(gpx.contains("lat=\"48.8584000\""))
        assertTrue(gpx.contains("lon=\"2.2945000\""))
        assertTrue(gpx.contains("<time>1970-01-01T00:00:00Z</time>"))
        assertTrue(gpx.contains("<type>MAJOR</type>"))
        assertTrue(gpx.contains("MAJOR #7"))
        assertTrue(gpx.contains("<cmt>Eiffel</cmt>"))
    }

    @Test
    fun noteOmittedWhenBlank() {
        val gpx = GpxWriter.toGpx(
            listOf(WaypointEntity(id = 1, kind = "MINOR", lat = 1.0, lon = 2.0, capturedAt = 0L, note = "  "))
        )
        assertFalse(gpx.contains("<cmt>"))
    }

    @Test
    fun escapesXmlSpecialCharsInNote() {
        val gpx = GpxWriter.toGpx(
            listOf(WaypointEntity(id = 1, kind = "MINOR", lat = 1.0, lon = 2.0, capturedAt = 0L, note = "a & b < c > d"))
        )
        assertTrue(gpx.contains("a &amp; b &lt; c &gt; d"))
    }

    @Test
    fun multipleWaypointsAllRendered() {
        val gpx = GpxWriter.toGpx(
            listOf(
                WaypointEntity(id = 1, kind = "MINOR", lat = 1.0, lon = 1.0, capturedAt = 0L),
                WaypointEntity(id = 2, kind = "MAJOR", lat = 2.0, lon = 2.0, capturedAt = 1000L),
            )
        )
        assertEquals(2, Regex("<wpt ").findAll(gpx).count())
    }
}
