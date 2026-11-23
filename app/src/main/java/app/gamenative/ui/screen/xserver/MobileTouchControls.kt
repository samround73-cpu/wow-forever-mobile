package app.gamenative.ui.screen.xserver

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.winlator.widget.TouchpadView
import com.winlator.winhandler.MouseEventFlags
import com.winlator.xserver.Pointer
import com.winlator.xserver.XKeycode
import com.winlator.xserver.XServer
import kotlin.math.abs

/** Routes fingers independently so a held stick doesn't count as a second tap in the game. */
internal class MobileTouchControls(private val touchSlop: Float) {
    var playing by mutableStateOf(false)
        private set
    var stickPosition by mutableStateOf(Offset.Zero)
        private set
    var stickAnchor by mutableStateOf<Offset?>(null)
        private set
    var cameraHeld by mutableStateOf(false)
        private set
    var chatting by mutableStateOf(false)
        private set
    var hudEditor by mutableStateOf<MobileHudEditor?>(null)
        private set

    /** Hides the whole touch overlay (stick, camera zone, shortcuts and toolbar) for keyboard/controller play. */
    var overlayHidden by mutableStateOf(false)
        private set
    var lookSensitivity = 1f
    private val cameraSlop = (touchSlop * .35f).coerceAtLeast(1f)

    private var server: XServer? = null
    private var touchpad: TouchpadView? = null
    private var layout: MobileLayout? = null
    private var stickId = NO_POINTER
    private var cameraId = NO_POINTER
    private val shortcutPointers = mutableMapOf<Int, MobileShortcut>()
    private var cameraOrigin = Offset.Zero
    private var previousCameraPosition = Offset.Zero
    private var cameraStarted = false
    private var rightViaHandler = false
    private val gamePointers = mutableSetOf<Int>()
    private var editorPointer = NO_POINTER
    private var heldKeys = 0

    fun attach(server: XServer, touchpad: TouchpadView) {
        releaseAll()
        this.server = server
        this.touchpad = touchpad
    }

    fun updateLayout(layout: MobileLayout) {
        if (this.layout == layout) return
        releaseAll()
        this.layout = layout
    }

    fun togglePlaying() {
        if (playing) {
            useLoginControls()
            return
        }
        releaseAll()
        // Flush the touch gesture that may still be active when entering this mode.
        touchpad?.cancelTouchInput()
        playing = true
    }

    fun useLoginControls() {
        if (!playing) return
        closeHudEditor()
        releaseAll()
        touchpad?.cancelTouchInput()
        chatting = false
        playing = false
    }

    fun setChatActive(active: Boolean) {
        releaseAll()
        touchpad?.cancelTouchInput()
        chatting = active
    }

    fun closeChat(send: Boolean = false) {
        if (!chatting) return
        setChatActive(false)
        tap(if (send) XKeycode.KEY_ENTER else XKeycode.KEY_ESC)
    }

    fun openHudEditor(editor: MobileHudEditor) {
        if (!playing || chatting || hudEditor != null) return
        releaseAll()
        touchpad?.cancelTouchInput()
        hudEditor = editor
        if (editor == MobileHudEditor.Skills) tap(XKeycode.KEY_F7, modified = true)
    }

    fun resetSkillsEditor() {
        if (hudEditor == MobileHudEditor.Skills) tap(XKeycode.KEY_HOME, modified = true)
    }

    fun closeHudEditor(save: Boolean = true) {
        val editor = hudEditor ?: return
        releaseAll()
        if (editor == MobileHudEditor.Skills) {
            tap(if (save) XKeycode.KEY_F1 else XKeycode.KEY_F2, modified = true)
        }
        hudEditor = null
    }

    fun releaseAll() {
        stopStick()
        stopCamera()
        releaseEditorPointer()
        if (gamePointers.isNotEmpty()) touchpad?.cancelTouchInput()
        gamePointers.clear()
        stickId = NO_POINTER
        cameraId = NO_POINTER
        shortcutPointers.clear()
        cameraStarted = false
    }

    fun detach() {
        closeHudEditor()
        releaseAll()
        server = null
        touchpad = null
        chatting = false
    }

    fun tap(key: XKeycode, modified: Boolean = false) {
        val game = server ?: return
        if (modified) {
            game.injectKeyPress(XKeycode.KEY_CTRL_L, 0, false)
            game.injectKeyPress(XKeycode.KEY_SHIFT_L, 0, false)
        }
        try {
            game.injectKeyPress(key, 0, false)
            game.injectKeyRelease(key)
        } finally {
            if (modified) {
                game.injectKeyRelease(XKeycode.KEY_SHIFT_L)
                game.injectKeyRelease(XKeycode.KEY_CTRL_L)
            }
        }
    }

