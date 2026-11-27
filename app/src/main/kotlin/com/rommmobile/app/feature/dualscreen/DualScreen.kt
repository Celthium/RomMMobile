@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.rommmobile.app.feature.dualscreen

import kotlinx.coroutines.flow.stateIn
import androidx.compose.foundation.layout.offset
import com.rommmobile.app.core.design.rememberDominantColor
import com.rommmobile.app.core.design.shimmer
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.draw.drawBehind
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.rounded.Star
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.only
import android.os.Build
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.activity.enableEdgeToEdge
import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.VideogameAsset
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.ComposeView
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.rommmobile.app.R
import com.rommmobile.app.core.design.ArtShapeLarge
import com.rommmobile.app.core.design.BackdropArt
import com.rommmobile.app.core.design.CoverImage
import com.rommmobile.app.core.design.CoverRatios
import com.rommmobile.app.core.design.RommTheme
import com.rommmobile.app.core.design.ThemeMode
import com.rommmobile.app.core.util.Format
import com.rommmobile.app.data.repo.Rom
import com.rommmobile.app.data.repo.RomRepository
import com.rommmobile.app.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dual-screen handhelds (AYN Thor, Ayaneo Pocket DS...): while the app runs on one screen, the
 * other shows the game under the cursor - cover, screenshots cycling, title and summary - the way
 * a DS shows the map below the action. Screens publish what is focused with [show]; the
 * secondary display only reads [rom].
 *
 * List items carry no screenshots or summary, so the detail is fetched once the cursor has
 * settled on a game, and kept for the session.
 */
@Singleton
class DualScreenController @Inject constructor(
    private val roms: RomRepository,
    @ApplicationScope private val scope: CoroutineScope,
    /** Credentials only towards the user's own hosts: same client as covers, used for videos. */
    @com.rommmobile.app.di.ImageClient val http: okhttp3.OkHttpClient,
    settings: com.rommmobile.app.data.prefs.SettingsStore,
    private val api: com.rommmobile.app.data.api.RommApi,
    private val serverStore: com.rommmobile.app.core.network.ServerStore,
) {
    /** The server's totals, for the other screen while nothing is selected; null until read. */
    private val _stats = MutableStateFlow<ServerOverview?>(null)
    val stats: StateFlow<ServerOverview?> = _stats.asStateFlow()
    @Volatile private var statsAt = 0L

    /** Reads `/api/stats` again when the overview shows and the last read is a few minutes old. */
    fun refreshStats() {
        val now = System.currentTimeMillis()
        if (now - statsAt < 5 * 60_000L && _stats.value != null) return
        statsAt = now
        scope.launch {
            runCatching { api.stats() }.onSuccess { st ->
                val cfg = serverStore.config.value
                _stats.value = ServerOverview(st, cfg.serverVersion, cfg.activeUrl?.substringAfter("://")?.trimEnd('/'))
            }.onFailure { statsAt = 0L }
        }
    }

    /** What the info block shows (Settings > Appearance). */
    val preview: StateFlow<com.rommmobile.app.data.prefs.PreviewOptions> =
        settings.preview.stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, com.rommmobile.app.data.prefs.PreviewOptions())

    private val _rom = MutableStateFlow<Rom?>(null)
    val rom: StateFlow<Rom?> = _rom.asStateFlow()

    /** Platform under the cursor on the Platforms screen; cleared as soon as a game is shown. */
    private val _platform = MutableStateFlow<PlatformSheet?>(null)
    val platform: StateFlow<PlatformSheet?> = _platform.asStateFlow()

    /** The Platforms screen left: drop its sheet so the other screen does not show a stale one. */
    fun clearPlatform() { _platform.value = null }

    fun showPlatform(p: com.rommmobile.app.data.repo.Platform, base: String?) {
        _platform.value = PlatformSheet(p.id, p.name, p.iconUrls(base), p.romCount, p.sizeBytes, p.firmwareCount)
    }

    /** True while a second screen is actually showing the companion: no fetching otherwise. */
    private val _active = MutableStateFlow(false)
    /** Observable form of [active]: screens hide what the other screen already shows. */
    val activeState: StateFlow<Boolean> = _active.asStateFlow()
    var active: Boolean
        get() = _active.value
        set(v) { _active.value = v }

    /**
     * Whether [CompanionActivity] should stay open. The app sets it; the activity, on its own
     * display, finishes as soon as it turns false (app left, option switched off, screen gone).
     */
    val activityWanted = MutableStateFlow(false)

    /** True while the setup wizard is on screen: the other screen shows a "setting up" card. */
    val setupMode = MutableStateFlow(false)

    /**
     * Brings the app's own screen back to the front of input. Set by MainActivity. Android gives
     * keyboard focus to the display that last started an activity; left on the companion, every
     * button press went to a window with nothing to do with it and the system killed the app as
     * not responding after five seconds.
     */
    @Volatile var focusBack: (() -> Unit)? = null

    /**
     * Delivers a key or stick event to the app's screen. Some systems keep input on the display
     * that last opened an activity whatever is asked; the companion then just passes every press
     * on, and the pad keeps driving the app as if nothing had happened.
     */
    @Volatile var forwardKey: ((android.view.KeyEvent) -> Boolean)? = null
    @Volatile var forwardMotion: ((android.view.MotionEvent) -> Boolean)? = null

    private val cache = android.util.LruCache<Int, Rom>(96)
    private var job: Job? = null

    fun show(rom: Rom?) {
        if (rom == null) return
        _platform.value = null
        val cached = cache.get(rom.id)
        _rom.value = cached ?: rom
        if (cached != null || !active) return
        job?.cancel()
        job = scope.launch {
            // Only once the cursor rests: browsing a row with the d-pad must not fire a request
            // per game passed on the way.
            delay(700)
            roms.detail(rom.id).onSuccess { base ->
                // The grid shows one version of a game (RomM groups them); its clip may sit on
                // another version. Borrow it, but only when this one has none, and from two
                // versions at most: each is a full detail request on the server.
                var full = base
                if (base.siblings.isNotEmpty() && base.videos.isEmpty()) {
                    val more = base.siblings.take(2).mapNotNull { sib -> roms.detail(sib.id).getOrNull() }
                    full = base.copy(
                        videos = (base.videos + more.flatMap { it.videos }).distinct(),
                        screenshots = (base.screenshots + more.flatMap { it.screenshots }).distinct(),
                        logo = base.logo ?: more.firstNotNullOfOrNull { it.logo },
                        players = base.players ?: more.firstNotNullOfOrNull { it.players },
                    )
                }
                cache.put(full.id, full)
                if (_rom.value?.id == full.id) _rom.value = full
            }
        }
    }
}

