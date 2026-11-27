package com.rommmobile.app.feature.onboarding

import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Dns
import com.rommmobile.app.core.design.RommTextField
import androidx.compose.foundation.border
import com.rommmobile.app.core.design.LocalNavBarInset
import com.rommmobile.app.feature.controls.MappingRow
import com.rommmobile.app.feature.controls.CaptureSession
import com.rommmobile.app.feature.controls.ButtonCaptureOverlay
import com.rommmobile.app.core.input.LogicalButton
import com.rommmobile.app.core.input.ButtonMap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rommmobile.app.R
import com.rommmobile.app.core.design.PadButton
import com.rommmobile.app.core.design.PadGlyph
import com.rommmobile.app.core.design.QrImage
import com.rommmobile.app.core.input.rememberHasGamepad
import kotlinx.coroutines.delay
import com.rommmobile.app.core.design.PillShape
import com.rommmobile.app.core.design.RommTheme
import com.rommmobile.app.core.design.RommTopBar
import com.rommmobile.app.core.design.SwitchRow
import com.rommmobile.app.core.design.RommDialog
import com.rommmobile.app.core.design.SettingRow
import com.rommmobile.app.core.design.gamepadFocus
import com.rommmobile.app.core.design.gamepadFocusRing
import com.rommmobile.app.core.design.gamepadTextField
import com.rommmobile.app.core.input.rightStickScroll
import com.rommmobile.app.core.storage.StorageLocations
import com.rommmobile.app.data.prefs.Launcher
import com.rommmobile.app.data.repo.Session
import com.rommmobile.app.feature.settings.MappingEditDialog
import com.rommmobile.app.feature.settings.MappingRow
import com.rommmobile.app.feature.settings.MappingStatus

@Composable
fun launcherLabel(l: Launcher): String = when (l) {
    Launcher.ESDE -> "ES-DE"
    Launcher.COCOON -> "Cocoon"
    Launcher.DAIJISHO -> "Daijishō"
    Launcher.BEACON -> "Beacon"
    Launcher.IISU -> "iiSU"
    Launcher.PEGASUS -> "Pegasus"
    Launcher.CUSTOM -> stringResource(R.string.launcher_custom)
}

fun launcherDescRes(l: Launcher): Int = when (l) {
    Launcher.ESDE -> R.string.launcher_esde_desc
    Launcher.COCOON -> R.string.launcher_cocoon_desc
    Launcher.DAIJISHO -> R.string.launcher_daijisho_desc
    Launcher.BEACON -> R.string.launcher_beacon_desc
    Launcher.IISU -> R.string.launcher_iisu_desc
    Launcher.PEGASUS -> R.string.launcher_pegasus_desc
    Launcher.CUSTOM -> R.string.launcher_custom_desc
}

