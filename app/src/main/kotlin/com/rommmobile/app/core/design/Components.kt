package com.rommmobile.app.core.design

import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.unit.em
import androidx.compose.material3.LocalContentColor
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.layout
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.foundation.border
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import com.rommmobile.app.core.input.remapKey
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.DisposableEffect
import android.view.Window
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import android.view.KeyEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.VideogameAsset
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.rommmobile.app.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.rommmobile.app.core.input.GamepadBus
import com.rommmobile.app.core.input.LogicalButton
import com.rommmobile.app.core.input.LocalGamepad
import com.rommmobile.app.core.input.ModalScope
import kotlin.math.roundToInt

/* ----------------------------- top bar ----------------------------- */

@Composable
fun RommTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val layout = RommTheme.layout
    val colors = RommTheme.colors
    // v2 bars are glass over the page, not a solid band: transparent, no line underneath. What
    // sits behind (the blurred cover backdrop on game screens) shows through, as on the web.
    // A Box, not a Surface: a Surface clips, and on a short screen the 36 dp bar cut the top off
    // the back button's 48 dp focus circle.
    androidx.compose.runtime.CompositionLocalProvider(LocalContentColor provides colors.onBackground) {
    // No divider under the title: the page flows straight from the header into the content.
    // 10 dp of air above it on every screen, as the setup had first: right under the status bar
    // the title read as stuck to it.
    Box(modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(
            // The back arrow's glyph (8 dp inside its 40 dp button) and a title without arrow
            // both land on the page margin, like the grid under them.
            modifier = Modifier.height(layout.topBarHeight).padding(start = if (onBack != null) layout.margin - 8.dp else 0.dp, end = layout.margin - 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.gamepadFocusIcon()) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            } else {
                Spacer(Modifier.width(layout.margin))
            }
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null && !layout.isShort) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
    }
}

/* ----------------------------- skeleton ----------------------------- */

@Composable
fun Skeleton(modifier: Modifier = Modifier, shape: Shape = MaterialTheme.shapes.medium) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f, targetValue = 0.75f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alpha",
    )
    Box(modifier.alpha(alpha).clip(shape).background(RommTheme.colors.coverPlaceholder))
}

/* ----------------------------- search field ----------------------------- */

/**
 * The v2 search box: a pill of `surface` with a hairline edge (brand-coloured while typing), the
 * magnifier inset from the edge, and the clear button only when there is text. 46 dp high, so a
 * short handheld keeps its row of covers under the keyboard.
 */
@Composable
fun RommSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    clearFocusable: Boolean = true,
) {
    val colors = RommTheme.colors
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.primary),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSearch() }),
        interactionSource = interaction,
        modifier = modifier,
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .clip(PillShape)
                    .background(colors.surface)
                    .border(1.dp, if (focused) colors.primary else colors.borderStrong, PillShape)
                    .padding(start = 18.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, null, tint = colors.onSurfaceMuted, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
                if (value.isNotEmpty()) {
                    IconButton(onClick = { onValueChange("") }, modifier = Modifier.focusProperties { canFocus = clearFocusable }) {
                        Icon(Icons.Rounded.Close, stringResource(R.string.action_clear), tint = colors.onSurfaceMuted)
                    }
                } else {
                    Spacer(Modifier.width(12.dp))
                }
            }
        },
    )
}

/**
 * Form field in the search box's style: the same 46 dp pill, surface fill and border that turns
 * violet on focus, with an optional label above it (forms need to say what goes in, a search box
 * does not). [fieldModifier] goes on the editable part itself: focus requesters and the
 * gamepad text-field behaviour belong there.
 */
