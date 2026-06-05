package com.pala.one.companion

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

class ManagerActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_MANAGER_URL = "extra_manager_url"
        const val EXTRA_RESTORE_WIFI = "extra_restore_wifi"
        const val EXTRA_SAME_NETWORK = "extra_same_network"
    }

    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = filePathCallback ?: return@registerForActivityResult
            filePathCallback = null
            if (result.resultCode != Activity.RESULT_OK) {
                callback.onReceiveValue(null)
                return@registerForActivityResult
            }
            val dataIntent = result.data
            val selectedUris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, dataIntent)
            callback.onReceiveValue(selectedUris)
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manager)

        val root = findViewById<View>(R.id.managerRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutout = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            view.updatePadding(
                maxOf(systemBars.left, cutout.left),
                maxOf(systemBars.top, cutout.top),
                maxOf(systemBars.right, cutout.right),
                maxOf(systemBars.bottom, cutout.bottom, ime.bottom)
            )
            windowInsets
        }
        ViewCompat.requestApplyInsets(root)

        webView = findViewById(R.id.managerWebView)
        val restoreWifiButton: ImageButton = findViewById(R.id.restoreWifiButton)
        val managerUrl = intent.getStringExtra(EXTRA_MANAGER_URL) ?: "http://192.168.4.1/"
        val sameNetwork = intent.getBooleanExtra(EXTRA_SAME_NETWORK, false)
        if (sameNetwork) {
            restoreWifiButton.visibility = View.GONE
        }

        webView.webViewClient = WebViewClient()
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                this@ManagerActivity.filePathCallback?.onReceiveValue(null)
                this@ManagerActivity.filePathCallback = filePathCallback
                return try {
                    val chooserIntent: Intent = fileChooserParams.createIntent()
                    fileChooserLauncher.launch(chooserIntent)
                    true
                } catch (_: Exception) {
                    this@ManagerActivity.filePathCallback = null
                    false
                }
            }
        }
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        webView.loadUrl(managerUrl)

        restoreWifiButton.setOnClickListener {
            setResult(
                Activity.RESULT_OK,
                intent.putExtra(EXTRA_RESTORE_WIFI, true)
            )
            finish()
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        super.onDestroy()
    }
}
