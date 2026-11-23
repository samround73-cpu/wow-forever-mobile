package app.gamenative.ui.screen.xserver

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gamenative.R
import app.gamenative.externaldisplay.IMEInputReceiver
import app.gamenative.ui.screen.wow.MobileDiagnosticsDialog
import app.gamenative.ui.screen.wow.usesTabletLayout
import com.winlator.xserver.XKeycode
import kotlin.math.roundToInt

private val overlayBackground = Color(0xEB191610)
private val overlayBorder = Color(0xB2977846)
private val overlayAccent = Color(0xFFEAC783)
private val overlayText = Color(0xFFF5E5C9)
private val socialShortcuts = listOf(
    R.string.mobile_friends to XKeycode.KEY_END,
    R.string.mobile_guild to XKeycode.KEY_PRIOR,
    R.string.mobile_group_finder to XKeycode.KEY_NEXT,
)

@Composable
internal fun BoxScope.MobileGameOverlay(
    controls: MobileTouchControls,
    receiver: IMEInputReceiver?,
    visible: Boolean,
    canPlayWithTouch: Boolean,
    fullscreen: Boolean?,
    onToggleFullscreen: () -> Unit,
    loginFailed: Boolean,
    browserConnectionStalled: Boolean,
    onRetryBrowserLogin: () -> Unit,
) {
    if (!visible || receiver == null || controls.overlayHidden) return
    DisposableEffect(controls, receiver, canPlayWithTouch) {
        onDispose {
            if (controls.chatting) {
                controls.closeChat()
                receiver.hideKeyboard()
            }
            controls.closeHudEditor()
            controls.releaseAll()
        }
    }
    val playing = controls.playing && canPlayWithTouch
    val chatting = playing && controls.chatting
    val editor = controls.hudEditor.takeIf { playing }
    val editingControls = editor == MobileHudEditor.Controls
    var showMenu by remember { mutableStateOf(false) }
    var showSocial by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var showSensitivity by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("forever_touch", Context.MODE_PRIVATE) }
    var toolbarMinimized by remember(preferences) { mutableStateOf(preferences.getBoolean("toolbar_minimized", false)) }
    var positions by remember(preferences) { mutableStateOf(readMobileHudPositions(preferences)) }
    var draftPositions by remember { mutableStateOf(positions) }
    var sensitivity by remember(preferences) {
        mutableFloatStateOf(preferences.getFloat("camera_sensitivity", 1f).coerceIn(.55f, 1.8f))
    }
    SideEffect { controls.lookSensitivity = sensitivity }

    val compactToolbar = toolbarMinimized && !chatting && editor == null
    fun setToolbarMinimized(minimized: Boolean) {
        toolbarMinimized = minimized
        preferences.edit().putBoolean("toolbar_minimized", minimized).apply()
    }

    fun finishEditor(save: Boolean) {
        if (save && editingControls) {
            writeMobileHudPositions(preferences, draftPositions)
            positions = draftPositions
        }
        controls.closeHudEditor(save)
    }

    BackHandler(enabled = editor != null) { finishEditor(save = !editingControls) }

    fun finishChat(send: Boolean) {
        controls.closeChat(send)
        receiver.hideKeyboard()
    }

    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val tabletDensity = if (LocalConfiguration.current.usesTabletLayout) density.density else null
    // XServerScreen pads both the renderer and overlay for visible system bars.
    val insets = WindowInsets.safeDrawing.exclude(WindowInsets.systemBars)
    val left = insets.getLeft(density, direction).toFloat()
    val right = insets.getRight(density, direction).toFloat()
    val top = insets.getTop(density).toFloat()
    val bottom = insets.getBottom(density).toFloat()
    val topMargin = with(density) { 5.dp.toPx() }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var toolbar by remember { mutableStateOf(IntSize.Zero) }

    Box(Modifier.matchParentSize().onSizeChanged { viewport = it }) {
        val activePositions = if (editingControls) draftPositions else positions
        val safeArea = Rect(left, top, viewport.width - right, viewport.height - bottom)
        val layout = remember(viewport, safeArea, activePositions, tabletDensity) {
            MobileLayout.fit(
                viewport.width.toFloat(), viewport.height.toFloat(), left, right, bottom, top,
                positions = activePositions, tabletDensity = tabletDensity,
            )
        }
        val toolbarSize = if (compactToolbar) with(density) { 48.dp.roundToPx().let { IntSize(it, it) } } else toolbar
        val defaultToolbar = Rect(
            Offset(safeArea.center.x - toolbarSize.width / 2f, top + topMargin),
            Size(toolbarSize.width.toFloat(), toolbarSize.height.toFloat()),
        )
        val toolbarBounds = safeArea.placeMobileHudElement(defaultToolbar, activePositions[MobileHudElement.Toolbar])

        if (playing && !chatting && editor != MobileHudEditor.Skills && viewport != IntSize.Zero) {
            SideEffect { controls.updateLayout(layout) }
            MobileControlSurface(controls, layout, editingControls)
        }

        if (editor != MobileHudEditor.Skills) {
            val toolbarModifier = if (playing && !chatting && safeArea.width > 0f) {
                Modifier.align(Alignment.TopCenter)
                    .absoluteOffset {
                        val centeredLeft = (viewport.width - toolbarSize.width) / 2f
                        IntOffset((toolbarBounds.left - centeredLeft).roundToInt(), toolbarBounds.top.roundToInt())
                    }
                    .widthIn(max = with(density) { safeArea.width.toDp() })
                    .onSizeChanged { if (!compactToolbar) toolbar = it }
            } else {
                Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 5.dp)
                    .onSizeChanged { if (!compactToolbar) toolbar = it }
            }
            Surface(
                modifier = toolbarModifier,
                border = BorderStroke(1.dp, if (editingControls) overlayAccent else overlayBorder),
                color = overlayBackground,
                contentColor = overlayText,
                shape = RoundedCornerShape(16.dp),
                shadowElevation = 3.dp,
            ) {
                if (compactToolbar) {
                    IconButton(onClick = { setToolbarMinimized(false) }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.mobile_expand_toolbar), tint = overlayAccent)
                    }
                    return@Surface
                }
                Row(
                    modifier = Modifier.padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (chatting) {
                        ToolbarText(R.string.keyboard, Icons.Filled.Keyboard) {
                            controls.tap(XKeycode.KEY_F8, modified = true)
                            receiver.showKeyboard { finishChat(send = true) }
                        }
                        ToolbarText(R.string.mobile_paste, Icons.Filled.ContentPaste) { receiver.pasteFromClipboard(chat = true) }
                        ToolbarText(R.string.mobile_chat_send, accent = true) { finishChat(send = true) }
                        ToolbarText(android.R.string.cancel) { finishChat(send = false) }
                    } else if (playing) {
                        Row(
                            modifier = Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ToolbarText(R.string.mobile_chat, Icons.Filled.ChatBubbleOutline, accent = true, enabled = !editingControls) {
                                controls.setChatActive(true)
                                controls.tap(XKeycode.KEY_F8, modified = true)
                                receiver.showKeyboard { finishChat(send = true) }
                            }
                            ToolbarText(R.string.mobile_character, enabled = !editingControls) { controls.tap(XKeycode.KEY_F3, modified = true) }
                            Box {
                                ToolbarText(R.string.mobile_social, enabled = !editingControls) { showSocial = true }
                                DropdownMenu(expanded = showSocial, onDismissRequest = { showSocial = false }) {
                                    for ((label, key) in socialShortcuts) {
                                        DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = {
                                            showSocial = false
                                            controls.tap(key, modified = true)
                                        })
                                    }
                                }
                            }
                            ToolbarText(R.string.mobile_bags, enabled = !editingControls) { controls.tap(XKeycode.KEY_F9, modified = true) }
                            ToolbarText(R.string.mobile_map, enabled = !editingControls) { controls.tap(XKeycode.KEY_F11, modified = true) }
                            ToolbarText(R.string.mobile_professions, enabled = !editingControls) { controls.tap(XKeycode.KEY_INSERT, modified = true) }
                            ToolbarText(R.string.mobile_talents, enabled = !editingControls) { controls.tap(XKeycode.KEY_F4, modified = true) }
                            ToolbarText(R.string.mobile_quests, enabled = !editingControls) { controls.tap(XKeycode.KEY_F5, modified = true) }
                            ToolbarText(R.string.mobile_spellbook, enabled = !editingControls) { controls.tap(XKeycode.KEY_F6, modified = true) }
                        }
                    } else {
                        ToolbarText(R.string.keyboard, Icons.Filled.Keyboard, onClick = receiver::showKeyboard)
                        ToolbarText(R.string.mobile_paste, Icons.Filled.ContentPaste) { receiver.pasteFromClipboard() }
                    }

                    if (loginFailed && !playing && canPlayWithTouch) {
                        ToolbarText(R.string.mobile_retry_browser_login, accent = true, onClick = onRetryBrowserLogin)
                    }
                    if (canPlayWithTouch && !playing) {
                        ToolbarText(
                            R.string.mobile_play_controls,
                            icon = Icons.Filled.SportsEsports,
                            accent = true,
                        ) {
                            receiver.hideKeyboard()
                            controls.togglePlaying()
                        }
                    }

                    if (!chatting && editor == null) {
                        IconButton(onClick = { setToolbarMinimized(true) }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.mobile_collapse_toolbar))
                        }
                    }

                    if (!chatting) Box {
                        IconButton(onClick = { showMenu = true }, modifier = Modifier.size(44.dp), enabled = !editingControls) {
                            Icon(Icons.Filled.MoreVert, stringResource(R.string.mobile_more))
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            if (loginFailed && !playing && !canPlayWithTouch) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.mobile_retry_browser_login)) }, onClick = {
                                    showMenu = false
                                    onRetryBrowserLogin()
                                })
                            }
                            if (playing) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.mobile_edit_layout)) }, onClick = {
                                    showMenu = false
                                    draftPositions = positions
                                    controls.openHudEditor(MobileHudEditor.Controls)
                                })
                                DropdownMenuItem(text = { Text(stringResource(R.string.mobile_arrange_skills)) }, onClick = {
                                    showMenu = false
                                    controls.openHudEditor(MobileHudEditor.Skills)
                                })
                                DropdownMenuItem(text = { Text(stringResource(R.string.mobile_collections)) }, onClick = {
                                    showMenu = false
                                    controls.tap(XKeycode.KEY_DEL, modified = true)
                                })
                                DropdownMenuItem(text = { Text(stringResource(R.string.mobile_frame_limit)) }, onClick = {
                                    showMenu = false
                                    controls.tap(XKeycode.KEY_F, modified = true)
                                })
                                DropdownMenuItem(text = { Text(stringResource(R.string.mobile_camera_sensitivity)) }, onClick = {
                                    showMenu = false
                                    showSensitivity = true
                                })
                                DropdownMenuItem(text = { Text(stringResource(R.string.mobile_login_controls)) }, onClick = {
                                    showMenu = false
                                    controls.togglePlaying()
                                    receiver.hideKeyboard()
                                })
                            }
                            DropdownMenuItem(text = { Text(stringResource(R.string.mobile_game_menu)) }, onClick = {
                                showMenu = false
                                controls.tap(XKeycode.KEY_ESC)
                            })
                            if (fullscreen != null) {
                                val action = if (fullscreen) R.string.mobile_exit_fullscreen else R.string.mobile_enter_fullscreen
                                DropdownMenuItem(text = { Text(stringResource(action)) }, onClick = {
                                    showMenu = false
                                    onToggleFullscreen()
                                })
                            }
                            DropdownMenuItem(text = { Text(stringResource(R.string.mobile_hide_touch_overlay)) }, onClick = {
                                showMenu = false
                                controls.toggleOverlayHidden(context)
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.mobile_diagnostics)) }, onClick = {
                                showMenu = false
                                showDiagnostics = true
                            })
                        }
                    }
                }
            }
        }

        if (browserConnectionStalled && !playing && !compactToolbar && toolbar.height > 0) {
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).safeDrawingPadding()
                    .padding(top = with(density) { toolbar.height.toDp() } + 9.dp),
                border = BorderStroke(1.dp, overlayBorder),
                color = overlayBackground,
                shape = RoundedCornerShape(16.dp),
            ) {
                ToolbarText(R.string.mobile_check_connection, accent = true) { showDiagnostics = true }
            }
        }

        if (editingControls && !safeArea.isEmpty) {
            val elements = mapOf(
                MobileHudElement.Movement to layout.stick,
                MobileHudElement.Camera to layout.camera,
                MobileHudElement.Toolbar to toolbarBounds,
            ) + layout.shortcuts.mapKeys { it.key.element }
            MobileHudDragLayer(elements, safeArea) { element, position ->
                draftPositions = draftPositions + (element to position)
            }
        }
        if (editor != null) {
            MobileHudEditorBar(
                editor = editor,
                onReset = {
                    if (editingControls) draftPositions = emptyMap() else controls.resetSkillsEditor()
                },
                onCancel = { finishEditor(save = false) },
                onSave = { finishEditor(save = true) },
            )
        }
    }

    if (showDiagnostics) {
        MobileDiagnosticsDialog(onRetryBrowserLogin.takeIf { browserConnectionStalled && !playing }) {
            showDiagnostics = false
        }
    }
    if (showSensitivity) {
        AlertDialog(
            onDismissRequest = { showSensitivity = false },
            title = { Text(stringResource(R.string.mobile_camera_sensitivity)) },
            text = {
                Column {
                    Text("${(sensitivity * 100).roundToInt()} %")
                    Slider(
                        value = sensitivity,
                        onValueChange = { sensitivity = it },
                        onValueChangeFinished = { preferences.edit().putFloat("camera_sensitivity", sensitivity).apply() },
                        valueRange = .55f..1.8f,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showSensitivity = false }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    sensitivity = 1f
                    preferences.edit().remove("camera_sensitivity").apply()
                }) { Text(stringResource(R.string.mobile_reset)) }
            },
        )
    }
}

