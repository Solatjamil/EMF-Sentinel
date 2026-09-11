package com.example.floorplan

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.net.LanDevice
import com.example.net.WifiContextInfo
import com.example.ui.theme.*
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import android.graphics.Paint as NPaint
import android.graphics.Typeface as NTypeface

private enum class MapperTool(val label: String, val hint: String) {
    WALL("WALL", "Drag on the grid to draw a structural wall"),
    DOOR("DOOR", "Drag on a wall to mark a door/opening"),
    ROOM("ROOM", "Tap to pin a room name"),
    DEVICE("DEVICE", "Tap to place the next discovered LAN device · drag a device to move it into a room"),
    ROUTER("ROUTER", "Tap where the Wi-Fi router actually sits"),
    ERASE("ERASE", "Tap an item to remove it")
}

/**
 * ARCHITECT FLOOR-PLAN MAPPER
 *
 * A calibrated, per-building-story blueprint editor. This is the honest way to get
 * "correct wall maps like an architect" from a phone: consumer Wi-Fi radios cannot
 * image walls through concrete — the structure is drawn/calibrated by the user,
 * while everything RF-related that CAN be measured (connected SSID/RSSI, FSPL
 * distance estimate, real LAN device list, vendors) is pulled live from the radio.
 *
 * Device markers sit in the exact rooms the user drags them into, linked to the
 * router with dotted lines — and applying the plan renders the same walls + dotted
 * links onto the Bio-Sync radar for live tracking.
 */
