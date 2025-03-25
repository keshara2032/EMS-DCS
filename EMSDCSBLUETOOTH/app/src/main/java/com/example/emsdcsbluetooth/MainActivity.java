package com.example.emsdcsbluetooth;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.os.Bundle;
import android.widget.TextView;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.util.Log;

import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;

import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;


import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

public class MainActivity extends Activity {

    private static final int REQUEST_CODE_BT_PERMISSIONS = 100;
    private TextView statusView;
    private TextView countView;


    @RequiresApi(api = Build.VERSION_CODES.S)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusView = findViewById(R.id.statusView);
        countView = findViewById(R.id.countView);

        statusView.setText("Waiting to start...");

        // Optional: Keep screen on for testing
//        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        registerReceiver(statusReceiver, new IntentFilter("com.example.emsdcsbluetooth.STATUS_UPDATE"));
        registerReceiver(statusReceiver, new IntentFilter("com.example.emsdcsbluetooth.COUNT_UPDATE"));


        if (!hasBluetoothPermissions()) {
            requestPermissions(new String[]{
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.BODY_SENSORS
            }, REQUEST_CODE_BT_PERMISSIONS);
        } else {
            startSensorService();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(statusReceiver);

    }

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.example.emsdcsbluetooth.STATUS_UPDATE".equals(intent.getAction())) {
                String msg = intent.getStringExtra("status");
                statusView.setText(msg);
            }
            if ("com.example.emsdcsbluetooth.COUNT_UPDATE".equals(intent.getAction())) {
                int msg = intent.getIntExtra("count",0);
                countView.setText(String.valueOf(msg));
            }
        }
    };


    @RequiresApi(api = Build.VERSION_CODES.S)
    private boolean hasBluetoothPermissions() {
        return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED;
    }

    private void startSensorService() {
        statusView.setText("Starting service...");
        Intent intent = new Intent(this, SensorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }


    @RequiresApi(api = Build.VERSION_CODES.S)
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == REQUEST_CODE_BT_PERMISSIONS) {
            boolean allGranted = false;
            Log.d("MainActivity", "Permission result: " + grantResults.length);
            for (int result : grantResults) {
                Log.d("MainActivity", "Permission result: " + result);
                if (result == PackageManager.PERMISSION_GRANTED) {
                    allGranted = true;
                    break;
                }
            }

            if (allGranted) {
                startSensorService();
            } else {
                statusView.setText("Permission denied.");
                Toast.makeText(this, "All permissions are required to start service", Toast.LENGTH_LONG).show();
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }
}