// Controls first: every later step is navigated with the buttons this one names, and a pad whose
// A and B are the other way round would otherwise back out of the sign-in it was confirming.
private const val STEP_CONTROLS = 0
private const val STEP_SERVER = 1
private const val STEP_AUTH = 2
private const val STEP_LAUNCHER = 3
private const val STEP_STORAGE = 4
private const val STEP_SUMMARY = 5
private val STEP_TITLES = listOf(R.string.ob_step_controls, R.string.ob_step_server, R.string.ob_step_auth, R.string.ob_step_launcher, R.string.ob_step_storage, R.string.ob_step_summary)

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val vm: OnboardingViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val layout = RommTheme.layout
    val colors = RommTheme.colors
    val hasGamepad by rememberHasGamepad()
    // The button recorder. Owned here rather than by the Controls step so its scrim covers Next
    // and Back as well; the step only asks for it.
    var capture by remember { mutableStateOf<CaptureSession?>(null) }
    // Records on its own the first time the Controls step is shown with a pad and no map ever
    // recorded: the first prompt is Up, which every d-pad reports the same way, so no map is
    // needed to begin. Once per wizard run, remembered in the ViewModel: a flag inside the step
    // would not survive leaving it, and a recorded map can equal the default (the Classic's).
    // A pad that declares Android's standard buttons is configured on the spot, no presses asked;
    // the recorder only opens by itself when nothing could be recognised.
    var detectedPad by remember { mutableStateOf<String?>(null) }
    // Coming back to this step later (or after a restart): still say which pad it is, as long as
    // the saved mapping is the one that pad gets automatically.
    val padMatchingMap = remember(state.buttonMap, state.buttonMapRecorded) {
        if (!state.buttonMapRecorded) null else ButtonMap.detect()?.takeIf { it.second == state.buttonMap }?.first
    }
    LaunchedEffect(state.step, state.loaded, hasGamepad) {
        if (state.step == STEP_CONTROLS && state.loaded && hasGamepad && !state.buttonMapRecorded && !state.controlsPrompted) {
            vm.markControlsPrompted()
            val found = ButtonMap.detect()
            if (found != null) {
                detectedPad = found.first
                vm.setButtonMap(found.second)
            } else {
                capture = CaptureSession(CaptureSession.FULL_ORDER, ButtonMap.EMPTY, refuseDuplicates = true)
            }
        }
    }
    // Dual screen: while the wizard runs, the other screen says so instead of "select a game".
    val dual = com.rommmobile.app.feature.dualscreen.LocalDualScreen.current
    androidx.compose.runtime.DisposableEffect(dual) {
        dual?.setupMode?.value = true
        onDispose { dual?.setupMode?.value = false }
    }

    BackHandler(enabled = state.step > 0) { vm.goTo(state.step - 1) }
    LaunchedEffect(state.step) { if (state.step == STEP_SUMMARY) vm.buildSummary() }

    val canNext = when (state.step) {
        STEP_CONTROLS -> true
        STEP_SERVER -> state.probe is ProbeState.Ok
        STEP_AUTH -> session is Session.LoggedIn
        STEP_LAUNCHER -> true
        // A folder alone is not enough: a root kept from an earlier setup shows up as "not
        // writable" once All files access is missing, and Next used to sail past it.
        STEP_STORAGE -> state.root != null && state.rootWritable == true
        else -> true
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            RommTopBar(title = stringResource(STEP_TITLES[state.step]), subtitle = stringResource(R.string.ob_step_n, state.step + 1, STEP_TITLES.size), onBack = if (state.step > 0) ({ vm.goTo(state.step - 1) }) else null)
            LinearProgressIndicator(progress = { (state.step + 1) / STEP_TITLES.size.toFloat() }, modifier = Modifier.fillMaxWidth().height(2.dp), color = colors.primary, trackColor = colors.surface, drawStopIndicator = {})
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (state.step) {
                    STEP_CONTROLS -> ControlsStep(state, detectedPad ?: padMatchingMap, onLayout = vm::setFaceLayout) { capture = CaptureSession(CaptureSession.FULL_ORDER, ButtonMap.EMPTY, refuseDuplicates = true) }
                    STEP_SERVER -> ServerStep(state, vm::onServerInput, vm::setAdvanced, onSubmit = { vm.confirmServer { vm.goTo(STEP_AUTH) } })
                    STEP_AUTH -> AuthStep(state, session, vm)
                    STEP_LAUNCHER -> LauncherStep(state.launcher, vm::setLauncher)
                    STEP_STORAGE -> StorageStep(state, vm)
                    else -> SummaryStep(state, vm)
                }
            }
        }
            // Laid over the content, not under it: the step scrolls on beneath the fade instead of
            // being cut off at the bar's top edge.
            // Console convention: the confirm button sits bottom-right and carries its glyph, so the
            // user reads "press A to continue" instead of hunting for it with the d-pad. It also
            // takes focus on every step that needs no typing, which is why a long list never has to
            // be scrolled through just to reach "Next".
            // Extra room on the sides: the focus ring is drawn outside the button, and at the screen
            // edge the window clipped its right half.
            // Runs on under the system bar on a fade, like the app's other bottom bars, instead of a
            // solid band stopping above it.
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(com.rommmobile.app.core.design.softFadeBrush(colors.background, colors.isDark))
                    .padding(start = layout.margin, end = layout.margin, top = 40.dp, bottom = 10.dp + LocalNavBarInset.current),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.step > 0) {
                    TextButton(
                        onClick = { vm.goTo(state.step - 1) },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        modifier = Modifier.heightIn(min = 38.dp).gamepadFocusRing(PillShape),
                    ) {
                        if (hasGamepad) { PadGlyph(PadButton.B); Spacer(Modifier.width(6.dp)) }
                        Text(stringResource(R.string.action_back))
                    }
                    Spacer(Modifier.width(8.dp))
                }
                val nextFr = remember { FocusRequester() }
                val inputModes = androidx.compose.ui.platform.LocalInputModeManager.current
                // Keyed on the recorder too: when it closes, focus lands on Next whatever a finger
                // had focused underneath, and the next A continues instead of recording again.
                LaunchedEffect(canNext, state.step, capture == null) {
                    // Not on the server step: its field is what needs the cursor, not Next.
                    if (canNext && state.step != STEP_SERVER && capture == null) {
                        delay(120)
                        // Launched with a finger, Android is in touch mode, where buttons refuse
                        // focus: A then did nothing until a d-pad press. With a pad, switch first.
                        if (hasGamepad) inputModes.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard)
                        runCatching { nextFr.requestFocus() }
                    }
                }
                Button(
                    onClick = {
                        when (state.step) {
                            STEP_SERVER -> vm.confirmServer { vm.goTo(STEP_AUTH) }
                            STEP_SUMMARY -> vm.finish(onDone)
                            else -> vm.goTo(state.step + 1)
                        }
                    },
                    enabled = canNext,
                    // Trimmed from the Material default: at this size the pill is still an easy target
                    // and it leaves room for the pop when the pad lands on it.
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp),
                    modifier = Modifier.heightIn(min = 38.dp).focusRequester(nextFr).gamepadFocusRing(PillShape),
                ) {
                    Text(stringResource(if (state.step == STEP_SUMMARY) R.string.action_finish else R.string.action_next))
                    if (hasGamepad) { Spacer(Modifier.width(6.dp)); PadGlyph(PadButton.A) }
                }
            }
        capture?.let { session ->
            ButtonCaptureOverlay(
                session = session,
                onFinished = { vm.setButtonMap(it); capture = null },
                onCancel = { capture = null },
            )
        }
    }
}

