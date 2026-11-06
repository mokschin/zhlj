package com.velmorath.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ZhljApiTest {
    @Test fun selectedSemesterDoesNotUseEarlierUnselectedOption() {
        val html = """<select id='cx_xnm'><option value='2025'>2025</option>
            <option selected value='2026'>2026</option></select>
            <select id='cx_xqm'><option value='3'>秋季</option><option value='12' selected>春季</option></select>"""
        assertEquals("2026", ZhljApi.selectedOption(html, "cx_xnm"))
        assertEquals("12", ZhljApi.selectedOption(html, "cx_xqm"))
        assertEquals("", ZhljApi.selectedOption(html, "missing"))
        assertEquals("3", ZhljApi.selectedOption("<select id='term'><option value='3'>秋季</option></select>", "term"))
    }
}
