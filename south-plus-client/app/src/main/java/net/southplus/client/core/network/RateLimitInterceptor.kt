package net.southplus.client.core.network

import java.io.IOException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import kotlin.random.Random

/**
 * 请求双车道（方案 §3.3 硬性约束 + §6.4.6）：
 *  - FORUM：全局串行（并发=1），相邻请求随机间隔 1.5–3.0s；
 *  - IMAGES：站外/图片车道，并发 2、短间隔，不占论坛串行队列。
 */
enum class Lane { FORUM, IMAGES }

class RateLimitInterceptor(
    private val mirrorHosts: () -> Set<String>,
    private val forumGapMinMs: Long = 1500,
    private val forumGapMaxMs: Long = 3000,
    private val imageConcurrency: Int = 2,
    private val imageGapMinMs: Long = 150,
    private val imageGapMaxMs: Long = 400,
    private val random: Random = Random.Default,
) : Interceptor {

    private val forumSemaphore = Semaphore(1, true)
    private val imageSemaphore = Semaphore(imageConcurrency, true)

    @Volatile private var lastForumAt = 0L
    @Volatile private var lastImageAt = 0L

    /** 镜像域请求走论坛串行车道，其余（站外图/图床）走宽松车道。 */
    fun laneFor(url: HttpUrl): Lane {
        val host = url.host
        val isMirror = mirrorHosts().any { host == it || host.endsWith(".$it") }
        return if (isMirror) Lane.FORUM else Lane.IMAGES
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val lane = laneFor(chain.request().url)
        val gate = if (lane == Lane.FORUM) forumSemaphore else imageSemaphore
        while (!gate.tryAcquire(25, TimeUnit.MILLISECONDS)) checkActive(chain)
        try {
            checkActive(chain)
            val lastAt = if (lane == Lane.FORUM) lastForumAt else lastImageAt
            val gap = if (lane == Lane.FORUM) jitter(forumGapMinMs, forumGapMaxMs)
                else jitter(imageGapMinMs, imageGapMaxMs)
            pauseSince(lastAt, gap, chain)
            checkActive(chain)
            try {
                return chain.proceed(chain.request())
            } finally {
                if (lane == Lane.FORUM) lastForumAt = System.nanoTime()
                else lastImageAt = System.nanoTime()
            }
        } finally {
            gate.release()
        }
    }

    private fun checkActive(chain: Interceptor.Chain) {
        if (chain.call().isCanceled()) throw IOException("Canceled")
    }

    private fun jitter(minMs: Long, maxMs: Long): Long =
        if (maxMs <= minMs) minMs else random.nextLong(minMs, maxMs + 1)

    /** 间隔从上一个请求【完成】时刻起算（方案 §3.3：相邻请求间隔）。 */
    private fun pauseSince(lastAtNanos: Long, gapMs: Long, chain: Interceptor.Chain) {
        if (lastAtNanos == 0L) return
        while (true) {
            checkActive(chain)
            val remaining = gapMs - (System.nanoTime() - lastAtNanos) / 1_000_000
            if (remaining <= 0) return
            Thread.sleep(minOf(remaining, 25))
        }
    }
}