@Composable
fun RommTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    fieldModifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    leadingIcon: ImageVector? = null,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default,
    keyboardActions: androidx.compose.foundation.text.KeyboardActions = androidx.compose.foundation.text.KeyboardActions.Default,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
    clearable: Boolean = true,
    /** Shown in red under the field, which then gets a red border too. */
    error: String? = null,
) {
    val colors = RommTheme.colors
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val edge = when {
        error != null -> colors.red
        focused -> colors.primary
        else -> colors.borderStrong
    }
    Column(modifier) {
        if (label != null) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = if (focused) colors.primaryLighten else colors.onSurfaceMuted)
            Spacer(Modifier.height(6.dp))
        }
        // Held as a TextFieldValue so a pre-filled field ("http://") opens with the caret at the
        // end, ready to type on, instead of at the start.
        var tfv by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(value, androidx.compose.ui.text.TextRange(value.length))) }
        if (tfv.text != value) tfv = androidx.compose.ui.text.input.TextFieldValue(value, androidx.compose.ui.text.TextRange(value.length))
        androidx.compose.foundation.text.BasicTextField(
            value = tfv,
            onValueChange = { tfv = it; if (it.text != value) onValueChange(it.text) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.primary),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            visualTransformation = visualTransformation,
            interactionSource = interaction,
            modifier = Modifier.fillMaxWidth().then(fieldModifier),
            decorationBox = { inner ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .clip(PillShape)
                        .background(colors.surface)
                        .border(1.dp, edge, PillShape)
                        .padding(start = 18.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (leadingIcon != null) {
                        Icon(leadingIcon, null, tint = colors.onSurfaceMuted, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty() && placeholder != null) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        inner()
                    }
                    if (clearable && value.isNotEmpty()) {
                        // Touch only: from the pad the field is cleared with the keyboard.
                        IconButton(onClick = { onValueChange("") }, modifier = Modifier.focusProperties { canFocus = false }) {
                            Icon(Icons.Rounded.Close, stringResource(R.string.action_clear), tint = colors.onSurfaceMuted)
                        }
                    } else {
                        Spacer(Modifier.width(12.dp))
                    }
                }
            },
        )
        if (error != null) {
            Spacer(Modifier.height(6.dp))
            Text(error, style = MaterialTheme.typography.bodySmall, color = colors.red, modifier = Modifier.padding(horizontal = 18.dp))
        }
    }
}

/* ----------------------------- small bits ----------------------------- */

@Composable
fun StatusPill(text: String, container: Color, content: Color = Color.White, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.background(container, PillShape).padding(horizontal = 7.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = content,
        maxLines = 1,
    )
}

@Composable
fun Badge(text: String, modifier: Modifier = Modifier, container: Color = RommTheme.colors.scrimSoft) {
    // v2 overlay chip: soft scrim, 1 px light border, 6 px radius. Readable on any cover.
    val shape = RoundedCornerShape(6.dp)
    Text(
        text = text,
        modifier = modifier
            .background(container, shape)
            .border(1.dp, RommTheme.colors.overlayBorder, shape)
            .padding(horizontal = 5.dp, vertical = 1.dp),
        style = MaterialTheme.typography.labelSmall,
        color = RommTheme.colors.overlayFg,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
    )
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier.padding(horizontal = RommTheme.layout.margin, vertical = 6.dp),
    )
}

/* ----------------------------- list states ----------------------------- */

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, contentDescription = null, tint = RommTheme.colors.onSurfaceMuted, modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            PadText(subtitle, style = MaterialTheme.typography.bodyMedium, color = RommTheme.colors.onSurfaceMuted, textAlign = TextAlign.Center)
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            AutoFocusButton(actionLabel, onAction)
        }
    }
}

@Composable
fun ErrorState(message: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    Column(modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Rounded.CloudOff, contentDescription = null, tint = RommTheme.colors.red, modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(12.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        if (onRetry != null) {
            Spacer(Modifier.height(16.dp))
            AutoFocusButton(stringResource(R.string.action_retry), onRetry)
        }
    }
}

/** Button that takes focus when it appears: "Retry" must be one press away on a gamepad. */
@Composable
fun AutoFocusButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val fr = remember { FocusRequester() }
    // Asking once fails silently while the node is still being placed; a few spaced requests
    // cost nothing and re-requesting on an already-focused node is a no-op.
    LaunchedEffect(Unit) { repeat(6) { runCatching { fr.requestFocus() }; delay(40) } }
    Button(onClick = onClick, modifier = modifier.focusRequester(fr).gamepadFocusRing(PillShape)) { Text(label) }
}

