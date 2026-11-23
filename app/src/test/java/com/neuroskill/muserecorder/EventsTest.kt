package com.neuroskill.muserecorder

import com.neuroskill.muserecorder.core.EventRecord
import com.neuroskill.muserecorder.core.EventTracker
import com.neuroskill.muserecorder.core.parseCsvLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EventsTest {

    @Test
    fun `select arms only, start begins, end closes to unselected`() {
        var clock = 1000L
        val closed = ArrayList<EventRecord>()
        val t = EventTracker(clock = { clock }, onClosed = { closed.add(it) })
        assertNull(t.active.value)

        t.select("闭眼静息")       // 仅已选，不开启
        assertEquals("闭眼静息", t.selectedName.value)
        assertNull(t.active.value)

        clock = 2000
        t.start()                  // 点「开始」才开启
        assertEquals("闭眼静息" to 2000L, t.active.value)
        assertNull(t.selectedName.value)

        clock = 61000
        t.select("心算任务")       // 换选：自动结束上一个并回到已选态
        assertEquals(1, closed.size)
        assertEquals("闭眼静息", closed[0].name)
        assertEquals(2000L, closed[0].startMs)
        assertEquals(61000L, closed[0].endMs)
        assertEquals("心算任务", t.selectedName.value)
        assertNull(t.active.value)

        clock = 70000
        t.start()
        clock = 90000
        t.closeCurrent()           // 结束 → 回到未选择
        assertEquals(2, closed.size)
        assertNull(t.active.value)
        assertNull(t.selectedName.value)
    }

    @Test
    fun `note attaches to ongoing event only`() {
        var clock = 10L
        val closed = ArrayList<EventRecord>()
        val t = EventTracker(clock = { clock }, onClosed = { closed.add(it) })
        t.setNote("ignored")         // 无进行中事件 → 忽略
        assertEquals("", t.activeNote.value)
        t.select("任务A")
        t.start()
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