@Composable
fun FloorPlanMapperDialog(
    store: FloorPlanStore,
    startFloor: Int,
    wifi: WifiContextInfo,
    lanDevices: List<LanDevice>,
    onApply: (FloorPlan) -> Unit,
    onDismiss: () -> Unit
) {
    var floor by remember { mutableIntStateOf(startFloor) }
    var plan by remember { mutableStateOf(store.load(floor) ?: FloorPlan.empty(floor)) }
    var tool by remember { mutableStateOf(MapperTool.WALL) }

    // In-progress wall drag (normalized coords)
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragCurrent by remember { mutableStateOf<Offset?>(null) }
    var movingDeviceId by remember { mutableStateOf<String?>(null) }
    var statusLine by remember { mutableStateOf(MapperTool.WALL.hint) }
    var showRoomNameDialog by remember { mutableStateOf(false) }
    var pendingRoomAnchor by remember { mutableStateOf(Offset.Zero) }
    var roomName by remember { mutableStateOf("") }

    fun switchFloor(newFloor: Int) {
        store.save(plan.copy(floor = floor))
        floor = newFloor
        plan = store.load(newFloor) ?: FloorPlan.empty(newFloor)
    }

    val unplacedDevices = plan.devices.let { placed ->
        lanDevices.filter { lan -> placed.none { it.ip != null && it.ip == lan.ip } && !lan.isSelf(placed) }
    }

    // Native text paints for blueprint annotations
    val labelPaint = remember {
        NPaint(NPaint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(230, 226, 232, 240)
            textSize = 26f
            typeface = NTypeface.create(NTypeface.MONOSPACE, NTypeface.BOLD)
        }
    }
    val dimPaint = remember {
        NPaint(NPaint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(200, 52, 211, 153)
            textSize = 22f
            typeface = NTypeface.MONOSPACE
        }
    }
    val smallPaint = remember {
        NPaint(NPaint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(170, 148, 163, 184)
            textSize = 19f
            typeface = NTypeface.MONOSPACE
        }
    }

    Dialog(
        onDismissRequest = {
            store.save(plan.copy(floor = floor))
            onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            colors = CardDefaults.cardColors(containerColor = Zinc950),
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, Zinc800)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // ── Header ────────────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "🏛 ARCHITECT FLOOR-PLAN MAPPER",
                            color = Emerald500,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "Calibrated blueprint • per-story walls & devices",
                            color = Slate500,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(Zinc900)
                            .clickable {
                                store.save(plan.copy(floor = floor))
                                onDismiss()
                            },
                        contentAlignment = Alignment.Center
                    ) { Text("✕", color = Slate400, fontSize = 13.sp) }
                }

                // ── Building story (floor) selector ───────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(SpaceBlack)
                        .border(1.dp, Zinc800, RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("◀", color = Emerald400, fontSize = 16.sp,
                        modifier = Modifier.clickable { switchFloor(floor - 1) })
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = floorLabel(floor).uppercase(),
                            color = Slate50,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "walls ${plan.walls.size} • rooms ${plan.rooms.size} • devices ${plan.devices.size}",
                            color = Slate500,
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Text("▶", color = Emerald400, fontSize = 16.sp,
                        modifier = Modifier.clickable { switchFloor(floor + 1) })
                }

                // ── Live RF chip (real data) ──────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Emerald500.copy(alpha = 0.06f))
                        .border(1.dp, Emerald500.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "📶 ${wifi.ssid ?: "No Wi-Fi"}  ${wifi.rssi?.let { "$it dBm" } ?: ""}",
                        color = Emerald400, fontSize = 9.sp, fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = wifi.estimatedDistanceMeters?.let { "≈ ${it}m FSPL est." } ?: "router dist n/a",
                        color = Slate500, fontSize = 9.sp, fontFamily = FontFamily.Monospace
                    )
                }

                // ── Tool selector ─────────────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    MapperTool.entries.forEach { t ->
                        val selected = tool == t
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selected) Emerald500.copy(alpha = 0.18f) else SpaceBlack)
                                .border(1.dp, if (selected) Emerald500 else Zinc800, RoundedCornerShape(8.dp))
                                .clickable {
                                    tool = t
                                    statusLine = t.hint
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = t.label,
                                color = if (selected) Emerald400 else Slate400,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }

                // ── Blueprint canvas ──────────────────────────────────────
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(SpaceBlack)
                        .border(1.dp, Zinc800, RoundedCornerShape(14.dp))
                ) {
                    androidx.compose.foundation.Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(tool, plan) {
                                detectTapGestures { tap ->
                                    val nx: Float
                                    val ny: Float
                                    val s = size
                                    nx = (tap.x / s.width).coerceIn(0f, 1f)
                                    ny = (tap.y / s.height).coerceIn(0f, 1f)
                                    when (tool) {
                                        MapperTool.ROOM -> {
                                            pendingRoomAnchor = Offset(nx, ny)
                                            roomName = ""
                                            showRoomNameDialog = true
                                        }
                                        MapperTool.DEVICE -> {
                                            val next = unplacedDevices.firstOrNull()
                                            if (next != null) {
                                                plan = plan.copy(
                                                    devices = plan.devices + PlacedDevice(
                                                        name = next.vendor.ifBlank { next.ip },
                                                        ip = next.ip, mac = next.mac,
                                                        vendor = next.vendor,
                                                        cameraSuspect = next.cameraChipsetSuspect,
                                                        isRouter = false,
                                                        offset = Offset(nx, ny)
                                                    )
                                                )
                                                statusLine = "Placed ${next.vendor.ifBlank { next.ip }} — drag it into the right room"
                                            } else {
                                                statusLine = "All discovered devices placed. Run LAN SCAN again for new ones."
                                            }
                                        }
                                        MapperTool.ROUTER -> {
                                            val existing = plan.devices.filter { !it.isRouter }
                                            plan = plan.copy(
                                                devices = existing + PlacedDevice(
                                                    name = wifi.ssid ?: "Wi-Fi Router",
                                                    ip = lanDevices.firstOrNull { it.vendor.contains("Router") }?.ip,
                                                    mac = wifi.bssid,
                                                    vendor = "Router / Gateway",
                                                    cameraSuspect = false,
                                                    isRouter = true,
                                                    offset = Offset(nx, ny)
                                                )
                                            )
                                            statusLine = "Router anchored at (${"%.2f".format(nx)}, ${"%.2f".format(ny)})"
                                        }
                                        MapperTool.ERASE -> {
                                            plan = eraseNearest(plan, Offset(nx, ny))
                                            statusLine = "Removed nearest item (if any was in reach)"
                                        }
                                        else -> Unit
                                    }
                                }
                            }
                            .pointerInput(tool, plan) {
                                detectDragGestures(
                                    onDragStart = { start ->
                                        if (tool == MapperTool.DEVICE) {
                                            val hit = nearestDevice(plan, Offset(start.x / size.width, start.y / size.height))
                                            if (hit != null) {
                                                movingDeviceId = hit.id
                                                return@detectDragGestures
                                            }
                                        }
                                        if (tool == MapperTool.WALL || tool == MapperTool.DOOR) {
                                            val w = size.width.toFloat()
                                            val h = size.height.toFloat()
                                            dragStart = snapToGrid(Offset(start.x / w, start.y / h))
                                            dragCurrent = dragStart
                                        }
                                    },
                                    onDrag = { change, _ ->
                                        val w = size.width.toFloat()
                                        val h = size.height.toFloat()
                                        val pos = Offset(
                                            (change.position.x / w).coerceIn(0f, 1f),
                                            (change.position.y / h).coerceIn(0f, 1f)
                                        )
                                        val moving = movingDeviceId
                                        if (moving != null) {
                                            plan = plan.copy(
                                                devices = plan.devices.map {
                                                    if (it.id == moving) it.copy(offset = pos) else it
                                                }
                                            )
                                        } else if (dragStart != null) {
                                            dragCurrent = snapAngle(dragStart!!, snapToGrid(pos))
                                        }
                                    },
                                    onDragEnd = {
                                        val s0 = dragStart
                                        val e0 = dragCurrent
                                        if (movingDeviceId != null) {
                                            movingDeviceId = null
                                            store.save(plan.copy(floor = floor))
                                        } else if (s0 != null && e0 != null &&
                                            (e0 - s0).getDistance() > 0.01f
                                        ) {
                                            plan = plan.copy(
                                                walls = plan.walls + PlanWall(
                                                    start = s0,
                                                    end = e0,
                                                    kind = if (tool == MapperTool.DOOR) PlanWall.KIND_DOOR else PlanWall.KIND_WALL
                                                )
                                            )
                                            val cells = (e0 - s0).getDistance() * FloorPlan.GRID_CELLS
                                            statusLine = "${if (tool == MapperTool.DOOR) "Door" else "Wall"} ${"%.1f".format(cells * plan.metersPerGrid)}m added"
                                        }
                                        dragStart = null
                                        dragCurrent = null
                                    },
                                    onDragCancel = {
                                        dragStart = null
                                        dragCurrent = null
                                        movingDeviceId = null
                                    }
                                )
                            }
                    ) {
                        val w = size.width
                        val h = size.height
                        val cells = FloorPlan.GRID_CELLS
                        val cell = w / cells

                        // Graph paper
                        for (i in 0..cells) {
                            val major = i % 4 == 0
                            val c = Slate800.copy(alpha = if (major) 0.35f else 0.14f)
                            drawLine(c, Offset(i * cell, 0f), Offset(i * cell, h), (if (major) 1f else 0.5f).dp.toPx())
                            drawLine(c, Offset(0f, i * cell), Offset(w, i * cell), (if (major) 1f else 0.5f).dp.toPx())
                        }

                        fun metersOf(a: Offset, b: Offset): Float =
                            (b - a).getDistance() * cells * plan.metersPerGrid

                        // ── Walls (double-line architectural style) ───────
                        plan.walls.forEach { wall ->
                            val a = Offset(wall.start.x * w, wall.start.y * h)
                            val b = Offset(wall.end.x * w, wall.end.y * h)
                            if (wall.kind == PlanWall.KIND_DOOR) {
                                drawLine(
                                    Amber500, a, b, 3.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
                                )
                            } else {
                                drawLine(Emerald500.copy(alpha = 0.22f), a, b, 6.dp.toPx())
                                drawLine(Emerald400, a, b, 2.2f.dp.toPx())
                            }
                            // Dimension label at midpoint
                            val mid = (a + b) / 2f
                            drawContext.canvas.nativeCanvas.drawText(
                                "%.1f m".format(metersOf(wall.start, wall.end)),
                                mid.x + 6f, mid.y - 6f, dimPaint
                            )
                        }

                        // In-progress wall preview
                        val s0 = dragStart; val e0 = dragCurrent
                        if (s0 != null && e0 != null) {
                            val a = Offset(s0.x * w, s0.y * h)
                            val b = Offset(e0.x * w, e0.y * h)
                            drawLine(
                                Emerald400.copy(alpha = 0.7f), a, b, 2.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
                            )
                        }

                        // ── Room labels ───────────────────────────────────
                        plan.rooms.forEach { room ->
                            val p = Offset(room.anchor.x * w, room.anchor.y * h)
                            drawRoundRect(
                                color = Zinc900.copy(alpha = 0.85f),
                                topLeft = p - Offset(6.dp.toPx(), 14.dp.toPx()),
                                size = androidx.compose.ui.geometry.Size(
                                    (room.name.length * 15f + 26f), 22.dp.toPx()
                                ),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx())
                            )
                            drawContext.canvas.nativeCanvas.drawText(room.name, p.x, p.y, labelPaint)
                        }

                        // ── Dotted router → device RF links ───────────────
                        val router = plan.devices.firstOrNull { it.isRouter }
                        if (router != null) {
                            val rp = Offset(router.offset.x * w, router.offset.y * h)
                            plan.devices.filter { !it.isRouter }.forEach { dev ->
                                val dp = Offset(dev.offset.x * w, dev.offset.y * h)
                                drawLine(
                                    Emerald500.copy(alpha = 0.5f), rp, dp, 1.6f.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 9f), 0f)
                                )
                            }
                        }

                        // ── Devices ───────────────────────────────────────
                        plan.devices.forEach { dev ->
                            val p = Offset(dev.offset.x * w, dev.offset.y * h)
                            if (dev.isRouter) {
                                drawCircle(Emerald500.copy(alpha = 0.14f), 16.dp.toPx(), p)
                                drawCircle(Emerald500.copy(alpha = 0.4f), 11.dp.toPx(), p, style = Stroke(1.dp.toPx()))
                                drawCircle(Emerald400, 6.dp.toPx(), p)
                                drawContext.canvas.nativeCanvas.drawText(
                                    "📶 ${dev.name}", p.x + 10.dp.toPx(), p.y - 8.dp.toPx(), labelPaint
                                )
                            } else {
                                val box = 7.dp.toPx()
                                drawRoundRect(
                                    color = if (dev.cameraSuspect) Color(0xFF7F1D1D) else Zinc800,
                                    topLeft = p - Offset(box, box),
                                    size = androidx.compose.ui.geometry.Size(box * 2, box * 2),
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
                                )
                                drawRoundRect(
                                    color = if (dev.cameraSuspect) Color.Red else Slate400,
                                    topLeft = p - Offset(box, box),
                                    size = androidx.compose.ui.geometry.Size(box * 2, box * 2),
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                                    style = Stroke(1.dp.toPx())
                                )
                                val tag = (if (dev.cameraSuspect) "⚠ " else "") +
                                        dev.name.take(14) + (dev.ip?.let { " · $it" } ?: "")
                                drawContext.canvas.nativeCanvas.drawText(
                                    tag, p.x + 9.dp.toPx(), p.y + 4.dp.toPx(), smallPaint
                                )
                            }
                        }
                    }

                    // Status / hint footer inside canvas
                    Text(
                        text = statusLine,
                        color = Slate500,
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .background(SpaceBlack.copy(alpha = 0.85f))
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }

                // ── Scale calibration ─────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("GRID\nSCALE", color = Slate500, fontSize = 7.sp, fontFamily = FontFamily.Monospace)
                    Slider(
                        value = plan.metersPerGrid,
                        onValueChange = { plan = plan.copy(metersPerGrid = (it * 4).roundToInt() / 4f) },
                        valueRange = 0.5f..5f,
                        colors = SliderDefaults.colors(
                            thumbColor = Emerald500, activeTrackColor = Emerald500, inactiveTrackColor = Zinc800
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "1 cell = ${"%.2f".format(plan.metersPerGrid)} m\nspan ≈ ${"%.0f".format(plan.metersPerGrid * FloorPlan.GRID_CELLS)} m",
                        color = Emerald400, fontSize = 8.sp, fontFamily = FontFamily.Monospace
                    )
                }

                // ── Honesty note ──────────────────────────────────────────
                Text(
                    text = "NOTE: consumer Wi-Fi radios cannot image walls through concrete — this blueprint is calibrated by you. Signals, RSSI distances, LAN devices & vendors are live radio data. Drag each device into its real room for exact placement.",
                    color = Slate600,
                    fontSize = 7.5.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 10.sp
                )

                // ── Actions ───────────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            plan = FloorPlan.empty(floor)
                            statusLine = "Blueprint cleared for ${floorLabel(floor)}"
                        },
                        border = BorderStroke(1.dp, Zinc800),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Slate400),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.height(42.dp)
                    ) { Text("CLEAR", fontSize = 10.sp, fontFamily = FontFamily.Monospace) }

                    Button(
                        onClick = {
                            val finalPlan = plan.copy(floor = floor)
                            store.save(finalPlan)
                            onApply(finalPlan)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Emerald500),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                    ) {
                        Text(
                            "APPLY TO RADAR — ${floorLabel(floor).uppercase()}",
                            color = SpaceBlack,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }

    if (showRoomNameDialog) {
        AlertDialog(
            onDismissRequest = { showRoomNameDialog = false },
            containerColor = Zinc950,
            title = { Text("NAME THIS ROOM", color = Emerald400, fontSize = 13.sp, fontFamily = FontFamily.Monospace) },
            text = {
                OutlinedTextField(
                    value = roomName,
                    onValueChange = { roomName = it },
                    singleLine = true,
                    placeholder = { Text("Bedroom, Kitchen, Lounge…", color = Slate600, fontSize = 12.sp) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Emerald500,
                        unfocusedBorderColor = Zinc800,
                        focusedTextColor = Slate50,
                        unfocusedTextColor = Slate200
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (roomName.isNotBlank()) {
                            plan = plan.copy(rooms = plan.rooms + RoomLabel(name = roomName.trim(), anchor = pendingRoomAnchor))
                        }
                        showRoomNameDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Emerald500)
                ) { Text("PIN", color = SpaceBlack, fontWeight = FontWeight.Bold, fontSize = 11.sp) }
            },
            dismissButton = {
                TextButton(onClick = { showRoomNameDialog = false }) { Text("CANCEL", color = Slate500) }
            }
        )
    }
}

