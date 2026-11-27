package com.rommmobile.app.feature.main

import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.SportsEsports
import com.rommmobile.app.core.design.LocalNavBarInset
import com.rommmobile.app.core.design.LocalBottomOverlay
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.activity.compose.BackHandler
import com.rommmobile.app.R
import com.rommmobile.app.core.design.NavMode
import com.rommmobile.app.core.design.PadBar
import com.rommmobile.app.core.design.RommTheme
import com.rommmobile.app.core.design.StatusPill
import com.rommmobile.app.core.design.gamepadFocus
import com.rommmobile.app.core.input.GamepadAction
import com.rommmobile.app.feature.collections.CollectionsScreen
import com.rommmobile.app.feature.downloads.DownloadMiniBar
import com.rommmobile.app.feature.platforms.LocalPlatformsScreen
import com.rommmobile.app.feature.platforms.PlatformsScreen
import com.rommmobile.app.feature.search.SearchScreen

/**
 * Icons follow the RomM v2 nav (`useNavDestinations`): mdi-controller, mdi-bookmark-outline,
 * mdi-magnify. "Installed" has no web counterpart: an outlined SD card, same family.
 */
private enum class Section(val icon: ImageVector, val labelRes: Int) {
    PLATFORMS(Icons.Outlined.SportsEsports, R.string.section_platforms),
    COLLECTIONS(Icons.Outlined.BookmarkBorder, R.string.section_collections),
    LOCAL(Icons.Outlined.SdCard, R.string.section_local),
    SEARCH(Icons.Outlined.Search, R.string.section_search),
}

/**
 * Shell for the three sections. Rail on the left (D-pad side) for short / wide screens,
 * bottom bar only on tall compact phones. L2/R2 cycle sections, R3 opens the queue; L1/R1 are
 * left to the letter index of whatever list is showing.
 */
@Composable
fun MainScaffold(
    appVm: AppViewModel,
    onOpenLibrary: (LibraryRoute) -> Unit,
    onOpenGame: (Int) -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val layout = RommTheme.layout
    val colors = RommTheme.colors
    var sectionIndex by rememberSaveable { mutableIntStateOf(0) }
    val section = Section.entries[sectionIndex]
    // One level up, like everywhere else: from Collections, Installed or Search, B returns to
    // Platforms; only from Platforms does it arm the exit. Registered here, inside the Home
    // destination, so it takes priority over the root handler while it is enabled.
    BackHandler(enabled = sectionIndex != 0) { sectionIndex = 0 }
    val holder = rememberSaveableStateHolder()
    val openCount by appVm.openDownloadCount.collectAsStateWithLifecycle()

    // Silent by design: the rail highlights the section L2/R2 move between, and the queue bar
    // shows where R3 lands. Nothing is drawn here; the shell has no bar of its own.
    PadBar(sectionIndex) {
        silent(GamepadAction.PREV_SECTION) { sectionIndex = (sectionIndex + Section.entries.size - 1) % Section.entries.size }
        silent(GamepadAction.NEXT_SECTION) { sectionIndex = (sectionIndex + 1) % Section.entries.size }
        silent(GamepadAction.OPEN_SEARCH) { sectionIndex = Section.SEARCH.ordinal }
        silent(GamepadAction.OPEN_DOWNLOADS) { onOpenDownloads() }
    }

    val content: @Composable (Modifier) -> Unit = { modifier ->
        Box(modifier) {
            holder.SaveableStateProvider(section.name) {
                when (section) {
                    Section.PLATFORMS -> PlatformsScreen(appVm = appVm, onOpenLibrary = onOpenLibrary, onOpenGame = onOpenGame, onOpenDownloads = onOpenDownloads)
                    Section.COLLECTIONS -> CollectionsScreen(onOpenLibrary = onOpenLibrary)
                    Section.LOCAL -> LocalPlatformsScreen(appVm = appVm, onOpenLibrary = onOpenLibrary)
                    Section.SEARCH -> SearchScreen(onOpenGame = onOpenGame, onOpenDownloads = onOpenDownloads)
                }
            }
        }
    }

    if (layout.navMode == NavMode.BOTTOM_BAR) {
        // The pill floats over the content, as in the v2 web app: the grid scrolls on behind a
        // dark gradient instead of stopping at a solid band.
        Box(Modifier.fillMaxSize()) {
            val navInset = LocalNavBarInset.current
            CompositionLocalProvider(LocalBottomOverlay provides layout.bottomBarHeight + 40.dp + navInset) {
                content(Modifier.fillMaxSize())
            }
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(com.rommmobile.app.core.design.softFadeBrush(colors.background, colors.isDark))
                    .padding(top = 40.dp, bottom = navInset),
            ) {
                DownloadMiniBar(onClick = onOpenDownloads)
                BottomBar(
                    selected = sectionIndex,
                    onSelect = { sectionIndex = it },
                    openCount = openCount,
                    onOpenDownloads = onOpenDownloads,
                    onOpenSettings = onOpenSettings,
                )
            }
        }
    } else {
        Row(Modifier.fillMaxSize()) {
            Rail(
                mode = layout.navMode,
                selected = sectionIndex,
                onSelect = { sectionIndex = it },
                openCount = openCount,
                onOpenDownloads = onOpenDownloads,
                onOpenSettings = onOpenSettings,
            )
            // No extra gap: the page margin (layout.margin) is the space after the side panel.
            Column(Modifier.weight(1f).fillMaxHeight()) {
                content(Modifier.weight(1f).fillMaxWidth())
                DownloadMiniBar(onClick = onOpenDownloads, modifier = Modifier.padding(bottom = LocalNavBarInset.current))
            }
        }
    }
}

