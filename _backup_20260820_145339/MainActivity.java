package com.phonecam;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private Button btnStart;
    private Button btnStop;
    private TextView tvStatus;
    private TextView tvIpAddress;
    private static final int PERMISSION_REQUEST_CODE = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);
        tvStatus = findViewById(R.id.tvStatus);
        tvIpAddress = findViewById(R.id.tvIpAddress);

        // Verificar y solicitar permisos
        if (!hasPermissions()) {
            requestPermissions();
        }

        // Mostrar IP local
        String ip = NetworkUtils.getLocalIpAddress();
        tvIpAddress.setText("📱 IP: " + ip + ":8554");

        btnStart.setOnClickListener(v -> startStreaming());
        btnStop.setOnClickListener(v -> stopStreaming());
        btnStop.setEnabled(false);
    }

    private boolean hasPermissions() {
        String[] permissions = getRequiredPermissions();
        for (String perm : permissions) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private String[] getRequiredPermissions() {
        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.CAMERA);
        perms.add(Manifest.permission.INTERNET);
        perms.add(Manifest.permission.ACCESS_NETWORK_STATE);
        perms.add(Manifest.permission.ACCESS_WIFI_STATE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            perms.add(Manifest.permission.RECORD_AUDIO);
        }

        return perms.toArray(new String[0]);
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(
                this,
                getRequiredPermissions(),
                PERMISSION_REQUEST_CODE
        );
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (!allGranted) {
                Toast.makeText(this, "Se necesitan permisos para la cámara", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void startStreaming() {
        if (!hasPermissions()) {
            Toast.makeText(this, "Permisos no concedidos", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent intent = new Intent(this, RTSPService.class);
        startService(intent);

        tvStatus.setText("📹 Transmitiendo...");
        tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark));
        btnStart.setEnabled(false);
        btnStop.setEnabled(true);

        Toast.makeText(this, "📹 Transmisión iniciada", Toast.LENGTH_SHORT).show();
    }

    private void stopStreaming() {
        Intent intent = new Intent(this, RTSPService.class);
        stopService(intent);

        tvStatus.setText("⏸️ Detenido");
        tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray));
        btnStart.setEnabled(true);
        btnStop.setEnabled(false);

        Toast.makeText(this, "⏹️ Transmisión detenida", Toast.LENGTH_SHORT).show();
    }
}