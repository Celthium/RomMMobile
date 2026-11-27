package com.rommmobile.app.feature.sync

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rommmobile.app.R
import com.rommmobile.app.core.design.PillShape
import com.rommmobile.app.core.design.RommDialog
import com.rommmobile.app.core.design.RommTheme
import com.rommmobile.app.core.design.SettingRow
import com.rommmobile.app.core.design.gamepadFocusRing
import com.rommmobile.app.data.api.SaveDto
import com.rommmobile.app.data.api.StateDto
import kotlinx.coroutines.delay

/** Number of files RomM holds for the game (saves and states), for the tab's badge; null until known. */
@Composable
fun rememberSaveDataCount(romId: Int): Int? {
    val vm = gameSyncVm(romId)
    LaunchedEffect(romId) { vm.load(romId) }
    val d = vm.data.collectAsStateWithLifecycle().value
    return (d as? ServerDataState.Ready)?.data?.let { it.saves.size + it.states.size }
}

/** A version from RomM the user asked to put back on the device. */
private data class RestoreAsk(val kind: SaveKind, val id: Int, val name: String)

/**
 * Save data tab, laid out like RomM web's: Saves / States side by side as sub-tabs with their
 * counts; saves grouped by slot ("AUTOSAVE · 3 versions") with every version kept on the server;
 * states one row each. The copy on this device is marked; any other one can be put back (A).
 */
