package com.rommmobile.app.feature.sync

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rommmobile.app.R
import com.rommmobile.app.core.design.RommDialog
import com.rommmobile.app.core.design.SettingRow
import com.rommmobile.app.core.design.SwitchRow
import com.rommmobile.app.core.design.Toaster
import com.rommmobile.app.feature.settings.ChoiceDialog
import com.rommmobile.app.feature.settings.TextInputDialog
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/** What the settings show about the device side: folders, RetroArch, the permission. */
data class SyncEnvironment(
    val setup: RetroArchSetup,
    val retroArch: RetroArchState,
    val usageAccess: Boolean,
    val missingScopes: List<String>,
)

@HiltViewModel
class SyncSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: SyncPrefs,
    private val engine: SaveSyncEngine,
    private val watcher: RetroArchWatcher,
    private val toaster: Toaster,
) : ViewModel() {
    val settings: StateFlow<SyncSettings?> = prefs.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val state: StateFlow<SyncState> = engine.state
    val running: StateFlow<Boolean> = engine.running
    private val _env = MutableStateFlow<SyncEnvironment?>(null)
    val env: StateFlow<SyncEnvironment?> = _env.asStateFlow()

    val usageAccessIntent get() = watcher.usageAccessIntent()

    fun refresh() = viewModelScope.launch {
        _env.value = SyncEnvironment(engine.setup(), watcher.state(), watcher.hasUsageAccess(), engine.missingScopes())
    }

    fun update(transform: (SyncSettings) -> SyncSettings) = viewModelScope.launch { prefs.update(transform); refresh() }

    fun syncNow() = viewModelScope.launch {
        val r = engine.runAll(auto = false)
        toaster.show(resultText(context, r), isError = r.errors > 0)
    }

    fun restoreNow() = viewModelScope.launch {
        val n = engine.applyPending(force = true)
        toaster.show(context.resources.getQuantityString(R.plurals.sync_restored, n, n))
    }

    fun resolve(key: String, choice: ConflictChoice) = viewModelScope.launch {
        engine.resolve(key, choice)
            .onSuccess { written -> toaster.show(context.getString(if (choice == ConflictChoice.KEEP_SERVER && !written) R.string.sync_resolved_pending else R.string.sync_resolved)) }
            .onFailure { toaster.show(it.message ?: "", isError = true) }
    }
}

fun resultText(context: Context, r: RunResult): String = when {
    r.skipped != null -> context.getString(R.string.sync_disabled_hint)
    r.errors > 0 && r.uploaded + r.downloaded + r.pending + r.conflicts == 0 -> context.getString(R.string.sync_result_failed)
    else -> context.getString(R.string.sync_summary, r.uploaded, r.downloaded, r.pending, r.conflicts)
}

/**
 * Settings > Sync. Each row is a plain focusable row, so the pad walks it top to bottom like the
 * other categories; every choice opens a dialog whose first button holds the focus.
 */
