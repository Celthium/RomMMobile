package com.rommmobile.app.feature.settings

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.relocation.bringIntoViewRequester
import com.rommmobile.app.core.design.PillShape
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Monitor
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material3.Icon
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import com.rommmobile.app.core.design.RommTextField
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rommmobile.app.BuildConfig
import com.rommmobile.app.R
import com.rommmobile.app.core.design.RommDialog
import com.rommmobile.app.core.design.RommTheme
import com.rommmobile.app.core.design.RommTopBar
import com.rommmobile.app.core.design.SectionHeader
import com.rommmobile.app.core.design.SettingRow
import com.rommmobile.app.core.design.SwitchRow
import com.rommmobile.app.core.design.ThemeMode
import com.rommmobile.app.core.design.Toaster
import com.rommmobile.app.core.design.gamepadFocusRing
import com.rommmobile.app.core.design.gamepadTextField
import com.rommmobile.app.core.input.rightStickScroll
import com.rommmobile.app.core.network.ApiException
import com.rommmobile.app.core.network.ServerConfig
import com.rommmobile.app.core.network.ServerStore
import com.rommmobile.app.core.storage.StorageRoot
import com.rommmobile.app.data.mapping.MappingRepository
import com.rommmobile.app.data.prefs.Launcher
import com.rommmobile.app.data.prefs.Settings
import com.rommmobile.app.data.prefs.SettingsStore
import com.rommmobile.app.data.repo.AuthRepository
import com.rommmobile.app.data.repo.LocalLibraryRepository
import com.rommmobile.app.data.repo.Session
import com.rommmobile.app.feature.onboarding.RootChooserDialog
import com.rommmobile.app.feature.onboarding.launcherDescRes
import com.rommmobile.app.feature.onboarding.launcherLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val store: SettingsStore,
    private val serverStore: ServerStore,
    private val auth: AuthRepository,
    private val mapping: MappingRepository,
    private val localLibrary: LocalLibraryRepository,
    private val platformRepo: com.rommmobile.app.data.repo.PlatformRepository,
    private val toaster: Toaster,
) : ViewModel() {
    val settings: StateFlow<Settings?> = store.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val platforms: StateFlow<List<com.rommmobile.app.data.repo.Platform>> =
        platformRepo.platforms.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setFolderPerGame(slug: String, enabled: Boolean) = viewModelScope.launch {
        val current = store.snapshot().folderPerGameSlugs
        store.setFolderPerGame(if (enabled) current + slug else current - slug)
    }

    fun setExtractOverride(slug: String, extract: Boolean?) = viewModelScope.launch { store.setExtractOverride(slug, extract) }
    val server: StateFlow<ServerConfig> = serverStore.config
    val session: StateFlow<Session> = auth.session
    val rootNames: List<String> get() = mapping.map.rootNamesFor(settings.value?.launcher ?: Launcher.ESDE)

    fun setLauncher(l: Launcher) = viewModelScope.launch { store.setLauncher(l); mapping.resetAutomatic() }
    fun setRoot(root: StorageRoot) = viewModelScope.launch { store.setStorageRoot(root); mapping.resetAutomatic(); localLibrary.rescanAll() }
    fun setConcurrency(n: Int) = viewModelScope.launch { store.setConcurrency(n) }
    fun setWifiOnly(v: Boolean) = viewModelScope.launch { store.setWifiOnly(v) }
    fun setDualScreen(v: Boolean) = viewModelScope.launch { store.setDualScreen(v) }
    fun setPreview(v: com.rommmobile.app.data.prefs.PreviewOptions) = viewModelScope.launch { store.setPreview(v) }
    fun setExtractZip(v: Boolean) = viewModelScope.launch { store.setAutoExtractZip(v) }
    fun setExtract7z(v: Boolean) = viewModelScope.launch { store.setAutoExtract7z(v) }
    fun setMargin(v: Int) = viewModelScope.launch { store.setScreenMargin(v) }
    fun setTheme(v: ThemeMode) = viewModelScope.launch { store.setThemeMode(v) }
    fun setNavStyle(v: com.rommmobile.app.core.design.NavStyle) = viewModelScope.launch { store.setNavStyle(v) }
    fun setBiosPath(v: String?) = viewModelScope.launch { store.setBiosPath(v) }
    fun setAllowInsecure(v: Boolean) = viewModelScope.launch { store.setAllowInsecureRemote(v) }
    fun setRemoteUrl(v: String?) = viewModelScope.launch { serverStore.setRemoteUrl(v?.let { com.rommmobile.app.core.network.UrlNormalizer.normalize(it) }) }
    fun setCloudflare(id: String?, secret: String?) = viewModelScope.launch { serverStore.setCloudflareAccess(id, secret) }
    fun rescan() = viewModelScope.launch { localLibrary.rescanAll(); toaster.show("OK") }
    fun logout() = auth.logout()
    fun resetOnboarding() = viewModelScope.launch { store.setOnboardingDone(false) }

    fun changeServer(url: String, onResult: (ApiException?) -> Unit) = viewModelScope.launch {
        auth.probe(url).onSuccess { r -> auth.saveServer(r.url, r.version); onResult(null) }.onFailure { onResult(ApiException.from(it)) }
    }
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenMappings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenLocalLibrary: () -> Unit,
    onOpenFirmware: () -> Unit,
    onOpenControls: () -> Unit,
    onLoggedOut: () -> Unit,
    onRerunOnboarding: () -> Unit,
) {
    val vm: SettingsViewModel = hiltViewModel()
    val s = vm.settings.collectAsStateWithLifecycle().value ?: return
    val server by vm.server.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val colors = RommTheme.colors
    val layout = RommTheme.layout
    val scroll = rememberScrollState()

    var dialog by remember { mutableStateOf<String?>(null) }

    // Categories instead of one long list: on a landscape handheld a sidebar on the left (like a
    // console's settings), on a portrait phone a row of tabs on top. LB / RB step through them
    // either way, and a category opens as soon as the cursor lands on it.
    var category by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(SettingsCategory.SERVER) }
    val categories = SettingsCategory.entries
    com.rommmobile.app.core.input.GamepadActions(category) { action ->
        val i = categories.indexOf(category)
        when (action) {
            com.rommmobile.app.core.input.GamepadAction.PREV_LETTER -> { if (i > 0) category = categories[i - 1]; true }
            com.rommmobile.app.core.input.GamepadAction.NEXT_LETTER -> { if (i < categories.lastIndex) category = categories[i + 1]; true }
            else -> false
        }
    }
    // By the screen's shape, not the navigation style: a landscape screen keeps the category
    // sidebar even when the user put the app's navigation at the bottom.
    val wide = layout.shape == com.rommmobile.app.core.design.ScreenShape.LANDSCAPE
    val otherScreen = remember { hasSecondScreen(context) }

    val content: @Composable () -> Unit = {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).rightStickScroll(scroll).padding(start = if (wide) 0.dp else layout.margin, end = layout.margin, top = 8.dp, bottom = 56.dp + com.rommmobile.app.core.design.LocalNavBarInset.current)) {
            when (category) {
                SettingsCategory.SERVER -> {
                    SettingsCard {
                        SettingRow(stringResource(R.string.settings_server_lan), subtitle = listOfNotNull(server.baseUrl, server.serverVersion?.let { "RomM $it" }).joinToString(" · "), onClick = { dialog = "server" })
                        SettingRow(stringResource(R.string.settings_server_remote), subtitle = server.remoteUrl ?: stringResource(R.string.settings_not_set), onClick = { dialog = "remote" })
                        SettingRow(stringResource(R.string.settings_cf_access), subtitle = if (server.cfClientId.isNullOrBlank()) stringResource(R.string.settings_not_set) else server.cfClientId, onClick = { dialog = "cf" })
                        SwitchRow(stringResource(R.string.settings_allow_insecure), s.allowInsecureRemote, vm::setAllowInsecure, subtitle = stringResource(R.string.settings_allow_insecure_hint))
                    }
                    SettingsCard {
                        val user = (session as? Session.LoggedIn)
                        SettingRow(stringResource(R.string.settings_account), subtitle = user?.let { "${it.username ?: "?"} · ${it.mode.name.lowercase()}" } ?: stringResource(R.string.settings_logged_out), onClick = { dialog = "logout" })
                    }
                }
                SettingsCategory.STORAGE -> {
                    SettingsCard {
                        SettingRow(stringResource(R.string.settings_launcher), subtitle = launcherLabel(s.launcher), onClick = { dialog = "launcher" })
                        SettingRow(stringResource(R.string.settings_root), subtitle = s.storageRoot?.displayName ?: stringResource(R.string.settings_not_set), onClick = { dialog = "root" })
                        SettingRow(stringResource(R.string.settings_mappings), subtitle = stringResource(R.string.settings_mappings_hint), onClick = onOpenMappings)
                    }
                    // Covers, screenshots, logos and gamelist.xml written for ES-DE: only ES-DE (and Cocoon) read them.
                    if (com.rommmobile.app.feature.esde.usesEsdeLayout(s.launcher)) com.rommmobile.app.feature.esde.EsdeSettingsSection(card = { inner -> SettingsCard(inner) })
                    SettingsCard {
                        SettingRow(stringResource(R.string.settings_local_library), subtitle = stringResource(R.string.settings_local_library_hint), onClick = onOpenLocalLibrary)
                        SettingRow(stringResource(R.string.settings_rescan), subtitle = stringResource(R.string.settings_rescan_hint), onClick = { vm.rescan() })
                    }
                    SettingsCard {
                        SettingRow(stringResource(R.string.settings_bios), subtitle = s.biosPath ?: stringResource(R.string.settings_bios_default), onClick = onOpenFirmware)
                        SettingRow(stringResource(R.string.settings_bios_path), subtitle = s.biosPath ?: stringResource(R.string.settings_bios_default), onClick = { dialog = "bios" })
                    }
                }
                SettingsCategory.DOWNLOADS -> {
                    SettingsCard {
                        SettingRow(stringResource(R.string.settings_concurrency), subtitle = s.concurrency.toString(), onClick = { dialog = "concurrency" })
                        SwitchRow(stringResource(R.string.settings_wifi_only), s.wifiOnly, vm::setWifiOnly)
                    }
                    SettingsCard {
                        SwitchRow(stringResource(R.string.settings_extract_zip), s.autoExtractZip, vm::setExtractZip, subtitle = stringResource(R.string.settings_extract_zip_hint))
                        SwitchRow(stringResource(R.string.settings_extract_7z), s.autoExtract7z, vm::setExtract7z, subtitle = stringResource(R.string.settings_extract_7z_hint))
                        SettingRow(stringResource(R.string.settings_per_platform), subtitle = stringResource(R.string.settings_per_platform_hint), onClick = { dialog = "perplatform" })
                    }
                }
                SettingsCategory.SYNC -> com.rommmobile.app.feature.sync.SyncSettingsSection(
                    card = { inner -> SettingsCard(inner) },
                    // Write scopes come with a new login: the logout dialog leads there.
                    onRelogin = { dialog = "logout" },
                )
                SettingsCategory.CONTROLS -> {
                    SettingsCard {
                        SettingRow(stringResource(R.string.settings_button_map), subtitle = stringResource(if (s.buttonMap.isDefault) R.string.settings_button_map_default else R.string.settings_button_map_custom), onClick = onOpenControls)
                    }
                }
                SettingsCategory.DISPLAY -> {
                    SettingsCard {
                        SettingRow(stringResource(R.string.settings_theme), subtitle = themeLabel(s.themeMode), onClick = { dialog = "theme" })
                        SettingRow(stringResource(R.string.settings_nav_style), subtitle = navStyleLabel(s.navStyle), onClick = { dialog = "navstyle" })
                    }
                    SettingsCard {
                        SwitchRow(stringResource(R.string.settings_dual_screen), s.dualScreen, vm::setDualScreen, subtitle = stringResource(R.string.settings_dual_screen_hint))
                        // Only once the second screen is on and there is one to swap with.
                        if (s.dualScreen && otherScreen) {
                            SettingRow(
                                stringResource(R.string.settings_swap_screens),
                                subtitle = stringResource(R.string.settings_swap_screens_hint),
                                onClick = { (context.findActivity() as? com.rommmobile.app.MainActivity)?.moveToOtherScreen() },
                            )
                        }
                    }
                    // The info block over the picture on the other screen: on / off, then part by
                    // part (the parts only matter, and only respond, while the block is on).
                    if (s.dualScreen) {
                        val pv = s.preview
                        SettingsCard {
                            SwitchRow(stringResource(R.string.settings_preview_block), pv.block, { vm.setPreview(pv.copy(block = it)) }, subtitle = stringResource(R.string.settings_preview_block_hint))
                            SwitchRow(stringResource(R.string.settings_preview_title), pv.title, { vm.setPreview(pv.copy(title = it)) }, enabled = pv.block)
                            SwitchRow(stringResource(R.string.settings_preview_tags), pv.tags, { vm.setPreview(pv.copy(tags = it)) }, enabled = pv.block)
                            SwitchRow(stringResource(R.string.settings_preview_summary), pv.summary, { vm.setPreview(pv.copy(summary = it)) }, enabled = pv.block)
                        }
                    }
                }
                SettingsCategory.ADVANCED -> {
                    SettingsCard {
                        SettingRow(stringResource(R.string.settings_diagnostics), subtitle = stringResource(R.string.settings_diagnostics_hint), onClick = onOpenDiagnostics)
                        SettingRow(stringResource(R.string.settings_rerun_onboarding), onClick = { dialog = "rerun" })
                    }
                    SettingsCard {
                        SettingRow(stringResource(R.string.settings_version), subtitle = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    }
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        RommTopBar(title = stringResource(R.string.section_settings), onBack = onBack)
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier.width(248.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = layout.margin, end = 16.dp, top = 8.dp, bottom = 16.dp + com.rommmobile.app.core.design.LocalNavBarInset.current),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    categories.forEach { c -> CategoryItem(c, c == category) { category = c } }
                }
                Box(Modifier.weight(1f).fillMaxHeight()) { content() }
            }
        } else {
            val hasPad by com.rommmobile.app.core.input.rememberHasGamepad()
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = layout.margin, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // LB / RB step through the categories (handled above); say so, as on a game page.
                if (hasPad) com.rommmobile.app.core.design.KeycapGlyph("LB", Modifier.padding(end = 4.dp))
                categories.forEach { c ->
                    val selected = c == category
                    // LB / RB can land on a tab scrolled out of the row: bring it back into view.
                    val inView = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
                    LaunchedEffect(selected) { if (selected) inView.bringIntoView() }
                    Text(
                        stringResource(c.label),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) colors.white else colors.onSurface,
                        modifier = Modifier.bringIntoViewRequester(inView).gamepadFocusRing(PillShape).clip(PillShape).background(if (selected) colors.primary else colors.surface)
                            .clickable { category = c }.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                if (hasPad) com.rommmobile.app.core.design.KeycapGlyph("RB", Modifier.padding(start = 4.dp))
            }
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        }
    }
    // The page runs on under the system bar (ExtendUnderNavBar on the route) and dims into it on
    // the shared fade, instead of a hard edge cutting the last card just above the bar.
    Box(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .height(56.dp + com.rommmobile.app.core.design.LocalNavBarInset.current)
            .background(com.rommmobile.app.core.design.softFadeBrush(colors.background, colors.isDark)),
    )
    }

    when (dialog) {
        "server" -> {
            var err by remember { mutableStateOf<String?>(null) }
            TextInputDialog(stringResource(R.string.settings_server_lan), server.baseUrl.orEmpty(), error = err, onDismiss = { dialog = null }) { v ->
                vm.changeServer(v) { e -> if (e == null) dialog = null else err = e.userMessage(context) }
            }
        }
        "remote" -> TextInputDialog(stringResource(R.string.settings_server_remote), server.remoteUrl.orEmpty(), hint = "https://romm.example.com", onDismiss = { dialog = null }) { vm.setRemoteUrl(it.ifBlank { null }); dialog = null }
        "cf" -> CloudflareDialog(server.cfClientId.orEmpty(), server.cfClientSecret.orEmpty(), onDismiss = { dialog = null }) { id, secret -> vm.setCloudflare(id.ifBlank { null }, secret.ifBlank { null }); dialog = null }
        "logout" -> RommDialog(onDismissRequest = { dialog = null }, title = stringResource(R.string.settings_logout_title), confirmText = stringResource(R.string.action_logout), onConfirm = { dialog = null; vm.logout(); onLoggedOut() }, destructive = true, focusConfirm = false) {
            Text(stringResource(R.string.settings_logout_body))
        }
        "launcher" -> ChoiceDialog(
            title = stringResource(R.string.settings_launcher),
            options = Launcher.entries.map { launcherLabel(it) to stringResource(launcherDescRes(it)) },
            selected = Launcher.entries.indexOf(s.launcher),
            onDismiss = { dialog = null },
        ) { i -> vm.setLauncher(Launcher.entries[i]); dialog = null }
        "root" -> RootChooserDialog(preferredNames = vm.rootNames, onChosen = { vm.setRoot(it); dialog = null }, onDismiss = { dialog = null })
        "bios" -> TextInputDialog(stringResource(R.string.settings_bios_path), s.biosPath.orEmpty(), hint = "/storage/emulated/0/RetroArch/system", onDismiss = { dialog = null }) { vm.setBiosPath(it.ifBlank { null }); dialog = null }
        "concurrency" -> ChoiceDialog(stringResource(R.string.settings_concurrency), (1..5).map { it.toString() to null }, s.concurrency - 1, onDismiss = { dialog = null }) { vm.setConcurrency(it + 1); dialog = null }
        "navstyle" -> ChoiceDialog(
            stringResource(R.string.settings_nav_style),
            com.rommmobile.app.core.design.NavStyle.entries.map { navStyleLabel(it) to null },
            com.rommmobile.app.core.design.NavStyle.entries.indexOf(s.navStyle),
            onDismiss = { dialog = null },
        ) { vm.setNavStyle(com.rommmobile.app.core.design.NavStyle.entries[it]); dialog = null }
        "theme" -> ChoiceDialog(stringResource(R.string.settings_theme), ThemeMode.entries.map { themeLabel(it) to null }, ThemeMode.entries.indexOf(s.themeMode), onDismiss = { dialog = null }) { vm.setTheme(ThemeMode.entries[it]); dialog = null }
        "perplatform" -> PerPlatformDialog(vm, s, onDismiss = { dialog = null })
        "rerun" -> RommDialog(onDismissRequest = { dialog = null }, title = stringResource(R.string.settings_rerun_onboarding), confirmText = stringResource(R.string.action_continue), onConfirm = { dialog = null; vm.resetOnboarding(); onRerunOnboarding() }, focusConfirm = false) {
            Text(stringResource(R.string.settings_rerun_body))
        }
    }
}

