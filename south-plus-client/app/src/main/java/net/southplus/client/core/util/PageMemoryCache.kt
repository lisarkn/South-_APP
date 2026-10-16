package net.southplus.client.core.util

/** Accessed by its owning ViewModel on Main. Weight bounds long HTML/Spanned pages. */
class PageMemoryCache<T>(
    private val maxEntries: Int = 5,
    private val maxWeight: Long = 4L * 1024 * 1024,
    private val ttlMillis: Long = 5 * 60_000,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private data class Entry<T>(val value: T, val weight: Long, val created: Long)
    private val entries = LinkedHashMap<Int, Entry<T>>(8, 0.75f, true)
    private var weight = 0L

    fun get(page: Int): T? {
        val entry = entries[page] ?: return null
        if (now() - entry.created >= ttlMillis) {
            entries.remove(page)
            weight -= entry.weight
            return null
        }
        return entry.value
    }

    fun put(page: Int, value: T, bytes: Long) {
        entries.remove(page)?.let { weight -= it.weight }
        if (bytes > maxWeight) return
        val cost = bytes.coerceAtLeast(1)
        entries[page] = Entry(value, cost, now())
        weight += cost
        while (entries.size > maxEntries || weight > maxWeight) {
            val oldest = entries.entries.iterator()
            weight -= oldest.next().value.weight
            oldest.remove()
        }
    }

    fun clear() { entries.clear(); weight = 0 }
}
