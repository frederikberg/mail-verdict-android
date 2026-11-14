package com.frederikberg.mailverdict.kit.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceNameTest {
    @Test
    fun anOrdinaryNameIsKept() {
        assertEquals("Google Pixel 8", DeviceName.sanitize("Google Pixel 8"))
    }

    @Test
    fun controlCharactersAndRunsOfSpaceCollapseToOneSpace() {
        assertEquals("Pixel 8 Pro", DeviceName.sanitize("  Pixel\r\n8\t\t Pro \u0000"))
    }

    @Test
    fun aBlankNameBecomesAGenericOne() {
        assertEquals("Android device", DeviceName.sanitize(null))
        assertEquals("Android device", DeviceName.sanitize(" \n "))
    }

    @Test
    fun aLongNameIsCutAtSixtyFourCharactersAndNeverInsideACharacter() {
        val name = DeviceName.sanitize("a".repeat(63) + "😀😀")

        assertEquals(64, name.codePointCount(0, name.length))
        assertTrue(name.endsWith("😀"))
        assertEquals("a".repeat(64), DeviceName.sanitize("a".repeat(100)))
    }

    @Test
    fun theTrailingSpaceLeftByTheCutIsDropped() {
        assertEquals("a".repeat(63), DeviceName.sanitize("a".repeat(63) + " b"))
    }
}