    fun loadPreferences(context: Context) {
        overlayHidden = preferences(context).getBoolean(KEY_OVERLAY_HIDDEN, false)
    }

    fun toggleOverlayHidden(context: Context) {
        val hidden = !overlayHidden
        // The overlay's dispose effect closes chat and editors; drop held keys here too.
        if (hidden) releaseAll()
        overlayHidden = hidden
        preferences(context).edit().putBoolean(KEY_OVERLAY_HIDDEN, hidden).apply()
    }

    fun route(event: MotionEvent): Boolean {
        if (!playing || overlayHidden || touchpad == null) return false
        when (hudEditor) {
            MobileHudEditor.Controls -> return true
            MobileHudEditor.Skills -> {
                routeEditorPointer(event)
                return true
            }
            null -> Unit
        }
        val geometry = layout ?: return false
        if (event.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER) return false
        if (chatting) {
            routeChat(event)
            return true
        }

        val index = event.actionIndex
        val pointerId = event.getPointerId(index)
        val position = event.position(index)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) releaseAll()
                when {
                    geometry.stick.contains(position) -> {
                        if (stickId == NO_POINTER) {
                            stickId = pointerId
                            stickAnchor = position
                        }
                    }
                    geometry.camera.contains(position) -> {
                        if (cameraId == NO_POINTER) {
                            cameraId = pointerId
                            cameraOrigin = position
                            previousCameraPosition = position
                            cameraStarted = false
                        }
                    }
                    else -> {
                        val shortcut = geometry.shortcutAt(position)
                        if (shortcut != null) {
                            shortcutPointers[pointerId] = shortcut
                        } else {
                            // Release right-drag before clicking a different target or UI button.
                            stopCamera()
                            gamePointers.add(pointerId)
                            forward(event)
                        }
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val stickIndex = event.findPointerIndex(stickId)
                if (stickIndex >= 0) moveStick(event.position(stickIndex), geometry.stick.width * .37f)
                val cameraIndex = event.findPointerIndex(cameraId)
                if (cameraIndex >= 0) moveCamera(event.position(cameraIndex))
                forward(event)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                when (pointerId) {
                    stickId -> {
                        stopStick()
                        stickId = NO_POINTER
                    }
                    cameraId -> {
                        val canceled = event.flags and MotionEvent.FLAG_CANCELED != 0
                        if (!canceled) moveCamera(position)
                        stopCamera()
                        cameraId = NO_POINTER
                        if (!canceled && !cameraStarted && gamePointers.isEmpty()) {
                            touchpad?.tapFromMobileLook(position.x, position.y)
                        }
                    }
                    else -> {
                        val shortcut = shortcutPointers.remove(pointerId)
                        if (shortcut != null && event.flags and MotionEvent.FLAG_CANCELED == 0 &&
                            geometry.shortcutAt(position) == shortcut
                        ) {
                            tap(shortcut.key, shortcut.modified)
                        } else if (pointerId in gamePointers) {
                            forward(event)
                            gamePointers.remove(pointerId)
                        }
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        return true
    }

    private fun routeEditorPointer(event: MotionEvent) {
        val pad = touchpad ?: return
        val index = event.actionIndex
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                releaseEditorPointer()
                val position = event.position(index)
                if (pad.beginMobileEditorTouch(position.x, position.y)) editorPointer = event.getPointerId(index)
            }
            MotionEvent.ACTION_MOVE -> {
                val active = event.findPointerIndex(editorPointer)
                if (active >= 0) {
                    val position = event.position(active)
                    pad.moveMobileEditorTouch(position.x, position.y)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(index) == editorPointer) {
                    if (event.flags and MotionEvent.FLAG_CANCELED == 0) {
                        val position = event.position(index)
                        pad.moveMobileEditorTouch(position.x, position.y)
                    }
                    releaseEditorPointer()
                }
            }
            MotionEvent.ACTION_CANCEL -> releaseEditorPointer()
        }
    }

    private fun releaseEditorPointer() {
        if (editorPointer == NO_POINTER) return
        touchpad?.endMobileEditorTouch()
        editorPointer = NO_POINTER
    }

    private fun routeChat(event: MotionEvent) {
        val index = event.actionIndex
        val id = event.getPointerId(index)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) releaseAll()
                if (touchpad?.isMobileChatTouch(event.getX(index), event.getY(index)) == true) {
                    gamePointers.add(id)
                    forward(event)
                }
            }
            MotionEvent.ACTION_MOVE -> forward(event)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> if (id in gamePointers) {
                forward(event)
                gamePointers.remove(id)
            }
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
    }

    private fun moveStick(position: Offset, radius: Float) {
        val anchor = stickAnchor ?: return
        val delta = (position - anchor) / radius
        val distance = delta.getDistance()
        stickPosition = if (distance > 1f) delta / distance else delta
        holdMovement(movementKeys(delta.x, delta.y, heldKeys))
    }

    private fun stopStick() {
        holdMovement(0)
        stickAnchor = null
        stickPosition = Offset.Zero
    }

    private fun holdMovement(keys: Int) {
        if (keys == heldKeys) return
        MOVEMENT_KEYS.forEachIndexed { index, key ->
            val flag = 1 shl index
            if (heldKeys and flag != 0 && keys and flag == 0) server?.injectKeyRelease(key)
        }
        MOVEMENT_KEYS.forEachIndexed { index, key ->
            val flag = 1 shl index
            if (keys and flag != 0 && heldKeys and flag == 0) server?.injectKeyPress(key, 0, false)
        }
        heldKeys = keys
    }

    private fun moveCamera(position: Offset) {
        if (!cameraStarted && (position - cameraOrigin).getDistance() < cameraSlop) return
        cameraStarted = true
        val delta = position - previousCameraPosition
        previousCameraPosition = position
        if (delta == Offset.Zero || gamePointers.isNotEmpty()) return
        if (!cameraHeld) {
            val game = server ?: return
            touchpad?.beginMobileLook()
            val handler = if (game.isRelativeMouseMovement) game.winHandler else null
            rightViaHandler = handler != null
            if (handler != null) handler.mouseEvent(MouseEventFlags.RIGHTDOWN, 0, 0, 0)
            else game.injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT)
            cameraHeld = true
        }
        touchpad?.movePointerFromMobileLook(delta.x, delta.y, lookSensitivity)
    }

    private fun stopCamera() {
        if (!cameraHeld) return
        if (rightViaHandler) server?.winHandler?.mouseEvent(MouseEventFlags.RIGHTUP, 0, 0, 0)
        else server?.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT)
        cameraHeld = false
    }

    private fun forward(event: MotionEvent) {
        if (gamePointers.isEmpty()) return
        if (gamePointers.size == event.pointerCount) {
            touchpad?.onTouchEvent(event)
            return
        }
        val touch = gameMotionEvent(event, gamePointers) ?: return
        try {
            touchpad?.onTouchEvent(touch)
        } finally {
            touch.recycle()
        }
    }

    companion object {
        private const val NO_POINTER = -1
        private const val KEY_OVERLAY_HIDDEN = "overlay_hidden"

        private fun preferences(context: Context) =
            context.getSharedPreferences("forever_touch", Context.MODE_PRIVATE)

        fun create(viewConfiguration: ViewConfiguration) =
            MobileTouchControls(viewConfiguration.scaledTouchSlop.toFloat())
    }
}

