package com.willianswill.novatv.core.security

/**
 * Armazena e decodifica em tempo de execução URLs sensíveis para evitar
 * exposição direta como strings em texto puro no DEX.
 */
object SecretVault {

    private val firebaseUrlData = intArrayOf(
        50, 46, 46, 42, 41, 96, 117, 117, 45, 46, 44, 57, 59, 52, 59, 51, 41, 53,
        52, 54, 51, 52, 63, 119, 104, 99, 111, 60, 106, 119, 62, 63, 60, 59, 47, 54,
        46, 119, 40, 46, 62, 56, 116, 59, 41, 51, 59, 119, 41, 53, 47, 46, 50, 63,
        59, 41, 46, 107, 116, 60, 51, 40, 63, 56, 59, 41, 63, 62, 59, 46, 59, 56,
        59, 41, 63, 116, 59, 42, 42
    )

    private val updateUrlData = intArrayOf(
        95, 67, 67, 71, 68, 13, 24, 24, 86, 71, 94, 25, 80, 94, 67, 95, 66, 85,
        25, 84, 88, 90, 24, 69, 82, 71, 88, 68, 24, 64, 94, 91, 91, 94, 86, 89,
        68, 64, 94, 91, 91, 24, 64, 94, 91, 91, 94, 86, 89, 68, 64, 94, 91, 91,
        67, 82, 84, 95, 26, 122, 94, 89, 95, 86, 68, 115, 82, 68, 71, 82, 68, 86,
        68, 26, 88, 89, 26, 90, 86, 94, 89, 24, 69, 82, 91, 82, 86, 68, 82, 68,
        24, 91, 86, 67, 82, 68, 67
    )

    fun firebaseRtdbUrl(): String = decode(firebaseUrlData, 0x5A)

    fun latestReleaseUrl(): String = decode(updateUrlData, 0x37)

    private fun decode(data: IntArray, key: Int): String = buildString(data.size) {
        for (b in data) append((b xor key).toChar())
    }
}