@Composable
fun OfflineBanner(visible: Boolean, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    AnimatedVisibility(visible, modifier = modifier, enter = expandVertically(), exit = shrinkVertically()) {
        // Tappable on purpose: the banner used to be the only place that said we were offline and
        // the only place with no way to say otherwise, so it stayed up for the whole session.
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (onRetry != null) Modifier.gamepadFocusRow(RectangleShape).clickable(onClick = onRetry) else Modifier)
                .background(RommTheme.colors.toplayer)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.CloudOff, contentDescription = null, tint = RommTheme.colors.accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.offline_banner), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            if (onRetry != null) {
                Text(stringResource(R.string.action_retry), style = MaterialTheme.typography.labelMedium, color = RommTheme.colors.primaryLighten)
            }
        }
    }
}

/* ----------------------------- settings rows ----------------------------- */

@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = RommTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .gamepadFocusRow(MaterialTheme.shapes.medium)
            .clip(MaterialTheme.shapes.medium)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .padding(horizontal = RommTheme.layout.margin, vertical = 10.dp)
            .alpha(if (enabled) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceMuted)
        }
        if (trailing != null) { Spacer(Modifier.width(12.dp)); trailing() }
    }
}

@Composable
fun SwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, subtitle: String? = null, enabled: Boolean = true) {
    SettingRow(title = title, subtitle = subtitle, onClick = { onCheckedChange(!checked) }, enabled = enabled, modifier = modifier) {
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/* ----------------------------- dialogs ----------------------------- */

/**
 * Focus lands on the confirm button; B (BACK) dismisses. Global gamepad shortcuts are muted
 * while it is on screen thanks to [ModalScope].
 */
@Composable
fun RommDialog(
    onDismissRequest: () -> Unit,
    title: String,
    confirmText: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    dismissText: String? = stringResource(R.string.action_cancel),
    destructive: Boolean = false,
    focusConfirm: Boolean = true,
    content: @Composable () -> Unit,
) {
    ModalScope()
    val confirmFr = remember { FocusRequester() }
    val dismissFr = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // Dialog content lives in its own window and may not be placed on the first frame.
        repeat(6) {
            runCatching { if (focusConfirm) confirmFr.requestFocus() else dismissFr.requestFocus() }
            delay(40)
        }
    }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = RommTheme.colors.toplayer,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = { DialogPadRemap(onBack = onDismissRequest); content() },
        confirmButton = {
            if (destructive) {
                TextButton(onClick = onConfirm, modifier = Modifier.focusRequester(confirmFr).gamepadFocusRing(PillShape)) {
                    Text(confirmText, color = RommTheme.colors.red)
                }
            } else {
                Button(onClick = onConfirm, modifier = Modifier.focusRequester(confirmFr).gamepadFocusRing(PillShape)) { Text(confirmText) }
            }
        },
        dismissButton = if (dismissText != null) {
            { TextButton(onClick = onDismissRequest, modifier = Modifier.focusRequester(dismissFr).gamepadFocusRing(PillShape)) { Text(dismissText) } }
        } else null,
        properties = DialogProperties(usePlatformDefaultWidth = RommTheme.layout.widthDp >= 480),
    )
}

/**
 * A dialog is its own window with its own key dispatch, which the Activity never sees: without
 * this, a remapped pad would keep the raw layout inside every dialog (B confirming, A closing).
 * Wraps the dialog window's callback with the same translation the Activity applies.
 */
@Composable
internal fun DialogPadRemap(onBack: () -> Unit) {
    val bus = LocalGamepad.current
    val view = LocalView.current
    val back by rememberUpdatedState(onBack)
    DisposableEffect(view, bus) {
        val window = (view.parent as? DialogWindowProvider)?.window
        val original = window?.callback
        if (window != null && original != null) {
            window.callback = object : Window.Callback by original {
                override fun dispatchKeyEvent(event: KeyEvent): Boolean =
                    bus.remapKey(event, dispatch = { original.dispatchKeyEvent(it) }, onBack = { back() })
            }
        }
        onDispose { if (window != null && original != null && window.callback !== original) window.callback = original }
    }
}