/** Lets any screen reach the controller without threading it through every ViewModel. */
val LocalDualScreen = staticCompositionLocalOf<DualScreenController?> { null }

/**
 * The window on the other screen. A [Presentation] is a dialog bound to a display; its Compose
 * content borrows the activity's lifecycle and state owners, so it lives and dies with the app.
 */
class CompanionPresentation(
    private val activity: ComponentActivity,
    display: Display,
    private val controller: DualScreenController,
    private val themeMode: () -> ThemeMode,
) : Presentation(activity, display) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge to edge on its display: without this the window stopped above that screen's
        // system-bar area and left a black band at the bottom.
        window?.let { w ->
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, false)
            w.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
            w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            if (Build.VERSION.SDK_INT >= 28) {
                w.attributes = w.attributes.apply {
                    layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }
        // A display context is built from the system configuration, not the app's: an in-app
        // language choice (Android 13+ per-app locale) was lost and the sheet came out in English.
        val localized = context.createConfigurationContext(
            android.content.res.Configuration(context.resources.configuration).apply {
                setLocales(activity.resources.configuration.locales)
            },
        )
        val view = ComposeView(localized).apply {
            setViewTreeLifecycleOwner(activity)
            setViewTreeViewModelStoreOwner(activity)
            setViewTreeSavedStateRegistryOwner(activity)
            setContent {
                RommTheme(themeMode = themeMode()) {
                    CompanionScreen(controller)
                }
            }
        }
        setContentView(view)
    }
}

/**
 * The companion as a full activity, for the case a [Presentation] cannot cover: the app running
 * on the SECOND screen and the sheet wanted on the main one (Android only puts presentations on
 * non-default displays). Started on that display by MainActivity. It never takes focus, so the
 * pad keeps driving the app on the other screen, and it closes itself once no longer wanted.
 */
