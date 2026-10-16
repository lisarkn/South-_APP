package net.southplus.client.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import net.southplus.client.core.model.ThreadItem
import net.southplus.client.core.model.UserBrief
import net.southplus.client.ui.reader.ReaderTitle
import net.southplus.client.ui.reader.ReaderToolbar
import net.southplus.client.ui.theme.SouthPlusTheme
import net.southplus.client.ui.theme.Spacing

// 展示数据仅用于 Android Studio Preview，不接入正式导航或业务容器。
@Preview(name = "帖子列表 · 浅色", widthDp = 360)
@Composable
fun ThreadListLightPreview() = ThreadListSample(1)

@Preview(name = "帖子列表 · 深色", widthDp = 360)
@Composable
fun ThreadListDarkPreview() = ThreadListSample(2)

@Preview(name = "帖子列表 · 小屏大字", widthDp = 320, fontScale = 1.6f)
@Composable
fun ThreadListLargeTextPreview() = ThreadListSample(1)

@Composable
private fun ThreadListSample(theme: Int) {
    SouthPlusTheme(theme) {
        Surface {
            Column {
                SectionHeader("版块讨论")
                ThreadRow(ThreadItem("preview", "周末阅读手记：把日常里的小发现，写成一段值得分享的故事", UserBrief(name = "一个比较长的作者昵称"),
                    replies = 128, lastReplyTime = "今天 12:30", isPinned = true, isDigest = true)) {}
                HorizontalDivider()
                ThreadRow(ThreadItem("read", "读过的帖子，以更轻的字重呈现", UserBrief(name = "读者"), isRead = true)) {}
            }
        }
    }
}

@Preview(name = "阅读器 · 浅色", widthDp = 360)
@Composable
fun ReaderLightPreview() = ReaderSample(1)

@Preview(name = "阅读器 · 深色", widthDp = 360)
@Composable
fun ReaderDarkPreview() = ReaderSample(2)

@Preview(name = "阅读器 · 小屏大字", widthDp = 320, fontScale = 1.6f)
@Composable
fun ReaderLargeTextPreview() = ReaderSample(1)

@Composable
private fun ReaderSample(theme: Int) {
    SouthPlusTheme(theme) {
        Surface {
            Column {
                ReaderTitle("这里是一条跨越多行的长标题，完整展示内容，让阅读从标题开始", 2, 128)
                Text("正文保留舒适的字号与行距。工具栏使用图标翻页，并可点击页码跳转。", Modifier.padding(Spacing.l), style = MaterialTheme.typography.bodyLarge)
                ReaderToolbar(2, 128, false, true, {}, {}, {}, {})
            }
        }
    }
}

@Preview(name = "偏好设置 · 浅色", widthDp = 360)
@Composable
fun PreferencesLightPreview() = PreferencesSample(1)

@Preview(name = "偏好设置 · 深色", widthDp = 360)
@Composable
fun PreferencesDarkPreview() = PreferencesSample(2)

@Composable
private fun PreferencesSample(theme: Int) {
    SouthPlusTheme(theme) {
        Surface {
            Column(Modifier.padding(Spacing.l)) {
                SectionCard {
                    ThemeSelector(theme) {}
                    SettingSwitch("站外图片按需加载", true, "移动网络下点击后再加载图片") {}
                }
                EmptyPane("输入关键词，发现感兴趣的话题与分享。", title = "从一个关键词开始")
            }
        }
    }
}
