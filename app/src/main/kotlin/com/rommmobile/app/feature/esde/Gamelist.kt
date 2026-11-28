package com.rommmobile.app.feature.esde

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.StringReader
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/**
 * One `<game>` (or `<folder>`) of an ES-DE `gamelist.xml`. [path] is relative to the system's ROM
 * folder, without the leading `./` (`Game.gba`, `Final Fantasy VII/Final Fantasy VII.m3u`).
 * [fields] keeps ES-DE's element names (`name`, `desc`, `rating`, ...) in the order to write them.
 */
data class GamelistEntry(
    val path: String,
    val fields: Map<String, String>,
    val tag: String = "game",
)

/** What a merge did. [xml] is null when the file needs no change at all. */
data class MergeResult(val xml: String?, val added: Int, val completed: Int)

class GamelistException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Reads, merges and writes ES-DE gamelists without ever losing what is already there.
 *
 * ES-DE (`GamelistFileParser.cpp`) reads `<gameList>` children `<game>` / `<folder>`, each with a
 * `<path>` relative to the system folder. When it saves, it parses the file on disk again and only
 * replaces the entries it changed itself, so entries added here survive a running ES-DE as long as
 * it does not know the game yet.
 *
 * Merge rules: an entry whose path is new is appended; an entry that already exists only gets the
 * fields it lacks (absent or blank). Nothing is ever removed or overwritten, every unknown element
 * and attribute is kept as it was.
 */
object GamelistXml {

    /** ES-DE metadata keys this app fills, in ES-DE's own order (`MetaData.cpp`). */
    val FIELD_ORDER = listOf("name", "desc", "rating", "releasedate", "developer", "publisher", "genre", "players")

    private const val WRAPPER = "romm-top-level"
    /** What ES-DE itself puts at the top level of a gamelist (`GamelistFileParser.cpp`). */
    private val TOP_LEVEL = setOf("gameList", "alternativeEmulator")

    /**
     * @throws GamelistException when [existing] is not a readable gamelist: the caller must then
     * leave the file alone rather than replace a file it could not understand.
     */
    fun merge(existing: String?, entries: List<GamelistEntry>): MergeResult {
        val doc = parse(existing)
        val root = gameList(doc) ?: doc.createElement("gameList").also { doc.documentElement.appendChild(it) }
        var added = 0
        var completed = 0
        for (entry in entries) {
            val key = normalizePath(entry.path)
            if (key.isEmpty()) continue
            val node = findEntry(root, key)
            if (node == null) {
                root.appendChild(createEntry(doc, entry))
                added++
            } else if (complete(doc, node, entry)) {
                completed++
            }
        }
        if (added == 0 && completed == 0) return MergeResult(null, 0, 0)
        return MergeResult(serialize(doc), added, completed)
    }

    /**
     * ES-DE's file is not one XML document: `<alternativeEmulator>` sits BEFORE `<gameList>` at the
     * top level, which pugixml accepts and a strict parser refuses. The top level is therefore read
     * inside a wrapper element that [serialize] never writes back. Refuses anything that does not
     * parse, has more than one `<gameList>`, or holds top-level elements ES-DE does not write.
     */
    fun parse(xml: String?): Document {
        val body = xml.orEmpty().removePrefix("\uFEFF").trim().let { s ->
            if (s.startsWith("<?xml")) s.substringAfter("?>", "").trim() else s
        }
        if (body.startsWith("<!DOCTYPE", ignoreCase = true)) throw GamelistException("doctype in gamelist")
        val doc = try {
            newBuilder().parse(InputSource(StringReader("<$WRAPPER>$body</$WRAPPER>")))
        } catch (t: Throwable) {
            throw GamelistException("unreadable gamelist: ${t.message}", t)
        }
        val top = doc.documentElement.childElements().toList()
        if (top.count { it.tagName == "gameList" } > 1) throw GamelistException("several <gameList>")
        top.firstOrNull { it.tagName !in TOP_LEVEL }?.let { throw GamelistException("unexpected <${it.tagName}> at top level") }
        return doc
    }

    /** Number of `<game>` and `<folder>` entries, used to check a file before it replaces another. */
    fun entryCount(xml: String): Int = gameList(parse(xml))?.childElements()?.count { it.tagName == "game" || it.tagName == "folder" } ?: 0

