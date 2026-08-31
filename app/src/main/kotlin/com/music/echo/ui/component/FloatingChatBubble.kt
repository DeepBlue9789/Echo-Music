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
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import echo.music.iad1tya.ui.theme.PlayerColorExtractor
import echo.music.iad1tya.utils.rememberPreference
import echo.music.iad1tya.constants.ListenTogetherChatBlurIntensityKey
import echo.music.iad1tya.constants.ListenTogetherChatTintIntensityKey
import echo.music.iad1tya.constants.ListenTogetherChatFontSizeKey
import echo.music.iad1tya.constants.ListenTogetherChatFontWeightKey
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.TransformOrigin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

private fun Color.contrastTextColor(): Color {
    return if (this.luminance() > 0.45f) Color.Black else Color.White
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

    // Dynamic song colors extraction
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
                    // Fallback to default
                }
            }
        } else {
            songColors = listOf(fallbackPrimary, fallbackSecondary)
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

    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    val edgePaddingPx = with(density) { 16.dp.toPx() }

    val leftDockX = - with(density) { (bubbleDiameter * 0.25f).toPx() }
    val rightDockX = screenWidthPx - with(density) { (bubbleDiameter * 0.75f).toPx() }

    val initialDockX = rightDockX
    val offsetX = remember { Animatable(initialDockX) }
    val offsetY = remember { Animatable(screenHeightPx * 0.45f) }

    var isExpanded by rememberSaveable { mutableStateOf(forceExpanded) }
    var isDismissed by rememberSaveable { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }

    // Premium spring drag scale — bubble scales to 0.9 while being dragged, bounces back on release
    val dragScale by animateFloatAsState(
        targetValue = if (isDragging) 0.88f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "bubbleDragScale"
    )

    // Animated equalizer for playing indicator (3 bars)
    val infiniteTransition = rememberInfiniteTransition(label = "equalizer")
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

    // Speech bubble callout on incoming message (no card-morph)
    var isCalloutShowing by remember { mutableStateOf(false) }
    var calloutTimerJob by remember { mutableStateOf<Job?>(null) }

    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(messages.size) {
        val lastMsg = messages.lastOrNull()
        if (lastMsg != null && lastMsg.userId != "SYSTEM" && lastMsg.userId != currentUserId) {
            isDismissed = false
            if (!isExpanded) {
                isCalloutShowing = true
                calloutTimerJob?.cancel()
                calloutTimerJob = coroutineScope.launch {
                    delay(3000)
                    isCalloutShowing = false
                }
            }
        }
    }

    LaunchedEffect(isCalloutShowing, unreadUserMessages.size) {
        onCalloutVisibilityChanged?.invoke(isCalloutShowing && unreadUserMessages.isNotEmpty())
    }

    if (isDismissed && !isExpanded) return

    val currentBubbleX = if (isOverlayMode) (bubbleAnchorPosition?.first ?: rightDockX) else offsetX.value
    val isOnRightSide = currentBubbleX > (screenWidthPx / 2)

    Box(
        modifier = if (isOverlayMode && !isExpanded) Modifier.wrapContentSize() else modifier.fillMaxSize()
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
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.95f),
                    shadowElevation = 12.dp,
                    modifier = Modifier.size(68.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(R.drawable.close),
                            contentDescription = "Dismiss bubble",
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }
            }
        }        // Draggable Floating Bubble & Speech Callout
        if (!isExpanded) {
            Box(
                modifier = Modifier
                    .then(
                        if (isOverlayMode) {
                            Modifier.wrapContentSize()
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
                                        (clampedX - (placeable.width - bubbleDiameter.toPx())).roundToInt()
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
                                onDragStart = { isDragging = true },
                                onDragEnd = {
                                    isDragging = false
                                    onOverlayDragEnd?.invoke()
                                },
                                onDragCancel = { isDragging = false },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    onOverlayDrag?.invoke(dragAmount.x, dragAmount.y)
                                }
                            )
                        } else {
                            detectDragGestures(
                                onDragStart = { isDragging = true },
                                onDragEnd = {
                                    isDragging = false
                                    coroutineScope.launch {
                                        if (offsetY.value > screenHeightPx - with(density) { 150.dp.toPx() } &&
                                            offsetX.value > screenWidthPx * 0.25f &&
                                            offsetX.value < screenWidthPx * 0.75f
                                        ) {
                                            isDismissed = true
                                        } else {
                                            val snapTargetX = if (offsetX.value < screenWidthPx / 2) leftDockX else rightDockX
                                            offsetX.animateTo(
                                                snapTargetX,
                                                spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)
                                            )
                                        }
                                    }
                                },
                                onDragCancel = { isDragging = false },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    coroutineScope.launch {
                                        offsetX.snapTo((offsetX.value + dragAmount.x).coerceIn(leftDockX, rightDockX))
                                        offsetY.snapTo((offsetY.value + dragAmount.y).coerceIn(with(density) { 50.dp.toPx() }, screenHeightPx - with(density) { 100.dp.toPx() }))
                                    }
                                }
                            )
                        }
                    }
            ) {
                // Layout: Speech Callout balloon + Circular Floating Bubble anchored securely
                Box(
                    modifier = Modifier.wrapContentSize(),
                    contentAlignment = if (isOnRightSide) Alignment.CenterEnd else Alignment.CenterStart
                ) {
                    if (isOnRightSide) {
                        // When docked right, speech callout pops out to the LEFT of the bubble
                        AnimatedVisibility(
                            visible = isCalloutShowing && unreadUserMessages.isNotEmpty(),
                            enter = fadeIn(spring()) + scaleIn(initialScale = 0.4f, transformOrigin = TransformOrigin(1f, 0.5f)),
                            exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.4f, transformOrigin = TransformOrigin(1f, 0.5f)),
                            modifier = Modifier.padding(end = bubbleDiameter + 8.dp)
                        ) {
                            SpeechBubbleCallout(
                                unreadMessages = unreadUserMessages,
                                isOnRightSide = true,
                                themeColor = dynamicPrimary,
                                fontScale = chatFontScale,
                                fontWeight = chatFontWeight,
                                onClick = {
                                    isExpanded = true
                                    onExpandChanged?.invoke(true, chatBlurIntensity)
                                    isCalloutShowing = false
                                    lastReadUserMessageCount = userMessages.size
                                }
                            )
                        }
                        CircularFloatingBubble(
                            bubbleDiameter = bubbleDiameter,
                            dragScale = dragScale,
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
                                isExpanded = true
                                onExpandChanged?.invoke(true, chatBlurIntensity)
                                isCalloutShowing = false
                                lastReadUserMessageCount = userMessages.size
                            }
                        )
                    } else {
                        // When docked left, speech callout pops out to the RIGHT of the bubble
                        CircularFloatingBubble(
                            bubbleDiameter = bubbleDiameter,
                            dragScale = dragScale,
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
                                isExpanded = true
                                onExpandChanged?.invoke(true, chatBlurIntensity)
                                isCalloutShowing = false
                                lastReadUserMessageCount = userMessages.size
                            }
                        )
                        AnimatedVisibility(
                            visible = isCalloutShowing && unreadUserMessages.isNotEmpty(),
                            enter = fadeIn(spring()) + scaleIn(initialScale = 0.4f, transformOrigin = TransformOrigin(0f, 0.5f)),
                            exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.4f, transformOrigin = TransformOrigin(0f, 0.5f)),
                            modifier = Modifier.padding(start = bubbleDiameter + 8.dp)
                        ) {
                            SpeechBubbleCallout(
                                unreadMessages = unreadUserMessages,
                                isOnRightSide = false,
                                themeColor = dynamicPrimary,
                                fontScale = chatFontScale,
                                fontWeight = chatFontWeight,
                                onClick = {
                                    isExpanded = true
                                    onExpandChanged?.invoke(true, chatBlurIntensity)
                                    isCalloutShowing = false
                                    lastReadUserMessageCount = userMessages.size
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // Expanded Dynamic Themed Chat Modal — smooth fluid transition from bubble anchor
    val anchorX = bubbleAnchorPosition?.first ?: offsetX.value
    val anchorY = bubbleAnchorPosition?.second ?: offsetY.value
    val pivotX = (anchorX / screenWidthPx).coerceIn(0.08f, 0.92f)
    val pivotY = (anchorY / screenHeightPx).coerceIn(0.08f, 0.92f)
    val transformOrigin = TransformOrigin(pivotX, pivotY)

    AnimatedVisibility(
        visible = isExpanded,
        enter = fadeIn(tween(220)) +
                scaleIn(
                    initialScale = 0.85f,
                    transformOrigin = transformOrigin,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMedium)
                ),
        exit = fadeOut(tween(160)) +
               scaleOut(
                   targetScale = 0.88f,
                   transformOrigin = transformOrigin,
                   animationSpec = tween(160)
               )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(if (isOverlayMode) Color.Transparent else Color.Black.copy(alpha = 0.38f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    if (forceExpanded) {
                        onDismiss?.invoke()
                    } else {
                        isExpanded = false
                        onExpandChanged?.invoke(false, 0f)
                        lastReadUserMessageCount = userMessages.size
                    }
                }
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 18.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.92f)
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
                                dynamicPrimary.copy(alpha = 0.85f),
                                Color.White.copy(alpha = 0.45f),
                                dynamicAccent.copy(alpha = 0.65f)
                            )
                        ),
                        shape = RoundedCornerShape(28.dp)
                    ),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = chatTintIntensity.coerceIn(0.65f, 0.96f))
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Ambient Frosted Glass illumination layer
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        dynamicPrimary.copy(alpha = (chatBlurIntensity / 30f) * 0.22f),
                                        dynamicAccent.copy(alpha = (chatBlurIntensity / 30f) * 0.12f),
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
                                        Color.White.copy(alpha = 0.08f),
                                        Color.Transparent,
                                        Color.Black.copy(alpha = 0.14f)
                                    )
                                )
                            )
                    )
                    Column(
                        modifier = Modifier.fillMaxSize()
                    ) {
                    // Header with Gear Settings and Disconnect buttons
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        dynamicPrimary.copy(alpha = 0.35f),
                                        dynamicAccent.copy(alpha = 0.2f)
                                    )
                                )
                            )
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(dynamicPrimary),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.chat_msg),
                                    contentDescription = null,
                                    tint = onDynamicPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = "Listen Together Chat",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Swipe left to quote • Long press send to search",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f),
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        // Actions: Settings, Disconnect, Close
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    isExpanded = false
                                    onExpandChanged?.invoke(false, 0f)
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
                                Icon(
                                    painter = painterResource(R.drawable.settings),
                                    contentDescription = "Listen Together Settings",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            IconButton(
                                onClick = {
                                    isExpanded = false
                                    onExpandChanged?.invoke(false, 0f)
                                    manager.leaveRoom()
                                    Toast.makeText(context, "Disconnected from session", Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.logout),
                                    contentDescription = "Disconnect",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }

                            IconButton(
                                onClick = {
                                    if (forceExpanded) {
                                        onDismiss?.invoke()
                                    } else {
                                        isExpanded = false
                                        onExpandChanged?.invoke(false, 0f)
                                        lastReadUserMessageCount = userMessages.size
                                    }
                                }
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.close),
                                    contentDescription = "Close",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    // Messages List (Scrollable)
                    val listState = rememberLazyListState()
                    var replyingTo by remember { mutableStateOf<ChatMessagePayload?>(null) }
                    var messageInput by remember { mutableStateOf("") }

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

                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        contentPadding = PaddingValues(vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
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
                                        text = "No messages yet. Send a message, quote songs, or long press send to search!",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        itemsIndexed(messages, key = { _, it -> "${it.userId}_${it.timestamp}_${it.message.hashCode()}" }) { idx, msg ->
                            val prevMsg = messages.getOrNull(idx - 1)
                            val showSenderName = (prevMsg == null || prevMsg.userId != msg.userId || prevMsg.message.startsWith("🎵") || msg.message.startsWith("🎵") || (msg.timestamp - prevMsg.timestamp) > 120_000L)
                            SwipeableMessageItem(
                                message = msg,
                                isMe = msg.userId == currentUserId,
                                themeColor = dynamicPrimary,
                                showSenderName = showSenderName,
                                onQuote = {
                                    val isSong = it.trackInfo != null || it.userId == "SYSTEM" || it.message.startsWith("🎵")
                                    val title = it.trackInfo?.title ?: it.message.removePrefix("🎵 Now Playing: ").removePrefix("🎵 ").substringBefore(" - ")
                                    val artist = it.trackInfo?.artist ?: it.message.substringAfter(" - ", "Echo Music")
                                    val thumb = it.trackInfo?.thumbnail ?: it.replyTo?.thumbnail ?: (if (isSong) (currentMetadataThumbnail ?: roomState?.currentTrack?.thumbnail) else null)

                                    val quotePayload = if (isSong) {
                                        it.copy(
                                            username = "🎵 $title",
                                            message = "$title - $artist",
                                            trackInfo = it.trackInfo ?: TrackInfo(
                                                id = roomState?.currentTrack?.id ?: "current",
                                                title = title,
                                                artist = artist,
                                                duration = 0L,
                                                thumbnail = thumb
                                            )
                                        )
                                    } else {
                                        it
                                    }
                                    replyingTo = quotePayload
                                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                }
                            )
                        }
                    }

                    // Quick Big Emoji Reaction Bar (Immediate send)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(dynamicPrimary.copy(alpha = 0.08f))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf("❤️", "🔥", "👏", "🎶", "😂", "😮", "🎉", "⚡").forEach { emoji ->
                            Text(
                                text = emoji,
                                fontSize = 24.sp,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable {
                                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
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
                                    if (isSearchMode) "Search songs on YouTube..." else "Send a message...",
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
                            // Send / Long-Press Search Button
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(CircleShape)
                                    .background(dynamicPrimary)
                                    .combinedClickable(
                                        onClick = {
                                            if (messageInput.isNotBlank()) {
                                                manager.sendChatMessage(
                                                    messageInput.trim(),
                                                    replyingTo?.let { RepliedMessage(it.username, it.message, it.trackInfo?.thumbnail ?: it.replyTo?.thumbnail) }
                                                )
                                                messageInput = ""
                                                replyingTo = null
                                            }
                                        },
                                        onLongClick = {
                                            if (messageInput.isBlank()) {
                                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                                isSearchMode = true
                                                focusRequester.requestFocus()
                                            }
                                        }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.send_chat),
                                    contentDescription = "Send (Long press to search)",
                                    tint = onDynamicPrimary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }

                    // Embedded Dynamic Mini-Player at Bottom (High Contrast Controls)
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.95f),
                        shape = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                AsyncImage(
                                    model = currentMetadataThumbnail,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(dynamicPrimary.copy(alpha = 0.3f))
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = currentMetadataTitle ?: "No track playing",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = currentMetadataArtist,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            // Playback Controls (Brilliant contrast on all themes)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = {
                                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                        val player = effectiveConnection?.player
                                        if (player != null) player.seekToPrevious() else effectiveConnection?.seekToPrevious()
                                    }
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.skip_previous),
                                        contentDescription = "Previous",
                                        tint = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                        val player = effectiveConnection?.player
                                        if (player != null) {
                                            if (player.playWhenReady) player.pause() else player.play()
                                        } else {
                                            if (isPlaying) effectiveConnection?.pause() else effectiveConnection?.play()
                                        }
                                    }
                                ) {
                                    Icon(
                                        painter = painterResource(if (isPlaying) R.drawable.pause else R.drawable.play),
                                        contentDescription = if (isPlaying) "Pause" else "Play",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                        val player = effectiveConnection?.player
                                        if (player != null) player.seekToNext() else effectiveConnection?.seekToNext()
                                    }
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.skip_next),
                                        contentDescription = "Next",
                                        tint = MaterialTheme.colorScheme.onSurface
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