internal val MOVEMENT_KEYS = listOf(XKeycode.KEY_Q, XKeycode.KEY_E, XKeycode.KEY_W, XKeycode.KEY_S)

internal fun movementKeys(x: Float, y: Float, previous: Int = 0): Int {
    // Hysteresis at the center and at direction changes keeps small thumb jitter from restarting movement.
    val deadZone = if (previous == 0) .19f else .12f
    if (x * x + y * y < deadZone * deadZone) return 0
    val horizontal = if (x < 0) 1 else 2
    val vertical = if (y < 0) 4 else 8
    val horizontalThreshold = if (previous and horizontal != 0) .39f else .58f
    val verticalThreshold = if (previous and vertical != 0) .39f else .58f
    var keys = 0
    if (abs(x) > abs(y) * horizontalThreshold) keys = keys or horizontal
    if (abs(y) > abs(x) * verticalThreshold) keys = keys or vertical
    return keys
}

internal enum class MobileShortcut(val key: XKeycode, val element: MobileHudElement, val modified: Boolean = true) {
    Jump(XKeycode.KEY_SPACE, MobileHudElement.Jump, false),
    Target(XKeycode.KEY_F12, MobileHudElement.Target),
    Interact(XKeycode.KEY_F10, MobileHudElement.Interact),
}

internal data class MobileLayout(val stick: Rect, val camera: Rect, val shortcuts: Map<MobileShortcut, Rect>) {
    fun shortcutAt(position: Offset) = shortcuts.entries.firstOrNull { it.value.contains(position) }?.key

