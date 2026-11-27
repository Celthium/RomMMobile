package com.rommmobile.app.core.design

import coil3.toBitmap
import coil3.request.allowHardware
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.composed
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.VideogameAsset
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.network.HttpException
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/* ------------------------------------------------------------------------------------------ *
 * Cover art. The rule, as in the RomM web app: a game always shows a picture. The server copy
 * first (large or small), then the external `url_cover` RomM recorded, then generated art with
 * the title on it. A grey box with a joystick icon is what this file exists to never show.
 * ------------------------------------------------------------------------------------------ */

/**
 * Walks [urls] until one loads. A 4xx (the file is not there) moves on at once; anything else
 * (timeouts on a weak handheld Wi-Fi, a tunnel hiccup) is asked again twice, spaced out, before
 * moving on. The memory-cache key changes per retry so Coil sees a new request; the disk key does
 * not, so a retry that succeeds is cached like any other load.
 */
@Composable
private fun FallbackImage(
    urls: List<String>,
    contentDescription: String?,
    contentScale: ContentScale,
    modifier: Modifier,
    crossfadeMs: Int,
    onExhausted: @Composable () -> Unit,
    onRatio: ((Float) -> Unit)? = null,
) {
    var index by remember(urls) { mutableIntStateOf(0) }
    var attempt by remember(urls) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val url = urls.getOrNull(index)
    if (url == null) {
        onExhausted()
        return
    }
    val request = remember(url, attempt) {
        ImageRequest.Builder(context).data(url)
            .apply { if (crossfadeMs > 0) crossfade(crossfadeMs) }
            .apply { if (attempt > 0) memoryCacheKey("$url#retry$attempt") }
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        onSuccess = { state ->
            val size = state.painter.intrinsicSize
            if (onRatio != null && size.width > 0f && size.height > 0f) {
                val r = size.width / size.height
                CoverRatios.put(url, r)
                onRatio(r)
            }
        },
        onError = { state ->
            val code = (state.result.throwable as? HttpException)?.response?.code
            if ((code != null && code in 400..499) || attempt >= 2) {
                attempt = 0
                index++
            } else {
                val next = attempt + 1
                scope.launch { delay(1500L * next); attempt = next }
            }
        },
    )
}

/**
 * A game cover. [urls] in order of preference, usually [com.rommmobile.app.data.repo.Rom.coverCandidates].
 * While loading the box shows the web app's cover-placeholder colour; when nothing loads, the
 * generated [DefaultCoverArt] with [title] written on it.
 */
@Composable
fun CoverImage(
    urls: List<String>,
    title: String?,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    contentScale: ContentScale = ContentScale.Crop,
    showTitleWhenMissing: Boolean = true,
    /** Receives width / height of the picture that actually loaded. */
    onRatio: ((Float) -> Unit)? = null,
) {
    val colors = RommTheme.colors
    Box(modifier.clip(shape).background(colors.coverPlaceholder)) {
        FallbackImage(
            urls = urls,
            contentDescription = title,
            contentScale = contentScale,
            modifier = Modifier.matchParentSize(),
            crossfadeMs = 180,
            onExhausted = { DefaultCoverArt(title, Modifier.matchParentSize(), showTitle = showTitleWhenMissing) },
            onRatio = onRatio,
        )
    }
}

/**
 * The most vivid colour of a picture (cover art, console icon), for tinting glows and backdrops.
 * Decoded tiny (24 px) off the main thread; pixels are weighted by saturation x value so a grey
 * box or a black background does not win. Falls back to [fallback] while loading or on failure.
 */
