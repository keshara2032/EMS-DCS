package com.example.emsdcs

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import com.example.emsdcs.databinding.FragmentFirstBinding
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread

class FirstFragment : Fragment() {

    private var _binding: FragmentFirstBinding? = null
    private val binding get() = _binding!!

    private var bluetoothSocket: BluetoothSocket? = null
    private var reader: BufferedReader? = null
    private var readerThread: Thread? = null
    private var isRecording = false
    private var outputFile: File? = null
    private var fileWriter: BufferedWriter? = null

    private val REQUEST_BLUETOOTH_PERMISSIONS = 1
    private val SMARTWATCH_01_MAC = "28:3D:C2:EA:54:81"
    private val SMARTWATCH_02_MAC = "04:29:2E:DE:DD:AF"
    private val SMARTWATCH_03_MAC = "B0:4A:6A:49:5A:61"

    private val MY_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFirstBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.buttonFirst.text = "Start Recording"

        binding.buttonFirst.setOnClickListener {
            if (!isRecording) {
                requestBluetoothPermissions()
            } else {
                stopBluetoothConnection()
                binding.buttonFirst.text = "Start Recording"
                requireActivity().runOnUiThread {
                    binding.statusText.text = "Ready"
                }
            }
        }
    }

    private fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (requireContext().checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                requireContext().checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(
                    arrayOf(
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_SCAN
                    ), REQUEST_BLUETOOTH_PERMISSIONS
                )
            } else {
                startBluetoothConnection()
                binding.buttonFirst.text = "Stop Recording"
            }
        } else {
            startBluetoothConnection()
            binding.buttonFirst.text = "Stop Recording"
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BLUETOOTH_PERMISSIONS && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startBluetoothConnection()
            binding.buttonFirst.text = "Stop Recording"
        } else {
            showToast("Bluetooth permission denied.")
        }
    }

    private fun startBluetoothConnection() {
        isRecording = true

        createOutputFile()

        readerThread = thread {
            try {
                val adapter = BluetoothAdapter.getDefaultAdapter()
                if (adapter == null || !adapter.isEnabled) {
                    showToast("Bluetooth is not enabled.")
                    return@thread
                }

                val device: BluetoothDevice = adapter.getRemoteDevice(SMARTWATCH_01_MAC)
                if (ActivityCompat.checkSelfPermission(
                        requireContext(),
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    showToast("Permission missing. Cannot connect.")
                    return@thread
                }

                bluetoothSocket = device.createRfcommSocketToServiceRecord(MY_UUID)
                bluetoothSocket?.connect()

                reader = BufferedReader(InputStreamReader(bluetoothSocket?.inputStream))

                requireActivity().runOnUiThread {
                    binding.statusText.text = "Connected to Smartwatch!"
                }

                var line: String?
                while (isRecording) {
                    line = reader?.readLine() ?: break
                    Log.d("AccelData", line)

                    val currentTime = System.currentTimeMillis()
                    val timestampedLine = "${currentTime},$line"
                    fileWriter?.write(timestampedLine)
                    fileWriter?.newLine()
                    fileWriter?.flush()

                    if (isRecording)
                    requireActivity().runOnUiThread {
                        binding.dataStatus.text = "Smartwatch data: $line"
                        binding.epochTimestamp.text = "$currentTime"
                    }
                }

            } catch (e: IOException) {
                Log.e("Bluetooth", "Error reading Bluetooth data", e)
                showToast("Bluetooth connection failed.")
            }
        }
        showToast("Recording started.")
    }

    private fun stopBluetoothConnection() {
        isRecording = false
        try {
            reader?.close()
            bluetoothSocket?.close()
            fileWriter?.close()
        } catch (e: IOException) {
            Log.e("Bluetooth", "Error closing Bluetooth connection", e)
        }
        readerThread?.interrupt()
        showToast("Recording stopped.")
        requireActivity().runOnUiThread {
            binding.dataStatus.text = ""
        }

        Log.d("AccelData", "Data saved to: ${outputFile?.absolutePath}")
        requireActivity().runOnUiThread {
            binding.dataStatus.text = "✅ Data saved to:\n${outputFile?.absolutePath}"
        }
    }

    private fun createOutputFile() {
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
        val fileName = "smartwatch_data_$timestamp.txt"

        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadsDir.exists()) {
            downloadsDir.mkdirs()
        }

        outputFile = File(downloadsDir, fileName)

        try {
            fileWriter = BufferedWriter(FileWriter(outputFile!!))
        } catch (e: IOException) {
            Log.e("FileIO", "Error creating file writer", e)
            showToast("Failed to create output file.")
        }
    }

    private fun showToast(msg: String) {
        activity?.runOnUiThread {
            Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopBluetoothConnection()
        _binding = null
    }
}