/**
 * Sign in again (after a logout or an expired session): the auth step, with the server it talks to
 * shown on top and a way to change it, since the address may be the very reason to sign in again.
 */
@Composable
fun LoginScreen(onDone: () -> Unit) {
    val vm: OnboardingViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    LaunchedEffect(session) { if (session is Session.LoggedIn) onDone() }
    var editServer by rememberSaveable { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = editServer) { editServer = false }
    // Dual screen: signing in again is setup too, so the other screen shows the setup card, as
    // during the wizard, not "select a game" (there is no game to select before signing in).
    val dual = com.rommmobile.app.feature.dualscreen.LocalDualScreen.current
    androidx.compose.runtime.DisposableEffect(dual) {
        dual?.setupMode?.value = true
        onDispose { dual?.setupMode?.value = false }
    }
    val submit: () -> Unit = { vm.confirmServer { editServer = false; vm.restartDeviceFlow() } }
    Column(Modifier.fillMaxSize()) {
        RommTopBar(
            title = stringResource(if (editServer) R.string.ob_step_server else R.string.ob_step_auth),
            onBack = if (editServer) ({ editServer = false }) else null,
            actions = {
                // The address itself is the button: it says which server the code is for, and
                // A on it changes it (short screens drop the bar's subtitle, so not there).
                if (!editServer) {
                    androidx.compose.material3.TextButton(onClick = { editServer = true }, modifier = Modifier.gamepadFocusRing(PillShape)) {
                        Text(stringResource(R.string.login_server, state.serverInput.removePrefix("http://").removePrefix("https://").ifBlank { "?" }))
                        Spacer(Modifier.width(8.dp))
                        Icon(androidx.compose.material.icons.Icons.Rounded.Edit, contentDescription = stringResource(R.string.login_change_server), modifier = Modifier.size(18.dp))
                    }
                }
            },
        )
        Box(Modifier.weight(1f)) {
            if (editServer) {
                ServerStep(state, vm::onServerInput, vm::setAdvanced, onSubmit = submit)
                Button(
                    onClick = submit,
                    enabled = state.probe is ProbeState.Ok,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(RommTheme.layout.margin).padding(bottom = LocalNavBarInset.current).gamepadFocusRing(PillShape),
                ) { Text(stringResource(R.string.action_continue)) }
            } else {
                AuthStep(state, session, vm)
            }
        }
    }
}

/* ---------------------------------- step 2 ---------------------------------- */

