package com.rommmobile.app.feature.esde

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
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
import com.rommmobile.app.core.storage.StorageLocations
import com.rommmobile.app.core.storage.StorageRoot
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
import javax.inject.Inject

@HiltViewModel
class EsdeSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: EsdePrefs,
    private val exporter: EsdeExporter,
) : ViewModel() {
    val settings: StateFlow<EsdeExportSettings?> = prefs.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val progress: StateFlow<CatchUpProgress> = exporter.progress
    private val _status = MutableStateFlow<EsdeStatus?>(null)
    val status: StateFlow<EsdeStatus?> = _status.asStateFlow()

    fun refresh() = viewModelScope.launch { _status.value = runCatching { exporter.status() }.getOrNull() }

    fun update(transform: (EsdeExportSettings) -> EsdeExportSettings) = viewModelScope.launch { prefs.update(transform); refresh() }

    fun setDir(root: StorageRoot?) = update { it.copy(manualDir = root?.let { r -> if (r is StorageRoot.Direct) r.path else r.serialize() }) }

    fun exportExisting() = exporter.exportExisting()
}

/**
 * Settings > Launcher and folders, while the launcher is ES-DE: export of covers, screenshots,
 * logos, clips and `gamelist.xml` entries. Plain focusable rows, so the pad walks them like the
 * rest of the category; every choice opens a dialog.
 */
@Composable
fun EsdeSettingsSection(card: @Composable (@Composable ColumnScope.() -> Unit) -> Unit) {
    val vm: EsdeSettingsViewModel = hiltViewModel()
    val s = vm.settings.collectAsStateWithLifecycle().value ?: return
    val status by vm.status.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var dialog by remember { mutableStateOf<String?>(null) }
    // Back from Android's file-access page or from the folder picker: look again.
    LifecycleResumeEffect(Unit) { vm.refresh(); onPauseOrDispose { } }

    val target = (status as? EsdeStatus.Ready)?.target
    card {
        SwitchRow(stringResource(R.string.esde_export), s.enabled, { v -> vm.update { it.copy(enabled = v) } }, subtitle = stringResource(R.string.esde_export_hint))
        SettingRow(
            stringResource(R.string.esde_dir),
            subtitle = when (val st = status) {
                null -> "…"
                is EsdeStatus.Ready -> st.target.dataPath + if (s.manualDir.isNullOrBlank()) " · " + stringResource(R.string.esde_dir_auto_short) else ""
                EsdeStatus.NotFound -> stringResource(R.string.esde_dir_not_found)
                EsdeStatus.NoAccess -> stringResource(R.string.esde_dir_no_access)
            },
            onClick = { dialog = "dir" },
            enabled = s.enabled,
        )
        if (target != null) {
            SettingRow(
                stringResource(R.string.esde_media_dir),
                subtitle = listOfNotNull(
                    target.mediaPath ?: stringResource(R.string.esde_media_dir_unsupported, target.mediaProblem.orEmpty()),
                    if (target.legacyGamelists) stringResource(R.string.esde_legacy_gamelists) else null,
                ).joinToString("\n"),
                enabled = s.enabled,
            )
        }
    }

    card {
        SwitchRow(stringResource(R.string.esde_media_cover), s.cover, { v -> vm.update { it.copy(cover = v) } }, enabled = s.enabled)
        SwitchRow(stringResource(R.string.esde_media_screenshot), s.screenshot, { v -> vm.update { it.copy(screenshot = v) } }, enabled = s.enabled)
        SwitchRow(stringResource(R.string.esde_media_marquee), s.marquee, { v -> vm.update { it.copy(marquee = v) } }, subtitle = stringResource(R.string.esde_media_marquee_hint), enabled = s.enabled)
        SwitchRow(stringResource(R.string.esde_media_video), s.video, { v -> vm.update { it.copy(video = v) } }, subtitle = stringResource(R.string.esde_media_video_hint), enabled = s.enabled)
        SwitchRow(stringResource(R.string.esde_media_metadata), s.metadata, { v -> vm.update { it.copy(metadata = v) } }, subtitle = stringResource(R.string.esde_media_metadata_hint), enabled = s.enabled)
    }

    card {
        SettingRow(
            stringResource(R.string.esde_catch_up),
            subtitle = when {
                progress.running -> stringResource(R.string.esde_catch_up_running, progress.done, progress.total)
                progress.last != null -> progress.last!!.let { r -> stringResource(R.string.esde_catch_up_result, r.games, r.media, r.added, r.completed) + if (r.errors > 0) " · " + stringResource(R.string.esde_catch_up_errors, r.errors) else "" }
                else -> stringResource(R.string.esde_catch_up_hint)
            },
            onClick = { if (!progress.running) dialog = "catchup" },
            enabled = s.enabled && target != null,
            trailing = if (progress.running) { { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) } } else null,
        )
    }

    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            StorageLocations.persistTree(context, uri)
            vm.setDir(StorageLocations.rootFromTree(context, uri))
        }
    }

    when (dialog) {
        "dir" -> {
            val canPick = StorageLocations.canOpenTree(context)
            val options = buildList {
                add(Triple("auto", stringResource(R.string.esde_dir_auto), stringResource(R.string.esde_dir_auto_hint)))
                if (canPick) add(Triple("pick", stringResource(R.string.esde_dir_pick), null))
                add(Triple("type", stringResource(R.string.esde_dir_type), null))
                if (status == EsdeStatus.NoAccess) add(Triple("grant", stringResource(R.string.esde_dir_grant), stringResource(R.string.esde_dir_grant_hint)))
            }
            ChoiceDialog(stringResource(R.string.esde_dir), options.map { it.second to it.third }, if (s.manualDir.isNullOrBlank()) 0 else -1, onDismiss = { dialog = null }) { i ->
                dialog = null
                when (options[i].first) {
                    "auto" -> vm.setDir(null)
                    "pick" -> runCatching { treeLauncher.launch(null) }
                    "type" -> dialog = "dir_path"
                    else -> StorageLocations.allFilesAccessIntent(context)?.let { runCatching { context.startActivity(it) } }
                }
            }
        }
        "dir_path" -> TextInputDialog(
            title = stringResource(R.string.esde_dir),
            initial = s.manualDir?.takeIf { it.startsWith("/") } ?: target?.dataPath?.takeIf { it.startsWith("/") }.orEmpty(),
            hint = "/storage/emulated/0/ES-DE",
            onDismiss = { dialog = null },
        ) { v -> vm.setDir(v.ifBlank { null }?.let { StorageRoot.Direct(it.trimEnd('/')) }); dialog = null }
        "catchup" -> RommDialog(
            onDismissRequest = { dialog = null },
            title = stringResource(R.string.esde_catch_up),
            confirmText = stringResource(R.string.action_continue),
            onConfirm = { dialog = null; vm.exportExisting() },
            focusConfirm = false,
        ) {
            Text(stringResource(R.string.esde_catch_up_body))
        }
    }
}
