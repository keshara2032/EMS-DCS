package com.example.emsdcsbluetooth;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;

public class SensorService extends Service implements SensorEventListener {

    private static final String TAG = "SensorService";
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final String APP_NAME = "WatchAccelApp";

    private SensorManager sensorManager;
    private Sensor accelerometer;
    private BluetoothServerSocket serverSocket;
    private BluetoothSocket socket;
    private OutputStream outputStream;
    private boolean isConnected = false;

    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WatchAccelApp::WakelockTag");
        wakeLock.acquire();

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

        startBluetoothServer();
    }

    private void sendStatus(String status) {
        Intent intent = new Intent("com.example.emsdcsbluetooth.STATUS_UPDATE");
        intent.putExtra("status", status);
        sendBroadcast(intent);
    }

    private void startBluetoothServer() {
        new Thread(() -> {
            while (true) {
                try {
                    BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                            Log.e(TAG, "BLUETOOTH_CONNECT permission not granted. Cannot start server.");
                            stopSelf(); // stop the service if not allowed
                            return;
                        }
                    }
                    serverSocket = adapter.listenUsingRfcommWithServiceRecord(APP_NAME, MY_UUID);
                    sendStatus("Waiting for phone connection...");
                    Log.d(TAG, "Waiting for phone connection...");
                    socket = serverSocket.accept();  // BLOCKING
                    Log.d(TAG, "Phone connected!");
                    sendStatus("Phone connected!");


                    outputStream = socket.getOutputStream();
                    isConnected = true;

                    sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
                    sendStatus("Sending data...");


                    while (isConnected && socket.isConnected()) {
                        Thread.sleep(500);
                    }

                } catch (Exception e) {
                    Log.e(TAG, "Bluetooth error", e);
                    sendStatus("Bluetooth error!");

                } finally {
                    cleanup();
                }
            }
        }).start();
    }

    private void cleanup() {
        isConnected = false;
        try {
            if (outputStream != null) outputStream.close();
            if (socket != null) socket.close();
            if (serverSocket != null) serverSocket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        sensorManager.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (isConnected && outputStream != null && event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            long timestamp = System.currentTimeMillis();
            float x = event.values[0];
            float y = event.values[1];
            float z = event.values[2];
            String message = timestamp + "," + x + "," + y + "," + z + "\n";

            try {
                outputStream.write(message.getBytes());
                outputStream.flush();
                Log.d(TAG, "Sent: " + message.trim());
            } catch (IOException e) {
                Log.e(TAG, "Send failed", e);
                isConnected = false;
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Start as foreground service with persistent notification
        NotificationChannel channel = new NotificationChannel("sensor_channel", "Sensor Service", NotificationManager.IMPORTANCE_LOW);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);

        Notification notification = new Notification.Builder(this, "sensor_channel")
                .setContentTitle("Sensor Service Running")
                .setContentText("Sending accelerometer data...")
                .build();

        startForeground(1, notification);

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        cleanup();
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
