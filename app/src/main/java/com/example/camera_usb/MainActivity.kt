package com.example.camera_usb

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.StreamConfigurationMap
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings
import android.util.Size as AndroidSize
import android.view.Surface
import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.camera_usb.ui.theme.Camera_usbTheme
import com.herohan.uvcapp.CameraException
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.serenegiant.usb.Size
import com.serenegiant.widget.AspectRatioTextureView
import kotlinx.coroutines.delay
import kotlin.math.abs

private const val LOGITECH_VENDOR_ID = 0x046D
private const val USB_CAMERA_ID = "usb_uvc"
const val ACTION_OPEN_USB_CAMERA = "com.example.camera_usb.action.OPEN_USB_CAMERA"
private enum class PreviewScaleMode { FIT, FILL }

class MainActivity : ComponentActivity() {
    private var autoOpenUsb by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        autoOpenUsb = intent?.action == ACTION_OPEN_USB_CAMERA || intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED
        enableEdgeToEdge()
        setContent {
            Camera_usbTheme {
                CameraApp(autoOpenUsbOnStart = autoOpenUsb)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        this.intent = intent
        if (intent.action == ACTION_OPEN_USB_CAMERA || intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            autoOpenUsb = true
        }
    }
}

@Composable
private fun CameraApp(
    autoOpenUsbOnStart: Boolean,
    viewModel: CameraViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted -> hasPermission = isGranted }
    )

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    if (!hasPermission) {
        PermissionScreen(
            onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            onOpenSettings = {
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    "package:${context.packageName}".toUri()
                )
                context.startActivity(intent)
            }
        )
        return
    }

    CameraScreen(
        uiState = uiState,
        autoOpenUsbOnStart = autoOpenUsbOnStart,
        onSelectLensFacing = viewModel::selectByLensFacing,
        onCamerasLoaded = viewModel::onCamerasLoaded,
        onBindingError = viewModel::onBindingError,
        onShowSuccess = viewModel::showSuccess,
        onShowError = viewModel::showError,
        onClearAlert = viewModel::clearAlert
    )
}

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
private fun CameraScreen(
    uiState: CameraUiState,
    autoOpenUsbOnStart: Boolean,
    onSelectLensFacing: (Int) -> Unit,
    onCamerasLoaded: (List<CameraDeviceUi>) -> Unit,
    onBindingError: (Throwable) -> Unit,
    onShowSuccess: (String) -> Unit,
    onShowError: (String) -> Unit,
    onClearAlert: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraManager = remember { context.getSystemService(Context.CAMERA_SERVICE) as CameraManager }
    val usbManager = remember { context.getSystemService(Context.USB_SERVICE) as UsbManager }

    var cameraDevice by remember { mutableStateOf<CameraDevice?>(null) }
    var captureSession by remember { mutableStateOf<CameraCaptureSession?>(null) }
    var internalPreviewSurface by remember { mutableStateOf<Surface?>(null) }
    var surfaceReadyTick by remember { mutableIntStateOf(0) }
    var preferredUsbDevice by remember { mutableStateOf<UsbDevice?>(null) }
    var usbPermissionTarget by remember { mutableStateOf<UsbDevice?>(null) }
    var showUsbPermissionDialog by remember { mutableStateOf(false) }
    var usbSurfaceBound by remember { mutableStateOf(false) }
    var usbOpeningDeviceId by remember { mutableStateOf<Int?>(null) }
    var usbOpenedDeviceId by remember { mutableStateOf<Int?>(null) }
    var usbAutoConfiguredDeviceId by remember { mutableStateOf<Int?>(null) }
    var usbPreferredSize by remember { mutableStateOf<Size?>(null) }
    val internalPreferredSize = remember { mutableStateMapOf<String, AndroidSize>() }
    val previewScaleModeByCamera = remember { mutableStateMapOf<String, PreviewScaleMode>() }
    var showCameraSettingsDialog by remember { mutableStateOf(false) }
    var settingsCameraId by remember { mutableStateOf<String?>(null) }
    var settingsIsUsb by remember { mutableStateOf(false) }
    var isSettingsLoading by remember { mutableStateOf(false) }
    var usbSizeOptions by remember { mutableStateOf<List<Size>>(emptyList()) }
    var internalSizeOptions by remember { mutableStateOf<List<AndroidSize>>(emptyList()) }
    var previewScaleMode by remember { mutableStateOf(PreviewScaleMode.FILL) }
    var currentPreviewAspect by remember { mutableStateOf(16f / 9f) }
    var pendingAutoUsbOpen by remember { mutableStateOf(autoOpenUsbOnStart) }
    val selectedCameraIdLatest by rememberUpdatedState(uiState.selectedCameraId)

    LaunchedEffect(uiState.alert?.timestamp) {
        if (uiState.alert != null) {
            delay(4000)
            onClearAlert()
        }
    }

    val cameraThread = remember {
        HandlerThread("camera-thread").apply { start() }
    }
    val cameraHandler = remember { Handler(cameraThread.looper) }

    val previewView = remember {
        AspectRatioTextureView(context).apply {
            setAspectRatio(9, 16)
        }
    }

    val uvcHelper = remember {
        CameraHelper()
    }

    val closeInternalCamera: () -> Unit = {
        runCatching { captureSession?.stopRepeating() }
        runCatching { captureSession?.close() }
        runCatching { cameraDevice?.close() }
        runCatching { internalPreviewSurface?.release() }
        captureSession = null
        cameraDevice = null
        internalPreviewSurface = null
    }

    val closeUsbCamera: () -> Unit = {
        runCatching { uvcHelper.closeCamera() }
        val surfaceTexture = previewView.surfaceTexture
        if (surfaceTexture != null) {
            runCatching { uvcHelper.removeSurface(surfaceTexture) }
        }
        usbSurfaceBound = false
        usbOpeningDeviceId = null
        usbOpenedDeviceId = null
        usbAutoConfiguredDeviceId = null
    }

    fun internalSupportedSizes(cameraId: String): List<AndroidSize> {
        val chars = runCatching { cameraManager.getCameraCharacteristics(cameraId) }.getOrNull() ?: return emptyList()
        val map: StreamConfigurationMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return emptyList()
        val raw = map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        return raw
            .sortedByDescending { it.width * it.height }
            .distinctBy { "${it.width}x${it.height}" }
    }

    val refreshSources: () -> Unit = {
        val internalList = runCatching {
            cameraManager.cameraIdList.mapNotNull { cameraId ->
                val chars = cameraManager.getCameraCharacteristics(cameraId)
                val lensFacing = chars.get(CameraCharacteristics.LENS_FACING)
                val (label, order) = when (lensFacing) {
                    CameraCharacteristics.LENS_FACING_BACK -> "Back" to 0
                    CameraCharacteristics.LENS_FACING_FRONT -> "Front" to 1
                    else -> return@mapNotNull null
                }
                CameraDeviceUi(
                    id = cameraId,
                    label = label,
                    sortOrder = order,
                    lensFacing = lensFacing
                )
            }
        }.getOrElse {
            onBindingError(it)
            emptyList()
        }

        val usbDevices = usbManager.deviceList.values.filter { it.isVideoClassDevice() }
        val externalEntry = if (usbDevices.isNotEmpty()) {
            listOf(
                CameraDeviceUi(
                    id = USB_CAMERA_ID,
                    label = "USB / External",
                    sortOrder = 2,
                    lensFacing = CameraCharacteristics.LENS_FACING_EXTERNAL
                )
            )
        } else {
            emptyList()
        }

        if (preferredUsbDevice == null && usbDevices.isNotEmpty()) {
            preferredUsbDevice = usbDevices.firstOrNull { it.vendorId == LOGITECH_VENDOR_ID } ?: usbDevices.firstOrNull()
        }
        if (preferredUsbDevice != null && usbDevices.none { it.deviceId == preferredUsbDevice?.deviceId }) {
            preferredUsbDevice = null
        }

        onCamerasLoaded(internalList + externalEntry)
    }

    fun requestUsbCameraAccess(device: UsbDevice) {
        closeInternalCamera()
        preferredUsbDevice = device
        if (uvcHelper.isCameraOpened() && usbOpenedDeviceId == device.deviceId) {
            surfaceReadyTick++
            return
        }
        if (usbOpeningDeviceId == device.deviceId) return
        usbOpeningDeviceId = device.deviceId
        uvcHelper.selectDevice(device)
    }

    fun attachUsbPreviewSurfaceIfReady() {
        val isUsbSelected = selectedCameraIdLatest == USB_CAMERA_ID
        val surfaceTexture = previewView.surfaceTexture
        if (!isUsbSelected || !uvcHelper.isCameraOpened() || surfaceTexture == null || !previewView.isAvailable) return
        if (usbSurfaceBound) return
        runCatching {
            uvcHelper.addSurface(surfaceTexture, false)
            usbSurfaceBound = true
        }
                .onFailure(onBindingError)
    }

    fun requestUsbTapFocus(): Boolean {
        val methods = listOf("startFocus", "startCameraFocus", "requestFocus", "autoFocus")
        methods.forEach { name ->
            runCatching {
                val noArgMethod = uvcHelper.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
                if (noArgMethod != null) {
                    noArgMethod.invoke(uvcHelper)
                    return true
                }
                val boolMethod = uvcHelper.javaClass.methods.firstOrNull {
                    it.name == name && it.parameterCount == 1 && it.parameterTypes.firstOrNull() == Boolean::class.javaPrimitiveType
                }
                if (boolMethod != null) {
                    boolMethod.invoke(uvcHelper, true)
                    return true
                }
            }
        }

        runCatching {
            val autoFocusMethod = uvcHelper.javaClass.methods.firstOrNull {
                it.name == "setAutoFocus" && it.parameterCount == 1 && it.parameterTypes.firstOrNull() == Boolean::class.javaPrimitiveType
            }
            if (autoFocusMethod != null) {
                autoFocusMethod.invoke(uvcHelper, true)
                return true
            }
        }

        return false
    }

    fun requestTapFocus(tapX: Float, tapY: Float) {
        val selected = uiState.cameras.firstOrNull { it.id == uiState.selectedCameraId } ?: return

        if (selected.lensFacing == CameraCharacteristics.LENS_FACING_EXTERNAL) {
            requestUsbTapFocus()
            return
        }

        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val surface = internalPreviewSurface ?: return

        val chars = runCatching { cameraManager.getCameraCharacteristics(selected.id) }.getOrNull()
        val activeRect = chars?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val maxAfRegions = chars?.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0

        val focusBuilder = runCatching {
            device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                if (activeRect != null && maxAfRegions > 0 && previewView.width > 0 && previewView.height > 0) {
                    val meteringRect = buildMeteringRect(
                        tapX = tapX,
                        tapY = tapY,
                        viewWidth = previewView.width,
                        viewHeight = previewView.height,
                        sensorRect = activeRect
                    )
                    set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(meteringRect))
                    set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(meteringRect))
                }
                set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            }
        }.getOrNull() ?: return

        runCatching {
            session.capture(focusBuilder.build(), null, cameraHandler)

            focusBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
            focusBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            session.setRepeatingRequest(focusBuilder.build(), null, cameraHandler)
        }
    }

    DisposableEffect(previewView) {
        previewView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                previewView.setAspectRatio(width, height)
                surfaceReadyTick++
                applyPreviewTransform(previewView, currentPreviewAspect, previewScaleMode)
                attachUsbPreviewSurfaceIfReady()
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                previewView.setAspectRatio(width, height)
                surfaceReadyTick++
                applyPreviewTransform(previewView, currentPreviewAspect, previewScaleMode)
                attachUsbPreviewSurfaceIfReady()
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                closeInternalCamera()
                runCatching { uvcHelper.removeSurface(surface) }
                usbSurfaceBound = false
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
        onDispose {
            previewView.surfaceTextureListener = null
        }
    }

    LaunchedEffect(previewScaleMode, currentPreviewAspect, surfaceReadyTick) {
        applyPreviewTransform(previewView, currentPreviewAspect, previewScaleMode)
    }

    DisposableEffect(Unit) {
        val callback = object : ICameraHelper.StateCallback {
            override fun onAttach(device: UsbDevice) {
                refreshSources()
                if (selectedCameraIdLatest == USB_CAMERA_ID && (preferredUsbDevice == null || preferredUsbDevice?.deviceId == device.deviceId)) {
                    preferredUsbDevice = device
                    requestUsbCameraAccess(device)
                }
            }

            override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
                usbSizeOptions = uvcHelper.getSupportedSizeList().orEmpty()
                uvcHelper.openCamera()
            }

            override fun onCameraOpen(device: UsbDevice) {
                usbOpeningDeviceId = null
                usbOpenedDeviceId = device.deviceId
                usbSurfaceBound = false
                val autoSize = usbPreferredSize ?: findBestUsbSize(uvcHelper.getSupportedSizeList())
                if (usbAutoConfiguredDeviceId != device.deviceId && autoSize != null) {
                    runCatching { uvcHelper.setPreviewSize(autoSize) }
                    usbPreferredSize = autoSize
                    usbAutoConfiguredDeviceId = device.deviceId
                }
                uvcHelper.startPreview()
                val size = uvcHelper.getPreviewSize()
                if (size != null) {
                    currentPreviewAspect = size.width / size.height.toFloat()
                }
                attachUsbPreviewSurfaceIfReady()
                onShowSuccess("تم توصيل ${device.readableName()} بنجاح")
            }

            override fun onCameraClose(device: UsbDevice) {
                previewView.surfaceTexture?.let { st ->
                    runCatching { uvcHelper.removeSurface(st) }
                }
                usbSurfaceBound = false
                if (usbOpenedDeviceId == device.deviceId) {
                    usbOpenedDeviceId = null
                }
                if (usbOpeningDeviceId == device.deviceId) {
                    usbOpeningDeviceId = null
                }
                if (usbAutoConfiguredDeviceId == device.deviceId) {
                    usbAutoConfiguredDeviceId = null
                }
            }

            override fun onDeviceClose(device: UsbDevice) = Unit

            override fun onDetach(device: UsbDevice) {
                refreshSources()
                if (preferredUsbDevice?.deviceId == device.deviceId) {
                    preferredUsbDevice = null
                    usbOpenedDeviceId = null
                    usbOpeningDeviceId = null
                    onShowError("تم فصل كاميرا USB")
                }
            }

            override fun onCancel(device: UsbDevice) {
                usbOpeningDeviceId = null
                onShowError("تم إلغاء إذن كاميرا USB")
            }

            override fun onError(device: UsbDevice, e: CameraException) {
                usbOpeningDeviceId = null
                onShowError("خطأ في كاميرا USB (${e.code})، أعد التوصيل ثم حاول مرة أخرى")
            }
        }

        uvcHelper.setStateCallback(callback)
        onDispose {
            uvcHelper.setStateCallback(null)
            uvcHelper.release()
        }
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                when (intent?.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED,
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        refreshSources()
                        surfaceReadyTick++
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }

        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    refreshSources()
                    surfaceReadyTick++
                }

                Lifecycle.Event.ON_PAUSE -> {
                    closeInternalCamera()
                    closeUsbCamera()
                }

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            closeInternalCamera()
            closeUsbCamera()
            cameraThread.quitSafely()
        }
    }

    LaunchedEffect(Unit) {
        refreshSources()
    }

    LaunchedEffect(uiState.cameras, pendingAutoUsbOpen) {
        if (!pendingAutoUsbOpen) return@LaunchedEffect
        val hasUsb = uiState.cameras.any { it.lensFacing == CameraCharacteristics.LENS_FACING_EXTERNAL }
        if (hasUsb) {
            onSelectLensFacing(CameraCharacteristics.LENS_FACING_EXTERNAL)
        }
        pendingAutoUsbOpen = false
    }

    val selectedCameraId = uiState.selectedCameraId

    LaunchedEffect(selectedCameraId, uiState.cameras) {
        val selected = uiState.cameras.firstOrNull { it.id == selectedCameraId } ?: return@LaunchedEffect
        previewScaleMode = previewScaleModeByCamera[selected.id] ?: PreviewScaleMode.FILL
    }

    LaunchedEffect(showCameraSettingsDialog, settingsCameraId, settingsIsUsb) {
        if (!showCameraSettingsDialog) return@LaunchedEffect
        val cameraId = settingsCameraId ?: return@LaunchedEffect
        isSettingsLoading = true
        if (settingsIsUsb) {
            usbSizeOptions = uvcHelper.getSupportedSizeList().orEmpty()
        } else {
            internalSizeOptions = internalSupportedSizes(cameraId)
        }
        isSettingsLoading = false
    }

    LaunchedEffect(selectedCameraId, surfaceReadyTick, uiState.cameras) {
        val selected = uiState.cameras.firstOrNull { it.id == selectedCameraId }

        if (selected == null) {
            return@LaunchedEffect
        }

        if (selected.lensFacing == CameraCharacteristics.LENS_FACING_EXTERNAL) {
            val targetUsb = preferredUsbDevice
                ?: usbManager.deviceList.values
                    .filter { it.isVideoClassDevice() }
                    .firstOrNull { it.vendorId == LOGITECH_VENDOR_ID }
                ?: usbManager.deviceList.values.firstOrNull { it.isVideoClassDevice() }

            if (targetUsb != null) {
                if (!usbManager.hasPermission(targetUsb)) {
                    usbPermissionTarget = targetUsb
                    showUsbPermissionDialog = true
                } else {
                    requestUsbCameraAccess(targetUsb)
                }
            } else {
                onShowError("لم يتم العثور على كاميرا USB. قم بتوصيل الكاميرا ثم حاول مجددًا")
            }
            return@LaunchedEffect
        }

        closeUsbCamera()

        val cameraId = selected.id
        val surfaceTexture = previewView.surfaceTexture
        if (surfaceTexture == null || !previewView.isAvailable) {
            return@LaunchedEffect
        }

        val preferred = internalPreferredSize[cameraId]
            ?: findBestInternalSize(internalSupportedSizes(cameraId), 1280, 720)
        val previewWidth = preferred?.width ?: if (previewView.width > 0) previewView.width else 1280
        val previewHeight = preferred?.height ?: if (previewView.height > 0) previewView.height else 720
        currentPreviewAspect = previewWidth / previewHeight.toFloat()
        surfaceTexture.setDefaultBufferSize(previewWidth, previewHeight)

        closeInternalCamera()

        val previewSurface = Surface(surfaceTexture)
        internalPreviewSurface = previewSurface

        openCamera(
            context = context,
            cameraManager = cameraManager,
            cameraId = cameraId,
            handler = cameraHandler,
            onError = onBindingError,
            onOpened = { device ->
                cameraDevice = device
                createPreviewSession(
                    device = device,
                    surface = previewSurface,
                    handler = cameraHandler,
                    onConfigured = { session ->
                        captureSession = session
                    },
                    onError = onBindingError
                )
            }
        )
    }

    val hasBack = uiState.cameras.any { it.lensFacing == CameraCharacteristics.LENS_FACING_BACK }
    val hasFront = uiState.cameras.any { it.lensFacing == CameraCharacteristics.LENS_FACING_FRONT }
    val hasUsb = uiState.cameras.any { it.lensFacing == CameraCharacteristics.LENS_FACING_EXTERNAL }

    val isBackSelected = uiState.cameras.any {
        it.id == uiState.selectedCameraId && it.lensFacing == CameraCharacteristics.LENS_FACING_BACK
    }
    val isFrontSelected = uiState.cameras.any {
        it.id == uiState.selectedCameraId && it.lensFacing == CameraCharacteristics.LENS_FACING_FRONT
    }
    val isUsbSelected = uiState.cameras.any {
        it.id == uiState.selectedCameraId && it.lensFacing == CameraCharacteristics.LENS_FACING_EXTERNAL
    }
    val isCompactHeight = LocalConfiguration.current.screenHeightDp < 700

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Black
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(uiState.selectedCameraId, cameraDevice, captureSession, usbOpenedDeviceId) {
                    detectTapGestures { offset ->
                        requestTapFocus(offset.x, offset.y)
                    }
                }
        ) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize()
            )

            if (uiState.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White
                )
            }

            if (uiState.cameras.isEmpty() && !uiState.isLoading) {
                Text(
                    text = "لم يتم العثور على أي كاميرا",
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 14.dp, vertical = if (isCompactHeight) 8.dp else 12.dp)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 10.dp, vertical = if (isCompactHeight) 8.dp else 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QuickSwitchButton(
                        modifier = Modifier.weight(1f),
                        label = "خلفية",
                        active = isBackSelected,
                        enabled = hasBack,
                        onClick = { onSelectLensFacing(CameraCharacteristics.LENS_FACING_BACK) }
                    )
                    QuickSwitchButton(
                        modifier = Modifier.weight(1f),
                        label = "أمامية",
                        active = isFrontSelected,
                        enabled = hasFront,
                        onClick = { onSelectLensFacing(CameraCharacteristics.LENS_FACING_FRONT) }
                    )
                    QuickSwitchButton(
                        modifier = Modifier.weight(1f),
                        label = "USB",
                        active = isUsbSelected,
                        enabled = hasUsb,
                        onClick = {
                            val targetUsb = usbManager.deviceList.values
                                .filter { it.isVideoClassDevice() }
                                .firstOrNull { it.vendorId == LOGITECH_VENDOR_ID }
                                ?: usbManager.deviceList.values.firstOrNull { it.isVideoClassDevice() }

                            if (targetUsb == null) {
                                onShowError("لا توجد كاميرا USB متصلة حاليًا")
                                return@QuickSwitchButton
                            }

                            preferredUsbDevice = targetUsb
                            onSelectLensFacing(CameraCharacteristics.LENS_FACING_EXTERNAL)

                            if (!usbManager.hasPermission(targetUsb)) {
                                usbPermissionTarget = targetUsb
                                showUsbPermissionDialog = true
                            } else {
                                requestUsbCameraAccess(targetUsb)
                            }
                        }
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            val selected = uiState.cameras.firstOrNull { it.id == uiState.selectedCameraId }
                                ?: return@Button
                            val nextMode = if (previewScaleMode == PreviewScaleMode.FIT) {
                                PreviewScaleMode.FILL
                            } else {
                                PreviewScaleMode.FIT
                            }
                            previewScaleMode = nextMode
                            previewScaleModeByCamera[selected.id] = nextMode
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF304B6D),
                            contentColor = Color.White
                        )
                    ) {
                        val modeText = if (previewScaleMode == PreviewScaleMode.FIT) "ملاءمة" else "ملء"
                        Text("حجم العرض: $modeText")
                    }

                    Button(
                        onClick = {
                            val selected = uiState.cameras.firstOrNull { it.id == uiState.selectedCameraId }
                            if (selected == null) return@Button
                            settingsCameraId = selected.id
                            settingsIsUsb = selected.lensFacing == CameraCharacteristics.LENS_FACING_EXTERNAL
                            isSettingsLoading = true
                            showCameraSettingsDialog = true
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF223A5A),
                            contentColor = Color.White
                        )
                    ) {
                        Text("الإعدادات")
                    }
                }
            }

            val pendingDevice = usbPermissionTarget
            if (showUsbPermissionDialog && pendingDevice != null) {
                UsbPermissionDialog(
                    deviceName = pendingDevice.readableName(),
                    onAllow = {
                        showUsbPermissionDialog = false
                        usbPermissionTarget = null
                        requestUsbCameraAccess(pendingDevice)
                    },
                    onCancel = {
                        showUsbPermissionDialog = false
                        usbPermissionTarget = null
                    }
                )
            }

            if (showCameraSettingsDialog) {
                val selected = settingsCameraId?.let { targetId ->
                    uiState.cameras.firstOrNull { it.id == targetId }
                }
                if (selected != null) {
                    CameraSettingsDialog(
                        isUsb = settingsIsUsb,
                        isLoading = isSettingsLoading,
                        usbOptions = usbSizeOptions,
                        internalOptions = internalSizeOptions,
                        selectedUsb = usbPreferredSize,
                        selectedInternal = internalPreferredSize[selected.id],
                        onSelectUsb = { size ->
                            usbPreferredSize = size
                            if (uvcHelper.isCameraOpened()) {
                                runCatching { uvcHelper.stopPreview() }
                                runCatching { uvcHelper.setPreviewSize(size) }
                                usbSurfaceBound = false
                                runCatching { uvcHelper.startPreview() }
                                currentPreviewAspect = size.width / size.height.toFloat()
                                attachUsbPreviewSurfaceIfReady()
                            } else {
                                val targetUsb = preferredUsbDevice
                                if (targetUsb != null) requestUsbCameraAccess(targetUsb)
                            }
                            showCameraSettingsDialog = false
                        },
                        onSelectInternal = { size ->
                            internalPreferredSize[selected.id] = size
                            currentPreviewAspect = size.width / size.height.toFloat()
                            surfaceReadyTick++
                            showCameraSettingsDialog = false
                        },
                        onDismiss = {
                            isSettingsLoading = false
                            showCameraSettingsDialog = false
                        }
                    )
                }
            }

            uiState.alert?.let { alert ->
                BottomAlertBar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 90.dp),
                    alert = alert,
                    onClose = onClearAlert
                )
            }
        }
    }
}