@Composable
fun rememberDominantColor(url: String?, fallback: Color): Color {
    val context = LocalContext.current
    var color by remember(url) { androidx.compose.runtime.mutableStateOf<Color?>(null) }
    LaunchedEffect(url) {
        if (url == null) return@LaunchedEffect
        val req = ImageRequest.Builder(context).data(url).size(24).allowHardware(false).build()
        val img = (coil3.SingletonImageLoader.get(context).execute(req) as? coil3.request.SuccessResult)?.image ?: return@LaunchedEffect
        color = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val bmp = img.toBitmap()
            var r = 0.0; var g = 0.0; var b = 0.0; var wSum = 0.0
            val hsv = FloatArray(3)
            for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
                val p = bmp.getPixel(x, y)
                if (android.graphics.Color.alpha(p) < 128) continue
                android.graphics.Color.colorToHSV(p, hsv)
                val w = (hsv[1] * hsv[1] * hsv[2]).toDouble()
                if (w <= 0.0) continue
                r += android.graphics.Color.red(p) * w; g += android.graphics.Color.green(p) * w; b += android.graphics.Color.blue(p) * w; wSum += w
            }
            if (wSum < 0.5) null else {
                // Pushed a little brighter and more saturated: it lights things, it is not paint.
                android.graphics.Color.colorToHSV(android.graphics.Color.rgb((r / wSum).toInt(), (g / wSum).toInt(), (b / wSum).toInt()), hsv)
                hsv[1] = (hsv[1] * 1.15f).coerceAtMost(1f); hsv[2] = hsv[2].coerceIn(0.65f, 1f)
                Color(android.graphics.Color.HSVToColor(hsv))
            }
        }
    }
    val target = color ?: fallback
    return androidx.compose.animation.animateColorAsState(target, tween(600), label = "dominant").value
}

/**
 * Staggered entrance for grid items: each one fades in and rises a few dp, [index] times a short
 * delay after the previous, the first time it is composed only. Items scrolled into view later
 * (index far from the first screen) come in at once, so long lists never wait on the cascade.
 */
fun Modifier.cascadeIn(index: Int, key: Any?): Modifier = composed {
    val shown = remember(key) { androidx.compose.animation.core.Animatable(if (index > 24) 1f else 0f) }
    LaunchedEffect(key) {
        if (shown.value < 1f) {
            kotlinx.coroutines.delay(40L * index.coerceAtMost(24))
            shown.animateTo(1f, tween(320, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        }
    }
    this.graphicsLayer {
        val v = shown.value
        alpha = v
        translationY = (1f - v) * 14.dp.toPx()
    }
}

/**
 * Loading sheen: a soft light band sweeping across the placeholder colour every 1.4 s, so an
 * empty frame reads as "coming" rather than "missing".
 */
fun Modifier.shimmer(base: Color, shape: Shape): Modifier = composed {
    val t by androidx.compose.animation.core.rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(tween(1400, easing = androidx.compose.animation.core.LinearEasing)),
        label = "x",
    )
    this.clip(shape).background(base).drawBehind {
        val w = size.width
        drawRect(
            Brush.linearGradient(
                0f to Color.Transparent, 0.5f to Color.White.copy(alpha = 0.07f), 1f to Color.Transparent,
                start = Offset(w * t - w * 0.4f, 0f), end = Offset(w * t + w * 0.4f, size.height * 0.3f),
            ),
        )
    }
}

/** Single-URL form kept for callers that only have one picture (download rows, mosaics). */
@Composable
fun CoverImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    contentScale: ContentScale = ContentScale.Crop,
) = CoverImage(listOfNotNull(url), contentDescription, modifier, shape, contentScale, showTitleWhenMissing = false)

/**
 * RomM's generated cover (`frontend/src/utils/covers.ts`): a #553E98 field with a #371F69 and a
 * #FF9B85 blob placed from a hash of the name, so every game gets its own but stable picture,
 * under the v2 card's black vignette with the title centred on top.
 */
