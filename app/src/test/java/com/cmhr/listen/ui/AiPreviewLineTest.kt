package com.cmhr.listen.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiPreviewLineTest {
    @Test fun dropsMarkdownMarkersAndJoinsFirstTwoLines() {
        val reply = "## 答案\n\n**B证**。\n\n项目经理还要取得安全生产考核合格证书。"
        assertEquals("答案 B证。", aiPreviewLine(reply))
    }

    @Test fun skipsTableSeparatorsAndBullets() {
        assertEquals("要点一 要点二", aiPreviewLine("|---|---|\n- 要点一\n* 要点二\n- 要点三"))
    }

    @Test fun blankTextHasNoPreview() {
        assertNull(aiPreviewLine("\n  \n"))
    }
}