/* ----------------------------- key hints ----------------------------- */

/**
 * Face and menu buttons carry a pixel-art sprite from the pad pack (16 px cell, idle and pressed
 * faces, recoloured onto the app palette); the shoulder and stick buttons have no sprite and fall
 * back to a drawn disc with the label.
 */
enum class PadButton(val glyph: String, val sprite: Int? = null, val spritePressed: Int? = null) {
    A("A", R.drawable.pad_south, R.drawable.pad_south_p),
    B("B", R.drawable.pad_east, R.drawable.pad_east_p),
    X("X", R.drawable.pad_west, R.drawable.pad_west_p),
    Y("Y", R.drawable.pad_north, R.drawable.pad_north_p),
    L1("L1"), R1("R1"), L2("L2"), R2("R2"), L3("L3"), R3("R3"),
    START("≡", R.drawable.pad_start, R.drawable.pad_start_p),
    SELECT("⧉", R.drawable.pad_select, R.drawable.pad_select_p);

    companion object {
        /** The drawn face of a printed button; the d-pad has none. */
        fun of(button: LogicalButton): PadButton? = when (button) {
            LogicalButton.A -> A
            LogicalButton.B -> B
            LogicalButton.X -> X
            LogicalButton.Y -> Y
            LogicalButton.L1 -> L1
            LogicalButton.R1 -> R1
            LogicalButton.L2 -> L2
            LogicalButton.R2 -> R2
            LogicalButton.START -> START
            LogicalButton.SELECT -> SELECT
            LogicalButton.UP, LogicalButton.DOWN, LogicalButton.LEFT, LogicalButton.RIGHT -> null
        }
    }
}

/** Which drawn button a physical key lights up, through the user's map; the sticks are raw. */
fun GamepadBus.padButtonFor(code: Int): PadButton? = when (val logical = logicalFor(code)) {
    null -> when (code) {
        KeyEvent.KEYCODE_BUTTON_THUMBL -> PadButton.L3
        KeyEvent.KEYCODE_BUTTON_THUMBR -> PadButton.R3
        else -> null
    }
    else -> PadButton.of(logical)
}

/** True while the physical key behind [button] is held down. */
@Composable
private fun isHeld(button: PadButton): Boolean {
    val bus = LocalGamepad.current
    val held by bus.held.collectAsState()
    return held.any { bus.padButtonFor(it) == button }
}

/**
 * The sprite, drawn pixel-exact: a whole multiple of the 16 px cell nearest to [size], sampled
 * nearest-neighbour, so the art never smears into a blurry disc at 2.6x density.
 */
@Composable
private fun PadSprite(button: PadButton, pressed: Boolean, size: Dp, modifier: Modifier = Modifier) {
    val id = (if (pressed) button.spritePressed else button.sprite) ?: return
    val bitmap = ImageBitmap.imageResource(id)
    // Every sprite is sized off the SAME 48-or-so pixel target, chosen once from the 16 px face
    // cell, and then each file takes the whole multiple of its own cell nearest that target. The
    // face buttons fill their 16 px cell while the Start/Select arrows are 12 px discs (cropped
    // files), and rounding each from the requested dp independently split them: at the Retroid
    // density of 2.0, 20 dp is 40 px, which is 3x16 = 48 for a letter but 3x12 = 36 for an arrow.
    // Anchoring on the face size gives 48 / 12 = 4x, and the arrows land on the same 48 px.
    val density = LocalDensity.current
    val exact = with(density) {
        val faceScale = (size.toPx() / 16f).roundToInt().coerceAtLeast(1)
        val target = 16 * faceScale
        val scale = (target.toFloat() / bitmap.width).roundToInt().coerceAtLeast(1)
        (bitmap.width * scale).toDp()
    }
    // The bitmap overload: it is the one that accepts a filter quality, and None is the whole
    // point - the painter overload would resample the art with a smoothing filter.
    Image(
        bitmap = bitmap,
        contentDescription = null,
        modifier = modifier.size(exact),
        filterQuality = FilterQuality.None,
    )
}

