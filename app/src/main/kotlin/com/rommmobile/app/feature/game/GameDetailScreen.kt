package com.rommmobile.app.feature.game

import com.rommmobile.app.core.design.dpadScroll
import com.rommmobile.app.core.design.LocalNavBarInset
import androidx.compose.ui.graphics.graphicsLayer
import com.rommmobile.app.core.input.rememberHasGamepad
import com.rommmobile.app.core.design.KeycapGlyph
import com.rommmobile.app.core.design.FaceButtonsGlyph
import com.rommmobile.app.core.design.PadButton
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.rommmobile.app.core.design.ArtShapeLarge
import com.rommmobile.app.core.design.SetBackdrop
import com.rommmobile.app.feature.dualscreen.LocalDualScreen
import com.rommmobile.app.core.design.CoverRatios
import com.rommmobile.app.core.design.DialogPadRemap
import com.rommmobile.app.core.design.PlatformIcon
import com.rommmobile.app.core.design.gamepadFocus
import com.rommmobile.app.core.input.ModalScope
import com.rommmobile.app.core.network.ServerStore
import com.rommmobile.app.core.network.UrlNormalizer
import com.rommmobile.app.core.util.Flags
import com.rommmobile.app.feature.library.COVER_RATIO
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.rommmobile.app.R
import com.rommmobile.app.core.design.Badge
import com.rommmobile.app.core.design.CoverImage
import com.rommmobile.app.core.design.ErrorState
import com.rommmobile.app.core.design.PadBar
import com.rommmobile.app.core.design.PillShape
import com.rommmobile.app.core.design.RommDialog
import com.rommmobile.app.core.design.RommTheme
import com.rommmobile.app.core.design.RommTopBar
import com.rommmobile.app.core.design.SectionHeader
import com.rommmobile.app.core.design.Skeleton
import com.rommmobile.app.core.design.StatusPill
import com.rommmobile.app.core.design.Toaster
import com.rommmobile.app.core.design.gamepadFocusRing
import com.rommmobile.app.core.input.GamepadAction
import com.rommmobile.app.core.input.rightStickScroll
import com.rommmobile.app.core.network.ApiException
import com.rommmobile.app.core.util.Format
import com.rommmobile.app.data.db.DownloadState
import com.rommmobile.app.data.repo.DownloadRepository
import com.rommmobile.app.data.repo.LocalLibraryRepository
import com.rommmobile.app.data.repo.Rom
import com.rommmobile.app.core.design.gamepadFocusRow
import com.rommmobile.app.data.repo.RomFile
import com.rommmobile.app.data.repo.RomRepository
import com.rommmobile.app.feature.downloads.DownloadMiniBar
import com.rommmobile.app.feature.downloads.EnqueueController
import com.rommmobile.app.feature.downloads.FolderDialog
import com.rommmobile.app.feature.downloads.RedownloadDialog
import com.rommmobile.app.feature.downloads.engine.DownloadProgress
import com.rommmobile.app.feature.main.GameRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface GameUiState {
    data object Loading : GameUiState
    data class Error(val error: ApiException) : GameUiState
    data class Ready(val rom: Rom) : GameUiState
}

