package si.jakobkreft.aftergleam.data

/**
 * The few things the app does to HTML, done with plain string searches.
 *
 * Not regular expressions, and not a parser. A regular expression is the obvious tool and
 * was the first one used, and on a phone it took 34 seconds to wrap the formulas of one 3.4 MB
 * paper that a desktop wraps in milliseconds: Android's engine backtracks character by
 * character through a lazy match. A parser would be a dependency for three operations on
 * markup that LaTeXML writes the same way every time.
 *
 * Searches are case-sensitive, which is what makes them fast; [lowered] gives a copy to
 * search when case should not matter.
 */
object Html {

    /** [html] in lower case, character for character, so positions in it are positions in [html]. */
    fun lowered(html: String): String = String(CharArray(html.length) { html[it].lowercaseChar() })

    /**
     * Where the next `<name` tag starts at or after [from], or -1. The name must end there:
     * `<math` does not find `<mathvariant`.
     */
    fun findTag(html: String, name: String, from: Int): Int {
        val open = "<$name"
        var i = from
        while (true) {
            i = html.indexOf(open, i)
            if (i < 0) return -1
            val after = i + open.length
            if (after >= html.length) return -1
            val c = html[after]
            if (c == '>' || c == '/' || c.isWhitespace()) return i
            i = after
        }
    }

    /** [html] without any `<name ...>...</name>` element, content and all, in any case. */
    fun removeElements(html: String, name: String): String {
        val low = lowered(html)
        var start = findTag(low, name, 0)
        if (start < 0) return html
        val out = StringBuilder(html.length)
        var at = 0
        while (start >= 0) {
            val close = low.indexOf("</$name", start)
            val end = if (close < 0) -1 else low.indexOf('>', close)
            // Unclosed: drop the rest, which is safer than keeping half a script.
            if (end < 0) return out.append(html, at, start).toString()
            out.append(html, at, start)
            at = end + 1
            start = findTag(low, name, at)
        }
        return out.append(html, at, html.length).toString()
    }

    /** [html] without any `<name ...>` tag, in any case, for tags that have no content. */
    fun removeTags(html: String, name: String): String {
        val low = lowered(html)
        var start = findTag(low, name, 0)
        if (start < 0) return html
        val out = StringBuilder(html.length)
        var at = 0
        while (start >= 0) {
            val end = low.indexOf('>', start)
            if (end < 0) return out.append(html, at, start).toString()
            out.append(html, at, start)
            at = end + 1
            start = findTag(low, name, at)
        }
        return out.append(html, at, html.length).toString()
    }
}
