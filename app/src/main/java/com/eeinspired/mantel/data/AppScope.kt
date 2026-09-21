package com.eeinspired.mantel.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-lifetime scope for work that must finish even if the screen that started it leaves
 * composition (e.g. copying picked files into staging, wiping caches on sign-out).
 */
object AppScope {
    val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
