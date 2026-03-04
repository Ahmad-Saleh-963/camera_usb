package com.example.camera_usb

data class CameraDeviceUi(
    val id: String,
    val label: String,
    val sortOrder: Int,
    val lensFacing: Int?
)

enum class AlertType {
    SUCCESS,
    ERROR
}

data class CameraAlert(
    val message: String,
    val type: AlertType,
    val timestamp: Long = System.currentTimeMillis()
)

data class CameraUiState(
    val cameras: List<CameraDeviceUi> = emptyList(),
    val selectedCameraId: String? = null,
    val isLoading: Boolean = true,
    val alert: CameraAlert? = null
)