/** A LAN row shouldn't be offered for placement if it was already placed by MAC/IP. */
private fun LanDevice.isSelf(placed: List<PlacedDevice>): Boolean =
    placed.any { it.mac != null && it.mac == mac && mac.isNotBlank() }

private fun nearestDevice(plan: FloorPlan, point: Offset, threshold: Float = 0.045f): PlacedDevice? =
    plan.devices.minByOrNull { (it.offset - point).getDistance() }
        ?.takeIf { (it.offset - point).getDistance() < threshold }

private fun eraseNearest(plan: FloorPlan, point: Offset, threshold: Float = 0.05f): FloorPlan {
    val wallHit = plan.walls.minByOrNull { distToSegment(point, it.start, it.end) }
        ?.takeIf { distToSegment(point, it.start, it.end) < threshold }
    val devHit = nearestDevice(plan, point)
    val roomHit = plan.rooms.minByOrNull { (it.anchor - point).getDistance() }
        ?.takeIf { (it.anchor - point).getDistance() < threshold }

    return when {
        devHit != null -> plan.copy(devices = plan.devices.filter { it.id != devHit.id })
        wallHit != null -> plan.copy(walls = plan.walls.filter { it.id != wallHit.id })
        roomHit != null -> plan.copy(rooms = plan.rooms.filter { it.id != roomHit.id })
        else -> plan
    }
}

