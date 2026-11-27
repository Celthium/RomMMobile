package com.rommmobile.app.feature.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.syncDataStore: DataStore<Preferences> by preferencesDataStore(name = "sync")

data class SyncSettings(
    /** Master switch: nothing is read, sent or written while off. Off until the user turns it on. */
    val enabled: Boolean = false,
    /** Game saves (SRAM): on, in both directions. */
    val saves: Boolean = true,
    /** Save states: on, but only synced by hand unless [statesAuto]. */
    val states: Boolean = true,
    /** Saves sync on their own: at app start and every hour in the background. */
    val savesAuto: Boolean = true,
    /** States follow the automatic runs too (off: they are several MB each). */
    val statesAuto: Boolean = false,
    val manualSavesDir: String? = null,
    val manualStatesDir: String? = null,
) {
    fun kindEnabled(kind: SaveKind) = enabled && if (kind == SaveKind.SAVE) saves else states
    fun kindAuto(kind: SaveKind) = kindEnabled(kind) && if (kind == SaveKind.SAVE) savesAuto else statesAuto
}

@Singleton
class SyncPrefs @Inject constructor(@ApplicationContext private val context: Context) {
    private val ds get() = context.syncDataStore

    private object K {
        val enabled = booleanPreferencesKey("enabled")
        val saves = booleanPreferencesKey("saves")
        val states = booleanPreferencesKey("states")
        val savesAuto = booleanPreferencesKey("saves_auto")
        val statesAuto = booleanPreferencesKey("states_auto")
        val savesDir = stringPreferencesKey("saves_dir")
        val statesDir = stringPreferencesKey("states_dir")
    }

    val settings: Flow<SyncSettings> = ds.data
        .catch { e -> if (e is java.io.IOException) emit(emptyPreferences()) else throw e }
        .map { p ->
            SyncSettings(
                enabled = p[K.enabled] ?: false,
                saves = p[K.saves] ?: true,
                states = p[K.states] ?: true,
                savesAuto = p[K.savesAuto] ?: true,
                statesAuto = p[K.statesAuto] ?: false,
                manualSavesDir = p[K.savesDir],
                manualStatesDir = p[K.statesDir],
            )
        }

    suspend fun snapshot(): SyncSettings = settings.first()

    suspend fun update(transform: (SyncSettings) -> SyncSettings) {
        val n = transform(snapshot())
        ds.edit { p ->
            p[K.enabled] = n.enabled
            p[K.saves] = n.saves
            p[K.states] = n.states
            p[K.savesAuto] = n.savesAuto
            p[K.statesAuto] = n.statesAuto
            if (n.manualSavesDir.isNullOrBlank()) p.remove(K.savesDir) else p[K.savesDir] = n.manualSavesDir
            if (n.manualStatesDir.isNullOrBlank()) p.remove(K.statesDir) else p[K.statesDir] = n.manualStatesDir
        }
    }
}