@HiltViewModel
class GameViewModel @Inject constructor(
    handle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val roms: RomRepository,
    private val local: LocalLibraryRepository,
    private val toaster: Toaster,
    downloads: DownloadRepository,
    val enqueue: EnqueueController,
    private val serverStore: ServerStore,
) : ViewModel() {
    private val romId = handle.toRoute<GameRoute>().id

    private val _state = MutableStateFlow<GameUiState>(GameUiState.Loading)
    val state: StateFlow<GameUiState> = _state.asStateFlow()

    val present: StateFlow<Boolean> = _state.flatMapLatest { s ->
        val rom = (s as? GameUiState.Ready)?.rom
        if (rom == null) MutableStateFlow(false) else local.presenceKeys(rom.platformSlug).map { rom.nameKey in it }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val progress: StateFlow<DownloadProgress?> = combine(downloads.observeOpenForRom(romId), downloads.progress) { open, prog ->
        open.firstOrNull()?.let { e -> prog[e.id] ?: DownloadProgress(e.id, e.downloadedBytes, e.totalBytes, 0, e.state) }
    }.sample(250).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init { load() }

    /** Same order as the web app: slug, then RomM's generic icon. */
    fun platformIconUrls(slug: String): List<String> = serverStore.config.value.activeUrl?.let { b ->
        listOf(slug.lowercase(), "default").distinct().map { UrlNormalizer.join(b, "assets/platforms/$it.ico") }
    } ?: emptyList()

    fun load() = viewModelScope.launch {
        // Show the cached record instantly, then replace it with the fresh one.
        roms.cached(romId)?.let { _state.value = GameUiState.Ready(it) }
        roms.detail(romId).onSuccess { _state.value = GameUiState.Ready(it) }.onFailure { e ->
            if (_state.value !is GameUiState.Ready) _state.value = GameUiState.Error(ApiException.from(e))
        }
    }

    fun download(force: Boolean = false) = viewModelScope.launch {
        (state.value as? GameUiState.Ready)?.rom?.let { enqueue.enqueue(it, force) }
    }

    /** Removes the game from this device only. The copy on RomM is never touched. */
    fun uninstall() = viewModelScope.launch {
        val rom = (state.value as? GameUiState.Ready)?.rom ?: return@launch
        val gone = local.deleteGame(rom.platformSlug, rom.nameKey)
        toaster.show(
            if (gone > 0) context.getString(R.string.uninstall_done) else context.getString(R.string.uninstall_failed),
            isError = gone == 0,
        )
    }
}

@Composable
fun GameDetailScreen(romId: Int, onBack: () -> Unit, onOpenGame: (Int) -> Unit, onOpenDownloads: () -> Unit) {
    val vm: GameViewModel = hiltViewModel()
    var confirmUninstall by remember { mutableStateOf<Rom?>(null) }
    val state by vm.state.collectAsStateWithLifecycle()
    val present by vm.present.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val pending by vm.enqueue.pending.collectAsStateWithLifecycle()
    val redownload by vm.enqueue.confirmRedownload.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val layout = RommTheme.layout
    val ready = (state as? GameUiState.Ready)?.rom
    // Tabs live here so L1/R1 can flip them from anywhere on the page, as on the web.
    var tab by rememberSaveable(romId) { mutableIntStateOf(0) }
    // Saves joins the tabs once the save sync is on (Settings > Save sync).
    val syncOn = com.rommmobile.app.feature.sync.rememberGameSyncOn(romId)
    // Achievements joins them when the game has a RetroAchievements set.
    val hasAchievements = rememberAchievementCount(romId) != null
    val tabs = remember(ready?.screenshots, ready?.files, syncOn, hasAchievements) {
        ready?.let { detailTabs(it, syncOn, hasAchievements) } ?: listOf(DetailTab.OVERVIEW)
    }
    val tabIndex = tab.coerceIn(0, tabs.lastIndex)

    // v2: the game page sits on its own cover, blurred and faded into the background.
    SetBackdrop(ready?.let { it.coverSmall ?: it.coverLarge })
    val dual = LocalDualScreen.current
    LaunchedEffect(ready) { dual?.show(ready) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            RommTopBar(title = ready?.platformName ?: "", onBack = onBack)
            Box(Modifier.weight(1f)) {
                when (val s = state) {
                    GameUiState.Loading -> DetailSkeleton()
                    is GameUiState.Error -> ErrorState(s.error.userMessage(context), onRetry = { vm.load() })
                    is GameUiState.Ready -> DetailBody(
                        rom = s.rom,
                        present = present,
                        progress = progress,
                        horizontal = layout.isShort || layout.widthDp >= 600,
                        platformIcons = remember(s.rom.platformSlug) { vm.platformIconUrls(s.rom.platformSlug) },
                        tabs = tabs,
                        tab = tabIndex,
                        onTab = { tab = it },
                        onDownload = { vm.download() },
                        onDownloadAgain = { vm.download(force = true) },
                        onUninstall = { confirmUninstall = s.rom },
                        onOpenGame = onOpenGame,
                        onDownloadFile = { f, label -> vm.viewModelScope.launch { vm.enqueue.enqueueFile(s.rom, f, label) } },
                    )
                }
                // The text scrolls on under the system bar: a fade there, so it dims away instead of
                // being cut by the bar's edge. It belongs to the page, not to the whole screen, so
                // the download bar below it stays on top, readable.
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(LocalNavBarInset.current + 64.dp)
                        .background(com.rommmobile.app.core.design.softFadeBrush(RommTheme.colors.background, RommTheme.colors.isDark)),
                )
            }
            DownloadMiniBar(onClick = onOpenDownloads, modifier = Modifier.padding(bottom = LocalNavBarInset.current))
            // Hint bar: X downloads, LB/RB flip the tabs. The action button under the title holds the focus and says what
            // A does. R3 still has to be handled, because MainScaffold is not resumed behind this
            // destination; L1/R1 step through the tabs, silently, like every letter index.
            PadBar(tabs.size, ready?.id, present) {
                silent(GamepadAction.OPEN_DOWNLOADS) { onOpenDownloads() }
                // X downloads from anywhere on the page, no need to walk the cursor to the button.
                // Already on the device, it asks before downloading again (the usual dialog).
                // Both shown where they act (X inside the download button, LB / RB around the
                // tabs), so they stay out of the hint bar.
                if (ready != null) {
                    silent(GamepadAction.DOWNLOAD) { vm.download(force = false) }
                }
                if (tabs.size > 1) {
                    silent(GamepadAction.PREV_LETTER) { tab = (tabIndex - 1).coerceAtLeast(0) }
                    silent(GamepadAction.NEXT_LETTER) { tab = (tabIndex + 1).coerceAtMost(tabs.lastIndex) }
                }
            }
        }
    }

    pending?.let { p ->
        FolderDialog(
            pending = p,
            onCreate = { name -> vm.viewModelScope.launch { vm.enqueue.confirmCreate(name) } },
            onChoose = { dir -> vm.viewModelScope.launch { vm.enqueue.chooseCandidate(dir) } },
            onDismiss = vm.enqueue::dismiss,
        )
    }
    redownload?.let { rom ->
        RedownloadDialog(rom = rom, onConfirm = { vm.viewModelScope.launch { vm.enqueue.confirmRedownload() } }, onDismiss = vm.enqueue::dismissRedownload)
    }
    confirmUninstall?.let { rom ->
        UninstallDialog(rom = rom, onConfirm = { confirmUninstall = null; vm.uninstall() }, onDismiss = { confirmUninstall = null })
    }
}

