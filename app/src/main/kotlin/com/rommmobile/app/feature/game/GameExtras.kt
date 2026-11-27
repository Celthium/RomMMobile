package com.rommmobile.app.feature.game

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.LibraryAddCheck
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.rommmobile.app.R
import com.rommmobile.app.core.design.PillShape
import com.rommmobile.app.core.design.RommDialog
import com.rommmobile.app.core.design.RommTheme
import com.rommmobile.app.core.design.SettingRow
import com.rommmobile.app.core.design.Toaster
import com.rommmobile.app.core.design.gamepadFocusRing
import com.rommmobile.app.core.network.ApiErrorKind
import com.rommmobile.app.core.network.ApiException
import com.rommmobile.app.core.network.ServerStore
import com.rommmobile.app.core.network.UrlNormalizer
import com.rommmobile.app.core.network.apiCall
import com.rommmobile.app.data.api.CollectionDto
import com.rommmobile.app.data.api.CollectionRomsBody
import com.rommmobile.app.data.api.MeRaDto
import com.rommmobile.app.data.api.RaAchievementDto
import com.rommmobile.app.data.api.RaRefreshBody
import com.rommmobile.app.data.api.RommApi
import com.rommmobile.app.data.repo.CollectionRepository
import com.rommmobile.app.feature.sync.SaveSyncEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject

/** One achievement as the tab draws it. */
data class AchievementUi(
    val a: RaAchievementDto,
    /** ISO date it was unlocked (softcore or hardcore), null while locked. */
    val earnedAt: String?,
    val hardcore: Boolean,
    val badge: String?,
)

data class GameExtras(
    val raId: Int?,
    val achievements: List<AchievementUi>,
    /** Null when the user has not set a RetroAchievements username on RomM. */
    val raUsername: String?,
    val userId: Int,
    /** Collections (own or not) the game is in. */
    val inCollections: Set<Int>,
    /** The user's own regular collections, favourites excluded. */
    val ownCollections: List<CollectionDto>,
    val favoriteId: Int?,
) {
    val isFavorite: Boolean get() = favoriteId != null && favoriteId in inCollections
    val earned: Int get() = achievements.count { it.earnedAt != null }
}

/**
 * What the game page adds on top of the ROM record: RetroAchievements (the set and the user's
 * progress) and the user's collections, read with the same detail call plus `/users/me`.
 */
@HiltViewModel
class GameExtrasViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: RommApi,
    private val serverStore: ServerStore,
    private val collections: CollectionRepository,
    private val toaster: Toaster,
) : ViewModel() {
    private val _extras = MutableStateFlow<GameExtras?>(null)
    val extras: StateFlow<GameExtras?> = _extras
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    private var romId = 0

    fun load(romId: Int) = viewModelScope.launch {
        this@GameExtrasViewModel.romId = romId
        apiCall {
            coroutineScope {
                val rom = async { api.romExtras(romId) }
                val me = async { runCatching { api.meRa() }.getOrNull() }
                val cols = async { runCatching { api.collections() }.getOrDefault(emptyList()) }
                build(rom.await(), me.await(), cols.await())
            }
        }.onSuccess { _extras.value = it }
    }

    private fun build(rom: com.rommmobile.app.data.api.RomExtrasDto, me: MeRaDto?, cols: List<CollectionDto>): GameExtras {
        val base = serverStore.config.value.activeUrl
        val progress = me?.raProgression?.results?.firstOrNull { it.romRaId != null && it.romRaId == rom.raId }
        val earned = progress?.earned.orEmpty().associateBy { it.id }
        fun res(path: String?, url: String?): String? {
            val p = path?.takeIf { it.isNotBlank() }
            if (p != null && base != null) return UrlNormalizer.resource(base, if (p.startsWith("/") || p.startsWith("http")) p else "assets/romm/resources/$p")
            return url?.takeIf { it.isNotBlank() }
        }
        val achievements = rom.raMetadata?.achievements.orEmpty().sortedBy { it.displayOrder }.map { a ->
            val e = earned[a.raId?.toString()]
            val at = e?.dateHardcore?.takeIf { it.isNotBlank() } ?: e?.date?.takeIf { it.isNotBlank() }
            AchievementUi(
                a = a,
                earnedAt = at,
                hardcore = !e?.dateHardcore.isNullOrBlank(),
                badge = if (at != null) res(a.badgePath, a.badgeUrl) else (res(a.badgePathLock, a.badgeUrlLock) ?: res(a.badgePath, a.badgeUrl)),
            )
        }
        val uid = me?.id ?: 0
        val mine = cols.filter { c -> (c.userId != null && c.userId == uid) || (c.userId == null && me != null && c.ownerUsername == me.username) }
        return GameExtras(
            raId = rom.raId,
            achievements = achievements,
            raUsername = me?.raUsername?.takeIf { it.isNotBlank() },
            userId = uid,
            inCollections = rom.userCollections.map { it.id }.toSet(),
            ownCollections = mine.filterNot { it.isFavorite },
            favoriteId = mine.firstOrNull { it.isFavorite }?.id,
        )
    }

    fun toggleFavorite() = act { x ->
        val favId = x.favoriteId ?: api.createCollection(true, "Favourites".toRequestBody(null)).id
        val adding = !x.isFavorite
        if (adding) api.addToCollection(favId, CollectionRomsBody(listOf(romId))) else api.removeFromCollection(favId, CollectionRomsBody(listOf(romId)))
        context.getString(if (adding) R.string.fav_added else R.string.fav_removed)
    }

    fun toggleCollection(id: Int) = act { x ->
        if (id in x.inCollections) api.removeFromCollection(id, CollectionRomsBody(listOf(romId))) else api.addToCollection(id, CollectionRomsBody(listOf(romId)))
        null
    }

    fun refreshAchievements() = act { x ->
        api.raRefresh(x.userId, RaRefreshBody(incremental = true))
        context.getString(R.string.ra_refreshed)
    }

    /** One change on the server, then a fresh read; the Collections screen is refreshed too. */
    private fun act(block: suspend (GameExtras) -> String?) = viewModelScope.launch {
        val x = _extras.value ?: return@launch
        if (_busy.value) return@launch
        _busy.value = true
        apiCall { block(x) }
            .onSuccess { msg -> msg?.let { toaster.show(it) } }
            .onFailure { e ->
                val ae = e as? ApiException
                toaster.show(if (ae?.kind == ApiErrorKind.FORBIDDEN) context.getString(R.string.extras_forbidden) else (ae?.userMessage(context) ?: e.message.orEmpty()), isError = true)
            }
        collections.refreshUser()
        load(romId).join()
        _busy.value = false
    }
}

