package net.southplus.client.core.util

import kotlinx.coroutines.CancellationException

/** Cancellation is control flow, never a UI error or an offline-cache fallback. */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}