/** Deleting a file cannot be undone, so it is always confirmed, and the wording says what survives. */
@Composable
fun UninstallDialog(rom: Rom, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    RommDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.uninstall_title),
        confirmText = stringResource(R.string.action_uninstall),
        onConfirm = onConfirm,
        destructive = true,
    ) {
        Text(stringResource(R.string.uninstall_body, rom.name), style = MaterialTheme.typography.bodyMedium)
    }
}

enum class DetailTab(val labelRes: Int) { OVERVIEW(R.string.tab_overview), MEDIA(R.string.tab_media), FILES(R.string.tab_files), ACHIEVEMENTS(R.string.tab_achievements), SAVES(R.string.tab_saves) }

/**
 * Overview always; Media and Files only when there is something to put in them; Achievements when
 * the game has a RetroAchievements set; Save data while the sync is on. Same order as the web.
 */
fun detailTabs(rom: Rom, saves: Boolean = false, achievements: Boolean = false): List<DetailTab> = buildList {
    add(DetailTab.OVERVIEW)
    if (rom.screenshots.isNotEmpty()) add(DetailTab.MEDIA)
    if (rom.files.size > 1 || rom.extraFiles.isNotEmpty()) add(DetailTab.FILES)
    if (achievements) add(DetailTab.ACHIEVEMENTS)
    if (saves) add(DetailTab.SAVES)
}

/**
 * The v2 game page (`v2/views/GameDetails.vue`): cover column on the left with a deep shadow, then
 * a heavy title, a meta line, tags, the actions and underlined tabs. On a portrait phone the cover
 * is centred on top instead.
 */