@Composable
fun navStyleLabel(style: com.rommmobile.app.core.design.NavStyle): String = stringResource(
    when (style) {
        com.rommmobile.app.core.design.NavStyle.AUTO -> R.string.nav_style_auto
        com.rommmobile.app.core.design.NavStyle.RAIL -> R.string.nav_style_rail
        com.rommmobile.app.core.design.NavStyle.BOTTOM -> R.string.nav_style_bottom
    },
)

@Composable
fun themeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.DARK -> stringResource(R.string.theme_dark)
    ThemeMode.LIGHT -> stringResource(R.string.theme_light)
    ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
}

@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    hint: String? = null,
    error: String? = null,
    keyboardType: KeyboardType = KeyboardType.Uri,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    RommDialog(onDismissRequest = onDismiss, title = title, confirmText = stringResource(R.string.action_save), onConfirm = { onConfirm(value.trim()) }, focusConfirm = false) {
        RommTextField(
            value = value,
            onValueChange = { value = it },
            placeholder = hint,
            error = error,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.fillMaxWidth(),
            fieldModifier = Modifier.gamepadTextField(com.rommmobile.app.core.design.PillShape),
        )
    }
}

@Composable
private fun CloudflareDialog(initialId: String, initialSecret: String, onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var id by remember { mutableStateOf(initialId) }
    var secret by remember { mutableStateOf(initialSecret) }
    RommDialog(onDismissRequest = onDismiss, title = stringResource(R.string.settings_cf_access), confirmText = stringResource(R.string.action_save), onConfirm = { onConfirm(id.trim(), secret.trim()) }, focusConfirm = false) {
        Column {
            Text(stringResource(R.string.settings_cf_hint), style = MaterialTheme.typography.bodySmall, color = RommTheme.colors.onSurfaceMuted)
            Spacer(Modifier.height(8.dp))
            RommTextField(value = id, onValueChange = { id = it }, label = "CF-Access-Client-Id", modifier = Modifier.fillMaxWidth(), fieldModifier = Modifier.gamepadTextField(com.rommmobile.app.core.design.PillShape))
            Spacer(Modifier.height(14.dp))
            RommTextField(value = secret, onValueChange = { secret = it }, label = "CF-Access-Client-Secret", modifier = Modifier.fillMaxWidth(), fieldModifier = Modifier.gamepadTextField(com.rommmobile.app.core.design.PillShape))
        }
    }
}

