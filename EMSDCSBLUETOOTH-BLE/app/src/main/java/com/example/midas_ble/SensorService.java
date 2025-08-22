package com.example.midas_ble;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;                 // for GATT_* constants
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
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

import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class SensorService extends Service implements SensorEventListener {

    private static final String TAG = "BleSensorService";

    // Stable custom UUIDs (must match your PC client)
    public static final UUID SERVICE_UUID = UUID.fromString("8b2e0001-5b3a-4f93-9f2a-5e0f5e5f0001");
    public static final UUID CHAR_UUID    = UUID.fromString("8b2e0002-5b3a-4f93-9f2a-5e0f5e5f0002");
    private static final UUID CCCD_UUID   = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final String NOTIF_CHANNEL = "sensor_ble_channel";
    private static final int    NOTIF_ID      = 1001;
    private static final int    MAX_NOTIFY_BYTES = 20; // safe without MTU negotiation

    private SensorManager sensorManager;
    private Sensor accelerometer;

    private BluetoothManager btManager;
    private BluetoothAdapter btAdapter;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothGattServer gattServer;
    private BluetoothGattCharacteristic imuChar;

    private final Set<BluetoothDevice> subscribers = new HashSet<>();
    private int seq = 0;
    private PowerManager.WakeLock wakeLock;

    // ------------ Permission helpers (Android 12+) ------------
    private boolean isSPlus() { return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S; }

    private boolean hasPerm(String perm) {
        return !isSPlus() || checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasConnect()  { return hasPerm(Manifest.permission.BLUETOOTH_CONNECT); }
    private boolean hasAdvertise(){ return hasPerm(Manifest.permission.BLUETOOTH_ADVERTISE); }
    // (We don’t scan here, but keep for completeness)
    @SuppressWarnings("unused")
    private boolean hasScan()     { return hasPerm(Manifest.permission.BLUETOOTH_SCAN); }


    private void updateCount(int count) {
        Intent intent = new Intent("com.example.emsdcsbluetooth.COUNT_UPDATE");
        intent.putExtra("count", count);
        sendBroadcast(intent);
    }

    private void sendStatus(String status) {
        Intent intent = new Intent("com.example.emsdcsbluetooth.STATUS_UPDATE");
        intent.putExtra("status", status);
        sendBroadcast(intent);
    }

    // --- Add these helpers inside your SensorService class ---

    private static String gattStatusToString(int s) {
        switch (s) {
            case BluetoothGatt.GATT_SUCCESS: return "GATT_SUCCESS(0)";
            case BluetoothGatt.GATT_READ_NOT_PERMITTED: return "READ_NOT_PERMITTED(2)";
            case BluetoothGatt.GATT_WRITE_NOT_PERMITTED: return "WRITE_NOT_PERMITTED(3)";
            case BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION: return "INSUFFICIENT_AUTH(5)";
            case BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED: return "REQ_NOT_SUPPORTED(6)";
            case BluetoothGatt.GATT_INVALID_OFFSET: return "INVALID_OFFSET(7)";
            case BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH: return "INVALID_ATTR_LEN(13)";
            case BluetoothGatt.GATT_CONNECTION_CONGESTED: return "CONGESTED(143)";
            case BluetoothGatt.GATT_FAILURE: return "GATT_FAILURE(257)";
            default: return "STATUS(" + s + ")";
        }
    }

    private static String stateToString(int st) {
        switch (st) {
            case android.bluetooth.BluetoothProfile.STATE_DISCONNECTED: return "DISCONNECTED(0)";
            case android.bluetooth.BluetoothProfile.STATE_CONNECTING:   return "CONNECTING(1)";
            case android.bluetooth.BluetoothProfile.STATE_CONNECTED:    return "CONNECTED(2)";
            case android.bluetooth.BluetoothProfile.STATE_DISCONNECTING:return "DISCONNECTING(3)";
            default: return "STATE(" + st + ")";
        }
    }

    private static String advErrorToString(int code) {
        switch (code) {
            case 1: return "DATA_TOO_LARGE(1)";
            case 2: return "TOO_MANY_ADVERTISERS(2)";
            case 3: return "ALREADY_STARTED(3)";
            case 4: return "INTERNAL_ERROR(4)";
            case 5: return "FEATURE_UNSUPPORTED(5)";
            default: return "ERROR(" + code + ")";
        }
    }

    private static String cccdValueToString(byte[] v) {
        if (v == null || v.length == 0) return "[]";
        // bit0=Notify, bit1=Indicate
        boolean notif = (v[0] & 0x01) != 0;
        boolean indic = (v[0] & 0x02) != 0;
        return Arrays.toString(v) + " (notify=" + notif + ", indicate=" + indic + ")";
    }










    @Override
    public void onCreate() {
        super.onCreate();

        // Foreground notification
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(
                NOTIF_CHANNEL, "BLE IMU Service", NotificationManager.IMPORTANCE_LOW);
        if (nm != null) nm.createNotificationChannel(ch);
        Notification n = new Notification.Builder(this, NOTIF_CHANNEL)
                .setContentTitle("BLE IMU Service")
                .setContentText("Streaming accelerometer via BLE")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .build();
        startForeground(NOTIF_ID, n);

        // Optional: keep CPU awake
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BleIMU::Wakelock");
        wakeLock.acquire();

        // Sensors
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);

        // Bluetooth managers (reading adapter requires CONNECT on S+ for some operations)
        btManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        btAdapter = BluetoothAdapter.getDefaultAdapter();

        if (btAdapter == null) {
            Log.e(TAG, "Bluetooth not available.");
            stopSelf();
            return;
        }
        if (!btAdapter.isEnabled()) {
            Log.e(TAG, "Bluetooth disabled.");
            stopSelf();
            return;
        }

        // Check we have permissions we need before touching advertiser/server
        if (isSPlus() && (!hasConnect() || !hasAdvertise())) {
            Log.e(TAG, "Missing runtime BT permissions. Ensure Activity granted CONNECT & ADVERTISE.");
            stopSelf();
            return;
        }

        // GATT server
        try {
            gattServer = btManager.openGattServer(this, gattCallback); // requires CONNECT on S+
        } catch (SecurityException se) {
            Log.e(TAG, "openGattServer denied (CONNECT).", se);
            stopSelf(); return;
        }

        BluetoothGattService svc = new BluetoothGattService(
                SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);

        imuChar = new BluetoothGattCharacteristic(
                CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);

        BluetoothGattDescriptor cccd = new BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE);
        imuChar.addDescriptor(cccd);
        svc.addCharacteristic(imuChar);

        try {
            gattServer.addService(svc); // requires CONNECT on S+
        } catch (SecurityException se) {
            Log.e(TAG, "addService denied (CONNECT).", se);
            stopSelf(); return;
        }

        // Advertiser
        try {
            advertiser = btAdapter.getBluetoothLeAdvertiser(); // requires ADVERTISE on S+
        } catch (SecurityException se) {
            Log.e(TAG, "getBluetoothLeAdvertiser denied (ADVERTISE).", se);
            stopSelf(); return;
        }
        if (advertiser == null) {
            Log.e(TAG, "BLE advertiser not available.");
            stopSelf(); return;
        }

// Start advertising our service UUID (UUID in adv, Name in scan response)
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .build();

        AdvertiseData advData = new AdvertiseData.Builder()
                .addServiceUuid(new android.os.ParcelUuid(SERVICE_UUID))
                .setIncludeDeviceName(false)     // important: keep name OUT of main adv
                .build();

        AdvertiseData scanResp = new AdvertiseData.Builder()
                .setIncludeDeviceName(true)      // put long name here instead
                .build();

// Overload with scan response (API 21+)
        try {
            advertiser.startAdvertising(settings, advData, scanResp, advCallback);
        } catch (SecurityException se) {
            Log.e(TAG, "startAdvertising denied (ADVERTISE).", se);
            stopSelf(); return;
        }

        Log.i(TAG, "BLE IMU Service started");
        sendStatus("Waiting for DCS...");
    }

    private final AdvertiseCallback advCallback = new AdvertiseCallback() {
        @Override public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            Log.i(TAG, "ADV started: mode=" + settingsInEffect.getMode()
                    + " tx=" + settingsInEffect.getTxPowerLevel()
                    + " connectable=" + settingsInEffect.isConnectable());
        }
        @Override public void onStartFailure(int errorCode) {
            Log.e(TAG, "ADV failed: " + advErrorToString(errorCode));
        }
    };

    private final BluetoothGattServerCallback gattCallback = new BluetoothGattServerCallback() {
        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            if (!hasConnect()) return;
            Log.i(TAG, "CONN: " + device.getAddress()
                    + " status=" + gattStatusToString(status)
                    + " state=" + stateToString(newState));
            if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                synchronized (subscribers) { subscribers.remove(device); }
                boolean emptyNow;
                synchronized (subscribers) { emptyNow = subscribers.isEmpty(); }
                if (emptyNow) {
                    seq = 0;
                    updateCount(0);
                    Log.i(TAG, "All subscribers disconnected → seq reset to 0");
                }
            }
        }


        @Override
        public void onServiceAdded(int status, BluetoothGattService service) {
            if (!hasConnect()) return;
            Log.i(TAG, "SERVICE_ADDED: " + service.getUuid() + " status=" + gattStatusToString(status));
        }

        @Override
        public void onMtuChanged(BluetoothDevice device, int mtu) {
            if (!hasConnect()) return;
            Log.i(TAG, "MTU_CHANGED: " + device.getAddress() + " mtu=" + mtu);
        }

        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
                                             BluetoothGattDescriptor descriptor, boolean preparedWrite,
                                             boolean responseNeeded, int offset, byte[] value) {
            if (!hasConnect()) {
                if (responseNeeded && gattServer != null) {
                    try { gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null); }
                    catch (SecurityException ignored) {}
                }
                return;
            }
            Log.i(TAG, "DESC_WRITE: dev=" + device.getAddress()
                    + " desc=" + descriptor.getUuid()
                    + " val=" + cccdValueToString(value));

            if (descriptor.getUuid().equals(CCCD_UUID)) {
                boolean enable = value != null && value.length > 0 && (value[0] & 0x01) != 0;

                int before;
                synchronized (subscribers) {
                    before = subscribers.size();
                    if (enable) subscribers.add(device); else subscribers.remove(device);
                }

                if (responseNeeded) gattSafeSendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value);

                // Reset rules:
                // - First subscriber just enabled → start a fresh sequence
                // - All subscribers gone → reset to 0
                if (enable && before == 0) {
                    seq = 0;
                    updateCount(0);
                    Log.i(TAG, "First subscriber enabled notifications → seq reset to 0");
                    sendStatus("DCS Connected!");
                } else if (!enable) {
                    boolean emptyNow;
                    synchronized (subscribers) { emptyNow = subscribers.isEmpty(); }
                    if (emptyNow) {
                        seq = 0;
                        updateCount(0);
                        Log.i(TAG, "All subscribers disabled notifications → seq reset to 0");
                    }
                    sendStatus("Waiting for DCS...");
                }

                Log.i(TAG, "CCCD " + (enable ? "ENABLED" : "DISABLED") + " for " + device.getAddress()
                        + " subs=" + subscribers.size());
            } else if (responseNeeded) {
                gattSafeSendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null);
            }
        }

        @Override
        public void onDescriptorReadRequest(BluetoothDevice device, int requestId, int offset,
                                            BluetoothGattDescriptor descriptor) {
            if (!hasConnect()) return;
            Log.i(TAG, "DESC_READ: dev=" + device.getAddress() + " desc=" + descriptor.getUuid());
            gattSafeSendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, descriptor.getValue());
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset,
                                                BluetoothGattCharacteristic characteristic) {
            if (!hasConnect()) return;
            Log.i(TAG, "CHAR_READ: dev=" + device.getAddress() + " char=" + characteristic.getUuid()
                    + " offset=" + offset);

            if (CHAR_UUID.equals(characteristic.getUuid())) {
                byte[] v = imuChar.getValue();
                if (v == null) v = new byte[]{};
                gattSafeSendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, v);
            } else {
                gattSafeSendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null);
            }
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                                                 BluetoothGattCharacteristic characteristic,
                                                 boolean preparedWrite, boolean responseNeeded,
                                                 int offset, byte[] value) {
            if (!hasConnect()) return;
            Log.i(TAG, "CHAR_WRITE: dev=" + device.getAddress() + " char=" + characteristic.getUuid()
                    + " len=" + (value == null ? 0 : value.length));
            if (responseNeeded) {
                gattSafeSendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
            }
        }

        @Override
        public void onNotificationSent(BluetoothDevice device, int status) {
            if (!hasConnect()) return;
            Log.i(TAG, "NOTIFY_SENT: dev=" + device.getAddress()
                    + " status=" + gattStatusToString(status));
        }
    };

    private void gattSafeSendResponse(BluetoothDevice device, int reqId, int status, int offset, byte[] value) {
        if (gattServer == null || !hasConnect()) return;
        try {
            gattServer.sendResponse(device, reqId, status, offset, value);
        } catch (SecurityException se) {
            Log.e(TAG, "sendResponse denied (CONNECT).", se);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) return;
        if (!hasConnect()) return;                        // needs CONNECT on S+

        if (gattServer == null || imuChar == null) return;

        // Snapshot subscribers to avoid concurrent modification
        final Set<BluetoothDevice> subsSnapshot;
        synchronized (subscribers) {
            if (subscribers.isEmpty()) return;            // nobody listening → don't advance seq
            subsSnapshot = new HashSet<>(subscribers);
        }

        long epochMs = System.currentTimeMillis();
        float x = event.values[0], y = event.values[1], z = event.values[2];

        // Build one full frame; OS will handle ATT sizing (your MTU is large on Windows)
        int nextSeq = seq + 1;
        String line = nextSeq + "," + epochMs + "," + x + "," + y + "," + z + "\n";
        byte[] payload = line.getBytes(StandardCharsets.UTF_8);

        boolean deliveredToAtLeastOne = false;

        for (BluetoothDevice d : subsSnapshot) {
            try {
                imuChar.setValue(payload);
                boolean ok = gattServer.notifyCharacteristicChanged(d, imuChar, /*confirm=*/false);
                if (ok) {
                    deliveredToAtLeastOne = true;
                } else {
                    // If a device failed this time, we just skip counting it
                    // (Optional: remove from subscribers if persistently failing)
                }
            } catch (SecurityException se) {
                Log.e(TAG, "notify denied (CONNECT).", se);
            } catch (Exception e) {
                Log.e(TAG, "notify failed for device " + d.getAddress(), e);
            }
        }

        // Advance seq & update UI only if at least one subscriber got this frame
        if (deliveredToAtLeastOne) {
            seq = nextSeq;
            updateCount(seq);
            Log.d(TAG, "Frame " + seq + " delivered to ≥1 subscriber");
        }
    }


    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try { sensorManager.unregisterListener(this); } catch (Exception ignored) {}

        // Stop advertising (guard ADVERTISE)
        if (advertiser != null && hasAdvertise()) {
            try { advertiser.stopAdvertising(advCallback); } catch (SecurityException ignored) {}
        }

        // Close GATT server (guard CONNECT)
        if (gattServer != null && hasConnect()) {
            try { gattServer.close(); } catch (SecurityException ignored) {}
        }

        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
        Log.i(TAG, "BLE IMU Service stopped");
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
}
