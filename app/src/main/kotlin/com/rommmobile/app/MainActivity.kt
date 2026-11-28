package com.rommmobile.app

import androidx.compose.runtime.setValue
import com.rommmobile.app.core.input.remapKey
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rommmobile.app.core.design.RommTheme
import com.rommmobile.app.core.design.ThemeMode
import com.rommmobile.app.core.input.GamepadBus
import com.rommmobile.app.data.prefs.SettingsStore
import com.rommmobile.app.feature.main.RomMRoot
import com.rommmobile.app.feature.dualscreen.CompanionPresentation
import com.rommmobile.app.feature.dualscreen.CompanionActivity
import com.rommmobile.app.feature.dualscreen.DualScreenController
import com.rommmobile.app.feature.dualscreen.ProvideDualScreen
import android.hardware.display.DisplayManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** Bumped on every new intent so the navigation graph re-reads its extras. */
    private val pendingIntentEpoch = androidx.compose.runtime.mutableIntStateOf(0)

    @Inject lateinit var gamepad: GamepadBus
    @Inject lateinit var settings: SettingsStore
    @Inject lateinit var dualScreen: DualScreenController

    private var companion: CompanionPresentation? = null
    @Volatile private var dualEnabled = true
    // Compose state, not a plain field: the presentation on the other screen reads it inside its
    // composition, so a theme change in Settings now repaints the companion too.
    private var currentTheme by androidx.compose.runtime.mutableStateOf(ThemeMode.DARK)
    private val displays by lazy { getSystemService(DisplayManager::class.java) }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = updateCompanion()
        override fun onDisplayRemoved(displayId: Int) = updateCompanion()
        override fun onDisplayChanged(displayId: Int) = updateCompanion()
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Without this the download progress notification is silently dropped on Android 13+,
        // and a queue running in the background becomes invisible.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            runCatching { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
        dualScreen.forwardKey = { ev -> dispatchKeyEvent(ev) }
        dualScreen.forwardMotion = { ev -> onGenericMotionEvent(ev) }
        dualScreen.focusBack = {
            runOnUiThread {
                runCatching {
                    getSystemService(android.app.ActivityManager::class.java)
                        .moveTaskToFront(taskId, android.app.ActivityManager.MOVE_TASK_NO_USER_ACTION)
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { settings.dualScreen.collect { dualEnabled = it; updateCompanion() } }
                launch { settings.themeMode.collect { currentTheme = it } }
            }
        }
        setContent {
            val themeMode by settings.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.DARK)
            val screenMargin by settings.screenMarginDp.collectAsStateWithLifecycle(initialValue = 0)
            val navStyle by settings.navStyle.collectAsStateWithLifecycle(initialValue = com.rommmobile.app.core.design.NavStyle.AUTO)
            RommTheme(themeMode = themeMode, screenMarginDp = screenMargin, navStyle = navStyle) {
                ProvideDualScreen(dualScreen) {
                    RomMRoot(intentEpoch = pendingIntentEpoch.intValue)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        displays.registerDisplayListener(displayListener, android.os.Handler(mainLooper))
        updateCompanion()
    }

    override fun onStop() {
        displays.unregisterDisplayListener(displayListener)
        // Leaving for the other screen: the new instance has already opened its own companion on
        // this side, and closing here (the shared controller) would shut that one straight away.
        if (!movingScreens) closeCompanions()
        super.onStop()
    }

    /** Set by [moveToOtherScreen]: this instance is on its way out and must not touch the companion. */
    private var movingScreens = false

    private fun closeCompanions() {
        companion?.dismiss()
        companion = null
        companionActivityDisplay = null
        dualScreen.activityWanted.value = false
        dualScreen.active = false
    }

    /**
     * Swaps the screens: the app restarts on the other display and the game sheet comes to this
     * one (updateCompanion does that by itself once the app sits on the other side). The task
     * is closed first and the new one started from the application a moment later: MainActivity
     * is singleTask, so starting it while the old task lives would only bring that task back.
     */
    fun moveToOtherScreen(): Boolean {
        val mine = if (Build.VERSION.SDK_INT >= 30) display?.displayId else @Suppress("DEPRECATION") windowManager.defaultDisplay.displayId
        val other = displays.displays.firstOrNull { it.displayId != mine && it.state != android.view.Display.STATE_OFF } ?: return false
        val app = applicationContext
        val intent = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opts = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(other.displayId).toBundle()
        closeCompanions()
        movingScreens = true
        finishAndRemoveTask()
        android.os.Handler(mainLooper).postDelayed({ runCatching { app.startActivity(intent, opts) } }, 350)
        return true
    }

    /** Display the [CompanionActivity] was last started on, so it is not launched twice. */
    private var companionActivityDisplay: Int? = null

    /**
     * Shows the game sheet on the other screen of a dual-screen handheld. App on the main screen:
     * a [CompanionPresentation] on the second one. App on the second screen: presentations are
     * not allowed on the main display, so a display-only [CompanionActivity] is started there.
     * Nothing happens on a single-screen device.
     */
    private fun updateCompanion() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        val mine = if (Build.VERSION.SDK_INT >= 30) display?.displayId else @Suppress("DEPRECATION") windowManager.defaultDisplay.displayId
        val target = if (!dualEnabled) null else
            displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull { it.displayId != mine }
                ?: displays.displays.firstOrNull { it.displayId != mine && it.state != android.view.Display.STATE_OFF }
        if (target == null) { closeCompanions(); return }

        if (target.displayId != android.view.Display.DEFAULT_DISPLAY) {
            // Presentation path; an activity left on another display from before is closed.
            if (companionActivityDisplay != null) { dualScreen.activityWanted.value = false; companionActivityDisplay = null }
            if (companion?.display?.displayId == target.displayId) return
            companion?.dismiss()
            companion = CompanionPresentation(this, target, dualScreen) { currentTheme }.also { p ->
                val shown = runCatching { p.show() }.isSuccess
                dualScreen.active = shown
                if (!shown) companion = null
            }
            if (companion != null) return
        }
        // Activity path: the sheet goes on the main display.
        companion?.dismiss()
        companion = null
        if (companionActivityDisplay == target.displayId) return
        dualScreen.activityWanted.value = true
        dualScreen.active = true
        val opts = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(target.displayId)
        val intent = Intent(this, CompanionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        val started = runCatching { startActivity(intent, opts.toBundle()) }.isSuccess
        companionActivityDisplay = if (started) target.displayId else null
        // Starting it moved input focus to its screen: take it back for the pad.
        if (started) window.decorView.postDelayed({ dualScreen.focusBack?.invoke() }, 350)
        if (!started) { dualScreen.activityWanted.value = false; dualScreen.active = false }
    }

    /** singleTask: a notification tap on a live app arrives here, not in onCreate. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingIntentEpoch.intValue += 1
    }

    /**
     * Every key goes through the user's button map first (see [remapKey] for what each printed
     * button becomes). While a mapper is recording, it gets the raw key and nothing else does;
     * the system's own keys stay with the system, or the volume could not be set and the
     * recorder could not be left.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!GamepadBus.isSystemKey(event.keyCode) && gamepad.offerToCapture(event)) return true
        return gamepad.remapKey(event, dispatch = { super.dispatchKeyEvent(it) }, onBack = { onBackPressedDispatcher.onBackPressed() })
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (gamepad.onMotionEvent(event)) return true
        return super.onGenericMotionEvent(event)
    }
}
