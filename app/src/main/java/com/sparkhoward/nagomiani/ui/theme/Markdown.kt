package com.sparkhoward.nagomiani.ui.theme

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * 轻量 Markdown → AnnotatedString（更新弹窗的发布说明渲染）。
 * 覆盖发布说明常见结构：标题(#..####)、无序列表(-、*)、有序列表、引用(>)、粗体(**)、
 * 行内代码(`)、链接([t](u))；图片剥除仅留替代文字，分隔线与无法识别的标记原样兜底——
 * 不追求完整 GFM，目标是把 GitHub Releases 的原文渲染得美观可读。
 */
object NagomiMarkdown {

    fun toAnnotatedString(body: String, accent: Color, secondary: Color): AnnotatedString =
        buildAnnotatedString {
            val lines = body.lines()
            lines.forEachIndexed { index, raw ->
                val trimmed = raw.trim()
                when {
                    trimmed.isEmpty() -> Unit // 行尾统一补 \n，空行即段落间距
                    trimmed.startsWith("#") -> {
                        val level = trimmed.takeWhile { it == '#' }.length.coerceIn(1, 4)
                        val text = trimmed.dropWhile { it == '#' }.trim()
                        if (index > 0) append('\n')
                        withStyle(
                            SpanStyle(
                                fontWeight = FontWeight.Bold,
                                fontSize = if (level <= 2) 16.sp else 14.sp,
                                color = accent,
                            ),
                        ) { append(inline(text, accent)) }
                    }

                    trimmed.length >= 3 && trimmed.all { it == '-' || it == '=' || it == '*' || it == ' ' } ->
                        withStyle(SpanStyle(color = secondary)) { append("————————") }

                    trimmed.startsWith(">") -> {
                        withStyle(SpanStyle(color = secondary)) {
                            append("▎")
                            append(inline(trimmed.removePrefix(">").trim(), accent))
                        }
                    }

                    trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ") -> {
                        append("•  ")
                        append(inline(trimmed.substring(2).trim(), accent))
                    }

                    else -> append(inline(trimmed, accent))
                }
                if (index < lines.lastIndex) append('\n')
            }
        }

    /** 行内标记：**粗体** / `代码` / [链接](url) / ![图片](url) 剥除留 alt */
    private fun inline(text: String, accent: Color): AnnotatedString = buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > i + 1) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, end)) }
                        i = end + 2
                    } else {
                        append(text[i]); i++
                    }
                }

                text[i] == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i) {
                        withStyle(
                            SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x14888888)),
                        ) { append(text.substring(i + 1, end)) }
                        i = end + 1
                    } else {
                        append(text[i]); i++
                    }
                }

                text.startsWith("![", i) -> {
                    val altEnd = text.indexOf("](", i)
                    val end = if (altEnd > 0) text.indexOf(')', altEnd) else -1
                    if (altEnd > i + 1 && end > altEnd) {
                        append(text.substring(i + 2, altEnd))
                        i = end + 1
                    } else {
                        append(text[i]); i++
                    }
                }

                text[i] == '[' -> {
                    val bracketEnd = text.indexOf("](", i)
                    val end = if (bracketEnd > 0) text.indexOf(')', bracketEnd) else -1
                    if (bracketEnd > i + 1 && end > bracketEnd) {
                        val label = text.substring(i + 1, bracketEnd)
                        val url = text.substring(bracketEnd + 2, end)
                        val idx = pushLink(
                            LinkAnnotation.Url(
                                url,
                                TextLinkStyles(
                                    style = SpanStyle(color = accent, textDecoration = TextDecoration.Underline),
                                ),
                            ),
                        )
                        append(label)
                        pop(idx)
                        i = end + 1
                    } else {
                        append(text[i]); i++
                    }
                }

                else -> {
                    append(text[i]); i++
                }
            }
        }
    }
}

/** 更新说明等简短 Markdown 的渲染（点击链接走系统默认打开行为） */
@Composable
fun MarkdownText(
    body: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 13.sp,
    lineHeight: TextUnit = 20.sp,
    color: Color = Color.Unspecified,
) {
    val accent = NagomiColors.accent
    val secondary = NagomiColors.accentDark
    val text = remember(body) { NagomiMarkdown.toAnnotatedString(body, accent, secondary) }
    Text(text, modifier = modifier, fontSize = fontSize, lineHeight = lineHeight, color = color)
}
