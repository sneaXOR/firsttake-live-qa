package dev.firsttake.probe

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object TelemetryChain {
    const val GENESIS_HASH =
        "0000000000000000000000000000000000000000000000000000000000000000"

    fun recordHash(
        sequence: Long,
        previousHash: String,
        payload: String,
    ): String {
        require(sequence >= 0)
        require(previousHash.matches(Regex("[0-9a-f]{64}")))
        val bytes = "$sequence\n$previousHash\n$payload"
            .toByteArray(StandardCharsets.UTF_8)
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}