@Composable
private fun ToolbarText(
    label: Int,
    icon: ImageVector? = null,
    accent: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(
            contentColor = if (accent) overlayAccent else overlayText,
            disabledContentColor = overlayText.copy(alpha = .74f),
        ),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp),
    ) {
        if (icon != null) {
            Icon(icon, null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(stringResource(label), style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

@Composable
private fun BoxScope.MobileHudEditorBar(
    editor: MobileHudEditor,
    onReset: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    Surface(
        modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 6.dp),
        color = overlayBackground,
        border = BorderStroke(1.dp, overlayBorder),
        shape = RoundedCornerShape(14.dp),
        shadowElevation = 3.dp,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(
                    if (editor == MobileHudEditor.Controls) R.string.mobile_edit_layout_hint
                    else R.string.mobile_edit_skills_hint,
                ),
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 7.dp),
                style = MaterialTheme.typography.labelMedium,
                color = overlayText,
            )
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                ToolbarText(R.string.mobile_layout_default, onClick = onReset)
                val skills = editor == MobileHudEditor.Skills
                ToolbarText(if (skills) R.string.mobile_layout_discard else android.R.string.cancel, onClick = onCancel)
                ToolbarText(if (skills) R.string.mobile_layout_done else R.string.mobile_layout_save, accent = true, onClick = onSave)
            }
        }
    }
}