@dagger.hilt.android.AndroidEntryPoint
class CompanionActivity : ComponentActivity() {
    @Inject lateinit var controller: DualScreenController
    @Inject lateinit var settings: com.rommmobile.app.data.prefs.SettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Display-only. The window stays focusable on purpose: a non-focusable activity that ends
        // up holding its display's focus makes Android wait for a window that never comes, and
        // the whole app is killed as not responding. Instead, focus is handed straight back.
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
        )
        // Edge to edge under transparent bars. Not immersive: hiding the bars made Android show its
        // "Viewing full screen" lesson right over the sheet.
        enableEdgeToEdge()
        lifecycleScope.launch {
            controller.activityWanted.collect { wanted -> if (!wanted) finish() }
        }
        setContent {
            val theme by settings.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.DARK)
            RommTheme(themeMode = theme) {
                CompanionScreen(controller)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        controller.active = true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) controller.focusBack?.invoke()
    }

    /** A press that lands here belongs to the app on the other screen: pass it on. */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) {
            return super.dispatchKeyEvent(event)
        }
        controller.forwardKey?.invoke(event) ?: controller.focusBack?.invoke()
        return true
    }

    override fun onGenericMotionEvent(event: android.view.MotionEvent): Boolean =
        controller.forwardMotion?.invoke(event) ?: super.onGenericMotionEvent(event)
}

/** What the companion shows for a platform: its icon, name and library numbers. */
data class PlatformSheet(val id: Int, val name: String, val icons: List<String>, val games: Int, val sizeBytes: Long, val bios: Int)

@Composable
private fun CompanionScreen(controller: DualScreenController) {
    val rom by controller.rom.collectAsStateWithLifecycle()
    val platform by controller.platform.collectAsStateWithLifecycle()
    val setup by controller.setupMode.collectAsStateWithLifecycle()
    val preview by controller.preview.collectAsStateWithLifecycle()
    // A platform wins while one is set (the Platforms screen); opening a library or a game clears it.
    val content: Any? = platform ?: rom
    Box(Modifier.fillMaxSize()) {
        val artUrl = if (platform == null) rom?.let { it.coverSmall ?: it.coverLarge } else platform?.icons?.firstOrNull()
        val tint = rememberDominantColor(artUrl, RommTheme.colors.primary)
        BackdropArt(if (platform == null) rom?.let { it.coverSmall ?: it.coverLarge } else null, tint = tint)
        AnimatedContent(
            targetState = content,
            contentKey = { when (it) { is PlatformSheet -> "p" + it.id; is Rom -> "r" + it.id; else -> "none" } },
            transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(260)) },
            label = "companion",
            // Display-only screen: nothing to tap under the bottom bar, so the sheet keeps the same
            // margin top and bottom. Only the sides and the top (cutouts, status bar) are avoided.
            modifier = Modifier.fillMaxSize().windowInsetsPadding(
                WindowInsets.safeDrawing.only(androidx.compose.foundation.layout.WindowInsetsSides.Horizontal + androidx.compose.foundation.layout.WindowInsetsSides.Top),
            ),
        ) { c ->
            when (c) {
                is PlatformSheet -> PlatformSheetView(c, this)
                is Rom -> GameSheet(c, controller.http, this, preview)
                else -> if (setup) SetupIdle() else Idle(controller)
            }
        }
    }
}

/** Platforms screen: the console's icon large, its name, and the numbers of its library. */
@Composable
private fun PlatformSheetView(p: PlatformSheet, scope: androidx.compose.animation.AnimatedVisibilityScope) {
    val colors = RommTheme.colors
    BoxWithConstraints(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        val iconSize = (minOf(maxWidth, maxHeight) * 0.42f).coerceIn(96.dp, 260.dp)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = with(scope) {
                Modifier.animateEnterExit(
                    enter = androidx.compose.animation.slideInVertically(tween(380, delayMillis = 80)) { it / 6 } + fadeIn(tween(300, delayMillis = 80)),
                    exit = fadeOut(tween(160)),
                )
            },
        ) {
            // Gentle float: the console bobs up and down and tilts a hair, with a soft shadow on
            // the "floor" that shrinks as it rises. Slow (3.6 s) so it reads as alive, not busy.
            val float = androidx.compose.animation.core.rememberInfiniteTransition(label = "float")
            val t by float.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                    tween(3600, easing = androidx.compose.animation.core.LinearEasing),
                ),
                label = "t",
            )
            val wave: Float = kotlin.math.sin(t * 2f * Math.PI.toFloat())
            // The floor glow takes the console's own colour (purple GBA, red 3DS...).
            val primaryGlow = rememberDominantColor(p.icons.firstOrNull(), RommTheme.colors.primary)
            val lift = with(androidx.compose.ui.platform.LocalDensity.current) { (iconSize * 0.05f).toPx() }
            Box(contentAlignment = Alignment.Center) {
                com.rommmobile.app.core.design.PlatformIcon(
                    p.icons,
                    size = iconSize,
                    modifier = Modifier.graphicsLayer {
                        translationY = -lift * (0.5f + 0.5f * wave)
                        rotationZ = 1.2f * kotlin.math.sin(t * 2f * Math.PI.toFloat() + 0.9f).toFloat()
                    },
                )
            }
            Box(
                Modifier
                    .padding(top = 6.dp)
                    .width(iconSize * 0.7f)
                    .height(18.dp)
                    .graphicsLayer {
                        val k = 1f - 0.18f * (0.5f + 0.5f * wave)
                        scaleX = k
                        alpha = 0.55f * k
                    }
                    // An elliptical glow as wide as the box: a radial gradient squashed vertically.
                    .drawBehind {
                        val glow = primaryGlow
                        scale(scaleX = 1f, scaleY = size.height / size.width, pivot = center) {
                            drawCircle(
                                androidx.compose.ui.graphics.Brush.radialGradient(
                                    listOf(glow.copy(alpha = 0.6f), glow.copy(alpha = 0.18f), Color.Transparent),
                                    center = center,
                                    radius = size.width / 2f,
                                ),
                                radius = size.width / 2f,
                                center = center,
                            )
                        }
                    },
            )
            Spacer(Modifier.height(20.dp))
            Text(
                p.name,
                style = MaterialTheme.typography.headlineMedium.copy(shadow = Shadow(Color(0x80000000), Offset(0f, 2f), 16f)),
                color = colors.onBackground,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatChip(androidx.compose.ui.res.pluralStringResource(R.plurals.games_count, p.games, p.games))
                if (p.sizeBytes > 0) StatChip(Format.bytes(p.sizeBytes))
                if (p.bios > 0) StatChip(stringResource(R.string.dual_bios_count, p.bios))
            }
        }
    }
}

