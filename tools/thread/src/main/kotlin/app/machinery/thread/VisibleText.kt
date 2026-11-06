package app.machinery.thread

import org.commonmark.ext.footnotes.FootnoteDefinition
import org.commonmark.ext.footnotes.FootnoteReference
import org.commonmark.ext.footnotes.FootnotesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.LinkReferenceDefinition
import org.commonmark.node.ListBlock
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

/**
 * What GitHub's page shows of a text, for an AI agent to read: whatever a writer can hide from the maintainers who read
 * the page is left out, so that it can't steer the agent behind their backs.
 *
 * Out of Markdown go comments (HTML's, and Markdown's link reference definitions, as in `[//]: # (...)`), HTML tags
 * with their attributes (an image's description, a link's tooltip), the content of elements marked `hidden` and of
 * `<template>`, `<noscript>`, `<svg>`, `<math>` and `<object>`, text nested two deep in `<sub>`, `<sup>` or `<small>`,
 * images, the words after a code block's language, math that hides, shrinks, moves or recolours text, the code blocks
 * GitHub draws as diagrams, maps or 3D models, footnotes nothing refers to (and the labels of the others, which the
 * page numbers), and from everything invisible characters ([withoutInvisibleCharacters]). The Markdown is parsed as
 * CommonMark, as GitHub parses it, so that a construct can't look like code here and like a comment there.
 */
object VisibleText {
    private val parser = Parser.builder().extensions(listOf(FootnotesExtension.create())).build()

    /** What the page shows of [markdown], as Markdown; links keep their targets only when [keepLinkTargets]. */
    fun ofMarkdown(
        markdown: String,
        keepLinkTargets: Boolean,
    ): String {
        val document = parser.parse(markdown)
        val lines = Rendering(keepLinkTargets, footnoteNumbers(document)).blocks(document)
        return tidy(withoutInvisibleCharacters(lines.joinToString("\n")))
    }

    /** What the page shows of [text], plain text on one line, like a title or a file's path. */
    fun ofLine(text: String): String = withoutInvisibleCharacters(text).replace(WHITESPACE, " ").trim()

    /** What the page shows of [code], which it shows as it is (a diff, say). */
    fun ofCode(code: String): String = tidy(withoutInvisibleCharacters(code))

    /**
     * [text] without the characters a page doesn't show, which can smuggle instructions past its readers: formatting
     * characters (zero-width spaces and joiners, direction marks and overrides, Unicode's tag characters), variation
     * selectors, fillers and other blank letters, control characters but tabs and line feeds, and private-use,
     * unassigned and lone surrogate code points. Unusual spaces become plain ones, line and paragraph separators line
     * feeds.
     */
    fun withoutInvisibleCharacters(text: String): String =
        buildString(text.length) {
            text.codePoints().forEach { point ->
                when {
                    point == '\n'.code || point == '\t'.code -> appendCodePoint(point)
                    point == LINE_SEPARATOR || point == PARAGRAPH_SEPARATOR -> append('\n')
                    Character.getType(point) in INVISIBLE_TYPES || INVISIBLE.any { point in it } -> Unit
                    Character.getType(point) == Character.SPACE_SEPARATOR.toInt() -> append(' ')
                    else -> appendCodePoint(point)
                }
            }
        }

    private fun tidy(text: String): String =
        text
            .lines()
            .joinToString("\n") { it.trimEnd() }
            .replace(BLANK_LINES, "\n\n")
            .trim('\n')

    /** The number the page gives each footnote, in the order the text first refers to them. */
    private fun footnoteNumbers(document: Node): Map<String, Int> =
        document
            .descendants()
            .filterIsInstance<FootnoteReference>()
            .map { it.label }
            .distinct()
            .withIndex()
            .associate { (index, label) -> label to index + 1 }

    private const val LINE_SEPARATOR = 0x2028
    private const val PARAGRAPH_SEPARATOR = 0x2029
    private val INVISIBLE_TYPES =
        setOf(
            Character.FORMAT,
            Character.CONTROL,
            Character.PRIVATE_USE,
            Character.UNASSIGNED,
            Character.SURROGATE,
        ).map { it.toInt() }.toSet()

