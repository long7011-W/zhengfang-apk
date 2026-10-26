package com.tyust.course.announcement

import org.commonmark.node.AbstractVisitor
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.Node
import org.commonmark.node.Text
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser

/** Preserve Markdown formatting while preventing HTML and non-public image requests. */
internal fun safeAnnouncementMarkdown(content: String): String {
    val lines = mutableListOf(0)
    content.forEachIndexed { index, char -> if (char == '\n') lines.add(index + 1) }
    val edits = mutableListOf<Triple<Int, Int, String>>()
    fun replace(node: Node, text: String) {
        val spans = node.sourceSpans
        if (spans.isEmpty()) return
        val start = lines[spans.first().lineIndex] + spans.first().columnIndex
        val last = spans.last()
        val end = lines[last.lineIndex] + last.columnIndex + last.length
        edits.add(Triple(start, end, text))
    }
    fun escape(text: String) = text.replace("\\", "\\\\").replace("<", "\\<").replace(">", "\\>")
        .replace("[", "\\[").replace("]", "\\]").replace("*", "\\*").replace("_", "\\_").replace("`", "\\`")
    Parser.builder().includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES).build().parse(content).accept(object : AbstractVisitor() {
        override fun visit(block: HtmlBlock) = replace(block, escape(block.literal))
        override fun visit(inline: HtmlInline) = replace(inline, escape(inline.literal))
        override fun visit(image: Image) {
            if (!AnnouncementManager.validPublicUrl(image.destination)) {
                val alt = StringBuilder()
                image.accept(object : AbstractVisitor() { override fun visit(text: Text) { alt.append(text.literal) } })
                replace(image, escape(alt.toString().ifBlank { "图片" }))
            }
        }
    })
    val result = StringBuilder(content)
    edits.sortedByDescending { it.first }.forEach { (start, end, replacement) -> result.replace(start, end, replacement) }
    return result.toString()
}