@Composable
private fun ServerStep(
    state: OnboardingState,
    onInput: (String) -> Unit,
    onAdvanced: (String, String, Boolean) -> Unit,
    onSubmit: () -> Unit,
) {
    val colors = RommTheme.colors
    val layout = RommTheme.layout
    val fr = remember { FocusRequester() }
    var advanced by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = layout.margin, end = layout.margin, top = STEP_TOP_GAP, bottom = NEXT_BAR_CLEAR + LocalNavBarInset.current)) {
        Text(stringResource(R.string.ob_server_body), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(16.dp))
        RommTextField(
            value = state.serverInput,
            onValueChange = onInput,
            label = stringResource(R.string.ob_server_label),
            placeholder = "http://192.168.50.11:8080",
            leadingIcon = androidx.compose.material.icons.Icons.Rounded.Dns,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            // "Done" on the keyboard, or A on the field, goes on as soon as the server answered.
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { if (state.probe is ProbeState.Ok) onSubmit() }),
            modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp),
            fieldModifier = Modifier.focusRequester(fr).gamepadTextField(PillShape, onSubmit = { (state.probe is ProbeState.Ok).also { if (it) onSubmit() } }),
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (val p = state.probe) {
                ProbeState.Idle -> Text(stringResource(R.string.ob_server_idle), color = colors.onSurfaceMuted)
                ProbeState.Probing -> { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.ob_server_probing), color = colors.onSurfaceMuted) }
                is ProbeState.Ok -> { Icon(Icons.Rounded.CheckCircle, null, tint = colors.green); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.ob_server_ok, p.version ?: "?", p.url)) }
                is ProbeState.Error -> { Icon(Icons.Rounded.Error, null, tint = colors.red); Spacer(Modifier.width(8.dp)); Text(p.message, color = colors.red) }
            }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { advanced = true }, modifier = Modifier.gamepadFocusRing(PillShape)) {
            Text(stringResource(R.string.ob_server_advanced))
        }
        if (state.cfClientId.isNotBlank() || state.allowInsecureRemote) {
            Text(
                listOfNotNull(
                    state.cfClientId.takeIf { it.isNotBlank() }?.let { "Cloudflare Access" },
                    stringResource(R.string.settings_allow_insecure).takeIf { state.allowInsecureRemote },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceMuted,
            )
        }
    }
    if (advanced) {
        AdvancedServerDialog(state, onDismiss = { advanced = false }) { id, secret, insecure ->
            onAdvanced(id, secret, insecure); advanced = false
        }
    }
}

/** Cloudflare Access credentials and the plain-HTTP override, available before login. */
@Composable
private fun AdvancedServerDialog(
    state: OnboardingState,
    onDismiss: () -> Unit,
    onConfirm: (String, String, Boolean) -> Unit,
) {
    var id by remember { mutableStateOf(state.cfClientId) }
    var secret by remember { mutableStateOf(state.cfClientSecret) }
    var insecure by remember { mutableStateOf(state.allowInsecureRemote) }
    RommDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.ob_server_advanced),
        confirmText = stringResource(R.string.action_save),
        onConfirm = { onConfirm(id, secret, insecure) },
        focusConfirm = false,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.settings_cf_hint), style = MaterialTheme.typography.bodySmall, color = RommTheme.colors.onSurfaceMuted)
            Spacer(Modifier.height(8.dp))
            RommTextField(id, { id = it }, label = "CF-Access-Client-Id", modifier = Modifier.fillMaxWidth(), fieldModifier = Modifier.gamepadTextField(com.rommmobile.app.core.design.PillShape))
            Spacer(Modifier.height(8.dp))
            RommTextField(secret, { secret = it }, label = "CF-Access-Client-Secret", modifier = Modifier.fillMaxWidth(), fieldModifier = Modifier.gamepadTextField(com.rommmobile.app.core.design.PillShape))
            Spacer(Modifier.height(4.dp))
            SwitchRow(
                title = stringResource(R.string.settings_allow_insecure),
                checked = insecure,
                onCheckedChange = { insecure = it },
                subtitle = stringResource(R.string.settings_allow_insecure_hint),
            )
        }
    }
}

/* ---------------------------------- step 3 ---------------------------------- */

