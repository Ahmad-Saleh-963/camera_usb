package com.example.camera_usb

import android.app.Application
import com.serenegiant.utils.UVCUtils

class CameraUsbApp : Application() {
    override fun onCreate() {
        super.onCreate()
        UVCUtils.init(this)
    }
}
