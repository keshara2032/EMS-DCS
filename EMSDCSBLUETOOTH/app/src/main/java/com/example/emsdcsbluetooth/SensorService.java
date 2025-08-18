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
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
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

    private static int COUNTER = 0;

    // Variables to track sensor activity and re-register if needed.
    private long lastSensorEventTime = 0;
    private Handler sensorHandler = new Handler();
    private Runnable sensorCheckRunnable = new Runnable() {
        @Override
        public void run() {
            if (isConnected) {
                long now = System.currentTimeMillis();
                // If no sensor event has been received for more than 5 seconds, re-register the listener.
                if (now - lastSensorEventTime > 5000) {
                    Log.d(TAG, "No sensor event for 5 seconds. Re-registering sensor listener.");
                    sensorManager.unregisterListener(SensorService.this);
                    sensorManager.registerListener(SensorService.this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
                }
            }
            sensorHandler.postDelayed(this, 5000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        // Request battery optimization exemption to help keep sensors active when the screen is off.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            String packageName = getPackageName();
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + packageName));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            }
        }

        // Acquire a full wake lock to keep the CPU and screen on indefinitely.
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        // FULL_WAKE_LOCK is deprecated but still functional for our purposes.
        wakeLock = pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE,
                "WatchAccelApp::WakelockTag");
        wakeLock.acquire();

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

        // Start the periodic sensor check.
        sensorHandler.post(sensorCheckRunnable);

        startBluetoothServer();
    }

    private void sendStatus(String status) {
        Intent intent = new Intent("com.example.emsdcsbluetooth.STATUS_UPDATE");
        intent.putExtra("status", status);
        sendBroadcast(intent);
    }

    private void updateCount(int count) {
        Intent intent = new Intent("com.example.emsdcsbluetooth.COUNT_UPDATE");
        intent.putExtra("count", count);
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
                    socket = serverSocket.accept();  // BLOCKING call until a connection is made.
                    Log.d(TAG, "Phone connected!");
                    sendStatus("Phone connected!");

                    outputStream = socket.getOutputStream();
                    isConnected = true;

                    COUNTER = 0;
                    lastSensorEventTime = System.currentTimeMillis();
                    // Register the accelerometer listener.
                    sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
                    sendStatus("Sending data...");

                    // Keep the thread alive as long as the connection is active.
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
            Log.e(TAG, "Error during cleanup", e);
        }
        sensorManager.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (isConnected && outputStream != null && event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            lastSensorEventTime = System.currentTimeMillis();
            long timestamp = System.currentTimeMillis();
            float x = event.values[0];
            float y = event.values[1];
            float z = event.values[2];
            COUNTER++;

            String message = COUNTER + "," + timestamp + "," + x + "," + y + "," + z + "\n";

            try {
                outputStream.write(message.getBytes());
                outputStream.flush();
                Log.d(TAG, "Sent: " + message.trim());
                updateCount(COUNTER);
            } catch (IOException e) {
                Log.e(TAG, "Send failed", e);
                isConnected = false;
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // No action required.
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Start as a foreground service with a persistent notification.
        NotificationChannel channel = new NotificationChannel("sensor_channel", "Sensor Service", NotificationManager.IMPORTANCE_LOW);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }

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
        sensorHandler.removeCallbacks(sensorCheckRunnable);
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
