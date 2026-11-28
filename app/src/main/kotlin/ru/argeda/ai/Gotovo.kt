package ru.argeda.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * «Видео готово», «Не получилось» — one notification per finished work, so the person can minimise the app
 * or stay on another page. A tap opens the place where the result is: a page of the site (put) or a screen
 * of the app's own studio (ekran + k).
 */
object Gotovo {
    const val ACTION = "ru.argeda.ai.GOTOVO"

    fun pokazat(c: Context, klyuch: String, zag: String, tekst: String, put: String?, ekran: String?, k: String?) {
        try {
            val nm = c.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel("gotovo") == null)
                nm.createNotificationChannel(NotificationChannel("gotovo", "Готовые работы", NotificationManager.IMPORTANCE_DEFAULT))
            val id = 100 + (klyuch.hashCode() and 0xffff)
            val open = Intent(c, MainActivity::class.java).setAction(ACTION)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (!put.isNullOrEmpty()) open.putExtra("put", put)
            if (!ekran.isNullOrEmpty()) open.putExtra("ekran", ekran)
            if (!k.isNullOrEmpty()) open.putExtra("k", k)
            // own request code per notification: otherwise a newer one would overwrite the address of the older
            val pi = PendingIntent.getActivity(c, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            @Suppress("DEPRECATION")
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, "gotovo") else Notification.Builder(c)
            val t = tekst.trim().let { if (it.length > 120) it.substring(0, 117) + "…" else it }
            val n = b.setSmallIcon(R.drawable.ic_nota)
                .setContentTitle(zag.ifEmpty { "Готово" })
                .setContentText(t.ifEmpty { "Откройте приложение" })
                .setStyle(Notification.BigTextStyle().bigText(t.ifEmpty { "Откройте приложение" }))
                .setContentIntent(pi).setAutoCancel(true).build()
            nm.notify(id, n)
        } catch (ignored: Exception) {}
    }
}