@Composable
private fun StatChip(text: String) {
    val colors = RommTheme.colors
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(999.dp)
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = colors.onSurfaceSecondary,
        modifier = Modifier
            .background(colors.surface.copy(alpha = 0.85f), shape)
            .border(1.dp, colors.borderStrong, shape)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

/**
 * During the setup wizard: "setting up" over a slow parade of pad buttons drifting across the
 * screen in two rows going opposite ways, each bobbing a little out of step with its neighbours.
 */
@Composable
private fun SetupIdle() {
    val colors = RommTheme.colors
    val tr = androidx.compose.animation.core.rememberInfiniteTransition(label = "setup")
    val t by tr.animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(tween(16_000, easing = androidx.compose.animation.core.LinearEasing)), label = "x")
    val bob by tr.animateFloat(0f, (2 * Math.PI).toFloat(), androidx.compose.animation.core.infiniteRepeatable(tween(3_200, easing = androidx.compose.animation.core.LinearEasing)), label = "b")
    val glyphs: List<@Composable () -> Unit> = listOf(
        { com.rommmobile.app.core.design.KeycapGlyph("LB") },
        { com.rommmobile.app.core.design.FaceButtonsGlyph(com.rommmobile.app.core.design.PadButton.A, size = 34.dp) },
        { com.rommmobile.app.core.design.KeycapGlyph("START") },
        { com.rommmobile.app.core.design.KeycapGlyph("RB") },
        { com.rommmobile.app.core.design.FaceButtonsGlyph(com.rommmobile.app.core.design.PadButton.Y, size = 34.dp) },
        { com.rommmobile.app.core.design.KeycapGlyph("SELECT") },
        { com.rommmobile.app.core.design.FaceButtonsGlyph(com.rommmobile.app.core.design.PadButton.B, size = 34.dp) },
        { com.rommmobile.app.core.design.KeycapGlyph("LT") },
        { com.rommmobile.app.core.design.FaceButtonsGlyph(com.rommmobile.app.core.design.PadButton.X, size = 34.dp) },
        { com.rommmobile.app.core.design.KeycapGlyph("RT") },
    )
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth
        val spacing = 120.dp
        val band = spacing * glyphs.size
        for (row in 0..1) {
            val dir = if (row == 0) 1f else -1f
            val y = maxHeight * (if (row == 0) 0.22f else 0.78f)
            for (i in glyphs.indices) {
                for (copy in 0..1) {
                    // Two copies of the band end to end: as one leaves on a side the other enters.
                    var x = spacing * i + band * copy - band * ((t * dir).mod(1f))
                    if (dir < 0) x = w - x
                    val dy = (6 * kotlin.math.sin(bob + i * 0.9f + row)).dp
                    Box(
                        Modifier.offset(x = x - 20.dp, y = y + dy - 17.dp)
                            .graphicsLayer { alpha = 0.55f },
                    ) { glyphs[(i + row * 3) % glyphs.size]() }
                }
            }
        }
        Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(R.string.dual_setup),
                style = MaterialTheme.typography.headlineSmall.copy(shadow = Shadow(Color(0x80000000), Offset(0f, 2f), 12f)),
                color = colors.onBackground,
            )
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.dual_setup_hint), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceMuted)
        }
    }
}

