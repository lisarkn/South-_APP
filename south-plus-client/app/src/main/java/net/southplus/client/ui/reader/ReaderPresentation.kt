package net.southplus.client.ui.reader

import android.text.Spanned
import android.text.SpannedString
import androidx.core.text.HtmlCompat
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import net.southplus.client.core.config.SelectorConfig
import net.southplus.client.core.links.ResourceExtractor
import net.southplus.client.core.model.ContentBlock
import net.southplus.client.core.model.Post
import net.southplus.client.core.model.ResourceLink
import net.southplus.client.core.model.ThreadPage
import org.jsoup.Jsoup

/** Prepared off Main; widgets never parse HTML during scrolling or copy actions. */
data class RenderedPost(
    val key: String,
    val post: Post,
    val plainText: String,
    val cards: List<ResourceLink>,
    val text: Map<Int, Spanned>,
)

data class RenderedPage(val page: ThreadPage, val posts: List<RenderedPost>, val weight: Long)

internal fun postKeys(page: ThreadPage): List<String> {
    val counts = page.posts.mapNotNull { it.pid }.groupingBy { it }.eachCount()
    return page.posts.mapIndexed { index, post ->
        val id = post.pid?.takeIf { it.isNotBlank() && counts[it] == 1 }
        "${page.tid}:${page.page}:" + if (id != null) "pid:$id" else "floor:${post.floor}:$index"
    }
}

suspend fun prepareReaderPage(page: ThreadPage, config: SelectorConfig): RenderedPage {
    val extractor = ResourceExtractor(config)
    val keys = postKeys(page)
    var weight = 0L
    val posts = page.posts.mapIndexed { index, post ->
        currentCoroutineContext().ensureActive()
        val text = LinkedHashMap<Int, Spanned>()
        val plain = ArrayList<String>()
        post.contentBlocks.forEachIndexed { blockIndex, block ->
            currentCoroutineContext().ensureActive()
            val html = when (block) {
                is ContentBlock.Text -> block.html
                is ContentBlock.Quote -> block.html
                else -> null
            }
            if (html != null) {
                text[blockIndex] = SpannedString(HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY))
                if (block is ContentBlock.Text) plain += Jsoup.parse(html).text()
                weight += html.length.toLong() * 8 + 256
            }
        }
        val plainText = plain.joinToString("\n")
        weight += 1024 + plainText.length * 2L
        RenderedPost(keys[index], post, plainText, extractor.extract(plainText), text)
    }
    return RenderedPage(page, posts, weight)
}