@SuppressLint("MissingPermission")
private fun openCamera(
    context: Context,
    cameraManager: CameraManager,
    cameraId: String,
    handler: Handler,
    onError: (Throwable) -> Unit,
    onOpened: (CameraDevice) -> Unit
) {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
        onError(IllegalStateException("Camera permission not granted"))
        return
    }

    runCatching {
        cameraManager.openCamera(
            cameraId,
            object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) = onOpened(camera)

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    onError(IllegalStateException("Camera disconnected"))
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    onError(IllegalStateException("Camera open error code: $error"))
                }
            },
            handler
        )
    }.onFailure(onError)
}

private fun createPreviewSession(
    device: CameraDevice,
    surface: Surface,
    handler: Handler,
    onConfigured: (CameraCaptureSession) -> Unit,
    onError: (Throwable) -> Unit
) {
    runCatching {
        val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surface)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }

        device.createCaptureSession(
            listOf(surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    onConfigured(session)
                    runCatching {
                        session.setRepeatingRequest(requestBuilder.build(), null, handler)
                    }.onFailure(onError)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    onError(IllegalStateException("Failed to configure camera preview session"))
                }
            },
            handler
        )
    }.onFailure(onError)
}

private fun findBestUsbSize(sizes: List<Size>?): Size? {
    if (sizes.isNullOrEmpty()) return null
    val targetWidth = 1280
    val targetHeight = 720

    return sizes.minByOrNull { size ->
        val w = size.width
        val h = size.height
        val direct = abs(w - targetWidth) + abs(h - targetHeight)
        val swapped = abs(h - targetWidth) + abs(w - targetHeight)
        minOf(direct, swapped)
    }
}

