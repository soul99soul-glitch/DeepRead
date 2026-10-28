package app.amber.core.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import app.amber.feature.live.LiveScreenSnapshot
import kotlin.coroutines.resume

class AmberAccessibilityService : AccessibilityService(), AccessibilityController {
    private val nativeScreen = AccessibilityNativeScreen(this)
    private val liveWindows = LiveAccessibilityWindowReader(this)
    private val editableText = AccessibilityEditableText(this)
    private val legacyTree = AccessibilityLegacyTreeReader(this)

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            -> _screenEvents.tryEmit(
                ScreenEvent(
                    packageName = e.packageName?.toString().orEmpty(),
                    eventType = e.eventType,
                    atMillis = System.currentTimeMillis(),
                )
            )
        }
    }

    data class ScreenEvent(val packageName: String, val eventType: Int, val atMillis: Long)

    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        activeService = this
        AccessibilityActive.activeController = this
    }

    override fun onDestroy() {
        if (activeService === this) {
            activeService = null
        }
        if (AccessibilityActive.activeController === this) {
            AccessibilityActive.activeController = null
        }
        super.onDestroy()
    }

    override suspend fun tap(x: Float, y: Float, durationMillis: Long): Boolean =
        gesture(path = Path().apply { moveTo(x, y) }, durationMillis = durationMillis)

    override suspend fun longPress(x: Float, y: Float, durationMillis: Long): Boolean =
        gesture(path = Path().apply { moveTo(x, y) }, durationMillis = durationMillis)

    override suspend fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMillis: Long): Boolean {
        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(toX, toY)
        }
        return gesture(path = path, durationMillis = durationMillis)
    }

    override fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    override fun home(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    override fun captureScreenSnapshot(): ScreenSnapshot? = nativeScreen.captureScreenSnapshot()

    override fun performScreenAction(snapshot: ScreenSnapshot, action: ScreenAction): ScreenActionReceipt =
        nativeScreen.performScreenAction(snapshot, action)

    override fun setFocusedText(text: String): Boolean = editableText.setFocusedText(text)

    fun readTextInPackage(packageName: String): String? = editableText.readTextInPackage(packageName)

    fun setTextInPackageVerified(packageName: String, text: String): LiveFillOutcome =
        editableText.setTextInPackageVerified(packageName, text)

    fun activePackageName(): String? = legacyTree.activePackageName()

    fun captureLiveUiSnapshot(ownPackageName: String, maxNodes: Int = 180): LiveScreenSnapshot? =
        liveWindows.captureLiveUiSnapshot(ownPackageName, maxNodes)

    override fun dumpUiTree(maxNodes: Int): String = legacyTree.dumpUiTree(maxNodes)

    override fun findTextNodes(query: String, maxNodes: Int): List<AccessibilityTextMatch> =
        legacyTree.findTextNodes(query, maxNodes)


    /**
     * API 30+ 截当前屏为软件 Bitmap；低版本或失败返回 null（调用方降级保守模式）。
     */
    @Suppress("NewApi")
    suspend fun takeScreenshotBitmap(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return suspendCancellableCoroutine { cont ->
            // takeScreenshot 在服务未声明 canTakeScreenshot、或被系统限流时会同步抛异常；
            // 吞掉并 resume(null)，让调用方降级保守模式，而不是把整次分析炸成失败。
            val launched = runCatching {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(result: ScreenshotResult) {
                            val bitmap = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                            result.hardwareBuffer.close()
                            if (cont.isActive) cont.resume(bitmap)
                        }

                        override fun onFailure(errorCode: Int) {
                            if (cont.isActive) cont.resume(null)
                        }
                    },
                )
            }.isSuccess
            if (!launched && cont.isActive) cont.resume(null)
        }
    }

    /**
     * API 34+ 按窗口截图（takeScreenshotOfWindow）：只拍候选窗口，气泡等 overlay 不混入。
     * 窗口已失效（id 过期）或低版本返回 null，调用方回退整屏 [takeScreenshotBitmap]。
     */
    suspend fun takeScreenshotOfWindowBitmap(windowId: Int): Bitmap? {
        if (windowId < 0 || Build.VERSION.SDK_INT < 34) return null
        return suspendCancellableCoroutine { cont ->
            val launched = runCatching {
                takeScreenshotOfWindow(
                    windowId,
                    mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(result: ScreenshotResult) {
                            val bitmap = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                            result.hardwareBuffer.close()
                            if (cont.isActive) cont.resume(bitmap)
                        }

                        override fun onFailure(errorCode: Int) {
                            if (cont.isActive) cont.resume(null)
                        }
                    },
                )
            }.isSuccess
            if (!launched && cont.isActive) cont.resume(null)
        }
    }
    private suspend fun gesture(path: Path, durationMillis: Long): Boolean =
        suspendCancellableCoroutine { continuation ->
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMillis))
                .build()
            val dispatched = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                },
                null
            )
            if (!dispatched && continuation.isActive) continuation.resume(false)
        }
    companion object {
        @Volatile
        private var activeService: AmberAccessibilityService? = null

        fun getActiveService(): AmberAccessibilityService? = activeService

        private val _screenEvents = MutableSharedFlow<ScreenEvent>(
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

        /** 屏幕变化事件（窗口切换/内容变化），LiveModeManager 据此驱动分析。 */
        val screenEvents: SharedFlow<ScreenEvent> = _screenEvents.asSharedFlow()
    }
}

/** Live 填入的写入后回读结果（蓝图 §7.2 P0-5 仲裁契约）。 */
enum class LiveFillOutcome { SUCCESS, NOT_FOUND, MISMATCH, UNKNOWN }