    /** Code points that draw nothing, though Unicode files them as marks or letters. */
    private val INVISIBLE =
        listOf(
            0x034F..0x034F, // combining grapheme joiner
            0x115F..0x1160, // Hangul choseong and jungseong fillers
            0x17B4..0x17B5, // Khmer inherent vowels
            0x180B..0x180F, // Mongolian variation selectors and vowel separator
            0x3164..0x3164, // Hangul filler
            0xFE00..0xFE0F, // variation selectors
            0xFFA0..0xFFA0, // halfwidth Hangul filler
            0xE0100..0xE01EF, // variation selectors supplement
        )
    private val WHITESPACE = Regex("\\s+")
    private val BLANK_LINES = Regex("\n{3,}")
}

/** Markdown's blocks and inlines as the page shows them, in order, since an HTML element can span several. */
private class Rendering(
    private val keepLinkTargets: Boolean,
    private val footnotes: Map<String, Int>,
) {
    private val html = HtmlFilter()

    /** The lines [parent]'s blocks show, a blank line between two blocks unless they are a [tight] list item's. */
    fun blocks(
        parent: Node,
        tight: Boolean = false,
    ): List<String> {
        val lines = mutableListOf<String>()
        parent.children().forEach { child ->
            val block = block(child)
            if (block.isNotEmpty()) {
                if (lines.isNotEmpty() && !tight) lines += ""
                lines += block
            }
        }
        return lines
    }

    private fun block(node: Node): List<String> =
        when (node) {
            is Paragraph -> leaf(node) { it }
            is Heading -> leaf(node) { "#".repeat(node.level) + " " + it }
            is BlockQuote -> blocks(node).map { "> $it".trimEnd() }
            is ListBlock -> list(node)
            is FencedCodeBlock -> fenced(node)
            is IndentedCodeBlock -> if (html.hiding) emptyList() else code("", node.literal.orEmpty())
            is HtmlBlock -> htmlBlock(node)
            is ThematicBreak -> if (html.hiding) emptyList() else listOf("---")
            is LinkReferenceDefinition -> emptyList()
            is FootnoteDefinition -> footnote(node)
            else -> blocks(node)
        }.dropLastWhile { it.isEmpty() }

    private fun htmlBlock(node: HtmlBlock): List<String> =
        html
            .visible(node.literal.orEmpty())
            .lines()
            .map { it.trim() }
            .dropWhile { it.isEmpty() }

    /** A paragraph or heading, which math can hide parts of: GitHub draws it, and this doesn't. */
    private fun leaf(
        node: Node,
        format: (String) -> String,
    ): List<String> {
        val text = inlines(node).trim()
        return when {
            text.isEmpty() -> emptyList()
            '$' in text && MATH_THAT_HIDES.containsMatchIn(text) -> listOf(MATH_LEFT_OUT)
            else -> format(text).lines()
        }
    }

    private fun inlines(parent: Node): String = buildString { parent.children().forEach { inline(it, this) } }

    private fun inline(
        node: Node,
        out: StringBuilder,
    ) {
        when (node) {
            is HtmlInline -> out.append(html.visibleTag(node.literal.orEmpty()))
            is Link -> out.append(link(node))
            is Emphasis -> out.append(wrapped(inlines(node), "*"))
            is StrongEmphasis -> out.append(wrapped(inlines(node), "**"))
            is Text, is Code, is Image, is FootnoteReference -> out.append(shown(node))
            is SoftLineBreak, is HardLineBreak -> out.append(shown(node))
            else -> node.children().forEach { inline(it, out) }
        }
    }

    /** What a leaf of the text shows: nothing inside a hiding element, and an image's place, not its description. */
    private fun shown(node: Node): String =
        when {
            html.hiding -> ""
            node is Text -> node.literal.orEmpty()
            node is Code -> codeSpan(node.literal.orEmpty())
            node is Image -> IMAGE
            node is FootnoteReference -> footnotes[node.label]?.let { "[^$it]" }.orEmpty()
            else -> "\n"
        }

    /** A link's text, and its target too when it is shown and differs from the text; never its tooltip. */
    private fun link(node: Link): String {
        val text = inlines(node)
        val target = node.destination.orEmpty()
        val plain = text.isBlank() || target.isBlank() || text == target || "mailto:$text" == target
        return if (plain || !keepLinkTargets) text else "[$text]($target)"
    }

    private fun list(node: ListBlock): List<String> {
        val lines = mutableListOf<String>()
        var number = (node as? OrderedList)?.markerStartNumber ?: 1
        node.children().forEach { item ->
            val marker = if (node is OrderedList) "${number++}. " else "- "
            lines += indented(marker, blocks(item, node.isTight))
        }
        return lines
    }

    private fun fenced(node: FencedCodeBlock): List<String> {
        // The page shows no more of the info string than the language, which it doesn't show either
        val language =
            node.info
                .orEmpty()
                .trim()
                .split(WHITESPACE)
                .first()
                .takeIf { LANGUAGE.matches(it) }
                .orEmpty()
        val literal = node.literal.orEmpty()
        val kind = language.lowercase()
        return when {
            html.hiding -> emptyList()
            kind in DRAWN -> listOf("(a $language block, which the page draws rather than shows: left out)")
            kind == "math" && MATH_THAT_HIDES.containsMatchIn(literal) -> listOf(MATH_LEFT_OUT)
            else -> code(language, literal)
        }
    }

    private fun footnote(node: FootnoteDefinition): List<String> {
        // GitHub doesn't show a footnote nothing refers to
        val number = footnotes[node.label] ?: return emptyList()
        return indented("[^$number]: ", blocks(node))
    }

    private fun indented(
        marker: String,
        lines: List<String>,
    ): List<String> =
        lines.mapIndexed { index, line ->
            when {
                index == 0 -> marker + line
                line.isEmpty() -> line
                else -> " ".repeat(marker.length) + line
            }
        }

    private companion object {
        const val IMAGE = "(image)"
        const val MATH_LEFT_OUT = "(math left out: it can hide text)"
        const val MIN_FENCE = 3

        /** Code blocks GitHub draws as diagrams, maps or 3D models, whose source shows nowhere. */
        val DRAWN = setOf("mermaid", "geojson", "topojson", "stl")
        val LANGUAGE = Regex("[A-Za-z0-9_+#.-]{1,40}")
        val WHITESPACE = Regex("\\s+")

        /** LaTeX commands with which GitHub's math hides, shrinks, overlaps, moves or recolours text. */
        val MATH_THAT_HIDES =
            Regex(
                "\\\\(?:[hv]?phantom|(?:text|page|define)?color|colorbox|fcolorbox|class|cssId|style|bbox|" +
                    "html(?:Class|Style|Id|Data)|tiny|scriptsize|footnotesize|(?:script)?scriptstyle|" +
                    "(?:math)?[lrc]lap|smash|raise|lower|kern|mkern|mskip|hskip|hspace|move(?:left|right)|require|" +
                    "unicode)(?![A-Za-z])",
            )

        fun code(
            language: String,
            literal: String,
        ): List<String> {
            val fence = "`".repeat(maxOf(MIN_FENCE, longestRun(literal) + 1))
            return listOf(fence + language) + literal.removeSuffix("\n").lines() + fence
        }

        fun codeSpan(literal: String): String {
            val fence = "`".repeat(longestRun(literal) + 1)
            return if (fence.length == 1) "`$literal`" else "$fence $literal $fence"
        }

        fun wrapped(
            text: String,
            marker: String,
        ): String = if (text.isBlank()) text else marker + text + marker

        private fun longestRun(text: String): Int = Regex("`+").findAll(text).maxOfOrNull { it.value.length } ?: 0
    }
}