    /** Paths (normalized) of every entry, for tests and diagnostics. */
    fun paths(xml: String): List<String> = (gameList(parse(xml))?.childElements() ?: emptySequence())
        .filter { it.tagName == "game" || it.tagName == "folder" }
        .mapNotNull { e -> e.childElements().firstOrNull { it.tagName == "path" }?.textContent?.let(::normalizePath) }
        .toList()

    private fun gameList(doc: Document): Element? = doc.documentElement.childElements().firstOrNull { it.tagName == "gameList" }

    /** `./a/b.gba`, `a\b.gba` and ` a/b.gba ` all mean `a/b.gba`. */
    fun normalizePath(raw: String): String {
        var s = raw.trim().replace('\\', '/')
        while (s.startsWith("./")) s = s.removePrefix("./")
        return s.trimStart('/')
    }

    private fun findEntry(root: Element, key: String): Element? = root.childElements().firstOrNull { e ->
        (e.tagName == "game" || e.tagName == "folder") &&
            e.childElements().firstOrNull { it.tagName == "path" }?.textContent?.let { normalizePath(it).equals(key, ignoreCase = true) } == true
    }

    private fun createEntry(doc: Document, entry: GamelistEntry): Element {
        val e = doc.createElement(entry.tag)
        e.appendChild(doc.createElement("path").apply { textContent = "./" + normalizePath(entry.path) })
        for ((k, v) in ordered(entry.fields)) {
            val clean = cleanText(v)
            if (clean.isBlank()) continue
            e.appendChild(doc.createElement(k).apply { textContent = clean })
        }
        return e
    }

    /** Adds the fields [node] lacks. @return true when something was added. */
    private fun complete(doc: Document, node: Element, entry: GamelistEntry): Boolean {
        var changed = false
        for ((k, v) in ordered(entry.fields)) {
            val clean = cleanText(v)
            if (clean.isBlank()) continue
            val existing = node.childElements().firstOrNull { it.tagName == k }
            when {
                existing == null -> { node.appendChild(doc.createElement(k).apply { textContent = clean }); changed = true }
                existing.textContent.isBlank() && existing.childElements().none() -> { existing.textContent = clean; changed = true }
            }
        }
        return changed
    }

    private fun ordered(fields: Map<String, String>): List<Pair<String, String>> {
        val known = FIELD_ORDER.mapNotNull { k -> fields[k]?.let { k to it } }
        val rest = fields.filterKeys { it !in FIELD_ORDER && it != "path" }.toList()
        return known + rest
    }

    /** XML 1.0 forbids most control characters; RomM summaries sometimes carry them. */
    private fun cleanText(s: String): String = buildString(s.length) {
        for (c in s) if (c == '\t' || c == '\n' || c == '\r' || c >= ' ') append(c)
    }.trim()

    private fun newBuilder() = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        isExpandEntityReferences = false
        isIgnoringComments = false
        // No DTD, no external entities: a gamelist never needs them.
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
    }.newDocumentBuilder()

    /* ---------------- writing, in ES-DE's own layout (pugixml, tab indent) ---------------- */

    fun serialize(doc: Document): String = buildString {
        append("<?xml version=\"1.0\"?>\n")
        var n = doc.documentElement.firstChild
        while (n != null) {
            when (n.nodeType) {
                Node.ELEMENT_NODE -> writeElement(this, n as Element, 0)
                Node.COMMENT_NODE -> append("<!--").append(n.nodeValue).append("-->\n")
            }
            n = n.nextSibling
        }
    }

    private fun writeElement(out: StringBuilder, e: Element, depth: Int) {
        val indent = "\t".repeat(depth)
        out.append(indent).append('<').append(e.tagName)
        val attrs = e.attributes
        for (i in 0 until attrs.length) {
            val a = attrs.item(i)
            out.append(' ').append(a.nodeName).append("=\"").append(escape(a.nodeValue, attr = true)).append('"')
        }
        val children = (0 until e.childNodes.length).map { e.childNodes.item(it) }
        val hasElements = children.any { it.nodeType == Node.ELEMENT_NODE || it.nodeType == Node.COMMENT_NODE }
        val text = children.filter { it.nodeType == Node.TEXT_NODE || it.nodeType == Node.CDATA_SECTION_NODE }.joinToString("") { it.nodeValue }
        when {
            !hasElements && text.isEmpty() -> out.append(" />\n")
            !hasElements -> out.append('>').append(escape(text, attr = false)).append("</").append(e.tagName).append(">\n")
            else -> {
                out.append(">\n")
                for (c in children) when (c.nodeType) {
                    Node.ELEMENT_NODE -> writeElement(out, c as Element, depth + 1)
                    Node.COMMENT_NODE -> out.append(indent).append('\t').append("<!--").append(c.nodeValue).append("-->\n")
                    // Mixed content does not occur in gamelists; keep any real text rather than drop it.
                    Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> if (c.nodeValue.isNotBlank()) out.append(indent).append('\t').append(escape(c.nodeValue.trim(), attr = false)).append('\n')
                }
                out.append(indent).append("</").append(e.tagName).append(">\n")
            }
        }
    }

    private fun escape(s: String, attr: Boolean): String = buildString(s.length + 8) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> if (attr) append("&quot;") else append(c)
            else -> append(c)
        }
    }

    private fun Element.childElements(): Sequence<Element> = sequence {
        var n = firstChild
        while (n != null) {
            if (n.nodeType == Node.ELEMENT_NODE) yield(n as Element)
            n = n.nextSibling
        }
    }
}

