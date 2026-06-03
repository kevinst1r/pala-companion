package com.pala.one.companion

import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import kotlin.math.max

class ImageCropActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IMAGE_URI = "extra_image_uri"
        private const val TAG = "ImageCropActivity"
    }

    private lateinit var cropView: CropImageView
    private lateinit var fileNameInput: TextInputEditText
    private var sourceUri: Uri? = null
    private var baseDisplayName: String = "image"

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_crop)

        val root: View = findViewById(R.id.cropRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutout = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            view.updatePadding(
                max(systemBars.left, cutout.left),
                max(systemBars.top, cutout.top),
                max(systemBars.right, cutout.right),
                maxOf(systemBars.bottom, cutout.bottom, ime.bottom)
            )
            windowInsets
        }
        ViewCompat.requestApplyInsets(root)

        val uri = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_IMAGE_URI, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_IMAGE_URI)
        }
        if (uri == null) {
            Toast.makeText(this, R.string.image_crop_missing_uri, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        sourceUri = uri
        baseDisplayName = getDisplayName(uri).substringBeforeLast(".", getDisplayName(uri))
        fileNameInput = findViewById(R.id.cropFileNameInput)
        fileNameInput.setText(baseDisplayName)

        val toolbar: MaterialToolbar = findViewById(R.id.cropToolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_reset_fit -> {
                    cropView.resetToFit()
                    true
                }
                else -> false
            }
        }
        toolbar.inflateMenu(R.menu.menu_image_crop)

        cropView = findViewById(R.id.cropImageView)
        val toleranceLabel: TextView = findViewById(R.id.cropToleranceLabel)
        val toleranceSlider: Slider = findViewById(R.id.cropToleranceSlider)
        fun updateToleranceLabel(offset: Int) {
            val signPart = if (offset > 0) "+$offset" else offset.toString()
            val labelText = getString(R.string.image_crop_black_tolerance_label, signPart)
            val clickableValue = SpannableString(labelText)
            val valueStart = labelText.indexOf(signPart)
            if (valueStart >= 0) {
                clickableValue.setSpan(
                    object : ClickableSpan() {
                        override fun onClick(widget: View) {
                            showToleranceInputDialog(
                                currentOffset = offset,
                                onSubmit = { updated ->
                                    cropView.blackToleranceOffsetPercent = updated
                                    toleranceSlider.value = updated.toFloat()
                                    updateToleranceLabel(updated)
                                }
                            )
                        }
                    },
                    valueStart,
                    valueStart + signPart.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                toleranceLabel.text = clickableValue
                toleranceLabel.movementMethod = LinkMovementMethod.getInstance()
            } else {
                toleranceLabel.text = labelText
            }
        }
        updateToleranceLabel(cropView.blackToleranceOffsetPercent)
        toleranceSlider.value = cropView.blackToleranceOffsetPercent.toFloat()
        toleranceSlider.addOnChangeListener { _, value, _ ->
            val offset = value.toInt()
            cropView.blackToleranceOffsetPercent = offset
            updateToleranceLabel(offset)
        }

        val saveButton: ImageButton = findViewById(R.id.cropSaveButton)
        val invertButton: ImageButton = findViewById(R.id.cropInvertButton)
        val rotateButton: ImageButton = findViewById(R.id.cropRotateButton)
        saveButton.setOnClickListener { saveCroppedPacked() }
        invertButton.setOnClickListener { cropView.invertOneBit = !cropView.invertOneBit }
        rotateButton.setOnClickListener { cropView.rotateClockwise() }

        runCatching {
            val source = ImageDecoder.createSource(contentResolver, uri)
            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.isMutableRequired = false
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            cropView.bitmap = bitmap
        }.onFailure { e ->
            Log.e(TAG, "Failed to decode image", e)
            Toast.makeText(this, R.string.image_crop_decode_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveCroppedPacked() {
        if (cropView.bitmap == null) {
            Toast.makeText(this, R.string.image_crop_no_bitmap, Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            val preview = cropView.exportSleepSizedBitmap()
            try {
                SleepImageConverter.thresholdToOneBitInPlace(
                    preview,
                    cropView.blackToleranceOffsetPercent
                )
                if (cropView.invertOneBit) {
                    SleepImageConverter.invertOneBitInPlace(preview)
                }
                val bytes = SleepImageConverter.bitmapToPackedSleepImageBytes(
                    preview,
                    cropView.blackToleranceOffsetPercent
                )
                val requestedName = fileNameInput.text?.toString().orEmpty().trim()
                val outputBaseName = if (requestedName.isBlank()) baseDisplayName else requestedName
                val outputName = "${sanitizeFileName(outputBaseName)}_250x122_lsb.bin"
                saveBytesToDownloads(outputName, "application/octet-stream", bytes)
                Toast.makeText(
                    this,
                    getString(R.string.image_crop_saved, bytes.size),
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            } finally {
                if (!preview.isRecycled) {
                    preview.recycle()
                }
            }
        }.onFailure { e ->
            Log.e(TAG, "Save failed", e)
            Toast.makeText(this, R.string.image_crop_save_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun getDisplayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            .use { cursor ->
                if (cursor != null && cursor.moveToFirst()) {
                    val nameColumn = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
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

    private fun showToleranceInputDialog(currentOffset: Int, onSubmit: (Int) -> Unit) {
        val input = EditText(this).apply {
            setText(currentOffset.toString())
            setSelection(text.length)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.image_crop_black_tolerance_dialog_title)
            .setMessage(R.string.image_crop_black_tolerance_dialog_message)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val entered = input.text?.toString()?.trim()?.toIntOrNull()
                if (entered == null) {
                    Toast.makeText(this, R.string.image_crop_black_tolerance_invalid, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                onSubmit(entered.coerceIn(-100, 100))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
