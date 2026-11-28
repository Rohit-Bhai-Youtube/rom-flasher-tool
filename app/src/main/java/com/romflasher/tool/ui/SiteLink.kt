package com.romflasher.tool.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.romflasher.tool.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * The written half of the channel: every video has its own page on the site with
 * step-by-step text, direct downloads, and copy-paste commands so nobody has to
 * pause and transcribe off a phone screen.
 */
private const val SITE_URL = "https://romflasher.in"
private const val GITHUB_REPO_URL = "https://github.com/romflasher3419-spec/rom-flasher-tool"

/** Opens the community guides portal in whatever browser the user prefers. */
fun openRomFlasherSite(context: Context) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(SITE_URL)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
    }
}

/** Opens the official GitHub repository for ROM Flasher Tool. */
fun openGitHubRepo(context: Context) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_REPO_URL)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
    }
}

/** Small footer pill that opens the guides site. */
@Composable
fun WebsitePill(
    modifier: Modifier = Modifier,
    label: String = tr("Guides", "Guides")
) {
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .liquidGlass(
                glowColor = Color(0xFF6C63FF),
                shape = RoundedCornerShape(14.dp),
                borderWidth = 1.2.dp
            )
            .clickable { openRomFlasherSite(context) }
            .padding(horizontal = 6.dp, vertical = 7.dp)
    ) {
        Icon(
            imageVector = Icons.Rounded.Public,
            contentDescription = null,
            tint = Color(0xFF9C93FF),
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFFD5D7DE),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Small golden footer pill that allows users to star the project on GitHub with 1 tap. */
@Composable
fun GitHubStarPill(
    modifier: Modifier = Modifier,
    label: String = tr("Star on GitHub", "Star on GitHub")
) {
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .liquidGlass(
                glowColor = Color(0xFFFACC15),
                shape = RoundedCornerShape(14.dp),
                borderWidth = 1.2.dp
            )
            .clickable { openGitHubRepo(context) }
            .padding(horizontal = 6.dp, vertical = 7.dp)
    ) {
        Icon(
            imageVector = Icons.Rounded.Star,
            contentDescription = null,
            tint = Color(0xFFFACC15),
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFFEF08A),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private const val GH_USER = "Rohit-Bhai-Youtube"
private const val GH_REPOS_API = "https://api.github.com/users/$GH_USER/repos?sort=updated&per_page=50"
private const val GH_PROFILE_URL = "https://github.com/$GH_USER"

data class GitHubProjectItem(
    val name: String,
    val title: String,
    val description: String,
    val language: String,
    val stars: Int,
    val url: String
)

private val FALLBACK_GH_PROJECTS = listOf(
    GitHubProjectItem(
        name = "ROM_Flasher_Tool_For_PC",
        title = "ROM Flasher Tool (PC)",
        description = "1-Click Fastboot & ARB Bypass, Wireless ADB, Mi Unlock, KernelSU/Magisk/APatch Root & EDL Unbrick.",
        language = "Python",
        stars = 1,
        url = "https://github.com/Rohit-Bhai-Youtube/ROM_Flasher_Tool_For_PC"
    ),
    GitHubProjectItem(
        name = "rom-flasher-tool",
        title = "ROM Flasher Tool (Android)",
        description = "Flash Fastboot & Custom ROMs Android-to-Android over USB-OTG with zero PC.",
        language = "Kotlin",
        stars = 29,
        url = "https://github.com/Rohit-Bhai-Youtube/rom-flasher-tool"
    ),
    GitHubProjectItem(
        name = "rohit-bhai-1-click-mac-os",
        title = "1-Click macOS for Windows 11",
        description = "Native macOS transformation suite for Windows 11 with Liquid Glass Top Bar & Centered Dock.",
        language = "PowerShell",
        stars = 0,
        url = "https://github.com/Rohit-Bhai-Youtube/rohit-bhai-1-click-mac-os"
    ),
    GitHubProjectItem(
        name = "adb-fastboot-driver-toolkit",
        title = "ADB & Fastboot Driver Toolkit",
        description = "1-Line Web Installer for Platform Tools, WinUSB, Samsung, MTK BROM & Qualcomm 9008 drivers.",
        language = "PowerShell",
        stars = 1,
        url = "https://github.com/Rohit-Bhai-Youtube/adb-fastboot-driver-toolkit"
    ),
    GitHubProjectItem(
        name = "rdp-switcher",
        title = "RDP Switcher (Dynamic Island)",
        description = "iOS-style Dynamic Island Notch for instant Remote Desktop switching on Windows.",
        language = "Python",
        stars = 0,
        url = "https://github.com/Rohit-Bhai-Youtube/rdp-switcher"
    )
)

/** Crisp vector GitHub Octocat silhouette icon (normalized 24x24). */
@Composable
fun GitHubMarkIcon(
    modifier: Modifier = Modifier.size(14.dp),
    tint: Color = Color.White
) {
    Canvas(modifier = modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        scale(scaleX = sx, scaleY = sy, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            val path = Path().apply {
                moveTo(12f, 0.5f)
                cubicTo(5.37f, 0.5f, 0f, 5.87f, 0f, 12.5f)
                cubicTo(0f, 17.81f, 3.44f, 22.3f, 8.21f, 23.89f)
                cubicTo(8.81f, 24.0f, 9.03f, 23.63f, 9.03f, 23.32f)
                cubicTo(9.03f, 23.03f, 9.01f, 22.09f, 9.01f, 21.08f)
                cubicTo(6.0f, 21.64f, 5.22f, 20.35f, 4.98f, 19.67f)
                cubicTo(4.84f, 19.33f, 4.26f, 18.26f, 3.75f, 17.98f)
                cubicTo(3.33f, 17.75f, 2.73f, 17.2f, 3.74f, 17.18f)
                cubicTo(4.68f, 17.17f, 5.36f, 18.05f, 5.58f, 18.41f)
                cubicTo(6.66f, 20.23f, 8.39f, 19.72f, 9.08f, 19.4f)
                cubicTo(9.19f, 18.62f, 9.5f, 18.09f, 9.85f, 17.79f)
                cubicTo(7.18f, 17.49f, 4.39f, 16.46f, 4.39f, 11.87f)
                cubicTo(4.39f, 10.56f, 4.85f, 9.48f, 5.62f, 8.64f)
                cubicTo(5.49f, 8.34f, 5.08f, 7.11f, 5.74f, 5.46f)
                cubicTo(5.74f, 5.46f, 6.74f, 5.14f, 9.03f, 6.69f)
                cubicTo(9.99f, 6.42f, 11.01f, 6.29f, 12.03f, 6.29f)
                cubicTo(13.05f, 6.29f, 14.07f, 6.42f, 15.03f, 6.69f)
                cubicTo(17.32f, 5.13f, 18.32f, 5.46f, 18.32f, 5.46f)
                cubicTo(18.98f, 7.11f, 18.57f, 8.34f, 18.44f, 8.64f)
                cubicTo(19.21f, 9.48f, 19.67f, 10.55f, 19.67f, 11.87f)
                cubicTo(19.67f, 16.47f, 16.87f, 17.49f, 14.19f, 17.79f)
                cubicTo(14.63f, 18.17f, 15.01f, 18.89f, 15.01f, 20.01f)
                cubicTo(15.01f, 21.62f, 14.99f, 22.91f, 14.99f, 23.32f)
                cubicTo(14.99f, 23.63f, 15.22f, 24.01f, 15.82f, 23.89f)
                cubicTo(20.57f, 22.3f, 24f, 17.81f, 24f, 12.5f)
                cubicTo(24f, 5.87f, 18.63f, 0.5f, 12f, 0.5f)
                close()
            }
            drawPath(path = path, color = tint)
        }
    }
}

/** Middle footer pill with GitHub icon that opens the live GitHub Projects popup. */
@Composable
fun MyProjectsPill(
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .liquidGlass(
                glowColor = Color(0xFF00E5FF),
                shape = RoundedCornerShape(14.dp),
                borderWidth = 1.2.dp
            )
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 7.dp)
    ) {
        GitHubMarkIcon(
            modifier = Modifier.size(14.dp),
            tint = Color(0xFF38BDF8)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = tr("My Projects", "My Projects"),
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFE0F2FE),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun parseReposJson(rawJson: String): List<GitHubProjectItem> {
    val arr = JSONArray(rawJson)
    val list = mutableListOf<GitHubProjectItem>()
    for (i in 0 until arr.length()) {
        val obj = arr.getJSONObject(i)
        if (obj.optBoolean("private", false)) continue
        val name = obj.optString("name", "")
        if (name.isBlank()) continue
        val low = name.lowercase()
        if (low == "readme.md" || low == "rohit-bhai-youtube" || low == "romflasher-site") continue
        val title = name.replace("_", " ").replace("-", " ")
            .split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ") { w -> w.replaceFirstChar { c -> c.uppercase() } }
        list.add(
            GitHubProjectItem(
                name = name,
                title = title,
                description = obj.optString("description", "").ifBlank { "Open-source project by @$GH_USER" },
                language = obj.optString("language", "").ifBlank { "Project" },
                stars = obj.optInt("stargazers_count", 0),
                url = obj.optString("html_url", "$GH_PROFILE_URL/$name")
            )
        )
    }
    return list
}

private fun openUrlExternal(context: Context, uriHandler: androidx.compose.ui.platform.UriHandler, url: String) {
    try {
        uriHandler.openUri(url)
    } catch (_: Exception) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
        }
    }
}