@Composable
private fun Idle(controller: DualScreenController) {
    val colors = RommTheme.colors
    val stats by controller.stats.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { controller.refreshStats() }
    val st = stats
    if (st == null) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Rounded.SportsEsports, null, tint = colors.onSurfaceMuted, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.dual_idle), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceSecondary)
        }
        return
    }
    ServerOverviewView(st)
}

/** What the other screen shows while nothing is selected: the RomM library at a glance. */
data class ServerOverview(val stats: com.rommmobile.app.data.api.StatsDto, val version: String?, val host: String?)

@Composable
private fun ServerOverviewView(o: ServerOverview) {
    val colors = RommTheme.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    val nf = remember { java.text.NumberFormat.getIntegerInstance() }
    val s = o.stats
    val tiles = listOf(
        Triple(Icons.Rounded.SportsEsports, nf.format(s.platforms), R.string.dual_stat_platforms),
        Triple(Icons.Rounded.VideogameAsset, nf.format(s.roms), R.string.dual_stat_games),
        Triple(Icons.Rounded.Storage, android.text.format.Formatter.formatShortFileSize(context, s.totalBytes), R.string.dual_stat_size),
        Triple(Icons.Rounded.Save, nf.format(s.saves), R.string.dual_stat_saves),
        Triple(Icons.Rounded.PhotoCamera, nf.format(s.states), R.string.dual_stat_states),
        Triple(Icons.Rounded.Image, nf.format(s.screenshots), R.string.dual_stat_screenshots),
    )
    BoxWithConstraints(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        val columns = if (maxWidth > maxHeight * 1.3f) 3 else 2
        Column(Modifier.widthIn(max = 720.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(R.string.dual_stats_title),
                style = MaterialTheme.typography.headlineSmall.copy(shadow = Shadow(Color(0x80000000), Offset(0f, 2f), 12f)),
                color = colors.onBackground,
            )
            listOfNotNull(o.version?.let { "RomM $it" }, o.host).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceMuted)
            }
            Spacer(Modifier.height(20.dp))
            tiles.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { (icon, value, label) ->
                        Column(
                            Modifier.weight(1f).clip(MaterialTheme.shapes.large).background(colors.surface).padding(horizontal = 16.dp, vertical = 14.dp),
                        ) {
                            Icon(icon, null, tint = colors.primaryLighten, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.height(8.dp))
                            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = colors.onSurface, maxLines = 1)
                            Text(stringResource(label), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceMuted, maxLines = 1)
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.dual_stats_hint), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceMuted)
        }
    }
}

/**
 * Screenshots cycling every few seconds (the media the web shows on hover), the cover, title, meta
 * line and summary. Stacked on a squarish panel (the Thor's bottom screen), side by side on a wide
 * one (its 16:9 top screen): screenshots on the left, the text on the right.
 */
/** One slide of the companion carousel. */
private sealed interface Media {
    val url: String
    data class Video(override val url: String) : Media
    data class Image(override val url: String) : Media
}