/**
 * The bare button glyph, for placing inside a control ("Next (A)"). Consoles put the glyph on
 * the action itself so the mapping is readable without a separate legend.
 */
@Composable
fun PadGlyph(button: PadButton, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    // Same drawing as the hint bar (RomM console mode): the diamond for face buttons, a keycap
    // for START / SELECT. The old pixel-art sprites stay in res/ but are no longer drawn.
    when (button) {
        PadButton.A, PadButton.B, PadButton.X, PadButton.Y -> {
            FaceButtonsGlyph(button, modifier, size = size + 6.dp, pressed = isHeld(button)); return
        }
        PadButton.START -> { KeycapGlyph("START", modifier, pressed = isHeld(button)); return }
        PadButton.SELECT -> { KeycapGlyph("SELECT", modifier, pressed = isHeld(button)); return }
        else -> Unit
    }
    val colors = RommTheme.colors
    val bg = when (button) {
        PadButton.A -> colors.green
        PadButton.B -> colors.red
        PadButton.X -> colors.blue
        PadButton.Y -> colors.gold
        else -> colors.gray
    }
    Box(modifier.size(size).background(bg, CircleShape), contentAlignment = Alignment.Center) {
        Text(
            button.glyph,
            fontSize = (size.value * 0.6f).sp,
            lineHeight = (size.value * 0.6f).sp,
            fontWeight = FontWeight.Bold,
            color = if (button == PadButton.Y) Color.Black else Color.White,
            maxLines = 1,
        )
    }
}

/**
 * RomM console-mode face buttons (`console/components/icons/FaceButtons.vue`): four dots in a
 * diamond, the one meant lit in the keycap colour, the others faint. Position, not letter, is
 * what it shows: south = A, east = B, west = X, north = Y on an Xbox-style layout, mirrored for a
 * Nintendo-style one ([com.rommmobile.app.core.input.PadLayout]).
 */
@Composable
fun FaceButtonsGlyph(
    button: PadButton,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    pressed: Boolean = false,
    tint: Color? = null,
    layout: com.rommmobile.app.core.input.FaceLayout = com.rommmobile.app.core.input.PadLayout.face,
) {
    val colors = RommTheme.colors
    val base = tint ?: colors.onSurface
    val on = if (pressed) colors.primaryLighten else base
    val off = base.copy(alpha = 0.25f)
    androidx.compose.foundation.Canvas(modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val r = 4f * u
        fun dot(x: Float, y: Float, lit: Boolean) = drawCircle(if (lit) on else off, radius = r, center = Offset(x * u, y * u))
        // Lit by position: on a Nintendo-layout pad A is the east button, B the south one...
        val nintendo = layout == com.rommmobile.app.core.input.FaceLayout.NINTENDO
        dot(12f, 4f, button == if (nintendo) PadButton.X else PadButton.Y)
        dot(12f, 20f, button == if (nintendo) PadButton.B else PadButton.A)
        dot(4f, 12f, button == if (nintendo) PadButton.Y else PadButton.X)
        dot(20f, 12f, button == if (nintendo) PadButton.A else PadButton.B)
    }
}

