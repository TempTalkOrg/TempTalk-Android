


package com.difft.android.call.layout

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs





class ControllerGoldenTest {
    private val golden: JsonObject by lazy {
        val text = ControllerGoldenTest::class.java.getResource("/generated/call-layout-controller.v$LAYOUT_SPEC_VERSION.json")!!.readText()
        Json.parseToJsonElement(text).jsonObject
    }

    @Test
    fun configAndVersionMatchTheFile() {
        assertEquals(LAYOUT_SPEC_VERSION, golden.getValue("specVersion").jsonPrimitive.int)
        val file = golden.getValue("config").jsonObject
        val ours = configEntries(LayoutConfig.DEFAULT)
        assertEquals("config keys", file.keys, ours.keys)
        for ((k, v) in ours) {
            val p = file.getValue(k).jsonPrimitive
            when (v) {
                is Boolean -> assertEquals(k, v, p.boolean)
                is Number -> assertEquals(k, v.toDouble(), p.double, 0.0)
                else -> error("unexpected config value $k")
            }
        }
    }

    @Test
    fun scriptsReplay() {
        val tol = golden.getValue("tolerance").jsonObject.getValue("rect").jsonPrimitive.double
        val scripts = golden.getValue("scripts").jsonArray
        var steps = 0
        for (s in scripts) {
            val script = s.jsonObject
            val name = script.getValue("name").jsonPrimitive.content
            var now = 0.0
            val c = CallLayoutController(
                overflow = Overflow.entries.first { it.json == script.getValue("overflow").jsonPrimitive.content },
                allowRotation = script.getValue("allowRotation").jsonPrimitive.boolean,
                now = { now },
            )
            var want: JsonObject? = null
            script.getValue("steps").jsonArray.forEachIndexed { i, el ->
                val step = el.jsonObject
                val op = step.getValue("op").jsonPrimitive.content
                now = step.getValue("t").jsonPrimitive.double
                call(c, op, step.getValue("args").jsonArray)
                val expect = step.getValue("expect")
                if (expect != JsonNull) want = expect.jsonObject
                check(c.state.value, want!!, "$name step $i $op", tol)
                steps++
            }
        }
        assertTrue("$steps steps", steps > 1000)
    }

    private fun call(c: CallLayoutController, op: String, a: JsonArray) {
        when (op) {
            "setWindow" -> c.setWindow(size(a[0].jsonObject), insets(a[1].jsonObject), rectOrNull(a[2]))
            "setContent" -> c.setContent(rect(a[0].jsonObject), rect(a[1].jsonObject))
            "setCallType" -> c.setCallType(CallType.entries.first { it.json == a[0].jsonPrimitive.content })
            "setParticipants" -> c.setParticipants(a[0].jsonArray.map {
                val m = it.jsonObject
                CallMember(m.getValue("id").jsonPrimitive.content, m.getValue("isLocal").jsonPrimitive.boolean, m.getValue("cameraOn").jsonPrimitive.boolean)
            })
            "setVideoSize" -> c.setVideoSize(a[0].jsonPrimitive.content, a[1].jsonPrimitive.double, a[2].jsonPrimitive.double)
            "setSpeakers" -> c.setSpeakers(a[0].jsonArray.map { it.jsonPrimitive.content })
            "setShare" -> c.setShare(
                if (a[0] == JsonNull) null
                else a[0].jsonObject.let { ScreenShare(it.getValue("sharerId").jsonPrimitive.content, it.getValue("width").jsonPrimitive.double, it.getValue("height").jsonPrimitive.double) },
            )
            "setAllowRotation" -> c.setAllowRotation(a[0].jsonPrimitive.boolean)
            "setControlsHidden" -> c.setControlsHidden(a[0].jsonPrimitive.boolean)
            "setSchedulerHold" -> c.setSchedulerHold(a[0].jsonPrimitive.boolean)
            "toggleControls" -> c.toggleControls()
            "turnPage" -> c.turnPage(a[0].jsonPrimitive.int)
            "showPage" -> c.showPage(a[0].jsonPrimitive.int)
            "dropPip" -> c.dropPip(a[0].jsonObject.let { Point(it.getValue("x").jsonPrimitive.double, it.getValue("y").jsonPrimitive.double) })
            "toggleSwap" -> c.toggleSwap()
            "toggleShareZoom" -> c.toggleShareZoom()
            "tick" -> c.tick()
            else -> error("unknown op $op")
        }
    }

