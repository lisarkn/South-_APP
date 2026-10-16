package net.southplus.client.core.network

/**
 * 轻量网络诊断日志（环形缓冲，最近 60 条）。
 * 仅存内存，可在 设置 → 网络诊断 查看/复制；用于真机上定位
 * 「加载不出来」类问题：请求结果、挑战误判、镜像切换、解析产出。
 */
object DiagLog {

    data class Entry(val at: Long, val label: String, val detail: String)

    private const val CAP = 60
    private val buf = ArrayDeque<Entry>(CAP)

    /** 最近一次成功抓取的原始 HTML（截断保存，供「复制原始页面」诊断用）。 */
    @Volatile
    var lastHtml: Pair<String, String>? = null
        private set

    fun log(label: String, detail: String) {
        synchronized(buf) {
            buf.addLast(Entry(System.currentTimeMillis(), label, detail))
            while (buf.size > CAP) buf.removeFirst()
        }
    }

    fun rememberHtml(label: String, html: String) {
        lastHtml = label to html.take(400_000)
    }

    fun snapshot(): List<Entry> = synchronized(buf) { buf.toList().asReversed() }

    fun clear() = synchronized(buf) { buf.clear() }
}
