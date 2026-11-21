package com.neuroskill.muserecorder

import com.neuroskill.muserecorder.core.EventRecord
import com.neuroskill.muserecorder.core.EventTracker
import com.neuroskill.muserecorder.core.parseCsvLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EventsTest {

    @Test
    fun `select anytime opens and replace auto-closes`() {
        var clock = 1000L
        val closed = ArrayList<EventRecord>()
        val t = EventTracker(clock = { clock }, onClosed = { closed.add(it) })
        assertNull(t.active.value)

        t.select("闭眼静息")       // 随时可选，不依赖记录
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
    fun `note attaches to ongoing event only`() {
        var clock = 10L
        val closed = ArrayList<EventRecord>()
        val t = EventTracker(clock = { clock }, onClosed = { closed.add(it) })
        t.setNote("ignored")         // 无进行中事件 → 忽略
        assertEquals("", t.activeNote.value)
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
