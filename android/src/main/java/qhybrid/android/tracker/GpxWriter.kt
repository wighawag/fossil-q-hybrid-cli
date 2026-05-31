package qhybrid.android.tracker

import qhybrid.android.db.WaypointEntity
import java.time.Instant
import java.util.Locale

/**
 * WP-TRACKER \u2014 the **pure**, unit-testable GPX serializer for the waypoint log. Turns a list of
 * [WaypointEntity] rows into a GPX 1.1 `<wpt>` document string (no Android, no file I/O). The
 * Android shell ([qhybrid.android.tracker.WaypointsViewModel] / the viewer screen) writes the
 * string to a cache file and shares it via the existing WP15 FileProvider authority
 * (`${applicationId}.fileprovider`).
 *
 * Each waypoint becomes a `<wpt lat lon>` with `<time>` (ISO-8601), a `<name>` (kind + id), a
 * `<type>` (MINOR/MAJOR) and, when present, a `<cmt>` for the note. Coordinates use `Locale.US` so
 * the decimal separator is always `.` regardless of device locale.
 */
object GpxWriter {

    /** Serialize [waypoints] (any order; caller usually passes chronological) into a GPX document. */
    fun toGpx(waypoints: List<WaypointEntity>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append(
            "<gpx version=\"1.1\" creator=\"FossilQ-Tracker\" " +
                "xmlns=\"http://www.topografix.com/GPX/1/1\">\n"
        )
        for (w in waypoints) {
            sb.append(String.format(Locale.US, "  <wpt lat=\"%.7f\" lon=\"%.7f\">\n", w.lat, w.lon))
            sb.append("    <time>").append(Instant.ofEpochMilli(w.capturedAt).toString()).append("</time>\n")
            sb.append("    <name>").append(esc("${w.kind} #${w.id}")).append("</name>\n")
            sb.append("    <type>").append(esc(w.kind)).append("</type>\n")
            val note = w.note?.trim().orEmpty()
            if (note.isNotEmpty()) sb.append("    <cmt>").append(esc(note)).append("</cmt>\n")
            sb.append("  </wpt>\n")
        }
        sb.append("</gpx>\n")
        return sb.toString()
    }

    /** Minimal XML escaping for text nodes. */
    private fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
