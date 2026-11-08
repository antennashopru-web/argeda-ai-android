package ru.argeda.ai;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/** Result of the update install session: shows the system confirmation screen if Android still asks for it. */
public class UstanovkaPriemnik extends BroadcastReceiver {
    static final String ACTION = "ru.argeda.ai.USTANOVKA";

    @Override public void onReceive(Context c, Intent i) {
        int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { c.startActivity(confirm); } catch (Exception e) { Toast.makeText(c, "Не удалось открыть установку", Toast.LENGTH_LONG).show(); }
            }
        } else if (st != PackageInstaller.STATUS_SUCCESS) {
            if (st == PackageInstaller.STATUS_FAILURE_ABORTED) return; // the person pressed "Cancel"
            // the session way failed: fall back to the classic installer screen with the same file
            java.io.File f = new java.io.File(new java.io.File(c.getCacheDir(), "update"), "ARGEDA-AI.apk");
            try {
                if (!f.exists()) throw new java.io.IOException();
                Intent v = new Intent(Intent.ACTION_VIEW).setDataAndType(CameraFileProvider.uriFor("update", f), "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(v);
            } catch (Exception e) { Toast.makeText(c, "Обновление не установилось, попробуйте ещё раз", Toast.LENGTH_LONG).show(); }
        }
    }
}
