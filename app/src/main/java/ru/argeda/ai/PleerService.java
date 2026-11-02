package ru.argeda.ai;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;

/**
 * Keeps the app alive while the site's own player is playing (so music continues in the background)
 * and shows play/pause controls in the shade and on the lock screen. The sound itself is played by the page.
 */
public class PleerService extends Service {
    static final String A_PLAY = "ru.argeda.ai.PLAY", A_PAUSE = "ru.argeda.ai.PAUSE", A_SEEK = "ru.argeda.ai.SEEK",
            A_STOP = "ru.argeda.ai.STOP", A_TOGGLE = "ru.argeda.ai.TOGGLE";
    static final String CH = "pleer";
    static final int NOTIF_ID = 7;

    static volatile MainActivity ui;
    static volatile boolean playing;

    private MediaSession session;
    private String title = "ARGEDA AI";
    private long posMs, durMs;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CH) == null) {
            NotificationChannel c = new NotificationChannel(CH, "Музыка", NotificationManager.IMPORTANCE_LOW);
            c.setSound(null, null);
            nm.createNotificationChannel(c);
        }
        session = new MediaSession(this, "ArgedaPleer");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { mirror("play", 0); }
            @Override public void onPause() { mirror("pause", 0); }
            @Override public void onStop() { mirror("pause", 0); stopAll(); }
            @Override public void onSeekTo(long pos) { mirror("seek", (int) pos); }
        });
        session.setActive(true);
    }

    private void mirror(String what, int ms) { if (ui != null) ui.pleerMirror(what, ms); }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (A_PLAY.equals(a)) {
            String t = intent.getStringExtra("title");
            if (t != null && !t.isEmpty()) title = t;
            posMs = intent.getIntExtra("pos", 0); durMs = intent.getIntExtra("dur", 0);
            playing = true; show();
        } else if (A_PAUSE.equals(a)) { posMs = intent.getIntExtra("pos", (int) posMs); playing = false; show(); }
        else if (A_SEEK.equals(a)) { posMs = intent.getIntExtra("pos", 0); show(); }
        else if (A_TOGGLE.equals(a)) { mirror(playing ? "pause" : "play", 0); }
        else if (A_STOP.equals(a)) { mirror("pause", 0); stopAll(); }
        return START_NOT_STICKY;
    }

    private void stopAll() {
        playing = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private PendingIntent pi(String action, int code) {
        Intent i = new Intent(this, PleerService.class).setAction(action);
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void show() {
        session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "ARGEDA AI")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, durMs).build());
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, posMs, playing ? 1f : 0f).build());
        Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CH)
                .setSmallIcon(R.drawable.ic_nota)
                .setContentTitle(title).setContentText("ARGEDA AI")
                .setContentIntent(openPi).setOngoing(playing).setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(null, playing ? "Пауза" : "Играть", pi(A_TOGGLE, 1)).build())
                .addAction(new Notification.Action.Builder(null, "Закрыть", pi(A_STOP, 2)).build())
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1))
                .build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(NOTIF_ID, n);
        if (!playing) stopForeground(STOP_FOREGROUND_DETACH); // paused: notification stays, process may sleep
    }

    @Override
    public void onDestroy() {
        playing = false;
        if (session != null) { session.setActive(false); session.release(); }
        super.onDestroy();
    }

    static void send(Context c, String action, String title, int posMs, int durMs) {
        if (Build.VERSION.SDK_INT < 26) return; // Android 7: no channels / foreground start API; the page plays on its own
        Intent i = new Intent(c, PleerService.class).setAction(action);
        if (title != null) i.putExtra("title", title);
        i.putExtra("pos", posMs).putExtra("dur", durMs);
        try {
            if (A_PLAY.equals(action)) c.startForegroundService(i); else c.startService(i);
        } catch (Exception ignored) { /* background start limits: the page keeps playing anyway */ }
    }
}