@Composable
internal fun gameExtrasVm(romId: Int): GameExtrasViewModel {
    val vm: GameExtrasViewModel = hiltViewModel(key = "game-extras-$romId")
    LaunchedEffect(romId) { if (vm.extras.value == null) vm.load(romId) }
    return vm
}

/** Earned / total when the game has a RetroAchievements set, for the tab and its badge; null otherwise. */
@Composable
fun rememberAchievementCount(romId: Int): Pair<Int, Int>? {
    val x = gameExtrasVm(romId).extras.collectAsStateWithLifecycle().value ?: return null
    if (x.achievements.isEmpty()) return null
    return x.earned to x.achievements.size
}

/** Heart and collections, icon only, next to the page's actions and at their height. */
@Composable
fun GameCollectionButtons(romId: Int, sameHeight: Modifier) {
    val vm = gameExtrasVm(romId)
    val x = vm.extras.collectAsStateWithLifecycle().value ?: return
    var manage by remember { mutableStateOf(false) }
    val colors = RommTheme.colors
    IconPill(
        icon = if (x.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
        description = stringResource(if (x.isFavorite) R.string.fav_remove else R.string.fav_add),
        tint = if (x.isFavorite) colors.red else colors.onSurface,
        modifier = sameHeight,
        onClick = vm::toggleFavorite,
    )
    IconPill(
        icon = if (x.ownCollections.any { it.id in x.inCollections }) Icons.Rounded.LibraryAddCheck else Icons.Rounded.LibraryAdd,
        description = stringResource(R.string.collections_manage),
        tint = colors.onSurface,
        modifier = sameHeight,
        onClick = { manage = true },
    )
    if (manage) CollectionsDialog(x, onToggle = vm::toggleCollection, onDismiss = { manage = false })
}

@Composable
private fun IconPill(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, tint: androidx.compose.ui.graphics.Color, modifier: Modifier, onClick: () -> Unit) {
    val colors = RommTheme.colors
    Button(
        onClick = onClick,
        shape = PillShape,
        colors = ButtonDefaults.buttonColors(containerColor = colors.surface, contentColor = tint),
        border = BorderStroke(1.dp, colors.borderStrong),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        modifier = modifier.gamepadFocusRing(PillShape),
    ) {
        Icon(icon, description, modifier = Modifier.size(20.dp), tint = tint)
    }
}

/** The user's own collections, ticked when the game is in them; each press adds or removes at once. */
@Composable
private fun CollectionsDialog(x: GameExtras, onToggle: (Int) -> Unit, onDismiss: () -> Unit) {
    val colors = RommTheme.colors
    RommDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.collections_manage),
        confirmText = stringResource(R.string.action_close),
        onConfirm = onDismiss,
        dismissText = null,
        focusConfirm = x.ownCollections.isEmpty(),
    ) {
        if (x.ownCollections.isEmpty()) {
            Text(stringResource(R.string.collections_empty), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceMuted)
        } else {
            // The pad starts on the first collection, not on Close.
            val first = remember { androidx.compose.ui.focus.FocusRequester() }
            LaunchedEffect(Unit) { repeat(6) { kotlinx.coroutines.delay(40); runCatching { first.requestFocus() } } }
            Column(Modifier.verticalScroll(rememberScrollState())) {
                x.ownCollections.sortedBy { it.name.lowercase() }.forEachIndexed { i, c ->
                    val inIt = c.id in x.inCollections
                    Row(
                        Modifier.fillMaxWidth()
                            .then(if (i == 0) Modifier.focusRequester(first) else Modifier)
                            .gamepadFocusRing(MaterialTheme.shapes.small).clip(MaterialTheme.shapes.small)
                            .clickable { onToggle(c.id) }.padding(vertical = 4.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = inIt, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(androidx.compose.ui.res.pluralStringResource(R.plurals.games_count, c.romCount, c.romCount), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceMuted)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Achievements tab: progress (unlocked, points, hardcore) and a refresh of the RetroAchievements
 * progress through RomM, then the set in RA's order, unlocked ones in colour with their date.
 */
@Composable
fun AchievementsTab(romId: Int) {
    val vm = gameExtrasVm(romId)
    val x = vm.extras.collectAsStateWithLifecycle().value ?: return
    val busy by vm.busy.collectAsStateWithLifecycle()
    val colors = RommTheme.colors
    val context = LocalContext.current
    val total = x.achievements.size
    val earned = x.earned
    val points = x.achievements.sumOf { it.a.points }
    val earnedPoints = x.achievements.filter { it.earnedAt != null }.sumOf { it.a.points }
    val hardcore = x.achievements.count { it.hardcore }

    Card {
        Column(Modifier.padding(horizontal = RommTheme.layout.margin, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ra_progress, earned, total), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.ra_points, earnedPoints, points), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceMuted)
            }
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { if (total == 0) 0f else earned / total.toFloat() },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(PillShape),
                color = colors.warning,
                trackColor = colors.surfaceHover,
                drawStopIndicator = {},
            )
            if (hardcore > 0) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.ra_hardcore, hardcore), style = MaterialTheme.typography.bodySmall, color = colors.warning)
            }
        }
        if (x.raUsername != null) {
            SettingRow(
                title = stringResource(R.string.ra_refresh),
                subtitle = stringResource(R.string.ra_refresh_hint, x.raUsername),
                onClick = { if (!busy) vm.refreshAchievements() },
                enabled = !busy,
            )
        } else {
            Text(
                stringResource(R.string.ra_no_user),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceMuted,
                modifier = Modifier.padding(horizontal = RommTheme.layout.margin, vertical = 10.dp),
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    Card {
        x.achievements.forEach { u -> AchievementRow(context, u) }
    }
}

private val grayscale = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

@Composable
private fun AchievementRow(context: Context, u: AchievementUi) {
    val colors = RommTheme.colors
    val locked = u.earnedAt == null
    Row(
        Modifier.fillMaxWidth().padding(horizontal = RommTheme.layout.margin, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = u.badge,
            contentDescription = null,
            colorFilter = if (locked) grayscale else null,
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(colors.surfaceHover).alpha(if (locked) 0.55f else 1f),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(u.a.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = if (locked) colors.onSurfaceMuted else colors.onSurface)
            if (u.a.description.isNotBlank()) Text(u.a.description, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceMuted)
            u.earnedAt?.let { at ->
                val ms = SaveSyncEngine.parseInstant(at)?.toEpochMilli()
                val date = ms?.let { DateUtils.formatDateTime(context, it, DateUtils.FORMAT_SHOW_DATE) } ?: at.take(10)
                Text(
                    stringResource(if (u.hardcore) R.string.ra_unlocked_hardcore else R.string.ra_unlocked, date),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (u.hardcore) colors.warning else colors.success,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.ra_points_short, u.a.points), style = MaterialTheme.typography.labelLarge, color = if (locked) colors.onSurfaceMuted else colors.warning)
    }
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(RommTheme.colors.surface).padding(vertical = 4.dp),
        verticalArrangement = Arrangement.Top,
        content = content,
    )
}
