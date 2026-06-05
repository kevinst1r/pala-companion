package com.pala.one.companion

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import org.json.JSONObject
import org.jsoup.Jsoup

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "PalaOneCompanion"
        private const val PREFS_NAME = "pala_companion_prefs"
        private const val KEY_REMEMBER_CONNECT = "remember_connect_choice"
        private const val KEY_CONNECT_MODE = "connect_mode"
        private const val CONNECT_MODE_READER_WIFI = 0
        private const val CONNECT_MODE_SAME_NETWORK = 1
        private val SAME_NETWORK_BASE_URLS = listOf(
            "http://pala-one.local",
            "http://192.168.0.11"
        )
    }

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var managerUrl: String = "http://192.168.4.1/"
    private var isReaderConnected: Boolean = false
    private var isApBound: Boolean = false
    private var didAutoOpenManagerForConnection: Boolean = false

    private val managerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val shouldRestoreWifi =
                result.data?.getBooleanExtra(ManagerActivity.EXTRA_RESTORE_WIFI, false) == true
            if (shouldRestoreWifi) {
                releaseNetworkBinding()
            } else if (isReaderConnected && !isApBound) {
                isReaderConnected = false
            }
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val granted = it.values.all { value -> value }
            if (granted) {
                connectToDeviceAp()
            }
        }

    private val bookPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uri = result.data?.data ?: return@registerForActivityResult
            convertBook(uri)
        }

    private val imagePickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uri = result.data?.data ?: return@registerForActivityResult
            openImageCrop(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val connectButton: ImageButton = findViewById(R.id.connectButton)
        val convertBookButton: ImageButton = findViewById(R.id.convertBookButton)
        val convertImageButton: ImageButton = findViewById(R.id.convertImageButton)

        connectButton.setOnClickListener {
            startConnectFlow()
        }
        connectButton.setOnLongClickListener {
            showConnectOptionsDialog()
            true
        }
        convertBookButton.setOnClickListener { openBookPicker() }
        convertImageButton.setOnClickListener { openImagePicker() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isReaderConnected && isApBound) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(R.string.connected_exit_title)
                        .setMessage(R.string.connected_exit_message)
                        .setPositiveButton(R.string.restore_wifi_and_close) { _, _ ->
                            releaseNetworkBinding()
                            finish()
                        }
                        .setNegativeButton(R.string.stay_connected, null)
                        .show()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    private fun startConnectFlow() {
        val prefs = getConnectPrefs()
        if (prefs.getBoolean(KEY_REMEMBER_CONNECT, false)) {
            applyConnectMode(prefs.getInt(KEY_CONNECT_MODE, CONNECT_MODE_READER_WIFI))
            return
        }
        showConnectOptionsDialog()
    }

    private fun showConnectOptionsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_connect_options, null)
        val readerWifiOption: RadioButton = view.findViewById(R.id.connectModeReaderWifi)
        val sameNetworkOption: RadioButton = view.findViewById(R.id.connectModeSameNetwork)
        val rememberChoice: CheckBox = view.findViewById(R.id.connectRememberChoice)

        val prefs = getConnectPrefs()
        when (prefs.getInt(KEY_CONNECT_MODE, CONNECT_MODE_READER_WIFI)) {
            CONNECT_MODE_SAME_NETWORK -> sameNetworkOption.isChecked = true
            else -> readerWifiOption.isChecked = true
        }
        rememberChoice.isChecked = prefs.getBoolean(KEY_REMEMBER_CONNECT, false)

        AlertDialog.Builder(this)
            .setTitle(R.string.connect_option_title)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val mode = if (sameNetworkOption.isChecked) {
                    CONNECT_MODE_SAME_NETWORK
                } else {
                    CONNECT_MODE_READER_WIFI
                }
                if (rememberChoice.isChecked) {
                    saveConnectPreference(mode)
                } else {
                    clearConnectPreference()
                }
                applyConnectMode(mode)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyConnectMode(mode: Int) {
        when (mode) {
            CONNECT_MODE_READER_WIFI -> ensurePermissionsAndConnect()
            CONNECT_MODE_SAME_NETWORK -> connectViaSameNetwork()
        }
    }

    private fun getConnectPrefs() =
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun saveConnectPreference(mode: Int) {
        getConnectPrefs().edit()
            .putBoolean(KEY_REMEMBER_CONNECT, true)
            .putInt(KEY_CONNECT_MODE, mode)
            .apply()
    }

    private fun clearConnectPreference() {
        getConnectPrefs().edit()
            .putBoolean(KEY_REMEMBER_CONNECT, false)
            .remove(KEY_CONNECT_MODE)
            .apply()
    }

    private fun ensurePermissionsAndConnect() {
        val needed = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        val missing = needed.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            if (!isLocationEnabled()) {
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                return
            }
            connectToDeviceAp()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun isLocationEnabled(): Boolean {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return try {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (_: Exception) {
            false
        }
    }

    private fun connectToDeviceAp() {
        didAutoOpenManagerForConnection = false
        try {
            val specifier = WifiNetworkSpecifier.Builder()
                .setSsidPattern(android.os.PatternMatcher("PALA-", android.os.PatternMatcher.PATTERN_PREFIX))
                .setWpa2Passphrase("palaread")
                .build()

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build()

            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            networkCallback?.let {
                try {
                    cm.unregisterNetworkCallback(it)
                } catch (_: Exception) {
                    // Ignore unregister races; we only need the latest callback.
                }
            }

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    cm.bindProcessToNetwork(network)
                    runOnUiThread {
                        isReaderConnected = true
                        isApBound = true
                    }
                    checkDeviceApi(network)
                }

                override fun onUnavailable() {
                    runOnUiThread {
                        isReaderConnected = false
                        isApBound = false
                        didAutoOpenManagerForConnection = false
                    }
                }

                override fun onLost(network: Network) {
                    runOnUiThread {
                        isReaderConnected = false
                        isApBound = false
                        didAutoOpenManagerForConnection = false
                    }
                }
            }

            cm.requestNetwork(request, networkCallback!!)
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission/security error while requesting network", e)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Invalid network request", e)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Wi-Fi request failed due to state", e)
        }
    }

    private fun checkDeviceApi(network: Network) {
        val client = OkHttpClient.Builder()
            .socketFactory(network.socketFactory)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()

        Thread {
            try {
                val infoRequest = Request.Builder()
                    .url("http://192.168.4.1/api/info")
                    .get()
                    .build()
                client.newCall(infoRequest).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    runOnUiThread {
                        if (response.isSuccessful) {
                            managerUrl = extractManagerUrl(body)
                        }
                        if (!didAutoOpenManagerForConnection) {
                            didAutoOpenManagerForConnection = true
                            openManagerInApp(sameNetwork = false)
                        }
                    }
                }
            } catch (e: IOException) {
                runOnUiThread {
                    if (!didAutoOpenManagerForConnection) {
                        didAutoOpenManagerForConnection = true
                        openManagerInApp(sameNetwork = false)
                    }
                }
            }
        }.start()
    }

    private fun connectViaSameNetwork() {
        showToast(getString(R.string.same_network_connecting))
        Thread {
            val client = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()

            for (baseUrl in SAME_NETWORK_BASE_URLS) {
                val resolvedUrl = probeSameNetworkReader(client, baseUrl) ?: continue
                runOnUiThread {
                    isReaderConnected = true
                    isApBound = false
                    managerUrl = resolvedUrl
                    openManagerInApp(sameNetwork = true)
                }
                return@Thread
            }

            runOnUiThread {
                showToast(getString(R.string.same_network_falling_back))
                ensurePermissionsAndConnect()
            }
        }.start()
    }

    private fun probeSameNetworkReader(client: OkHttpClient, baseUrl: String): String? {
        val infoUrl = "$baseUrl/api/info"
        try {
            client.newCall(Request.Builder().url(infoUrl).get().build()).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    return extractManagerUrl(body).ifBlank { "$baseUrl/" }
                }
            }
        } catch (_: IOException) {
            // Fall through and try the manager root.
        }

        return try {
            client.newCall(Request.Builder().url("$baseUrl/").get().build()).execute().use { response ->
                if (response.isSuccessful) "$baseUrl/" else null
            }
        } catch (_: IOException) {
            null
        }
    }

    private fun extractManagerUrl(jsonBody: String): String {
        return try {
            val ip = JSONObject(jsonBody).optString("ip", "192.168.4.1")
            "http://$ip/"
        } catch (_: Exception) {
            "http://192.168.4.1/"
        }
    }

    private fun openManagerInApp(sameNetwork: Boolean) {
        try {
            val intent = Intent(this, ManagerActivity::class.java)
            intent.putExtra(ManagerActivity.EXTRA_MANAGER_URL, managerUrl)
            intent.putExtra(ManagerActivity.EXTRA_SAME_NETWORK, sameNetwork)
            managerLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open manager in app", e)
        }
    }

    private fun releaseNetworkBinding() {
        if (isApBound) {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            networkCallback?.let {
                try {
                    cm.unregisterNetworkCallback(it)
                } catch (_: Exception) {
                    // Ignore if callback is already unregistered or never registered.
                }
            }
            networkCallback = null
            cm.bindProcessToNetwork(null)
        }
        isReaderConnected = false
        isApBound = false
        didAutoOpenManagerForConnection = false
    }

    private fun openBookPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("text/plain", "application/epub+zip")
            )
        }
        bookPickerLauncher.launch(intent)
    }

    private fun openImagePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        imagePickerLauncher.launch(intent)
    }

    private fun openImageCrop(uri: Uri) {
        val intent = Intent(this, ImageCropActivity::class.java).apply {
            putExtra(ImageCropActivity.EXTRA_IMAGE_URI, uri)
        }
        startActivity(intent)
    }

    private fun convertBook(uri: Uri) {
        runCatching {
            val originalName = getDisplayName(uri).substringBeforeLast(".", getDisplayName(uri))
            val content = readBookContentFromUri(uri)
            val normalized = normalizeBookText(content)
            val outputName = "${sanitizeFileName(originalName)}.txt"
            val bytes = normalized.toByteArray(Charsets.UTF_8)
            saveBytesToDownloads(outputName, "text/plain", bytes)
            showToast("Book converted (${formatSize(bytes.size.toLong())})")
        }.onFailure { error ->
            Log.e(TAG, "Book conversion failed", error)
            showToast("Book conversion failed")
        }
    }

    private fun readTextFromUri(uri: Uri): String {
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot read selected file." }
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                return reader.readText()
            }
        }
    }

    private fun readBookContentFromUri(uri: Uri): String {
        val mimeType = contentResolver.getType(uri).orEmpty().lowercase(Locale.US)
        val name = getDisplayName(uri).lowercase(Locale.US)
        val isEpub = mimeType == "application/epub+zip" || name.endsWith(".epub")
        return if (isEpub) {
            extractEpubText(uri)
        } else {
            readTextFromUri(uri)
        }
    }

    private fun extractEpubText(uri: Uri): String {
        val htmlParts = mutableListOf<Pair<String, String>>()
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot read selected EPUB file." }
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val lowerName = entry.name.lowercase(Locale.US)
                        if (
                            lowerName.endsWith(".xhtml") ||
                            lowerName.endsWith(".html") ||
                            lowerName.endsWith(".htm")
                        ) {
                            val html = zip.readBytes().toString(Charsets.UTF_8)
                            val text = Jsoup.parse(html).text()
                            if (text.isNotBlank()) {
                                htmlParts.add(entry.name to text)
                            }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        require(htmlParts.isNotEmpty()) { "No readable text found in EPUB." }
        return htmlParts
            .sortedBy { it.first }
            .joinToString("\n\n") { it.second }
    }

    private fun normalizeBookText(input: String): String {
        val unified = input.replace("\r\n", "\n").replace('\r', '\n')
        val normalizedLines = unified.split('\n').map { line -> line.trimEnd() }
        val compacted = StringBuilder()
        var blankRun = 0
        normalizedLines.forEachIndexed { index, line ->
            val isBlank = line.isBlank()
            if (isBlank) {
                blankRun += 1
            } else {
                blankRun = 0
            }
            if (!isBlank || blankRun <= 2) {
                compacted.append(line)
                if (index < normalizedLines.lastIndex) compacted.append('\n')
            }
        }
        return compacted.toString().trim('\n')
    }

    private fun getDisplayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null).use { cursor ->
            if (cursor != null && cursor.moveToFirst()) {
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameColumn >= 0) {
                    return cursor.getString(nameColumn)
                }
            }
        }
        return "converted_file"
    }

    private fun sanitizeFileName(raw: String): String {
        return raw.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "converted_file" }
    }

    private fun formatSize(sizeBytes: Long): String {
        if (sizeBytes < 1024) return "$sizeBytes B"
        val kb = sizeBytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        return String.format(Locale.US, "%.1f MB", mb)
    }

    private fun showToast(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        releaseNetworkBinding()
        super.onDestroy()
    }
}
