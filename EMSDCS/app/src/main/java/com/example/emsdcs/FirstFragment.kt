package com.example.emsdcs

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.emsdcs.databinding.FragmentFirstBinding
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread

class FirstFragment : Fragment() {

    private var _binding: FragmentFirstBinding? = null
    private val binding get() = _binding!!

    // Data class for a smartwatch connection.
    data class SmartwatchConnection(
        val mac: String,
        var socket: BluetoothSocket? = null,
        var reader: BufferedReader? = null,
        var thread: Thread? = null
    )

    // List to hold all successful connections.
    private val connections = mutableListOf<SmartwatchConnection>()

    private var isRecording = false
    private var outputFile: File? = null
    private var fileWriter: BufferedWriter? = null

    private val REQUEST_BLUETOOTH_PERMISSIONS = 1
    private val SMARTWATCH_01_MAC = "28:3D:C2:EA:54:81"
    private val SMARTWATCH_02_MAC = "04:29:2E:DE:DD:AF"
    private val SMARTWATCH_03_MAC = "B0:4A:6A:49:5A:61"
    private val smartwatchMacs = listOf(SMARTWATCH_01_MAC, SMARTWATCH_02_MAC, SMARTWATCH_03_MAC)

    private val MY_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    // Timer variables for elapsed time display.
    private var startTime: Long = 0L
    private val timerHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (isRecording) {
                val elapsedMillis = System.currentTimeMillis() - startTime
                val seconds = (elapsedMillis / 1000) % 60
                val minutes = (elapsedMillis / 1000) / 60
                val timeString = String.format("%02d:%02d", minutes, seconds)
                requireActivity().runOnUiThread {
                    binding.elapsedTime.text = timeString
                }
                timerHandler.postDelayed(this, 1000)
            }
        }
    }

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
                stopBluetoothConnections()
                binding.buttonFirst.text = "Start Recording"
                resetSmartwatchStatuses()
            }
        }
    }

    // Resets UI elements for statuses, elapsed time, epoch timestamp, and file location.
    private fun resetSmartwatchStatuses() {
        requireActivity().runOnUiThread {
            binding.overallStatus.text = "Smartwatch Connection Status"
//            binding.elapsedTime.text = "00:00"
            binding.smartwatch1Status.text = "Smartwatch 1: Not Connected"
            binding.smartwatch1Status.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.black))
            binding.smartwatch1Data.text = "Data: --"
            binding.smartwatch2Status.text = "Smartwatch 2: Not Connected"
            binding.smartwatch2Status.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.black))
            binding.smartwatch2Data.text = "Data: --"
            binding.smartwatch3Status.text = "Smartwatch 3: Not Connected"
            binding.smartwatch3Status.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.black))
            binding.smartwatch3Data.text = "Data: --"
