package com.rommmobile.app.feature.sync

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.layout.Box
import com.rommmobile.app.core.design.SettingRow
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.SyncProblem
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rommmobile.app.R
import com.rommmobile.app.core.design.PillShape
import com.rommmobile.app.core.design.RommDialog
import com.rommmobile.app.core.design.RommTheme
import com.rommmobile.app.core.design.Toaster
import com.rommmobile.app.core.design.gamepadFocusRing
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What RomM holds for the game, as the Save data tab shows it. */
sealed interface ServerDataState {
    data object Loading : ServerDataState
    data class Ready(val data: SaveSyncEngine.ServerData) : ServerDataState
    data class Failed(val message: String) : ServerDataState
}

@HiltViewModel
class GameSyncViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    prefs: SyncPrefs,
    private val engine: SaveSyncEngine,
    private val toaster: Toaster,
) : ViewModel() {
    val settings: StateFlow<SyncSettings?> = prefs.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val state: StateFlow<SyncState> = engine.state
    val running: StateFlow<Boolean> = engine.running

    private val _data = kotlinx.coroutines.flow.MutableStateFlow<ServerDataState>(ServerDataState.Loading)
    val data: StateFlow<ServerDataState> = _data
    private var romId = 0

    /** RomM's saves and states for the game, and which are on the device. Kept on screen while it reloads. */
    fun load(romId: Int) = viewModelScope.launch {
        this@GameSyncViewModel.romId = romId
        engine.serverData(romId)
            .onSuccess { _data.value = ServerDataState.Ready(it) }
            .onFailure { e -> if (_data.value !is ServerDataState.Ready) _data.value = ServerDataState.Failed(e.message ?: "") }
    }

    fun sync(romId: Int) = viewModelScope.launch {
        val r = engine.syncGame(romId)
        toaster.show(resultText(context, r), isError = r.errors > 0)
        load(romId)
    }

    fun resolve(key: String, choice: ConflictChoice) = viewModelScope.launch {
        engine.resolve(key, choice)
            .onSuccess { written -> toaster.show(context.getString(if (choice == ConflictChoice.KEEP_SERVER && !written) R.string.sync_resolved_pending else R.string.sync_resolved)) }
            .onFailure { toaster.show(it.message ?: "", isError = true) }
        if (romId != 0) load(romId)
    }

    fun restoreNow() = viewModelScope.launch {
        val n = engine.applyPending(force = true)
        toaster.show(context.resources.getQuantityString(R.plurals.sync_restored, n, n))
        if (romId != 0) load(romId)
    }

    fun restoreVersion(romId: Int, kind: SaveKind, serverId: Int) = viewModelScope.launch {
        engine.restoreVersion(romId, kind, serverId)
            .onSuccess { written -> toaster.show(context.getString(if (written) R.string.sync_resolved else R.string.sync_resolved_pending)) }
            .onFailure { toaster.show(it.message ?: "", isError = true) }
        load(romId)
    }
}

@Composable
internal fun gameSyncVm(romId: Int): GameSyncViewModel = hiltViewModel(key = "game-sync-$romId")

/** True while the sync is on for at least one kind: the game page then shows its button and tab. */
@Composable
fun rememberGameSyncOn(romId: Int): Boolean {
    val s = gameSyncVm(romId).settings.collectAsStateWithLifecycle().value ?: return false
    return SaveKind.entries.any { s.kindEnabled(it) }
}

/** What needs the user on this game, worst first: drawn as a dot on the sync button. */
private fun openIssue(st: SyncState, romId: Int): ItemStatus? = when {
    st.conflicts.any { it.romId == romId } -> ItemStatus.CONFLICT
    st.pending.any { it.romId == romId } -> ItemStatus.PENDING
    else -> null
}

/**
 * Game page, next to Download: syncs this game's saves and states now. A dot on the icon says a
 * conflict (amber) or a file waiting for RetroArch (blue) is open in the Saves tab.
 */
@Composable
fun GameSyncButton(romId: Int, modifier: Modifier = Modifier, focus: FocusRequester? = null) {
    if (!rememberGameSyncOn(romId)) return
    val vm = gameSyncVm(romId)
    val st by vm.state.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val colors = RommTheme.colors
    val dot = when (openIssue(st, romId)) {
        ItemStatus.CONFLICT -> colors.warning
        ItemStatus.PENDING -> colors.info
        else -> null
    }
    // Same shape and weight as the page's secondary pill ("Download again").
    Button(
        onClick = { if (!running) vm.sync(romId) },
        shape = PillShape,
        colors = ButtonDefaults.buttonColors(containerColor = colors.surface, contentColor = colors.onSurface),
        border = BorderStroke(1.dp, colors.borderStrong),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
        modifier = modifier.then(if (focus != null) Modifier.focusRequester(focus) else Modifier).gamepadFocusRing(PillShape),
    ) {
        Box {
            if (running) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.onSurface)
            else Icon(Icons.Rounded.Sync, null, modifier = Modifier.size(18.dp))
            if (dot != null && !running) {
                Box(
                    Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-3).dp).size(9.dp)
                        .background(dot, CircleShape).border(1.5.dp, colors.surface, CircleShape),
                )
            }
        }
        Spacer(Modifier.width(7.dp))
        Text(stringResource(if (running) R.string.sync_running else R.string.sync_action), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
internal fun statusLook(s: ItemStatus): Pair<ImageVector, Color?> {
    val c = RommTheme.colors
    return when (s) {
        ItemStatus.CONFLICT -> Icons.Rounded.SyncProblem to c.warning
        ItemStatus.PENDING -> Icons.Rounded.HourglassTop to c.info
        ItemStatus.ERROR -> Icons.Rounded.ErrorOutline to c.red
        ItemStatus.UPLOADED -> Icons.Rounded.CloudUpload to c.success
        ItemStatus.DOWNLOADED -> Icons.Rounded.CloudDownload to c.success
        ItemStatus.SYNCED -> Icons.Rounded.CloudDone to c.success
        ItemStatus.NONE -> Icons.Rounded.CloudOff to null
    }
}

internal fun statusText(context: Context, s: ItemStatus, g: GameSyncStatus?): String {
    val base = context.getString(
        when (s) {
            ItemStatus.CONFLICT -> R.string.sync_st_conflict
            ItemStatus.PENDING -> R.string.sync_st_pending
            ItemStatus.ERROR -> R.string.sync_st_error
            ItemStatus.UPLOADED -> R.string.sync_st_uploaded
            ItemStatus.DOWNLOADED -> R.string.sync_st_downloaded
            ItemStatus.SYNCED -> R.string.sync_st_synced
            ItemStatus.NONE -> if (g == null) R.string.sync_st_never else R.string.sync_st_none
        }
    )
    return base
}
