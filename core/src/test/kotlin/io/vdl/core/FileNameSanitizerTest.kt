package io.vdl.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileNameSanitizerTest {

    @Test
    fun stripsTraversal() {
        assertEquals("etc passwd", FileNameSanitizer.sanitize("../../etc/passwd"))
        assertEquals("etc passwd", FileNameSanitizer.sanitize("..\\..\\etc\\passwd"))
        assertEquals("passwd", FileNameSanitizer.sanitize("a/../../b/passwd"))
    }

    @Test
    fun stripsSeparatorsAndColons() {
        assertEquals("passwd", FileNameSanitizer.sanitize("/etc/passwd"))
        assertEquals("a_b_c", FileNameSanitizer.sanitize("a:b:c"))
    }

    @Test
    fun keepsSingleSpacesCollapsesMany() {
        assertEquals("my file.mp4", FileNameSanitizer.sanitize("my    file.mp4"))
    }

    @Test
    fun trimsDots() {
        assertEquals("hidden", FileNameSanitizer.sanitize(".hidden"))
        assertEquals("name", FileNameSanitizer.sanitize("name."))
    }

    @Test
    fun replacesControlChars() {
        val raw = "vi\u0000deo\u0007.mp4"
        assertEquals("vi_deo_.mp4", FileNameSanitizer.sanitize(raw))
    }

    @Test
    fun replacesShellMetachars() {
        assertEquals("a_b_c", FileNameSanitizer.sanitize("a*?b\"c"))
        assertEquals("x_y", FileNameSanitizer.sanitize("x<>|y"))
    }

    @Test
    fun capsLengthKeepingSuffix() {
        val long = "a".repeat(500) + ".mp4"
        val out = FileNameSanitizer.sanitize(long)
        assertTrue("length ${out.length}", out.length <= 120)
        assertTrue(out.endsWith(".mp4"))
    }

    @Test
    fun neverEmpty() {
        assertEquals("download.bin", FileNameSanitizer.sanitize(null))
        assertEquals("download.bin", FileNameSanitizer.sanitize(""))
        assertEquals("download.bin", FileNameSanitizer.sanitize("///.."))
        assertEquals("download.bin", FileNameSanitizer.sanitize("   "))
    }

    @Test
    fun normalNameUntouched() {
        assertEquals("video 1080p.mp4", FileNameSanitizer.sanitize("video 1080p.mp4"))
    }

    @Test
    fun unicodeSurvives() {
        assertEquals("vídeo año 2026.mp4", FileNameSanitizer.sanitize("vídeo año 2026.mp4"))
        assertFalse(FileNameSanitizer.sanitize("日本語.mp4").isBlank())
    }
}