private fun findBestInternalSize(
    sizes: List<AndroidSize>,
    targetWidth: Int,
    targetHeight: Int
): AndroidSize? {
    if (sizes.isEmpty()) return null
    return sizes.minByOrNull { size ->
        val direct = abs(size.width - targetWidth) + abs(size.height - targetHeight)
        val swapped = abs(size.height - targetWidth) + abs(size.width - targetHeight)
        minOf(direct, swapped)
    }
}

private fun buildMeteringRect(
    tapX: Float,
    tapY: Float,
    viewWidth: Int,
    viewHeight: Int,
    sensorRect: Rect
): MeteringRectangle {
    val x = (tapX / viewWidth.toFloat()).coerceIn(0f, 1f)
    val y = (tapY / viewHeight.toFloat()).coerceIn(0f, 1f)

    val sensorX = (sensorRect.left + x * sensorRect.width()).toInt()
    val sensorY = (sensorRect.top + y * sensorRect.height()).toInt()
    val boxWidth = (sensorRect.width() * 0.08f).toInt().coerceAtLeast(120)
    val boxHeight = (sensorRect.height() * 0.08f).toInt().coerceAtLeast(120)

    val left = (sensorX - boxWidth / 2).coerceIn(sensorRect.left, sensorRect.right - boxWidth)
    val top = (sensorY - boxHeight / 2).coerceIn(sensorRect.top, sensorRect.bottom - boxHeight)

    return MeteringRectangle(left, top, boxWidth, boxHeight, MeteringRectangle.METERING_WEIGHT_MAX)
}