@Composable
fun DefaultCoverArt(title: String?, modifier: Modifier = Modifier, showTitle: Boolean = true) {
    val seed = remember(title) { (title ?: "").hashCode() }
    BoxWithConstraints(
        modifier.drawBehind {
            val w = size.width
            val h = size.height
            val rnd = Random(seed)
            drawRect(Color(0xFF553E98))
            val c1 = Offset(w * (0.1f + rnd.nextFloat() * 0.6f), h * (0.05f + rnd.nextFloat() * 0.45f))
            val r1 = w * 0.95f
            drawCircle(Brush.radialGradient(listOf(Color(0xFF371F69), Color(0x00371F69)), center = c1, radius = r1), radius = r1, center = c1)
            val c2 = Offset(w * (0.3f + rnd.nextFloat() * 0.6f), h * (0.5f + rnd.nextFloat() * 0.45f))
            val r2 = w * 0.8f
            drawCircle(Brush.radialGradient(listOf(Color(0xCCFF9B85), Color(0x00FF9B85)), center = c2, radius = r2), radius = r2, center = c2)
            drawRect(
                Brush.radialGradient(
                    listOf(Color.Transparent, Color(0xA6000000)),
                    center = Offset(w / 2, h / 2),
                    radius = maxOf(w, h) * 0.72f,
                ),
            )
        },
        contentAlignment = Alignment.Center,
    ) {
        if (showTitle && !title.isNullOrBlank()) {
            // clamp(8px, 8cqmin, 18px) in the web app.
            val fs = (minOf(maxWidth, maxHeight).value * 0.09f).coerceIn(8f, 18f)
            Text(
                title,
                style = TextStyle(
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = fs.sp,
                    lineHeight = (fs * 1.18f).sp,
                    textAlign = TextAlign.Center,
                    shadow = Shadow(Color(0x99000000), Offset(0f, 1f), 6f),
                ),
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        } else {
            Icon(Icons.Rounded.VideogameAsset, contentDescription = null, tint = Color(0xCCF9F9F9), modifier = Modifier.size(28.dp))
        }
    }
}

/** A platform icon: `slug`, then `fs_slug`, then RomM's `default.ico`, then a Material glyph. */
@Composable
fun PlatformIcon(urls: List<String>, size: Dp, modifier: Modifier = Modifier) {
    FallbackImage(
        urls = urls,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier.size(size),
        crossfadeMs = 0,
        onExhausted = { Icon(Icons.Rounded.VideogameAsset, contentDescription = null, modifier = modifier.size(size)) },
    )
}

@Composable
fun PlatformIcon(url: String?, size: Dp, modifier: Modifier = Modifier) = PlatformIcon(listOfNotNull(url), size, modifier)

/**
 * Real width / height of covers already seen, by URL. A GBA box is nearly square, a PS2 case is
 * tall, a 3DS one is in between: the web card takes its shape from the picture, and so do ours.
 * Remembered for the process so a card scrolled back into view has the right shape at once
 * instead of opening at 2:3 and snapping.
 */
object CoverRatios {
    private val map = java.util.concurrent.ConcurrentHashMap<String, Float>()
    fun put(url: String, ratio: Float) { map[url] = ratio }
    fun firstKnown(urls: List<String>): Float? = urls.firstNotNullOfOrNull { map[it] }

    /** Cell shape last used by a list (a platform, a collection), so reopening it does not jump. */
    private val slots = java.util.concurrent.ConcurrentHashMap<String, Float>()
    fun slot(key: String): Float? = slots[key]
    fun putSlot(key: String, ratio: Float) { slots[key] = ratio }
}

/**
 * Which cover the app-wide backdrop shows. Held at the root, outside the system-bar insets, so the
 * blurred art runs up under the status bar like the web app's page background, instead of
 * stopping under it with a hard edge.
 */
/** Who asked for the backdrop, and what: the owner is per screen, so two screens showing the same
 * cover (a library and the game opened from it) cannot clear each other's request. */
class BackdropRequest(val owner: Any, val url: String?)

val LocalBackdrop = staticCompositionLocalOf<MutableState<BackdropRequest?>> { mutableStateOf(null) }

/** Draws the backdrop a screen asked for; placed once, under everything, by [RommTheme]. */
@Composable
fun BackdropHost() {
    BackdropArt(LocalBackdrop.current.value?.url)
}

/**
 * Asks the root backdrop for [url] while this composable is on screen. Leaving clears it, unless
 * another screen already took it over (the game page opened from a library, for instance).
 */
@Composable
fun SetBackdrop(url: String?) {
    val state = LocalBackdrop.current
    val owner = remember { Any() }
    DisposableEffect(url) {
        state.value = BackdropRequest(owner, url)
        onDispose { if (state.value?.owner === owner) state.value = null }
    }
}

/**
 * The v2 page backdrop: the current cover, blurred (28 px) and darkened, under two gradients
 * that fade it into the page so text on top stays readable. [url] null shows the plain page.
 * Blur needs Android 12; older systems get the same picture, dimmed harder instead.
 */
@Composable
fun BackdropArt(url: String?, modifier: Modifier = Modifier, breathe: Boolean = false, tint: Color? = null) {
    val colors = RommTheme.colors
    val bg = colors.background
    val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    // Clipped: the picture is scaled up 1.08 and blurred, and without a clip it bled past the
    // screen's safe area into the system bars as a lighter frame around the whole app.
    Box(modifier.fillMaxSize().clipToBounds().background(bg)) {
        AnimatedContent(
            targetState = url,
            transitionSpec = { fadeIn(tween(360)) togetherWith fadeOut(tween(360)) },
            label = "backdrop",
            modifier = Modifier.matchParentSize(),
        ) { target ->
            if (target != null) {
                // Ken Burns: a very slow zoom and drift (26 s each way) so even a still picture
                // keeps the screen alive. Off by default: on the main screen it would move behind
                // text the user is reading.
                val kb = if (breathe) {
                    val tr = androidx.compose.animation.core.rememberInfiniteTransition(label = "kenburns")
                    tr.animateFloat(
                        initialValue = 0f,
                        targetValue = 1f,
                        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                            tween(26_000, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
                        ),
                        label = "k",
                    ).value
                } else 0f
                AsyncImage(
                    model = target,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val zoom = 1.08f + 0.12f * kb
                            scaleX = zoom
                            scaleY = zoom
                            translationX = size.width * 0.03f * (kb - 0.5f)
                            translationY = size.height * 0.02f * (0.5f - kb)
                            alpha = if (colors.isDark) (if (canBlur) 0.45f else 0.3f) else 0.35f
                        }
                        .then(if (canBlur) Modifier.blur(28.dp) else Modifier),
                )
            } else {
                Box(Modifier.fillMaxSize())
            }
        }
        // The washes only when there is a picture under them. Over the plain page they blended
        // the background with itself: no visible change, but the dithering of those gradients
        // came out on the handheld's screen as a pattern of faint blocks.
        val washed by androidx.compose.animation.core.animateFloatAsState(
            if (url != null || tint != null) 1f else 0f, tween(360), label = "wash",
        )
        if (washed > 0f) Box(Modifier.matchParentSize().graphicsLayer { alpha = washed }) {
            // Colour wash from the art's dominant colour, strongest at the top, gone by mid-screen.
            if (tint != null) {
                Box(
                    Modifier.matchParentSize().background(
                        Brush.radialGradient(
                            listOf(tint.copy(alpha = 0.32f), tint.copy(alpha = 0.08f), Color.Transparent),
                            center = Offset.Zero.copy(x = 0f, y = 0f),
                            radius = 2200f,
                        ),
                    ),
                )
            }
            Box(
                Modifier.matchParentSize().background(
                    Brush.horizontalGradient(
                        0f to bg.copy(alpha = 0.72f),
                        0.5f to bg.copy(alpha = 0.30f),
                        1f to bg.copy(alpha = 0.55f),
                    ),
                ),
            )
            Box(
                Modifier.matchParentSize().background(
                    Brush.verticalGradient(
                        0f to bg.copy(alpha = 0.55f),
                        0.12f to bg.copy(alpha = 0.15f),
                        0.3f to bg.copy(alpha = 0f),
                        0.7f to bg.copy(alpha = 0.70f),
                        1f to bg.copy(alpha = 0.92f),
                    ),
                ),
            )
        }
    }
}
