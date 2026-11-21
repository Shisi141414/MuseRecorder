package com.neuroskill.muserecorder

import com.neuroskill.muserecorder.core.EventRecord
import com.neuroskill.muserecorder.core.EventTracker
import com.neuroskill.muserecorder.core.parseCsvLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EventsTest {

    @Test
    fun `replace auto-closes previous with correct interval`() {
        var clock = 1000L
        val closed = ArrayList<EventRecord>()
        val t = EventTracker(clock = { clock }, onClosed = { closed.add(it) })
        t.onRecordingStarted()
        assertNull(t.active.value)   // 无已选 → 不开启

        t.select("闭眼静息")       // 记录中：立即开启
        assertEquals("闭眼静息" to 1000L, t.active.value)
        clock = 61000
        t.select("心算任务")       // 换选自动结束上一个
        assertEquals(1, closed.size)
        assertEquals("闭眼静息", closed[0].name)
        assertEquals(1000L, closed[0].startMs)
        assertEquals(61000L, closed[0].endMs)
        assertEquals("心算任务" to 61000L, t.active.value)

        clock = 90000
        t.closeCurrent()           // 手动结束
        assertEquals(2, closed.size)
        assertEquals("心算任务", closed[1].name)
        assertEquals(61000L, closed[1].startMs)
        assertEquals(90000L, closed[1].endMs)
        assertNull(t.active.value)
    }

    @Test
    fun `select before recording only remembered`() {
        var clock = 0L
        val closed = ArrayList<EventRecord>()
        val t = EventTracker(clock = { clock }, onClosed = { closed.add(it) })
        t.select("阅读")             // 未记录：不开区间
        assertNull(t.active.value)
        assertEquals("阅读", t.selectedName.value)

        t.onRecordingStarted()       // 记录开始：自动开启已选
        assertEquals("阅读" to 0L, t.active.value)
        clock = 5000
        t.onRecordingStopped()
        assertEquals(1, closed.size)
        assertEquals(0L, closed[0].startMs)
        assertEquals(5000L, closed[0].endMs)
    }

    @Test
    fun `new session clears previous events`() {
        var clock = 0L
        val closed = ArrayList<EventRecord>()
        val t = EventTracker(clock = { clock }, onClosed = { closed.add(it) })
        t.onRecordingStarted()
        t.select("A")
        clock = 100
        t.closeCurrent()
        assertEquals(1, t.sessionEvents.value.size)
        t.onRecordingStopped()
        t.onRecordingStarted()       // 新会话清空
        assertEquals(0, t.sessionEvents.value.size)
        assertEquals(1, closed.size) // 历史写入不受影响
    }

    @Test
    fun `note attaches to ongoing event only`() {
        var clock = 10L
        val closed = ArrayList<EventRecord>()
        val t = EventTracker(clock = { clock }, onClosed = { closed.add(it) })
        t.setNote("ignored")         // 无进行中事件 → 忽略
        assertEquals("", t.activeNote.value)
        t.onRecordingStarted()
        t.select("任务A")
        t.setNote("第 3 组, 含逗号")
        clock = 6010
        t.closeCurrent()
        assertEquals("第 3 组, 含逗号", closed[0].note)
    }

    @Test
    fun `csv parse handles quotes and commas`() {
        val cols = parseCsvLine("\"闭眼,静息\",2026-11-21 10:02:14.320,2026-11-21 10:07:31.105,316.8,\"备注含\"\"引号\"\",逗号\"")
        assertEquals(5, cols.size)
        assertEquals("闭眼,静息", cols[0])
        assertEquals("2026-11-21 10:02:14.320", cols[1])
        assertEquals("备注含\"引号\",逗号", cols[4])
    }
}
