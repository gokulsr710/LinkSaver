package com.linksaver.app

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var urlInput: TextInputEditText
    private lateinit var qualityGroup: RadioGroup
    private lateinit var btnVideo: Button
    private lateinit var btnImage: Button
    private lateinit var btnCancel: Button
    private lateinit var btnPaste: Button
    private lateinit var btnUpdate: Button
    private lateinit var progress: LinearProgressIndicator
    private lateinit var status: TextView
    private lateinit var log: TextView

    private val urlRegex = Regex("""https?://\S+""")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlInput = findViewById(R.id.urlInput)
        qualityGroup = findViewById(R.id.qualityGroup)
        btnVideo = findViewById(R.id.btnVideo)
        btnImage = findViewById(R.id.btnImage)
        btnCancel = findViewById(R.id.btnCancel)
        btnPaste = findViewById(R.id.btnPaste)
        btnUpdate = findViewById(R.id.btnUpdate)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        log = findViewById(R.id.log)

        btnPaste.setOnClickListener { pasteFromClipboard() }
        btnVideo.setOnClickListener { start(video = true) }
        btnImage.setOnClickListener { start(video = false) }
        btnCancel.setOnClickListener { Downloader.cancel(); status.text = getString(R.string.cancelled) }
        btnUpdate.setOnClickListener { updateEngine() }

        handleShare(intent)
        warmUp()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            urlRegex.find(text)?.let { urlInput.setText(it.value) }
        }
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        val url = urlRegex.find(text)?.value
        if (url != null) urlInput.setText(url)
        else Toast.makeText(this, R.string.no_link_in_clipboard, Toast.LENGTH_SHORT).show()
    }

    /** Unpacks Python/yt-dlp on first launch so the first download starts faster. */
    private fun warmUp() {
        lifecycleScope.launch {
            status.text = getString(R.string.preparing)
            val err = withContext(Dispatchers.IO) {
                try { Downloader.init(applicationContext); null } catch (e: Exception) { e.message }
            }
            status.text = err?.let { "Engine error: $it" } ?: getString(R.string.ready)
        }
    }

    private fun selectedHeight(): Int? = when (qualityGroup.checkedRadioButtonId) {
        R.id.q1080 -> 1080
        R.id.q720 -> 720
        R.id.q480 -> 480
        else -> null
    }

    private fun needsLegacyPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED

    private fun start(video: Boolean) {
        val url = urlRegex.find(urlInput.text?.toString().orEmpty())?.value
        if (url == null) {
            urlInput.error = getString(R.string.enter_link)
            return
        }
        if (needsLegacyPermission()) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1
            )
            return
        }

        val height = selectedHeight()
        setBusy(true)
        log.text = ""
        status.text = if (video) getString(R.string.fetching_video) else getString(R.string.fetching_image)

        lifecycleScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    val cb: (Float, String) -> Unit = { p, line -> runOnUiThread { onProgress(p, line) } }
                    if (video) Downloader.downloadVideo(applicationContext, url, height, cb)
                    else Downloader.downloadImage(applicationContext, url, cb)
                }
                status.text = getString(R.string.saved, saved.joinToString("\n"))
                Toast.makeText(this@MainActivity, R.string.saved_toast, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                status.text = friendlyError(e)
            } finally {
                setBusy(false)
            }
        }
    }

    private fun onProgress(p: Float, line: String) {
        if (p > 0f) {
            progress.isIndeterminate = false
            progress.setProgressCompat(p.toInt().coerceIn(0, 100), true)
        }
        if (line.isNotBlank()) {
            val lines = (log.text.toString() + "\n" + line).lines().takeLast(6)
            log.text = lines.joinToString("\n").trim()
        }
    }

    private fun friendlyError(e: Exception): String {
        val msg = e.message.orEmpty()
        return when {
            "login" in msg.lowercase() || "private" in msg.lowercase() ->
                "This post needs a login or is private. Only public posts can be saved."
            "Unsupported URL" in msg -> "This link isn't supported."
            "Canceled" in msg || e.javaClass.simpleName.contains("Cancel") -> getString(R.string.cancelled)
            else -> "Failed: " + msg.lines().lastOrNull { it.isNotBlank() }.orEmpty().take(300) +
                "\n\nTip: tap 'Update engine' — sites change often."
        }
    }

    private fun updateEngine() {
        setBusy(true)
        status.text = getString(R.string.updating)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try { "Engine update: " + Downloader.updateEngine(applicationContext) }
                catch (e: Exception) { "Update failed: ${e.message}" }
            }
            status.text = result
            setBusy(false)
        }
    }

    private fun setBusy(busy: Boolean) {
        btnVideo.isEnabled = !busy
        btnImage.isEnabled = !busy
        btnUpdate.isEnabled = !busy
        btnCancel.visibility = if (busy) View.VISIBLE else View.GONE
        progress.visibility = if (busy) View.VISIBLE else View.INVISIBLE
        if (busy) progress.isIndeterminate = true
    }
}
