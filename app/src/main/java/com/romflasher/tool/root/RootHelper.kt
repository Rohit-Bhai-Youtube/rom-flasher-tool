package com.romflasher.tool.root

import com.romflasher.tool.ui.tr

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Which rooting tool the user picked. */
enum class RootKind(val label: String, val apiUrl: String) {
    KERNELSU("KernelSU", "https://api.github.com/repos/tiann/KernelSU/releases/latest"),
    MAGISK("Magisk", "https://api.github.com/repos/topjohnwu/Magisk/releases/latest"),
    APATCH("APatch", "https://api.github.com/repos/bmax121/APatch/releases/latest"),
    SUKISU("SukiSU Ultra", "https://api.github.com/repos/SukiSU-Ultra/SukiSU-Ultra/releases/latest"),

    /**
     * The user brings their own root app (SukiSU Ultra, APatch, a KernelSU fork, …).
     * Nothing is downloaded from GitHub — the wizard asks for the APK and then runs
     * the exact same install → patch → flash flow as the other two.
     */
    SELF("My Own Root File", "")
}

/** A file the wizard needs the user to pick from storage. */
enum class RootFileRequest {
    INIT_BOOT_IMG,   // stock init_boot.img (fallback when the device can't be dumped)
    PATCHED_IMG,     // the patched image produced on the target phone
    ROOT_APK,        // the KernelSU / Magisk APK (fallback when GitHub download fails)
    RECOVERY_IMG     // a TWRP / OrangeFox recovery .img to temporarily boot (generic devices)
}

/** Which ROM family the target currently runs — selects the matching recovery build. */
enum class RecoveryVariant(val label: String, val assetTag: String) {
    HOS("HyperOS / Custom Modded ROM", "HOS"),
    AOSP("AOSP Based ROM", "AOSP")
}

/** OrangeFox ships both an AVB-enabled and an AVB-disabled image per ROM family. */
enum class AvbVariant(val assetTag: String) {
    DISABLED("AVB-DISABLED"),
    ENABLED("AVB-ENABLED")
}

/** A resolved downloadable recovery image (name + direct URL). */
data class RecoveryAsset(val name: String, val url: String)

/**
 * Small, dependency-free helper that resolves the latest release asset for
 * KernelSU / Magisk on GitHub and streams the APK to the host phone.
 */
object RootHelper {

    /**
     * OrangeFox recovery repository for the POCO X7 Pro / Redmi Turbo 4 ("rodin").
     * We only ever BOOT this image temporarily (`fastboot boot`), never flash it.
     */
    private const val ORANGEFOX_RODIN_RELEASES =
        "https://api.github.com/repos/NEESCHAL-3/OrangeFox-rodin-source/releases?per_page=8"

    /** Downloads the newest APK asset of [kind] into [outDir] and returns it. */
    fun downloadLatestApk(kind: RootKind, outDir: File, onLog: (String) -> Unit): File? {
        onLog("→ GitHub: ${kind.apiUrl}")
        val json = httpGetText(kind.apiUrl)
        if (json == null) {
            onLog(tr("[ERROR] No response from the GitHub API (check your internet).", "[ERROR] GitHub API se response nahi mila (internet check karo)."))
            return null
        }
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            onLog("[ERROR] Release JSON parse fail: ${e.message}")
            return null
        }
        root.optString("tag_name").takeIf { it.isNotEmpty() }?.let {
            onLog("[i] Latest ${kind.label} release: $it")
        }

        val assets = root.optJSONArray("assets")
        if (assets == null || assets.length() == 0) {
            onLog(tr("[ERROR] No asset found in the release.", "[ERROR] Release me koi asset nahi mila."))
            return null
        }