private fun distToSegment(p: Offset, a: Offset, b: Offset): Float {
    val vx = b.x - a.x; val vy = b.y - a.y
    val wx = p.x - a.x; val wy = p.y - a.y
    val lenSq = vx * vx + vy * vy
    val t = if (lenSq > 1e-8f) ((wx * vx + wy * vy) / lenSq).coerceIn(0f, 1f) else 0f
    val px = a.x + vx * t; val py = a.y + vy * t
    val dx = p.x - px; val dy = p.y - py
    return sqrt(dx * dx + dy * dy)
}

/** Snap a normalized point to the half-cell grid. */
private fun snapToGrid(o: Offset): Offset {
    val half = 1f / (FloorPlan.GRID_CELLS * 2)
    fun snap(v: Float) = ((v / half).roundToInt() * half).coerceIn(0f, 1f)
    return Offset(snap(o.x), snap(o.y))
}

/** Snap the segment angle to 15° increments (architect-straight walls). */
private fun snapAngle(start: Offset, end: Offset): Offset {
    val dx = end.x - start.x
    val dy = end.y - start.y
    val len = sqrt(dx * dx + dy * dy)
    if (len < 1e-4f) return end
    val angle = atan2(dy.toDouble(), dx.toDouble())
    val step = Math.PI / 12 // 15°
    val snapped = (angle / step).roundToInt() * step
    return Offset(
        (start.x + len * cos(snapped)).toFloat().coerceIn(0f, 1f),
        (start.y + len * sin(snapped)).toFloat().coerceIn(0f, 1f)
    )
}