@Composable
fun SyncSettingsSection(card: @Composable (@Composable ColumnScope.() -> Unit) -> Unit, onRelogin: () -> Unit) {
    val vm: SyncSettingsViewModel = hiltViewModel()
    val s = vm.settings.collectAsStateWithLifecycle().value ?: return
    val st by vm.state.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val env by vm.env.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var dialog by remember { mutableStateOf<String?>(null) }
    var conflict by remember { mutableStateOf<Conflict?>(null) }
    // Back from Android's usage-access page, or from RetroArch: read everything again.
    LifecycleResumeEffect(Unit) { vm.refresh(); onPauseOrDispose { } }

    card {
        SwitchRow(stringResource(R.string.sync_enable), s.enabled, { v -> vm.update { it.copy(enabled = v) } }, subtitle = stringResource(R.string.sync_enable_hint))
        SettingRow(
            stringResource(R.string.sync_now),
            subtitle = when {
                running -> stringResource(R.string.sync_running)
                st.lastRunAt != null -> listOfNotNull(
                    DateUtils.getRelativeTimeSpanString(st.lastRunAt!!, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
                    st.lastRunSummary, st.lastRunError,
                ).joinToString(" · ")
                else -> stringResource(R.string.sync_never)
            },
            onClick = { if (!running) vm.syncNow() },
            enabled = s.enabled,
            trailing = if (running) { { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) } } else null,
        )
    }

    env?.missingScopes?.takeIf { it.isNotEmpty() }?.let { missing ->
        card {
            SettingRow(stringResource(R.string.sync_scopes_title), subtitle = stringResource(R.string.sync_scopes_hint, missing.joinToString(", ")), onClick = onRelogin)
        }
    }

    // What needs the user: files waiting for RetroArch to close, and conflicts.
    if (st.pending.isNotEmpty() || st.conflicts.isNotEmpty()) {
        card {
            if (st.pending.isNotEmpty()) {
                SettingRow(
                    context.resources.getQuantityString(R.plurals.sync_notif_pending, st.pending.size, st.pending.size),
                    subtitle = st.pending.joinToString(", ") { it.game } + "\n" + stringResource(R.string.sync_pending_hint),
                    onClick = { dialog = "restore" },
                )
            }
            st.conflicts.forEach { c ->
                SettingRow(
                    stringResource(R.string.sync_conflict_row, c.game, kindLabel(c.kind)),
                    subtitle = conflictSides(context, c),
                    onClick = { conflict = c },
                )
            }
        }
    }

    card {
        SwitchRow(stringResource(R.string.sync_kind_saves), s.saves, { v -> vm.update { it.copy(saves = v) } }, subtitle = stringResource(R.string.sync_kind_saves_hint), enabled = s.enabled)
        SwitchRow(stringResource(R.string.sync_kind_saves_auto), s.savesAuto, { v -> vm.update { it.copy(savesAuto = v) } }, subtitle = stringResource(R.string.sync_auto_hint), enabled = s.enabled && s.saves)
        SwitchRow(stringResource(R.string.sync_kind_states), s.states, { v -> vm.update { it.copy(states = v) } }, subtitle = stringResource(R.string.sync_kind_states_hint), enabled = s.enabled)
        SwitchRow(stringResource(R.string.sync_kind_states_auto), s.statesAuto, { v -> vm.update { it.copy(statesAuto = v) } }, subtitle = stringResource(R.string.sync_states_auto_hint), enabled = s.enabled && s.states)
    }

    env?.let { e ->
        card {
            SettingRow(stringResource(R.string.sync_dir_saves), subtitle = dirSubtitle(context, e.setup.saves), onClick = { dialog = "dir_saves" })
            SettingRow(stringResource(R.string.sync_dir_states), subtitle = dirSubtitle(context, e.setup.states), onClick = { dialog = "dir_states" })
            e.setup.esde?.let { es ->
                SettingRow(
                    stringResource(R.string.sync_esde),
                    subtitle = listOfNotNull(
                        es.dataDir.absolutePath,
                        es.romDirectory?.let { stringResource(R.string.sync_esde_roms, it) },
                        es.alternativeEmulators.takeIf { it.isNotEmpty() }?.entries?.joinToString(", ") { "${it.key}: ${it.value}" },
                    ).joinToString("\n"),
                )
            }
        }
        card {
            SettingRow(
                stringResource(R.string.sync_retroarch),
                subtitle = listOf(
                    e.setup.packageName ?: stringResource(R.string.sync_retroarch_missing),
                    stringResource(retroArchLabel(e.retroArch)),
                ).joinToString(" · "),
            )
            SettingRow(
                stringResource(R.string.sync_usage_access),
                subtitle = stringResource(if (e.usageAccess) R.string.sync_usage_access_on else R.string.sync_usage_access_off),
                onClick = { runCatching { context.startActivity(vm.usageAccessIntent) } },
            )
        }
    }

    when (dialog) {
        "dir_saves", "dir_states" -> {
            val saves = dialog == "dir_saves"
            TextInputDialog(
                title = stringResource(if (saves) R.string.sync_dir_saves else R.string.sync_dir_states),
                initial = (if (saves) s.manualSavesDir else s.manualStatesDir).orEmpty(),
                hint = (if (saves) env?.setup?.saves?.dir else env?.setup?.states?.dir)?.absolutePath ?: "/storage/emulated/0/RetroArch/saves",
                onDismiss = { dialog = null },
            ) { v ->
                vm.update { if (saves) it.copy(manualSavesDir = v.ifBlank { null }) else it.copy(manualStatesDir = v.ifBlank { null }) }
                dialog = null
            }
        }
        "restore" -> RommDialog(
            onDismissRequest = { dialog = null },
            title = stringResource(R.string.sync_restore_title),
            confirmText = stringResource(R.string.sync_restore_confirm),
            onConfirm = { dialog = null; vm.restoreNow() },
            focusConfirm = false,
        ) {
            Text(stringResource(R.string.sync_restore_body), style = MaterialTheme.typography.bodyMedium)
        }
    }
    conflict?.let { c -> ConflictDialog(c, onDismiss = { conflict = null }) { choice -> conflict = null; vm.resolve(c.key, choice) } }
}