/** RomM values turned into what ES-DE writes itself. */
object EsdeMeta {

    /** RomM's average rating is 0-10 (sometimes 0-100); ES-DE wants 0-1 with dot decimals. */
    fun rating(value: Double?): String? {
        if (value == null || value <= 0 || value.isNaN()) return null
        val r = (if (value > 10) value / 100.0 else value / 10.0).coerceIn(0.0, 1.0)
        return String.format(Locale.ROOT, "%.2f", r).trimEnd('0').trimEnd('.').ifEmpty { null }
    }

    /** Epoch seconds or millis to ES-DE's `YYYYMMDDT000000`. */
    fun releaseDate(epoch: Long?): String? {
        if (epoch == null || epoch <= 0) return null
        val millis = if (epoch < 100_000_000_000L) epoch * 1000 else epoch
        val d = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
        if (d.year !in 1950..2100) return null
        return String.format(Locale.ROOT, "%04d%02d%02dT000000", d.year, d.monthValue, d.dayOfMonth)
    }

    /** "1-8 players", "2" or "1 - 4" -> "1-8", "2", "1-4"; ES-DE shows it as is. */
    fun players(raw: String?): String? {
        val m = raw?.let { Regex("(\\d+)\\s*(?:-\\s*(\\d+))?").find(it) } ?: return null
        val a = m.groupValues[1]
        val b = m.groupValues[2]
        return if (b.isNotEmpty() && b != a) "$a-$b" else a
    }

    /** ES-DE's `getStem`: the file (or folder) name without its last extension. */
    fun stem(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        return if (dot > 0) fileName.substring(0, dot) else fileName
    }

    /**
     * The file ES-DE will list for a game among the files that were written for it: the `.m3u`
     * of a multi-disc set, else the file named like the ROM, else a disc descriptor, else the
     * biggest. [files] are name to size; sidecars (saves, images, text) are never picked.
     */
    fun primaryFile(files: Map<String, Long>, romStem: String?): String? {
        val candidates = files.filterKeys { n -> !n.startsWith(".") && n.substringAfterLast('.', "").lowercase() !in NOT_GAMES }
        if (candidates.isEmpty()) return null
        fun ext(n: String) = n.substringAfterLast('.', "").lowercase()
        val m3u = candidates.keys.filter { ext(it) == "m3u" }
        if (m3u.isNotEmpty()) return m3u.firstOrNull { romStem != null && stem(it).equals(romStem, true) } ?: m3u.first()
        if (romStem != null) candidates.keys.firstOrNull { stem(it).equals(romStem, true) }?.let { return it }
        candidates.keys.firstOrNull { ext(it) in DISC_DESCRIPTORS }?.let { return it }
        return candidates.maxByOrNull { it.value }?.key
    }

    private val DISC_DESCRIPTORS = setOf("cue", "gdi", "chd", "ccd", "mds", "toc", "iso", "cso", "pbp", "rvz")
    private val NOT_GAMES = setOf(
        "txt", "xml", "json", "ini", "cfg", "nfo", "html", "pdf",
        "png", "jpg", "jpeg", "webp", "bmp", "mp4", "webm", "mkv",
        "sav", "srm", "state", "rtc", "eep", "ips", "bps", "ups", "xdelta", "log", "bak", "tmp", "part", "rommpart",
    )
}