@Composable
fun ChoiceDialog(
    title: String,
    options: List<Pair<String, String?>>,
    selected: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    val colors = RommTheme.colors
    RommDialog(onDismissRequest = onDismiss, title = title, confirmText = stringResource(R.string.action_close), onConfirm = onDismiss, dismissText = null, focusConfirm = false) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            options.forEachIndexed { i, (label, desc) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .gamepadFocusRing(MaterialTheme.shapes.small)
                        .clip(MaterialTheme.shapes.small)
                        .clickable { onSelect(i) }
                        .padding(vertical = 6.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = i == selected, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                        if (desc != null) Text(desc, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceMuted)
                    }
                }
            }
        }
    }
}


/** The settings, by subject. */
private enum class SettingsCategory(val label: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    SERVER(R.string.settings_server, androidx.compose.material.icons.Icons.Rounded.Dns),
    STORAGE(R.string.settings_launcher_folders, androidx.compose.material.icons.Icons.Rounded.Folder),
    DOWNLOADS(R.string.settings_downloads, androidx.compose.material.icons.Icons.Rounded.FileDownload),
    SYNC(R.string.settings_sync, androidx.compose.material.icons.Icons.Rounded.Sync),
    CONTROLS(R.string.settings_controls, androidx.compose.material.icons.Icons.Rounded.SportsEsports),
    DISPLAY(R.string.settings_appearance, androidx.compose.material.icons.Icons.Rounded.Monitor),
    ADVANCED(R.string.settings_advanced, androidx.compose.material.icons.Icons.Rounded.Tune),
}