    private fun check(got: ControllerState, want: JsonObject, where: String, tol: Double) {
        assertEquals(where, want.getValue("animated").jsonPrimitive.boolean, got.animated)
        assertEquals(where, want.getValue("hidden").jsonPrimitive.boolean, got.controlsHidden)
        assertEquals(where, want.getValue("focus").takeUnless { it == JsonNull }?.jsonPrimitive?.content, got.focus)
        
        assertEquals("$where speaker", want.getValue("speaker").takeUnless { it == JsonNull }?.jsonPrimitive?.content, got.speaker)
        val wantPager = want.getValue("pager")
        assertEquals(where, wantPager != JsonNull, got.pager != null)
        got.pager?.let { g ->
            val e = wantPager.jsonArray
            assertEquals(where, listOf(e[0], e[1], e[2]).map { it.jsonPrimitive.int }, listOf(g.page, g.pages, g.later))
            assertRect("$where pager", g.area, e.drop(3), tol)
        }
        val wantPlan = want.getValue("plan")
        assertEquals(where, wantPlan != JsonNull, got.plan != null)
        val p = got.plan ?: return
        val e = wantPlan.jsonObject
        assertEquals(where, e.getValue("mode").jsonPrimitive.content, p.mode.json)
        assertEquals(where, e.getValue("posture").jsonPrimitive.content, p.posture.json)
        assertEquals(where, e.getValue("request").jsonPrimitive.content, p.requestOrientation.json)
        val grid = e.getValue("grid")
        assertEquals(
            "$where grid",
            if (grid == JsonNull) null else grid.jsonArray.map { it.jsonPrimitive.int },
            p.grid?.let { listOf(it.page, it.pages, it.later, it.visibleCount) },
        )
        for ((what, r) in listOf("dock" to p.dock, "pipArea" to p.pipArea)) {
            val w = e.getValue(what)
            assertEquals("$where $what", w != JsonNull, r != null)
            if (r != null) assertRect("$where $what", r, w.jsonArray, tol)
        }
        val tiles = e.getValue("tiles").jsonArray
        assertEquals("$where tiles", tiles.size, p.tiles.size)
        p.tiles.forEachIndexed { k, t ->
            val w = tiles[k].jsonArray
            assertEquals(
                "$where tile $k",
                listOf(w[0].jsonPrimitive.content, w[1].jsonPrimitive.content, w[6].takeUnless { it == JsonNull }?.jsonPrimitive?.content, w[7].jsonPrimitive.int, w[8].jsonPrimitive.intOrNull),
                listOf(t.id, t.role.json, t.contentMode?.json, t.z, t.overflowCount),
            )
            assertRect("$where ${t.id}", t.rect, w.subList(2, 6), tol)
        }
    }

    private fun assertRect(where: String, r: Rect, e: List<JsonElement>, tol: Double) {
        val got = listOf(r.x, r.y, r.w, r.h)
        val want = e.map { it.jsonPrimitive.double }
        assertTrue("$where $got != $want", got.indices.all { abs(got[it] - want[it]) <= tol })
    }

    private fun size(o: JsonObject) = Size(o.getValue("w").jsonPrimitive.double, o.getValue("h").jsonPrimitive.double)

    private fun insets(o: JsonObject) = Insets(
        o.getValue("top").jsonPrimitive.double,
        o.getValue("right").jsonPrimitive.double,
        o.getValue("bottom").jsonPrimitive.double,
        o.getValue("left").jsonPrimitive.double,
    )

    private fun rect(o: JsonObject) = Rect(o.getValue("x").jsonPrimitive.double, o.getValue("y").jsonPrimitive.double, o.getValue("w").jsonPrimitive.double, o.getValue("h").jsonPrimitive.double)

    private fun rectOrNull(e: JsonElement) = if (e == JsonNull) null else rect(e.jsonObject)
}
