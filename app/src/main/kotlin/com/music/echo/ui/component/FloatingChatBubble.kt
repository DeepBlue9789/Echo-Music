package echo.music.iad1tya.ui.component

import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.palette.graphics.Palette
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import echo.music.iad1tya.LocalListenTogetherManager
import echo.music.iad1tya.LocalPlayerConnection
import echo.music.iad1tya.MainActivity
import echo.music.iad1tya.R
import echo.music.iad1tya.constants.ListenTogetherBubbleSizeKey
import echo.music.iad1tya.constants.ListenTogetherFloatingChatBubbleKey
import echo.music.iad1tya.extensions.toMediaItem
import echo.music.iad1tya.listentogether.ChatMessagePayload
import echo.music.iad1tya.listentogether.RepliedMessage
import echo.music.iad1tya.listentogether.TrackInfo
import echo.music.iad1tya.models.toMediaMetadata
import echo.music.iad1tya.playback.queues.YouTubeQueue
import echo.music.iad1tya.ui.player.MiniPlayer
import echo.music.iad1tya.ui.theme.PlayerColorExtractor
import echo.music.iad1tya.ui.theme.echomusicTheme
import echo.music.iad1tya.constants.PureBlackKey
import echo.music.iad1tya.utils.rememberPreference
import echo.music.iad1tya.constants.ListenTogetherChatBlurIntensityKey
import echo.music.iad1tya.constants.ListenTogetherChatTintIntensityKey
import echo.music.iad1tya.constants.ListenTogetherChatFontSizeKey
import echo.music.iad1tya.constants.ListenTogetherChatFontWeightKey
import echo.music.iad1tya.constants.ListenTogetherBubbleHaloKey
import echo.music.iad1tya.constants.ListenTogetherChatDragToDismissKey
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.animation.core.FastOutSlowInEasing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

private data class FloatingEmojiParticle(
    val id: Long,
    val emoji: String,
    val startXRatio: Float
)

private fun Color.contrastTextColor(): Color {
    return if (this.luminance() > 0.45f) Color.Black else Color.White
}

private fun lerp(start: Float, stop: Float, fraction: Float): Float =
    start + fraction * (stop - start)

private fun lerpDp(start: androidx.compose.ui.unit.Dp, stop: androidx.compose.ui.unit.Dp, fraction: Float): androidx.compose.ui.unit.Dp =
    androidx.compose.ui.unit.Dp(start.value + fraction * (stop.value - start.value))