/**
 * v2 navigation (`AppShell/AppNav.vue`, `BottomNav.vue`): one rounded "pill" of glass holding the
 * sections, the active one lit as a white disc with a dark glyph. Here it stands upright on the
 * left, where the D-pad thumb is, instead of lying in the top bar.
 */
@Composable
private fun Rail(
    mode: NavMode,
    selected: Int,
    onSelect: (Int) -> Unit,
    openCount: Int,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val colors = RommTheme.colors
    val requesters = remember { List(Section.entries.size) { FocusRequester() } }
    val statusInset = with(androidx.compose.ui.platform.LocalDensity.current) {
        androidx.compose.foundation.layout.WindowInsets.statusBars.getTop(this).toDp()
    }
    // A full-height side panel against the left edge, a hairline only on its right, like a
    // desktop sidebar: not a floating pill.
    Column(
        Modifier
            .fillMaxHeight()
            .width(mode.railWidth)
            // The panel runs on up behind the transparent status bar (the root insets stop the
            // content below it): background and right hairline are drawn that much higher.
            // Runs up behind the status bar but fades out there: no hard edge across the clock,
            // and no step where the panel would otherwise stop under the bar.
            .drawBehind {
                val up = statusInset.toPx()
                drawRect(colors.elevated, topLeft = Offset(0f, 160.dp.toPx()), size = androidx.compose.ui.geometry.Size(size.width, (size.height - 160.dp.toPx()).coerceAtLeast(0f)))
                // Softer and longer fade: from nothing at the top of the screen to the full panel
                // well below the status bar, so the panel melts into the header.
                val fadeEnd = 160.dp.toPx()
                drawRect(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        0f to colors.elevated.copy(alpha = 0f), 1f to colors.elevated, startY = -up, endY = fadeEnd,
                    ),
                    topLeft = Offset(0f, -up),
                    size = androidx.compose.ui.geometry.Size(size.width, up + fadeEnd),
                )
                val x = size.width - 0.5f
                drawLine(colors.borderStrong, Offset(x, 160.dp.toPx()), Offset(x, size.height), 1f)
                drawLine(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        0f to colors.borderStrong.copy(alpha = 0f), 1f to colors.borderStrong, startY = -up, endY = 160.dp.toPx(),
                    ),
                    Offset(x, -up), Offset(x, 160.dp.toPx()), 1f,
                )
            }
            // First icon level with the page title, Settings near the bottom edge: the gesture
            // handle is centred, nothing to keep clear of on the left.
            .padding(top = 0.dp, bottom = 10.dp)
            // Moving into the rail lands on the section that is already highlighted, never on the
            // first item: a cursor on "Platforms" while "Installed" is lit is two answers to one
            // question.
            .focusProperties { onEnter = { requesters[selected].requestFocus() } }
            .focusGroup(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // One full-height block: the sections and Downloads at the top, Settings alone at the
        // bottom, apart from the things the user browses.
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Section.entries.forEachIndexed { i, s ->
                RailItem(
                    icon = s.icon,
                    label = stringResource(s.labelRes),
                    selected = selected == i,
                    showLabel = mode.showLabels,
                    onClick = { onSelect(i) },
                    modifier = Modifier.focusRequester(requesters[i]),
                )
            }
            RailItem(
                icon = Icons.Outlined.FileDownload,
                label = stringResource(R.string.section_downloads),
                selected = false,
                showLabel = mode.showLabels,
                badge = openCount.takeIf { it > 0 }?.toString(),
                onClick = onOpenDownloads,
            )
            Spacer(Modifier.weight(1f))
            RailItem(
                icon = Icons.Outlined.Settings,
                label = stringResource(R.string.section_settings),
                selected = false,
                showLabel = mode.showLabels,
                onClick = onOpenSettings,
            )
        }
    }
}