/**
 * Swipeable message item with swipe-left-to-quote and high-contrast indicators.
 */
@Composable
private fun SwipeableMessageItem(
    message: ChatMessagePayload,
    isMe: Boolean,
    themeColor: Color,
    showSenderName: Boolean = true,
    onQuote: (ChatMessagePayload) -> Unit
) {
    val density = LocalDensity.current
    val maxSwipePx = with(density) { 72.dp.toPx() }
    val swipeOffset = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()

    val isSystem = message.userId == "SYSTEM" || message.message.startsWith("🎵")

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(message) {
                detectHorizontalDragGestures(
                    onDragStart = { },
                    onDragEnd = {
                        if (abs(swipeOffset.value) >= maxSwipePx * 0.65f) {
                            onQuote(message)
                        }
                        coroutineScope.launch {
                            swipeOffset.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                        }
                    },
                    onDragCancel = {
                        coroutineScope.launch {
                            swipeOffset.animateTo(0f)
                        }
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        coroutineScope.launch {
                            // Swipe left: dragAmount.x is negative
                            swipeOffset.snapTo((swipeOffset.value + dragAmount).coerceIn(-maxSwipePx, 0f))
                        }
                    }
                )
            }
    ) {
        // Revealed Quote Icon on the right side when swiping left
        if (swipeOffset.value < -10f) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 8.dp)
                    .size(36.dp)
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
                UserChatMessageBubble(message = message, isMe = isMe, themeColor = themeColor, showSenderName = showSenderName)
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
 * Standard User Chat Message Bubble with reply card, mini album art, and big emoji reactions.
 */
