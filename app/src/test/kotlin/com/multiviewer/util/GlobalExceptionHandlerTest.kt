package com.multiviewer.util

import kotlin.test.Test
import kotlin.test.assertNotNull

class GlobalExceptionHandlerTest {

    @Test
    fun `install initializes exception handlers successfully without error`() {
        GlobalExceptionHandler.install()
        assertNotNull(Thread.getDefaultUncaughtExceptionHandler())
        assertNotNull(GlobalExceptionHandler.coroutineHandler)
    }
}