/** Popup dialog showing all live-synced public GitHub projects of Rohit Bhai. */
@Composable
fun MyProjectsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val listState = rememberLazyListState()
    val prefs = remember { context.getSharedPreferences("rf_github_projects", Context.MODE_PRIVATE) }
    var projects by remember {
        val cached = prefs.getString("repos_json", null)
        val initial = if (!cached.isNullOrBlank()) {
            try { parseReposJson(cached) } catch (_: Exception) { FALLBACK_GH_PROJECTS }
        } else FALLBACK_GH_PROJECTS
        mutableStateOf(initial.ifEmpty { FALLBACK_GH_PROJECTS })
    }
    var isSyncing by remember { mutableStateOf(true) }
    var statusLabel by remember { mutableStateOf("Syncing live from GitHub...") }
    var refreshTrigger by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshTrigger) {
        isSyncing = true
        statusLabel = "Syncing live from GitHub..."
        val result = withContext(Dispatchers.IO) {
            try {
                val conn = (URL(GH_REPOS_API).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("User-Agent", "ROM-Flasher-Tool-Android")
                    setRequestProperty("Accept", "application/vnd.github+json")
                }
                if (conn.responseCode in 200..299) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val parsed = parseReposJson(body)
                    if (parsed.isNotEmpty()) {
                        prefs.edit().putString("repos_json", body).apply()
                    }
                    Result.success(parsed)
                } else {
                    Result.failure(RuntimeException("HTTP ${conn.responseCode}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
        result.onSuccess { liveList ->
            if (liveList.isNotEmpty()) {
                projects = liveList
                statusLabel = "Live synced • ${liveList.size} Public Projects"
            } else {
                statusLabel = "Showing ${projects.size} Projects"
            }
        }.onFailure {
            statusLabel = "Cached / Offline • ${projects.size} Projects"
        }
        isSyncing = false
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        SmoothDialogEntrance {
            Column(
                modifier = Modifier
                .fillMaxWidth(0.92f)
                .heightIn(max = 620.dp)
                .liquidGlass(
                    glowColors = listOf(Color(0xFF00E5FF), Color(0xFF8B5CF6)),
                    shape = RoundedCornerShape(24.dp),
                    borderWidth = 1.5.dp
                )
                .padding(16.dp)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF00E5FF).copy(alpha = 0.16f))
                        .border(1.dp, Color(0xFF00E5FF).copy(alpha = 0.45f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    GitHubMarkIcon(modifier = Modifier.size(18.dp), tint = Color(0xFF38BDF8))
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "My GitHub Projects",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = statusLabel,
                        fontSize = 11.sp,
                        color = if (isSyncing) Color(0xFF38BDF8) else Color(0xFF94A3B8)
                    )
                }
                if (isSyncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Color(0xFF38BDF8)
                    )
                    Spacer(Modifier.width(6.dp))
                } else {
                    IconButton(
                        onClick = { refreshTrigger++ },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Refresh",
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close",
                        tint = Color(0xFF94A3B8),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Live Project List with visible vertical scrollbar
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 180.dp, max = 460.dp)
                    .drawWithContent {
                        drawContent()
                        val layoutInfo = listState.layoutInfo
                        val totalItems = layoutInfo.totalItemsCount
                        val visibleItems = layoutInfo.visibleItemsInfo
                        if (totalItems > 0 && visibleItems.isNotEmpty() && visibleItems.size < totalItems) {
                            val firstIdx = listState.firstVisibleItemIndex.toFloat()
                            val thumbH = (size.height * (visibleItems.size.toFloat() / totalItems.toFloat()))
                                .coerceIn(28.dp.toPx(), size.height * 0.8f)
                            val scrollMax = (totalItems - visibleItems.size).coerceAtLeast(1).toFloat()
                            val frac = (firstIdx / scrollMax).coerceIn(0f, 1f)
                            val thumbY = frac * (size.height - thumbH)
                            val barW = 3.5.dp.toPx()
                            drawRoundRect(
                                color = Color.White.copy(alpha = 0.10f),
                                topLeft = Offset(size.width - barW, 0f),
                                size = Size(barW, size.height),
                                cornerRadius = CornerRadius(barW / 2f)
                            )
                            drawRoundRect(
                                color = Color(0xFF38BDF8).copy(alpha = 0.78f),
                                topLeft = Offset(size.width - barW, thumbY),
                                size = Size(barW, thumbH),
                                cornerRadius = CornerRadius(barW / 2f)
                            )
                        }
                    }
                    .padding(end = 8.dp)
            ) {
                items(projects, key = { it.name }) { item ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF0F172A).copy(alpha = 0.82f))
                            .border(
                                1.dp,
                                Color.White.copy(alpha = 0.14f),
                                RoundedCornerShape(16.dp)
                            )
                            .clickable { openUrlExternal(context, uriHandler, item.url) }
                            .padding(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = item.title,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = item.language,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF38BDF8),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(Color(0xFF38BDF8).copy(alpha = 0.14f))
                                    .padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "★ ${item.stars}",
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFACC15),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(Color(0xFFFACC15).copy(alpha = 0.14f))
                                    .padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = item.description,
                            fontSize = 11.5.sp,
                            color = Color(0xFF94A3B8),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            lineHeight = 15.sp
                        )
                    }
                }
            }
        }
        }
    }
}
