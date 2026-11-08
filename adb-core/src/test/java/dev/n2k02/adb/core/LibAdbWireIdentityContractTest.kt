package dev.n2k02.adb.core

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/** Regression for release-only StackOverflowError in libadb identity callback. */
class LibAdbWireIdentityContractTest {
    @Test fun anonymousManagerReadsOuterDeviceNameWithoutSelfRecursiveGetter() {
        val src = listOf(
            File("src/main/java/dev/n2k02/adb/core/LibAdbWire.kt"),
            File("adb-core/src/main/java/dev/n2k02/adb/core/LibAdbWire.kt"),
            File("../adb-core/src/main/java/dev/n2k02/adb/core/LibAdbWire.kt")
        ).first { it.isFile }.readText()
        assertTrue(src.contains("override fun getDeviceName(): String = this@LibAdbWire.deviceName"))
        assertFalse(src.contains("override fun getDeviceName(): String = deviceName"))
    }
}