@Composable
private fun BoxScope.MobileHudDragLayer(
    elements: Map<MobileHudElement, Rect>,
    safeArea: Rect,
    onMove: (MobileHudElement, Offset) -> Unit,
) {
    val current by rememberUpdatedState(elements)
    val move by rememberUpdatedState(onMove)
    Box(Modifier.matchParentSize().pointerInput(safeArea) {
        awaitEachGesture {
            val down = awaitFirstDown()
            down.consume()
            val element = current.entries.firstOrNull { (_, bounds) -> bounds.contains(down.position) }
                ?: return@awaitEachGesture
            drag(down.id) { change ->
                val center = element.value.center + (change.position - down.position)
                val position = safeArea.mobileHudPosition(element.value, center)
                val moved = safeArea.placeMobileHudElement(element.value, position)
                if (current.none { (other, bounds) -> other != element.key && moved.overlaps(bounds) }) {
                    move(element.key, position)
                }
                change.consume()
            }
        }
    })
}

@Composable
private fun BoxScope.MobileControlSurface(controls: MobileTouchControls, layout: MobileLayout, editing: Boolean) {
    val measurer = rememberTextMeasurer()
    val cameraLabel = stringResource(R.string.mobile_camera)
    val jumpLabel = stringResource(R.string.mobile_jump)
    val targetLabel = stringResource(R.string.mobile_target)
    val interactLabel = stringResource(R.string.mobile_interact)

    Canvas(Modifier.matchParentSize()) {
        if (layout.stick.width <= 0f) return@Canvas
        val radius = layout.stick.width / 2f
        val center = controls.stickAnchor?.let {
            val inset = radius * .70f
            Offset(
                it.x.coerceIn(layout.stick.left + inset, layout.stick.right - inset),
                it.y.coerceIn(layout.stick.top + inset, layout.stick.bottom - inset),
            )
        } ?: layout.stick.center
        val outline = Stroke(1.dp.toPx())
        val rim = if (editing) overlayAccent else overlayBorder
        drawCircle(
            brush = Brush.radialGradient(listOf(Color(0x861A140D), Color(0xC21D1911)), center, radius),
            radius = radius,
            center = center,
        )
        drawCircle(rim, radius, center, style = outline)
        drawCircle(rim.copy(alpha = .28f), radius * .88f, center, style = outline)
        drawCircle(
            color = overlayAccent.copy(alpha = .72f),
            radius = radius * .36f,
            center = center + controls.stickPosition * (radius * .53f),
        )
        val labels = mapOf(MobileShortcut.Jump to jumpLabel, MobileShortcut.Target to targetLabel, MobileShortcut.Interact to interactLabel)
        for ((shortcut, bounds) in layout.shortcuts) {
            val rounding = CornerRadius(bounds.height / 2f)
            drawRoundRect(
                overlayBackground.copy(alpha = .76f),
                topLeft = bounds.topLeft,
                size = bounds.size,
                cornerRadius = rounding,
            )
            drawRoundRect(
                rim,
                topLeft = bounds.topLeft,
                size = bounds.size,
                cornerRadius = rounding,
                style = outline,
            )
            val label = labels.getValue(shortcut)
            val text = measurer.measure(
                if (shortcut == MobileShortcut.Jump) "↑\n$label" else label,
                TextStyle(color = overlayText, fontSize = 12.sp, textAlign = TextAlign.Center),
            )
            drawText(text, topLeft = bounds.center - Offset(text.size.width / 2f, text.size.height / 2f))
        }
        val cameraCorner = CornerRadius(layout.camera.height * .36f)
        drawRoundRect(
            overlayBackground.copy(alpha = if (controls.cameraHeld) .32f else .19f),
            topLeft = layout.camera.topLeft,
            size = layout.camera.size,
            cornerRadius = cameraCorner,
        )
        val cameraMark = measurer.measure("↔  $cameraLabel", TextStyle(color = overlayText.copy(alpha = .84f), fontSize = 11.sp))
        drawText(cameraMark, topLeft = layout.camera.center - Offset(cameraMark.size.width / 2f, cameraMark.size.height / 2f))
        drawRoundRect(
            if (controls.cameraHeld) overlayAccent else rim,
            topLeft = layout.camera.topLeft,
            size = layout.camera.size,
            cornerRadius = cameraCorner,
            style = if (controls.cameraHeld || editing) outline else Stroke(
                width = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
            ),
        )
    }
}