@Composable
private fun AuthStep(state: OnboardingState, session: Session, vm: OnboardingViewModel) {
    val colors = RommTheme.colors
    val layout = RommTheme.layout
    val context = LocalContext.current
    // The device flow is the default method, so nothing triggers setAuthMethod for it: start it
    // when the step is first shown, otherwise the panel sits on a spinner forever.
    LaunchedEffect(state.authMethod, session) {
        if (state.authMethod == AuthMethod.DEVICE && session !is Session.LoggedIn && state.device.init == null && state.device.error == null) {
            vm.restartDeviceFlow()
        }
    }

    // LB / RB flip the method, as they flip the tabs of a game page.
    val methods = AuthMethod.entries
    val hasPad by rememberHasGamepad()
    com.rommmobile.app.core.input.GamepadActions(state.authMethod, session) { action ->
        if (session is Session.LoggedIn) return@GamepadActions false
        val i = methods.indexOf(state.authMethod)
        when (action) {
            com.rommmobile.app.core.input.GamepadAction.PREV_LETTER -> { if (i > 0) vm.setAuthMethod(methods[i - 1]); true }
            com.rommmobile.app.core.input.GamepadAction.NEXT_LETTER -> { if (i < methods.lastIndex) vm.setAuthMethod(methods[i + 1]); true }
            else -> false
        }
    }

    // Arriving here from the address field, focus fell on the first thing left, the back arrow,
    // which then showed its focus circle for no reason. Nothing is focused on arrival: the method
    // is already shown selected, LB / RB change it, and the first d-pad press picks a target.
    // (The password form takes its own field.)
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(Unit) {
        if (state.authMethod != AuthMethod.PASSWORD && session !is Session.LoggedIn) {
            // After the step change has settled: the key release of the A that got here lands
            // a moment later and is what put focus on the arrow.
            delay(400)
            focusManager.clearFocus()
        }
    }

    Column(Modifier.fillMaxSize().padding(start = layout.margin, end = layout.margin, top = STEP_TOP_GAP)) {
        if (session is Session.LoggedIn) {
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CheckCircle, null, tint = colors.green); Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.ob_auth_logged_in, session.username ?: ""), style = MaterialTheme.typography.bodyLarge)
            }
        }
        // Each method says what it does right under the tabs: "Token" and "Password" mean
        // nothing on their own, and the user should not have to try one to find out.
        // Centred, like the code panel under it.
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            if (hasPad && session !is Session.LoggedIn) com.rommmobile.app.core.design.KeycapGlyph("LB", Modifier.padding(end = 4.dp))
            AuthMethod.entries.forEach { m ->
                val selected = m == state.authMethod
                Text(
                    stringResource(authMethodLabel(m)),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) colors.white else colors.onSurface,
                    modifier = Modifier.gamepadFocusRing(PillShape).clip(PillShape).background(if (selected) colors.primary else colors.surface).clickable { vm.setAuthMethod(m) }.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            if (hasPad && session !is Session.LoggedIn) com.rommmobile.app.core.design.KeycapGlyph("RB", Modifier.padding(start = 4.dp))
        }
        if (session !is Session.LoggedIn) {
            Text(
                stringResource(authMethodDesc(state.authMethod)),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp),
            )
        }
        val busy = state.auth == AuthUiState.Busy
        (state.auth as? AuthUiState.Error)?.let { Text(it.message, color = colors.red, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp)) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // Once signed in, the pairing panel would keep saying "waiting for approval".
            if (session is Session.LoggedIn) {
                Text(
                    stringResource(R.string.ob_auth_done),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            } else when (state.authMethod) {
                AuthMethod.DEVICE -> DeviceMethod(state.device, vm)
                AuthMethod.PASSWORD -> PasswordMethod(busy, vm)
                AuthMethod.TOKEN -> TokenMethod(busy, vm)
            }
        }
    }
}

private fun authMethodLabel(m: AuthMethod): Int = when (m) {
    AuthMethod.DEVICE -> R.string.auth_device
    AuthMethod.PASSWORD -> R.string.auth_password
    AuthMethod.TOKEN -> R.string.auth_token
}

private fun authMethodDesc(m: AuthMethod): Int = when (m) {
    AuthMethod.DEVICE -> R.string.auth_device_desc
    AuthMethod.PASSWORD -> R.string.auth_password_desc
    AuthMethod.TOKEN -> R.string.auth_token_desc
}


@Composable
private fun DeviceMethod(d: DeviceFlowState, vm: OnboardingViewModel) {
    val colors = RommTheme.colors
    val layout = RommTheme.layout
    // Centred in the space under the method's description (scrolls if it ever does not fit),
    // rather than pressed up against it.
    // Kept clear of the floating Next bar, so what is centred in here is centred in what shows.
    BoxWithConstraints(Modifier.fillMaxSize().padding(bottom = NEXT_BAR_CLEAR + LocalNavBarInset.current)) {
    val minH = maxHeight
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = minH),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val init = d.init
        when {
            d.error != null -> {
                Text(d.error, color = colors.red, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { vm.restartDeviceFlow() }, modifier = Modifier.gamepadFocusRing(PillShape)) { Text(stringResource(R.string.action_retry)) }
            }
            init == null -> CircularProgressIndicator()
            else -> {
                // One thing to do: point a phone at the code. The link already carries the
                // pairing code, so there is nothing left to type or to copy across.
                val qr = @Composable { QrImage(d.verificationUrl, Modifier.size(if (layout.isShort) 160.dp else 220.dp)) }
                val caption = @Composable {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            d.verificationUrl.removePrefix("http://").removePrefix("https://"),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceMuted,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.auth_device_waiting, d.secondsLeft / 60, d.secondsLeft % 60), color = colors.onSurfaceMuted)
                    }
                }
                // A short screen has no room for a stacked QR plus caption.
                if (layout.isShort) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        qr()
                        Spacer(Modifier.width(16.dp))
                        Box(Modifier.widthIn(max = 420.dp)) { caption() }
                    }
                } else {
                    qr()
                    Spacer(Modifier.height(10.dp))
                    caption()
                }
            }
        }
    }
}
}

