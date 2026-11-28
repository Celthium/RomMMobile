package com.rommmobile.app

import com.google.common.truth.Truth.assertThat
import com.rommmobile.app.feature.esde.EsdeExporter
import com.rommmobile.app.feature.esde.EsdeMeta
import com.rommmobile.app.feature.esde.EsdeSettingsFile
import com.rommmobile.app.feature.esde.GamelistEntry
import com.rommmobile.app.feature.esde.GamelistException
import com.rommmobile.app.feature.esde.GamelistXml
import org.junit.Assert.assertThrows
import org.junit.Test

class EsdeGamelistTest {

    private val wario = GamelistEntry(
        "Wario Land 4 (USA, Europe).gba",
        linkedMapOf(
            "genre" to "Platform",
            "name" to "Wario Land 4",
            "desc" to "Wario & the golden pyramid <treasure>",
            "rating" to "0.8",
            "releasedate" to "20010821T000000",
        ),
    )

    /** What ES-DE writes after scraping one game and playing another. */
    private val esdeFile = """
        <?xml version="1.0"?>
        <alternativeEmulator>
        	<label>RetroArch (mGBA)</label>
        </alternativeEmulator>
        <gameList>
        	<game>
        		<path>./Metroid Fusion (USA).gba</path>
        		<name>Metroid Fusion</name>
        		<desc>Samus is back.</desc>
        		<playcount>3</playcount>
        		<lastplayed>20260101T101010</lastplayed>
        	</game>
        	<game>
        		<path>./Wario Land 4 (USA, Europe).gba</path>
        		<name>My own name</name>
        		<desc />
        		<favorite>true</favorite>
        	</game>
        </gameList>
    """.trimIndent()

    @Test fun `new file has ES-DE's root, relative path and escaped text`() {
        val r = GamelistXml.merge(null, listOf(wario))
        assertThat(r.added).isEqualTo(1)
        val xml = r.xml!!
        assertThat(xml).startsWith("<?xml version=\"1.0\"?>\n<gameList>\n\t<game>\n\t\t<path>./Wario Land 4 (USA, Europe).gba</path>\n\t\t<name>Wario Land 4</name>")
        assertThat(xml).contains("<desc>Wario &amp; the golden pyramid &lt;treasure&gt;</desc>")
        // ES-DE's order (name, desc, rating, releasedate, ..., genre), whatever order the map had.
        assertThat(xml.indexOf("<releasedate>")).isLessThan(xml.indexOf("<genre>"))
        assertThat(GamelistXml.paths(xml)).containsExactly("Wario Land 4 (USA, Europe).gba")
    }

    @Test fun `alternativeEmulator before gameList is read and written back in place`() {
        // Two top-level elements, as ES-DE writes them: not one XML document, yet ES-DE's own format.
        val xml = GamelistXml.merge(esdeFile, listOf(GamelistEntry("New.gba", mapOf("name" to "New")))).xml!!
        assertThat(xml).startsWith("<?xml version=\"1.0\"?>\n<alternativeEmulator>\n\t<label>RetroArch (mGBA)</label>\n</alternativeEmulator>\n<gameList>\n")
        assertThat(xml).doesNotContain("romm-top-level")
        assertThat(GamelistXml.paths(xml)).containsExactly("Metroid Fusion (USA).gba", "Wario Land 4 (USA, Europe).gba", "New.gba").inOrder()
        // And it reads back the same way.
        assertThat(GamelistXml.merge(xml, listOf(GamelistEntry("New.gba", mapOf("name" to "New")))).xml).isNull()
    }

    @Test fun `file with only alternativeEmulator gets a gameList after it`() {
        val src = "<?xml version=\"1.0\"?>\n<alternativeEmulator>\n\t<label>X</label>\n</alternativeEmulator>\n"
        val xml = GamelistXml.merge(src, listOf(wario)).xml!!
        assertThat(xml.indexOf("<alternativeEmulator>")).isLessThan(xml.indexOf("<gameList>"))
        assertThat(GamelistXml.entryCount(xml)).isEqualTo(1)
    }

