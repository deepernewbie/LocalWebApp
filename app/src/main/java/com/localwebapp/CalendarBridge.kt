package com.localwebapp

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.webkit.JavascriptInterface
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone

// ═══════════════════════════════════════════════════════════════════════════════
// CalendarBridge — real calendar access, not an .ics file.
//
// Writing an .ics and handing it to a share sheet was always a workaround: it
// can only ever create, never read or change, and the user has to complete the
// import by hand. CalendarContract is the actual API — the same one the stock
// Calendar app uses — so the agent can list what's on Thursday, move a meeting
// and delete a cancelled one.
//
// Permissions are requested at run time and nothing here works until the user
// grants them. READ_CALENDAR and WRITE_CALENDAR are ordinary dangerous
// permissions, not "restricted settings", so no Play Protect dance is needed.
//
// Every method returns a JSON string, because that is what crosses the
// JavaScript bridge cleanly. Errors come back as {"error": "..."} rather than
// as exceptions, so the web app can show something useful.
// ═══════════════════════════════════════════════════════════════════════════════
class CalendarBridge(private val activity: Activity) {

    companion object {
        const val REQUEST_CODE = 4711
        val PERMISSIONS = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED

    private fun canRead() = granted(Manifest.permission.READ_CALENDAR)
    private fun canWrite() = granted(Manifest.permission.WRITE_CALENDAR)

    private fun denied() = JSONObject()
        .put("error", "Calendar permission not granted")
        .put("needs_permission", true)
        .toString()

    /** Status, so the web app can explain itself before anything fails. */
    @JavascriptInterface
    fun calendarStatus(): String = try {
        val json = JSONObject()
            .put("read", canRead())
            .put("write", canWrite())
        if (canRead()) json.put("calendars", calendarList())
        json.toString()
    } catch (e: Exception) {
        JSONObject().put("error", e.message ?: "unknown").toString()
    }

    /** Opens the system permission dialog. Result arrives in onRequestPermissionsResult. */
    @JavascriptInterface
    fun calendarRequestPermission(): String {
        if (canRead() && canWrite()) return JSONObject().put("granted", true).toString()
        activity.runOnUiThread {
            activity.requestPermissions(PERMISSIONS, REQUEST_CODE)
        }
        return JSONObject().put("requested", true).toString()
    }

    private fun calendarList(): JSONArray {
        val out = JSONArray()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.IS_PRIMARY
        )
        activity.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, projection, null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val access = c.getInt(3)
                out.put(JSONObject()
                    .put("id", c.getLong(0))
                    .put("name", c.getString(1) ?: "")
                    .put("account", c.getString(2) ?: "")
                    .put("writable", access >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR)
                    .put("primary", c.getInt(4) == 1))
            }
        }
        return out
    }

    /** The calendar we write to by default: the primary one, else any writable one. */
    private fun defaultCalendarId(): Long? {
        val list = calendarList()
        var fallback: Long? = null
        for (i in 0 until list.length()) {
            val cal = list.getJSONObject(i)
            if (!cal.getBoolean("writable")) continue
            if (cal.getBoolean("primary")) return cal.getLong("id")
            if (fallback == null) fallback = cal.getLong("id")
        }
        return fallback
    }

    /**
     * Events overlapping the window. Uses the Instances table rather than
     * Events so that repeating events are expanded into the occurrences the
     * user actually sees.
     */
    @JavascriptInterface
    fun calendarList(startMs: String, endMs: String, limit: Int): String {
        if (!canRead()) return denied()
        return try {
            val from = startMs.toLongOrNull() ?: System.currentTimeMillis()
            val to = endMs.toLongOrNull() ?: (from + 7L * 24 * 3600 * 1000)
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
                .appendPath(from.toString()).appendPath(to.toString()).build()
            val projection = arrayOf(
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.DESCRIPTION,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.CALENDAR_ID
            )
            val events = JSONArray()
            activity.contentResolver.query(
                uri, projection, null, null, CalendarContract.Instances.BEGIN + " ASC"
            )?.use { c ->
                var n = 0
                val cap = if (limit > 0) limit else 100
                while (c.moveToNext() && n < cap) {
                    events.put(JSONObject()
                        .put("id", c.getLong(0))
                        .put("title", c.getString(1) ?: "(no title)")
                        .put("start", c.getLong(2))
                        .put("end", c.getLong(3))
                        .put("location", c.getString(4) ?: "")
                        .put("notes", c.getString(5) ?: "")
                        .put("allDay", c.getInt(6) == 1)
                        .put("calendarId", c.getLong(7)))
                    n++
                }
            }
            JSONObject().put("count", events.length()).put("events", events).toString()
        } catch (e: Exception) {
            JSONObject().put("error", e.message ?: "query failed").toString()
        }
    }

    @JavascriptInterface
    fun calendarCreate(payload: String): String {
        if (!canWrite()) return denied()
        return try {
            val p = JSONObject(payload)
            val calId = if (p.has("calendarId")) p.getLong("calendarId") else defaultCalendarId()
                ?: return JSONObject().put("error", "No writable calendar on this device").toString()

            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calId)
                put(CalendarContract.Events.TITLE, p.optString("title", "Event"))
                put(CalendarContract.Events.DTSTART, p.getLong("start"))
                put(CalendarContract.Events.DTEND, p.optLong("end", p.getLong("start") + 3600000))
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                if (p.optString("location").isNotEmpty())
                    put(CalendarContract.Events.EVENT_LOCATION, p.getString("location"))
                if (p.optString("notes").isNotEmpty())
                    put(CalendarContract.Events.DESCRIPTION, p.getString("notes"))
                if (p.optBoolean("allDay")) put(CalendarContract.Events.ALL_DAY, 1)
            }
            val uri = activity.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                ?: return JSONObject().put("error", "Insert refused").toString()
            val id = ContentUris.parseId(uri)

            // A reminder is the whole point of putting it in the calendar.
            val minutes = p.optInt("remindMinutes", -1)
            if (minutes >= 0) {
                activity.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI,
                    ContentValues().apply {
                        put(CalendarContract.Reminders.EVENT_ID, id)
                        put(CalendarContract.Reminders.MINUTES, minutes)
                        put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                    })
            }
            DebugLog.bridge("Android.calendarCreate", p.optString("title"), "id=$id")
            JSONObject().put("created", true).put("id", id).put("calendarId", calId).toString()
        } catch (e: Exception) {
            JSONObject().put("error", e.message ?: "create failed").toString()
        }
    }

    @JavascriptInterface
    fun calendarUpdate(payload: String): String {
        if (!canWrite()) return denied()
        return try {
            val p = JSONObject(payload)
            val id = p.getLong("id")
            val values = ContentValues()
            if (p.has("title")) values.put(CalendarContract.Events.TITLE, p.getString("title"))
            if (p.has("start")) values.put(CalendarContract.Events.DTSTART, p.getLong("start"))
            if (p.has("end")) values.put(CalendarContract.Events.DTEND, p.getLong("end"))
            if (p.has("location")) values.put(CalendarContract.Events.EVENT_LOCATION, p.getString("location"))
            if (p.has("notes")) values.put(CalendarContract.Events.DESCRIPTION, p.getString("notes"))
            if (values.size() == 0) return JSONObject().put("error", "Nothing to change").toString()

            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)
            val rows = activity.contentResolver.update(uri, values, null, null)
            DebugLog.bridge("Android.calendarUpdate", "id=$id", "$rows row(s)")
            JSONObject().put("updated", rows > 0).put("id", id).toString()
        } catch (e: Exception) {
            JSONObject().put("error", e.message ?: "update failed").toString()
        }
    }

    @JavascriptInterface
    fun calendarDelete(eventId: String): String {
        if (!canWrite()) return denied()
        return try {
            val id = eventId.toLongOrNull()
                ?: return JSONObject().put("error", "Bad event id").toString()
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)
            val rows = activity.contentResolver.delete(uri, null, null)
            DebugLog.bridge("Android.calendarDelete", "id=$id", "$rows row(s)")
            JSONObject().put("deleted", rows > 0).put("id", id).toString()
        } catch (e: Exception) {
            JSONObject().put("error", e.message ?: "delete failed").toString()
        }
    }
}
