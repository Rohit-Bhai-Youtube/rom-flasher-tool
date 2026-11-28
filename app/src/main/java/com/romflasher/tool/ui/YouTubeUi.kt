package com.romflasher.tool.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.BitmapFactory
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.romflasher.tool.R
import com.romflasher.tool.util.YouTubeFeed
import com.romflasher.tool.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

private val YT_RED = Color(0xFFFF0033)
private val YT_BG = Color(0xFF160408)
private val YT_MUTED = Color(0xFFB8B8BE)

/**
 * The player page is served from this real https origin (see
 * `shouldInterceptRequest` in [YouTubeInlinePlayer]) instead of being injected as a
 * `data:`/`loadDataWithBaseURL` document. Android reports a `null` document origin
 * for those, and YouTube reacts to a null origin with "Error 153 – video player
 * configuration error". A real https origin fixes that and also lets the IFrame
 * API handshake finish, which is what gives us ready/error callbacks.
 */
private const val PLAYER_ORIGIN = "https://appassets.androidplatform.net"
private const val PLAYER_URL = "$PLAYER_ORIGIN/yt.html"

// ============================================================================
//  "Rom Flasher • YouTube" card — behaves exactly like the other glow cards.
//
//  • Collapsed: thumbnail + the *current* video title (auto-updating).
//  • Tapped:    expands smoothly and starts the video inline (no pop-up window).
//  • Collapsed again: the player keeps playing but is muted, so it survives the
//    collapse. Expand again and the sound comes back.
//  • Fullscreen works through the player's own button (WebChromeClient).
// ============================================================================
@Composable
fun YouTubeCard(
    video: YouTubeFeed.VideoInfo?,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onOpenInYouTube: (String) -> Unit
) {
    val thumb = rememberRemoteThumbnail(video?.let { YouTubeFeed.thumbnailUrl(it.id) })

    // Only spin up the WebView once the card has opened, so the video surface is
    // created at its real size instead of while the slot is still ~0 high.
    var playerRequested by remember { mutableStateOf(false) }
    LaunchedEffect(isExpanded) {
        if (isExpanded) {
            delay(260)
            playerRequested = true
        }
    }

    val rimBrush = LiquidGlass.rememberSpecularRimBrush(listOf(YT_RED, Color(0xFFFF6B3D)))
    val fillBrush = LiquidGlass.rememberGlassFillBrush()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(fillBrush, LiquidGlass.CardShape)
            .border(1.8.dp, rimBrush, LiquidGlass.CardShape)
            .padding(14.dp)
    ) {
        Column {
            // ---------------- header (tap = expand / collapse) ----------------
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .noRippleClickable { onToggleExpand() }
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    // Bigger 16:9 thumbnail, so the video art is actually readable.
                    // ContentScale.Crop trims the letterbox bars YouTube bakes into
                    // hqdefault.jpg, so the full picture (not black borders) shows.
                    Box(
                        modifier = Modifier
                            .width(116.dp)
                            .height(65.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        if (thumb != null) {
                            Image(
                                bitmap = thumb,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Image(
                                painter = painterResource(id = R.drawable.ic_youtube_logo),
                                contentDescription = null,
                                modifier = Modifier.size(34.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(13.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = video?.title?.takeIf { it.isNotBlank() }
                                ?: tr("Latest video on Rom Flasher", "Rom Flasher ka naya video"),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        // The chevron sits right beside the subtitle instead of in a
                        // reserved strip below it, so the header keeps no dead space.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (video == null) tr("Loading latest video…", "Naya video load ho raha hai…")
                                else tr("Rom Flasher • Tap to play in app", "Rom Flasher • App me dekhne ke liye tap karo"),
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (video == null) YT_RED else Color(0xFF8E8E93),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = if (isExpanded) Icons.Rounded.KeyboardArrowUp
                                else Icons.Rounded.KeyboardArrowDown,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            // ---------------- body (video) ----------------
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val playerHeight = maxWidth * 9f / 16f

                // The video slot animates its *real* height. Earlier versions kept a
                // fixed-size WebView inside an animating clip window; a clipped video
                // surface keeps playing audio while painting black, so the layout is
                // deliberately completely ordinary here.
                val playerSlotHeight by animateDpAsState(
                    targetValue = if (isExpanded) playerHeight else 0.dp,
                    animationSpec = androidx.compose.animation.core.spring(
                        dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
                        stiffness = 380f
                    ),
                    label = "youtubePlayerSlot"
                )

                // Wrapped in a Column so AnimatedVisibility resolves against the
                // ColumnScope receiver rather than BoxWithConstraintsScope.
                Column(modifier = Modifier.fillMaxWidth()) {
                ExpandableContent(isExpanded = isExpanded) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Spacer(modifier = Modifier.height(18.dp))
                        HorizontalDivider(color = Color(0x33FFFFFF))
                        Spacer(modifier = Modifier.height(14.dp))
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(playerSlotHeight)
                        .background(Color.Black, RoundedCornerShape(14.dp))
                ) {
                    // Poster behind the player, so it is never a blank black
                    // rectangle while the embed is still warming up.
                    if (thumb != null) {
                        Image(
                            bitmap = thumb,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    if (playerRequested && video != null) {
                        // key() so a newly uploaded video rebuilds the player
                        // instead of playing the old id forever.
                        key(video.id) {
                            YouTubeInlinePlayer(
                                videoId = video.id,
                                muted = !isExpanded,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }

                AnimatedVisibility(
                    visible = isExpanded,
                    enter = expandVertically(tween(280, easing = FastOutSlowInEasing)) +
                        fadeIn(tween(190)),
                    exit = shrinkVertically(tween(280, easing = FastOutSlowInEasing)) +
                        fadeOut(tween(190))
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Spacer(modifier = Modifier.height(10.dp))

                        // Small escape hatch — intentionally compact.
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0x14FFFFFF))
                                .clickable {
                                    video?.let { onOpenInYouTube(it.id) }
                                },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                contentDescription = null,
                                tint = YT_MUTED,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = tr("Open in YouTube", "YouTube me kholo"),
                                color = YT_MUTED,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
                }
            }
        }
    }
}

// ============================================================================
//  The inline WebView player.
//
//  The page is served from a real https origin via shouldInterceptRequest instead
//  of being injected with loadDataWithBaseURL(). Android reports a `null` document
//  origin for injected documents, and YouTube answers a null origin with
//  "Error 153 – video player configuration error" (which is exactly what produced
//  the black box / endless "loading" before).
//
//  The page itself uses a *plain* embed iframe that is sized in explicit pixels
//  (see playerHtml), which is what keeps the picture visible: a percentage sized
//  iframe is laid out while the card is still 0 high and then stays invisible,
//  giving the classic "audio plays, screen stays black" symptom.
// ============================================================================
@Composable
private fun YouTubeInlinePlayer(
    videoId: String,
    muted: Boolean,
    modifier: Modifier = Modifier
) {
    val webRef = remember { mutableStateOf<WebView?>(null) }
    val fullscreen = remember { FullscreenHolder() }
    var playerReady by remember(videoId) { mutableStateOf(false) }
    var playerError by remember(videoId) { mutableStateOf<String?>(null) }
    var slow by remember(videoId) { mutableStateOf(false) }

    LaunchedEffect(videoId) {
        playerReady = false
        playerError = null
        slow = false
        delay(8000)
        if (!playerReady && playerError == null) slow = true
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    // Video inside a clipped/animated Compose layer only paints when
                    // the WebView owns an opaque, hardware-accelerated layer.
                    // Without this you get exactly "audio plays, picture black".
                    setBackgroundColor(android.graphics.Color.BLACK)
                    setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.loadWithOverviewMode = false
                    settings.useWideViewPort = true
                    settings.setSupportZoom(false)
                    settings.builtInZoomControls = false

                    addJavascriptInterface(
                        PlayerJsBridge(
                            web = this,
                            onReady = {
                                playerReady = true
                                // Force the video layer to (re)composite.
                                requestLayout()
                                invalidate()
                            },
                            onError = { info ->
                                playerError = info.ifBlank { "unknown" }
                            }
                        ),
                        "RFPlayer"
                    )

                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val url = request?.url?.toString() ?: return null
                            if (!url.startsWith(PLAYER_URL)) return null
                            return WebResourceResponse(
                                "text/html",
                                "UTF-8",
                                200,
                                "OK",
                                mapOf("Cache-Control" to "no-store"),
                                playerHtml(videoId).byteInputStream(Charsets.UTF_8)
                            )
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                            val activity = ctx.findActivity() ?: return
                            val decor = activity.window.decorView as? ViewGroup ?: return
                            if (fullscreen.view != null) {
                                onHideCustomView()
                                return
                            }
                            fullscreen.view = view
                            fullscreen.callback = callback
                            decor.addView(
                                view,
                                ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                            )
                        }

                        override fun onHideCustomView() {
                            fullscreen.detach()
                        }
                    }

                    // Served from a real https origin (see shouldInterceptRequest
                    // above) — that origin is what the YouTube embed expects.
                    loadUrl(PLAYER_URL)
                    webRef.value = this
                }
            },
            onRelease = { wv ->
                webRef.value = null
                fullscreen.detach()
                try {
                    wv.stopLoading()
                    wv.loadUrl("about:blank")
                    wv.destroy()
                } catch (_: Throwable) {
                }
            }
        )

        // Compact status layer — only ever visible while the player is starting
        // up or when it genuinely failed.
        when {
            playerError != null -> {
                Text(
                    text = tr("Player error ${playerError}. Tap “Open in YouTube” below.", "Player error ${playerError}. Neeche “Open in YouTube” dabao."),
                    color = Color(0xFFFFB4B4),
                    fontSize = 10.5.sp,
                    maxLines = 2,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(10.dp)
                )
            }

            !playerReady -> {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = YT_RED,
                        strokeWidth = 2.5.dp
                    )
                    if (slow) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = tr("Slow? Use “Open in YouTube” below", "Slow? Neeche “Open in YouTube” dabao"),
                            color = YT_MUTED,
                            fontSize = 10.5.sp
                        )
                    }
                }
            }
        }
    }

    // Muted while collapsed (so it can keep playing), sound when open.
    // Re-sent once the page reports itself ready, because the very first command
    // can land before the embed's command listener exists.
    LaunchedEffect(playerReady, muted) {
        if (!playerReady) return@LaunchedEffect
        delay(250)
        webRef.value?.evaluateJavascript("ytSetMuted($muted);", null)
        delay(900)
        webRef.value?.evaluateJavascript("ytSetMuted($muted);", null)
    }
}