@Composable
private fun GameSheet(rom: Rom, http: okhttp3.OkHttpClient, scope: androidx.compose.animation.AnimatedVisibilityScope, preview: com.rommmobile.app.data.prefs.PreviewOptions) {
    // Videos first (the clip is the closest thing to seeing the game run), then screenshots. An
    // image stays 4 s; a video plays to its end (capped at 40 s) before the carousel moves on.
    val media = remember(rom.id, rom.videos, rom.screenshots) {
        rom.videos.map { Media.Video(it) } + rom.screenshots.map { Media.Image(it) }
    }
    var index by remember(rom.id) { mutableIntStateOf(0) }
    var broken by remember(rom.id) { mutableStateOf(emptySet<String>()) }
    val playable = remember(media, broken) { media.filterNot { it.url in broken } }
    val current = playable.getOrNull(index.coerceIn(0, (playable.size - 1).coerceAtLeast(0)))
    fun next() { if (playable.size > 1) index = (index + 1) % playable.size }
    fun prev() { if (playable.size > 1) index = (index - 1 + playable.size) % playable.size }
    LaunchedEffect(rom.id, current, playable.size) {
        when (current) {
            is Media.Image -> if (playable.size > 1) { delay(4000); next() }
            is Media.Video -> if (playable.size > 1) { delay(40_000); next() }
            null -> Unit
        }
    }
    // Carousel position, drawn ON the picture (top centre), so it sits in the same spot whatever
    // the media's shape. Only when there is more than one slide; the current dot is wider.
    val dots: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {
        if (playable.size > 1) {
            val pos = index.coerceIn(0, playable.lastIndex)
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp)
                    .background(Color.Black.copy(alpha = 0.45f), androidx.compose.foundation.shape.RoundedCornerShape(999.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                playable.take(12).forEachIndexed { i, _ ->
                    val active = i == pos
                    val w by androidx.compose.animation.core.animateDpAsState(if (active) 16.dp else 6.dp, tween(220), label = "dot")
                    Box(
                        Modifier.width(w).height(6.dp).background(
                            if (active) Color.White else Color.White.copy(alpha = 0.45f),
                            androidx.compose.foundation.shape.RoundedCornerShape(999.dp),
                        ),
                    )
                }
            }
        }
    }
    val slide: @Composable () -> Unit = {
        when (val m = current) {
            is Media.Video -> {
                // The clip starts only after 1.5 s on the same game: a picture first, so a quick
                // pass over a row opens no video stream at all.
                var warm by remember(m.url) { mutableStateOf(false) }
                LaunchedEffect(m.url) { delay(1_500); warm = true }
                if (warm) {
                    VideoClip(
                        url = m.url,
                        http = http,
                        loop = playable.size == 1,
                        onEnded = { next() },
                        onError = { broken = broken + m.url },
                        overlay = dots,
                    )
                } else {
                    val still = playable.firstOrNull { it is Media.Image } as? Media.Image
                    if (still != null) Shot(still.url, overlay = dots) else Box(Modifier.fillMaxSize())
                }
            }
            is Media.Image -> Shot(m.url, overlay = dots)
            null -> Unit
        }
    }
    // A tap anywhere hides the info block to watch the clip or the picture full size; another
    // brings it back. Per game: the next one opens with its block shown.
    var infoShown by remember(rom.id) { mutableStateOf(true) }
    // The picture fills the screen; the title and summary float over its lower part on a dark
    // gradient panel, inset from the sides so the art still shows around it.
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            // Swipe left / right flips the carousel (still a cross-fade); a swipe resets the
            // auto-advance timer, since the slide changed.
            .pointerInput(playable.size) {
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = {
                        val threshold = 48.dp.toPx()
                        if (total < -threshold) next() else if (total > threshold) prev()
                    },
                ) { change, dx -> change.consume(); total += dx }
            }
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
            ) { infoShown = !infoShown }
            .padding(RommTheme.layout.margin),
    ) {
        val wide = maxWidth / maxHeight > 1.4f
        if (current != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { slide() }
        } else if (rom.files.isEmpty()) {
            // Detail (screenshots, clip) still on its way: hold the media frame with a sheen
            // instead of a bare backdrop, so the sheet does not jump when the picture lands.
            Box(
                Modifier.fillMaxSize()
                    .shimmer(RommTheme.colors.coverPlaceholder.copy(alpha = 0.85f), MaterialTheme.shapes.large),
            )
        }
        // The block slides down out of view as the game changes and back up once the new picture
        // is on screen, instead of the next title appearing before its image. It slides inside
        // the picture's frame: clipped to its rounded edge, it disappears behind the frame's
        // border instead of travelling over the margin below.
        Box(Modifier.matchParentSize().clip(MaterialTheme.shapes.large)) {
        androidx.compose.animation.AnimatedVisibility(
            visible = infoShown && preview.visible,
            enter = androidx.compose.animation.slideInVertically(tween(320)) { it / 2 } + fadeIn(tween(260)),
            exit = androidx.compose.animation.slideOutVertically(tween(260)) { it / 2 } + fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Box(
                with(scope) {
                    Modifier.animateEnterExit(
                        enter = androidx.compose.animation.slideInVertically(tween(380, delayMillis = 140)) { it / 2 } + fadeIn(tween(300, delayMillis = 140)),
                        exit = androidx.compose.animation.slideOutVertically(tween(220)) { it / 2 } + fadeOut(tween(180)),
                    )
                }
                    .padding(14.dp)
                    .then(if (wide) Modifier.fillMaxWidth(0.55f) else Modifier.fillMaxWidth()),
            ) {
                InfoPanel {
                    Info(
                        rom,
                        maxSummaryLines = if (current == null) 10 else if (wide) 4 else 3,
                        coverWidth = if (current == null) 130.dp else if (wide) 90.dp else 70.dp,
                        preview = preview,
                    )
                }
            }
        }
        }
    }
}

