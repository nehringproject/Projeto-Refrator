package dev.agentworkbench

import java.net.InetAddress

internal object RuntimePackageRules {
    val packageName = Regex("[a-z0-9][a-z0-9._-]{0,63}")
    val packageVersion = Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}")
    // Entrypoints become POSIX shell functions, so their names must follow
    // portable shell identifier rules instead of arbitrary executable names.
    val commandName = Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")
    val sha256 = Regex("[A-Fa-f0-9]{64}")
    val allowedShellShebangs = setOf(
        "#!/system/bin/sh",
        "#!/bin/sh",
        "#! /system/bin/sh",
        "#! /bin/sh",
    )

    fun isElf(header: ByteArray): Boolean = header.size >= 4 &&
        header[0] == 0x7f.toByte() &&
        header[1] == 'E'.code.toByte() &&
        header[2] == 'L'.code.toByte() &&
        header[3] == 'F'.code.toByte()

    fun isAllowedShellScript(firstLine: String): Boolean = firstLine.trim() in allowedShellShebangs

    fun requireSafeRelativePath(value: String, maximumLength: Int = 512) {
        require(value.isNotBlank() && value.length <= maximumLength) { "Caminho de pacote invalido." }
        require('\u0000' !in value && !value.startsWith('/') && !value.startsWith('\\')) {
            "Caminho absoluto bloqueado."
        }
        require(!Regex("^[A-Za-z]:").containsMatchIn(value)) { "Caminho absoluto Windows bloqueado." }
        require(value.replace('\\', '/').split('/').none { it.isBlank() || it == "." || it == ".." }) {
            "Caminho de pacote contem componente inseguro."
        }
    }

    fun isPublicAddress(address: InetAddress): Boolean =
        !address.isAnyLocalAddress &&
            !address.isLoopbackAddress &&
            !address.isLinkLocalAddress &&
            !address.isSiteLocalAddress &&
            !address.isMulticastAddress &&
            !isUniqueLocalIpv6(address.address)

    private fun isUniqueLocalIpv6(raw: ByteArray): Boolean =
        raw.size == 16 && (raw[0].toInt() and 0xfe) == 0xfc
}
