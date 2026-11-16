package com.linksaver.app

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** All blocking work. Call from a background thread. */
object Downloader {

    const val PROCESS_ID = "linksaver-job"
    private const val FOLDER = "LinkSaver"

    // Crawler user agent: Instagram / Facebook / LinkedIn serve preview tags (og:image) to it
    private const val PREVIEW_UA = "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)"

    private val VIDEO_EXT = setOf("mp4", "mkv", "webm", "mov", "m4v")
    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp")

    @Volatile private var ready = false

    @Synchronized
    fun init(ctx: Context) {
        if (ready) return
        YoutubeDL.getInstance().init(ctx.applicationContext)
        FFmpeg.getInstance().init(ctx.applicationContext)
        ready = true
    }

    fun updateEngine(ctx: Context): String {
        init(ctx)
        val status = YoutubeDL.getInstance().updateYoutubeDL(ctx.applicationContext)
        return status?.toString() ?: "done"
    }

    fun cancel() {
        try { YoutubeDL.getInstance().destroyProcessById(PROCESS_ID) } catch (_: Exception) {}
    }

    private fun freshDir(ctx: Context): File =
        File(ctx.cacheDir, "job_${System.currentTimeMillis()}").apply { mkdirs() }

    private fun outTemplate(dir: File) = dir.absolutePath + "/%(title).60B [%(id)s].%(ext)s"

    /** maxHeight = null → best available quality. Returns saved file names. */
    fun downloadVideo(
        ctx: Context,
        url: String,
        maxHeight: Int?,
        onProgress: (Float, String) -> Unit
    ): List<String> {
        init(ctx)
        val dir = freshDir(ctx)
        val h = maxHeight
        val format = if (h == null) {
            "bv*[ext=mp4]+ba[ext=m4a]/b[ext=mp4]/bv*+ba/b"
        } else {
            "bv*[height<=$h][ext=mp4]+ba[ext=m4a]/b[height<=$h][ext=mp4]/bv*[height<=$h]+ba/b[height<=$h]/b"
        }
        val req = YoutubeDLRequest(url).apply {
            addOption("-f", format)
            addOption("--merge-output-format", "mp4")
            addOption("--remux-video", "mp4")
            addOption("--no-playlist")
            addOption("--no-mtime")
            addOption("-o", outTemplate(dir))
        }
        YoutubeDL.getInstance().execute(req, PROCESS_ID) { progress, _, line ->
            onProgress(progress, line)
        }
        val files = dir.listFiles().orEmpty().filter { it.extension.lowercase() in VIDEO_EXT }
        if (files.isEmpty()) throw IllegalStateException("No video was found at this link.")
        val saved = files.map { saveToGallery(ctx, it, isVideo = true) }
        dir.deleteRecursively()
        return saved
    }

    /** Saves the post's image (Instagram/Facebook/LinkedIn photo) or the video's thumbnail. */
    fun downloadImage(ctx: Context, url: String, onProgress: (Float, String) -> Unit): List<String> {
        init(ctx)
        val dir = freshDir(ctx)

        // 1) Try yt-dlp thumbnails (works for YouTube and most video posts)
        try {
            val req = YoutubeDLRequest(url).apply {
                addOption("--skip-download")
                addOption("--write-thumbnail")
                addOption("--convert-thumbnails", "jpg")
                addOption("--no-playlist")
                addOption("-o", outTemplate(dir))
            }
            YoutubeDL.getInstance().execute(req, PROCESS_ID) { p, _, line -> onProgress(p, line) }
        } catch (e: Exception) {
            onProgress(0f, "yt-dlp found no thumbnail, trying page preview image…")
        }

        var files = dir.listFiles().orEmpty().filter { it.extension.lowercase() in IMAGE_EXT }

        // 2) Fallback: read the page's og:image (photo posts on Instagram / Facebook / LinkedIn)
        if (files.isEmpty()) {
            val imgUrl = findPreviewImage(url)
                ?: throw IllegalStateException("No image found. The post may be private or need a login.")
            onProgress(50f, "Downloading image…")
            files = listOf(httpDownload(imgUrl, dir))
        }

        val saved = files.map { saveToGallery(ctx, it, isVideo = false) }
        dir.deleteRecursively()
        return saved
    }

    // ---------- helpers ----------

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", PREVIEW_UA)
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        }

    private fun findPreviewImage(pageUrl: String): String? {
        val html = open(pageUrl).inputStream.bufferedReader().use { it.readText() }
        val patterns = listOf(
            Regex("""<meta[^>]+property=["']og:image(?::secure_url)?["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+property=["']og:image["']""", RegexOption.IGNORE_CASE),
            Regex("""<meta[^>]+name=["']twitter:image["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        )
        for (p in patterns) {
            val m = p.find(html) ?: continue
            return m.groupValues[1]
                .replace("&amp;", "&")
                .replace("&#x2F;", "/")
                .replace("&#039;", "'")
        }
        return null
    }

    private fun httpDownload(url: String, dir: File): File {
        val conn = open(url)
        val type = conn.contentType ?: ""
        val ext = when {
            "png" in type -> "png"
            "webp" in type -> "webp"
            else -> "jpg"
        }
        val out = File(dir, "image_${System.currentTimeMillis()}.$ext")
        conn.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
        return out
    }

    private fun mimeOf(f: File): String = when (f.extension.lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }

    /** Copies into Movies/LinkSaver or Pictures/LinkSaver so it shows up in the Gallery. */
    private fun saveToGallery(ctx: Context, file: File, isVideo: Boolean): String {
        val mime = mimeOf(file)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = ctx.contentResolver
            val collection = if (isVideo)
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val baseDir = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$baseDir/$FOLDER")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values)
                ?: throw IllegalStateException("Could not create file in gallery")
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return "$baseDir/$FOLDER/${file.name}"
        } else {
            @Suppress("DEPRECATION")
            val base = Environment.getExternalStoragePublicDirectory(
                if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
            )
            val target = File(base, FOLDER).apply { mkdirs() }
            val dest = File(target, file.name)
            file.copyTo(dest, overwrite = true)
            MediaScannerConnection.scanFile(ctx, arrayOf(dest.absolutePath), arrayOf(mime), null)
            return dest.absolutePath
        }
    }
}
