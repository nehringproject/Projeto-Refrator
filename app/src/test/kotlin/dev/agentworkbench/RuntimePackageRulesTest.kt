package dev.agentworkbench

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimePackageRulesTest {
    @Test
    fun acceptsBoundedRelativePackagePaths() {
        RuntimePackageRules.requireSafeRelativePath("bin/tool")
        RuntimePackageRules.requireSafeRelativePath("lib/python3.13/site.py")
    }

    @Test
    fun rejectsTraversalAbsoluteAndEmptyComponents() {
        listOf("../tool", "bin/../tool", "/system/bin/sh", "C:\\tool.exe", "bin//tool").forEach { path ->
            assertFailsWith<IllegalArgumentException>(path) {
                RuntimePackageRules.requireSafeRelativePath(path)
            }
        }
    }

    @Test
    fun validatesPackageIdentityAndDigestShapes() {
        assertTrue(RuntimePackageRules.packageName.matches("python-core"))
        assertTrue(RuntimePackageRules.packageVersion.matches("3.13.5+awb.1"))
        assertTrue(RuntimePackageRules.commandName.matches("python3"))
        assertTrue(RuntimePackageRules.commandName.matches("config_guess"))
        assertFalse(RuntimePackageRules.commandName.matches("config-guess"))
        assertTrue(RuntimePackageRules.sha256.matches("a".repeat(64)))
        assertFalse(RuntimePackageRules.packageName.matches("../python"))
        assertFalse(RuntimePackageRules.sha256.matches("a".repeat(63)))
    }

    @Test
    fun distinguishesInterpretedShellFromDownloadedElf() {
        assertTrue(RuntimePackageRules.isElf(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())))
        assertFalse(RuntimePackageRules.isElf("#!/bin/sh".toByteArray()))
        assertTrue(RuntimePackageRules.isAllowedShellScript("#!/system/bin/sh"))
        assertTrue(RuntimePackageRules.isAllowedShellScript("  #!/bin/sh  "))
        assertTrue(RuntimePackageRules.isAllowedShellScript("#! /bin/sh"))
        assertFalse(RuntimePackageRules.isAllowedShellScript("#!/usr/bin/env bash"))
        assertFalse(RuntimePackageRules.isAllowedShellScript("#!/system/bin/python"))
    }

    @Test
    fun blocksLocalAndMetadataStyleAddresses() {
        listOf("127.0.0.1", "10.0.0.1", "192.168.1.1", "169.254.169.254", "::1", "fc00::1").forEach { value ->
            assertFalse(RuntimePackageRules.isPublicAddress(InetAddress.getByName(value)), value)
        }
        assertTrue(RuntimePackageRules.isPublicAddress(InetAddress.getByName("8.8.8.8")))
        assertTrue(RuntimePackageRules.isPublicAddress(InetAddress.getByName("2606:4700:4700::1111")))
    }
}
