package com.rommmobile.app.feature.platforms

import com.rommmobile.app.core.design.cascadeIn
import androidx.compose.ui.graphics.compositeOver
import com.rommmobile.app.core.design.LocalBottomOverlay
import androidx.compose.foundation.border

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import com.rommmobile.app.core.design.PadBar
import com.rommmobile.app.core.input.GamepadAction
import com.rommmobile.app.feature.library.AlphabetRail
import com.rommmobile.app.feature.library.step
import com.rommmobile.app.feature.library.LetterEntry
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rommmobile.app.R
import com.rommmobile.app.core.design.bottomOverlayPadding
import com.rommmobile.app.core.design.EmptyState
import com.rommmobile.app.core.design.ErrorState
import com.rommmobile.app.core.design.OfflineBanner
import com.rommmobile.app.core.design.PlatformIcon
import com.rommmobile.app.core.design.RommTheme
import com.rommmobile.app.core.design.RommTopBar
import com.rommmobile.app.core.design.Skeleton
import com.rommmobile.app.core.design.StatusPill
import com.rommmobile.app.core.design.gamepadFocus
import com.rommmobile.app.core.design.gamepadFocusRing
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.focusGroup
import com.rommmobile.app.core.input.GridBringIntoViewSpec
import com.rommmobile.app.core.input.gridDpadNavigation
import com.rommmobile.app.core.input.gridFocusItem
import com.rommmobile.app.core.input.rememberGridFocusCoordinator
import com.rommmobile.app.core.input.rememberHasGamepad
import com.rommmobile.app.core.input.rightStickScroll
import com.rommmobile.app.core.network.ApiException
import com.rommmobile.app.core.network.ServerStore
import com.rommmobile.app.core.util.Format
import com.rommmobile.app.data.repo.LibrarySource
import com.rommmobile.app.data.repo.Platform
import com.rommmobile.app.data.repo.PlatformRepository
import com.rommmobile.app.feature.library.LibraryContent
import com.rommmobile.app.feature.library.libraryViewModel
import com.rommmobile.app.feature.main.AppViewModel
import com.rommmobile.app.feature.main.LibraryRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlatformsViewModel @Inject constructor(
    private val repo: PlatformRepository,
    serverStore: ServerStore,
) : ViewModel() {
    val platforms: StateFlow<List<Platform>?> = repo.platforms.map<List<Platform>, List<Platform>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val baseUrl: StateFlow<String?> = serverStore.config.map { it.activeUrl }.stateIn(viewModelScope, SharingStarted.Eagerly, serverStore.config.value.activeUrl)

    private val _error = MutableStateFlow<ApiException?>(null)
    val error: StateFlow<ApiException?> = _error.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    fun refresh() = viewModelScope.launch {
        _refreshing.value = true
        repo.refresh().onSuccess { _error.value = null }.onFailure { _error.value = ApiException.from(it) }
        _refreshing.value = false
    }
}

@Composable
fun PlatformsScreen(
    appVm: AppViewModel,
    onOpenLibrary: (LibraryRoute) -> Unit,
    onOpenGame: (Int) -> Unit,
    onOpenDownloads: () -> Unit,
) {
    val vm: PlatformsViewModel = hiltViewModel()
    val platforms by vm.platforms.collectAsStateWithLifecycle()
    val base by vm.baseUrl.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val offline by appVm.offline.collectAsStateWithLifecycle()
    val version by appVm.serverVersion.collectAsStateWithLifecycle()
    val remote by appVm.remoteActive.collectAsStateWithLifecycle()
    val remoteLabel = stringResource(R.string.endpoint_remote)
    val layout = RommTheme.layout
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) appVm.onForeground() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    Column(Modifier.fillMaxSize()) {
        // "remoto" next to the version is the one visible sign that requests are going out through
        // the tunnel: every cover and every page is then slow, and the fix is the network, not the app.
        RommTopBar(title = stringResource(R.string.section_platforms), subtitle = version?.let { if (remote) "RomM $it · $remoteLabel" else "RomM $it" })
        // retryOnline, not vm.refresh: only that re-runs endpoint selection, and being on the
        // wrong endpoint is exactly the state this banner describes.
        OfflineBanner(visible = offline, onRetry = { appVm.retryOnline(); vm.refresh() })
        val list = platforms
        when {
            list == null -> PlatformsSkeleton()
            list.isEmpty() && error != null -> ErrorState(error!!.userMessage(context), onRetry = { vm.refresh(); appVm.retryOnline() })
            list.isEmpty() -> EmptyState(Icons.Rounded.SportsEsports, stringResource(R.string.platforms_empty), subtitle = stringResource(R.string.platforms_empty_hint), actionLabel = stringResource(R.string.action_retry), onAction = { vm.refresh() })
            layout.twoPane -> TwoPane(list, base, onOpenGame, onOpenDownloads)
            else -> PlatformGrid(list, base) { p -> onOpenLibrary(LibraryRoute.platform(p.id, p.slug, p.name)) }
        }
    }
}