@Composable
private fun PasswordMethod(busy: Boolean, vm: OnboardingViewModel) {
    var user by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = NEXT_BAR_CLEAR + LocalNavBarInset.current)) {
        RommTextField(user, { user = it }, label = stringResource(R.string.auth_username), leadingIcon = androidx.compose.material.icons.Icons.Rounded.Person, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth(), fieldModifier = Modifier.focusRequester(fr).gamepadTextField(PillShape))
        Spacer(Modifier.height(14.dp))
        RommTextField(pass, { pass = it }, label = stringResource(R.string.auth_password_field), leadingIcon = androidx.compose.material.icons.Icons.Rounded.Lock, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth(), fieldModifier = Modifier.gamepadTextField(PillShape))
        Spacer(Modifier.height(12.dp))
        Button(onClick = { vm.loginPassword(user, pass) }, enabled = !busy && user.isNotBlank() && pass.isNotEmpty(), modifier = Modifier.gamepadFocusRing(PillShape)) { Text(stringResource(R.string.action_login)) }
    }
}

@Composable
private fun TokenMethod(busy: Boolean, vm: OnboardingViewModel) {
    var token by rememberSaveable { mutableStateOf("") }
    val colors = RommTheme.colors
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = NEXT_BAR_CLEAR + LocalNavBarInset.current)) {
        Text(stringResource(R.string.auth_token_body), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceMuted)
        Spacer(Modifier.height(8.dp))
        RommTextField(token, { token = it }, placeholder = "rmm_…", leadingIcon = androidx.compose.material.icons.Icons.Rounded.Key, modifier = Modifier.fillMaxWidth(), fieldModifier = Modifier.gamepadTextField(PillShape))
        Spacer(Modifier.height(12.dp))
        Button(onClick = { vm.loginToken(token) }, enabled = !busy && token.isNotBlank(), modifier = Modifier.gamepadFocusRing(PillShape)) { Text(stringResource(R.string.action_login)) }
    }
}

/* ---------------------------------- step 4 ---------------------------------- */