private fun getVibrantSenderColor(username: String): Color {
    val palette = listOf(
        Color(0xFF4FC3F7), // Vibrant Sky Blue
        Color(0xFF81C784), // Vibrant Mint Green
        Color(0xFFFFB74D), // Vibrant Pastel Amber
        Color(0xFFCE93D8), // Vibrant Soft Lavender
        Color(0xFFFF8A80), // Vibrant Coral
        Color(0xFF4DB6AC), // Vibrant Aqua Teal
        Color(0xFFFFD54F)  // Vibrant Goldenrod
    )
    val hash = kotlin.math.abs(username.hashCode())
    return palette[hash % palette.size]
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FloatingChatBubble(
    navController: NavController? = null,
    isOverlayMode: Boolean = false,
    onOverlayDrag: ((Float, Float) -> Unit)? = null,
    onOverlayDragEnd: (() -> Unit)? = null,
    onExpandChanged: ((Boolean, Float) -> Unit)? = null,
    onCalloutVisibilityChanged: ((Boolean) -> Unit)? = null,
    bubbleAnchorPosition: Pair<Float, Float>? = null,
    forceExpanded: Boolean = false,
    onDismiss: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val manager = LocalListenTogetherManager.current ?: return
    val roomState by manager.roomState.collectAsState()
    val messages by manager.chatMessages.collectAsState()
    val currentUserId by manager.userId.collectAsState()
    val managerConnection by manager.playerConnectionFlow.collectAsState()
    val effectiveConnection = LocalPlayerConnection.current ?: managerConnection

    val (enableInAppBubble) = rememberPreference(ListenTogetherFloatingChatBubbleKey, true)
    val (bubbleSizePref) = rememberPreference(ListenTogetherBubbleSizeKey, "medium")
    val (chatBlurIntensity) = rememberPreference(ListenTogetherChatBlurIntensityKey, 16f)
    val (chatTintIntensity) = rememberPreference(ListenTogetherChatTintIntensityKey, 0.35f)
    val (chatFontSizePref) = rememberPreference(ListenTogetherChatFontSizeKey, "medium")
    val (chatFontWeightPref) = rememberPreference(ListenTogetherChatFontWeightKey, "medium")
    val (bubbleHaloPref) = rememberPreference(ListenTogetherBubbleHaloKey, true)
    val (chatDragToDismissPref) = rememberPreference(ListenTogetherChatDragToDismissKey, true)
    val (pureBlack) = rememberPreference(PureBlackKey, defaultValue = false)

    val chatFontScale = when (chatFontSizePref) {
        "small" -> 0.88f
        "large" -> 1.15f
        else -> 1.0f
    }
    val chatFontWeight = when (chatFontWeightPref) {
        "bold" -> FontWeight.Bold
        "medium" -> FontWeight.Medium
        else -> FontWeight.Normal
    }

    // forceExpanded=true means we show just the modal (opened from player chat button)
    if (roomState == null || (!enableInAppBubble && !isOverlayMode && !forceExpanded)) return

    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val view = LocalView.current

    val currentMetadataFromConn by (effectiveConnection?.mediaMetadata ?: MutableStateFlow(null)).collectAsState()
    val isPlayingFromConn by (effectiveConnection?.isPlaying ?: MutableStateFlow(false)).collectAsState()

    val currentMetadataTitle = currentMetadataFromConn?.title ?: roomState?.currentTrack?.title
    val currentMetadataArtist = currentMetadataFromConn?.artists?.joinToString(", ") { it.name } ?: roomState?.currentTrack?.artist ?: "Echo Music"
    val currentMetadataThumbnail = currentMetadataFromConn?.thumbnailUrl ?: roomState?.currentTrack?.thumbnail
    val isPlaying = if (effectiveConnection != null) isPlayingFromConn else (roomState?.isPlaying ?: false)

    // Dynamic song colors extraction from album art
    val fallbackPrimary = MaterialTheme.colorScheme.primary
    val fallbackSecondary = MaterialTheme.colorScheme.tertiary
    var songColors by remember { mutableStateOf(listOf(fallbackPrimary, fallbackSecondary)) }

    LaunchedEffect(currentMetadataThumbnail) {
        val thumbUrl = currentMetadataThumbnail
        if (!thumbUrl.isNullOrBlank()) {
            withContext(Dispatchers.IO) {
                try {
                    val request = ImageRequest.Builder(context)
                        .data(thumbUrl)
                        .size(100, 100)
                        .allowHardware(false)
                        .build()
                    val result = context.imageLoader.execute(request)
                    val bitmap = result.image?.toBitmap()
                    if (bitmap != null) {
                        val palette = Palette.from(bitmap)
                            .maximumColorCount(8)
                            .resizeBitmapArea(100 * 100)
                            .generate()
                        val colors = PlayerColorExtractor.extractGradientColors(
                            palette = palette,
                            fallbackColor = fallbackPrimary.toArgb()
                        )
                        if (colors.isNotEmpty()) {
                            songColors = colors
                        }
                    }
                } catch (e: Exception) {
                    // Keep existing colors
                }
            }
        }
    }

    val dynamicPrimary by animateColorAsState(
        targetValue = songColors.firstOrNull() ?: fallbackPrimary,
        animationSpec = tween(500),
        label = "dynamicPrimary"
    )
    val dynamicAccent by animateColorAsState(
        targetValue = songColors.getOrNull(1) ?: fallbackSecondary,
        animationSpec = tween(500),
        label = "dynamicAccent"
    )

    val onDynamicPrimary = dynamicPrimary.contrastTextColor()

    // Bubble diameter based on preference
    val bubbleDiameter = when (bubbleSizePref) {
        "small" -> 46.dp
        "large" -> 66.dp
        else -> 56.dp
    }
    // Strict constant bubble container size that NEVER changes whether playing, paused, or callout
    val bubbleContainerSize = bubbleDiameter + 16.dp

    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    val edgePaddingPx = with(density) { 16.dp.toPx() }

    val leftDockX = - with(density) { (bubbleContainerSize * 0.20f).toPx() }
    val rightDockX = screenWidthPx - with(density) { (bubbleContainerSize * 0.80f).toPx() }

    val initialDockX = rightDockX
    val offsetX = remember { Animatable(initialDockX) }
    val offsetY = remember { Animatable(screenHeightPx * 0.45f) }

    var isExpanded by rememberSaveable { mutableStateOf(forceExpanded) }
    var isDismissed by rememberSaveable { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }

    // Dynamic Tilt & Collision Squash-and-Stretch physics
    val dragTilt = remember { Animatable(0f) }
    val squashScaleX = remember { Animatable(1f) }
    val squashScaleY = remember { Animatable(1f) }

    // Premium spring drag scale — bubble scales to 0.88 while being dragged, bounces back on release
    val dragScale by animateFloatAsState(
        targetValue = if (isDragging) 0.88f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "bubbleDragScale"
    )

    // Animated equalizer for playing indicator (3 bars)
    val infiniteTransition = rememberInfiniteTransition(label = "bubbleAnimations")
    val bar1Height by infiniteTransition.animateFloat(
        initialValue = 3f, targetValue = 12f,
        animationSpec = infiniteRepeatable(tween(320, easing = LinearEasing), RepeatMode.Reverse),
        label = "bar1"
    )
    val bar2Height by infiniteTransition.animateFloat(
        initialValue = 12f, targetValue = 4f,
        animationSpec = infiniteRepeatable(tween(280, easing = LinearEasing), RepeatMode.Reverse),
        label = "bar2"
    )
    val bar3Height by infiniteTransition.animateFloat(
        initialValue = 6f, targetValue = 14f,
        animationSpec = infiniteRepeatable(tween(360, easing = LinearEasing), RepeatMode.Reverse),
        label = "bar3"
    )

    // Audio breathing halo
    val audioHaloScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "audioHaloScale"
    )
    val audioHaloAlpha by infiniteTransition.animateFloat(
        initialValue = 0.22f,
        targetValue = 0.65f,
        animationSpec = infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "audioHaloAlpha"
    )

    // Red badge count strictly for user messages
    val userMessages = remember(messages) { messages.filter { it.userId != "SYSTEM" && it.userId != currentUserId } }
    var lastReadUserMessageCount by rememberSaveable { mutableIntStateOf(0) }
    val unreadUserMessages = remember(userMessages, lastReadUserMessageCount) {
        if (userMessages.size > lastReadUserMessageCount) {
            userMessages.subList(lastReadUserMessageCount, userMessages.size)
        } else {
            emptyList()
        }
    }
    val unreadCount = unreadUserMessages.size

    val coroutineScope = rememberCoroutineScope()

    val positionState = remember { mutableLongStateOf(0L) }
    val durationState = remember { mutableLongStateOf(1L) }

    LaunchedEffect(isPlaying, isExpanded) {
        if (isPlaying && isExpanded) {
            while (isActive) {
                val player = effectiveConnection?.player
                if (player != null) {
                    positionState.longValue = player.currentPosition
                    durationState.longValue = player.duration.coerceAtLeast(1L)
                }
                delay(200)
            }
        } else {
            val player = effectiveConnection?.player
            if (player != null) {
                positionState.longValue = player.currentPosition
                durationState.longValue = player.duration.coerceAtLeast(1L)
            }
        }
    }

    val modalTransition = updateTransition(targetState = isExpanded, label = "ChatModalTransition")
    val showModal = isExpanded || modalTransition.currentState || modalTransition.targetState

    // Seamless Material 3 animation specs: duration, easing and timing perfectly synchronized
    val cardAlpha by modalTransition.animateFloat(
        transitionSpec = {
            if (targetState) {
                tween(durationMillis = 280, easing = LinearOutSlowInEasing)
            } else {
                tween(durationMillis = 180, easing = FastOutLinearInEasing)
            }
        },
        label = "cardAlpha"
    ) { expanded -> if (expanded) 1f else 0f }

    val cardScale by modalTransition.animateFloat(
        transitionSpec = {
            if (targetState) {
                tween(durationMillis = 320, easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f))
            } else {
                tween(durationMillis = 240, easing = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f))
            }
        },
        label = "cardScale"
    ) { expanded -> if (expanded) 1f else 0.82f }

    val cardOffsetProgress by modalTransition.animateFloat(
        transitionSpec = {
            if (targetState) {
                tween(durationMillis = 320, easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f))
            } else {
                tween(durationMillis = 240, easing = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f))
            }
        },
        label = "cardOffset"
    ) { expanded -> if (expanded) 0f else 1f }

    val scrimAlpha by modalTransition.animateFloat(
        transitionSpec = {
            if (targetState) {
                tween(durationMillis = 300, easing = FastOutSlowInEasing)
            } else {
                tween(durationMillis = 220, easing = FastOutLinearInEasing)
            }
        },
        label = "scrimAlpha"
    ) { expanded -> if (expanded) 0.45f else 0f }

    val bubbleAlpha by modalTransition.animateFloat(
        transitionSpec = {
            if (targetState) {
                tween(durationMillis = 130, easing = FastOutLinearInEasing)
            } else {
                tween(durationMillis = 180, delayMillis = 60, easing = LinearOutSlowInEasing)
            }
        },
        label = "bubbleAlpha"
    ) { expanded -> if (expanded) 0f else 1f }

    val bubbleScale by modalTransition.animateFloat(
        transitionSpec = {
            if (targetState) {
                tween(durationMillis = 130, easing = FastOutLinearInEasing)
            } else {
                tween(durationMillis = 180, delayMillis = 60, easing = LinearOutSlowInEasing)
            }
        },
        label = "bubbleScale"
    ) { expanded -> if (expanded) 0.80f else 1f }

    LaunchedEffect(modalTransition.currentState, modalTransition.targetState) {
        if (modalTransition.targetState) {
            onExpandChanged?.invoke(true, chatBlurIntensity)
        } else if (!modalTransition.currentState && !modalTransition.targetState) {
            onExpandChanged?.invoke(false, 0f)
        }
    }

    fun closeChatModal() {
        if (forceExpanded) {
            onDismiss?.invoke()
            return
        }
        if (!isExpanded) return
        isExpanded = false
        lastReadUserMessageCount = userMessages.size
    }

    // Speech bubble callout
    var isCalloutShowing by remember { mutableStateOf(false) }
    var calloutTimerJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(messages.size) {
        val lastMsg = messages.lastOrNull()
        if (lastMsg != null && lastMsg.userId != "SYSTEM" && lastMsg.userId != currentUserId) {
            isDismissed = false
            if (!isExpanded) {
                isCalloutShowing = true
                calloutTimerJob?.cancel()
                calloutTimerJob = coroutineScope.launch {
                    delay(3800)
                    isCalloutShowing = false
                }
            }
        }
    }

    LaunchedEffect(isCalloutShowing, unreadUserMessages.size) {
        onCalloutVisibilityChanged?.invoke(isCalloutShowing && unreadUserMessages.isNotEmpty())
    }

    if (isDismissed && !showModal) return

    val currentBubbleX = if (isOverlayMode) (bubbleAnchorPosition?.first ?: rightDockX) else offsetX.value
    val isOnRightSide = currentBubbleX > (screenWidthPx / 2)

    // Bottom Dismiss Target Zone coordinates & magnetic snapping calculations
    val bubbleDiameterPx = with(density) { bubbleDiameter.toPx() }
    val dismissTargetCenterX = screenWidthPx / 2f
    val dismissTargetCenterY = screenHeightPx - with(density) { 70.dp.toPx() }
    val currentBubbleCenterX = offsetX.value + (bubbleDiameterPx / 2f)
    val currentBubbleCenterY = offsetY.value + (bubbleDiameterPx / 2f)
    val distToDismiss = hypot(currentBubbleCenterX - dismissTargetCenterX, currentBubbleCenterY - dismissTargetCenterY)
    val isNearDismiss = distToDismiss < with(density) { 130.dp.toPx() }
    val isInsideDismiss = distToDismiss < with(density) { 65.dp.toPx() }

    val dismissScale by animateFloatAsState(
        targetValue = if (isInsideDismiss) 1.35f else if (isNearDismiss) 1.15f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "dismissScale"
    )

    Box(
        modifier = if (isOverlayMode && !showModal) Modifier.wrapContentSize(if (isOnRightSide) Alignment.CenterEnd else Alignment.CenterStart) else modifier.fillMaxSize()
    ) {
        // Bottom Dismiss Target Zone while dragging (in-app only)
        if (!isOverlayMode) {
            AnimatedVisibility(
                visible = isDragging,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 36.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (isInsideDismiss) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.95f),
                    shadowElevation = if (isInsideDismiss) 18.dp else 12.dp,
                    modifier = Modifier
                        .size(68.dp)
                        .graphicsLayer {
                            scaleX = dismissScale
                            scaleY = dismissScale
                        }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(R.drawable.close),
                            contentDescription = "Dismiss bubble",
                            tint = if (isInsideDismiss) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(if (isInsideDismiss) 34.dp else 30.dp)
                        )
                    }
                }
            }
        }

        // Draggable Floating Bubble
        if (bubbleAlpha > 0.001f && (!isDismissed || forceExpanded)) {
            Box(
                modifier = Modifier
                    .graphicsLayer {
                        alpha = bubbleAlpha
                        scaleX = bubbleScale
                        scaleY = bubbleScale
                    }
                    .then(
                        if (isOverlayMode) {
                            Modifier.wrapContentSize(if (isOnRightSide) Alignment.CenterEnd else Alignment.CenterStart)
                        } else {
                            Modifier.layout { measurable, constraints ->
                                val placeable = measurable.measure(constraints)
                                layout(placeable.width, placeable.height) {
                                    val clampedX = offsetX.value.coerceIn(leftDockX, rightDockX)
                                    val clampedY = offsetY.value.coerceIn(
                                        50.dp.toPx(),
                                        screenHeightPx - 100.dp.toPx()
                                    )
                                    val x = if (isOnRightSide) {
                                        (clampedX - (placeable.width - bubbleContainerSize.toPx())).roundToInt()
                                    } else {
                                        clampedX.roundToInt()
                                    }
                                    placeable.place(x, clampedY.roundToInt())
                                }
                            }
                        }
                    )
                    .pointerInput(isOverlayMode) {
                        if (isOverlayMode) {
                            detectDragGestures(
                                onDragStart = {
                                    isDragging = true
                                    isCalloutShowing = false
                                },
                                onDragEnd = {
                                    isDragging = false
                                    coroutineScope.launch {
                                        dragTilt.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
                                    }
                                    onOverlayDragEnd?.invoke()
                                },
                                onDragCancel = {
                                    isDragging = false
                                    coroutineScope.launch { dragTilt.animateTo(0f) }
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    coroutineScope.launch {
                                        val tiltTarget = (dragAmount.x * 0.45f).coerceIn(-18f, 18f)
                                        dragTilt.snapTo(tiltTarget)
                                    }
                                    onOverlayDrag?.invoke(dragAmount.x, dragAmount.y)
                                }
                            )
                        } else {
                            detectDragGestures(
                                onDragStart = {
                                    isDragging = true
                                    isCalloutShowing = false
                                },
                                onDragEnd = {
                                    isDragging = false
                                    coroutineScope.launch {
                                        dragTilt.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
                                        if (isInsideDismiss) {
                                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                            isDismissed = true
                                            Toast.makeText(context, "Chat bubble dismissed", Toast.LENGTH_SHORT).show()
                                        } else {
                                            val snapTargetX = if (offsetX.value < screenWidthPx / 2) leftDockX else rightDockX
                                            offsetX.animateTo(
                                                snapTargetX,
                                                spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)
                                            )
                                            // Edge collision squash and stretch
                                            squashScaleX.snapTo(0.84f)
                                            squashScaleY.snapTo(1.15f)
                                            launch { squashScaleX.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)) }
                                            launch { squashScaleY.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)) }
                                        }
                                    }
                                },
                                onDragCancel = {
                                    isDragging = false
                                    coroutineScope.launch { dragTilt.animateTo(0f) }
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    coroutineScope.launch {
                                        val tiltTarget = (dragAmount.x * 0.45f).coerceIn(-18f, 18f)
                                        dragTilt.snapTo(tiltTarget)
                                        // Magnetic pull towards dismiss target when nearby
                                        var targetX = offsetX.value + dragAmount.x
                                        var targetY = offsetY.value + dragAmount.y
                                        if (isNearDismiss && !isInsideDismiss) {
                                            val pullFactor = 0.25f
                                            targetX += (dismissTargetCenterX - currentBubbleCenterX) * pullFactor
                                            targetY += (dismissTargetCenterY - currentBubbleCenterY) * pullFactor
                                        }
                                        offsetX.snapTo(targetX.coerceIn(leftDockX, rightDockX))
                                        offsetY.snapTo(targetY.coerceIn(with(density) { 50.dp.toPx() }, screenHeightPx - with(density) { 60.dp.toPx() }))
                                    }
                                }
                            )
                        }
                    }
            ) {
                Row(
                    modifier = Modifier.wrapContentSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isOnRightSide) {
                        AnimatedVisibility(
                            visible = isCalloutShowing && unreadUserMessages.isNotEmpty(),
                            enter = fadeIn(tween(200)) + expandHorizontally(tween(200), expandFrom = Alignment.End),
                            exit = fadeOut(tween(150)) + shrinkHorizontally(tween(150), shrinkTowards = Alignment.End)
                        ) {
                            SpeechBubbleCallout(
                                unreadMessages = unreadUserMessages,
                                isOnRightSide = true,
                                themeColor = dynamicPrimary,
                                fontScale = chatFontScale,
                                fontWeight = chatFontWeight,
                                onSwipeDismiss = { isCalloutShowing = false },
                                onClick = {
                                    onExpandChanged?.invoke(true, chatBlurIntensity)
                                    isExpanded = true
                                    isCalloutShowing = false
                                    lastReadUserMessageCount = userMessages.size
                                }
                            )
                        }
                        CircularFloatingBubble(
                            bubbleDiameter = bubbleDiameter,
                            bubbleContainerSize = bubbleContainerSize,
                            dragScale = dragScale,
                            dragTilt = dragTilt.value,
                            squashScaleX = squashScaleX.value,
                            squashScaleY = squashScaleY.value,
                            audioHaloScale = audioHaloScale,
                            audioHaloAlpha = audioHaloAlpha,
                            bubbleHaloPref = bubbleHaloPref,
                            isDragging = isDragging,
                            dynamicPrimary = dynamicPrimary,
                            dynamicAccent = dynamicAccent,
                            onDynamicPrimary = onDynamicPrimary,
                            currentMetadataThumbnail = currentMetadataThumbnail,
                            isPlaying = isPlaying,
                            unreadCount = unreadCount,
                            isOnRightSide = true,
                            barHeights = listOf(bar1Height, bar2Height, bar3Height),
                            onClick = {
                                onExpandChanged?.invoke(true, chatBlurIntensity)
                                isExpanded = true
                                isCalloutShowing = false
                                lastReadUserMessageCount = userMessages.size
                            }
                        )
                    } else {
                        CircularFloatingBubble(
                            bubbleDiameter = bubbleDiameter,
                            bubbleContainerSize = bubbleContainerSize,
                            dragScale = dragScale,
                            dragTilt = dragTilt.value,
                            squashScaleX = squashScaleX.value,
                            squashScaleY = squashScaleY.value,
                            audioHaloScale = audioHaloScale,
                            audioHaloAlpha = audioHaloAlpha,
                            bubbleHaloPref = bubbleHaloPref,
                            isDragging = isDragging,
                            dynamicPrimary = dynamicPrimary,
                            dynamicAccent = dynamicAccent,
                            onDynamicPrimary = onDynamicPrimary,
                            currentMetadataThumbnail = currentMetadataThumbnail,
                            isPlaying = isPlaying,
                            unreadCount = unreadCount,
                            isOnRightSide = false,
                            barHeights = listOf(bar1Height, bar2Height, bar3Height),
                            onClick = {
                                onExpandChanged?.invoke(true, chatBlurIntensity)
                                isExpanded = true
                                isCalloutShowing = false
                                lastReadUserMessageCount = userMessages.size
                            }
                        )
                        AnimatedVisibility(
                            visible = isCalloutShowing && unreadUserMessages.isNotEmpty(),
                            enter = fadeIn(tween(200)) + expandHorizontally(tween(200), expandFrom = Alignment.Start),
                            exit = fadeOut(tween(150)) + shrinkHorizontally(tween(150), shrinkTowards = Alignment.Start)
                        ) {
                            SpeechBubbleCallout(
                                unreadMessages = unreadUserMessages,
                                isOnRightSide = false,
                                themeColor = dynamicPrimary,
                                fontScale = chatFontScale,
                                fontWeight = chatFontWeight,
                                onSwipeDismiss = { isCalloutShowing = false },
                                onClick = {
                                    onExpandChanged?.invoke(true, chatBlurIntensity)
                                    isExpanded = true
                                    isCalloutShowing = false
                                    lastReadUserMessageCount = userMessages.size
                                }
                            )
                        }
                    }
                }
            }
        }

    // Expanded Dynamic Themed Chat Modal — synchronized movement, fade, and scale from bubble anchor
    val modalDragOffsetY = remember { Animatable(0f) }

    if (showModal) {
        val anchorX = bubbleAnchorPosition?.first ?: offsetX.value
        val anchorY = bubbleAnchorPosition?.second ?: offsetY.value
        val bubbleCenterX = anchorX + bubbleDiameterPx / 2f
        val bubbleCenterY = anchorY + bubbleDiameterPx / 2f
        val screenCenterX = screenWidthPx / 2f
        val screenCenterY = screenHeightPx / 2f

        val deltaX = bubbleCenterX - screenCenterX
        val deltaY = bubbleCenterY - screenCenterY
        val targetTranslationX = deltaX * 0.40f * cardOffsetProgress
        val targetTranslationY = deltaY * 0.40f * cardOffsetProgress

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(if (isOverlayMode) Color.Transparent else Color.Black.copy(alpha = scrimAlpha))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    closeChatModal()
                }
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 18.dp),
            contentAlignment = Alignment.Center
        ) {
            echomusicTheme(
                darkTheme = true,
                pureBlack = pureBlack,
                themeColor = dynamicPrimary,
            ) {
                Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.92f)
                    .graphicsLayer {
                        val dragProg = (modalDragOffsetY.value / 1000f).coerceIn(0f, 0.15f)
                        alpha = cardAlpha
                        scaleX = cardScale * (1f - dragProg)
                        scaleY = cardScale * (1f - dragProg)
                        translationX = targetTranslationX
                        translationY = targetTranslationY + modalDragOffsetY.value
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        // Consume clicks inside surface so they don't dismiss dialog
                    }
                    .clip(RoundedCornerShape(28.dp))
                    .shadow(24.dp, shape = RoundedCornerShape(28.dp))
                    .border(
                        width = 1.5.dp,
                        brush = Brush.linearGradient(
                            listOf(
                                Color.White.copy(alpha = 0.55f),
                                dynamicPrimary.copy(alpha = 0.85f),
                                dynamicAccent.copy(alpha = 0.65f)
                            )
                        ),
                        shape = RoundedCornerShape(28.dp)
                    ),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = chatTintIntensity.coerceIn(0.65f, 0.96f))
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Deep-blurred album artwork layer for genuine frosted glass backdrop
                    if (!currentMetadataThumbnail.isNullOrBlank()) {
                        AsyncImage(
                            model = currentMetadataThumbnail,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .blur((chatBlurIntensity.coerceIn(14f, 50f)).dp)
                                .alpha(0.38f)
                        )
                    }

                    // Ambient Frosted Glass illumination layer
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        dynamicPrimary.copy(alpha = (chatBlurIntensity / 30f) * 0.25f),
                                        dynamicAccent.copy(alpha = (chatBlurIntensity / 30f) * 0.15f),
                                        Color.Transparent
                                    ),
                                    radius = 1100f
                                )
                            )
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.White.copy(alpha = 0.09f),
                                        Color.Transparent,
                                        Color.Black.copy(alpha = 0.16f)
                                    )
                                )
                            )
                    )
                    Column(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        // Ultra-Compact Modern Glass Header with iOS Drag Handle & Drag-to-Dismiss
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(
                                            dynamicPrimary.copy(alpha = 0.28f),
                                            dynamicAccent.copy(alpha = 0.16f)
                                        )
                                    )
                                )
                                .pointerInput(chatDragToDismissPref) {
                                    if (chatDragToDismissPref) {
                                        detectVerticalDragGestures(
                                            onDragStart = { },
                                            onDragEnd = {
                                                coroutineScope.launch {
                                                    if (modalDragOffsetY.value > with(density) { 95.dp.toPx() }) {
                                                        closeChatModal()
                                                        modalDragOffsetY.snapTo(0f)
                                                    } else {
                                                        modalDragOffsetY.animateTo(
                                                            0f,
                                                            spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)
                                                        )
                                                    }
                                                }
                                            },
                                            onDragCancel = {
                                                coroutineScope.launch { modalDragOffsetY.animateTo(0f) }
                                            },
                                            onVerticalDrag = { change, dragAmount ->
                                                change.consume()
                                                coroutineScope.launch {
                                                    val raw = modalDragOffsetY.value + dragAmount
                                                    val factor = if (raw > with(density) { 70.dp.toPx() }) 0.42f else 1f
                                                    modalDragOffsetY.snapTo((modalDragOffsetY.value + dragAmount * factor).coerceAtLeast(0f))
                                                }
                                            }
                                        )
                                    }
                                }
                                .padding(top = 8.dp, bottom = 8.dp, start = 14.dp, end = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Pill Drag Handle
                            Box(
                                modifier = Modifier
                                    .width(36.dp)
                                    .height(4.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.32f))
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                // Room badge pill
                                Surface(
                                    shape = CircleShape,
                                    color = dynamicPrimary.copy(alpha = 0.18f),
                                    border = BorderStroke(1.dp, dynamicPrimary.copy(alpha = 0.35f))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xFF4CAF50))
                                        )
                                        Text(
                                            text = roomState?.roomCode?.let { "#$it" } ?: "Live Chat",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }

                                // Compact Actions: Settings, Disconnect, Close
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                        modifier = Modifier.size(32.dp),
                                        onClick = {
                                            closeChatModal()
                                            if (navController != null) {
                                                navController.navigate("settings/integrations/listen_together")
                                            } else {
                                                val intent = Intent(context, MainActivity::class.java).apply {
                                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                                                    putExtra("EXTRA_OPEN_LISTEN_TOGETHER_SETTINGS", true)
                                                }
                                                context.startActivity(intent)
                                            }
                                        }
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                painter = painterResource(R.drawable.settings),
                                                contentDescription = "Listen Together Settings",
                                                tint = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }

                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                                        modifier = Modifier.size(32.dp),
                                        onClick = {
                                            closeChatModal()
                                            manager.leaveRoom()
                                            Toast.makeText(context, "Disconnected from session", Toast.LENGTH_SHORT).show()
                                        }
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                painter = painterResource(R.drawable.logout),
                                                contentDescription = "Disconnect",
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }

                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                        modifier = Modifier.size(32.dp),
                                        onClick = {
                                            closeChatModal()
                                        }
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                painter = painterResource(R.drawable.close),
                                                contentDescription = "Close",
                                                tint = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }


                    // Messages List (Scrollable)
                    val listState = rememberLazyListState()
                    var replyingTo by remember { mutableStateOf<ChatMessagePayload?>(null) }
                    var messageInput by remember { mutableStateOf("") }
                    var floatingEmojis by remember { mutableStateOf<List<FloatingEmojiParticle>>(emptyList()) }
                    val sendHoldProgress = remember { Animatable(0f) }

                    // In-chat Song Search mode triggered by long-pressing send button
                    var isSearchMode by rememberSaveable { mutableStateOf(false) }
                    val focusRequester = remember { FocusRequester() }
                    var searchResults by remember { mutableStateOf<List<SongItem>>(emptyList()) }
                    var isSearchingSongs by remember { mutableStateOf(false) }
                    val searchListState = rememberLazyListState()

                    LaunchedEffect(messageInput, isSearchMode) {
                        if (isSearchMode || messageInput.contains("//")) {
                            val query = if (messageInput.contains("//")) {
                                messageInput.substringAfter("//").trim()
                            } else {
                                messageInput.trim()
                            }
                            if (query.length >= 2) {
                                isSearchingSongs = true
                                delay(250) // Debounce search
                                withContext(Dispatchers.IO) {
                                    try {
                                        YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).onSuccess { res ->
                                            val songs = res.items.filterIsInstance<SongItem>().take(5)
                                            // Ordered bottom-to-top: closest match at the bottom near textbox
                                            searchResults = songs.reversed()
                                        }
                                    } catch (e: Exception) {
                                        searchResults = emptyList()
                                    } finally {
                                        isSearchingSongs = false
                                    }
                                }
                            } else {
                                searchResults = emptyList()
                                isSearchingSongs = false
                            }
                        } else {
                            searchResults = emptyList()
                            isSearchingSongs = false
                        }
                    }

                    // Automatically scroll search results to bottom (closest match)
                    LaunchedEffect(searchResults.size) {
                        if (searchResults.isNotEmpty()) {
                            searchListState.scrollToItem(searchResults.size - 1)
                        }
                    }

                    LaunchedEffect(messages.size) {
                        if (messages.isNotEmpty()) {
                            listState.animateScrollToItem(messages.size - 1)
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 12.dp),
                            contentPadding = PaddingValues(vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (messages.isEmpty()) {
                                item {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(32.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "No messages yet. Send a message, quote songs, or hold send to search!",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            itemsIndexed(messages, key = { _, it -> "${it.userId}_${it.timestamp}_${it.message.hashCode()}" }) { idx, msg ->
                                val prevMsg = messages.getOrNull(idx - 1)
                                val nextMsg = messages.getOrNull(idx + 1)
                                val isSong = msg.trackInfo != null || msg.userId == "SYSTEM" || msg.message.startsWith("🎵")
                                val prevIsSong = prevMsg != null && (prevMsg.trackInfo != null || prevMsg.userId == "SYSTEM" || prevMsg.message.startsWith("🎵"))
                                val nextIsSong = nextMsg != null && (nextMsg.trackInfo != null || nextMsg.userId == "SYSTEM" || nextMsg.message.startsWith("🎵"))

                                val isPrevSame = prevMsg != null && prevMsg.userId == msg.userId && !isSong && !prevIsSong && (msg.timestamp - prevMsg.timestamp) < 120_000L
                                val isNextSame = nextMsg != null && nextMsg.userId == msg.userId && !isSong && !nextIsSong && (nextMsg.timestamp - msg.timestamp) < 120_000L
                                val showSenderName = !isPrevSame

                                SwipeableMessageItem(
                                    message = msg,
                                    isMe = msg.userId == currentUserId,
                                    themeColor = dynamicPrimary,
                                    showSenderName = showSenderName,
                                    isPrevSame = isPrevSame,
                                    isNextSame = isNextSame,
                                    onQuote = { quotedMsg ->
                                        val quoteIsSong = quotedMsg.trackInfo != null || quotedMsg.userId == "SYSTEM" || quotedMsg.message.startsWith("🎵")
                                        val title = quotedMsg.trackInfo?.title ?: quotedMsg.message.removePrefix("🎵 Now Playing: ").removePrefix("🎵 ").substringBefore(" - ")
                                        val artist = quotedMsg.trackInfo?.artist ?: quotedMsg.message.substringAfter(" - ", "Echo Music")
                                        val thumb = quotedMsg.trackInfo?.thumbnail ?: quotedMsg.replyTo?.thumbnail ?: (if (quoteIsSong) (currentMetadataThumbnail ?: roomState?.currentTrack?.thumbnail) else null)

                                        val quotePayload = if (quoteIsSong) {
                                            quotedMsg.copy(
                                                username = "🎵 $title",
                                                message = "$title - $artist",
                                                trackInfo = quotedMsg.trackInfo ?: TrackInfo(
                                                    id = roomState?.currentTrack?.id ?: "current",
                                                    title = title,
                                                    artist = artist,
                                                    duration = 0L,
                                                    thumbnail = thumb
                                                )
                                            )
                                        } else {
                                            quotedMsg
                                        }
                                        replyingTo = quotePayload
                                        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                    }
                                )
                            }
                        }

                        // Floating Tapback Emoji Particles overlay
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter)
                                .height(130.dp),
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            floatingEmojis.forEach { particle ->
                                key(particle.id) {
                                    val particleProgress = remember { Animatable(0f) }
                                    LaunchedEffect(particle.id) {
                                        particleProgress.animateTo(1f, tween(950, easing = FastOutSlowInEasing))
                                    }
                                    Text(
                                        text = particle.emoji,
                                        fontSize = 32.sp,
                                        modifier = Modifier
                                            .graphicsLayer {
                                                translationY = -particleProgress.value * 105.dp.toPx()
                                                translationX = particle.startXRatio * 150.dp.toPx()
                                                alpha = (1f - particleProgress.value).coerceIn(0f, 1f)
                                                val scale = 0.75f + particleProgress.value * 0.65f
                                                scaleX = scale
                                                scaleY = scale
                                            }
                                    )
                                }
                            }
                        }
                    }

                    // Quick Big Emoji Reaction Bar with immediate send and tapback burst
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(dynamicPrimary.copy(alpha = 0.08f))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val reactionEmojis = listOf("❤️", "🔥", "👏", "🎶", "😂", "😮", "🎉", "⚡")
                        reactionEmojis.forEachIndexed { i, emoji ->
                            Text(
                                text = emoji,
                                fontSize = 24.sp,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable {
                                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                        val particleId = System.currentTimeMillis() + (0..1000).random()
                                        val ratio = (i - (reactionEmojis.size - 1) / 2f) / ((reactionEmojis.size - 1) / 2f)
                                        floatingEmojis = floatingEmojis + FloatingEmojiParticle(particleId, emoji, ratio * 0.85f)
                                        coroutineScope.launch {
                                            delay(1000)
                                            floatingEmojis = floatingEmojis.filter { it.id != particleId }
                                        }
                                        manager.sendChatMessage(
                                            emoji,
                                            replyingTo?.let { RepliedMessage(it.username, it.message, it.trackInfo?.thumbnail) }
                                        )
                                        replyingTo = null
                                    }
                                    .padding(4.dp)
                            )
                        }
                    }

                    // Quoted Reply Preview Banner (with album art for songs)
                    AnimatedVisibility(
                        visible = replyingTo != null,
                        enter = expandVertically(spring()) + fadeIn(tween(160)),
                        exit = shrinkVertically(tween(140)) + fadeOut(tween(140))
                    ) {
                        replyingTo?.let { reply ->
                            val quoteThumb = reply.trackInfo?.thumbnail ?: reply.replyTo?.thumbnail
                            val isSongQuote = reply.trackInfo != null || reply.username.startsWith("🎵") || !quoteThumb.isNullOrBlank()
                            val quoteHeader = if (isSongQuote) reply.username else "Replying to ${reply.username}"
                            val quoteBody = reply.message

                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = dynamicPrimary.copy(alpha = 0.15f),
                                modifier = Modifier
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                                    .wrapContentWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (!quoteThumb.isNullOrBlank()) {
                                        AsyncImage(
                                            model = quoteThumb,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(28.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                        )
                                    } else if (isSongQuote) {
                                        Box(
                                            modifier = Modifier
                                                .size(28.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(dynamicPrimary.copy(alpha = 0.3f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.music_note),
                                                contentDescription = null,
                                                tint = dynamicPrimary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                    Column(modifier = Modifier.widthIn(max = 220.dp)) {
                                        Text(
                                            text = quoteHeader,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = quoteBody,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    IconButton(
                                        onClick = { replyingTo = null },
                                        modifier = Modifier.size(20.dp)
                                    ) {
                                        Icon(
                                            painterResource(R.drawable.close),
                                            contentDescription = "Cancel reply",
                                            modifier = Modifier.size(14.dp),
                                            tint = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Flow Sheet for In-Chat Song Search Results (Overflow scrollable, default scrolled to bottom)
                    if ((isSearchMode || messageInput.contains("//")) && (searchResults.isNotEmpty() || isSearchingSongs)) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.98f),
                            shadowElevation = 12.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                                .border(1.5.dp, dynamicPrimary.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                        ) {
                            Column(modifier = Modifier.fillMaxSize().padding(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "🎵 Song Search (closest at bottom)",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    if (isSearchingSongs) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(14.dp),
                                            strokeWidth = 2.dp,
                                            color = dynamicPrimary
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                LazyColumn(
                                    state = searchListState,
                                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    items(searchResults) { song ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(10.dp))
                                                .clickable {
                                                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                                    if (effectiveConnection != null) {
                                                        val metadata = song.toMediaMetadata()
                                                        effectiveConnection.playQueue(YouTubeQueue.radio(metadata))
                                                    }
                                                    manager.suggestTrack(
                                                        TrackInfo(
                                                            id = song.id,
                                                            title = song.title,
                                                            artist = song.artists.firstOrNull()?.name ?: "Artist",
                                                            duration = (song.duration ?: 0) * 1000L,
                                                            thumbnail = song.thumbnail
                                                        )
                                                    )
                                                    Toast.makeText(context, "Playing now: ${song.title}", Toast.LENGTH_SHORT).show()
                                                    messageInput = ""
                                                    searchResults = emptyList()
                                                    isSearchMode = false
                                                }
                                                .padding(horizontal = 6.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                AsyncImage(
                                                    model = song.thumbnail,
                                                    contentDescription = null,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier
                                                        .size(34.dp)
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(dynamicPrimary.copy(alpha = 0.2f))
                                                )
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = song.title,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.onSurface,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Text(
                                                        text = song.artists.joinToString(", ") { it.name },
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }

                                            // Action Buttons: Play Next & Add to Queue
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                IconButton(
                                                    onClick = {
                                                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                                        effectiveConnection?.playNext(song.toMediaMetadata().toMediaItem())
                                                        Toast.makeText(context, "Playing next: ${song.title}", Toast.LENGTH_SHORT).show()
                                                        messageInput = ""
                                                        searchResults = emptyList()
                                                        isSearchMode = false
                                                    },
                                                    modifier = Modifier.size(30.dp)
                                                ) {
                                                    Icon(
                                                        painter = painterResource(R.drawable.skip_next),
                                                        contentDescription = "Play Next",
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                                IconButton(
                                                    onClick = {
                                                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                                        effectiveConnection?.addToQueue(song.toMediaMetadata().toMediaItem())
                                                        Toast.makeText(context, "Added to queue: ${song.title}", Toast.LENGTH_SHORT).show()
                                                        messageInput = ""
                                                        searchResults = emptyList()
                                                        isSearchMode = false
                                                    },
                                                    modifier = Modifier.size(30.dp)
                                                ) {
                                                    Icon(
                                                        painter = painterResource(R.drawable.playlist_add),
                                                        contentDescription = "Add to Queue",
                                                        tint = MaterialTheme.colorScheme.onSurface,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Chat / Search Input Box (Always fully visible above mini-player)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = messageInput,
                            onValueChange = { messageInput = it },
                            placeholder = {
                                Text(
                                    if (isSearchMode) "Search songs on YouTube..." else "Send a message... (Hold ✈ to search)",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(22.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surface,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                                focusedBorderColor = dynamicPrimary,
                                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(focusRequester)
                        )

                        if (isSearchMode) {
                            // Dismiss Search Mode Button
                            IconButton(
                                onClick = {
                                    isSearchMode = false
                                    messageInput = ""
                                    searchResults = emptyList()
                                    focusRequester.requestFocus()
                                },
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.close),
                                    contentDescription = "Dismiss search",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        } else {
                            // Send / Hold-to-Search Button with tactile radial hold arc
                            Box(
                                modifier = Modifier.size(46.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (sendHoldProgress.value > 0f) {
                                    Canvas(modifier = Modifier.fillMaxSize()) {
                                        val stroke = 3.dp.toPx()
                                        drawArc(
                                            brush = Brush.sweepGradient(listOf(dynamicPrimary, dynamicAccent, dynamicPrimary)),
                                            startAngle = -90f,
                                            sweepAngle = sendHoldProgress.value * 360f,
                                            useCenter = false,
                                            style = Stroke(width = stroke, cap = StrokeCap.Round)
                                        )
                                    }
                                }

                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(dynamicPrimary)
                                        .pointerInput(messageInput) {
                                            detectTapGestures(
                                                onPress = {
                                                    if (messageInput.isBlank()) {
                                                        val holdJob = coroutineScope.launch {
                                                            sendHoldProgress.animateTo(1f, tween(450, easing = LinearEasing))
                                                            if (sendHoldProgress.value >= 1f) {
                                                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                                                isSearchMode = true
                                                                focusRequester.requestFocus()
                                                            }
                                                        }
                                                        tryAwaitRelease()
                                                        holdJob.cancel()
                                                        sendHoldProgress.animateTo(0f, tween(150))
                                                    }
                                                },
                                                onTap = {
                                                    if (messageInput.isNotBlank()) {
                                                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                                        manager.sendChatMessage(
                                                            messageInput.trim(),
                                                            replyingTo?.let { RepliedMessage(it.username, it.message, it.trackInfo?.thumbnail ?: it.replyTo?.thumbnail) }
                                                        )
                                                        messageInput = ""
                                                        replyingTo = null
                                                    }
                                                }
                                            )
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.send_chat),
                                        contentDescription = "Send (Hold to search)",
                                        tint = onDynamicPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Embedded App Mini-Player at Bottom (uses user's configured theme & style)
                    effectiveConnection?.let { conn ->
                        CompositionLocalProvider(
                            LocalPlayerConnection provides conn
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                MiniPlayer(
                                    positionState = positionState,
                                    durationState = durationState,
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = {
                                        if (navController != null) {
                                            closeChatModal()
                                            navController.navigate("player")
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
}
}
}

/**
 * Swipeable message item with swipe-left-to-quote, tactile detent haptic, and item animations.
 */
@Composable
private fun LazyItemScope.SwipeableMessageItem(
    message: ChatMessagePayload,
    isMe: Boolean,
    themeColor: Color,
    showSenderName: Boolean = true,
    isPrevSame: Boolean = false,
    isNextSame: Boolean = false,
    onQuote: (ChatMessagePayload) -> Unit
) {
    val density = LocalDensity.current
    val view = LocalView.current
    val maxSwipePx = with(density) { 72.dp.toPx() }
    val swipeOffset = remember { Animatable(0f) }
    var hasTriggeredDetent by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    val isSystem = message.userId == "SYSTEM" || message.message.startsWith("🎵")

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .animateItem()
            .pointerInput(message) {
                detectHorizontalDragGestures(
                    onDragStart = { hasTriggeredDetent = false },
                    onDragEnd = {
                        if (abs(swipeOffset.value) >= maxSwipePx * 0.65f) {
                            onQuote(message)
                        }
                        hasTriggeredDetent = false
                        coroutineScope.launch {
                            swipeOffset.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                        }
                    },
                    onDragCancel = {
                        hasTriggeredDetent = false
                        coroutineScope.launch {
                            swipeOffset.animateTo(0f)
                        }
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        coroutineScope.launch {
                            val nextVal = (swipeOffset.value + dragAmount).coerceIn(-maxSwipePx, 0f)
                            if (abs(nextVal) >= maxSwipePx * 0.65f && !hasTriggeredDetent) {
                                view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                hasTriggeredDetent = true
                            }
                            swipeOffset.snapTo(nextVal)
                        }
                    }
                )
            }
    ) {
        // Revealed Quote Icon on the right side when swiping left with spring scale
        if (swipeOffset.value < -10f) {
            val quoteScale = (abs(swipeOffset.value) / (maxSwipePx * 0.65f)).coerceIn(0.5f, 1.25f)
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 8.dp)
                    .size(36.dp)
                    .graphicsLayer {
                        scaleX = quoteScale
                        scaleY = quoteScale
                    }
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.share),
                    contentDescription = "Quote",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // Message Content Card
        Box(
            modifier = Modifier
                .offset { IntOffset(swipeOffset.value.roundToInt(), 0) }
                .fillMaxWidth()
        ) {
            if (isSystem) {
                SongChangeMessageCard(message = message, themeColor = themeColor)
            } else {
                UserChatMessageBubble(
                    message = message,
                    isMe = isMe,
                    themeColor = themeColor,
                    showSenderName = showSenderName,
                    isPrevSame = isPrevSame,
                    isNextSame = isNextSame
                )
            }
        }
    }
}

/**
 * Beautiful Song Change Message with Album Art and guaranteed legible contrast.
 */
@Composable
private fun SongChangeMessageCard(
    message: ChatMessagePayload,
    themeColor: Color
) {
    val trackInfo = message.trackInfo
    val title = trackInfo?.title ?: message.message.removePrefix("🎵 Now Playing: ").removePrefix("🎵 ").substringBefore(" - ")
    val artist = trackInfo?.artist ?: message.message.substringAfter(" - ", "Echo Music")

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.85f),
        shadowElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .border(1.dp, themeColor.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Album Art Thumbnail
            if (!trackInfo?.thumbnail.isNullOrBlank()) {
                AsyncImage(
                    model = trackInfo?.thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(50.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(themeColor.copy(alpha = 0.25f))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(themeColor.copy(alpha = 0.25f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.music_note),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.music_note),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "NOW PLAYING",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 1.sp
                    )
                }

                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = artist,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Standard User Chat Message Bubble with adaptive corner grouping, reply card, and big emoji reactions.
 */
@Composable
private fun UserChatMessageBubble(
    message: ChatMessagePayload,
    isMe: Boolean,
    themeColor: Color,
    showSenderName: Boolean = true,
    isPrevSame: Boolean = false,
    isNextSame: Boolean = false
) {
    val onThemeColor = themeColor.contrastTextColor()
    val isEmojiOnlyMessage = remember(message.message) {
        val trimmed = message.message.trim()
        trimmed.isNotEmpty() && trimmed.length <= 12 && trimmed.all { ch ->
            val type = Character.getType(ch)
            type == Character.SURROGATE.toInt() ||
                    type == Character.OTHER_SYMBOL.toInt() ||
                    type == Character.MODIFIER_SYMBOL.toInt() ||
                    type == Character.OTHER_PUNCTUATION.toInt() ||
                    ch.code == 0xFE0F ||
                    ch.code == 0x200D ||
                    ch.isWhitespace()
        }
    }

    // Adaptive grouped corner radii
    val topStartRadius = if (!isMe && isPrevSame) 4.dp else 18.dp
    val topEndRadius = if (isMe && isPrevSame) 4.dp else 18.dp
    val bottomStartRadius = if (!isMe && isNextSame) 4.dp else (if (isMe) 18.dp else 4.dp)
    val bottomEndRadius = if (isMe && isNextSame) 4.dp else (if (isMe) 4.dp else 18.dp)
    val bubbleShape = RoundedCornerShape(
        topStart = topStartRadius,
        topEnd = topEndRadius,
        bottomStart = bottomStartRadius,
        bottomEnd = bottomEndRadius
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (showSenderName) 4.dp else 1.dp),
        horizontalAlignment = if (isMe) Alignment.End else Alignment.Start
    ) {
        if (showSenderName) {
            Text(
                text = message.username,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (isMe) MaterialTheme.colorScheme.primary else getVibrantSenderColor(message.username),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }

        if (isEmojiOnlyMessage && message.replyTo == null) {
            // Big Emoji without box
            Text(
                text = message.message,
                fontSize = 42.sp,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
        } else {
            Surface(
                shape = bubbleShape,
                color = if (isMe) themeColor else MaterialTheme.colorScheme.surfaceVariant,
                shadowElevation = 2.dp,
                modifier = Modifier.widthIn(max = 280.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    // Quoted Reply Card (Wrapped to content with thumbnail & cleaned header)
                    message.replyTo?.let { reply ->
                        val isSongReply = !reply.thumbnail.isNullOrBlank() || reply.username == "Echo System" || reply.username.startsWith("🎵")
                        val replyTitle = if (isSongReply) {
                            if (reply.username.startsWith("🎵")) reply.username else "🎵 ${reply.message.removePrefix("🎵 Now Playing: ").removePrefix("🎵 ").substringBefore(" - ")}"
                        } else {
                            reply.username
                        }
                        val replyText = if (isSongReply) {
                            reply.message.substringAfter(" - ", reply.message)
                        } else {
                            reply.message
                        }

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isMe) Color.Black.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                            modifier = Modifier
                                .padding(bottom = 6.dp)
                                .wrapContentWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (!reply.thumbnail.isNullOrBlank()) {
                                    AsyncImage(
                                        model = reply.thumbnail,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(RoundedCornerShape(5.dp))
                                    )
                                } else if (isSongReply) {
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(RoundedCornerShape(5.dp))
                                            .background(MaterialTheme.colorScheme.primaryContainer),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.music_note),
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                                Column {
                                    Text(
                                        text = replyTitle,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isMe) onThemeColor else MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = replyText,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = if (isMe) onThemeColor.copy(alpha = 0.9f) else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }

                    Text(
                        text = message.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isMe) onThemeColor else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun CircularFloatingBubble(
    bubbleDiameter: androidx.compose.ui.unit.Dp,
    bubbleContainerSize: androidx.compose.ui.unit.Dp = bubbleDiameter + 16.dp,
    dragScale: Float,
    dragTilt: Float = 0f,
    squashScaleX: Float = 1f,
    squashScaleY: Float = 1f,
    audioHaloScale: Float = 1f,
    audioHaloAlpha: Float = 0.35f,
    bubbleHaloPref: Boolean = true,
    isDragging: Boolean,
    dynamicPrimary: Color,
    dynamicAccent: Color,
    onDynamicPrimary: Color,
    currentMetadataThumbnail: String?,
    isPlaying: Boolean,
    unreadCount: Int,
    isOnRightSide: Boolean,
    barHeights: List<Float>,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(bubbleContainerSize),
        contentAlignment = Alignment.Center
    ) {
        // Audio breathing halo behind bubble (stays within fixed container bounds and breathes purely via graphicsLayer)
        if (isPlaying && bubbleHaloPref) {
            Box(
                modifier = Modifier
                    .size(bubbleDiameter * 1.25f)
                    .graphicsLayer {
                        scaleX = audioHaloScale
                        scaleY = audioHaloScale
                        alpha = audioHaloAlpha
                    }
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(
                                dynamicPrimary.copy(alpha = 0.55f),
                                dynamicAccent.copy(alpha = 0.25f),
                                Color.Transparent
                            )
                        )
                    )
            )
        }

        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = dynamicPrimary.copy(alpha = 0.15f),
            shadowElevation = if (isDragging) 22.dp else 12.dp,
            modifier = Modifier
                .graphicsLayer {
                    rotationZ = dragTilt
                    scaleX = dragScale * squashScaleX
                    scaleY = dragScale * squashScaleY
                }
                .size(bubbleDiameter)
                .clip(CircleShape)
                .drawWithContent {
                    drawContent()
                    drawCircle(
                        color = dynamicPrimary,
                        radius = size.minDimension / 2f - (if (isDragging) 1.25.dp.toPx() else 1.dp.toPx()),
                        style = Stroke(width = if (isDragging) 2.5.dp.toPx() else 2.dp.toPx())
                    )
                }
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                if (!currentMetadataThumbnail.isNullOrBlank()) {
                    AsyncImage(
                        model = currentMetadataThumbnail,
                        contentDescription = "Playing Track Poster",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    // Inner vignette for readability
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.radialGradient(
                                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.28f))
                                )
                            )
                    )
                    if (isPlaying) {
                        // Animated 3-bar equalizer indicator in bottom-right
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(4.dp)
                                .size(18.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(dynamicPrimary.copy(alpha = 0.85f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                verticalAlignment = Alignment.Bottom,
                                modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp)
                            ) {
                                barHeights.forEach { barH ->
                                    Box(
                                        modifier = Modifier
                                            .width(2.5.dp)
                                            .height(barH.dp)
                                            .clip(RoundedCornerShape(1.dp))
                                            .background(onDynamicPrimary)
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Icon(
                        painter = painterResource(R.drawable.chat_msg),
                        contentDescription = "Open Chat",
                        tint = dynamicPrimary,
                        modifier = Modifier.size(bubbleDiameter * 0.48f)
                    )
                }
            }
        }

        // Counter badge placed OUTSIDE the Surface on the screen-facing shoulder so it is never cut
        if (unreadCount > 0) {
            Badge(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
                modifier = Modifier
                    .align(if (isOnRightSide) Alignment.TopStart else Alignment.TopEnd)
                    .offset(
                        x = if (isOnRightSide) 4.dp else (-4).dp,
                        y = 4.dp
                    )
            ) {
                Text(
                    text = if (unreadCount > 99) "99+" else unreadCount.toString(),
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun SpeechBubbleCallout(
    unreadMessages: List<ChatMessagePayload>,
    isOnRightSide: Boolean,
    themeColor: Color,
    fontScale: Float,
    fontWeight: FontWeight,
    onSwipeDismiss: () -> Unit = {},
    onClick: () -> Unit
) {
    val bubbleShape = if (isOnRightSide) {
        RoundedCornerShape(topStart = 16.dp, topEnd = 6.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
    } else {
        RoundedCornerShape(topStart = 6.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
    }

    val calloutSwipeOffset = remember { Animatable(0f) }
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()

    Surface(
        onClick = onClick,
        shape = bubbleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.96f),
        shadowElevation = 12.dp,
        border = BorderStroke(1.2.dp, themeColor),
        modifier = Modifier
            .widthIn(min = 90.dp, max = 260.dp)
            .wrapContentWidth()
            .offset { IntOffset(calloutSwipeOffset.value.roundToInt(), 0) }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { },
                    onDragEnd = {
                        if (abs(calloutSwipeOffset.value) > with(density) { 45.dp.toPx() }) {
                            onSwipeDismiss()
                        }
                        coroutineScope.launch {
                            calloutSwipeOffset.animateTo(0f)
                        }
                    },
                    onDragCancel = {
                        coroutineScope.launch { calloutSwipeOffset.animateTo(0f) }
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        coroutineScope.launch {
                            calloutSwipeOffset.snapTo(calloutSwipeOffset.value + dragAmount)
                        }
                    }
                )
            }
            .padding(vertical = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val visible = unreadMessages.takeLast(3)
            val linesPerMessage = when (visible.size) {
                1 -> 4
                2 -> 2
                else -> 1
            }
            visible.forEachIndexed { idx, msg ->
                val isSong = msg.trackInfo != null || msg.userId == "SYSTEM" || msg.message.startsWith("🎵")
                val quoteThumb = msg.trackInfo?.thumbnail ?: msg.replyTo?.thumbnail

                val prevMsg = visible.getOrNull(idx - 1)
                val showSenderName = (prevMsg == null || prevMsg.userId != msg.userId || prevMsg.username != msg.username)
                val vibrantSenderColor = if (isSong) themeColor else getVibrantSenderColor(msg.username)

                if (msg.replyTo != null && visible.size <= 2) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                        modifier = Modifier.padding(bottom = 2.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (!msg.replyTo.thumbnail.isNullOrBlank()) {
                                AsyncImage(
                                    model = msg.replyTo.thumbnail,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size((12 * fontScale).dp)
                                        .clip(RoundedCornerShape(3.dp))
                                )
                            }
                            Text(
                                text = "↪ ${msg.replyTo.username}: ${msg.replyTo.message.take(22)}",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = (9 * fontScale).sp
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier.wrapContentWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (showSenderName) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = vibrantSenderColor.copy(alpha = 0.22f),
                            border = BorderStroke(0.75.dp, vibrantSenderColor.copy(alpha = 0.55f)),
                            modifier = Modifier.padding(bottom = 2.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (isSong) {
                                    Icon(
                                        painter = painterResource(R.drawable.music_note),
                                        contentDescription = null,
                                        tint = vibrantSenderColor,
                                        modifier = Modifier.size(10.dp)
                                    )
                                    Text(
                                        text = "Now Playing",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = (9.5f * fontScale).sp,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        color = Color.White
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(vibrantSenderColor)
                                    )
                                    Text(
                                        text = msg.username,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = (10f * fontScale).sp,
                                            fontWeight = FontWeight.ExtraBold
                                        ),
                                        color = vibrantSenderColor
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (isSong && !quoteThumb.isNullOrBlank()) {
                            AsyncImage(
                                model = quoteThumb,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size((20 * fontScale).dp)
                                    .clip(RoundedCornerShape(4.dp))
                            )
                        }
                        Text(
                            text = if (isSong) msg.message.removePrefix("🎵 Now Playing: ").removePrefix("🎵 ") else msg.message,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = (11.5f * fontScale).sp,
                                fontWeight = fontWeight,
                                lineHeight = (15f * fontScale).sp
                            ),
                            color = Color.White,
                            maxLines = linesPerMessage,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}


