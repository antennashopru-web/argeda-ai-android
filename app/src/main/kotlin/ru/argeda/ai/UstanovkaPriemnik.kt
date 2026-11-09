package ru.argeda.ai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast
import java.io.File
import java.io.IOException

/** Result of the update install session: shows the system confirmation screen if Android still asks for it. */
class UstanovkaPriemnik : BroadcastReceiver() {
    companion object { const val ACTION = "ru.argeda.ai.USTANOVKA" }

    override fun onReceive(c: Context, i: Intent) {
        val st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm: Intent? = i.getParcelableExtra(Intent.EXTRA_INTENT)
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { c.startActivity(confirm) } catch (e: Exception) { Toast.makeText(c, "Не удалось открыть установку", Toast.LENGTH_LONG).show() }
            }
        } else if (st != PackageInstaller.STATUS_SUCCESS) {
            if (st == PackageInstaller.STATUS_FAILURE_ABORTED) return // the person pressed "Cancel"
            // the session way failed: fall back to the classic installer screen with the same file
            val f = File(File(c.cacheDir, "update"), "ARGEDA-AI.apk")
            try {
                if (!f.exists()) throw IOException()
                val v = Intent(Intent.ACTION_VIEW).setDataAndType(CameraFileProvider.uriFor("update", f), "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                c.startActivity(v)
            } catch (e: Exception) { Toast.makeText(c, "Обновление не установилось, попробуйте ещё раз", Toast.LENGTH_LONG).show() }
        }
    }
}
