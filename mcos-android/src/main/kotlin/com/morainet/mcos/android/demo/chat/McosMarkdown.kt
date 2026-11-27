package com.morainet.mcos.android.demo.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.morainet.mcos.android.demo.McosRadius
import com.morainet.mcos.android.demo.McosSpace
import java.net.URI
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListBlock
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text as MarkdownText
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

private val markdownParser = Parser.builder().extensions(listOf(TablesExtension.create())).build()

@Composable
internal fun MarkdownReply(text: String, canCopyCode: Boolean) {
    val document = remember(text) { markdownParser.parse(text) }
    MarkdownBlocks(document, canCopyCode)
}

@Composable
private fun MarkdownBlocks(parent: Node, canCopyCode: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(McosSpace.lg)) {
        parent.children().forEach { block ->
            when (block) {
                is Paragraph -> InlineText(block)
                is Heading -> {
                    val type = MaterialTheme.typography
                    val style = when (block.level) {
                        1 -> type.headlineSmall
                        2 -> type.titleLarge
                        else -> type.titleMedium
                    }
                    InlineText(block, style)
                }
                is ListBlock -> MarkdownList(block, canCopyCode)
                is BlockQuote -> Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(McosRadius.sm),
                ) {
                    Column(Modifier.fillMaxWidth().padding(McosSpace.lg)) { MarkdownBlocks(block, canCopyCode) }
                }
                is FencedCodeBlock -> CodeBlock(
                    code = block.literal,
                    canCopy = canCopyCode,
                    language = block.info.orEmpty().takeWhile { !it.isWhitespace() },
                )
                is IndentedCodeBlock -> CodeBlock(block.literal, canCopy = canCopyCode)
                is TableBlock -> MarkdownTable(block)
                is ThematicBreak -> HorizontalDivider()
                is HtmlBlock -> Text(block.literal.trimEnd(), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun MarkdownList(list: ListBlock, canCopyCode: Boolean) {
    val start = (list as? OrderedList)?.markerStartNumber
    Column(verticalArrangement = Arrangement.spacedBy(McosSpace.xs)) {
        list.children().forEachIndexed { index, item ->
            Row(horizontalArrangement = Arrangement.spacedBy(McosSpace.md)) {
                Text(if (start == null) "•" else "${start + index}.", style = MaterialTheme.typography.bodyLarge)
                Column(Modifier.weight(1f)) { MarkdownBlocks(item, canCopyCode) }
            }
        }
    }
}

@Composable
private fun CodeBlock(code: String, canCopy: Boolean, language: String = "") {
    val palette = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(McosRadius.md),
        color = palette.surfaceVariant,
    ) {
        Column {
            if (canCopy && code.isNotBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = McosSpace.lg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) {
                        if (language.isNotEmpty()) {
                            Text(
                                language,
                                color = palette.onSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    CopyToClipboardButton(code, label = "Copy code")
                }
                HorizontalDivider(color = palette.outlineVariant)
            }
            Text(
                code.trimEnd('\n'),
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(McosSpace.lg),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun MarkdownTable(table: TableBlock) {
    val palette = MaterialTheme.colorScheme
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        table.children().forEach { section ->
            val header = section == table.firstChild
            section.children().forEach { row ->
                Row(Modifier.background(if (header) palette.surfaceVariant else Color.Transparent)) {
                    row.children().forEach { cell ->
                        InlineText(
                            cell,
                            style = if (header) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.width(156.dp).padding(McosSpace.md),
                        )
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun InlineText(
    node: Node,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    modifier: Modifier = Modifier,
) {
    val palette = MaterialTheme.colorScheme
    val text = remember(node, palette.primary, palette.surfaceVariant) {
        buildAnnotatedString { appendInline(node, palette.primary, palette.surfaceVariant) }
    }
    Text(text, modifier = modifier, style = style)
}

private fun AnnotatedString.Builder.appendInline(node: Node, linkColor: Color, codeBackground: Color) {
    when (node) {
        is MarkdownText -> append(node.literal)
        is SoftLineBreak, is HardLineBreak -> append('\n')
        is Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) {
            append(node.literal)
        }
        is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            node.children().forEach { appendInline(it, linkColor, codeBackground) }
        }
        is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
            node.children().forEach { appendInline(it, linkColor, codeBackground) }
        }
        is Link -> {
            val url = runCatching { URI(node.destination) }.getOrNull()
            if (url?.host != null && url.userInfo == null && url.scheme?.lowercase() in setOf("https", "http")) {
                val styles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
                withLink(LinkAnnotation.Url(node.destination, styles)) {
                    node.children().forEach { appendInline(it, linkColor, codeBackground) }
                }
            } else {
                node.children().forEach { appendInline(it, linkColor, codeBackground) }
            }
        }
        is HtmlInline -> append(node.literal)
        else -> node.children().forEach { appendInline(it, linkColor, codeBackground) }
    }
}

private fun Node.children(): Sequence<Node> = generateSequence(firstChild) { it.next }