/**
 * Raw HTML as the page shows it: the text between the tags, an image's place and the line breaks, but no comment,
 * declaration, tag or attribute, and nothing inside the elements that hide what they hold. It reads a post's pieces of
 * HTML in order and remembers which elements are still open, as the page's HTML parser does: an element opened in one
 * piece can hide the next ones, up to its end tag or the end of the post.
 */
private class HtmlFilter {
    /** The names of the hiding elements open, outermost first, with those of the same name nested in them. */
    private val hidden = mutableListOf<String>()
    private var small = 0

    /** Whether the page hides what comes now. */
    val hiding: Boolean get() = hidden.isNotEmpty() || small >= SMALL_ENOUGH_TO_HIDE

    /** What a block of raw [html] shows. */
    fun visible(html: String): String =
        buildString {
            var at = 0
            TOKEN.findAll(html).forEach { token ->
                if (!hiding) append(html, at, token.range.first)
                val shows = tag(token.value)
                if (!hiding) append(shows)
                at = token.range.last + 1
            }
            if (!hiding) append(html, at, html.length)
        }.let(::withCharacterReferences)

    /** What one inline [tag] shows (a comment, a tag or a declaration, as CommonMark found it). */
    fun visibleTag(tag: String): String {
        val shows = if (TOKEN.matches(tag)) tag(tag) else ""
        return if (hiding) "" else shows
    }