/** Sidebar entry: icon and name; the open one filled, the focused one tinted. */
@Composable
private fun CategoryItem(c: SettingsCategory, selected: Boolean, onSelect: () -> Unit) {
    val colors = RommTheme.colors
    val shape = MaterialTheme.shapes.medium
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .onFocusChanged {
                focused = it.isFocused
                // Landing on a category opens it: no extra press to see what is inside.
                if (it.isFocused && !selected) onSelect()
            }
            .clip(shape)
            .background(
                when {
                    selected -> colors.primary.copy(alpha = 0.18f)
                    focused -> colors.surfaceHover
                    else -> androidx.compose.ui.graphics.Color.Transparent
                },
            )
            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(c.icon, null, tint = if (selected) colors.primaryLighten else colors.onSurfaceMuted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            stringResource(c.label),
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected || focused) colors.onSurface else colors.onSurfaceMuted,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

/** A group of rows on one rounded surface, as on the RomM web settings. */
@Composable
internal fun SettingsCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val colors = RommTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .clip(MaterialTheme.shapes.large)
            .background(colors.surface)
            .padding(vertical = 4.dp),
        content = content,
    )
}

private fun android.content.Context.findActivity(): android.app.Activity? {
    var c: android.content.Context? = this
    while (c is android.content.ContextWrapper) {
        if (c is android.app.Activity) return c
        c = c.baseContext
    }
    return null
}

private fun hasSecondScreen(context: android.content.Context): Boolean {
    val dm = context.getSystemService(android.hardware.display.DisplayManager::class.java) ?: return false
    return dm.displays.count { it.state != android.view.Display.STATE_OFF } > 1
}
