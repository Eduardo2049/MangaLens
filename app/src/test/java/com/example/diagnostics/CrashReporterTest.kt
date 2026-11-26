package com.example.diagnostics

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CrashReporterTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `a handled error is kept with its stack trace until dismissed`() {
        CrashReporter.recordHandled(context, "tradução da página", IllegalStateException("bitmap reciclado"))

        val report = CrashReporter.pending(context)
        assertNotNull(report)
        assertEquals("Erro inesperado em tradução da página (o app não fechou)", report!!.title)
        assertEquals("IllegalStateException: bitmap reciclado", report.summary)
        assertTrue(report.details.contains("CrashReporterTest"))

        CrashReporter.dismiss(context)
        assertNull(CrashReporter.pending(context))
    }
}
