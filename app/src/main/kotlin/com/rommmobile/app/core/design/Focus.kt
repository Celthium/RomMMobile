package com.rommmobile.app.core.design

import androidx.compose.foundation.gestures.animateScrollBy
import kotlinx.coroutines.launch
import com.rommmobile.app.core.input.LogicalButton
import com.rommmobile.app.core.input.LocalGamepad
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.foundation.shape.CircleShape

/**
 * Gamepad focus for CARDS and rail items: a short scale-up and nothing else. No ring, no glow -
 * the owner's call: a glow around every cover and every rail entry made the whole screen busy,
 * while a card that grows when you land on it is understood without being looked at. The ring
 * lives on [gamepadFocusRing], for buttons. Place BEFORE the focusable modifier (clickable /
 * focusable) in the chain so [onFocusChanged] observes that target.
 *
 * The scale runs in the draw phase via graphicsLayer, so neighbours never relayout.
 */
fun Modifier.gamepadFocus(
    shape: Shape,
    scale: Float? = null,
    ringWidthDp: Int = 2,
    onFocused: ((Boolean) -> Unit)? = null,
): Modifier = composed {
    val layout = LocalLayoutClass.current
    var focused by remember { mutableStateOf(false) }
    val target = scale ?: layout.focusScale
    val t by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = 110),
        label = "focus",
    )
    this
        .zIndex(if (focused) 1f else 0f)
        .onFocusChanged {
            if (focused != it.isFocused) {
                focused = it.isFocused
                onFocused?.invoke(it.isFocused)
            }
        }
        .graphicsLayer {
            val s = 1f + (target - 1f) * t
            scaleX = s
            scaleY = s
        }
}

/**
 * Buttons and chips. Same two signals as [gamepadFocus]: it grows a little and it gets a violet
 * edge. Nothing else - a cast shadow spilling out past the control read as a smudge rather than
 * as "you are here".
 */
fun Modifier.gamepadFocusRing(shape: Shape, ringWidthDp: Int = 2): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val t by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = 110),
        label = "focusPop",
    )
    this
        .zIndex(if (focused) 1f else 0f)
        .onFocusChanged { focused = it.isFocused }
        .graphicsLayer {
            // Barely: the ring already says where the cursor is.
            val s = 1f + 0.03f * t
            scaleX = s
            scaleY = s
        }
        .focusGlow(shape, t)
}

/**
 * The one focus decoration in the app, drawn OUTSIDE the control with a small gap: a white line
 * that follows the control's own shape, wrapped in two fading violet passes that read as a glow.
 *
 * Outside and white on purpose. A line on the edge in the button's own violet vanished on the
 * violet buttons, which is exactly where the user most needs to see it; a white ring with a gap
 * between it and the control reads on a violet fill, on a dark card and on the rail alike.
 */
private fun Modifier.focusGlow(shape: Shape, t: Float): Modifier = composed {
    val colors = LocalRommColors.current
    drawWithContent {
        drawContent()
        if (t <= 0f) return@drawWithContent
        // Kept quiet on purpose: thin line, small gap, one faint halo. The earlier double glow
        // drew more attention than the control it pointed at.
        val gap = 2.dp.toPx()
        val line = 1.5.dp.toPx()
        // Each pass is inset by a negative amount so `size` inside it is the enlarged box and the
        // outline keeps the control's proportions instead of being a bigger blob around it.
        fun ring(offset: Float, width: Float, color: Color) {
            inset(-offset) {
                drawOutline(shape.createOutline(size, layoutDirection, this), color, style = Stroke(width))
            }
        }
        ring(gap + line + 1.5.dp.toPx(), 3.dp.toPx(), colors.primaryLighten.copy(alpha = 0.14f * t))
        ring(gap + line / 2f, line, colors.white.copy(alpha = 0.85f * t))
    }
}

/**
 * Top-bar icons (back arrow, view toggle, sort, refresh, ...): a short scale-up and nothing else.
 * A ring around a 24 dp glyph in the header read as noise next to the title; growing is enough.
 */
fun Modifier.gamepadFocusIcon(scale: Float = 1.22f): Modifier = gamepadFocus(CircleShape, scale = scale)

/**
 * Full-width list rows. Growing a row that already spans its pane only pushes its left edge under
 * the screen border, so focus is shown as a fill plus a leading bar and nothing moves.
 */
fun Modifier.gamepadFocusRow(shape: Shape): Modifier = composed {
    val colors = LocalRommColors.current
    var focused by remember { mutableStateOf(false) }
    val t by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = 110),
        label = "rowFocus",
    )
    this
        .onFocusChanged { focused = it.isFocused }
        .clip(shape)
        .drawBehind {
            if (t <= 0f) return@drawBehind
            drawRect(colors.primary.copy(alpha = 0.20f * t))
            drawRect(colors.primaryLighten.copy(alpha = t), size = Size(3.dp.toPx(), size.height))
        }
}