//            binding.fileLocation.text = "Data file: --"
            binding.epochTimestamp.text = "Epoch: --"
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
                startBluetoothConnections()
                binding.buttonFirst.text = "Stop Recording"
            }
        } else {
            startBluetoothConnections()
            binding.buttonFirst.text = "Stop Recording"
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BLUETOOTH_PERMISSIONS && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startBluetoothConnections()
            binding.buttonFirst.text = "Stop Recording"
        } else {
            showToast("Bluetooth permission denied.")
        }
    }

    /**
     * Connects to each smartwatch. For each successful connection, a dedicated thread is started
     * to continuously read data. UI elements are updated for connection status, data, disconnections,
     * epoch timestamp, and (when stopping) the saved file location.
     */
    private fun startBluetoothConnections() {
        isRecording = true
        createOutputFile()

        thread {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null || !adapter.isEnabled) {
                showToast("Bluetooth is not enabled.")
                isRecording = false
                return@thread
            }

            // Attempt connection for each smartwatch.
            for (mac in smartwatchMacs) {
                try {
                    if (ActivityCompat.checkSelfPermission(
                            requireContext(),
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        showToast("Permission missing. Cannot connect.")
                        continue
                    }
                    val device: BluetoothDevice = adapter.getRemoteDevice(mac)
                    val socket = device.createRfcommSocketToServiceRecord(MY_UUID)
                    socket.connect()
                    val reader = BufferedReader(InputStreamReader(socket.inputStream))

                    val connection = SmartwatchConnection(mac = mac, socket = socket, reader = reader)
                    connections.add(connection)

                    // Update UI to show successful connection.
                    requireActivity().runOnUiThread {
                        when (mac) {
                            SMARTWATCH_01_MAC -> {
                                binding.smartwatch1Status.text = "Smartwatch 1: Connected"
                                binding.smartwatch1Status.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_green_dark))
                            }
                            SMARTWATCH_02_MAC -> {
                                binding.smartwatch2Status.text = "Smartwatch 2: Connected"
                                binding.smartwatch2Status.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_green_dark))
                            }
                            SMARTWATCH_03_MAC -> {
                                binding.smartwatch3Status.text = "Smartwatch 3: Connected"
                                binding.smartwatch3Status.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_green_dark))
                            }
                        }
                    }

                    // Start a thread to continuously read data.
                    connection.thread = thread {
                        try {
                            while (isRecording) {
                                val line = connection.reader?.readLine() ?: break
                                Log.d("AccelData", "From $mac: $line")
                                val currentTime = System.currentTimeMillis()
                                val timestampedLine = "${currentTime},$mac,$line"
                                synchronized(fileWriter!!) {
                                    fileWriter?.write(timestampedLine)
                                    fileWriter?.newLine()
                                    fileWriter?.flush()
                                }
                                requireActivity().runOnUiThread {
                                    when (mac) {
                                        SMARTWATCH_01_MAC -> {
                                            binding.smartwatch1Data.text = "Data: $line"
                                        }
                                        SMARTWATCH_02_MAC -> {
                                            binding.smartwatch2Data.text = "Data: $line"
                                        }
                                        SMARTWATCH_03_MAC -> {
                                            binding.smartwatch3Data.text = "Data: $line"
                                        }
                                    }
                                    // Update the epoch timestamp UI with the current reading time.
                                    binding.epochTimestamp.text = "Epoch: $currentTime"
                                }
                            }
                        } catch (e: IOException) {
                            Log.e("Bluetooth", "Error reading Bluetooth data from $mac", e)
                        } finally {
                            // When the connection is lost, update the UI.
                            requireActivity().runOnUiThread {
                                when (mac) {
                                    SMARTWATCH_01_MAC -> {
                                        binding.smartwatch1Status.text = "Smartwatch 1: Disconnected"
                                        binding.smartwatch1Status.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark))
                                    }
                                    SMARTWATCH_02_MAC -> {
                                        binding.smartwatch2Status.text = "Smartwatch 2: Disconnected"
                                        binding.smartwatch2Status.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark))
                                    }
                                    SMARTWATCH_03_MAC -> {
                                        binding.smartwatch3Status.text = "Smartwatch 3: Disconnected"
                                        binding.smartwatch3Status.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark))
                                    }
                                }
                            }
                        }
                    }
                } catch (e: IOException) {
                    Log.e("Bluetooth", "Failed to connect to smartwatch with MAC: $mac", e)
                    requireActivity().runOnUiThread {
                        when (mac) {
                            SMARTWATCH_01_MAC -> {
                                binding.smartwatch1Status.text = "Smartwatch 1: Not Connected"
                                binding.smartwatch1Status.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark))
                            }
                            SMARTWATCH_02_MAC -> {
                                binding.smartwatch2Status.text = "Smartwatch 2: Not Connected"
                                binding.smartwatch2Status.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark))
                            }
                            SMARTWATCH_03_MAC -> {
                                binding.smartwatch3Status.text = "Smartwatch 3: Not Connected"
                                binding.smartwatch3Status.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark))
                            }
                        }
                        showToast("Failed to connect to smartwatch: $mac")
                    }
                }
            }

            // Update overall status and start elapsed time timer if any connection succeeded.
            requireActivity().runOnUiThread {
                if (connections.isNotEmpty()) {
                    binding.overallStatus.text = "Connected to ${connections.size} Smartwatch(es)"
                    showToast("Recording started.")
                    startTime = System.currentTimeMillis()
                    timerHandler.post(timerRunnable)
                } else {
                    isRecording = false
                    binding.overallStatus.text = "No smartwatches connected."
                    showToast("No smartwatches connected.")
                }
            }
        }
    }

    /**
     * Stops all Bluetooth connections, terminates threads, stops the elapsed time timer,
     * and updates the UI with the saved file location.
     */
    private fun stopBluetoothConnections() {
        isRecording = false
        timerHandler.removeCallbacks(timerRunnable)
        for (connection in connections) {
            try {
                connection.reader?.close()
                connection.socket?.close()
            } catch (e: IOException) {
                Log.e("Bluetooth", "Error closing connection for ${connection.mac}", e)
            }
            connection.thread?.interrupt()
        }
        connections.clear()
        try {
            fileWriter?.close()
        } catch (e: IOException) {
            Log.e("Bluetooth", "Error closing file writer", e)
        }
        // Log and update the UI with the file save location.
        Log.d("AccelData", "Data saved to: ${outputFile?.absolutePath}")
        requireActivity().runOnUiThread {
            binding.fileLocation.text = "✅ Data saved to:\n${outputFile?.absolutePath}"
        }
        showToast("Recording stopped.")
    }

    /**
     * Creates an output file (in the Downloads directory) to store smartwatch data.
     */
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
            requireActivity().runOnUiThread {
                binding.fileLocation.text = "Data file: ${outputFile?.absolutePath}"
            }
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
        stopBluetoothConnections()
        _binding = null
    }
}