/**
 * Builds the letter bar from the list itself. There are enough consoles that scrolling to "P" for
 * PlayStation is a chore, and unlike a library this list is already fully in memory.
 */
private fun lettersOf(platforms: List<Platform>): List<LetterEntry> {
    val offsets = HashMap<String, Int>()
    platforms.forEachIndexed { i, p ->
        val c = p.name.trim().uppercase().firstOrNull() ?: return@forEachIndexed
        val label = when {
            c in 'A'..'Z' -> c.toString()
            c.isDigit() -> "#"
            else -> "@"
        }
        offsets.putIfAbsent(label, i)
    }
    return buildList {
        add(LetterEntry("#", offsets["#"]))
        for (c in 'A'..'Z') add(LetterEntry(c.toString(), offsets[c.toString()]))
        offsets["@"]?.let { add(LetterEntry("@", it)) }
    }
}

@Composable
fun PlatformGrid(platforms: List<Platform>, base: String?, onOpen: (Platform) -> Unit) {
    val layout = RommTheme.layout
    val state = rememberLazyGridState()
    val hasGamepad by rememberHasGamepad()
    val gridFocus = rememberGridFocusCoordinator(state)
    // Survives leaving for a library and coming back: without it the pad cursor was reset to the
    // first console every time, which throws away a long scroll.
    var lastFocused by rememberSaveable { mutableIntStateOf(0) }
    val letters = remember(platforms) { lettersOf(platforms) }
    // Dual screen: the other screen shows the platform under the cursor.
    val dual = com.rommmobile.app.feature.dualscreen.LocalDualScreen.current
    androidx.compose.runtime.DisposableEffect(dual) { onDispose { dual?.clearPlatform() } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val railWidth = if (letters.any { it.offset != null }) layout.alphabetRailWidth + layout.gap else 0.dp
        val columns = ((maxWidth - railWidth) / layout.platformMinCell).toInt().coerceIn(2, layout.maxColumns)
        LaunchedEffect(columns, platforms.size) {
            gridFocus.columns = columns
            gridFocus.itemCount = platforms.size
        }
        // The cursor starts at 0 on every fresh composition, so it must not be written back before
        // the saved one has been restored, or coming back from a library always lands on the first
        // console again - which is the bug this whole block exists to fix.
        var restored by remember { mutableStateOf(false) }
        LaunchedEffect(platforms.size) {
            if (platforms.isEmpty()) return@LaunchedEffect
            val target = lastFocused.coerceIn(0, platforms.lastIndex)
            kotlinx.coroutines.delay(80)
            if (hasGamepad) gridFocus.focusAt(target) else if (target > 0) state.scrollToItem(target)
            restored = true
        }
        LaunchedEffect(gridFocus.cursor) { if (restored) lastFocused = gridFocus.cursor }
        // Where the SELECTION is, not where the scroll happens to sit. At the end of the list the
        // grid cannot scroll any further, so the top row still shows the previous letter while the
        // cursor is already on the last item - which is why jumping to Z fell back to V.
        val anchor by remember(columns) {
            derivedStateOf {
                val info = state.layoutInfo.visibleItemsInfo
                val first = info.firstOrNull()?.index ?: 0
                val last = info.lastOrNull()?.index ?: first
                if (gridFocus.cursorValid && gridFocus.cursor in first..last) gridFocus.cursor
                else first + columns / 2
            }
        }
        val visibleRange by remember {
            derivedStateOf {
                val idx = state.layoutInfo.visibleItemsInfo.map { it.index }
                (idx.minOrNull() ?: 0) to (idx.maxOrNull() ?: -1)
            }
        }
        val visibleSet = remember(letters, visibleRange) { com.rommmobile.app.feature.library.visibleLetters(letters, visibleRange.first, visibleRange.second) }
        val current = remember(letters, anchor) {
            letters.filter { it.offset != null && it.offset <= anchor }.maxByOrNull { it.offset!! }?.label
        }
        val scope = rememberCoroutineScope()
        // One jump for the rail and for L1/R1 alike: the list is in memory, so nothing but the
        // scroll happens. Either way the cursor lands on the letter, so the rail can follow it.
        fun jump(entry: LetterEntry) {
            val target = entry.offset ?: return
            scope.launch {
                state.scrollToItem(target)
                if (hasGamepad) gridFocus.focusAt(target) else gridFocus.moveCursor(target)
            }
        }
        // Silent, like every letter index: the rail lights the letter reached. Held, the buttons
        // keep stepping (the bus repeats them).
        if (railWidth > 0.dp) {
            PadBar(letters) {
                silent(GamepadAction.PREV_LETTER, repeat = true) { letters.step(anchor, -1)?.let(::jump) }
                silent(GamepadAction.NEXT_LETTER, repeat = true) { letters.step(anchor, +1)?.let(::jump) }
            }
        }
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                CompositionLocalProvider(LocalBringIntoViewSpec provides GridBringIntoViewSpec) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        state = state,
                        contentPadding = PaddingValues(start = layout.margin, top = layout.padding, end = layout.margin, bottom = layout.padding + bottomOverlayPadding),
                        horizontalArrangement = Arrangement.spacedBy(layout.gap),
                        verticalArrangement = Arrangement.spacedBy(layout.gap),
                        modifier = Modifier.fillMaxSize().focusGroup().gridDpadNavigation(gridFocus).rightStickScroll(state),
                    ) {
                        itemsIndexed(platforms, key = { _, it -> it.id }) { index, p ->
                            PlatformCard(p, base, onClick = { onOpen(p) }, modifier = Modifier.gridFocusItem(index, gridFocus).cascadeIn(index, p.id), onFocused = { dual?.showPlatform(p, base) })
                        }
                    }
                }
            }
            if (railWidth > 0.dp) {
                AlphabetRail(letters = letters, current = current, onJump = ::jump, modifier = Modifier.padding(bottom = LocalBottomOverlay.current), visible = visibleSet)
            }
        }
    }
}

