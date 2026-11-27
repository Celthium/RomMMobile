package com.rommmobile.app.feature.library

import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.launch
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import com.rommmobile.app.core.design.CoverRatios
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.shadow
import com.rommmobile.app.core.design.PillShape
import com.rommmobile.app.core.util.Flags
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rommmobile.app.core.design.Badge
import com.rommmobile.app.core.design.CoverImage
import com.rommmobile.app.core.design.PlatformIcon
import com.rommmobile.app.core.design.RommTheme
import com.rommmobile.app.core.design.Skeleton
import com.rommmobile.app.core.design.gamepadFocus
import com.rommmobile.app.core.design.gamepadFocusRing
import com.rommmobile.app.core.design.gamepadFocusRow
import com.rommmobile.app.core.util.Format
import com.rommmobile.app.data.db.DownloadState
import com.rommmobile.app.data.repo.Rom
import com.rommmobile.app.feature.downloads.engine.DownloadProgress

/** Per-item status derived once per recomposition of the parent; cheap to compare. */
@Immutable
data class ItemStatus(val present: Boolean, val progress: DownloadProgress?) {
    companion object { val NONE = ItemStatus(false, null) }
}

/** v2 cards: covers are 2:3 (`--r-card-art-ratio`), never 1:1.4 any more. */
const val COVER_RATIO = 2f / 3f

/**
 * A game in a grid, drawn after the RomM web v2 card (`v2/components/GameCard`): the cover alone
 * carries the information, as small overlays that read on any art; the title sits underneath on
 * one line. On focus the card grows (owner's rule: no ring around covers) and, as on hover in the
 * web app, the rating pill appears at the top.
 */
