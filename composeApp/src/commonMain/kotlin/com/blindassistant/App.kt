package com.blindassistant

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.sin

@Composable
fun App(
    aiClient: AiClient,
    voiceService: VoiceService
) {
    val status by voiceService.status.collectAsState()
    val transcript by voiceService.transcript.collectAsState()
    val assistantReply by voiceService.assistantReply.collectAsState()
    val isListening by voiceService.isListening.collectAsState()
    val isSpeaking by voiceService.isSpeaking.collectAsState()
    val cameraPopupState by voiceService.cameraPopupState.collectAsState()

    var manualInputText by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()

    // Dynamic Color Palette for Accessibility & High Contrast
    val accentGold = Color(0xFFFFD600)
    val listeningRed = Color(0xFFFF2A55)
    val speakingGreen = Color(0xFF00E676)
    val processingPurple = Color(0xFF7C4DFF)
    val surfaceDark = Color(0xFF141419)
    val cardBackground = Color(0xFF1C1C24)
    val outlineBorder = Color(0xFF2E2E3C)

    val isProcessing = status.contains("Processing", ignoreCase = true)

    val currentThemeColor by animateColorAsState(
        targetValue = when {
            isListening -> listeningRed
            isSpeaking -> speakingGreen
            isProcessing -> processingPurple
            else -> accentGold
        },
        animationSpec = tween(400),
        label = "themeColor"
    )

    // Pulse & Wave Animations
    val infiniteTransition = rememberInfiniteTransition(label = "pulseEffects")

    val idlePulse by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "idlePulse"
    )

    val auraScale1 by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.42f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "auraScale1"
    )

    val auraScale2 by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.72f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, delayMillis = 350, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "auraScale2"
    )

    val auraAlpha by infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "auraAlpha"
    )

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = currentThemeColor,
            surface = surfaceDark,
            background = Color.Black
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            Box(Modifier.fillMaxSize()) {
                // Ambient Background Glow
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(currentThemeColor.copy(alpha = 0.08f), Color.Black)
                            )
                        )
                )

                // Small Floating Camera Popup
                androidx.compose.animation.AnimatedVisibility(
                    visible = cameraPopupState.isVisible,
                    enter = androidx.compose.animation.fadeIn(tween(300)) + androidx.compose.animation.slideInVertically(initialOffsetY = { -it / 2 }),
                    exit = androidx.compose.animation.fadeOut(tween(250)) + androidx.compose.animation.slideOutVertically(targetOffsetY = { -it / 2 }),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 10.dp, start = 16.dp, end = 16.dp)
                        .zIndex(20f)
                ) {
                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = Color(0xFF14141E),
                        border = BorderStroke(2.dp, accentGold.copy(alpha = 0.85f)),
                        shadowElevation = 18.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics(mergeDescendants = true) {
                                contentDescription = "Camera Active: ${cameraPopupState.title}. ${cameraPopupState.status}"
                            }
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            // Header Row: Title & Close Button
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(listeningRed)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = cameraPopupState.title.uppercase(),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Black,
                                        color = accentGold,
                                        letterSpacing = 1.2.sp
                                    )
                                }

                                Surface(
                                    shape = CircleShape,
                                    color = Color.White.copy(alpha = 0.12f),
                                    modifier = Modifier.clickable { voiceService.dismissCameraPopup() }
                                ) {
                                    Box(
                                        modifier = Modifier.size(28.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("✕", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    }
                                }
                            }

                            Spacer(Modifier.height(10.dp))

                            // Viewfinder Area with Actual Camera Image Rendering
                            val capturedBitmap = remember(cameraPopupState.base64Thumbnail) {
                                cameraPopupState.base64Thumbnail?.let { decodeBase64ToImageBitmap(it) }
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(180.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color(0xFF08080C))
                                    .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(14.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (capturedBitmap != null) {
                                    // Actual Camera Image
                                    Image(
                                        bitmap = capturedBitmap,
                                        contentDescription = "Actual Camera Capture",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )

                                    // Subtle Vignette Overlay for HUD contrast
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(
                                                Brush.verticalGradient(
                                                    colors = listOf(
                                                        Color.Black.copy(alpha = 0.35f),
                                                        Color.Transparent,
                                                        Color.Black.copy(alpha = 0.55f)
                                                    )
                                                )
                                            )
                                    )
                                }

                                // Corner HUD Brackets
                                Column(
                                    modifier = Modifier.fillMaxSize().padding(10.dp),
                                    verticalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("⌜", fontSize = 20.sp, color = accentGold.copy(alpha = 0.85f), fontWeight = FontWeight.Black)
                                        Text("⌝", fontSize = 20.sp, color = accentGold.copy(alpha = 0.85f), fontWeight = FontWeight.Black)
                                    }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("⌞", fontSize = 20.sp, color = accentGold.copy(alpha = 0.85f), fontWeight = FontWeight.Black)
                                        Text("⌟", fontSize = 20.sp, color = accentGold.copy(alpha = 0.85f), fontWeight = FontWeight.Black)
                                    }
                                }

                                if (capturedBitmap == null) {
                                    // Capturing Frame State
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("📷", fontSize = 32.sp)
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            text = "CAPTURING CAMERA FRAME...",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Black,
                                            color = accentGold,
                                            letterSpacing = 1.sp
                                        )
                                    }
                                } else {
                                    // Badge over the actual camera photo
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopStart)
                                            .padding(8.dp)
                                            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
                                            .padding(horizontal = 7.dp, vertical = 3.dp)
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(speakingGreen))
                                            Spacer(Modifier.width(4.dp))
                                            Text("ACTUAL CAMERA PHOTO", fontSize = 9.sp, fontWeight = FontWeight.Black, color = Color.White)
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.height(10.dp))

                            // Bottom Status & Dismiss
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = cameraPopupState.status,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color.White.copy(alpha = 0.85f),
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = accentGold.copy(alpha = 0.18f),
                                    modifier = Modifier.clickable { voiceService.dismissCameraPopup() }
                                ) {
                                    Text(
                                        text = "Dismiss",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = accentGold,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("BLIND AI", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color.White)
                            Text("VOICE ASSISTANT", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = currentThemeColor, letterSpacing = 2.5.sp)
                        }

                        // Status Pill Badge
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = currentThemeColor.copy(alpha = 0.14f),
                            border = BorderStroke(1.5.dp, currentThemeColor.copy(alpha = 0.45f)),
                            modifier = Modifier.clickable { if (isSpeaking) voiceService.stopSpeaking() else if (isListening) voiceService.stopListening() }
                        ) {
                            Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(currentThemeColor))
                                Spacer(Modifier.width(8.dp))
                                Text(when { isListening -> "LISTENING"; isSpeaking -> "SPEAKING"; isProcessing -> "PROCESSING"; else -> "READY" }, fontSize = 11.sp, fontWeight = FontWeight.Black, color = Color.White)
                            }
                        }
                    }

                    Spacer(Modifier.height(24.dp))

                    // Microphone Area
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.height(240.dp)) {
                        if (isListening || isSpeaking) {
                            Box(modifier = Modifier.size(180.dp).scale(auraScale1).border(2.5.dp, currentThemeColor.copy(alpha = auraAlpha), CircleShape))
                            Box(modifier = Modifier.size(180.dp).scale(auraScale2).border(1.5.dp, currentThemeColor.copy(alpha = auraAlpha * 0.6f), CircleShape))
                        }
                        Box(
                            modifier = Modifier
                                .size(175.dp)
                                .scale(if (isListening || isSpeaking) 1.03f else idlePulse)
                                .clip(CircleShape)
                                .background(Brush.radialGradient(listOf(currentThemeColor, currentThemeColor.copy(alpha = 0.55f))))
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onPress = {
                                            val pressStart = kotlin.time.TimeSource.Monotonic.markNow()
                                            if (!isListening && !isSpeaking) {
                                                voiceService.startListening()
                                            }
                                            tryAwaitRelease()
                                            val duration = pressStart.elapsedNow()
                                            // Only stop if the user intentionally held down the button (>450ms)
                                            // If tapped (<450ms), let it stay listening for speech!
                                            if (duration.inWholeMilliseconds > 450L && isListening) {
                                                voiceService.stopListening()
                                            }
                                        },
                                        onTap = {
                                            when {
                                                isSpeaking -> voiceService.stopSpeaking()
                                                isListening -> voiceService.stopListening()
                                                else -> voiceService.startListening()
                                            }
                                        }
                                    )
                                }
                                .semantics { role = Role.Button },
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                CustomMicIcon(tint = Color.Black, isListening = isListening, modifier = Modifier.size(46.dp))
                                Spacer(Modifier.height(6.dp))
                                Text(if (isListening) "TAP OR RELEASE" else if (isSpeaking) "TAP TO STOP" else "TAP TO SPEAK", fontSize = 10.sp, fontWeight = FontWeight.Black, color = Color.Black)
                            }
                        }
                    }

                    VoiceWaveVisualizer(isActive = isListening || isSpeaking, color = currentThemeColor, modifier = Modifier.fillMaxWidth(0.6f).height(24.dp))

                    Spacer(Modifier.height(16.dp))

                    // Live Transcript & Conversation Content Area
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        shape = RoundedCornerShape(24.dp),
                        color = cardBackground,
                        border = BorderStroke(1.5.dp, outlineBorder)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                                .verticalScroll(scrollState)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = when {
                                        isListening -> "LIVE TRANSCRIPT"
                                        transcript.isNotBlank() || assistantReply.isNotBlank() -> "CONVERSATION"
                                        else -> "DASHBOARD"
                                    },
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Black,
                                    color = if (isListening) listeningRed else Color.White.copy(alpha = 0.6f),
                                    letterSpacing = 1.5.sp
                                )

                                if (transcript.isNotBlank() || assistantReply.isNotBlank()) {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = Color.White.copy(alpha = 0.08f),
                                        modifier = Modifier.clickable {
                                            if (isSpeaking) voiceService.stopSpeaking()
                                        }
                                    ) {
                                        Text(
                                            text = if (isSpeaking) "Stop Audio" else "Clear Audio",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White.copy(alpha = 0.7f),
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                            }

                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 12.dp),
                                color = Color.White.copy(alpha = 0.08f)
                            )

                            // Live Recognized Speech / User Transcript Area
                            if (isListening || transcript.isNotBlank()) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .semantics(mergeDescendants = true) {
                                            if (!isListening && transcript.isNotBlank()) {
                                                contentDescription = "Your speech: $transcript"
                                            }
                                        }
                                ) {
                                    Text(
                                        text = if (isListening) "LISTENING..." else "YOU SAID:",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Black,
                                        color = if (isListening) listeningRed else accentGold,
                                        letterSpacing = 1.5.sp
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = if (transcript.isNotBlank()) "\"$transcript\"" else "Listening for speech...",
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (transcript.isNotBlank()) Color.White else Color.White.copy(alpha = 0.5f),
                                        lineHeight = 26.sp
                                    )
                                }
                            }

                            // Assistant Reply Area
                            if (assistantReply.isNotBlank()) {
                                Spacer(Modifier.height(14.dp))
                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 4.dp),
                                    color = Color.White.copy(alpha = 0.06f)
                                )
                                Spacer(Modifier.height(8.dp))
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .semantics(mergeDescendants = true) {
                                            contentDescription = "Assistant: $assistantReply"
                                        }
                                ) {
                                    Text(
                                        text = "ASSISTANT:",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Black,
                                        color = speakingGreen,
                                        letterSpacing = 1.5.sp
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = assistantReply,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Normal,
                                        color = Color.White.copy(alpha = 0.95f),
                                        lineHeight = 24.sp
                                    )
                                }
                            }

                            // Idle State or Status / Error Display
                            if (transcript.isBlank() && !isListening && assistantReply.isBlank()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp)
                                        .semantics { contentDescription = "Status: $status" },
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    Text(
                                        text = status,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = when {
                                            status.contains("not", ignoreCase = true) ||
                                            status.contains("No speech", ignoreCase = true) ||
                                            status.contains("error", ignoreCase = true) ||
                                            status.contains("required", ignoreCase = true) -> listeningRed
                                            else -> Color.White.copy(alpha = 0.6f)
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // Input Box
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = manualInputText,
                            onValueChange = { manualInputText = it },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { if (manualInputText.isNotBlank()) { voiceService.processVoiceCommand(manualInputText.trim()); manualInputText = "" } })
                        )
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (manualInputText.isNotBlank()) currentThemeColor else Color.White.copy(alpha = 0.1f))
                                .clickable(enabled = manualInputText.isNotBlank()) {
                                    val textToSend = manualInputText.trim()
                                    manualInputText = ""
                                    focusManager.clearFocus()
                                    voiceService.processVoiceCommand(textToSend)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            SendIcon(tint = if (manualInputText.isNotBlank()) Color.Black else Color.White.copy(alpha = 0.4f), modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CustomMicIcon(tint: Color, isListening: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        drawRoundRect(tint, Offset((w - w * 0.36f) / 2f, h * 0.12f), Size(w * 0.36f, h * 0.52f), CornerRadius(w * 0.18f))
        drawPath(Path().apply { moveTo(w * 0.35f, h * 0.4f); cubicTo(w * 0.35f, h * 0.9f, w * 0.65f, h * 0.9f, w * 0.65f, h * 0.4f) }, tint, style = Stroke(w * 0.08f, cap = StrokeCap.Round))
        drawLine(tint, Offset(w / 2f, h * 0.7f), Offset(w / 2f, h * 0.88f), w * 0.08f, StrokeCap.Round)
    }
}

@Composable
fun VoiceWaveVisualizer(isActive: Boolean, color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "wave")
    val phase by transition.animateFloat(0f, 6.28f, infiniteRepeatable(tween(1200, easing = LinearEasing)))
    Canvas(modifier = modifier) {
        val barCount = 13
        for (i in 0 until barCount) {
            val h = if (isActive) (sin(phase + i * 0.5f) + 1.2f) * size.height * 0.4f else size.height * 0.2f
            drawRoundRect(color.copy(alpha = 0.5f), Offset(i * (size.width / barCount), (size.height - h) / 2f), Size(size.width / barCount * 0.6f, h), CornerRadius(4.dp.toPx()))
        }
    }
}

@Composable
fun SendIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply { moveTo(0f, 0f); lineTo(w, h / 2f); lineTo(0f, h); lineTo(w * 0.2f, h / 2f); close() }
        drawPath(path = path, color = tint)
    }
}
