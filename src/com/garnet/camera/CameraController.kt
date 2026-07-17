package com.garnet.camera

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CameraController(private val context: Context) {
    companion object {
        private const val TAG = "CameraController"
        const val BACK_WIDE_CAMERA = "0"
        const val BACK_UW_CAMERA = "2"
        const val BACK_MACRO_CAMERA = "3"
        const val FRONT_CAMERA = "1"
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var imageReader: ImageReader? = null
    
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private val _currentCameraId = MutableStateFlow(BACK_WIDE_CAMERA)
    val currentCameraId: StateFlow<String> = _currentCameraId.asStateFlow()

    private val _isFrontCamera = MutableStateFlow(false)
    val isFrontCamera: StateFlow<Boolean> = _isFrontCamera.asStateFlow()

    private val _cameraState = MutableStateFlow<CameraState>(CameraState.Closed)
    val cameraState: StateFlow<CameraState> = _cameraState.asStateFlow()

    private var activeSurfaceTexture: SurfaceTexture? = null
    private var lastBackCameraId = BACK_WIDE_CAMERA

    sealed interface CameraState {
        object Closed : CameraState
        object Opening : CameraState
        object Active : CameraState
        data class Error(val message: String) : CameraState
    }

    fun onResume() {
        startBackgroundThread()
        if (activeSurfaceTexture != null && _cameraState.value == CameraState.Closed) {
            openCamera()
        }
    }

    fun startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = HandlerThread("CameraBackground").apply {
                start()
                backgroundHandler = Handler(looper)
            }
            Log.d(TAG, "Background thread started")
        }
    }

    fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
            Log.d(TAG, "Background thread stopped")
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error stopping background thread", e)
        }
    }

    fun onSurfaceAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        activeSurfaceTexture = texture
        openCamera()
    }

    fun onSurfaceDestroyed() {
        activeSurfaceTexture = null
        closeCamera()
    }

    fun switchCamera(cameraId: String) {
        if (_currentCameraId.value == cameraId) return
        if (cameraId == FRONT_CAMERA) {
            _isFrontCamera.value = true
        } else {
            _isFrontCamera.value = false
            lastBackCameraId = cameraId
        }
        _currentCameraId.value = cameraId
        closeCamera()
        openCamera()
    }

    fun toggleFrontBack() {
        if (_isFrontCamera.value) {
            switchCamera(lastBackCameraId)
        } else {
            switchCamera(FRONT_CAMERA)
        }
    }

    private fun openCamera() {
        val texture = activeSurfaceTexture ?: return
        val cameraId = _currentCameraId.value

        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            _cameraState.value = CameraState.Error("Camera permission not granted")
            return
        }

        try {
            _cameraState.value = CameraState.Opening
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCameraPreview(camera, texture)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    closeCamera()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera device error: $error")
                    _cameraState.value = CameraState.Error("Camera error code: $error")
                    closeCamera()
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to open camera $cameraId", e)
            _cameraState.value = CameraState.Error("Access exception: ${e.message}")
        }
    }

    private fun createCameraPreview(camera: CameraDevice, texture: SurfaceTexture) {
        try {
            val cameraId = _currentCameraId.value
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            
            // Get largest supported JPEG size (MIUI Camera resolution)
            val largestSize = map?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width * it.height }
                ?: Size(1920, 1080)
            Log.d(TAG, "Configuring ImageReader with size: ${largestSize.width}x${largestSize.height}")

            imageReader = ImageReader.newInstance(largestSize.width, largestSize.height, ImageFormat.JPEG, 2)

            texture.setDefaultBufferSize(1920, 1080)
            val previewSurface = Surface(texture)
            val readerSurface = imageReader!!.surface

            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(previewSurface)
                // Disable ZSL as required by vendor configs
                set(CaptureRequest.CONTROL_ENABLE_ZSL, false)
            }
            previewRequestBuilder = builder

            camera.createCaptureSession(listOf(previewSurface, readerSurface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) return
                    captureSession = session
                    
                    builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                    try {
                        session.setRepeatingRequest(builder.build(), null, backgroundHandler)
                        _cameraState.value = CameraState.Active
                    } catch (e: CameraAccessException) {
                        Log.e(TAG, "Failed to start repeating request", e)
                        _cameraState.value = CameraState.Error("Failed repeating request")
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Session configuration failed")
                    _cameraState.value = CameraState.Error("Session configuration failed")
                }
            }, null)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to configure camera preview", e)
            _cameraState.value = CameraState.Error("Failed to configure preview: ${e.message}")
        }
    }

    fun closeCamera() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        _cameraState.value = CameraState.Closed
    }

    fun takePhoto(onPhotoSaved: (String) -> Unit) {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val reader = imageReader ?: return

        try {
            val captureBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_ENABLE_ZSL, false)
            }

            reader.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                image.close()

                CoroutineScope(Dispatchers.IO).launch {
                    val filename = "IMG_${System.currentTimeMillis()}.jpg"
                    val resolver = context.contentResolver
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/GarnetCamera")
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        }
                    }

                    val imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                    if (imageUri != null) {
                        try {
                            resolver.openOutputStream(imageUri).use { outputStream ->
                                if (outputStream != null) {
                                    outputStream.write(bytes)
                                }
                            }

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                contentValues.clear()
                                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                                resolver.update(imageUri, contentValues, null, null)
                            }

                            CoroutineScope(Dispatchers.Main).launch {
                                onPhotoSaved(filename)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to write photo bytes", e)
                        }
                    }
                }
            }, backgroundHandler)

            session.capture(captureBuilder.build(), null, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to capture still image", e)
        }
    }
}
