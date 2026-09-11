package com.example.floorplan

import android.content.Context
import androidx.compose.ui.geometry.Offset
import com.example.SurroundingWall
import com.example.TrackingSubject
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A wall (or door opening) drawn by the user on the calibrated blueprint grid. */
data class PlanWall(
    val id: String = UUID.randomUUID().toString(),
    val start: Offset,
    val end: Offset,
    val kind: String = KIND_WALL // KIND_WALL | KIND_DOOR
) {
    companion object {
        const val KIND_WALL = "wall"
        const val KIND_DOOR = "door"
    }
}

/** A named room label pinned to the blueprint. */
data class RoomLabel(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val anchor: Offset
)

/** A real discovered device (router or LAN client) placed into a specific room. */
data class PlacedDevice(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val ip: String?,
    val mac: String?,
    val vendor: String?,
    val cameraSuspect: Boolean,
    val isRouter: Boolean,
    val offset: Offset
)

/**
 * One calibrated, per-building-story ("floor") plan. Positions are normalized 0..1
 * coordinates of the square blueprint canvas; [metersPerGrid] converts grid cells to
 * real meters (user-calibrated — a phone CANNOT image walls through concrete by radio).
 */
data class FloorPlan(
    val floor: Int,
    val metersPerGrid: Float,
    val walls: List<PlanWall>,
    val rooms: List<RoomLabel>,
    val devices: List<PlacedDevice>
) {
    companion object {
        fun empty(floor: Int): FloorPlan =
            FloorPlan(floor = floor, metersPerGrid = 1.0f, walls = emptyList(), rooms = emptyList(), devices = emptyList())

        const val GRID_CELLS = 24 // 24x24 architect grid
    }
}

fun floorLabel(floor: Int): String = when {
    floor < 0 -> "Basement ${-floor}"
    floor == 0 -> "Ground Floor"
    else -> "Floor $floor"
}

/** Converts blueprint walls to the radar's SurroundingWall model (same normalized space). */
fun FloorPlan.toSurroundingWalls(): List<SurroundingWall> =
    walls.filter { it.kind == PlanWall.KIND_WALL }.mapIndexed { index, wall ->
        SurroundingWall(start = wall.start, end = wall.end, name = "${floorLabel(floor)} • Wall ${index + 1}")
    }

/** Converts placed devices to radar tracking subjects. */
fun FloorPlan.toTrackingSubjects(): List<TrackingSubject> =
    devices.map { device ->
        TrackingSubject(
            name = if (device.isRouter) "📶 ${device.name}" else device.name,
            type = "Device",
            offset = device.offset
        )
    }

/** SharedPreferences-backed per-floor persistence (JSON, no extra dependencies). */
class FloorPlanStore(context: Context) {

    private val prefs = context.getSharedPreferences("emf_floorplans_v1", Context.MODE_PRIVATE)

    fun load(floor: Int): FloorPlan? {
        val raw = prefs.getString(key(floor), null) ?: return null
        return try {
            parse(JSONObject(raw))
        } catch (t: Throwable) {
            null
        }
    }

    fun save(plan: FloorPlan) {
        prefs.edit().putString(key(plan.floor), serialize(plan).toString()).apply()
    }

    fun savedFloors(): List<Int> =
        prefs.all.keys.mapNotNull { it.removePrefix("floor_").toIntOrNull() }.sorted()

    private fun key(floor: Int) = "floor_$floor"

    private fun putOffset(obj: JSONObject, prefix: String, o: Offset) {
        obj.put("${prefix}X", o.x.toDouble()).put("${prefix}Y", o.y.toDouble())
    }

    private fun getOffset(obj: JSONObject, prefix: String): Offset =
        Offset(obj.optDouble("${prefix}X", 0.5).toFloat(), obj.optDouble("${prefix}Y", 0.5).toFloat())

    private fun serialize(plan: FloorPlan): JSONObject {
        val root = JSONObject()
        root.put("floor", plan.floor)
        root.put("metersPerGrid", plan.metersPerGrid.toDouble())

        val walls = JSONArray()
        plan.walls.forEach { wall ->
            val w = JSONObject()
            w.put("id", wall.id).put("kind", wall.kind)
            putOffset(w, "s", wall.start)
            putOffset(w, "e", wall.end)
            walls.put(w)
        }
        root.put("walls", walls)

        val rooms = JSONArray()
        plan.rooms.forEach { room ->
            val r = JSONObject()
            r.put("id", room.id).put("name", room.name)
            putOffset(r, "a", room.anchor)
            rooms.put(r)
        }
        root.put("rooms", rooms)

        val devices = JSONArray()
        plan.devices.forEach { device ->
            val d = JSONObject()
            d.put("id", device.id)
            d.put("name", device.name)
            d.put("ip", device.ip ?: JSONObject.NULL)
            d.put("mac", device.mac ?: JSONObject.NULL)
            d.put("vendor", device.vendor ?: JSONObject.NULL)
            d.put("cameraSuspect", device.cameraSuspect)
            d.put("isRouter", device.isRouter)
            putOffset(d, "p", device.offset)
            devices.put(d)
        }
        root.put("devices", devices)
        return root
    }

    private fun parse(root: JSONObject): FloorPlan {
        fun JSONObject.optStr(name: String): String? =
            if (has(name) && !isNull(name)) optString(name, null) else null

        val walls = mutableListOf<PlanWall>()
        val wallsArr = root.optJSONArray("walls") ?: JSONArray()
        for (i in 0 until wallsArr.length()) {
            val w = wallsArr.getJSONObject(i)
            walls.add(
                PlanWall(
                    id = w.optString("id", UUID.randomUUID().toString()),
                    start = getOffset(w, "s"),
                    end = getOffset(w, "e"),
                    kind = w.optString("kind", PlanWall.KIND_WALL)
                )
            )
        }

        val rooms = mutableListOf<RoomLabel>()
        val roomsArr = root.optJSONArray("rooms") ?: JSONArray()
        for (i in 0 until roomsArr.length()) {
            val r = roomsArr.getJSONObject(i)
            rooms.add(
                RoomLabel(
                    id = r.optString("id", UUID.randomUUID().toString()),
                    name = r.optString("name", "Room"),
                    anchor = getOffset(r, "a")
                )
            )
        }

        val devices = mutableListOf<PlacedDevice>()
        val devicesArr = root.optJSONArray("devices") ?: JSONArray()
        for (i in 0 until devicesArr.length()) {
            val d = devicesArr.getJSONObject(i)
            devices.add(
                PlacedDevice(
                    id = d.optString("id", UUID.randomUUID().toString()),
                    name = d.optString("name", "Device"),
                    ip = d.optStr("ip"),
                    mac = d.optStr("mac"),
                    vendor = d.optStr("vendor"),
                    cameraSuspect = d.optBoolean("cameraSuspect", false),
                    isRouter = d.optBoolean("isRouter", false),
                    offset = getOffset(d, "p")
                )
            )
        }

        return FloorPlan(
            floor = root.optInt("floor", 0),
            metersPerGrid = root.optDouble("metersPerGrid", 1.0).toFloat().coerceIn(0.25f, 10f),
            walls = walls,
            rooms = rooms,
            devices = devices
        )
    }
}
