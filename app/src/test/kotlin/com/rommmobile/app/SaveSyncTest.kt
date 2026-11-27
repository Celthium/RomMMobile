package com.rommmobile.app

import com.google.common.truth.Truth.assertThat
import com.rommmobile.app.feature.sync.CoreNames
import com.rommmobile.app.feature.sync.EsDeConfig
import com.rommmobile.app.feature.sync.ItemStatus
import com.rommmobile.app.feature.sync.RetroArchConfig
import com.rommmobile.app.feature.sync.SaveSyncEngine
import com.rommmobile.app.core.network.AuthMode
import com.rommmobile.app.data.api.READ_SCOPES
import com.rommmobile.app.data.api.SYNC_SCOPES
import com.rommmobile.app.feature.sync.missingSyncScopes
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

class SaveSyncTest {

    @Test
    fun `reads retroarch cfg lines as on the Thor`() {
        val cfg = RetroArchConfig.parse(
            listOf(
                "# comment",
                "savefile_directory = \"/storage/emulated/0/RetroArch/saves\"",
                "sort_savefiles_enable = \"true\"",
                "savestate_directory = \":/states\"",
                "broken line",
            )
        )
        assertThat(cfg["savefile_directory"]).isEqualTo("/storage/emulated/0/RetroArch/saves")
        assertThat(cfg["sort_savefiles_enable"]).isEqualTo("true")
        assertThat(cfg["savestate_directory"]).isEqualTo(":/states")
        assertThat(cfg).doesNotContainKey("broken line")
    }

    @Test
    fun `strips RomM version tag`() {
        assertThat(SaveSyncEngine.stripTag("Pokemon - Version Emeraude [2026-11-27_10-00-00].srm")).isEqualTo("Pokemon - Version Emeraude.srm")
        assertThat(SaveSyncEngine.stripTag("Game (USA) [!].srm")).isEqualTo("Game (USA) [!].srm")
    }

    @Test
    fun `maps core folders to RomM emulator ids and back`() {
        assertThat(CoreNames.emulatorFor("mGBA")).isEqualTo("mgba")
        assertThat(CoreNames.emulatorFor("Genesis Plus GX")).isEqualTo("genesis_plus_gx")
        assertThat(CoreNames.emulatorFor("Some Core")).isEqualTo("Some Core")
        assertThat(CoreNames.emulatorFor(null)).isNull()
        assertThat(CoreNames.dirFor("snes9x")).isEqualTo("Snes9x")
        assertThat(CoreNames.dirFor("unknown")).isEqualTo("unknown")
    }

    @Test
    fun `ES-DE label names the RetroArch core folder`() {
        val es = EsDeConfig(File("ES-DE"), null, mapOf("gba" to "RetroArch (mGBA)", "psx" to "DuckStation (Standalone)"))
        assertThat(es.coreDirFor("gba")).isEqualTo("mGBA")
        assertThat(es.coreDirFor("psx")).isNull()
        assertThat(es.coreDirFor("snes")).isNull()
    }

    @Test
    fun `the status that needs the user wins`() {
        assertThat(SaveSyncEngine.worst(ItemStatus.SYNCED, ItemStatus.CONFLICT)).isEqualTo(ItemStatus.CONFLICT)
        assertThat(SaveSyncEngine.worst(ItemStatus.UPLOADED, ItemStatus.PENDING)).isEqualTo(ItemStatus.PENDING)
        assertThat(SaveSyncEngine.worst(null, null)).isEqualTo(ItemStatus.NONE)
    }

    @Test
    fun `parses server timestamps with and without offset`() {
        assertThat(SaveSyncEngine.parseInstant("2026-08-09T14:12:00+00:00")?.toEpochMilli()).isEqualTo(1786284720000L)
        assertThat(SaveSyncEngine.parseInstant("2026-08-09T14:12:00")?.toEpochMilli()).isEqualTo(1786284720000L)
        assertThat(SaveSyncEngine.parseInstant("nope")).isNull()
    }

    @Test
    fun `server times match to the second whatever the precision`() {
        // Upload replies carry microseconds, lists do not (RomM 5.4.1, seen on the Thor).
        assertThat(SaveSyncEngine.sameSecond("2026-11-27T13:24:22.754301+00:00", "2026-11-27T13:24:22+00:00")).isTrue()
        assertThat(SaveSyncEngine.sameSecond("2026-11-27T13:24:22+00:00", "2026-11-27T13:24:23+00:00")).isFalse()
        assertThat(SaveSyncEngine.sameSecond(null, null)).isTrue()
    }

    @Test
    fun `a session without stored scopes asks the server`() = runBlocking {
        // Logged in with 2.1: what the server granted is stored, no probe.
        assertThat(missingSyncScopes((READ_SCOPES + SYNC_SCOPES).toSet(), AuthMode.OAUTH) { error("no probe") }).isEmpty()
        assertThat(missingSyncScopes(READ_SCOPES.toSet(), AuthMode.CLIENT_TOKEN) { error("no probe") }).isEqualTo(SYNC_SCOPES)
        // Kept from 2.0 (read scopes only): nothing stored, the server refuses GET /api/devices.
        assertThat(missingSyncScopes(null, AuthMode.OAUTH) { true }).isEqualTo(SYNC_SCOPES)
        assertThat(missingSyncScopes(null, AuthMode.CLIENT_TOKEN) { false }).isEmpty()
        // Basic auth has the whole role; no session, nothing to say.
        assertThat(missingSyncScopes(null, AuthMode.BASIC) { error("no probe") }).isEmpty()
        assertThat(missingSyncScopes(null, AuthMode.NONE) { error("no probe") }).isEmpty()
    }
}
