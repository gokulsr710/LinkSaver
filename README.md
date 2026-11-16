# LinkSaver — Android video & image downloader

Paste (or share) a **YouTube, Instagram, Facebook or LinkedIn** link →
save it as **MP4** (to `Movies/LinkSaver`) or save the **image** (to `Pictures/LinkSaver`).

Powered by yt-dlp + FFmpeg via [youtubedl-android](https://github.com/yausername/youtubedl-android).

## Get the APK (no Android Studio needed)

1. Create a new repository on GitHub (private is fine).
2. Upload this whole folder to it (GitHub web: **Add file → Upload files**, drag everything in,
   including the hidden `.github` folder — or use `git push`).
3. Open the **Actions** tab → **Build APK** → it runs automatically (or press **Run workflow**).
4. After ~5–8 min, open the finished run → **Artifacts → LinkSaver-apk** → download the zip.
5. Inside are 3 APKs. Install **`app-arm64-v8a-release.apk`** on almost any modern phone
   (use `armeabi-v7a` only for very old/cheap phones).
6. On the phone, allow "Install unknown apps" for your file manager/browser when asked.

## Or build in Android Studio

Open the folder → let Gradle sync → **Build → Build APK(s)**.

## Using it

- Paste a link and tap **Download MP4** or **Download image**, or
- In YouTube/Instagram/Facebook/LinkedIn tap **Share → LinkSaver**, then tap a button.
- If a site stops working, tap **Update engine** (pulls the latest yt-dlp).

## Limits

- Only **public** posts. Private posts, stories and login-only content will fail.
- Image mode: saves the photo of a picture post (via the page's preview image) or the
  thumbnail of a video. Instagram carousels may give only the first image.
- APKs from different builds use different signing keys — uninstall the old one before
  installing a newer build.
- Respect copyright and each platform's terms; download only content you're allowed to.
