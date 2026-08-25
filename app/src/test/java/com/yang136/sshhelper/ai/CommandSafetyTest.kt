package com.yang136.sshhelper.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CommandSafetyTest {
    @Test
    fun rejectsEmptyNulAndOversizedCommands() {
        assertNotNull(validateCommand("  "))
        assertNotNull(validateCommand("echo\u0000x"))
        assertNotNull(validateCommand("x".repeat(16 * 1024 + 1)))
        assertNull(validateCommand("printf '%s' hello"))
    }

    @Test
    fun classifiesReadOnlyPipelinesAsLowRisk() {
        assertEquals(CommandRisk.LOW, CommandRiskClassifier.classify("df -h | sort"))
        assertEquals(CommandRisk.LOW, CommandRiskClassifier.classify("git status && git log -1"))
    }

    @Test
    fun classifiesWritesAndServiceChangesAsMediumRisk() {
        assertEquals(CommandRisk.MEDIUM, CommandRiskClassifier.classify("echo ok > status.txt"))
        assertEquals(CommandRisk.MEDIUM, CommandRiskClassifier.classify("sudo systemctl restart nginx"))
        assertEquals(CommandRisk.MEDIUM, CommandRiskClassifier.classify("git pull"))
    }

    @Test
    fun classifiesDestructiveOperationsAsHighRisk() {
        assertEquals(CommandRisk.HIGH, CommandRiskClassifier.classify("sudo rm -rf /tmp/data"))
        assertEquals(CommandRisk.HIGH, CommandRiskClassifier.classify("dd if=image of=/dev/sda"))
        assertEquals(CommandRisk.HIGH, CommandRiskClassifier.classify("mysql -e 'DROP DATABASE prod'"))
        assertEquals(CommandRisk.HIGH, CommandRiskClassifier.classify("chmod -R 777 /"))
    }

    @Test
    fun leavesUnknownOrMalformedShellAsUnknown() {
        assertEquals(CommandRisk.UNKNOWN, CommandRiskClassifier.classify("custom-deploy --prod"))
        assertEquals(CommandRisk.UNKNOWN, CommandRiskClassifier.classify("echo 'unterminated"))
    }
}