@Composable
private fun LauncherStep(selected: Launcher, onSelect: (Launcher) -> Unit) {
    val colors = RommTheme.colors
    val layout = RommTheme.layout
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = (maxWidth / 200.dp).toInt().coerceIn(1, 4)
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            contentPadding = PaddingValues(start = layout.margin, end = layout.margin, top = STEP_TOP_GAP, bottom = NEXT_BAR_CLEAR + LocalNavBarInset.current),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(Launcher.entries, key = { it.id }) { l ->
                val isSel = l == selected
                val shape = MaterialTheme.shapes.large
                Column(
                    Modifier
                        .gamepadFocus(shape, scale = 1.03f)
                        .clip(shape)
                        .background(if (isSel) colors.primary.copy(alpha = 0.25f) else colors.surface)
                        .clickable { onSelect(l) }
                        .padding(12.dp)
                        .heightIn(min = 76.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(launcherLabel(l), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (isSel) Icon(Icons.Rounded.CheckCircle, null, tint = colors.primaryLighten)
                    }
                    Text(stringResource(launcherDescRes(l)), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/* ---------------------------------- step 1 ---------------------------------- */

/**
 * Which physical key is which printed button. Every hint in the app is drawn from this answer,
 * and the Nova and the Classic disagree on it. The recorder itself is owned by the screen (it
 * has to cover Next and Back too); this step shows what is known and offers to record again.
 * Next means "keep what the table shows".
 */
/**
 * Where A is printed on the pad, as two cards each drawing the diamond with A lit in its corner:
 * the one picked is what every on-screen hint will show.
 */
@Composable
internal fun FaceLayoutPicker(onLayout: (com.rommmobile.app.core.input.FaceLayout) -> Unit) {
    val colors = RommTheme.colors
    val current = com.rommmobile.app.core.input.PadLayout.face
    Text(stringResource(R.string.face_layout_title), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.face_layout_hint), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceMuted)
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        for (option in com.rommmobile.app.core.input.FaceLayout.entries) {
            val selected = option == current
            val shape = MaterialTheme.shapes.medium
            Row(
                Modifier
                    .gamepadFocusRing(shape)
                    .background(if (selected) colors.primary.copy(alpha = 0.14f) else colors.surface, shape)
                    .border(1.dp, if (selected) colors.primary else colors.onSurface.copy(alpha = 0.12f), shape)
                    .clickable { onLayout(option) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                com.rommmobile.app.core.design.FaceButtonsGlyph(PadButton.A, size = 26.dp, layout = option, tint = if (selected) colors.primaryLighten else null)
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(if (option == com.rommmobile.app.core.input.FaceLayout.XBOX) R.string.face_layout_xbox else R.string.face_layout_nintendo),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) colors.onSurface else colors.onSurfaceMuted,
                )
            }
        }
    }
}

/** Room left under a step's last line so it can scroll up clear of the floating Next bar. */
private val NEXT_BAR_CLEAR = 88.dp

/** Space between the header (title + progress line) and a step's first line. */
private val STEP_TOP_GAP = 28.dp

/** Space between blocks of a step: intro or detected-pad notice, then the mapping. */
private val SECTION_GAP = 32.dp

@Composable
private fun ControlsStep(state: OnboardingState, detectedPad: String?, onLayout: (com.rommmobile.app.core.input.FaceLayout) -> Unit, onRecord: () -> Unit) {
    val colors = RommTheme.colors
    val layout = RommTheme.layout
    val hasPad by rememberHasGamepad()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = layout.margin, end = layout.margin, top = STEP_TOP_GAP, bottom = NEXT_BAR_CLEAR + LocalNavBarInset.current)) {
        // The "press each button" intro only makes sense when the pad still has to be recorded.
        if (detectedPad == null) {
            Text(stringResource(R.string.controls_intro), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(SECTION_GAP))
        }
        if (!hasPad) {
            Text(stringResource(R.string.controls_no_pad), style = MaterialTheme.typography.bodyMedium, color = colors.accent)
        } else {
            if (detectedPad != null) {
                // Recognised: say which pad, and that nothing needs pressing.
                val shape = MaterialTheme.shapes.large
                Row(
                    Modifier.fillMaxWidth().background(colors.success.copy(alpha = 0.10f), shape)
                        .border(1.dp, colors.success.copy(alpha = 0.4f), shape).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(androidx.compose.material.icons.Icons.Rounded.CheckCircle, null, tint = colors.success, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(stringResource(R.string.controls_detected, detectedPad), style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
                        Text(stringResource(R.string.controls_auto), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceMuted)
                    }
                }
                Spacer(Modifier.height(SECTION_GAP))
            }
            FaceLayoutPicker(onLayout)
            Spacer(Modifier.height(SECTION_GAP))
            Text(stringResource(R.string.controls_current_title), style = MaterialTheme.typography.titleMedium)
            if (!state.buttonMapRecorded) {
                // Numbers that were never measured on this pad must not read as if they were.
                Text(stringResource(R.string.controls_default_caption), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceMuted)
            }
            Spacer(Modifier.height(8.dp))
            LogicalButton.entries.forEach { MappingRow(it, state.buttonMap, Modifier.padding(vertical = 6.dp)) }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onRecord, modifier = Modifier.gamepadFocusRing(PillShape)) {
                Text(stringResource(if (detectedPad != null) R.string.controls_customize else if (state.buttonMapRecorded) R.string.controls_redo else R.string.controls_map))
            }
        }
    }
}

/* ---------------------------------- step 5 ---------------------------------- */

