package com.rommmobile.app.feature.esde

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

private val Context.esdeDataStore: DataStore<Preferences> by preferencesDataStore(name = "esde_export")

/** What the ES-DE export writes next to a downloaded game. Only used while the launcher is ES-DE. */
data class EsdeExportSettings(
    val enabled: Boolean = true,
    val cover: Boolean = true,
    val screenshot: Boolean = true,
    val marquee: Boolean = true,
    /** Off by default: a clip is several MB per game. */
    val video: Boolean = false,
    /** The game's entry in `gamelist.xml` (name, description, date, genre...). */
    val metadata: Boolean = true,
    /** ES-DE's data folder set by hand; null = found on its own (`<volume>/ES-DE`). */
    val manualDir: String? = null,
) {
    val anything: Boolean get() = enabled && (cover || screenshot || marquee || video || metadata)
}

@Singleton
class EsdePrefs @Inject constructor(@ApplicationContext private val context: Context) {
    private val ds get() = context.esdeDataStore

    private object K {
        val enabled = booleanPreferencesKey("enabled")
        val cover = booleanPreferencesKey("cover")
        val screenshot = booleanPreferencesKey("screenshot")
        val marquee = booleanPreferencesKey("marquee")
        val video = booleanPreferencesKey("video")
        val metadata = booleanPreferencesKey("metadata")
        val dir = stringPreferencesKey("dir")
    }

    val settings: Flow<EsdeExportSettings> = ds.data
        .catch { e -> if (e is java.io.IOException) emit(emptyPreferences()) else throw e }
        .map { p ->
            EsdeExportSettings(
                enabled = p[K.enabled] ?: true,
                cover = p[K.cover] ?: true,
                screenshot = p[K.screenshot] ?: true,
                marquee = p[K.marquee] ?: true,
                video = p[K.video] ?: false,
                metadata = p[K.metadata] ?: true,
                manualDir = p[K.dir],
            )
        }

    suspend fun snapshot(): EsdeExportSettings = settings.first()

    suspend fun update(transform: (EsdeExportSettings) -> EsdeExportSettings) {
        val n = transform(snapshot())
        ds.edit { p ->
            p[K.enabled] = n.enabled
            p[K.cover] = n.cover
            p[K.screenshot] = n.screenshot
            p[K.marquee] = n.marquee
            p[K.video] = n.video
            p[K.metadata] = n.metadata
            if (n.manualDir.isNullOrBlank()) p.remove(K.dir) else p[K.dir] = n.manualDir
        }
    }
}