@Composable
private fun Shot(url: String, overlay: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {}) {
    // The frame is there from the first frame (placeholder + sheen); the picture fades in over it.
    Box(Modifier.fillMaxSize().shimmer(RommTheme.colors.coverPlaceholder, MaterialTheme.shapes.large)) {
    AnimatedContent(
        targetState = url,
        transitionSpec = { fadeIn(tween(500)) togetherWith fadeOut(tween(500)) },
        label = "shot",
    ) { u ->
        Box(Modifier.fillMaxSize().shadow(16.dp, MaterialTheme.shapes.large).clip(MaterialTheme.shapes.large)) {
        CoverImage(
            listOf(u), null,
            Modifier.fillMaxSize(),
            shape = MaterialTheme.shapes.large,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            showTitleWhenMissing = false,
        )
        }
    }
    overlay()
    }
}

/**
 * A muted, autoplaying clip (Media3 ExoPlayer on a TextureView, so it can be clipped and faded
 * like an image). Fetched through the app's OkHttp client: a trailer stored as a game file needs
 * the session token, a scraped clip under /assets does not.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
/** One on-disk cache for every clip the companion plays: 150 MB, oldest dropped first. */
private object VideoCache {
    @Volatile private var cache: androidx.media3.datasource.cache.SimpleCache? = null
    fun get(context: android.content.Context): androidx.media3.datasource.cache.SimpleCache =
        cache ?: synchronized(this) {
            cache ?: androidx.media3.datasource.cache.SimpleCache(
                java.io.File(context.cacheDir, "companion_video"),
                androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor(150L * 1024 * 1024),
                androidx.media3.database.StandaloneDatabaseProvider(context.applicationContext),
            ).also { cache = it }
        }
}

@Composable
private fun VideoClip(
    url: String,
    http: okhttp3.OkHttpClient,
    loop: Boolean,
    onEnded: () -> Unit,
    onError: () -> Unit,
    overlay: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var ratio by remember(url) { androidx.compose.runtime.mutableFloatStateOf(16f / 9f) }
    val ended by androidx.compose.runtime.rememberUpdatedState(onEnded)
    val failed by androidx.compose.runtime.rememberUpdatedState(onError)
    val player = remember(url) {
        val upstream = androidx.media3.datasource.DefaultDataSource.Factory(context, androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(http))
        androidx.media3.exoplayer.ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                    // Through a disk cache: coming back to a game replays its clip from the card
                    // instead of downloading it from the server again.
                    androidx.media3.datasource.cache.CacheDataSource.Factory()
                        .setCache(VideoCache.get(context))
                        .setUpstreamDataSourceFactory(upstream)
                        .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR),
                ),
            )
            // A preview, not a film: 15 s ahead at most instead of ExoPlayer's 50, so passing
            // over a game does not pull a whole trailer off the server.
            .setLoadControl(
                androidx.media3.exoplayer.DefaultLoadControl.Builder()
                    .setBufferDurationsMs(4_000, 15_000, 1_000, 2_000)
                    .build(),
            )
            .build()
            .apply {
                volume = 0f
                setMediaItem(androidx.media3.common.MediaItem.fromUri(url))
                prepare()
                playWhenReady = true
            }
    }
    androidx.compose.runtime.SideEffect {
        player.repeatMode = if (loop) androidx.media3.common.Player.REPEAT_MODE_ONE else androidx.media3.common.Player.REPEAT_MODE_OFF
    }
    androidx.compose.runtime.DisposableEffect(player) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == androidx.media3.common.Player.STATE_ENDED) ended()
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) { failed() }
            override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) {
                if (size.width > 0 && size.height > 0) ratio = size.width * size.pixelWidthHeightRatio / size.height
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    val shape = MaterialTheme.shapes.large
    // Fills the slide like the screenshots do (centre crop), so switching from a clip to a
    // picture keeps the same frame instead of jumping to a letterboxed 16:9 box.
    Box(Modifier.fillMaxSize().shadow(16.dp, shape).clip(shape).background(Color.Black)) {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            val boxRatio = maxWidth / maxHeight
            val r = ratio.coerceIn(0.5f, 2.5f)
            // Scale the video up until it covers the box, then let the clip cut the overflow.
            val (w, h) = if (r > boxRatio) (maxHeight * r) to maxHeight else maxWidth to (maxWidth / r)
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { ctx -> android.view.TextureView(ctx).also { player.setVideoTextureView(it) } },
                modifier = Modifier.align(Alignment.Center).requiredSize(w, h),
            )
        }
        overlay()
    }
}