@Composable
private fun StorageStep(state: OnboardingState, vm: OnboardingViewModel) {
    val colors = RommTheme.colors
    val layout = RommTheme.layout
    val context = LocalContext.current
    var chooser by remember { mutableStateOf(false) }
    // Order matters here: access first, folder second. Without All files access the picked folder
    // silently becomes a SAF tree that every write has to go through, so the choice must not be
    // offered before the permission has been dealt with.
    var allFiles by remember { mutableStateOf(StorageLocations.hasAllFilesAccess(context)) }
    var askedOnce by rememberSaveable { mutableStateOf(false) }
    val grantIntent = remember { StorageLocations.allFilesAccessIntent(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val now = StorageLocations.hasAllFilesAccess(context)
                if (now != allFiles) {
                    allFiles = now
                    // Same folder, new answer: what was read-only a moment ago is writable now.
                    vm.recheckRoot()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { askedOnce = true }
    // The folder step is reachable once access is granted, or when this device has no screen to
    // grant it on, or after the user has been to that screen once and come back without it: the
    // sequence is enforced, but nobody is left with no way forward.
    val canChoose = allFiles || grantIntent == null || askedOnce
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = layout.margin, end = layout.margin, top = STEP_TOP_GAP, bottom = NEXT_BAR_CLEAR + LocalNavBarInset.current)) {
        Text(stringResource(R.string.ob_storage_body), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(if (allFiles) R.string.ob_storage_allfiles_ok else R.string.ob_storage_allfiles_missing), style = MaterialTheme.typography.bodyMedium, color = if (allFiles) colors.green else colors.accent)
        Spacer(Modifier.height(16.dp))
        val root = state.root
        if (root != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Folder, null, tint = colors.primaryLighten); Spacer(Modifier.width(8.dp))
                Column {
                    Text(root.displayName, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        when (state.rootWritable) { null -> stringResource(R.string.ob_storage_checking); true -> stringResource(R.string.ob_storage_writable); false -> stringResource(R.string.ob_storage_not_writable) },
                        style = MaterialTheme.typography.bodySmall, color = if (state.rootWritable == false) colors.red else colors.onSurfaceMuted,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        val fr = remember { FocusRequester() }
        LaunchedEffect(canChoose) { runCatching { fr.requestFocus() } }
        when {
            !canChoose -> {
                // Step one, and the only button on screen until it is done.
                Button(onClick = { settingsLauncher.launch(grantIntent!!) }, modifier = Modifier.focusRequester(fr).gamepadFocusRing(PillShape)) {
                    Icon(Icons.Rounded.FolderOpen, null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.root_allfiles_grant))
                }
            }
            root == null ->
                Button(onClick = { chooser = true }, modifier = Modifier.focusRequester(fr).gamepadFocusRing(PillShape)) { Text(stringResource(R.string.ob_storage_choose)) }
            else ->
                OutlinedButton(onClick = { chooser = true }, modifier = Modifier.focusRequester(fr).gamepadFocusRing(PillShape)) { Text(stringResource(R.string.ob_storage_change)) }
        }
        if (canChoose && !allFiles && grantIntent != null) {
            // Came back without granting: the folder is offered, the grant stays one press away as
            // the quieter option, whether a folder is already set or not.
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = { settingsLauncher.launch(grantIntent) }, modifier = Modifier.gamepadFocusRing(PillShape)) {
                Text(stringResource(R.string.root_allfiles_grant))
            }
        }
    }
    if (chooser) RootChooserDialog(preferredNames = vm.rootNames, onChosen = { vm.setRoot(it); chooser = false }, onDismiss = { chooser = false })
}

/* ---------------------------------- step 6 ---------------------------------- */

@Composable
private fun SummaryStep(state: OnboardingState, vm: OnboardingViewModel) {
    val colors = RommTheme.colors
    val layout = RommTheme.layout
    var editing by remember { mutableStateOf<MappingRow?>(null) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    Column(Modifier.fillMaxSize()) {
        val found = state.summary.count { it.status == MappingStatus.FOUND }
        Text(
            if (state.summaryBusy) stringResource(R.string.ob_summary_scanning) else stringResource(R.string.ob_summary_body, found, state.summary.size),
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = layout.margin, end = layout.margin, top = STEP_TOP_GAP, bottom = 8.dp),
        )
        if (state.summaryBusy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().rightStickScroll(listState), contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 4.dp, bottom = NEXT_BAR_CLEAR + LocalNavBarInset.current)) {
            items(state.summary, key = { it.platform.id }) { row ->
                val (icon, tint, text) = when (row.status) {
                    MappingStatus.FOUND -> Triple(Icons.Rounded.CheckCircle, colors.green, row.mapping!!.relDir)
                    MappingStatus.TO_CREATE -> Triple(Icons.Rounded.CreateNewFolder, colors.accent, stringResource(R.string.mappings_to_create, row.proposal ?: row.platform.slug))
                    MappingStatus.TO_CHOOSE -> Triple(Icons.Rounded.Edit, colors.blue, stringResource(R.string.mappings_to_choose, row.candidates.joinToString(", ")))
                    MappingStatus.UNKNOWN -> Triple(Icons.Rounded.Edit, colors.gray, stringResource(R.string.mappings_unknown))
                }
                SettingRow(title = row.platform.name, subtitle = text, onClick = { editing = row }) { Icon(icon, null, tint = tint) }
            }
        }
    }
    editing?.let { row ->
        MappingEditDialog(
            row = row,
            rootDirs = state.rootDirs,
            onChoose = { vm.summaryChoose(row.platform.slug, it); editing = null },
            onCreate = { vm.summaryCreate(row.platform.slug, it); editing = null },
            onClear = { vm.summaryClear(row.platform.slug); editing = null },
            onDismiss = { editing = null },
        )
    }
}