    @Test fun `existing entry is completed, never overwritten, and other entries are kept`() {
        val r = GamelistXml.merge(esdeFile, listOf(wario))
        assertThat(r.added).isEqualTo(0)
        assertThat(r.completed).isEqualTo(1)
        val xml = r.xml!!
        assertThat(xml).contains("<name>My own name</name>")
        assertThat(xml).doesNotContain("<name>Wario Land 4</name>")
        // Blank desc filled, absent fields added, user fields untouched.
        assertThat(xml).contains("<desc>Wario &amp; the golden pyramid &lt;treasure&gt;</desc>")
        assertThat(xml).contains("<favorite>true</favorite>")
        assertThat(xml).contains("<rating>0.8</rating>")
        assertThat(xml).contains("<playcount>3</playcount>")
        assertThat(xml).contains("<desc>Samus is back.</desc>")
        assertThat(GamelistXml.entryCount(xml)).isEqualTo(2)
    }

    @Test fun `merging twice changes nothing the second time`() {
        val first = GamelistXml.merge(null, listOf(wario)).xml!!
        val again = GamelistXml.merge(first, listOf(wario))
        assertThat(again.xml).isNull()
        assertThat(again.added + again.completed).isEqualTo(0)
    }

    @Test fun `path match ignores dot-slash, backslashes and case`() {
        val existing = "<gameList><game><path>.\\psx\\FF7.M3U</path><name>Mine</name></game></gameList>"
        val r = GamelistXml.merge(existing, listOf(GamelistEntry("psx/FF7.m3u", mapOf("name" to "Final Fantasy VII"))))
        assertThat(r.added).isEqualTo(0)
        assertThat(r.xml).isNull()
    }

    @Test fun `new game is appended after the existing ones, folder entries use their tag`() {
        val ff = GamelistEntry("Final Fantasy VII", mapOf("name" to "Final Fantasy VII"), tag = "folder")
        val xml = GamelistXml.merge(esdeFile, listOf(ff)).xml!!
        assertThat(GamelistXml.paths(xml)).containsExactly("Metroid Fusion (USA).gba", "Wario Land 4 (USA, Europe).gba", "Final Fantasy VII").inOrder()
        assertThat(xml).contains("<folder>\n\t\t<path>./Final Fantasy VII</path>")
        // Re-serializing keeps the existing self-closed element and the attribute-free layout.
        assertThat(xml).contains("<lastplayed>20260101T101010</lastplayed>")
    }

    @Test fun `broken or foreign files are refused`() {
        assertThrows(GamelistException::class.java) { GamelistXml.merge("<gameList><game>", listOf(wario)) }
        assertThrows(GamelistException::class.java) { GamelistXml.merge("<settings/>", listOf(wario)) }
        assertThrows(GamelistException::class.java) { GamelistXml.merge("<gameList/><gameList/>", listOf(wario)) }
        assertThrows(GamelistException::class.java) { GamelistXml.merge("<!DOCTYPE x [<!ENTITY a \"b\">]><gameList/>", listOf(wario)) }
    }

    @Test fun `control characters are dropped and blank fields skipped`() {
        val e = GamelistEntry("a.gba", mapOf("name" to "A\u0001B", "desc" to "   "))
        val xml = GamelistXml.merge(null, listOf(e)).xml!!
        assertThat(xml).contains("<name>AB</name>")
        assertThat(xml).doesNotContain("<desc")
    }

    @Test fun `comments and unknown attributes survive`() {
        val src = "<gameList>\n<!-- mine -->\n<game id=\"12\" source=\"ScreenScraper.fr\"><path>./b.gba</path><name>B</name></game></gameList>"
        val xml = GamelistXml.merge(src, listOf(wario)).xml!!
        assertThat(xml).contains("<!-- mine -->")
        assertThat(xml).contains("<game id=\"12\" source=\"ScreenScraper.fr\">")
    }