@Composable
private fun DetailBody(
    rom: Rom,
    present: Boolean,
    progress: DownloadProgress?,
    horizontal: Boolean,
    platformIcons: List<String>,
    tabs: List<DetailTab>,
    tab: Int,
    onTab: (Int) -> Unit,
    onDownload: () -> Unit,
    onDownloadAgain: () -> Unit,
    onUninstall: () -> Unit,
    onOpenGame: (Int) -> Unit,
    onDownloadFile: (RomFile, String) -> Unit,
) {
    val layout = RommTheme.layout
    val scroll = rememberScrollState()
    var viewer by remember { mutableStateOf<Int?>(null) }
    // Shared by the Sync button and the Saves tab, which hands the focus back to it.
    val syncFocus = remember { FocusRequester() }
    val covers = remember(rom.id, rom.coverLarge, rom.coverSmall) { rom.coverCandidates(preferLarge = true) }
    var ratio by remember(rom.id) { mutableFloatStateOf(CoverRatios.firstKnown(covers) ?: COVER_RATIO) }
    val cover: @Composable (Modifier) -> Unit = { m ->
        // The picture's own shape (a GBA box is nearly square), capped so a strip of art cannot
        // push the page around.
        // Entrance: the box swings in from a 3D tilt and settles, like a case pulled off a shelf.
        val enter = remember(rom.id) { androidx.compose.animation.core.Animatable(0f) }
        LaunchedEffect(rom.id) {
            enter.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.62f, stiffness = 140f))
        }
        Box(
            m.aspectRatio(ratio.coerceIn(0.5f, 1.5f))
                .graphicsLayer {
                    val v = enter.value
                    rotationY = (1f - v) * -38f
                    rotationX = (1f - v) * 8f
                    cameraDistance = 14f * density
                    alpha = v.coerceIn(0f, 1f)
                    val s = 0.92f + 0.08f * v
                    scaleX = s; scaleY = s
                }
                .shadow(22.dp, ArtShapeLarge, ambientColor = Color.Black, spotColor = Color.Black),
        ) {
            CoverImage(covers, rom.name, Modifier.fillMaxSize(), shape = ArtShapeLarge, onRatio = { ratio = it })
        }
    }
    val body: @Composable ColumnScope.() -> Unit = {
        DetailHeader(rom, platformIcons)
        Spacer(Modifier.height(14.dp))
        ActionRow(rom, present, progress, onDownload, onDownloadAgain, onUninstall) { sameHeight ->
            // Save sync, beside the download action (only once the sync is on in Settings).
            com.rommmobile.app.feature.sync.GameSyncButton(rom.id, modifier = sameHeight, focus = syncFocus)
            // Favourite and collections, as the web's heart and "manage collections".
            GameCollectionButtons(rom.id, sameHeight)
        }
        Spacer(Modifier.height(18.dp))
        if (tabs.size > 1) {
            // Counts beside the names, as on the web: "Achievements 12/197", "Save data 2".
            val saveCount = if (DetailTab.SAVES in tabs) com.rommmobile.app.feature.sync.rememberSaveDataCount(rom.id) else null
            val ach = if (DetailTab.ACHIEVEMENTS in tabs) rememberAchievementCount(rom.id) else null
            val badges = buildMap {
                saveCount?.let { put(DetailTab.SAVES, it.toString()) }
                ach?.let { (e, t) -> put(DetailTab.ACHIEVEMENTS, "$e/$t") }
            }
            DetailTabs(tabs, tab, onTab, badges = badges)
            Spacer(Modifier.height(14.dp))
        }
        when (tabs.getOrNull(tab) ?: DetailTab.OVERVIEW) {
            DetailTab.OVERVIEW -> OverviewTab(rom, onOpenGame)
            DetailTab.MEDIA -> MediaTab(rom.screenshots, onOpen = { viewer = it })
            DetailTab.FILES -> FilesTab(rom, onDownloadFile)
            DetailTab.ACHIEVEMENTS -> AchievementsTab(rom.id)
            DetailTab.SAVES -> com.rommmobile.app.feature.sync.GameSavesTab(rom.id, returnFocus = syncFocus)
        }
        Spacer(Modifier.height(28.dp))
    }
    if (horizontal) {
        Row(Modifier.fillMaxSize().padding(horizontal = layout.margin, vertical = layout.padding)) {
            Column(Modifier.width(if (layout.isShort) 150.dp else 220.dp).padding(top = 6.dp)) { cover(Modifier.fillMaxWidth()) }
            Spacer(Modifier.width(if (layout.isShort) 20.dp else 32.dp))
            Column(Modifier.weight(1f).fillMaxHeight().dpadScroll(scroll).verticalScroll(scroll).rightStickScroll(scroll).padding(top = 4.dp, bottom = LocalNavBarInset.current + 24.dp), content = body)
        }
    } else {
        Column(Modifier.fillMaxSize().dpadScroll(scroll).verticalScroll(scroll).rightStickScroll(scroll).padding(layout.padding).padding(bottom = LocalNavBarInset.current + 24.dp)) {
            Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                cover(Modifier.fillMaxWidth(0.5f).widthIn(min = 160.dp, max = 260.dp))
            }
            Spacer(Modifier.height(20.dp))
            body()
        }
    }
    viewer?.let { start -> ScreenshotViewer(rom.screenshots, start, onDismiss = { viewer = null }) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailHeader(rom: Rom, platformIcons: List<String>) {
    val colors = RommTheme.colors
    Text(
        rom.name,
        style = MaterialTheme.typography.headlineMedium.copy(shadow = Shadow(Color(0x80000000), Offset(0f, 2f), 20f)),
        color = colors.onBackground,
    )
    Spacer(Modifier.height(8.dp))
    // Meta line: platform, year, size, rating, dots at 30% between them. With the second screen
    // showing this game, platform, year and rating are already there in pills: only the size
    // (which only this screen has) stays.
    val dual = LocalDualScreen.current
    val companionOn by (dual?.activeState ?: remember { kotlinx.coroutines.flow.MutableStateFlow(false) }).collectAsStateWithLifecycle()
    val dot = colors.onSurfaceSecondary.copy(alpha = 0.3f)
    val parts = buildList {
        if (!companionOn) add(rom.platformName)
        if (!companionOn) Format.year(rom.firstReleaseDate)?.let { add(it) }
        if (rom.sizeBytes > 0) add(Format.bytes(rom.sizeBytes))
    }
    val rating = if (companionOn) null else rom.rating?.takeIf { it > 0 }?.let { "%.1f".format(if (it > 10) it / 10.0 else it) }
    if (parts.isNotEmpty() || rating != null) Row(verticalAlignment = Alignment.CenterVertically) {
        if (!companionOn) {
            PlatformIcon(platformIcons, size = 16.dp)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            buildAnnotatedString {
                parts.forEachIndexed { i, p ->
                    if (i > 0) withStyle(SpanStyle(color = dot)) { append("  \u00B7  ") }
                    append(p)
                }
                if (rating != null) {
                    withStyle(SpanStyle(color = dot)) { append("  \u00B7  ") }
                    withStyle(SpanStyle(color = colors.gold)) { append("\u2605 $rating") }
                }
            },
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
    // Tags: regions in the info tone, languages in the brand tone, flags first.
    val regions = rom.regions.filter { it.isNotBlank() }
    val languages = rom.languages.filter { it.isNotBlank() }
    if (regions.isNotEmpty() || languages.isNotEmpty() || rom.hasMultipleFiles || rom.missingFromFs || rom.extraFiles.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            regions.forEach { r -> Tag(listOfNotNull(Flags.of(r), r).joinToString(" "), tone = colors.info) }
            languages.forEach { l -> Tag(listOfNotNull(Flags.of(l), l).joinToString(" "), tone = colors.primaryLighten) }
            if (rom.hasMultipleFiles) Tag(stringResource(R.string.detail_files, rom.files.size), tone = colors.accent)
            rom.extraFiles.groupBy { it.category }.forEach { (cat, list) ->
                Tag((if (list.size > 1) "${list.size} \u00D7 " else "") + categoryLabel(cat), tone = if (cat == "update") colors.info else colors.accent)
            }
            if (rom.missingFromFs) Tag(stringResource(R.string.detail_missing), tone = colors.danger)
        }
    }
}

@Composable
private fun Tag(text: String, tone: Color? = null) {
    val colors = RommTheme.colors
    val shape = RoundedCornerShape(6.dp)
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = tone?.copy(alpha = 0.9f) ?: colors.onSurfaceSecondary,
        maxLines = 1,
        modifier = Modifier
            .background(tone?.copy(alpha = 0.12f) ?: colors.surface, shape)
            .border(1.dp, tone?.copy(alpha = 0.4f) ?: colors.borderStrong, shape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** v2 pill button: label and icon, 1 dp edge, focus ring from the app's own rule. */
@Composable
private fun PillButton(
    text: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
    border: Color? = null,
    /** Pad button that triggers this from anywhere on the page, drawn inside the button. */
    padButton: PadButton? = null,
) {
    val hasPad by rememberHasGamepad()
    Button(
        onClick = onClick,
        shape = PillShape,
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
        border = border?.let { BorderStroke(1.dp, it) },
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
        modifier = modifier.gamepadFocusRing(PillShape),
    ) {
        if (icon != null) {
            Icon(icon, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(7.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
        if (padButton != null && hasPad) {
            Spacer(Modifier.width(10.dp))
            FaceButtonsGlyph(padButton, size = 18.dp, tint = content)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionRow(
    rom: Rom,
    present: Boolean,
    progress: DownloadProgress?,
    onDownload: () -> Unit,
    onDownloadAgain: () -> Unit,
    onUninstall: () -> Unit,
    /** More actions after the download ones (the save sync button), given the main action's height. */
    extra: @Composable (Modifier) -> Unit = {},
) {
    val colors = RommTheme.colors
    // Every button of the row takes the height of the main action, whatever it holds (pad glyph,
    // progress ring), so the pills line up.
    var mainHeight by remember { mutableIntStateOf(0) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val sameHeight = if (mainHeight > 0) Modifier.height(with(density) { mainHeight.toDp() }) else Modifier
    val downloadFr = remember { FocusRequester() }
    // requestFocus() reports nothing when it fails, and in touch mode it is a no-op by design, so
    // keep asking for a few frames and stop as soon as the button itself says it has focus. Landing
    // here with the pad puts the action under the cursor; landing here with a finger does not, and
    // should not.
    var actionFocused by remember { mutableStateOf(false) }
    LaunchedEffect(rom.id) {
        repeat(10) {
            if (actionFocused) return@LaunchedEffect
            runCatching { downloadFr.requestFocus() }
            kotlinx.coroutines.delay(60)
        }
    }
    // The web's "Play" pill is white on dark; on the light theme white on white would vanish.
    val emphasis = if (colors.isDark) colors.emphasis else colors.primary
    val onEmphasis = if (colors.isDark) colors.onEmphasis else Color.White
    val primaryMod = Modifier.focusRequester(downloadFr).onFocusChanged { actionFocused = it.isFocused }
        .onSizeChanged { mainHeight = it.height }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            progress != null && !progress.state.isTerminal -> {
                val label = when (progress.state) {
                    DownloadState.PENDING -> stringResource(R.string.state_pending)
                    DownloadState.PAUSED -> stringResource(R.string.state_paused)
                    DownloadState.DOWNLOADING -> stringResource(R.string.state_downloading_pct, progress.percent)
                    DownloadState.VERIFYING -> stringResource(R.string.state_verifying)
                    DownloadState.EXTRACTING -> stringResource(R.string.state_extracting)
                    DownloadState.MOVING -> stringResource(R.string.state_moving)
                    else -> progress.state.name
                }
                Row(
                    Modifier.background(colors.blue.copy(alpha = 0.16f), PillShape).border(1.dp, colors.blue.copy(alpha = 0.5f), PillShape)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (progress.state == DownloadState.DOWNLOADING) {
                        CircularProgressIndicator(progress = { progress.percent / 100f }, modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.info, trackColor = colors.border)
                    } else {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.info)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(label, style = MaterialTheme.typography.labelLarge, color = colors.info)
                }
            }
            present -> {
                // The game is already here, so the useful action is getting rid of it. Downloading
                // it a second time stays reachable but reads as secondary.
                PillButton(
                    stringResource(R.string.action_uninstall), Icons.Rounded.DeleteOutline, onUninstall,
                    container = colors.red, content = Color.White, modifier = primaryMod,
                )
                PillButton(
                    stringResource(R.string.action_download_again), Icons.Rounded.Refresh, onDownloadAgain,
                    container = colors.surface, content = colors.onSurface, border = colors.borderStrong,
                    padButton = PadButton.X,
                )
                // No "On device" pill: an Uninstall button already says the game is here.
            }
            else -> PillButton(
                stringResource(R.string.action_download), Icons.Rounded.Download, onDownload,
                container = emphasis, content = onEmphasis, modifier = primaryMod,
                padButton = PadButton.X,
            )
        }
        extra(sameHeight)
    }
}

/** Underlined tabs: 1 dp baseline, 2 dp brand indicator under the selected one. */
@Composable
private fun DetailTabs(tabs: List<DetailTab>, selected: Int, onSelect: (Int) -> Unit, badges: Map<DetailTab, String> = emptyMap()) {
    val colors = RommTheme.colors
    // Walking down the page crosses this row: only the open tab takes the focus from outside, so a
    // tab is never switched just by passing through (focusing a tab opens it). Inside the row,
    // left / right reach the others.
    var inRow by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind { drawLine(colors.borderStrong, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f) }
            .onFocusChanged { inRow = it.hasFocus }
            .focusGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // LB / RB flip the tabs: shown right where they act, framing the row, pad only.
        val hasPad by rememberHasGamepad()
        if (hasPad) {
            KeycapGlyph("LB", Modifier.padding(end = 6.dp))
        }
        tabs.forEachIndexed { i, t ->
            val sel = i == selected
            val shape = RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)
            Box(
                Modifier
                    .focusProperties { canFocus = sel || inRow }
                    .gamepadFocus(shape, scale = 1f, onFocused = { if (it) onSelect(i) })
                    .clip(shape)
                    .clickable { onSelect(i) }
                    .drawBehind {
                        if (sel) {
                            val h = 2.dp.toPx()
                            drawRect(colors.primary, topLeft = Offset(0f, size.height - h), size = Size(size.width, h))
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(t.labelRes),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (sel) colors.onSurface else colors.onSurfaceMuted,
                    )
                    badges[t]?.let { n ->
                        Spacer(Modifier.width(8.dp))
                        com.rommmobile.app.feature.sync.CountBadge(n, highlighted = sel)
                    }
                }
            }
        }
        if (hasPad) {
            KeycapGlyph("RB", Modifier.padding(start = 6.dp))
        }
    }
}

@Composable
private fun OverviewTab(rom: Rom, onOpenGame: (Int) -> Unit) {
    val colors = RommTheme.colors
    if (!rom.summary.isNullOrBlank()) {
        Text(rom.summary, style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp), color = colors.onSurfaceSecondary)
    } else {
        Text(stringResource(R.string.detail_no_summary), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceFaint)
    }
    Spacer(Modifier.height(16.dp))
    val rows = buildList {
        Format.year(rom.firstReleaseDate)?.let { add(R.string.detail_release to it) }
        if (rom.genres.isNotEmpty()) add(R.string.detail_genres to rom.genres.joinToString(", "))
        if (rom.companies.isNotEmpty()) add(R.string.detail_companies to rom.companies.joinToString(", "))
        add(R.string.detail_file to rom.fsName)
    }
    val shape = MaterialTheme.shapes.large
    Column(
        Modifier.fillMaxWidth().clip(shape).background(colors.elevated).border(1.dp, colors.border, shape).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        rows.forEach { (label, value) ->
            Row {
                Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceMuted, modifier = Modifier.width(110.dp))
                Text(value, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceSecondary, modifier = Modifier.weight(1f))
            }
        }
        if (rom.hasMultipleFiles) {
            Text(stringResource(R.string.detail_multi_file, rom.files.size), style = MaterialTheme.typography.bodySmall, color = colors.accent)
        }
    }

    if (rom.siblings.isNotEmpty()) {
        Spacer(Modifier.height(18.dp))
        Text(stringResource(R.string.detail_versions, rom.siblings.size + 1), style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
        Spacer(Modifier.height(6.dp))
        rom.siblings.forEach { sib ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
                    .gamepadFocusRing(MaterialTheme.shapes.medium)
                    .clip(MaterialTheme.shapes.medium)
                    .background(colors.surface)
                    .border(1.dp, colors.border, MaterialTheme.shapes.medium)
                    .clickable { onOpenGame(sib.id) }
                    .padding(vertical = 9.dp, horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(sib.fsNameNoTags ?: sib.name ?: "#${sib.id}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (sib.isMain) Icon(Icons.Rounded.Check, null, tint = colors.primaryLighten, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun MediaTab(screenshots: List<String>, onOpen: (Int) -> Unit) {
    val colors = RommTheme.colors
    val layout = RommTheme.layout
    if (screenshots.isEmpty()) {
        Text(stringResource(R.string.detail_no_media), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceFaint)
        return
    }
    val state = rememberLazyListState()
    val w = if (layout.isShort) 200.dp else 260.dp
    LazyRow(
        state = state,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 6.dp, horizontal = 2.dp),
        modifier = Modifier.fillMaxWidth().focusGroup(),
    ) {
        itemsIndexed(screenshots) { i, url ->
            val shape = MaterialTheme.shapes.medium
            Box(
                Modifier
                    .width(w)
                    .aspectRatio(4f / 3f)
                    .gamepadFocus(shape)
                    .shadow(6.dp, shape)
                    .clip(shape)
                    .clickable { onOpen(i) },
            ) {
                CoverImage(listOf(url), stringResource(R.string.detail_screenshot, i + 1, screenshots.size), Modifier.fillMaxSize(), shape = shape, showTitleWhenMissing = false)
            }
        }
    }
}

/** Label for a RomM file category (`dlc`, `update`, `mod`...), in the device language. */
@Composable
fun categoryLabel(category: String?): String = when (category?.lowercase()) {
    null, "", "game" -> stringResource(R.string.cat_game)
    "dlc" -> stringResource(R.string.cat_dlc)
    "update" -> stringResource(R.string.cat_update)
    "mod" -> stringResource(R.string.cat_mod)
    "patch" -> stringResource(R.string.cat_patch)
    "hack" -> stringResource(R.string.cat_hack)
    "translation" -> stringResource(R.string.cat_translation)
    "demo" -> stringResource(R.string.cat_demo)
    "prototype" -> stringResource(R.string.cat_prototype)
    "manual" -> stringResource(R.string.cat_manual)
    "cheat" -> stringResource(R.string.cat_cheat)
    else -> category.replaceFirstChar { it.uppercase() }
}

/**
 * Every file RomM knows for the game, the game first, then its extras grouped by category. Each
 * row downloads on its own: a DLC or an update can be fetched without the 30 GB game again, and
 * lands in the game's folder under the category name, as RomM lays it out.
 */
@Composable
private fun FilesTab(rom: Rom, onDownloadFile: (RomFile, String) -> Unit) {
    val colors = RommTheme.colors
    val shape = MaterialTheme.shapes.large
    val ordered = remember(rom.files) {
        rom.files.sortedWith(compareBy<RomFile>({ if (it.category == null || it.category == Rom.GAME) 0 else 1 }, { it.category ?: "" }, { it.fileName }))
    }
    Column(Modifier.fillMaxWidth().clip(shape).background(colors.elevated).border(1.dp, colors.border, shape).padding(vertical = 4.dp)) {
        ordered.take(80).forEachIndexed { i, f ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
            val label = categoryLabel(f.category)
            val isGame = f.category == null || f.category == Rom.GAME
            val tone = when (f.category?.lowercase()) {
                "dlc" -> colors.accent
                "update" -> colors.info
                "mod", "hack", "patch", "translation" -> colors.primaryLighten
                else -> null
            }
            Row(
                Modifier.fillMaxWidth()
                    .gamepadFocusRow(MaterialTheme.shapes.small)
                    .clickable { onDownloadFile(f, label) }
                    .padding(vertical = 8.dp, horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Tag(label, tone = tone)
                Spacer(Modifier.width(10.dp))
                Text(f.fileName, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceSecondary, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(8.dp))
                Text(Format.bytes(f.sizeBytes), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceMuted)
                Spacer(Modifier.width(10.dp))
                Icon(
                    Icons.Rounded.Download,
                    contentDescription = stringResource(R.string.action_download_file, f.fileName),
                    tint = if (isGame) colors.onSurfaceMuted else colors.primaryLighten,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (ordered.size > 80) Text("\u2026", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceMuted, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
    }
}

/** Full-screen screenshot pager. D-pad left/right flips, B or a tap outside closes. */
@Composable
private fun ScreenshotViewer(urls: List<String>, start: Int, onDismiss: () -> Unit) {
    ModalScope()
    val pager = rememberPagerState(initialPage = start.coerceIn(0, urls.lastIndex)) { urls.size }
    val scope = rememberCoroutineScope()
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { repeat(6) { runCatching { fr.requestFocus() }; kotlinx.coroutines.delay(40) } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        DialogPadRemap(onBack = onDismiss)
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .focusRequester(fr)
                .focusable()
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionLeft, Key.ButtonL1 -> { scope.launch { pager.animateScrollToPage((pager.currentPage - 1).coerceAtLeast(0)) }; true }
                        Key.DirectionRight, Key.ButtonR1 -> { scope.launch { pager.animateScrollToPage((pager.currentPage + 1).coerceAtMost(urls.lastIndex)) }; true }
                        else -> false
                    }
                },
        ) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                AsyncImage(
                    model = urls[page],
                    contentDescription = stringResource(R.string.detail_screenshot, page + 1, urls.size),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().clickable(onClick = onDismiss),
                )
            }
            Text(
                "${pager.currentPage + 1} / ${urls.size}",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
                    .background(Color(0x8C000000), PillShape).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun DetailSkeleton() {
    val layout = RommTheme.layout
    Row(Modifier.fillMaxSize().padding(horizontal = layout.margin, vertical = layout.padding)) {
        Skeleton(Modifier.width(150.dp).aspectRatio(COVER_RATIO), ArtShapeLarge)
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Skeleton(Modifier.fillMaxWidth(0.7f).height(28.dp), MaterialTheme.shapes.small)
            Spacer(Modifier.height(10.dp))
            Skeleton(Modifier.fillMaxWidth(0.4f).height(14.dp), MaterialTheme.shapes.small)
            Spacer(Modifier.height(18.dp))
            Skeleton(Modifier.width(150.dp).height(40.dp), PillShape)
            Spacer(Modifier.height(18.dp))
            repeat(4) { Skeleton(Modifier.fillMaxWidth().height(12.dp), MaterialTheme.shapes.small); Spacer(Modifier.height(8.dp)) }
        }
    }
}
