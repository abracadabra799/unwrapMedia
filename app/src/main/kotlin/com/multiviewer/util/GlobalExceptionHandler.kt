package com.multiviewer.util

import kotlinx.coroutines.CoroutineExceptionHandler
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.coroutines.CoroutineContext

object GlobalExceptionHandler {
    private val logger = Logger.getLogger(GlobalExceptionHandler::class.java.name)

    /**
     * Initializes the default JVM uncaught exception handler and AWT EventQueue exception handler.
     */
    fun install() {
        // Default uncaught exception handler for background threads
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            logger.log(Level.SEVERE, "Uncaught exception in thread '${thread.name}': ${throwable.message}", throwable)
            System.err.println("[unwrapMedia Fatal] Uncaught exception in thread '${thread.name}': ${throwable.message}")
            throwable.printStackTrace()

            // Clean up any remaining child processes if fatal
            if (throwable is VirtualMachineError || throwable is OutOfMemoryError) {
                ProcessManager.destroyAll()
            }
        }

        // AWT EventQueue exception handling
        System.setProperty("sun.awt.exception.handler", AwtExceptionHandler::class.java.name)
    }

    /**
     * CoroutineExceptionHandler for top-level asynchronous tasks.
     */
    val coroutineHandler = CoroutineExceptionHandler { context: CoroutineContext, throwable: Throwable ->
        logger.log(Level.SEVERE, "Uncaught coroutine exception in context $context: ${throwable.message}", throwable)
        System.err.println("[unwrapMedia Coroutine Error] ${throwable.message}")
    }

    class AwtExceptionHandler {
        fun handle(throwable: Throwable) {
            logger.log(Level.SEVERE, "Uncaught exception in AWT EventQueue: ${throwable.message}", throwable)
            System.err.println("[unwrapMedia AWT Error] ${throwable.message}")
        }
    }
}
