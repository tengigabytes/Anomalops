// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.camera.request

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** FR-81: no request can fire the flash or the torch. */
class FlashGuardTest {
    @Test
    fun fr81_onlyNonFlashAeModesAreRepresentable() {
        assertEquals(listOf(AeMode.ON, AeMode.OFF), AeMode.entries)
    }

    @Test
    fun fr81_sourcesNeverNameFlashOrTorchModes() {
        // Unit tests run with the module directory as the working directory.
        val roots = listOf(File("src/main"), File("../../app/src/main"))
        roots.forEach { assertTrue("missing source root ${it.absolutePath}", it.isDirectory) }
        val offenders = roots
            .flatMap { root -> root.walk().filter { it.isFile && it.extension in SOURCE_EXTENSIONS }.toList() }
            .filter { FORBIDDEN.containsMatchIn(it.readText()) }
            .map { it.path }
        assertEquals(emptyList<String>(), offenders)
    }

    private companion object {
        val SOURCE_EXTENSIONS = setOf("kt", "java", "xml")
        val FORBIDDEN = Regex("""AE_MODE_ON_\w*FLASH|FLASH_MODE_(SINGLE|TORCH)|setTorchMode""")
    }
}