    /** What [token] shows, once it opened or closed its element. */
    private fun tag(token: String): String {
        val name =
            TAG_NAME
                .find(token)
                ?.groupValues
                ?.get(1)
                ?.lowercase() ?: return ""
        val closing = token.startsWith("</")
        if (closing) close(name) else open(name, token)
        return if (closing) "" else SHOWN[name].orEmpty()
    }

    private fun open(
        name: String,
        tag: String,
    ) {
        when {
            name in VOID || tag.endsWith("/>") -> Unit
            hidden.isNotEmpty() -> if (name == hidden.last()) hidden += name
            name in HIDES_CONTENT || HIDDEN_ATTRIBUTE.containsMatchIn(tag) -> hidden += name
            name in SMALL -> small++
        }
    }

    private fun close(name: String) {
        when {
            hidden.isNotEmpty() -> if (name == hidden.last()) hidden.removeAt(hidden.lastIndex)
            name in SMALL && small > 0 -> small--
        }
    }

    private companion object {
        const val SMALL_ENOUGH_TO_HIDE = 2
        val HIDES_CONTENT = setOf("template", "noscript", "svg", "math", "object")
        val SMALL = setOf("sub", "sup", "small")

        /** What the tags that show something show. */
        val SHOWN = mapOf("img" to "(image)", "br" to "\n", "hr" to "\n")
        val VOID =
            setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr")

        /**
         * Comments, CDATA, declarations, processing instructions, end tags and start tags, each also when the text
         * ends before it does, which ends it for the page too. Lenient about attributes: more taken for a tag is more
         * left out, never more shown.
         */
        val TOKEN =
            Regex(
                "<!--[\\s\\S]*?(?:-->|\\z)|<!\\[CDATA\\[[\\s\\S]*?(?:]]>|\\z)|<![A-Za-z][^>]*(?:>|\\z)|" +
                    "<\\?[\\s\\S]*?(?:\\?>|\\z)|</[A-Za-z][A-Za-z0-9-]*[^>]*(?:>|\\z)|" +
                    "<[A-Za-z][A-Za-z0-9-]*(?:\"[^\"]*(?:\"|\\z)|'[^']*(?:'|\\z)|[^\"'>])*(?:>|\\z)",
            )
        val TAG_NAME = Regex("^</?([A-Za-z][A-Za-z0-9-]*)")
        val HIDDEN_ATTRIBUTE = Regex("[\\s/\"']hidden(?![A-Za-z0-9_.:-])", RegexOption.IGNORE_CASE)
        val NUMERIC_REFERENCE = Regex("&#(?:([0-9]{1,7})|[xX]([0-9A-Fa-f]{1,6}));")

        /** [text] with its numeric character references decoded, so that one for an invisible character goes too. */
        fun withCharacterReferences(text: String): String =
            NUMERIC_REFERENCE.replace(text) { match ->
                val (decimal, hex) = match.destructured
                val point = if (decimal.isNotEmpty()) decimal.toInt() else hex.toInt(HEX)
                if (Character.isValidCodePoint(point)) Character.toChars(point).concatToString() else ""
            }

        const val HEX = 16
    }
}

private fun Node.children(): Sequence<Node> = generateSequence(firstChild) { it.next }

private fun Node.descendants(): Sequence<Node> = children().flatMap { sequenceOf(it) + it.descendants() }