/** RomM console-mode keycap: a small rounded box, translucent fill and edge, monospace label. */
@Composable
fun KeycapGlyph(text: String, modifier: Modifier = Modifier, pressed: Boolean = false) {
    val colors = RommTheme.colors
    val accent = if (pressed) colors.primary.copy(alpha = 0.5f) else colors.onSurface.copy(alpha = 0.2f)
    val shape = RoundedCornerShape(4.dp)
    Text(
        text,
        fontSize = 10.sp,
        lineHeight = 12.sp,
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp,
        color = colors.onSurface,
        maxLines = 1,
        modifier = modifier
            .background(accent, shape)
            .border(1.dp, accent, shape)
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

/** One hint, RomM console style: the button's glyph, then the action in muted 12 sp text. */
@Composable
fun KeyHint(button: PadButton, label: String, modifier: Modifier = Modifier) {
    val colors = RommTheme.colors
    val held = isHeld(button)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        when (button) {
            PadButton.A, PadButton.B, PadButton.X, PadButton.Y -> FaceButtonsGlyph(button, pressed = held)
            else -> KeycapGlyph(shoulderName(button), pressed = held)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.4.sp,
            color = colors.onSurfaceMuted,
            maxLines = 1,
        )
    }
}

/** Several buttons for one action, side by side, then the label. */
@Composable
fun KeyHintGroup(buttons: List<PadButton>, label: String, modifier: Modifier = Modifier) {
    if (buttons.size == 1) { KeyHint(buttons[0], label, modifier); return }
    val colors = RommTheme.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        buttons.forEachIndexed { i, b ->
            if (i > 0) Spacer(Modifier.width(4.dp))
            when (b) {
                PadButton.A, PadButton.B, PadButton.X, PadButton.Y -> FaceButtonsGlyph(b, pressed = isHeld(b))
                else -> KeycapGlyph(shoulderName(b), pressed = isHeld(b))
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp, color = colors.onSurfaceMuted, maxLines = 1)
    }
}

/** Shoulder buttons as printed on Xbox-style pads (AYN, Retroid, Ayaneo): LB / RB / LT / RT. */
fun shoulderName(b: PadButton): String = when (b) {
    PadButton.L1 -> "LB"
    PadButton.R1 -> "RB"
    PadButton.L2 -> "LT"
    PadButton.R2 -> "RT"
    PadButton.L3 -> "LS"
    PadButton.R3 -> "RS"
    PadButton.START -> "START"
    PadButton.SELECT -> "SELECT"
    else -> b.glyph
}

@Composable
fun KeyHintBarMulti(hints: List<Pair<List<PadButton>, String>>, modifier: Modifier = Modifier) =
    KeyHintBarImpl(modifier) { hints.forEach { (b, l) -> KeyHintGroup(b, l) } }

@Composable
fun KeyHintBar(hints: List<Pair<PadButton, String>>, modifier: Modifier = Modifier) =
    KeyHintBarImpl(modifier) { hints.forEach { (b, l) -> KeyHint(b, l) } }

@Composable
private fun KeyHintBarImpl(modifier: Modifier, content: @Composable () -> Unit) {
    Row(
        // Room on every side: flush against the screen edge and the gesture area it read as
        // clipped. Same inset as the page content, plus a little.
        // With the floating bar the hints take no room in the column: they are drawn just
        // above the bar, over the grid, so the covers still run all the way down behind both.
        // Always floating, like the nav bar: it takes no room in the column and is drawn over the
        // bottom of the grid on a dark gradient, so the covers run all the way down behind it.
        // With the phone/square nav bar it sits just above that bar.
        run {
            val lift = LocalBottomOverlay.current
            val navInset = LocalNavBarInset.current
            val bg = RommTheme.colors.background
            // Standing right on the system bar (no floating nav pill in between): the fade runs on
            // down behind it, so the grid dims evenly to the bottom edge of the screen.
            // The fade always runs on down to the bottom edge, under the nav pill too when there is
            // one: a light fade that stopped at the hints left them unreadable over bright covers.
            val extend = lift
            val brush = softFadeBrush(bg, RommTheme.colors.isDark)
            // On the system bar, the hints sit just above the gesture handle rather than a whole
            // bar-height above it: the handle is a thin line at the very bottom of that inset.
            // With the floating nav pill, the overlay height includes the 24 dp fade above the pill:
            // the hints go into that fade, right on top of the pill, not above it.
            val imeUp = WindowInsets.ime.getBottom(LocalDensity.current) > 0
            val placeAt = when {
                // Keyboard up: off its top edge, not glued to it.
                imeUp -> lift + 14.dp
                lift > navInset -> (lift - 26.dp).coerceAtLeast(0.dp)
                lift > 0.dp -> (lift - 4.dp).coerceAtLeast(0.dp)
                else -> lift
            }
            modifier.fillMaxWidth()
                .layout { m, c ->
                    val p = m.measure(c.copy(minHeight = 0))
                    layout(p.width, 0) { p.place(0, -p.height - placeAt.roundToPx()) }
                }
                .drawBehind {
                    val ext = (extend - (lift - placeAt)).toPx() + (lift - placeAt).toPx()
                    drawRect(brush, size = androidx.compose.ui.geometry.Size(size.width, size.height + ext))
                }
        // The top padding is the fade's run-up: drawn inside the bar (outside it was clipped), so
        // the bar reaches that far above the hints, which then sit on the dark end of the curve.
        }.padding(start = RommTheme.layout.margin, end = RommTheme.layout.margin, top = 84.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // RomM console glyphs, straight on the gradient (the owner preferred it to RomM's black box).
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
            content()
        }
    }
}


/**
 * Text that names pad buttons by drawing them: "{A}", "{B}", "{X}", "{Y}" become the face-button
 * diamond lit on the right corner for the user's layout, "{LB}", "{RB}", "{START}", "{SELECT}"
 * the keycap. Without a pad the token is written out as its letter instead.
 */
@Composable
fun PadText(
    text: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = RommTheme.colors.onSurface,
    textAlign: TextAlign? = null,
) {
    val hasPad by com.rommmobile.app.core.input.rememberHasGamepad()
    val tokens = Regex("""\{(A|B|X|Y|LB|RB|START|SELECT)\}""")
    if (!tokens.containsMatchIn(text)) {
        Text(text, modifier, style = style, color = color, textAlign = textAlign)
        return
    }
    if (!hasPad) {
        Text(tokens.replace(text) { it.groupValues[1] }, modifier, style = style, color = color, textAlign = textAlign)
        return
    }
    val used = mutableSetOf<String>()
    val annotated = androidx.compose.ui.text.buildAnnotatedString {
        var last = 0
        tokens.findAll(text).forEach { m ->
            append(text.substring(last, m.range.first))
            val name = m.groupValues[1]
            used += name
            appendInlineContent(name, name)
            last = m.range.last + 1
        }
        append(text.substring(last))
    }
    val inline = used.associateWith { name ->
        val keycap = name.length > 1
        androidx.compose.foundation.text.InlineTextContent(
            androidx.compose.ui.text.Placeholder(
                width = if (keycap) (name.length * 0.62f + 1.4f).em else 1.5.em,
                height = 1.4.em,
                placeholderVerticalAlign = androidx.compose.ui.text.PlaceholderVerticalAlign.TextCenter,
            ),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when (name) {
                    "A" -> FaceButtonsGlyph(PadButton.A, size = 20.dp)
                    "B" -> FaceButtonsGlyph(PadButton.B, size = 20.dp)
                    "X" -> FaceButtonsGlyph(PadButton.X, size = 20.dp)
                    "Y" -> FaceButtonsGlyph(PadButton.Y, size = 20.dp)
                    else -> KeycapGlyph(name)
                }
            }
        }
    }
    Text(annotated, modifier, style = style, color = color, textAlign = textAlign, inlineContent = inline)
}


/**
 * The one bottom fade (hint bar, bottom nav, setup's Next bar, foot of the game page). Neutral
 * rather than the page colour itself: at 80 % the page's blue-violet tint read as a violet band
 * over bright covers. And it rises gently, almost nothing over the top third, instead of
 * reaching 80 % a third of the way up.
 */
fun softFadeBrush(background: Color, dark: Boolean): Brush {
    val shade = if (dark) androidx.compose.ui.graphics.lerp(background, Color.Black, 0.6f) else background
    return Brush.verticalGradient(
        // Soft at the top, dark early enough that whatever sits in the lower half (the hints)
        // reads on a near-solid ground.
        0f to shade.copy(alpha = 0f),
        0.18f to shade.copy(alpha = 0.22f),
        0.36f to shade.copy(alpha = 0.66f),
        0.52f to shade.copy(alpha = 0.9f),
        1f to shade.copy(alpha = 0.97f),
    )
}
