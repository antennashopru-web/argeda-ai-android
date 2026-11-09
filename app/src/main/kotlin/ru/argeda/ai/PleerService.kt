package ru.argeda.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/**
 * Keeps the app alive while the site's own player is playing (so music continues in the background)
 * and shows play/pause controls in the shade and on the lock screen. The sound itself is played by the page.
 */
class PleerService : Service() {
    companion object {
        const val A_PLAY = "ru.argeda.ai.PLAY"
        const val A_PAUSE = "ru.argeda.ai.PAUSE"
        const val A_SEEK = "ru.argeda.ai.SEEK"
        const val A_STOP = "ru.argeda.ai.STOP"
        const val A_TOGGLE = "ru.argeda.ai.TOGGLE"
        const val A_NEXT = "ru.argeda.ai.NEXT"
        const val A_PREV = "ru.argeda.ai.PREV"
        const val A_STOP_TIHO = "ru.argeda.ai.STOP_TIHO"
        const val CH = "pleer"
        const val NOTIF_ID = 7

        @Volatile @JvmStatic var ui: MainActivity? = null
        @Volatile @JvmStatic var playing = false

        fun send(c: Context, action: String, title: String?, posMs: Int, durMs: Int) {
            if (Build.VERSION.SDK_INT < 26) return // Android 7: no channels / foreground start API; the page plays on its own
            val i = Intent(c, PleerService::class.java).setAction(action)
            if (title != null) i.putExtra("title", title)
            i.putExtra("pos", posMs).putExtra("dur", durMs)
            try {
                if (A_PLAY == action) c.startForegroundService(i) else c.startService(i)
            } catch (ignored: Exception) { /* background start limits: the page keeps playing anyway */ }
        }
    }

    private var session: MediaSession? = null
    private var title = "ARGEDA AI"
    private var posMs = 0L
    private var durMs = 0L
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private val h = Handler(Looper.getMainLooper())

    /** Paused for long: let the process sleep. A short pause (next track, buffering) keeps the service in the foreground,
     *  because Android 12+ forbids going back to the foreground from the background. */
    private val otpustit = Runnable { if (!playing) { zamki(false); stopForegroundCompat(false) } }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) == null) {
            val c = NotificationChannel(CH, "Музыка", NotificationManager.IMPORTANCE_LOW)
            c.setSound(null, null)
            nm.createNotificationChannel(c)
        }
        session = MediaSession(this, "ArgedaPleer").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { mirror("play", 0) }
                override fun onPause() { pauza() }
                override fun onStop() { mirror("pause", 0); stopAll() }
                override fun onSeekTo(pos: Long) { mirror("seek", pos.toInt()) }
                override fun onSkipToNext() { mirror("next", 0) }
                override fun onSkipToPrevious() { mirror("prev", 0) }
            })
            isActive = true
        }
    }

    private fun mirror(what: String, ms: Int) {
        val u = ui
        if (u != null) u.pleerMirror(what, ms) else if (what != "seek") stopAll()
    }

    /** Pause pressed outside the page: show it at once, do not wait for the page to answer. */
    private fun pauza() { mirror("pause", 0); if (ui != null) { playing = false; show() } }

    private val shum = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { if (playing) pauza() } // headphones unplugged
    }
    private var shumZareg = false

    override fun onTaskRemoved(root: Intent?) { mirror("pause", 0); stopAll(); super.onTaskRemoved(root) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            A_PLAY -> {
                val t = intent.getStringExtra("title")
                if (!t.isNullOrEmpty()) title = t
                posMs = intent.getIntExtra("pos", 0).toLong(); durMs = intent.getIntExtra("dur", 0).toLong()
                playing = true; show()
            }
            A_PAUSE -> { posMs = intent.getIntExtra("pos", posMs.toInt()).toLong(); playing = false; show() }
            A_SEEK -> { posMs = intent.getIntExtra("pos", 0).toLong(); show() }
            A_TOGGLE -> if (playing) pauza() else mirror("play", 0)
            A_STOP -> { mirror("pause", 0); stopAll() }
            A_STOP_TIHO -> stopAll()
            A_NEXT -> mirror("next", 0)
            A_PREV -> mirror("prev", 0)
        }
        return START_NOT_STICKY
    }

    private fun zamki(on: Boolean) {
        try {
            val w = wake ?: (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "argeda:pleer").also { it.setReferenceCounted(false); wake = it }
            val wf = wifi ?: (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
                .createWifiLock(@Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF, "argeda:pleer").also { it.setReferenceCounted(false); wifi = it }
            if (on) { w.acquire(3 * 60 * 60 * 1000L); wf.acquire() }
            else { if (w.isHeld) w.release(); if (wf.isHeld) wf.release() }
            if (on && !shumZareg) { registerReceiver(shum, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)); shumZareg = true }
            else if (!on && shumZareg) { unregisterReceiver(shum); shumZareg = false }
        } catch (ignored: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat(remove: Boolean) {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(if (remove) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH)
        else stopForeground(remove)
    }

    private fun stopAll() {
        playing = false; h.removeCallbacks(otpustit); zamki(false)
        stopForegroundCompat(true)
        stopSelf()
    }

    private fun pi(action: String, code: Int): PendingIntent {
        val i = Intent(this, PleerService::class.java).setAction(action)
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun show() {
        val s = session ?: return
        s.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "ARGEDA AI")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, durMs).build())
        s.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP
                    or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
            .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, posMs, if (playing) 1f else 0f).build())
        val open = Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val openPi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_nota)
            .setContentTitle(title).setContentText("ARGEDA AI")
            .setContentIntent(openPi).setDeleteIntent(pi(A_STOP_TIHO, 5)).setOngoing(playing).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(null, "Назад", pi(A_PREV, 3)).build())
            .addAction(Notification.Action.Builder(null, if (playing) "Пауза" else "Играть", pi(A_TOGGLE, 1)).build())
            .addAction(Notification.Action.Builder(null, "Дальше", pi(A_NEXT, 4)).build())
            .addAction(Notification.Action.Builder(null, "Закрыть", pi(A_STOP, 2)).build())
            .setStyle(Notification.MediaStyle().setMediaSession(s.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(NOTIF_ID, n)
        } catch (e: Exception) { // not allowed from the background: at least update the notification
            try { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n) } catch (ignored: Exception) {}
        }
        h.removeCallbacks(otpustit)
        if (playing) zamki(true)
        else h.post(otpustit) // paused: notification stays but can be swiped away, process may sleep
    }

    override fun onDestroy() {
        playing = false; h.removeCallbacks(otpustit); zamki(false)
        session?.let { it.isActive = false; it.release() }
        super.onDestroy()
    }
}
