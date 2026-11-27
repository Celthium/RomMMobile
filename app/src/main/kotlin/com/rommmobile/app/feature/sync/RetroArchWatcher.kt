package com.rommmobile.app.feature.sync

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** What the app can tell about RetroArch right now. */
enum class RetroArchState {
    /** Not installed, quit, or not used since the device started: safe to write its files. */
    CLOSED,
    /** On screen. */
    FOREGROUND,
    /** Left in the background with its activity alive: it may still hold a game in memory. */
    BACKGROUND,
    /** No usage access: the app cannot tell. */
    UNKNOWN,
}

/**
 * Android has no API that tells whether another app is running. The closest is the usage-event log
 * (special access "Usage access", granted by the user): an activity that was quit leaves an
 * `ACTIVITY_DESTROYED` event, one left in the background stops at `ACTIVITY_STOPPED`. RetroArch
 * writes its save every few seconds while a game runs and again when the game closes, so a file
 * restored under it would be overwritten: anything but [RetroArchState.CLOSED] means wait.
 */
@Singleton
class RetroArchWatcher @Inject constructor(@ApplicationContext private val context: Context) {

    fun hasUsageAccess(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Android's "Usage access" page, on this app when the device allows it. */
    fun usageAccessIntent(): Intent {
        val specific = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${context.packageName}"))
        return if (specific.resolveActivity(context.packageManager) != null) specific else Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
    }

    fun state(): RetroArchState {
        val pkg = RetroArchConfig.installedPackage(context) ?: return RetroArchState.CLOSED
        if (!hasUsageAccess()) return RetroArchState.UNKNOWN
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return RetroArchState.UNKNOWN
        val now = System.currentTimeMillis()
        // Back to the boot: an activity alive before it is not alive any more.
        val bootAt = now - SystemClock.elapsedRealtime()
        val events = runCatching { usm.queryEvents(maxOf(bootAt, now - 7L * 24 * 3600 * 1000), now) }.getOrNull() ?: return RetroArchState.UNKNOWN
        // Last known state per activity class: several RetroArch activities can be in the stack.
        val last = HashMap<String, Int>()
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.packageName != pkg) continue
            val t = e.eventType
            if (t == UsageEvents.Event.ACTIVITY_RESUMED || t == UsageEvents.Event.ACTIVITY_PAUSED || t == ACTIVITY_STOPPED || t == ACTIVITY_DESTROYED) {
                last[e.className ?: ""] = t
            }
        }
        return when {
            last.values.any { it == UsageEvents.Event.ACTIVITY_RESUMED } -> RetroArchState.FOREGROUND
            last.values.any { it == UsageEvents.Event.ACTIVITY_PAUSED || it == ACTIVITY_STOPPED } -> RetroArchState.BACKGROUND
            else -> RetroArchState.CLOSED
        }
    }

    private companion object {
        // UsageEvents.Event constants (API 29); spelled out so the minimum SDK stays 26.
        const val ACTIVITY_STOPPED = 23
        const val ACTIVITY_DESTROYED = 24
    }
}
