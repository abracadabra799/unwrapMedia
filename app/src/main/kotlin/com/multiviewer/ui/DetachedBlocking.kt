package com.multiviewer.ui

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * Runs [block] on [dispatcher] in a scope detached from the caller's Job and awaits it.
 * A failure inside [block] is delivered only through this call (so the caller's try/catch sees it)
 * and never cancels the caller's parent job. Cancelling the caller makes the await return at once;
 * the blocking [block] is left to finish on its own and its result is dropped.
 */
suspend fun <T> awaitDetached(dispatcher: CoroutineDispatcher = Dispatchers.IO, block: () -> T): T =
    CoroutineScope(SupervisorJob() + dispatcher).async { block() }.await()
