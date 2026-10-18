package com.cmhr.listen.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AiMarkdownTest {
    @Test
    fun `restores the space after bullet and ordered markers`() {
        assertEquals(
            "一、静矩\n- 定义：dS = x·dA\n  - 量纲：m³\n9. 工程价款\n10. 三同时",
            normalizeAiMarkdown("一、静矩\n-定义：dS = x·dA\n  -量纲：m³\n9.工程价款\n10.三同时")
        )
    }

    @Test
    fun `leaves well formed markdown, rules, decimals and code alone`() {
        val text = "- 已有空格\n---\n1.5 倍安全系数\n**加粗**\n```\n-x\n```"
        assertEquals(text, normalizeAiMarkdown(text))
    }
}
