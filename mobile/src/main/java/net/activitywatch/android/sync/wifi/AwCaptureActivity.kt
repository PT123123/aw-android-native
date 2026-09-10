// 相机参数全部走 Camera1（android.hardware.Camera），该 API 已废弃但 zxing-android-embedded
// 4.3.0 本身就是基于它实现的 —— 这里没有 Camera2/CameraX 的路可走，统一压掉废弃告警。
@file:Suppress("DEPRECATION")

package net.activitywatch.android.sync.wifi

import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.Camera
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.journeyapps.barcodescanner.BarcodeView
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.camera.CameraInstance
import com.journeyapps.barcodescanner.camera.CameraManager

/**
 * 扫码页（WiFi 传输 · 被传送方）：连续对焦 + 点按定向对焦。
 *
 * ## 为什么不用库默认的扫码页
 * zxing-android-embedded 4.3.0 的 [com.journeyapps.barcodescanner.camera.CameraSettings]
 * 默认 `focusMode = AUTO`，落到 Camera1 就是 [Camera.Parameters.FOCUS_MODE_AUTO]
 * —— 「一次性对焦」，镜头不会自己动，全靠库内 AutoFocusManager 每 2 秒补一次
 * `camera.autoFocus()`。而它的 `focusing` 标志只在 `onAutoFocus` 回调里复位：
 * 回调丢一次（新 ROM 上的 legacy Camera1 并不罕见）就永久卡住，镜头再也不动
 * —— 表现就是预览一直糊。
 *
 * 所以这里把默认对焦改成 CONTINUOUS（`FOCUS_MODE_CONTINUOUS_PICTURE`，由 HAL 持续对焦，
 * 不依赖回调链），再叠一层「点按对焦」让用户能主动干预。
 *
 * ## 点按对焦为什么要临时切模式
 * Camera1 的 `setFocusAreas()` **只在 AUTO / MACRO 模式下被 HAL 使用**
 * （continuous 模式下区域会被忽略），所以点按的流程是：
 * 切 AUTO + 设 focus area → `autoFocus()` → 回调（或超时兜底）切回 CONTINUOUS。
 * 切回后 AutoFocusManager 依然不介入：它是否接管由构造时的模式决定，
 * continuous 下 `useAutoFocus = false`，不会来打断。
 *
 * ## 拿 Camera 实例的路径
 * 库只暴露到 `CameraPreview.getCameraInstance()`；`CameraInstance.getCameraManager()`
 * 是 protected、`CameraManager.getCamera()` 是 public，所以中间那一步走反射
 * （release 的 `minifyEnabled false`，类名方法名不会被 R8 改，反射安全）。
 *
 * ## 设置 CameraSettings 的时机
 * CameraSettings 是 `DecoratedBarcodeView.initializeFromIntent()` 里 new 出来再
 * `setCameraSettings()` 绑定的，而那一步在 `CaptureActivity.onCreate()` 内部执行，
 * 所以只能在 `super.onCreate()` 之后改；写在 `initializeContent()` 里会被覆盖。
 */
class AwCaptureActivity : CaptureActivity() {

    private companion object {
        const val TAG = "AwCapture"

        /**
         * focus area 半边长。Camera.Area 的坐标系是 (-1000,-1000)..(1000,1000)，
         * 取 250 即边长占画面的 25%，够大能容下换算误差，又不至于大到被 HAL 忽略。
         */
        const val FOCUS_AREA_HALF = 250f

        /** 点按后切回连续对焦的兜底延时（autoFocus 回调丢失时用）。 */
        const val RESTORE_DELAY_MS = 2500L
    }

    private var decoratedBarcodeView: DecoratedBarcodeView? = null

    /** CameraManager 实例随 CameraPreview 存活，可缓存；其内部 camera 字段会随开关相机变化。 */
    private var cachedCameraManager: CameraManager? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    // ==================== 生命周期 ====================

