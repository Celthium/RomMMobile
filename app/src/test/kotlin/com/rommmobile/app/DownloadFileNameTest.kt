package com.rommmobile.app

import com.google.common.truth.Truth.assertThat
import com.rommmobile.app.data.repo.Rom
import com.rommmobile.app.data.repo.RomFile
import org.junit.Test

class DownloadFileNameTest {

    private fun rom(
        name: String = "Wario Land 4",
        fsName: String = "Wario Land 4 (USA, Europe).gba",
        fsNameNoExt: String = "Wario Land 4 (USA, Europe)",
        hasMultipleFiles: Boolean = false,
        files: List<RomFile> = emptyList(),
    ) = Rom(
        id = 1, platformId = 1, platformSlug = "gba", platformName = "Game Boy Advance",
        name = name, fsName = fsName, fsNameNoExt = fsNameNoExt, fsExtension = "gba",
        sizeBytes = 0, coverSmall = null, coverLarge = null,
        regions = emptyList(), languages = emptyList(), revision = null, siblingCount = 0,
        hasMultipleFiles = hasMultipleFiles, md5 = null, sha1 = null, crc = null, summary = null,
        firstReleaseDate = null, rating = null, genres = emptyList(), companies = emptyList(),
        files = files, siblings = emptyList(), missingFromFs = false,
    )

    @Test
    fun `single file keeps the original file name and extension, not the title`() {
        val r = rom()
        assertThat(r.downloadFileName).isEqualTo("Wario Land 4 (USA, Europe).gba")
        assertThat(r.downloadFileName).isNotEqualTo(r.name)
    }

    @Test
    fun `multi file game is saved as a zip named after the folder`() {
        val r = rom(fsName = "Final Fantasy VII", fsNameNoExt = "Final Fantasy VII", hasMultipleFiles = true)
        assertThat(r.downloadFileName).isEqualTo("Final Fantasy VII.zip")
    }

    @Test
    fun `nested single file uses the game file inside the folder`() {
        val game = RomFile(id = 10, fileName = "Bloodborne.pkg", sizeBytes = 100, category = "game", isTopLevel = true)
        val dlc = RomFile(id = 11, fileName = "dlc.pkg", sizeBytes = 10, category = "dlc", isTopLevel = false)
        val r = rom(fsName = "Bloodborne", fsNameNoExt = "Bloodborne", files = listOf(game, dlc))
        assertThat(r.downloadFileName).isEqualTo("Bloodborne.pkg")
    }
}