/**
 * Icon + gap + two lines of name + the count line + padding. Smaller on a short screen (a 16:9
 * handheld in landscape is about 430 dp high): two full rows must fit, not one and a half.
 */
private fun tileHeight(short: Boolean) = if (short) 140.dp else 176.dp
private fun tileIcon(short: Boolean) = if (short) 52.dp else 72.dp

/**
 * v2 platform tile (`v2/components/shared/Tile.vue`): elevated card with a hairline border and a
 * 14 dp radius, the console icon large in the middle, the name in semibold and the game count
 * underneath in muted text. No coloured count pill any more: the web dropped it.
 */
@Composable
fun PlatformCard(platform: Platform, base: String?, onClick: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false, onFocused: () -> Unit = {}) {
    val colors = RommTheme.colors
    val shape = MaterialTheme.shapes.large
    val short = RommTheme.layout.isShort
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier
            .gamepadFocus(shape, onFocused = { focused = it; if (it) onFocused() })
            .clip(shape)
            // Focus: the violet edge does the work; the fill only takes a hint of it.
            .background(if (focused) colors.primary.copy(alpha = 0.10f).compositeOver(colors.elevated) else colors.elevated)
            .border(if (focused) 2.dp else 1.dp, if (focused) colors.primary else colors.border, shape)
            .clickable(onClick = onClick)
            .then(
                if (compact) Modifier.padding(horizontal = 8.dp, vertical = 10.dp)
                // One height for every tile, sized for a two-line name ("Nintendo Entertainment
                // System"): rows of tiles that each grow to their own text looked uneven.
                else Modifier.height(tileHeight(short)).padding(start = 10.dp, end = 10.dp, top = if (short) 10.dp else 16.dp, bottom = if (short) 8.dp else 12.dp),
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        // Icon, name and count each on a fixed line: the name always reserves two lines, so the
        // stack has the same height on every tile and centring it moves nothing. A two-line name
        // ("Nintendo Entertainment System") no longer pushes its icon and title up.
        verticalArrangement = Arrangement.Center,
    ) {
        val icons = remember(platform.slug, platform.fsSlug, base) { platform.iconUrls(base) }
        PlatformIcon(icons, size = if (compact) 52.dp else tileIcon(short))
        Spacer(Modifier.height(if (compact || short) 8.dp else 14.dp))
        val nameStyle = MaterialTheme.typography.titleSmall
        // Room for two lines whatever the name: the first line and the count line never move.
        val twoLines = with(androidx.compose.ui.platform.LocalDensity.current) { (nameStyle.lineHeight * 2).toDp() }
        // The name is centred in its two-line slot: a one-line name sits in the middle, close to
        // the count under it, and a two-line one fills the slot. Icon slot, name slot and count
        // still stay at the same place on every tile.
        Box(Modifier.fillMaxWidth().then(if (compact) Modifier else Modifier.height(twoLines)), contentAlignment = Alignment.Center) {
            Text(
                platform.name,
                style = nameStyle,
                color = colors.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            pluralStringResource(R.plurals.games_count, platform.romCount, platform.romCount),
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceMuted,
            maxLines = 1,
        )
    }
}

@Composable
private fun PlatformsSkeleton() {
    val layout = RommTheme.layout
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = (maxWidth / layout.platformMinCell).toInt().coerceIn(2, layout.maxColumns)
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            contentPadding = PaddingValues(layout.padding),
            horizontalArrangement = Arrangement.spacedBy(layout.gap),
            verticalArrangement = Arrangement.spacedBy(layout.gap),
            userScrollEnabled = false,
        ) {
            items(12) { Skeleton(Modifier.fillMaxWidth().height(tileHeight(layout.isShort)), shape = MaterialTheme.shapes.large) }
        }
    }
}

