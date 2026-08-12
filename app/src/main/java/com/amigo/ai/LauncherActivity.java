package com.amigo.ai;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

public class LauncherActivity extends Activity {
    private static final int REQ_AUDIO = 200;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ensurePermissionAndStart();
    }

    private void ensurePermissionAndStart() {
        if (Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }
        startWakeServiceAndOpenApp();
    }

    private void startWakeServiceAndOpenApp() {
        try {
            Intent service = new Intent(this, WakeWordService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
            else startService(service);
        } catch (Exception e) {
            Toast.makeText(this, "No pude iniciar Hey Amigo: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }

        Intent app = new Intent(this, MainActivity.class);
        startActivity(app);
        finish();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startWakeServiceAndOpenApp();
            } else {
                Toast.makeText(this, "Sin permiso de micrófono no puede funcionar Hey Amigo.", Toast.LENGTH_LONG).show();
                startActivity(new Intent(this, MainActivity.class));
                finish();
            }
        }
    }
}