/**
 * Single-line text fields swallow D-pad up/down (caret navigation), trapping gamepad users
 * inside the field. This moves focus out instead, and draws the usual ring.
 */
@OptIn(ExperimentalLayoutApi::class)
fun Modifier.gamepadTextField(
    shape: Shape,
    ringWidthDp: Int = 2,
    onDismissed: (() -> Unit)? = null,
    /** A on the field: return true when it was taken as "go on" (keyboard then closes). */
    onSubmit: (() -> Boolean)? = null,
): Modifier = composed {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val bus = LocalGamepad.current
    val imeVisible = WindowInsets.isImeVisible
    val resolver = androidx.compose.ui.platform.LocalContext.current.contentResolver
    this
        // Before the IME sees it, or the keyboard swallows B and the field keeps focus and simply
        // reopens it. Consumed ONLY while the keyboard is up: with it down, B has to stay a
        // normal back, or a dialog holding a text field can never be closed from the pad. And
        // focus is cleared, not moved, because moving it into a neighbouring field just pops the
        // keyboard straight back open.
        .onInterceptKeyBeforeSoftKeyboard { event ->
            // Pre-IME dispatch runs before the Activity ever sees the key, so the user's map is
            // consulted here directly: the button printed B, whatever code it reports.
            val logical = bus.logicalFor(event.nativeKeyEvent.keyCode)
            // The keyboard gets keys before the app while it is up, even pinned to the other
            // screen, and it ate the d-pad and A: focus stayed stuck in the field. Taken here:
            // up/down leave the field (closing the keyboard), A goes on or opens the keyboard.
            val isA = logical == LogicalButton.A || event.key == Key.DirectionCenter
            val isVertical = event.key == Key.DirectionDown || event.key == Key.DirectionUp
            if (isA || isVertical) {
                if (event.type == KeyEventType.KeyDown) {
                    when {
                        isVertical -> {
                            keyboard?.hide()
                            focusManager.moveFocus(if (event.key == Key.DirectionDown) FocusDirection.Down else FocusDirection.Up)
                        }
                        onSubmit?.invoke() == true -> keyboard?.hide()
                        else -> keyboard?.show()
                    }
                }
                return@onInterceptKeyBeforeSoftKeyboard true
            }
            val isB = logical == LogicalButton.B
            if (!isB && event.key != Key.Back) return@onInterceptKeyBeforeSoftKeyboard false
            // AYN Thor can pin the keyboard to the bottom screen: it is then up but on another
            // display, so this window's insets never show it and B could not close it any more.
            val imeElsewhere = runCatching { android.provider.Settings.System.getInt(resolver, "ime_show_on_second", 0) == 1 }.getOrDefault(false)
            if (!imeVisible && !imeElsewhere) return@onInterceptKeyBeforeSoftKeyboard false
            if (event.type == KeyEventType.KeyDown) {
                keyboard?.hide()
                focusManager.clearFocus()
                // The owner may want to send the pad somewhere now that focus sits on nothing.
                onDismissed?.invoke()
            }
            true
        }
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            // A on a field whose keyboard was put away: bring it back. Without this, once the
            // keyboard was closed from its own button the field kept focus and the pad had no
            // way to reopen it.
            if (bus.logicalFor(event.nativeKeyEvent.keyCode) == LogicalButton.A || event.key == Key.DirectionCenter) {
                keyboard?.show()
                return@onPreviewKeyEvent true
            }
            when (event.key) {
                Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                else -> false
            }
        }
    // No ring here: the field already answers focus with its own coloured border (Material
    // OutlinedTextField), and a second outline around a search box was one too many.
}


/**
 * D-pad scrolling for a page made mostly of text (a game's overview): down moves focus to the
 * next control when there is one, otherwise scrolls the page a step; up first scrolls back what
 * the d-pad scrolled, then moves focus as usual. Without it the cursor stopped on the tabs and
 * the description under them could not be read from the pad.
 */
fun Modifier.dpadScroll(state: androidx.compose.foundation.ScrollState, step: androidx.compose.ui.unit.Dp = 160.dp): Modifier = composed {
    val focusManager = LocalFocusManager.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current
    // How far the d-pad (not focus) has scrolled the page: up gives that back first.
    var extra by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val px = with(density) { step.toPx() }
        when (e.key) {
            Key.DirectionDown -> when {
                extra == 0f && focusManager.moveFocus(FocusDirection.Down) -> true
                state.canScrollForward -> {
                    val d = px.coerceAtMost((state.maxValue - state.value).toFloat())
                    extra += d
                    scope.launch { state.animateScrollBy(d) }
                    true
                }
                // End of the page: swallowed. Let through, the focus search wrapped round to the
                // back arrow at the top, off a page scrolled down, and up could not undo it.
                extra > 0f || !state.canScrollForward -> true
                else -> false
            }
            Key.DirectionUp -> if (extra > 0f) {
                val d = extra.coerceAtMost(px)
                extra -= d
                scope.launch { state.animateScrollBy(-d) }
                true
            } else false
            else -> false
        }
    }
}