@Composable
private fun RailItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    showLabel: Boolean,
    onClick: () -> Unit,
    badge: String? = null,
    modifier: Modifier = Modifier,
) {
    val colors = RommTheme.colors
    val shape = RoundedCornerShape(22.dp)
    var focused by remember { mutableStateOf(false) }
    // Compact stack (about 44 dp per entry) so the sections read as one group; focus is a
    // colour, not a size, like everywhere else.
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 1.dp)
            .gamepadFocus(shape, scale = 1f, onFocused = { focused = it })
            .clip(shape)
            // No default indication: its focus overlay drew a second, rounded-rect highlight
            // around the whole entry on top of the icon's own circle.
            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Box(
                Modifier
                    .size(38.dp)
                    .background(if (selected) colors.emphasis else if (focused) colors.surfaceHover else Color.Transparent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = label, tint = if (selected) colors.onEmphasis else colors.onSurfaceSecondary, modifier = Modifier.size(22.dp))
            }
            if (badge != null) {
                StatusPill(badge, colors.primary, modifier = Modifier.align(Alignment.TopEnd))
            }
        }
        if (showLabel) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) colors.onSurface else colors.onSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** Phones: the floating, icon-only bottom pill of the v2 web app (max 480 dp wide). */
@Composable
private fun BottomBar(
    selected: Int,
    onSelect: (Int) -> Unit,
    openCount: Int,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val colors = RommTheme.colors
    val layout = RommTheme.layout
    val pill = RoundedCornerShape(999.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(layout.bottomBarHeight)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .fillMaxHeight()
                .clip(pill)
                .background(colors.surface.copy(alpha = 0.9f))
                .border(1.dp, colors.borderStrong, pill)
                .focusGroup()
                .padding(4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Section.entries.forEachIndexed { i, s ->
                BottomItem(s.icon, stringResource(s.labelRes), selected == i, Modifier.weight(1f)) { onSelect(i) }
            }
            BottomItem(Icons.Outlined.FileDownload, stringResource(R.string.section_downloads), false, Modifier.weight(1f), badge = openCount.takeIf { it > 0 }?.toString(), onClick = onOpenDownloads)
            BottomItem(Icons.Outlined.Settings, stringResource(R.string.section_settings), false, Modifier.weight(1f), onClick = onOpenSettings)
        }
    }
}

@Composable
private fun BottomItem(icon: ImageVector, label: String, selected: Boolean, modifier: Modifier, badge: String? = null, onClick: () -> Unit) {
    val colors = RommTheme.colors
    var focused by remember { mutableStateOf(false) }
    val pill = RoundedCornerShape(999.dp)
    Box(
        modifier
            .fillMaxHeight()
            .padding(horizontal = 2.dp)
            .gamepadFocus(pill, scale = 1f, onFocused = { focused = it })
            .clip(pill)
            .background(if (selected) colors.emphasis else if (focused) colors.surfaceHover else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = if (selected) colors.onEmphasis else colors.onSurfaceSecondary, modifier = Modifier.size(22.dp))
        if (badge != null) StatusPill(badge, colors.primary, modifier = Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 6.dp))
    }
}