/** Lets the embedded page tell us when it is actually alive (or broken). */
private class PlayerJsBridge(
    private val web: WebView,
    private val onReady: () -> Unit,
    private val onError: (String) -> Unit
) {
    @JavascriptInterface
    fun ready() {
        web.post { onReady() }
    }

    @JavascriptInterface
    fun error(info: String) {
        web.post { onError(info) }
    }
}

/** Holds the fullscreen view handed to us by [WebChromeClient]. */
private class FullscreenHolder {
    var view: View? = null
    var callback: WebChromeClient.CustomViewCallback? = null

    fun detach() {
        val v = view
        val ctx = v?.context
        val decor = ctx?.findActivity()?.window?.decorView as? ViewGroup
        if (v != null && decor != null) {
            try {
                decor.removeView(v)
            } catch (_: Throwable) {
            }
        }
        view = null
        try {
            callback?.onCustomViewHidden()
        } catch (_: Throwable) {
        }
        callback = null
    }
}

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/**
 * Player page served over [PLAYER_ORIGIN].
 *
 * Deliberately a *plain* YouTube embed <iframe> — no IFrame Player API loader.
 * The API replaces its host element with a fresh, unstyled <iframe> (the host's id
 * — and therefore the host's CSS — is dropped), which left the player with no size
 * at all: the audio track played while the picture stayed invisible. The plain
 * iframe with explicit CSS is the variant proven to render video in this WebView.
 *
 * Mute control for the collapse-keeps-playing behaviour goes through the embed's
 * postMessage command channel (`enablejsapi=1` plus a matching `origin`).
 */