    companion object {
        fun fit(
            width: Float,
            height: Float,
            leftInset: Float,
            rightInset: Float,
            bottomInset: Float,
            topInset: Float = 0f,
            positions: Map<MobileHudElement, Offset> = emptyMap(),
            tabletDensity: Float? = null,
        ): MobileLayout {
            val safeArea = Rect(leftInset, topInset, width - rightInset, height - bottomInset)
            val defaults = if (tabletDensity != null && !safeArea.isEmpty) {
                forTablet(safeArea, tabletDensity)
            } else {
                forPhone(safeArea)
            }
            if (positions.isEmpty()) return defaults

            fun place(element: MobileHudElement, bounds: Rect) =
                safeArea.placeMobileHudElement(bounds, positions[element])

            return MobileLayout(
                stick = place(MobileHudElement.Movement, defaults.stick),
                camera = place(MobileHudElement.Camera, defaults.camera),
                shortcuts = defaults.shortcuts.mapValues { (shortcut, bounds) -> place(shortcut.element, bounds) },
            )
        }

        private fun forPhone(safeArea: Rect): MobileLayout {
            val usableWidth = safeArea.width.coerceAtLeast(0f)
            val unit = minOf(safeArea.height.coerceAtLeast(0f), usableWidth * .96f)
            val margin = unit * .035f
            val gap = unit * .018f
            val diameter = unit * .178f
            val left = safeArea.left + margin
            val right = safeArea.right - margin
            val bottom = safeArea.bottom - margin
            val cameraTop = bottom - unit * .17f
            val cameraLeft = right - minOf(unit * .42f, usableWidth * .26f)
            val jumpRight = cameraLeft - gap
            val jumpLeft = jumpRight - unit * .142f
            val auxiliaryRight = jumpLeft - gap
            val auxiliaryLeft = auxiliaryRight - unit * .174f
            val auxiliaryHeight = (bottom - cameraTop - gap) / 2f

            return MobileLayout(
                stick = Rect(left, bottom - diameter, left + diameter, bottom),
                camera = Rect(cameraLeft, cameraTop, right, bottom),
                shortcuts = mapOf(
                    MobileShortcut.Jump to Rect(jumpLeft, bottom - unit * .142f, jumpRight, bottom),
                    MobileShortcut.Target to Rect(auxiliaryLeft, cameraTop, auxiliaryRight, cameraTop + auxiliaryHeight),
                    MobileShortcut.Interact to Rect(auxiliaryLeft, bottom - auxiliaryHeight, auxiliaryRight, bottom),
                ),
            )
        }

        private fun forTablet(safeArea: Rect, density: Float): MobileLayout {
            // Keep the thumb zones a consistent size in dp. Clamp while a window resize is being laid out.
            val unit = minOf(density, safeArea.width / 500f, safeArea.height / 200f)
            val left = safeArea.left + 20f * unit
            val right = safeArea.right - 20f * unit
            val bottom = safeArea.bottom - 20f * unit
            val stickSize = 98f * unit
            val cameraLeft = right - 148f * unit
            val jumpRight = cameraLeft - 10f * unit
            val jumpSize = 64f * unit
            val auxiliaryLeft = left + stickSize + 10f * unit
            val interact = Rect(auxiliaryLeft, bottom - 46f * unit, auxiliaryLeft + 92f * unit, bottom)

            return MobileLayout(
                stick = Rect(left, bottom - stickSize, left + stickSize, bottom),
                camera = Rect(cameraLeft, bottom - 76f * unit, right, bottom),
                shortcuts = mapOf(
                    MobileShortcut.Jump to Rect(jumpRight - jumpSize, bottom - jumpSize, jumpRight, bottom),
                    MobileShortcut.Target to interact.translate(0f, -54f * unit),
                    MobileShortcut.Interact to interact,
                ),
            )
        }
    }
}

private fun MotionEvent.position(index: Int) = Offset(getX(index), getY(index))

/** Builds the touch stream seen by the original touchscreen gestures, omitting movement and camera fingers. */
internal fun gameMotionEvent(event: MotionEvent, gamePointers: Set<Int>): MotionEvent? {
    val indices = (0 until event.pointerCount).filter { event.getPointerId(it) in gamePointers }
    if (indices.isEmpty()) return null

    val actionIndex = indices.indexOf(event.actionIndex)
    val action = when (event.actionMasked) {
        MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
            if (actionIndex == -1) return null
            if (indices.size == 1) {
                if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_UP
            } else {
                event.actionMasked or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            }
        }
        else -> event.actionMasked
    }

    return MotionEvent.obtain(
        event.downTime, event.eventTime, action, indices.size,
        Array(indices.size) { MotionEvent.PointerProperties().apply { event.getPointerProperties(indices[it], this) } },
        Array(indices.size) { MotionEvent.PointerCoords().apply { event.getPointerCoords(indices[it], this) } },
        event.metaState, event.buttonState, event.xPrecision, event.yPrecision,
        event.deviceId, event.edgeFlags, event.source, event.flags,
    )
}