private fun applyPreviewTransform(
    view: TextureView,
    contentAspect: Float,
    mode: PreviewScaleMode
) {
    val width = view.width.toFloat()
    val height = view.height.toFloat()
    if (width <= 0f || height <= 0f || contentAspect <= 0f) return

    val viewAspect = width / height
    val matrix = Matrix()
    if (mode == PreviewScaleMode.FILL) {
        if (contentAspect > viewAspect) {
            val scaleX = contentAspect / viewAspect
            matrix.setScale(scaleX, 1f, width / 2f, height / 2f)
        } else {
            val scaleY = viewAspect / contentAspect
            matrix.setScale(1f, scaleY, width / 2f, height / 2f)
        }
    }
    view.setTransform(matrix)
}

@Composable
private fun PermissionScreen(
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(Color(0xFF0D1B2A), Color(0xFF1B263B), Color(0xFF415A77))
                )
            )
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Camera permission is required",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
            Button(onClick = onRequestPermission, shape = RoundedCornerShape(16.dp)) {
                Text("Grant Permission")
            }
            Button(
                onClick = onOpenSettings,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White.copy(alpha = 0.18f),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Open Settings")
            }
        }
    }
}

@Composable
private fun QuickSwitchButton(
    modifier: Modifier,
    label: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Button(
        modifier = modifier,
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (active) Color(0xFF00A889) else Color(0xFF2A3D59),
            contentColor = Color.White,
            disabledContainerColor = Color(0xFF2A3D59).copy(alpha = 0.4f),
            disabledContentColor = Color.White.copy(alpha = 0.6f)
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Text(label)
    }
}

