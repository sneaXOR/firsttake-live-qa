package dev.firsttake.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TelemetryChainTest {
    @Test
    fun producesStableCrossLanguageHash() {
        assertEquals(
            "43a76fcb98db23ab4b458fcb11ad1327aed420eb806e5eb672ab0de8b0ca9174",
            TelemetryChain.recordHash(
                sequence = 0,
                previousHash = TelemetryChain.GENESIS_HASH,
                payload = """{"type":"SAMPLE","value":0}""",
            ),
        )
    }

    @Test
    fun payloadMutationChangesHash() {
        val first = TelemetryChain.recordHash(
            0,
            TelemetryChain.GENESIS_HASH,
            """{"value":1}""",
        )
        val second = TelemetryChain.recordHash(
            0,
            TelemetryChain.GENESIS_HASH,
            """{"value":2}""",
        )
        assertNotEquals(first, second)
    }
}