private fun playerHtml(videoId: String): String = """
<!DOCTYPE html>
<html>
<head>
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<style>
  html,body{margin:0;padding:0;background:#000;overflow:hidden}
  iframe#yt{display:block;width:320px;height:180px;border:0;background:#000}
</style>
</head>
<body>
<iframe id="yt"
        allow="autoplay; encrypted-media; fullscreen; picture-in-picture"
        allowfullscreen
        src="https://www.youtube.com/embed/${videoId}?autoplay=1&mute=1&playsinline=1&rel=0&modestbranding=1&controls=1&fs=1&enablejsapi=1&origin=${PLAYER_ORIGIN}"></iframe>
<script>
  var frame = document.getElementById('yt');

  // The WebView height animates from 0 while the card opens, so a percentage /
  // auto sized iframe can be laid out at 0 and simply stay that way - the audio
  // plays while the picture never appears. Size it in explicit pixels instead,
  // and remember the last known good size so collapsing the card (viewport height
  // 0) does not shrink the player and cut the sound off.
  var lastW = 0;
  var lastH = 0;

  // Surface real playback problems to the Kotlin side so the card can offer
  // "Open in YouTube" instead of silently showing a black box.
  function ytError(info) {
    try { window.RFPlayer.error(info || 'unknown'); } catch (e) {}
  }
  frame.addEventListener('error', function () { ytError('load-failed'); });
  window.addEventListener('message', function (e) {
    try {
      if (typeof e.data !== 'string') return;
      var msg = JSON.parse(e.data);
      if (msg && msg.event === 'onError') { ytError(String(msg.info)); }
    } catch (err) {}
  });

  function fit() {
    var w = window.innerWidth || document.documentElement.clientWidth || 0;
    var h = window.innerHeight || document.documentElement.clientHeight || 0;
    if (w > 0 && h > 0 && (w !== lastW || h !== lastH)) {
      lastW = w;
      lastH = h;
      frame.style.width = w + 'px';
      frame.style.height = h + 'px';
    }
  }
  fit();
  setInterval(fit, 250);

  function ytSend(func, args) {
    try {
      var f = document.getElementById('yt');
      if (!f || !f.contentWindow) return;
      f.contentWindow.postMessage(
        JSON.stringify({ event: 'command', func: func, args: args || [] }), '*');
    } catch (e) {}
  }

  function ytSetMuted(m) {
    if (m) {
      ytSend('mute');
    } else {
      ytSend('unMute');
      ytSend('setVolume', [100]);
      ytSend('playVideo');
    }
  }

  (function () {
    var f = document.getElementById('yt');
    var done = false;
    function ready() {
      if (done) return;
      done = true;
      try { window.RFPlayer.ready(); } catch (e) {}
      ytSend('playVideo');
    }
    if (f) {
      f.addEventListener('load', function () { setTimeout(ready, 700); });
    }
    // Safety net in case the load event fires before the listener is attached.
    setTimeout(ready, 3000);
  })();
</script>
</body>
</html>
""".trimIndent()

/**
 * Minimal network image loader for the thumbnail — no extra dependency.
 * Returns null (caller shows the vector logo) on offline / any error.
 */
@Composable
private fun rememberRemoteThumbnail(url: String?): ImageBitmap? {
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(url) {
        if (url.isNullOrBlank()) {
            bitmap = null
            return@LaunchedEffect
        }
        bitmap = withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 15000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Android) RomFlasher/1.0")
                }
                if (conn.responseCode !in 200..299) return@withContext null
                conn.inputStream.use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
            } catch (_: Throwable) {
                null
            } finally {
                try {
                    conn?.disconnect()
                } catch (_: Throwable) {
                }
            }
        }
    }

    return bitmap
}