@Composable
private fun UserChatMessageBubble(
    message: ChatMessagePayload,
    isMe: Boolean,
    themeColor: Color,
    showSenderName: Boolean = true
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

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = if (showSenderName) 4.dp else 1.dp),
        horizontalAlignment = if (isMe) Alignment.End else Alignment.Start
    ) {
        if (showSenderName) {
            Text(
                text = message.username,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (isMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
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
                shape = RoundedCornerShape(
                    topStart = 18.dp,
                    topEnd = 18.dp,
                    bottomStart = if (isMe) 18.dp else 4.dp,
                    bottomEnd = if (isMe) 4.dp else 18.dp
                ),
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
    dragScale: Float,
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
            .wrapContentSize()
            .padding(vertical = 4.dp, horizontal = 2.dp)
    ) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = dynamicPrimary.copy(alpha = 0.15f),
            shadowElevation = if (isDragging) 20.dp else 10.dp,
            modifier = Modifier
                .graphicsLayer { scaleX = dragScale; scaleY = dragScale }
                .size(bubbleDiameter)
                .clip(CircleShape)
                .border(
                    width = if (isDragging) 2.5.dp else 2.dp,
                    brush = Brush.sweepGradient(listOf(dynamicPrimary, dynamicAccent, dynamicPrimary)),
                    shape = CircleShape
                )
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

        // Counter badge placed on opposite edge to avoid screen clipping
        if (unreadCount > 0) {
            Badge(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
                modifier = Modifier
                    .align(if (isOnRightSide) Alignment.TopStart else Alignment.TopEnd)
                    .offset(
                        x = if (isOnRightSide) 2.dp else (-2).dp,
                        y = (-2).dp
                    )
            ) {
                Text(
                    text = if (unreadCount > 99) "99+" else unreadCount.toString(),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
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
    onClick: () -> Unit
) {
    val bubbleShape = if (isOnRightSide) {
        RoundedCornerShape(topStart = 18.dp, topEnd = 6.dp, bottomStart = 18.dp, bottomEnd = 18.dp)
    } else {
        RoundedCornerShape(topStart = 6.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp)
    }

    Surface(
        onClick = onClick,
        shape = bubbleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.96f),
        shadowElevation = 12.dp,
        border = BorderStroke(1.5.dp, themeColor.copy(alpha = 0.7f)),
        modifier = Modifier
            .widthIn(min = 140.dp, max = 220.dp)
            .padding(vertical = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val visible = unreadMessages.takeLast(4)
            val linesPerMessage = when (visible.size) {
                1 -> 5
                2 -> 2
                3 -> 2
                else -> 1
            }
            visible.forEachIndexed { idx, msg ->
                val isSong = msg.trackInfo != null || msg.userId == "SYSTEM" || msg.message.startsWith("🎵")
                val quoteThumb = msg.trackInfo?.thumbnail ?: msg.replyTo?.thumbnail

                val prevMsg = visible.getOrNull(idx - 1)
                val showSenderName = (prevMsg == null || prevMsg.userId != msg.userId || prevMsg.username != msg.username)

                if (msg.replyTo != null && visible.size <= 2) {
                    Row(
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
                            text = "↪ ${msg.replyTo.username}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = (9 * fontScale).sp
                            ),
                            color = themeColor.copy(alpha = 0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
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
                                .size((16 * fontScale).dp)
                                .clip(RoundedCornerShape(4.dp))
                        )
                    }
                    Column {
                        if (showSenderName) {
                            Text(
                                text = "${msg.username}:",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = (10 * fontScale).sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = themeColor,
                                maxLines = 1
                            )
                        }
                        Text(
                            text = if (isSong) msg.message.removePrefix("🎵 Now Playing: ").removePrefix("🎵 ") else msg.message,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = (11 * fontScale).sp,
                                fontWeight = fontWeight
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = linesPerMessage,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