/** Dark panel behind the title and summary: readable over any backdrop, inset from the edges. */
@Composable
private fun InfoPanel(content: @Composable () -> Unit) {
    val colors = RommTheme.colors
    val shape = MaterialTheme.shapes.large
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    0f to colors.toplayer.copy(alpha = 0.84f),
                    1f to colors.toplayer.copy(alpha = 0.95f),
                ),
            )
            .border(1.dp, colors.border, shape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) { content() }
}

@Composable
private fun Info(rom: Rom, maxSummaryLines: Int, coverWidth: androidx.compose.ui.unit.Dp, preview: com.rommmobile.app.data.prefs.PreviewOptions = com.rommmobile.app.data.prefs.PreviewOptions()) {
    val colors = RommTheme.colors
    Row(verticalAlignment = Alignment.Top) {
        // No box art in here any more: the logo (known only once the full detail arrives) came in
        // after it, so every change of game flashed the box before the logo. The logo, or the
        // title in large type when the game has none.
        // Centred: logo or title, chips and summary on one axis down the middle of the block.
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            // Narrow panel (under a picture on a square screen): a smaller title on two lines, so the
            // platform and year line still fits instead of being pushed out.
            val compact = maxSummaryLines <= 4
            val titleText: @Composable () -> Unit = {
                Text(
                    rom.name,
                    style = (if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium)
                        .copy(shadow = Shadow(Color(0x80000000), Offset(0f, 2f), 12f)),
                    color = colors.onBackground,
                    maxLines = if (compact) 2 else 3,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            // The scraped logo stands in for the title, as in the web header; the text comes back
            // while it loads and if it fails.
            if (!preview.title) {
                // Title hidden in Settings.
            } else if (rom.logo != null) {
                coil3.compose.SubcomposeAsyncImage(
                    model = rom.logo,
                    contentDescription = rom.name,
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    alignment = Alignment.Center,
                    modifier = Modifier.height(if (compact) 52.dp else 72.dp).widthIn(max = 320.dp),
                    loading = { titleText() },
                    error = { titleText() },
                )
            } else {
                titleText()
            }
            if (preview.title && preview.tags) Spacer(Modifier.height(8.dp))
            if (preview.tags) androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MetaPill(rom.platformName)
                Format.year(rom.firstReleaseDate)?.let { MetaPill(it) }
                rom.rating?.takeIf { it > 0 }?.let { MetaPill("%.1f".format(if (it > 10) it / 10.0 else it), tint = colors.gold, star = true) }
                rom.players?.let { p ->
                    // "1" -> "1 joueur", "1-8" -> "1-8 joueurs": the last number picks the plural.
                    val n = Regex("[0-9]+").findAll(p).lastOrNull()?.value?.toIntOrNull() ?: 2
                    MetaPill(androidx.compose.ui.res.pluralStringResource(R.plurals.dual_players, n, p))
                }
            }
            if (preview.summary && !rom.summary.isNullOrBlank()) {
                if (preview.title || preview.tags) Spacer(Modifier.height(8.dp))
                // RomM keeps the source's paragraphs ("tagline\n\nbody"); in a few preview lines a
                // blank line only wastes one, so the summary is folded into a single paragraph.
                Text(
                    remember(rom.summary) { rom.summary.replace(Regex("\\s+"), " ").trim() },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceMuted,
                    maxLines = maxSummaryLines,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.widthIn(max = 640.dp),
                )
            }
        }
    }
}

/** The web's metadata chips: translucent fill, light edge, small semibold text. */
@Composable
private fun MetaPill(text: String, tint: Color? = null, star: Boolean = false) {
    val colors = RommTheme.colors
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(999.dp)
    val fg = tint ?: colors.onSurfaceSecondary
    // Fixed height: the "★" glyph comes from a fallback font with taller metrics and made the
    // rating pill bigger than the others. An icon keeps every pill on the same line box.
    Row(
        Modifier
            .height(26.dp)
            .background(Color.White.copy(alpha = 0.08f), shape)
            .border(1.dp, (tint ?: Color.White).copy(alpha = if (tint != null) 0.45f else 0.18f), shape)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (star) {
            Icon(androidx.compose.material.icons.Icons.Rounded.Star, null, tint = fg, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1)
    }
}

/** Provides [LocalDualScreen] to a subtree. */
@Composable
fun ProvideDualScreen(controller: DualScreenController, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDualScreen provides controller, content = content)
}
