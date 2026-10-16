package net.southplus.client.core.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Owns read requests only. Mutation jobs must remain independent. */
class LatestRequest(private val scope: CoroutineScope) {
    private var job: Job? = null
    val isRunning: Boolean get() = job?.isActive == true
    fun launch(block: suspend CoroutineScope.() -> Unit): Job {
        job?.cancel()
        return scope.launch(block = block).also { job = it }
    }
}
