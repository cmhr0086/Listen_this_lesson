package com.cmhr.listen.data.course

import java.util.Locale
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordNameGeneratorTest {
    @Test
    fun defaultNameUsesDashInsteadOfColon() {
        val name = RecordNameGenerator.defaultName(timestamp = 1_756_713_960_000L, locale = Locale.US)
        assertFalse(name.contains(':'))
        assertTrue(name.matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}-\\d{2} 课堂记录")))
    }
}
