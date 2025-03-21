package com.example.emsdatacollectionsystem;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;

import com.example.emsdatacollectionsystem.databinding.FragmentFirstBinding;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.UUID;

public class FirstFragment extends Fragment {

    private FragmentFirstBinding binding;

    private BluetoothSocket bluetoothSocket;
    private BufferedReader reader;
    private Thread readerThread;
    private boolean isRecording = false;
    private final ArrayList<String> accelDataList = new ArrayList<>();

    private static final int REQUEST_BLUETOOTH_PERMISSIONS = 1;

    // Replace with your smartwatch's MAC address
    private final String SMARTWATCH_MAC = "28:3D:C2:EA:54:81";
    private final String GOPRO_MAC = "28:3D:C2:EA:54:84";

    private final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentFirstBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        binding.buttonFirst.setText("Start Recording");

        requireActivity().runOnUiThread(() -> binding.statusText.setText("Ready for Recording"));
        requireActivity().runOnUiThread(() -> binding.dataStatus.setText("No data..."));


        binding.buttonFirst.setOnClickListener(v -> {
            if (!isRecording) {
                requestBluetoothPermissions();  // will call startBluetoothConnection() on grant
            } else {
                stopBluetoothConnection();
                binding.buttonFirst.setText("Start Recording");
            }
        });
    }

    private void requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (requireContext().checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                    requireContext().checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {

                requestPermissions(new String[]{
                        android.Manifest.permission.BLUETOOTH_CONNECT,
                        android.Manifest.permission.BLUETOOTH_SCAN
                }, REQUEST_BLUETOOTH_PERMISSIONS);
            } else {
                startBluetoothConnection();
                binding.buttonFirst.setText("Stop Recording");
            }
        } else {
            startBluetoothConnection();
            binding.buttonFirst.setText("Stop Recording");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_BLUETOOTH_PERMISSIONS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startBluetoothConnection();
                binding.buttonFirst.setText("Stop Recording");
            } else {
                showToast("Bluetooth permission denied.");
            }
        }
    }

    private void startBluetoothConnection() {
        isRecording = true;

        readerThread = new Thread(() -> {
            try {
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter == null || !adapter.isEnabled()) {
                    showToast("Bluetooth is not enabled.");

                    return;
                }

                BluetoothDevice device = adapter.getRemoteDevice(SMARTWATCH_MAC);
                if (ActivityCompat.checkSelfPermission(requireContext(), android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    showToast("Permission missing. Cannot connect.");
                    return;
                }

                bluetoothSocket = device.createRfcommSocketToServiceRecord(MY_UUID);
                bluetoothSocket.connect();

                requireActivity().runOnUiThread(() -> binding.statusText.setText("Connected to Smartwatch!"));


                reader = new BufferedReader(new InputStreamReader(bluetoothSocket.getInputStream()));

                String line;
                while (isRecording && (line = reader.readLine()) != null) {
                    Log.d("AccelData", line);
                    String display = line;
                    requireActivity().runOnUiThread(() -> binding.dataStatus.setText(display));
                    accelDataList.add(line);
                }

            } catch (IOException e) {
                Log.e("Bluetooth", "Error reading Bluetooth data", e);
                showToast("Bluetooth connection failed.");
                requireActivity().runOnUiThread(() -> binding.statusText.setText("Bluetooth connection failed to Smartwatch!"));

            }
        });

        readerThread.start();
        showToast("Recording started.");
    }

    private void stopBluetoothConnection() {
        isRecording = false;

        try {
            if (reader != null) reader.close();
            if (bluetoothSocket != null) bluetoothSocket.close();
        } catch (IOException e) {
            Log.e("Bluetooth", "Error closing Bluetooth connection", e);
        }

        if (readerThread != null && readerThread.isAlive()) {
            readerThread.interrupt();
        }

        showToast("Recording stopped.");
        Log.d("AccelData", "Collected data: " + accelDataList.size() + " entries");
    }

    private void showToast(String msg) {
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> Toast.makeText(getActivity(), msg, Toast.LENGTH_SHORT).show());
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopBluetoothConnection(); // ensure cleanup
        binding = null;
    }
}