@Composable
private fun UsbPermissionDialog(
    deviceName: String,
    onAllow: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Color(0xFF0F2238),
        tonalElevation = 8.dp,
        shape = RoundedCornerShape(22.dp),
        title = {
            Text(
                text = "طلب إذن كاميرا USB",
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text(
                text = "سيتم الآن طلب إذن الوصول لكاميرا USB التالية:\n\"$deviceName\"\nاضغط \"سماح\" للمتابعة.",
                color = Color(0xFFD7E7FF)
            )
        },
        confirmButton = {
            Button(
                onClick = onAllow,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00A889),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("سماح")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("لاحقًا", color = Color(0xFF9DB8D8))
            }
        }
    )
}

@Composable
private fun BottomAlertBar(
    modifier: Modifier,
    alert: CameraAlert,
    onClose: () -> Unit
) {
    val backgroundColor = if (alert.type == AlertType.SUCCESS) {
        Color(0xFF128A49)
    } else {
        Color(0xFFB4232C)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(backgroundColor, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = alert.message,
            modifier = Modifier.weight(1f),
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
        TextButton(onClick = onClose) {
            Text("إخفاء", color = Color.White)
        }
    }
}

private fun UsbDevice.isVideoClassDevice(): Boolean {
    if (deviceClass == UsbConstants.USB_CLASS_VIDEO) return true
    for (i in 0 until interfaceCount) {
        if (getInterface(i).interfaceClass == UsbConstants.USB_CLASS_VIDEO) return true
    }
    return false
}

private fun UsbDevice.readableName(): String {
    val name = productName?.takeIf { it.isNotBlank() }
    if (name != null) return name
    val maker = manufacturerName?.takeIf { it.isNotBlank() }
    if (maker != null) return "$maker USB Camera"
    if (vendorId == LOGITECH_VENDOR_ID) return "Logitech USB Camera"
    return "USB Camera (VID:PID ${vendorId.toString(16)}:${productId.toString(16)})"
}

@Composable
private fun CameraSettingsDialog(
    isUsb: Boolean,
    isLoading: Boolean,
    usbOptions: List<Size>,
    internalOptions: List<AndroidSize>,
    selectedUsb: Size?,
    selectedInternal: AndroidSize?,
    onSelectUsb: (Size) -> Unit,
    onSelectInternal: (AndroidSize) -> Unit,
    onDismiss: () -> Unit
) {
    val usbList = usbOptions
        .sortedByDescending { it.width * it.height }
        .distinctBy { "${it.width}x${it.height}" }
        .take(10)
    val internalList = internalOptions
        .sortedByDescending { it.width * it.height }
        .distinctBy { "${it.width}x${it.height}" }
        .take(10)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF10263F),
        shape = RoundedCornerShape(22.dp),
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "إعدادات الدقة",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (isUsb) "كاميرا USB" else "كاميرا الهاتف",
                    color = Color(0xFFBFD4EF),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (isLoading) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(color = Color.White)
                    }
                } else if (isUsb) {
                    if (usbList.isEmpty()) {
                        Text("لا توجد دقات متاحة لكاميرا USB", color = Color(0xFFD7E7FF))
                    }
                    usbList.forEach { s ->
                        val isSelected = selectedUsb?.width == s.width && selectedUsb.height == s.height
                        ResolutionCard(
                            label = "${s.width} x ${s.height}",
                            subtitle = if (isSelected) "الحالي" else "اضغط للتطبيق",
                            selected = isSelected,
                            onClick = { onSelectUsb(s) }
                        )
                    }
                } else {
                    if (internalList.isEmpty()) {
                        Text("لا توجد دقات متاحة لهذه الكاميرا", color = Color(0xFFD7E7FF))
                    }
                    internalList.forEach { s ->
                        val isSelected = selectedInternal?.width == s.width && selectedInternal.height == s.height
                        ResolutionCard(
                            label = "${s.width} x ${s.height}",
                            subtitle = if (isSelected) "الحالي" else "اضغط للتطبيق",
                            selected = isSelected,
                            onClick = { onSelectInternal(s) }
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إغلاق", color = Color(0xFF9DB8D8))
            }
        }
    )
}

@Composable
private fun ResolutionCard(
    label: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = if (selected) Color(0xFF00A889) else Color(0xFF1E3B5E),
        shape = RoundedCornerShape(14.dp),
        tonalElevation = if (selected) 8.dp else 2.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = label,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subtitle,
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