@Composable
fun GameSavesTab(romId: Int, returnFocus: FocusRequester? = null) {
    val vm = gameSyncVm(romId)
    val s = vm.settings.collectAsStateWithLifecycle().value ?: return
    val st by vm.state.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val colors = RommTheme.colors
    val context = LocalContext.current
    var conflict by remember { mutableStateOf<Conflict?>(null) }
    var restore by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf<RestoreAsk?>(null) }
    val kinds = SaveKind.entries.filter { s.kindEnabled(it) }
    var kindIndex by rememberSaveable(romId) { mutableIntStateOf(0) }
    val kind = kinds.getOrNull(kindIndex) ?: kinds.firstOrNull() ?: return
    val ready = (data as? ServerDataState.Ready)?.data

    // A row the pad was on can stop being pressable once dealt with: the focus goes back to Sync.
    var refocus by remember { mutableIntStateOf(0) }
    LaunchedEffect(refocus) {
        if (refocus == 0 || returnFocus == null) return@LaunchedEffect
        repeat(6) { delay(60); runCatching { returnFocus.requestFocus() } }
    }

    Column(Modifier.fillMaxWidth()) {
        // Sub-tabs, as the web's left column: Saves (n) / States (n).
        if (kinds.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                kinds.forEachIndexed { i, k ->
                    val count = ready?.let { if (k == SaveKind.SAVE) it.saves.size else it.states.size }
                    SubTab(
                        label = stringResource(if (k == SaveKind.SAVE) R.string.sync_sub_saves else R.string.sync_sub_states),
                        icon = if (k == SaveKind.SAVE) Icons.Rounded.Save else Icons.Rounded.PhotoCamera,
                        count = count,
                        selected = k == kind,
                        onClick = { kindIndex = i },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // Where this device stands for that kind (the sync status), as before.
        val g = st.game(romId, kind)
        val conflicts = st.conflicts.filter { it.romId == romId && it.kind == kind }
        val pending = st.pending.filter { it.romId == romId && it.kind == kind }
        val status = SaveSyncEngine.worst(g?.status, when {
            conflicts.isNotEmpty() -> ItemStatus.CONFLICT
            pending.isNotEmpty() -> ItemStatus.PENDING
            else -> null
        })
        val (icon, tone) = statusLook(status)
        Card {
            SettingRow(
                title = stringResource(R.string.sync_this_device),
                subtitle = listOfNotNull(
                    g?.at?.takeIf { status != ItemStatus.NONE }?.let { DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString() },
                    g?.detail?.takeIf { status == ItemStatus.ERROR },
                    when {
                        conflicts.isNotEmpty() -> stringResource(R.string.sync_row_hint_conflict)
                        pending.isNotEmpty() -> stringResource(R.string.sync_row_hint_pending)
                        else -> null
                    },
                ).joinToString(" · ").ifEmpty { null },
                onClick = when {
                    conflicts.isNotEmpty() -> ({ conflict = conflicts.first() })
                    pending.isNotEmpty() -> ({ restore = true })
                    else -> null
                },
                trailing = { StatusLabel(statusText(context, status, g), icon, tone) },
            )
        }
        Spacer(Modifier.height(12.dp))

        when (val d = data) {
            ServerDataState.Loading -> Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            is ServerDataState.Failed -> Text(stringResource(R.string.sync_server_error) + "\n" + d.message, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceMuted)
            is ServerDataState.Ready -> when (kind) {
                SaveKind.SAVE -> SavesList(d.data, onRestore = { ask = it })
                SaveKind.STATE -> StatesList(d.data, onRestore = { ask = it })
            }
        }
    }

    conflict?.let { c -> ConflictDialog(c, onDismiss = { conflict = null }) { choice -> conflict = null; refocus++; vm.resolve(c.key, choice) } }
    if (restore) {
        RommDialog(
            onDismissRequest = { restore = false },
            title = stringResource(R.string.sync_restore_title),
            confirmText = stringResource(R.string.sync_restore_confirm),
            onConfirm = { restore = false; refocus++; vm.restoreNow() },
            focusConfirm = false,
        ) { Text(stringResource(R.string.sync_restore_body), style = MaterialTheme.typography.bodyMedium) }
    }
    ask?.let { a ->
        RommDialog(
            onDismissRequest = { ask = null },
            title = stringResource(R.string.sync_version_title),
            confirmText = stringResource(R.string.sync_restore_action),
            onConfirm = { ask = null; vm.restoreVersion(romId, a.kind, a.id) },
            focusConfirm = false,
        ) {
            Text(
                a.name + "\n\n" + stringResource(if (a.kind == SaveKind.SAVE) R.string.sync_version_body_save else R.string.sync_version_body_state),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun SavesList(d: SaveSyncEngine.ServerData, onRestore: (RestoreAsk) -> Unit) {
    if (d.saves.isEmpty()) { Empty(); return }
    val context = LocalContext.current
    // One group per slot, the most recent version first; slot-less (manual) uploads last.
    val groups = d.saves.groupBy { it.slot }.toList()
        .sortedWith(compareBy<Pair<String?, List<SaveDto>>> { it.first == null }.thenBy { it.first })
    groups.forEachIndexed { gi, (slot, versions) ->
        if (gi > 0) Spacer(Modifier.height(12.dp))
        Card {
            GroupHeader(
                title = (slot ?: stringResource(R.string.sync_no_slot)).uppercase(),
                trailing = pluralStringResource(R.plurals.sync_versions, versions.size, versions.size),
            )
            versions.sortedByDescending { SaveSyncEngine.parseInstant(it.updatedAt)?.toEpochMilli() ?: 0L }.forEach { v ->
                val here = v.contentHash != null && v.contentHash in d.localSaveHashes
                VersionRow(
                    title = v.fileName,
                    subtitle = details(context, v.fileSizeBytes, v.updatedAt, v.emulator),
                    onDevice = here,
                    onClick = { onRestore(RestoreAsk(SaveKind.SAVE, v.id, v.fileName)) },
                )
            }
        }
    }
}

@Composable
private fun StatesList(d: SaveSyncEngine.ServerData, onRestore: (RestoreAsk) -> Unit) {
    if (d.states.isEmpty()) { Empty(); return }
    val context = LocalContext.current
    Card {
        d.states.sortedByDescending { SaveSyncEngine.parseInstant(it.updatedAt)?.toEpochMilli() ?: 0L }.forEach { v: StateDto ->
            // States carry no hash: "on this device" is the same name with the bytes last synced.
            val name = SaveSyncEngine.stripTag(v.fileName)
            val here = d.localStates.containsKey(name) && v.id in d.syncedStateIds
            VersionRow(
                title = v.fileName,
                subtitle = details(context, v.fileSizeBytes, v.updatedAt, v.emulator),
                onDevice = here,
                onClick = { onRestore(RestoreAsk(SaveKind.STATE, v.id, v.fileName)) },
            )
        }
    }
}

private fun details(context: android.content.Context, size: Long, updatedAt: String?, emulator: String?): String = listOfNotNull(
    Formatter.formatShortFileSize(context, size),
    SaveSyncEngine.parseInstant(updatedAt)?.toEpochMilli()?.let { DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString() },
    emulator,
).joinToString(" · ")

@Composable
private fun VersionRow(title: String, subtitle: String, onDevice: Boolean, onClick: () -> Unit) {
    val colors = RommTheme.colors
    SettingRow(
        title = title,
        subtitle = subtitle,
        onClick = onClick,
        modifier = Modifier.heightIn(min = 64.dp),
        trailing = {
            if (onDevice) Chip(stringResource(R.string.sync_on_device), colors.success)
            else Text(stringResource(R.string.sync_restore_action), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceMuted)
        },
    )
}

@Composable
private fun GroupHeader(title: String, trailing: String) {
    val colors = RommTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = RommTheme.layout.margin, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Save, null, modifier = Modifier.size(16.dp), tint = colors.primaryLighten)
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(trailing, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceMuted)
    }
}

@Composable
private fun SubTab(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, count: Int?, selected: Boolean, onClick: () -> Unit) {
    val colors = RommTheme.colors
    val shape = PillShape
    Row(
        Modifier
            .gamepadFocusRing(shape)
            .clip(shape)
            .background(if (selected) colors.primary.copy(alpha = 0.18f) else colors.surface)
            .border(1.dp, if (selected) colors.primary.copy(alpha = 0.6f) else colors.borderStrong, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, modifier = Modifier.size(16.dp), tint = if (selected) colors.primaryLighten else colors.onSurfaceMuted)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) colors.onSurface else colors.onSurfaceMuted)
        if (count != null) {
            Spacer(Modifier.width(8.dp))
            CountBadge(count.toString(), selected)
        }
    }
}

/** Small rounded count, as next to the web's tab and sub-tab names. */
@Composable
fun CountBadge(text: String, highlighted: Boolean, modifier: Modifier = Modifier) {
    val colors = RommTheme.colors
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = if (highlighted) colors.primaryLighten else colors.onSurfaceMuted,
        modifier = modifier
            .background(if (highlighted) colors.primary.copy(alpha = 0.22f) else colors.surfaceHover, PillShape)
            .padding(horizontal = 7.dp, vertical = 1.dp),
    )
}

@Composable
private fun Chip(text: String, tone: Color) {
    val shape = RoundedCornerShape(6.dp)
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = tone,
        modifier = Modifier.background(tone.copy(alpha = 0.12f), shape).border(1.dp, tone.copy(alpha = 0.4f), shape).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun StatusLabel(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tone: Color?) {
    val colors = RommTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, modifier = Modifier.size(18.dp), tint = tone ?: colors.onSurfaceMuted)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = tone ?: colors.onSurfaceMuted)
    }
}

@Composable
private fun Empty() {
    Text(stringResource(R.string.sync_server_empty), style = MaterialTheme.typography.bodyMedium, color = RommTheme.colors.onSurfaceMuted, modifier = Modifier.padding(8.dp))
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(RommTheme.colors.surface).padding(vertical = 4.dp),
        content = content,
    )
}
