package echo.music.iad1tya.listentogether

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import echo.music.iad1tya.LocalListenTogetherManager
import echo.music.iad1tya.LocalPlayerConnection
import echo.music.iad1tya.ui.component.FloatingChatBubble
import echo.music.iad1tya.ui.theme.echomusicTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import timber.log.Timber

@EntryPoint
@InstallIn(SingletonComponent::class)
interface OverlayServiceEntryPoint {
    fun listenTogetherManager(): ListenTogetherManager
}

class ListenTogetherOverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    private var windowManager: WindowManager? = null
    private var composeView: View? = null
    private var overlayLayoutParams: WindowManager.LayoutParams? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        createFloatingOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (composeView == null) {
            createFloatingOverlay()
        }
        return START_STICKY
    }

    private fun createFloatingOverlay() {
        if (composeView != null) return

        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val entryPoint = EntryPointAccessors.fromApplication(
                applicationContext,
                OverlayServiceEntryPoint::class.java
            )
            val manager = entryPoint.listenTogetherManager()

            val density = resources.displayMetrics.density
            val bubbleDiameterPx = (56 * density).toInt()
            val bubbleContainerPx = bubbleDiameterPx + (16 * density).toInt()

            val tuckPx = (bubbleContainerPx * 0.20f).toInt()
            val leftDockX = -tuckPx
            val rightDockX = resources.displayMetrics.widthPixels - bubbleContainerPx + tuckPx

            val initialX = rightDockX
            val initialY = (resources.displayMetrics.heightPixels * 0.45f).toInt()

            var savedBubbleX = initialX
            var savedBubbleY = initialY
            var accumulatedX = initialX.toFloat()
            var accumulatedY = initialY.toFloat()
            var snapAnimator: ValueAnimator? = null

            val minY = (48 * density).toInt()
            val maxY = resources.displayMetrics.heightPixels - bubbleContainerPx - (48 * density).toInt()

            val layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = initialX
                y = initialY
            }
            overlayLayoutParams = layoutParams

            val view = ComposeView(this)
            composeView = view
            view.apply {
                setViewTreeLifecycleOwner(this@ListenTogetherOverlayService)
                setViewTreeSavedStateRegistryOwner(this@ListenTogetherOverlayService)
                setViewTreeViewModelStoreOwner(this@ListenTogetherOverlayService)

                setContent {
                    val currentConn by manager.playerConnectionFlow.collectAsState()
                    var bubbleAnchorState by remember { mutableStateOf(Pair(initialX.toFloat(), initialY.toFloat())) }
                    val calloutMaxWidthPx = (270 * density).toInt()

                    echomusicTheme {
                        CompositionLocalProvider(
                            LocalListenTogetherManager provides manager,
                            LocalPlayerConnection provides currentConn
                        ) {
                            FloatingChatBubble(
                                navController = null,
                                isOverlayMode = true,
                                bubbleAnchorPosition = bubbleAnchorState,
                                onCalloutVisibilityChanged = { visible ->
                                    val params = overlayLayoutParams ?: return@FloatingChatBubble
                                    val wm = windowManager ?: return@FloatingChatBubble
                                    if (params.width == WindowManager.LayoutParams.MATCH_PARENT) {
                                        // Modal is expanded, ignore callout resize
                                        return@FloatingChatBubble
                                    }
                                    val isRight = savedBubbleX + (bubbleContainerPx / 2) >= resources.displayMetrics.widthPixels / 2
                                    if (visible) {
                                        if (isRight) {
                                            // Anchor bubble on the right edge, expand window to the left
                                            val targetX = (savedBubbleX - calloutMaxWidthPx).coerceAtLeast(0)
                                            params.x = targetX
                                            params.width = (savedBubbleX + bubbleContainerPx) - targetX
                                        } else {
                                            // Anchor bubble on the left edge, expand window to the right
                                            params.x = savedBubbleX
                                            params.width = bubbleContainerPx + calloutMaxWidthPx
                                        }
                                    } else {
                                        // Restore window to strictly cover the bubble
                                        params.x = savedBubbleX
                                        params.width = WindowManager.LayoutParams.WRAP_CONTENT
                                    }
                                    try {
                                        wm.updateViewLayout(view, params)
                                    } catch (e: Exception) {
                                        Timber.tag("OverlayService").e(e, "Error updating callout overlay bounds")
                                    }
                                },
                                onOverlayDrag = { dx, dy ->
                                    snapAnimator?.cancel()
                                    val params = overlayLayoutParams ?: return@FloatingChatBubble
                                    val wm = windowManager ?: return@FloatingChatBubble

                                    accumulatedX += dx
                                    accumulatedY += dy

                                    params.gravity = Gravity.TOP or Gravity.START
                                    params.x = kotlin.math.round(accumulatedX).toInt().coerceIn(leftDockX, rightDockX)
                                    params.y = kotlin.math.round(accumulatedY).toInt().coerceIn(minY, maxY)
                                    savedBubbleX = params.x
                                    savedBubbleY = params.y
                                    bubbleAnchorState = Pair(savedBubbleX.toFloat(), savedBubbleY.toFloat())
                                    try {
                                        wm.updateViewLayout(view, params)
                                    } catch (e: Exception) {
                                        Timber.tag("OverlayService").e(e, "Error dragging overlay")
                                    }
                                },
                                onOverlayDragEnd = {
                                    val params = overlayLayoutParams ?: return@FloatingChatBubble
                                    val wm = windowManager ?: return@FloatingChatBubble
                                    val isRight = params.x + (bubbleContainerPx / 2) >= resources.displayMetrics.widthPixels / 2
                                    val snapTargetX = if (isRight) rightDockX else leftDockX

                                    snapAnimator?.cancel()
                                    val startX = params.x
                                    snapAnimator = ValueAnimator.ofInt(startX, snapTargetX).apply {
                                        duration = 260L
                                        interpolator = android.view.animation.DecelerateInterpolator(1.6f)
                                        addUpdateListener { anim ->
                                            val currX = (anim.animatedValue as Int).coerceIn(leftDockX, rightDockX)
                                            params.x = currX
                                            accumulatedX = currX.toFloat()
                                            savedBubbleX = currX
                                            bubbleAnchorState = Pair(savedBubbleX.toFloat(), savedBubbleY.toFloat())
                                            try {
                                                wm.updateViewLayout(view, params)
                                            } catch (e: Exception) { }
                                        }
                                        addListener(object : android.animation.AnimatorListenerAdapter() {
                                            override fun onAnimationEnd(animation: android.animation.Animator) {
                                                params.x = snapTargetX
                                                accumulatedX = snapTargetX.toFloat()
                                                savedBubbleX = snapTargetX
                                                bubbleAnchorState = Pair(savedBubbleX.toFloat(), savedBubbleY.toFloat())
                                                try {
                                                    wm.updateViewLayout(view, params)
                                                } catch (e: Exception) { }
                                            }
                                        })
                                    }
                                    snapAnimator?.start()
                                },
                                onExpandChanged = { expanded, blurRadius ->
                                    snapAnimator?.cancel()
                                    val params = overlayLayoutParams ?: return@FloatingChatBubble
                                    val wm = windowManager ?: return@FloatingChatBubble
                                    try {
                                        if (expanded) {
                                            params.width = WindowManager.LayoutParams.MATCH_PARENT
                                            params.height = WindowManager.LayoutParams.MATCH_PARENT
                                            params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                                                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blurRadius > 0f) {
                                                params.flags = params.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND or WindowManager.LayoutParams.FLAG_DIM_BEHIND
                                                params.blurBehindRadius = (blurRadius * density).toInt().coerceIn(1, 150)
                                                params.dimAmount = 0.32f
                                            } else {
                                                params.flags = params.flags or WindowManager.LayoutParams.FLAG_DIM_BEHIND
                                                params.dimAmount = 0.45f
                                            }
                                            params.gravity = Gravity.TOP or Gravity.START
                                            params.x = 0
                                            params.y = 0
                                        } else {
                                            params.width = WindowManager.LayoutParams.WRAP_CONTENT
                                            params.height = WindowManager.LayoutParams.WRAP_CONTENT
                                            params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                                                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                                params.flags = params.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
                                                params.blurBehindRadius = 0
                                            }
                                            params.flags = params.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
                                            params.dimAmount = 0f
                                            params.gravity = Gravity.TOP or Gravity.START
                                            params.x = savedBubbleX
                                            params.y = savedBubbleY
                                            accumulatedX = savedBubbleX.toFloat()
                                            accumulatedY = savedBubbleY.toFloat()
                                        }
                                        wm.updateViewLayout(view, params)
                                    } catch (e: Exception) {
                                        Timber.tag("OverlayService").e(e, "Error updating overlay layout params")
                                    }
                                }
                            )
                        }
                    }
                }
            }

            composeView = view
            windowManager?.addView(composeView, layoutParams)
            Timber.tag("OverlayService").i("Unified Compose floating overlay added to WindowManager at ($initialX, $initialY)")
        } catch (e: Exception) {
            Timber.tag("OverlayService").e(e, "Error creating Compose floating overlay")
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        serviceScope.cancel()

        composeView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                Timber.tag("OverlayService").w(e, "Error removing Compose overlay")
            }
        }
        composeView = null
    }

    companion object {
        fun start(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(context)) {
                try {
                    val intent = Intent(context, ListenTogetherOverlayService::class.java)
                    context.startService(intent)
                } catch (e: Exception) {
                    Timber.tag("OverlayService").e(e, "Error starting overlay service")
                }
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, ListenTogetherOverlayService::class.java)
                context.stopService(intent)
            } catch (e: Exception) {
                Timber.tag("OverlayService").e(e, "Error stopping overlay service")
            }
        }
    }
}
