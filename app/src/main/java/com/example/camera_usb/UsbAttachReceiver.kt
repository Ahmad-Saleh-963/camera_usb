package com.example.camera_usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

class UsbAttachReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
        if (!device.isVideoClassDevice()) return

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_USB_CAMERA
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(UsbManager.EXTRA_DEVICE, device)
        }
        context.startActivity(launchIntent)
    }
}

private fun UsbDevice.isVideoClassDevice(): Boolean {
    if (deviceClass == UsbConstants.USB_CLASS_VIDEO) return true
    for (i in 0 until interfaceCount) {
        if (getInterface(i).interfaceClass == UsbConstants.USB_CLASS_VIDEO) return true
    }
    return false
}

