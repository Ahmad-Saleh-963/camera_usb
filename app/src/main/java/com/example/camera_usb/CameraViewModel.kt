package com.example.camera_usb

import android.hardware.camera2.CameraCharacteristics
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class CameraViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    fun onCamerasLoaded(devices: List<CameraDeviceUi>) {
        val sorted = devices.sortedWith(compareBy<CameraDeviceUi> { it.sortOrder }.thenBy { it.id })
        val totalByLabel = sorted.groupingBy { it.label }.eachCount()
        val indexByLabel = mutableMapOf<String, Int>()
        val uniqueDevices = sorted.map { device ->
            if ((totalByLabel[device.label] ?: 0) <= 1) {
                device
            } else {
                val nextIndex = (indexByLabel[device.label] ?: 0) + 1
                indexByLabel[device.label] = nextIndex
                device.copy(label = "${device.label} $nextIndex")
            }
        }

        _uiState.update { current ->
            val preferredFallback = uniqueDevices.firstOrNull {
                it.lensFacing == CameraCharacteristics.LENS_FACING_BACK
            }?.id ?: uniqueDevices.firstOrNull {
                it.lensFacing == CameraCharacteristics.LENS_FACING_FRONT
            }?.id ?: uniqueDevices.firstOrNull {
                it.lensFacing == CameraCharacteristics.LENS_FACING_EXTERNAL
            }?.id

            current.copy(
                cameras = uniqueDevices,
                selectedCameraId = current.selectedCameraId.takeIf { selected ->
                    uniqueDevices.any { it.id == selected }
                } ?: preferredFallback,
                isLoading = false,
                alert = null
            )
        }
    }

    fun selectByLensFacing(lensFacing: Int) {
        val targetCameraId = _uiState.value.cameras.firstOrNull { it.lensFacing == lensFacing }?.id
        if (targetCameraId != null) {
            _uiState.update { it.copy(selectedCameraId = targetCameraId, alert = null) }
        } else {
            showError("الكاميرا المطلوبة غير متاحة")
        }
    }

    fun onBindingError(error: Throwable) {
        showError(error.message ?: "تعذر فتح الكاميرا المحددة")
    }

    fun clearAlert() {
        _uiState.update { it.copy(alert = null) }
    }

    fun showSuccess(message: String) {
        _uiState.update {
            it.copy(
                isLoading = false,
                alert = CameraAlert(message = message, type = AlertType.SUCCESS)
            )
        }
    }

    fun showError(message: String) {
        _uiState.update {
            it.copy(
                isLoading = false,
                alert = CameraAlert(message = message, type = AlertType.ERROR)
            )
        }
    }
}