        var url: String? = null
        var name: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val n = a.optString("name")
            if (n.endsWith(".apk", ignoreCase = true)) {
                url = a.optString("browser_download_url")
                name = n
                // Prefer standard release apk (exclude spoofed if clean release exists)
                if (n.contains("release", ignoreCase = true) && !n.contains("spoofed", ignoreCase = true)) break
            }
        }
        if (url.isNullOrEmpty() || name.isNullOrEmpty()) {
            onLog(tr("[ERROR] No .apk asset found in the GitHub release.", "[ERROR] GitHub release me koi .apk asset nahi mila."))
            return null
        }

        outDir.mkdirs()
        val out = File(outDir, name)
        if (out.exists() && out.length() > 0L) {
            onLog("[OK] Cached asset ready: $name (${out.length() / 1024} KB)")
            return out
        }
        onLog("[i] Asset: $name")
        val ok = httpDownload(url, out) { msg -> onLog(msg) }
        if (!ok || out.length() <= 0L) {
            onLog(tr("[ERROR] APK download failed.", "[ERROR] APK download fail ho gaya."))
            return null
        }
        return out
    }

    /**
     * Resolves and downloads the OrangeFox recovery for the POCO X7 Pro / Redmi
     * Turbo 4 ("rodin") that matches the installed ROM family. The image is only
     * ever temporarily BOOTed (`fastboot boot`), never flashed, so it is safe on a
     * locked-again stock device as long as the bootloader is unlocked.
     *
     * Returns the local .img file, or null on any failure (the caller falls back
     * to a manual recovery/init_boot selection).
     */
    fun downloadRodinRecovery(
        variant: RecoveryVariant,
        avb: AvbVariant,
        outDir: File,
        onLog: (String) -> Unit
    ): File? {
        onLog(tr("→ GitHub: looking for OrangeFox rodin releases…", "→ GitHub: OrangeFox rodin releases dhoondh rahe hain…"))
        val json = httpGetText(ORANGEFOX_RODIN_RELEASES)
        if (json == null) {
            onLog(tr("[ERROR] No response from the GitHub API (check your internet).", "[ERROR] GitHub API se response nahi mila (internet check karo)."))
            return null
        }
        val asset = resolveRecoveryAsset(json, variant, avb, onLog) ?: return null
        onLog("[i] Recovery asset: ${asset.name}")
        val out = File(outDir, asset.name)
        if (out.exists() && out.length() > 0L) {
            onLog("[OK] Cached recovery ready: ${out.name} (${out.length() / 1048576} MB)")
            return out
        }
        onLog("→ Downloading ${asset.name}…")
        if (!httpDownload(asset.url, out, onLog)) {
            onLog(tr("[ERROR] Recovery download failed.", "[ERROR] Recovery download fail ho gaya."))
            return null
        }
        onLog("[OK] Recovery ready: ${out.name} (${out.length() / 1048576} MB)")
        return out
    }

    /** Parses the releases JSON and returns the best matching recovery asset. */
    private fun resolveRecoveryAsset(
        json: String,
        variant: RecoveryVariant,
        avb: AvbVariant,
        onLog: (String) -> Unit
    ): RecoveryAsset? {
        val releases = try {
            org.json.JSONArray(json)
        } catch (e: Exception) {
            onLog("[ERROR] Releases JSON parse fail: ${e.message}")
            return null
        }
        // Pass 1: newest release that has the exact ROM-family + AVB image.
        for (pass in 0..1) {
            for (i in 0 until releases.length()) {
                val rel = releases.optJSONObject(i) ?: continue
                val assets = rel.optJSONArray("assets") ?: continue
                for (j in 0 until assets.length()) {
                    val a = assets.optJSONObject(j) ?: continue
                    val n = a.optString("name")
                    if (!n.endsWith(".img", ignoreCase = true)) continue
                    if (!n.contains("rodin", ignoreCase = true)) continue
                    if (!n.contains(variant.assetTag, ignoreCase = true)) continue
                    // pass 0 wants the AVB split; pass 1 accepts older single-image builds.
                    if (pass == 0 && !n.contains(avb.assetTag, ignoreCase = true)) continue
                    val url = a.optString("browser_download_url")
                    if (url.isBlank()) continue
                    return RecoveryAsset(n, url)
                }
            }
        }
        onLog(tr("[ERROR] ${variant.label} recovery asset not found (check the repo).", "[ERROR] ${variant.label} recovery asset nahi mila (repo check karo)."))
        return null
    }

    // ---------------------------------------------------------------- networking

    private fun httpGetText(urlStr: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 15000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "ROMFlasher-Tool")
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            if (conn.responseCode != 200) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun httpDownload(urlStr: String, out: File, onLog: (String) -> Unit): Boolean {
        val tmp = File(out.parentFile ?: out.absoluteFile.parentFile, "${out.name}.part")
        var current = urlStr
        var redirects = 0
        while (redirects < 6) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(current).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20000
                    readTimeout = 30000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "ROMFlasher-Tool")
                    setRequestProperty("Accept", "application/octet-stream")
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location")
                    if (loc.isNullOrBlank()) return false
                    current = if (loc.startsWith("http")) loc else URL(URL(current), loc).toString()
                    redirects++
                    continue
                }
                if (code != 200) {
                    onLog("[ERROR] HTTP $code while downloading.")
                    return false
                }
                val total = conn.contentLengthLong
                var done = 0L
                conn.inputStream.use { input ->
                    FileOutputStream(tmp).use { output ->
                        val buf = ByteArray(64 * 1024)
                        var lastLog = 0L
                        while (true) {
                            val read = input.read(buf)
                            if (read <= 0) break
                            output.write(buf, 0, read)
                            done += read
                            val now = System.currentTimeMillis()
                            if (now - lastLog > 800) {
                                lastLog = now
                                if (total > 0) onLog("[i] ${done / 1048576} / ${total / 1048576} MB")
                                else onLog("[i] ${done / 1048576} MB")
                            }
                        }
                    }
                }
                if (done <= 0L || (total > 0L && done != total)) {
                    onLog("[ERROR] Incomplete download ($done / $total bytes). Discarding partial file.")
                    try { tmp.delete() } catch (_: Exception) {}
                    return false
                }
                if (out.exists()) out.delete()
                if (!tmp.renameTo(out)) {
                    tmp.copyTo(out, overwrite = true)
                    tmp.delete()
                }
                return out.exists() && out.length() == done
            } catch (e: Exception) {
                try { tmp.delete() } catch (_: Exception) {}
                onLog("[ERROR] Download failed: ${e.message}")
                return false
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }
        }
        return false
    }
}