    override fun initializeContent(): DecoratedBarcodeView {
        val view = super.initializeContent()
        decoratedBarcodeView = view
        return view
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = decoratedBarcodeView ?: return

        // 默认连续对焦（见类注释）
        view.cameraSettings?.isContinuousFocusEnabled = true

        // 点按对焦：手指按下即对焦，抬起补一次 performClick 以满足无障碍语义
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    focusAt(v, event.x, event.y)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.performClick()
                    true
                }
                else -> true
            }
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ==================== 点按对焦 ====================

    private fun focusAt(view: View, x: Float, y: Float) {
        val camera = currentCamera() ?: return
        val preview = decoratedBarcodeView?.barcodeView ?: return
        try {
            val params = camera.parameters ?: return
            val modes = params.supportedFocusModes ?: emptyList()

            // 设备不提供可控对焦时，退化成「按一下触发一次对焦」，不设区域
            if (!modes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                camera.autoFocus(null)
                return
            }

            params.focusMode = Camera.Parameters.FOCUS_MODE_AUTO
            focusArea(view, preview, x, y)?.let { area ->
                if (params.maxNumFocusAreas > 0) {
                    params.focusAreas = listOf(Camera.Area(area, 1000))
                }
                if (params.maxNumMeteringAreas > 0) {
                    params.meteringAreas = listOf(Camera.Area(area, 800))
                }
            }
            camera.parameters = params

            camera.autoFocus { _, _ -> mainHandler.post { restoreContinuous(camera) } }
            // 兜底：万一 autoFocus 回调没回来，也不能一直停在 AUTO 模式
            mainHandler.postDelayed({ restoreContinuous(camera) }, RESTORE_DELAY_MS)

            showFocusIndicator(view, x, y)
        } catch (t: Throwable) {
            Log.w(TAG, "点按对焦失败", t)
        }
    }

    /** 对完焦切回连续对焦，并清掉一次性对焦区域。 */
    private fun restoreContinuous(camera: Camera) {
        mainHandler.removeCallbacksAndMessages(null)
        try {
            val params = camera.parameters ?: return
            val modes = params.supportedFocusModes ?: emptyList()
            when {
                modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE) ->
                    params.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
                modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO) ->
                    params.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
            }
            params.focusAreas = emptyList<Camera.Area>()
            params.meteringAreas = emptyList<Camera.Area>()
            camera.parameters = params
        } catch (t: Throwable) {
            Log.w(TAG, "切回连续对焦失败", t)
        }
    }

    /**
     * 把 View 上的点按坐标换算成 Camera.Area 的传感器坐标（-1000..1000）。
     *
     * 两步：① View 坐标 → 旋转后预览坐标（预览按 CenterCrop 铺满 View，居中裁剪）；
     * ② 旋转后预览坐标 → 传感器坐标（按 displayOrientation 逆旋转）。
     */
    private fun focusArea(view: View, preview: BarcodeView, x: Float, y: Float): Rect? {
        val natural = preview.previewSize ?: return null
        val rotation = preview.cameraInstance?.cameraRotation ?: 0
        val viewWidth = view.width.toFloat()
        val viewHeight = view.height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return null

        // previewSize 是传感器自然朝向，90/270 度显示时宽高互换
        val rotatedWidth: Float
        val rotatedHeight: Float
        if (rotation == 90 || rotation == 270) {
            rotatedWidth = natural.height.toFloat()
            rotatedHeight = natural.width.toFloat()
        } else {
            rotatedWidth = natural.width.toFloat()
            rotatedHeight = natural.height.toFloat()
        }

        // 与 zxing 默认的 CenterCropStrategy 一致：等比放大到铺满，居中裁剪
        val scale = maxOf(viewWidth / rotatedWidth, viewHeight / rotatedHeight)
        val offsetX = (viewWidth - rotatedWidth * scale) / 2f
        val offsetY = (viewHeight - rotatedHeight * scale) / 2f

        // ① 点到旋转后预览的归一化坐标
        val nx = ((x - offsetX) / scale / rotatedWidth) * 2000f - 1000f
        val ny = ((y - offsetY) / scale / rotatedHeight) * 2000f - 1000f

        // ② 逆着 displayOrientation 转回传感器坐标
        val sensorX: Float
        val sensorY: Float
        when (rotation) {
            90 -> {
                sensorX = ny
                sensorY = -nx
            }
            180 -> {
                sensorX = -nx
                sensorY = -ny
            }
            270 -> {
                sensorX = -ny
                sensorY = nx
            }
            else -> {
                sensorX = nx
                sensorY = ny
            }
        }

        val half = FOCUS_AREA_HALF
        val left = (sensorX - half).coerceIn(-1000f, 1000f).toInt()
        val top = (sensorY - half).coerceIn(-1000f, 1000f).toInt()
        val right = (sensorX + half).coerceIn(-1000f, 1000f).toInt()
        val bottom = (sensorY + half).coerceIn(-1000f, 1000f).toInt()
        if (right - left < 10 || bottom - top < 10) return null

        Log.d(TAG, "点按 ($x, $y) rot=$rotation -> area [$left,$top,$right,$bottom]")
        return Rect(left, top, right, bottom)
    }

    /** 点按位置闪一个圈，给个「点到了」的反馈。 */
    private fun showFocusIndicator(view: View, x: Float, y: Float) {
        val parent = view as? FrameLayout ?: return
        val density = resources.displayMetrics.density
        val size = (72 * density).toInt()
        val ring = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x22FFFFFF)
            setStroke((2 * density).toInt(), 0xCCFFFFFF.toInt())
        }
        val dot = View(parent.context).apply {
            background = ring
            isClickable = false
            isFocusable = false
        }
        // overlay 不消费触摸事件，事件会照常落到下层预览与父容器的 onTouchListener
        parent.addView(
            dot,
            FrameLayout.LayoutParams(size, size).apply {
                leftMargin = (x - size / 2f).toInt()
                topMargin = (y - size / 2f).toInt()
            }
        )
        dot.animate()
            .alpha(0f)
            .setStartDelay(280)
            .setDuration(420)
            .withEndAction { parent.removeView(dot) }
            .start()
    }

    // ==================== Camera 实例 ====================

    private fun currentCamera(): Camera? {
        val preview = decoratedBarcodeView?.barcodeView ?: return null
        val instance = preview.cameraInstance ?: return null
        var manager = cachedCameraManager
        if (manager == null) {
            manager = resolveCameraManager(instance) ?: return null
            cachedCameraManager = manager
        }
        return try {
            manager.camera
        } catch (t: Throwable) {
            null
        }
    }

    /** `CameraInstance.getCameraManager()` 是 protected，只能反射过去。 */
    private fun resolveCameraManager(instance: CameraInstance): CameraManager? = try {
        CameraInstance::class.java
            .getDeclaredMethod("getCameraManager")
            .apply { isAccessible = true }
            .invoke(instance) as? CameraManager
    } catch (t: Throwable) {
        Log.w(TAG, "反射获取 CameraManager 失败", t)
        null
    }
}
