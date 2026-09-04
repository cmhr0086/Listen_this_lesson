package com.cmhr.listen.data.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SttCallTraceTest {
    @Test
    fun traceUsesOneElapsedRealtimeClockForEveryNetworkEvent() {
        var elapsed = 81_188_900L
        var wall = 1_780_000_000_000L
        val trace = SttCallTrace(elapsedRealtime = { elapsed }, wallTime = { wall })

        trace.add(AsrNetworkEventType.CALL_START)
        elapsed += 125L
        wall += 125L
        trace.add(AsrNetworkEventType.REQUEST_BODY_START)
        elapsed += 375L
        wall += 375L
        trace.add(AsrNetworkEventType.RESPONSE_BODY_END)

        val events = trace.snapshot()
        assertEquals(81_188_900L, events.first().monotonicTimestampMs)
        assertEquals(0L, events.first().elapsedSinceCallStartMs)
        assertEquals(500L, events.last().elapsedSinceCallStartMs)
        assertEquals(
            500L,
            events.durationBetween(AsrNetworkEventType.CALL_START, AsrNetworkEventType.RESPONSE_BODY_END)
        )
    }

    @Test
    fun invalidEventOrderDoesNotGetHiddenByCoercion() {
        val events = listOf(
            SttNetworkEvent(
                eventType = AsrNetworkEventType.REQUEST_BODY_START,
                timestampMs = 1L,
                monotonicTimestampMs = 10L,
                elapsedSinceCallStartMs = 50L
            ),
            SttNetworkEvent(
                eventType = AsrNetworkEventType.REQUEST_BODY_END,
                timestampMs = 2L,
                monotonicTimestampMs = 9L,
                elapsedSinceCallStartMs = 25L
            )
        )

        assertNull(
            events.durationBetween(
                AsrNetworkEventType.REQUEST_BODY_START,
                AsrNetworkEventType.REQUEST_BODY_END
            )
        )
    }
}
