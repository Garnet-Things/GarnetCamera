package com.garnet.camera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.garnet.camera.CameraController.CameraState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


class MainActivity : ComponentActivity() {

    private lateinit var cameraController: CameraController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraController = CameraController(this)

        setContent {
            var hasCameraPermission by remember {
                mutableStateOf(
                    checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                )
            }

            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { isGranted ->
                hasCameraPermission = isGranted
            }

            LaunchedEffect(key1 = hasCameraPermission) {
                if (!hasCameraPermission) {
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                }
            }

            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color.White,
                    background = Color.Black,
                    surface = Color(0xFF1C1C1E)
                )
            ) {
                if (hasCameraPermission) {
                    CameraScreen(cameraController = cameraController)
                } else {
                    PermissionDeniedScreen(onRequestPermission = {
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    })
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        cameraController.onResume()
    }

    override fun onPause() {
        cameraController.closeCamera()
        cameraController.stopBackgroundThread()
        super.onPause()
    }
}

@Composable
fun CameraScreen(cameraController: CameraController) {
    val currentCameraId by cameraController.currentCameraId.collectAsState()
    val isFrontCamera by cameraController.isFrontCamera.collectAsState()
    val cameraState by cameraController.cameraState.collectAsState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var isFlashActive by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Camera Preview
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                        }

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            cameraController.onSurfaceAvailable(holder.surface, width, height)
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            cameraController.onSurfaceDestroyed()
                        }
                    })
                }
            }
        )

        // Shutter Flash Animation Overlay
        val flashAlpha by animateFloatAsState(
            targetValue = if (isFlashActive) 1f else 0f,
            animationSpec = tween(durationMillis = if (isFlashActive) 50 else 150),
            label = "flashAlpha"
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    if (flashAlpha > 0f) {
                        drawRect(Color.White, alpha = flashAlpha)
                    }
                }
        )

        // Top Glassmorphic Panel
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black.copy(alpha = 0.5f))
                    .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .align(Alignment.CenterStart),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = "Camera ID",
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = when {
                        cameraState is CameraState.Opening -> "Opening..."
                        cameraState is CameraState.Error -> "Error"
                        isFrontCamera -> "Front Camera"
                        currentCameraId == CameraController.BACK_MACRO_CAMERA -> "Macro Lens"
                        currentCameraId == CameraController.BACK_UW_CAMERA -> "Ultra-Wide Lens"
                        else -> "Wide Lens"
                    },
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Bottom Controls Container (Lens switcher + Shutter + Flip)
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .fillMaxWidth()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))
                    )
                )
                .padding(bottom = 36.dp, top = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Lens Switcher (Only visible for Back Cameras)
            AnimatedVisibility(
                visible = !isFrontCamera,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(32.dp))
                        .background(Color.Black.copy(alpha = 0.5f))
                        .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(32.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    LensSelectorItem(label = "Macro", cameraId = CameraController.BACK_MACRO_CAMERA, selectedCameraId = currentCameraId) {
                        cameraController.switchCamera(CameraController.BACK_MACRO_CAMERA)
                    }
                    LensSelectorItem(label = "1.0x", cameraId = CameraController.BACK_WIDE_CAMERA, selectedCameraId = currentCameraId) {
                        cameraController.switchCamera(CameraController.BACK_WIDE_CAMERA)
                    }
                    LensSelectorItem(label = "0.6x", cameraId = CameraController.BACK_UW_CAMERA, selectedCameraId = currentCameraId) {
                        cameraController.switchCamera(CameraController.BACK_UW_CAMERA)
                    }
                }
            }

            // Bottom Shutter Controls Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 48.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Placeholder Gallery Icon
                IconButton(
                    onClick = { /* Could open gallery */ },
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color.White.copy(alpha = 0.15f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = "Gallery",
                        tint = Color.White
                    )
                }

                // Shutter Button
                ShutterButton(onClick = {
                    if (cameraState is CameraState.Active) {
                        isFlashActive = true
                        cameraController.takePhoto { filename ->
                            Toast.makeText(context, "Saved: $filename", Toast.LENGTH_SHORT).show()
                        }
                        // Rapid reset of flash overlay
                        coroutineScope.launch {
                            delay(80)
                            isFlashActive = false
                        }
                    }
                })

                // Flip Camera Switcher
                IconButton(
                    onClick = { cameraController.toggleFrontBack() },
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color.White.copy(alpha = 0.15f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Cached,
                        contentDescription = "Flip Camera",
                        tint = Color.White
                    )
                }
            }
        }
    }
}

@Composable
fun ShutterButton(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    val innerScale by animateFloatAsState(
        targetValue = if (isPressed) 0.85f else 1.0f,
        animationSpec = tween(100),
        label = "innerScale"
    )

    Box(
        modifier = Modifier
            .size(76.dp)
            .border(4.dp, Color.White, CircleShape)
            .padding(6.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .scale(innerScale)
                .background(Color.White, CircleShape)
        )
    }
}

@Composable
fun LensSelectorItem(
    label: String,
    cameraId: String,
    selectedCameraId: String,
    onClick: () -> Unit
) {
    val isSelected = cameraId == selectedCameraId
    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) Color.White else Color.Transparent,
        animationSpec = tween(durationMillis = 200),
        label = "bgColor"
    )
    val textColor by animateColorAsState(
        targetValue = if (isSelected) Color.Black else Color.White.copy(alpha = 0.8f),
        animationSpec = tween(durationMillis = 200),
        label = "textColor"
    )
    val sizePadding by animateDpAsState(
        targetValue = if (isSelected) 8.dp else 5.dp,
        animationSpec = tween(durationMillis = 200),
        label = "padding"
    )

    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = sizePadding),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
fun PermissionDeniedScreen(onRequestPermission: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.CameraAlt,
                contentDescription = "Camera Permission Required",
                tint = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.size(64.dp)
            )
            Text(
                text = "Camera Permission Required",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Garnet Camera needs access to your camera to show preview.",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 14.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onRequestPermission,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(text = "Grant Permission", fontWeight = FontWeight.Bold)
            }
        }
    }
}
