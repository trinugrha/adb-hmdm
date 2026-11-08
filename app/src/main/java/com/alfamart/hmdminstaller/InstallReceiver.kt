package com.alfamart.hmdminstaller

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
        val pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME) ?: ""
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
        val ok = status == PackageInstaller.STATUS_SUCCESS
        // Diteruskan ke MainActivity via polling sederhana
        lastResult = (if (ok) "OK:" else "FAIL:$status ") + pkg + if (msg.isNotBlank()) " — $msg" else ""
    }

    companion object {
        @Volatile var lastResult: String? = null
    }
}