@Composable
fun GameCard(
    rom: Rom,
    status: ItemStatus,
    showPlatform: Boolean,
    platformIconUrl: String?,
    onFocused: (Rom) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    largeCover: Boolean = false,
    selecting: Boolean = false,
    selected: Boolean = false,
    platformIconUrls: List<String> = listOfNotNull(platformIconUrl),
    /** Shape of every cell in this grid: the tallest cover the list has shown (see LibraryContent). */
    slotRatio: Float = COVER_RATIO,
    onCoverRatio: (Float) -> Unit = {},
) {
    val colors = RommTheme.colors
    val shape = MaterialTheme.shapes.medium
    var focused by remember { mutableStateOf(false) }
    // RomM's "small" cover is about 160 px wide: fine for a thumbnail, mush once stretched over a
    // grid cell. Bigger cells therefore ask for the large copy first and Coil subsamples it.
    val covers = remember(rom.id, rom.coverSmall, rom.coverLarge, largeCover) { rom.coverCandidates(preferLarge = largeCover) }
    val flags = remember(rom.regions, rom.languages) { Flags.chips(rom.regions.ifEmpty { rom.languages }, max = 2) }
    Column(
        modifier
            .gamepadFocus(shape, onFocused = { focused = it; if (it) onFocused(rom) })
            .clip(shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Every cell has the same 2:3 slot so rows stay aligned; inside it the cover keeps its own
        // shape (wider ones get shorter, sitting on the title line), as on the web where a card
        // takes its width from the picture.
        var ratio by remember(rom.id) { mutableStateOf(CoverRatios.firstKnown(covers)) }
        LaunchedEffect(ratio) { ratio?.let(onCoverRatio) }
        // No picture yet (or none at all): fill the slot, so generated art is as big as its row.
        val r = (ratio ?: slotRatio).coerceIn(0.45f, 1.8f)
        Box(Modifier.fillMaxWidth().aspectRatio(slotRatio), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier
                .aspectRatio(r, matchHeightConstraintsFirst = r < slotRatio)
                .shadow(if (focused) 14.dp else 3.dp, shape, ambientColor = Color.Black, spotColor = Color.Black),
        ) {
            CoverImage(urls = covers, title = rom.name, modifier = Modifier.fillMaxSize(), shape = shape, onRatio = { ratio = it })
            // Focus is a colour, not a size: the web's 2 px brand outline around the cover.
            if (focused) Box(Modifier.matchParentSize().border(2.dp, colors.primaryLighten, shape))
            // Holographic glint: one light band sweeps across the cover as the cursor lands.
            val glint = remember { androidx.compose.animation.core.Animatable(1f) }
            LaunchedEffect(focused) {
                if (focused) { glint.snapTo(0f); glint.animateTo(1f, tween(750, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
            }
            if (glint.value < 1f) {
                Box(
                    Modifier.matchParentSize().clip(shape).drawWithContent {
                        val x = size.width * (glint.value * 2.2f - 0.6f)
                        drawRect(
                            androidx.compose.ui.graphics.Brush.linearGradient(
                                0f to Color.Transparent,
                                0.38f to Color.White.copy(alpha = 0.0f),
                                0.47f to Color.White.copy(alpha = 0.45f),
                                0.53f to Color(0x80C4B5FF),
                                0.62f to Color.Transparent,
                                start = androidx.compose.ui.geometry.Offset(x - size.width * 0.5f, 0f),
                                end = androidx.compose.ui.geometry.Offset(x + size.width * 0.5f, size.height),
                            ),
                        )
                    },
                )
            }
            // Top-right column: platform, then versions, as in the web card.
            Column(
                Modifier.align(Alignment.TopEnd).padding(5.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (selecting) {
                    Box(
                        Modifier.size(24.dp)
                            .background(if (selected) colors.primary else colors.scrimStrong, CircleShape)
                            .border(1.dp, colors.overlayBorderStrong, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(15.dp))
                    }
                } else if (showPlatform) {
                    Box(
                        Modifier.size(24.dp).background(colors.scrimStrong, CircleShape).border(1.dp, colors.overlayBorder, CircleShape).padding(3.dp),
                        contentAlignment = Alignment.Center,
                    ) { PlatformIcon(platformIconUrls, size = 17.dp) }
                }
                // Rating, only under the cursor (the web shows it on hover), at the end of this
                // column. Not when the other screen is showing the game: its sheet has the rating.
                val rating = rom.rating?.takeIf { it > 0 }
                val companionShown = com.rommmobile.app.feature.dualscreen.LocalDualScreen.current
                    ?.activeState?.collectAsState()?.value == true
                if (focused && rating != null && !companionShown) {
                    Text(
                        "★ " + "%.1f".format(if (rating > 10) rating / 10.0 else rating),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.gold,
                        modifier = Modifier
                            .background(colors.scrimStrong, PillShape)
                            .border(1.dp, colors.gold.copy(alpha = 0.6f), PillShape)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
                if (rom.siblingCount > 0) {
                    Row(
                        Modifier.background(colors.scrimStrong, PillShape).border(1.dp, colors.overlayBorder, PillShape).padding(horizontal = 5.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Layers, null, tint = colors.overlayFg, modifier = Modifier.size(11.dp))
                        Spacer(Modifier.width(2.dp))
                        Text("${rom.siblingCount + 1}", style = MaterialTheme.typography.labelSmall, color = colors.overlayFg)
                    }
                }
            }
            // Bottom-left: region flags.
            if (flags.isNotEmpty()) {
                Row(Modifier.align(Alignment.BottomStart).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    flags.forEach { Badge(it) }
                }
            }
            StatusOverlay(status, rom.id, Modifier.align(Alignment.Center), presentAlign = Alignment.BottomEnd, boxModifier = Modifier.matchParentSize())
        }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            rom.name,
            style = MaterialTheme.typography.bodySmall,
            color = if (focused) colors.onSurface else colors.onSurfaceSecondary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        )
    }
}

@Composable
private fun StatusOverlay(status: ItemStatus, romId: Int, modifier: Modifier, presentAlign: Alignment, boxModifier: Modifier) {
    val colors = RommTheme.colors
    // Was it absent when this card first showed, and is it present now? Then it just finished.
    // Or it finished while the card was not on screen (downloaded from the game page): played
    // the first time the card shows again.
    val wasAbsent = remember { status.present.not() }
    val finishedElsewhere = remember(status.present) { status.present && com.rommmobile.app.feature.downloads.engine.JustDownloaded.consume(romId) }
    val justCompleted = (wasAbsent && status.present) || finishedElsewhere
    if (wasAbsent && status.present) {
        // Celebrated here already: not again when the grid comes back.
        LaunchedEffect(Unit) { com.rommmobile.app.feature.downloads.engine.JustDownloaded.consume(romId) }
    }
    val p = status.progress
    Box(boxModifier) {
        when {
            p != null && (p.state.isActive || p.state == DownloadState.PENDING || p.state == DownloadState.PAUSED || p.state == DownloadState.FAILED) -> {
                Box(
                    Modifier.align(Alignment.Center).size(46.dp).background(colors.scrimStrong, CircleShape).border(1.dp, colors.overlayBorder, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    when (p.state) {
                        DownloadState.PENDING -> Icon(Icons.Rounded.Schedule, null, tint = Color.White, modifier = Modifier.size(22.dp))
                        DownloadState.PAUSED -> Icon(Icons.Rounded.Pause, null, tint = Color.White, modifier = Modifier.size(22.dp))
                        DownloadState.FAILED -> Icon(Icons.Rounded.ErrorOutline, null, tint = colors.red, modifier = Modifier.size(22.dp))
                        DownloadState.DOWNLOADING -> {
                            CircularProgressIndicator(progress = { p.percent / 100f }, modifier = Modifier.size(40.dp), color = colors.primaryLighten, trackColor = colors.gray, strokeWidth = 3.dp)
                            Text("${p.percent}", style = MaterialTheme.typography.labelSmall, color = Color.White)
                        }
                        else -> CircularProgressIndicator(modifier = Modifier.size(40.dp), color = colors.primaryLighten, strokeWidth = 3.dp)
                    }
                }
            }
            status.present -> {
                // Played only when the game turns present while on screen (a download finishing),
                // not for games that were already there: the tick pops in and a green glow fades.
                val pop = remember { androidx.compose.animation.core.Animatable(if (justCompleted) 0f else 1f) }
                val glow = remember { androidx.compose.animation.core.Animatable(if (justCompleted) 1f else 0f) }
                LaunchedEffect(justCompleted) {
                    if (justCompleted) {
                        // Tick and glow together: the tick bounces in while the green flash fades.
                        launch { glow.animateTo(0f, tween(700)) }
                        pop.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.45f, stiffness = 500f))
                    }
                }
                if (glow.value > 0f) {
                    Box(
                        Modifier.matchParentSize().drawWithContent {
                            val a = glow.value * 0.5f
                            drawRect(
                                androidx.compose.ui.graphics.Brush.radialGradient(
                                    listOf(colors.success.copy(alpha = a), Color.Transparent),
                                    center = center, radius = size.maxDimension * 0.7f,
                                ),
                            )
                        },
                    )
                }
                Box(
                    Modifier.align(presentAlign).padding(5.dp).size(20.dp)
                        .graphicsLayer { scaleX = pop.value; scaleY = pop.value }
                        .background(colors.green, CircleShape).border(1.dp, colors.overlayBorderStrong, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(14.dp)) }
            }
        }
    }
}

@Composable
fun GameCardSkeleton(modifier: Modifier = Modifier, slotRatio: Float = COVER_RATIO) {
    Column(modifier.padding(2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Skeleton(Modifier.fillMaxWidth().aspectRatio(slotRatio))
        Spacer(Modifier.height(7.dp))
        Skeleton(Modifier.fillMaxWidth(0.7f).height(11.dp), shape = MaterialTheme.shapes.small)
    }
}

@Composable
fun GameRow(
    rom: Rom,
    status: ItemStatus,
    showPlatform: Boolean,
    onFocused: (Rom) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    selecting: Boolean = false,
    selected: Boolean = false,
) {
    val colors = RommTheme.colors
    val layout = RommTheme.layout
    val shape = MaterialTheme.shapes.small
    Row(
        modifier
            .fillMaxWidth()
            .onFocusChanged { if (it.hasFocus) onFocused(rom) }
            .gamepadFocusRow(shape)
            .clip(shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .height(layout.listRowHeight - 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val thumbs = remember(rom.id, rom.coverSmall) { rom.coverCandidates(preferLarge = false) }
        CoverImage(thumbs, rom.name, Modifier.width(36.dp).fillMaxSize(), shape = MaterialTheme.shapes.extraSmall, showTitleWhenMissing = false)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(rom.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = buildList {
                if (showPlatform) add(rom.platformName)
                rom.regionBadge?.let { add(it) }
                if (rom.sizeBytes > 0) add(Format.bytes(rom.sizeBytes))
                if (rom.siblingCount > 0) add("${rom.siblingCount + 1} ver.")
            }.joinToString(" · ")
            Text(meta, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        if (selecting) {
            // Takes the status slot: while selecting, whether it is picked is the only status
            // that matters, and the tick is where the eye already goes on a row.
            Box(
                Modifier.size(24.dp).background(if (selected) colors.primary else colors.toplayer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
        } else {
            RowStatus(status)
        }
    }
}

@Composable
private fun RowStatus(status: ItemStatus) {
    val colors = RommTheme.colors
    val p = status.progress
    when {
        p != null && p.state == DownloadState.DOWNLOADING -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${p.percent}%", style = MaterialTheme.typography.labelMedium, color = colors.primaryLighten)
            Spacer(Modifier.width(6.dp))
            CircularProgressIndicator(progress = { p.percent / 100f }, modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.primaryLighten, trackColor = colors.gray)
        }
        p != null && p.state.isActive -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.primaryLighten)
        p != null && p.state == DownloadState.PENDING -> Icon(Icons.Rounded.Schedule, null, tint = colors.onSurfaceMuted, modifier = Modifier.size(18.dp))
        p != null && p.state == DownloadState.PAUSED -> Icon(Icons.Rounded.Pause, null, tint = colors.onSurfaceMuted, modifier = Modifier.size(18.dp))
        p != null && p.state == DownloadState.FAILED -> Icon(Icons.Rounded.ErrorOutline, null, tint = colors.red, modifier = Modifier.size(18.dp))
        status.present -> Icon(Icons.Rounded.CheckCircle, null, tint = colors.success, modifier = Modifier.size(20.dp))
        else -> Icon(Icons.Rounded.Download, null, tint = colors.onSurfaceFaint, modifier = Modifier.size(18.dp))
    }
}

@Composable
fun GameRowSkeleton(modifier: Modifier = Modifier) {
    val layout = RommTheme.layout
    Row(modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp).height(layout.listRowHeight - 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Skeleton(Modifier.width(40.dp).fillMaxSize(), shape = MaterialTheme.shapes.small)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Skeleton(Modifier.fillMaxWidth(0.6f).height(14.dp), shape = MaterialTheme.shapes.small)
            Spacer(Modifier.height(6.dp))
            Skeleton(Modifier.fillMaxWidth(0.35f).height(11.dp), shape = MaterialTheme.shapes.small)
        }
    }
}