/** Two sides, one press each; B leaves it for later. Nothing is written before a side is picked. */
@Composable
fun ConflictDialog(c: Conflict, onDismiss: () -> Unit, onChoose: (ConflictChoice) -> Unit) {
    val context = LocalContext.current
    ChoiceDialog(
        title = stringResource(R.string.sync_conflict_title, c.game),
        options = listOf(
            stringResource(R.string.sync_keep_device) to deviceSide(context, c),
            stringResource(R.string.sync_keep_server) to serverSide(context, c),
        ),
        selected = -1,
        onDismiss = onDismiss,
    ) { i -> onChoose(if (i == 0) ConflictChoice.KEEP_DEVICE else ConflictChoice.KEEP_SERVER) }
}

private fun deviceSide(context: Context, c: Conflict): String =
    context.getString(R.string.sync_side_device, DateUtils.formatDateTime(context, c.localMtime, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME), android.text.format.Formatter.formatShortFileSize(context, c.localSize))

private fun serverSide(context: Context, c: Conflict): String {
    val at = SaveSyncEngine.parseInstant(c.serverUpdatedAt)?.toEpochMilli()
    return context.getString(R.string.sync_side_server, at?.let { DateUtils.formatDateTime(context, it, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME) } ?: "?") +
        "\n" + context.getString(R.string.sync_side_backup)
}

private fun conflictSides(context: Context, c: Conflict) = deviceSide(context, c) + "\n" + serverSide(context, c).substringBefore('\n')

@Composable
fun kindLabel(kind: SaveKind): String = stringResource(if (kind == SaveKind.SAVE) R.string.sync_kind_save_short else R.string.sync_kind_state_short)

fun retroArchLabel(s: RetroArchState): Int = when (s) {
    RetroArchState.CLOSED -> R.string.sync_ra_closed
    RetroArchState.FOREGROUND -> R.string.sync_ra_foreground
    RetroArchState.BACKGROUND -> R.string.sync_ra_background
    RetroArchState.UNKNOWN -> R.string.sync_ra_unknown
}

private fun dirSubtitle(context: Context, kd: KindDirs): String {
    val src = context.getString(
        when (kd.source) {
            PathSource.MANUAL -> R.string.sync_src_manual
            PathSource.RETROARCH_CFG -> R.string.sync_src_cfg
            PathSource.DEFAULT -> R.string.sync_src_default
        }
    )
    val layout = when {
        kd.inContentDir -> context.getString(R.string.sync_layout_content_dir)
        kd.sortByCore -> context.getString(R.string.sync_layout_core)
        else -> null
    }
    val exists = if (File(kd.dir.absolutePath).isDirectory) null else context.getString(R.string.sync_dir_missing)
    return listOfNotNull(kd.dir.absolutePath, listOfNotNull(src, layout, exists).joinToString(" · ")).joinToString("\n")
}