/** Wide landscape screens: platform list on the left, library of the selected one on the right. */
@Composable
private fun TwoPane(platforms: List<Platform>, base: String?, onOpenGame: (Int) -> Unit, onOpenDownloads: () -> Unit) {
    var selectedId by rememberSaveable { mutableStateOf(platforms.firstOrNull()?.id ?: -1) }
    val selected = platforms.firstOrNull { it.id == selectedId } ?: platforms.firstOrNull()
    val colors = RommTheme.colors
    val listState = rememberLazyListState()
    Row(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.width(280.dp).fillMaxHeight().background(colors.elevated).focusRestorer().rightStickScroll(listState),
            contentPadding = PaddingValues(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 8.dp + LocalBottomOverlay.current),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(platforms, key = { it.id }) { p ->
                val isSel = p.id == selected?.id
                Row(
                    Modifier
                        .fillMaxWidth()
                        .gamepadFocusRing(MaterialTheme.shapes.medium)
                        .clip(MaterialTheme.shapes.medium)
                        .background(if (isSel) colors.surfaceHover else Color.Transparent)
                        .clickable { selectedId = p.id }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlatformIcon(p.iconUrls(base), size = 28.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(p.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(6.dp))
                    Text(p.romCount.toString(), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceMuted)
                }
            }
        }
        if (selected != null) {
            // Keyed on the platform: the pane's remembered state (search text, open flag, focus
            // bookkeeping) must not follow the user from one folder to the next.
            key(selected.id) {
                val vm = libraryViewModel(LibrarySource.ByPlatform(selected.id, selected.slug))
                var showSort by remember { mutableStateOf(false) }
                var searchOpen by rememberSaveable { mutableStateOf(false) }
                // Every platform keeps its own ViewModel for as long as Home lives, term included.
                // Clicking another platform mid-search would otherwise leave this one silently
                // filtered, with the field gone, the next time it is shown.
                DisposableEffect(vm) { onDispose { vm.setSearchTerm("") } }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Text(selected.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                    // Pad-only here, like sort and view in this pane: Select opens it.
                    LibraryContent(
                        vm = vm, modifier = Modifier.weight(1f), showPlatform = false, onOpenGame = onOpenGame, onOpenDownloads = onOpenDownloads,
                        showSort = showSort, onShowSort = { showSort = it }, handleDownloadsShortcut = false,
                        searchPlaceholder = stringResource(R.string.search_in_placeholder, selected.name), searchOpen = searchOpen, onSearchOpen = { searchOpen = it },
                    )
                }
            }
        }
    }
}
