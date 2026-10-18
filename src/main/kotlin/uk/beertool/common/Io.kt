package uk.beertool.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Exposed transactions block their thread, so database work runs off the engine's request threads.
suspend fun <T> io(block: suspend CoroutineScope.() -> T): T = withContext(Dispatchers.IO, block)