    @Test fun `RomM values in ES-DE's formats`() {
        assertThat(EsdeMeta.rating(8.0)).isEqualTo("0.8")
        assertThat(EsdeMeta.rating(85.0)).isEqualTo("0.85")
        assertThat(EsdeMeta.rating(10.0)).isEqualTo("1")
        assertThat(EsdeMeta.rating(null)).isNull()
        assertThat(EsdeMeta.rating(0.0)).isNull()
        assertThat(EsdeMeta.releaseDate(998352000L)).isEqualTo("20010821T000000") // seconds
        assertThat(EsdeMeta.releaseDate(998352000000L)).isEqualTo("20010821T000000") // millis
        assertThat(EsdeMeta.players("1-8 players")).isEqualTo("1-8")
        assertThat(EsdeMeta.players("1 - 4")).isEqualTo("1-4")
        assertThat(EsdeMeta.players("2")).isEqualTo("2")
        assertThat(EsdeMeta.players(null)).isNull()
        assertThat(EsdeMeta.stem("Wario Land 4 (USA, Europe).gba")).isEqualTo("Wario Land 4 (USA, Europe)")
        assertThat(EsdeMeta.stem("Folder")).isEqualTo("Folder")
    }

    @Test fun `primary file is the m3u, then the ROM's name, then a disc descriptor`() {
        val disc = mapOf("FF7 (Disc 1).cue" to 1L, "FF7 (Disc 1).bin" to 700L, "FF7.m3u" to 1L, "readme.txt" to 1L)
        assertThat(EsdeMeta.primaryFile(disc, "FF7")).isEqualTo("FF7.m3u")
        val cue = mapOf("Game (Track 1).bin" to 500L, "Game.cue" to 1L, "cover.png" to 9000L)
        assertThat(EsdeMeta.primaryFile(cue, "Something else")).isEqualTo("Game.cue")
        assertThat(EsdeMeta.primaryFile(cue, "Game")).isEqualTo("Game.cue")
        assertThat(EsdeMeta.primaryFile(mapOf("a.gba" to 1L, "b.gba" to 5L), null)).isEqualTo("b.gba")
        assertThat(EsdeMeta.primaryFile(mapOf("save.srm" to 1L), null)).isNull()
    }

    @Test fun `media extension from content type or URL`() {
        assertThat(EsdeExporter.extensionFor("image/png", "http://x/a", video = false)).isEqualTo(".png")
        assertThat(EsdeExporter.extensionFor("image/jpeg; charset=binary", "http://x/a", video = false)).isEqualTo(".jpg")
        assertThat(EsdeExporter.extensionFor(null, "http://x/cover.JPEG?ts=1", video = false)).isEqualTo(".jpg")
        assertThat(EsdeExporter.extensionFor("image/gif", "http://x/a.gif", video = false)).isNull()
        assertThat(EsdeExporter.extensionFor("application/octet-stream", "http://x/clip.mp4", video = true)).isEqualTo(".mp4")
        assertThat(EsdeExporter.extensionFor("video/mp4", "http://x/a", video = false)).isNull()
    }

    @Test fun `es_settings values`() {
        val xml = """<?xml version="1.0"?>
<bool name="LegacyGamelistFileLocation" value="true" />
<string name="MediaDirectory" value="/storage/6CFD-730A/ES-DE &amp; media" />
<string name="ROMDirectory" value="" />"""
        assertThat(EsdeSettingsFile.string(xml, "MediaDirectory")).isEqualTo("/storage/6CFD-730A/ES-DE & media")
        assertThat(EsdeSettingsFile.string(xml, "ROMDirectory")).isEmpty()
        assertThat(EsdeSettingsFile.string(xml, "Missing")).isNull()
        assertThat(EsdeSettingsFile.bool(xml, "LegacyGamelistFileLocation")).isTrue()
    }
}
