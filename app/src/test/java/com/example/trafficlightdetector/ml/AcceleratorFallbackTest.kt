package com.example.trafficlightdetector.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AcceleratorFallbackTest {

    @Test
    fun `keeps the backend name and the exception message`() {
        val fallback = AcceleratorFallback.of("GPU", IllegalStateException("delegate creation failed"))

        assertEquals("GPU", fallback.backend)
        assertEquals("delegate creation failed", fallback.reason)
        assertEquals("GPU: delegate creation failed", fallback.description)
    }

    @Test
    fun `uses the class name when the message is missing`() {
        // What the GPU delegate actually throws on some drivers.
        val fallback = AcceleratorFallback.of("GPU", IllegalArgumentException())

        assertEquals("IllegalArgumentException", fallback.reason)
        assertEquals("GPU: IllegalArgumentException", fallback.description)
    }

    @Test
    fun `treats a blank message as missing`() {
        val fallback = AcceleratorFallback.of("NNAPI", RuntimeException("   \n\t  "))

        assertEquals("RuntimeException", fallback.reason)
    }

    @Test
    fun `keeps only the first non blank line`() {
        val multiLine = AcceleratorFallback.of(
            "GPU",
            RuntimeException("delegate creation failed\nbecause the driver is too old"),
        )
        assertEquals("delegate creation failed", multiLine.reason)

        val padded = AcceleratorFallback.of("NNAPI", RuntimeException("\n\n   no NNAPI on this device  \n"))
        assertEquals("no NNAPI on this device", padded.reason)
    }

    @Test
    fun `truncates reasons that would not fit on one line`() {
        val fallback = AcceleratorFallback.of("GPU", RuntimeException("x".repeat(500)))

        assertEquals(AcceleratorFallback.MAX_REASON_LENGTH, fallback.reason.length)
        assertTrue(fallback.description.startsWith("GPU: xxx"))
    }

    @Test
    fun `names a custom exception type when it carries no message`() {
        class DriverRejectedTheDelegate : RuntimeException()

        val fallback = AcceleratorFallback.of("GPU", DriverRejectedTheDelegate())

        assertEquals("DriverRejectedTheDelegate", fallback.reason)
    }

    @Test
    fun `never produces a blank reason`() {
        // An anonymous class has no simple name, so the class-name fallback is
        // empty too -- the read-out must still explain itself.
        val anonymous = object : RuntimeException() {}

        assertTrue("reason should never be blank", AcceleratorFallback.of("GPU", anonymous).reason.isNotBlank())
    }
}
