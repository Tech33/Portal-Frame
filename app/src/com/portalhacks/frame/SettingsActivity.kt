package com.portalhacks.frame

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * The Photos setup/settings screen, in Jetpack Compose (migration Milestone 3).
 * Replaces the view-based status screen; reuses the existing prefs
 * ({@link ConfigReceiver}) and hands off to the Java {@link PhotosActivity} for
 * the camera scanner / manual entry (those flows are ported in a later milestone).
 */
class SettingsActivity : ComponentActivity() {

    private val prefs: SharedPreferences
        get() = getSharedPreferences(ConfigReceiver.PREFS, Context.MODE_PRIVATE)

    // Used to load the album's first photo for the preview thumbnail (reuses the
    // slideshow's on-disk image cache).
    private val loader by lazy { ImageLoader(this) }

    // Bumped on resume so the screen re-reads prefs after returning from the scanner /
    // manual entry (the album may have changed there).
    private val resumeTick = mutableIntStateOf(0)

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
        // Re-assert Frame (and restart the guard if it was killed) when opted in.
        ScreensaverGuardService.startIfEnabled(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = PortalColors.Blue,
                    onPrimary = PortalColors.OnPrimary,
                    background = PortalColors.Bg,
                    surface = PortalColors.Surface,
                ),
                typography = rememberInterTypography(this),
            ) {
                SettingsScreen()
            }
        }
    }

    private fun openScreensaver() {
        try {
            startActivity(Intent(Settings.ACTION_DREAM_SETTINGS))
        } catch (_: Exception) {
        }
    }

    /**
     * Make Frame the screensaver. With WRITE_SECURE_SETTINGS we set it directly and
     * start the guard so the launcher can't reclaim the slot on rotation; otherwise we
     * fall back to the system Screen-saver picker.
     */
    private fun enableScreensaver() {
        prefs.edit().putBoolean(ConfigReceiver.KEY_GUARD, true).apply()
        if (Screensaver.claim(this)) {
            ScreensaverGuardService.start(this)
        } else {
            openScreensaver()
        }
    }

    private fun gotoPhotos(goto: String) {
        startActivity(Intent(this, PhotosActivity::class.java).putExtra("goto", goto))
    }

    private fun startScreensaverNow() {
        startActivity(Intent(this, SlideshowComposeActivity::class.java))
    }

    private fun refreshAlbumsNow(
        urls: List<String>,
        onStatus: (String) -> Unit,
        onFinished: () -> Unit,
    ) {
        if (urls.isEmpty()) {
            onStatus("No albums to refresh.")
            onFinished()
            return
        }
        val remaining = java.util.concurrent.atomic.AtomicInteger(urls.size)
        val refreshed = java.util.concurrent.atomic.AtomicInteger(0)
        val failed = java.util.concurrent.atomic.AtomicInteger(0)
        onStatus("Refreshing ${urls.size} album" + if (urls.size == 1) "" else "s" + "…")
        for (url in urls) {
            loader.executor().execute {
                try {
                    val album = PhotoSources.fetch(url)
                    AlbumCache.write(prefs, url, album.slides, album.title)
                    refreshed.incrementAndGet()
                } catch (_: Exception) {
                    failed.incrementAndGet()
                } finally {
                    if (remaining.decrementAndGet() == 0) {
                        runOnUiThread {
                            val ok = refreshed.get()
                            val bad = failed.get()
                            onStatus(
                                when {
                                    bad == 0 -> "Refreshed $ok album" + if (ok == 1) "" else "s" + "."
                                    ok == 0 -> "Couldn't refresh albums right now."
                                    else -> "Refreshed $ok album" + if (ok == 1) "" else "s" +
                                        "; $bad failed."
                                },
                            )
                            onFinished()
                        }
                    }
                }
            }
        }
    }

    private fun checkForUpdates(
        onResult: (UpdateChecker.UpdateManifest?, String) -> Unit,
    ) {
        loader.executor().execute {
            val manifest = UpdateChecker.fetchManifest()
            val current = UpdateChecker.currentVersionCode(this)
            runOnUiThread {
                when {
                    manifest == null ->
                        onResult(null, "Couldn't reach GitHub — try again later.")
                    manifest.versionCode <= current ->
                        onResult(null, "You're on the latest version (${UpdateChecker.currentVersionName(this)}).")
                    else ->
                        onResult(
                            manifest,
                            "Version ${manifest.versionName} is available (you have " +
                                "${UpdateChecker.currentVersionName(this)}).",
                        )
                }
            }
        }
    }

    private fun downloadAndInstallUpdate(
        manifest: UpdateChecker.UpdateManifest,
        onStatus: (String) -> Unit,
        onFinished: () -> Unit,
    ) {
        loader.executor().execute {
            runOnUiThread { onStatus("Downloading update…") }
            when (val result = UpdateInstaller.download(this, manifest)) {
                is UpdateInstaller.Result.Ready -> runOnUiThread {
                    if (UpdateInstaller.promptInstall(this, result.file)) {
                        onStatus("Follow the system prompt to install.")
                    } else {
                        onStatus("Allow Frame to install updates, then tap Download again.")
                    }
                    onFinished()
                }
                is UpdateInstaller.Result.Error -> runOnUiThread {
                    onStatus(result.message)
                    onFinished()
                }
            }
        }
    }

    private fun isOurScreensaver(): Boolean = try {
        val enabled = Settings.Secure.getInt(contentResolver, "screensaver_enabled", 0) == 1
        val comp = Settings.Secure.getString(contentResolver, "screensaver_components")
        enabled && comp != null && comp.contains(packageName)
    } catch (_: Exception) {
        false
    }

    // ----------------------------------------------------------------- UI

    @Composable
    private fun SettingsScreen() {
        val ctx = LocalContext.current
        var tick by remember { mutableIntStateOf(0) } // bump to recompose after pref writes
        var refreshingAlbums by remember { mutableStateOf(false) }
        var albumRefreshStatus by remember { mutableStateOf("") }
        var checkingUpdate by remember { mutableStateOf(false) }
        var downloadingUpdate by remember { mutableStateOf(false) }
        var updateStatus by remember { mutableStateOf("") }
        var pendingUpdate by remember { mutableStateOf<UpdateChecker.UpdateManifest?>(null) }
        val installedVersion = remember(tick, resumeTick.intValue) {
            UpdateChecker.currentVersionName(ctx) +
                " (${UpdateChecker.currentVersionCode(ctx)})"
        }
        resumeTick.intValue // read so returning from the scanner re-reads albums below
        tick // read so writes that bump it recompose
        val albums = Albums.list(prefs)
        val hasAlbum = albums.isNotEmpty()
        val autoCheckUpdates = prefs.getBoolean(
            ConfigReceiver.KEY_UPDATE_AUTO_CHECK,
            ConfigReceiver.DEFAULT_UPDATE_AUTO_CHECK,
        )

        LaunchedEffect(resumeTick.intValue, autoCheckUpdates) {
            if (!autoCheckUpdates || checkingUpdate || downloadingUpdate) {
                return@LaunchedEffect
            }
            val last = prefs.getLong(ConfigReceiver.KEY_LAST_UPDATE_CHECK_MS, 0L)
            if (System.currentTimeMillis() - last < UPDATE_CHECK_INTERVAL_MS) {
                return@LaunchedEffect
            }
            checkingUpdate = true
            checkForUpdates { manifest, status ->
                checkingUpdate = false
                prefs.edit()
                    .putLong(ConfigReceiver.KEY_LAST_UPDATE_CHECK_MS, System.currentTimeMillis())
                    .apply()
                if (manifest != null) {
                    pendingUpdate = manifest
                    updateStatus = status
                }
            }
        }

        // Card groups, so the layout can be one or two columns by available width.
        val sourceCards: @Composable () -> Unit = {
            Card("Screensaver") {
                val active = isOurScreensaver()
                val protectedMode = Screensaver.canWrite(ctx)
                Body(
                    when {
                        active && protectedMode ->
                            "✓ Frame is your screensaver — and Frame keeps it that way, even after a rotation."
                        active ->
                            "✓ Frame is your screensaver. Your photos appear when the Portal is idle."
                        else ->
                            "Tap below so your photos show when the Portal is idle."
                    },
                )
                Spacer(Modifier.height(12.dp))
                if (active) PrimaryBtn("Change screensaver") { openScreensaver() }
                else PrimaryBtn("Use as screensaver") { enableScreensaver(); tick++ }
                Spacer(Modifier.height(10.dp))
                SecondaryBtn("Start screensaver now") { startScreensaverNow() }
            }
            Card("Updates") {
                Body("Installed: $installedVersion")
                Spacer(Modifier.height(8.dp))
                Body(
                    "Frame checks GitHub for a newer signed APK. You'll need to allow " +
                        "Frame to install updates once (Android will prompt you).",
                )
                if (updateStatus.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Body(updateStatus)
                }
                pendingUpdate?.releaseNotes?.let { notes ->
                    Spacer(Modifier.height(8.dp))
                    Body(notes)
                }
                Spacer(Modifier.height(12.dp))
                SecondaryBtn(
                    if (checkingUpdate) "Checking…" else "Check for updates",
                    enabled = !checkingUpdate && !downloadingUpdate,
                ) {
                    checkingUpdate = true
                    updateStatus = ""
                    checkForUpdates { manifest, status ->
                        checkingUpdate = false
                        pendingUpdate = manifest
                        updateStatus = status
                        prefs.edit()
                            .putLong(ConfigReceiver.KEY_LAST_UPDATE_CHECK_MS, System.currentTimeMillis())
                            .apply()
                    }
                }
                pendingUpdate?.let { manifest ->
                    Spacer(Modifier.height(10.dp))
                    PrimaryBtn(
                        if (downloadingUpdate) "Downloading…" else "Download and install ${manifest.versionName}",
                        enabled = !downloadingUpdate,
                    ) {
                        downloadingUpdate = true
                        downloadAndInstallUpdate(
                            manifest = manifest,
                            onStatus = { updateStatus = it },
                            onFinished = { downloadingUpdate = false },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                ToggleRow(
                    "Check automatically",
                    ConfigReceiver.KEY_UPDATE_AUTO_CHECK,
                    ConfigReceiver.DEFAULT_UPDATE_AUTO_CHECK,
                    subtitle = "When you open Settings (at most once every 6 hours).",
                ) { tick++ }
            }
            Card(if (hasAlbum) "Albums" else "No albums yet") {
                if (hasAlbum) {
                    if (albumRefreshStatus.isNotEmpty()) {
                        Body(albumRefreshStatus)
                        Spacer(Modifier.height(12.dp))
                    }
                    SecondaryBtn(
                        if (refreshingAlbums) "Refreshing albums…" else "Refresh albums now",
                        enabled = !refreshingAlbums,
                    ) {
                        refreshingAlbums = true
                        refreshAlbumsNow(
                            urls = albums,
                            onStatus = { albumRefreshStatus = it },
                            onFinished = {
                                refreshingAlbums = false
                                tick++
                            },
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    CycleRow(
                        "Album playback",
                        albumPlaybackLabel(
                            prefs.getString(
                                ConfigReceiver.KEY_ALBUM_PLAYBACK,
                                ConfigReceiver.DEFAULT_ALBUM_PLAYBACK,
                            ) ?: ConfigReceiver.DEFAULT_ALBUM_PLAYBACK,
                        ),
                    ) {
                        val next = if (
                            prefs.getString(
                                ConfigReceiver.KEY_ALBUM_PLAYBACK,
                                ConfigReceiver.DEFAULT_ALBUM_PLAYBACK,
                            ) == "album_priority"
                        ) "shuffled_merge" else "album_priority"
                        prefs.edit().putString(ConfigReceiver.KEY_ALBUM_PLAYBACK, next).apply()
                        tick++
                    }
                    Divider()
                    // One removable row per album; the slideshow plays them all merged.
                    albums.forEachIndexed { i, url ->
                        if (i > 0) Divider()
                        AlbumRow(
                            url = url,
                            index = i,
                            count = albums.size,
                            onMoveUp = { Albums.move(prefs, url, -1); tick++ },
                            onMoveDown = { Albums.move(prefs, url, 1); tick++ },
                            onRemove = { Albums.remove(prefs, url); tick++ },
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                } else {
                    Body("Add a Google Photos or iCloud shared album to show your own photos.")
                    Spacer(Modifier.height(12.dp))
                }
                // One add-album screen — scan a QR or paste a link there.
                PrimaryBtn("Add album") { gotoPhotos("scan") }
            }
        }
        val settingsCards: @Composable () -> Unit = {
            LivePreviewCard()
            Card("Slideshow") {
                DurationSliderRow { tick++ }
                Divider()
                ToggleRow("Shuffle photos", ConfigReceiver.KEY_SHUFFLE, false) { tick++ }
                Divider()
                TransitionSelectorRow { tick++ }
                Divider()
                ToggleRow("Pair photos to fill the screen", ConfigReceiver.KEY_PAIRS, false) { tick++ }
                Divider()
                ToggleRow(
                    "Zoom single photos to fill",
                    ConfigReceiver.KEY_ZOOM_FILL,
                    false,
                    subtitle = "Crop a single photo to fill the screen. Off: show the whole photo " +
                        "over a blurred fill. Paired photos always fill.",
                ) { tick++ }
                Divider()
                ToggleRow("Cinematic motion", ConfigReceiver.KEY_KEN_BURNS, true) { tick++ }
                Divider()
                ToggleRow("Photo captions", ConfigReceiver.KEY_CAPTIONS, true) { tick++ }
            }
            Card("Ambient intelligence") {
                ToggleRow("Face-aware framing", ConfigReceiver.KEY_FACE, true) { tick++ }
                Divider()
                ToggleRow("Auto-enhance photos", ConfigReceiver.KEY_ENHANCE, ConfigReceiver.DEFAULT_ENHANCE) { tick++ }
                Divider()
                ToggleRow("Ambient color glow", ConfigReceiver.KEY_AMBIENT, true) { tick++ }
                Divider()
                ToggleRow(
                    "Clock & weather", ConfigReceiver.KEY_CLOCK, true,
                    subtitle = "Long-press the clock on the screensaver to move or resize it.",
                ) { tick++ }
                Divider()
                CycleRow(
                    "Temperature unit",
                    if (prefs.getBoolean(
                            ConfigReceiver.KEY_WEATHER_FAHRENHEIT,
                            ConfigReceiver.DEFAULT_WEATHER_FAHRENHEIT,
                        )
                    ) "Fahrenheit" else "Celsius",
                    // Clarify ordering and default for users
                    // (Celsius is now the default)
                ) {
                    val next = !prefs.getBoolean(
                        ConfigReceiver.KEY_WEATHER_FAHRENHEIT,
                        ConfigReceiver.DEFAULT_WEATHER_FAHRENHEIT,
                    )
                    prefs.edit().putBoolean(ConfigReceiver.KEY_WEATHER_FAHRENHEIT, next).apply()
                    tick++
                }
                Divider()
                CycleRow("Clock format", if (clock24HourEnabled()) "24-hour" else "12-hour") {
                    prefs.edit().putBoolean(ConfigReceiver.KEY_CLOCK_24H, !clock24HourEnabled()).apply()
                    tick++
                }
                Divider()
                CycleRow("Clock position & size", "Reset") {
                    prefs.edit()
                        .putFloat(ConfigReceiver.KEY_CLOCK_DX, ConfigReceiver.DEFAULT_CLOCK_DX)
                        .putFloat(ConfigReceiver.KEY_CLOCK_DY, ConfigReceiver.DEFAULT_CLOCK_DY)
                        .putFloat(ConfigReceiver.KEY_CLOCK_SCALE, ConfigReceiver.DEFAULT_CLOCK_SCALE)
                        .apply()
                    tick++
                }
                Divider()
                ToggleRow("Only clock in low light", ConfigReceiver.KEY_CLOCK_LOW_LIGHT, ConfigReceiver.DEFAULT_CLOCK_LOW_LIGHT) { tick++ }
                Divider()
                ToggleRow(
                    "Scheduled full-screen night clock",
                    ConfigReceiver.KEY_NIGHT_CLOCK,
                    ConfigReceiver.DEFAULT_NIGHT_CLOCK,
                    subtitle = "Show a full-screen clock with AM/PM, an Exit button, and a line like Fri, 19 Jun 14°  ☁️ Cloudy instead of photos.",
                ) { tick++ }
                Divider()
                TimeSliderRow(
                    "Night clock starts",
                    ConfigReceiver.KEY_NIGHT_CLOCK_START_MIN,
                    ConfigReceiver.DEFAULT_NIGHT_CLOCK_START_MIN,
                ) { tick++ }
                Divider()
                TimeSliderRow(
                    "Night clock ends",
                    ConfigReceiver.KEY_NIGHT_CLOCK_END_MIN,
                    ConfigReceiver.DEFAULT_NIGHT_CLOCK_END_MIN,
                ) { tick++ }
                Divider()
                ToggleRow("Night warmth", ConfigReceiver.KEY_NIGHT, true) { tick++ }
                Divider()
                ToggleRow("On This Day memories", ConfigReceiver.KEY_ON_THIS_DAY, true) { tick++ }
            }
        }

        BoxWithConstraints(
            Modifier.fillMaxSize().background(PortalColors.Bg),
            contentAlignment = Alignment.TopCenter,
        ) {
            // Two columns only when there's room (Portal Go/+ landscape); one column
            // on the original Portal's portrait screen and the small Portal Mini.
            val twoCol = maxWidth >= 880.dp
            val sidePad = if (maxWidth < 560.dp) 24.dp else 40.dp
            Column(
                Modifier.widthIn(max = if (twoCol) 1100.dp else 620.dp).fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = sidePad, vertical = 72.dp),
            ) {
                Text(
                    if (hasAlbum) "Your photos" else "Show your photos",
                    color = PortalColors.Text, fontSize = 30.sp, fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))

                if (twoCol) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) { sourceCards() }
                        Spacer(Modifier.width(24.dp))
                        Column(Modifier.weight(1f)) { settingsCards() }
                    }
                } else {
                    sourceCards()
                    settingsCards()
                }

                // Trailing breathing room at the end of the scroll (no pinned bar:
                // leaving the screen is the Portal system top bar's back button).
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    @Composable
    private fun Card(title: String, content: @Composable () -> Unit) {
        Column(
            Modifier.fillMaxWidth().padding(top = 16.dp)
                .clip(RoundedCornerShape(20.dp)).background(PortalColors.Surface)
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            Text(
                title.uppercase(), color = PortalColors.TextMuted, fontSize = 13.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp,
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }

    /**
     * One album in the list: a thumbnail of its first photo, the album title +
     * provider/link, and a (two-tap) Remove button. The preview starts from the cache
     * the slideshow writes; if the album was just added and isn't cached yet, it's
     * fetched once in the background, persisted, then shown.
     */
    @Composable
    private fun AlbumRow(
        url: String,
        index: Int,
        count: Int,
        onMoveUp: () -> Unit,
        onMoveDown: () -> Unit,
        onRemove: () -> Unit,
    ) {
        var bmp by remember(url) { mutableStateOf<android.graphics.Bitmap?>(null) }
        var title by remember(url) { mutableStateOf("") }
        var failed by remember(url) { mutableStateOf(false) }
        var armed by remember(url) { mutableStateOf(false) }
        var cachedCount by remember(url) { mutableIntStateOf(AlbumCache.read(prefs, url)?.size ?: 0) }

        // Show the photo, or fall back to the error state (don't hang on "Loading…").
        val onBitmap = ImageLoader.Callback { b -> if (b != null) bmp = b else failed = true }

        LaunchedEffect(url) {
            AlbumCache.title(prefs, url)?.let { title = it }
            cachedCount = AlbumCache.read(prefs, url)?.size ?: 0
            val firstId = AlbumCache.firstId(prefs, url)
            val zoomFill = prefs.getBoolean(ConfigReceiver.KEY_ZOOM_FILL, ConfigReceiver.DEFAULT_ZOOM_FILL)
            if (firstId != null) {
                loader.load(firstId, PREVIEW_W, PREVIEW_H, zoomFill, onBitmap)
            } else {
                // Not fetched yet (just added) — fetch once, persist, then show.
                loader.executor().execute {
                    try {
                        val a = PhotoSources.fetch(url)
                        if (a.slides.isEmpty()) {
                            runOnUiThread { failed = true }
                            return@execute
                        }
                        AlbumCache.write(prefs, url, a.slides, a.title)
                        val id = a.slides[0].id
                        runOnUiThread {
                            if (Albums.list(prefs).contains(url)) {
                                title = a.title ?: ""
                                cachedCount = a.slides.size
                                loader.load(id, PREVIEW_W, PREVIEW_H, zoomFill, onBitmap)
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("PortalFrame", "album preview fetch failed: $url", e)
                        runOnUiThread { failed = true }
                    }
                }
            }
        }

        var on by remember(url) { mutableStateOf(Albums.isEnabled(prefs, url)) }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val shape = RoundedCornerShape(10.dp)
            val image = bmp
            Box(
                Modifier.width(120.dp).aspectRatio(16f / 9f).clip(shape).background(PortalColors.Field),
                contentAlignment = Alignment.Center,
            ) {
                if (image != null) {
                    Image(
                        bitmap = image.asImageBitmap(),
                        contentDescription = "First photo from the album",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Text(
                        if (failed) "—" else "…",
                        color = PortalColors.TextMuted, fontSize = 16.sp,
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title.ifEmpty { if (failed) "Couldn't load" else "Loading…" },
                    color = if (on) PortalColors.Text else PortalColors.TextMuted,
                    fontSize = 17.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    providerLabel(url) + " · " + url,
                    color = PortalColors.TextMuted, fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.MiddleEllipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (cachedCount > 0) "Cached $cachedCount photo" + if (cachedCount == 1) "" else "s"
                    else if (failed) "Cache unavailable"
                    else "Not cached yet",
                    color = PortalColors.Text.copy(alpha = 0.68f), fontSize = 13.sp,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SmallAction("Up", enabled = index > 0, onClick = onMoveUp)
                    Spacer(Modifier.width(8.dp))
                    SmallAction("Down", enabled = index < count - 1, onClick = onMoveDown)
                    Spacer(Modifier.width(10.dp))
                    Text("${index + 1} of $count", color = PortalColors.TextMuted, fontSize = 13.sp)
                }
                Spacer(Modifier.height(8.dp))
                // Stop/resume (keeps the album, just pauses it) + Remove (two-tap).
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = on,
                        onCheckedChange = { on = it; Albums.setEnabled(prefs, url, it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = PortalColors.Blue),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (on) "Playing" else "Stopped",
                        color = PortalColors.TextMuted, fontSize = 14.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (armed) "Confirm" else "Remove",
                        color = PortalColors.Red, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { if (!armed) armed = true else onRemove() }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }

    /** "Google Photos" / "iCloud" / "Link" for the album's URL. */
    private fun providerLabel(url: String): String =
        PhotoSources.providerFor(url)?.displayName ?: "Link"

    @Composable
    private fun Body(text: String) =
        Text(text, color = PortalColors.TextBody, fontSize = 17.sp)

    @Composable
    private fun Divider() = Box(
        Modifier.fillMaxWidth().height(1.dp).padding(vertical = 0.dp).background(PortalColors.Hairline),
    )

    @Composable
    private fun PrimaryBtn(label: String, enabled: Boolean = true, onClick: () -> Unit) = Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(64.dp),
        colors = ButtonDefaults.buttonColors(containerColor = PortalColors.Blue),
    ) { Text(label, color = PortalColors.OnPrimary, fontSize = 18.sp) }

    @Composable
    private fun SecondaryBtn(label: String, enabled: Boolean = true, onClick: () -> Unit) = Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = PortalColors.Surface,
            contentColor = PortalColors.Text,
        ),
    ) { Text(label, color = PortalColors.Text, fontSize = 17.sp) }

    @Composable
    private fun SmallAction(label: String, enabled: Boolean, onClick: () -> Unit) {
        Text(
            label,
            color = if (enabled) PortalColors.Blue else PortalColors.TextMuted,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .alpha(if (enabled) 1f else 0.5f)
                .clickable(enabled = enabled) { onClick() }
                .padding(horizontal = 10.dp, vertical = 7.dp),
        )
    }

    @Composable
    private fun ToggleRow(
        label: String,
        key: String,
        def: Boolean,
        subtitle: String? = null,
        onChanged: () -> Unit,
    ) {
        var on by remember(key) { mutableStateOf(prefs.getBoolean(key, def)) }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, color = PortalColors.Text, fontSize = 18.sp)
                if (subtitle != null) {
                    Text(subtitle, color = PortalColors.Text.copy(alpha = 0.6f), fontSize = 13.sp)
                }
            }
            Switch(
                checked = on,
                onCheckedChange = {
                    on = it
                    prefs.edit().putBoolean(key, it).apply()
                    onChanged()
                },
                colors = SwitchDefaults.colors(checkedTrackColor = PortalColors.Blue),
            )
        }
    }

    @Composable
    private fun CycleRow(label: String, value: String, onClick: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = PortalColors.Text, fontSize = 18.sp, modifier = Modifier.weight(1f))
            Text("$value  ›", color = PortalColors.Blue, fontSize = 18.sp)
        }
    }

    @Composable
    private fun TransitionSelectorRow(onChanged: () -> Unit) {
        var selected by remember {
            mutableStateOf(
                prefs.getString(ConfigReceiver.KEY_TRANSITION, ConfigReceiver.DEFAULT_TRANSITION)
                    ?: ConfigReceiver.DEFAULT_TRANSITION,
            )
        }
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            Text("Transition", color = PortalColors.Text, fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))
            TRANSITION_OPTIONS.forEachIndexed { i, option ->
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            if (selected != option.id) {
                                selected = option.id
                                prefs.edit().putString(ConfigReceiver.KEY_TRANSITION, option.id).apply()
                                onChanged()
                            }
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = selected == option.id,
                        onClick = null,
                        colors = RadioButtonDefaults.colors(
                            selectedColor = PortalColors.Blue,
                            unselectedColor = PortalColors.TextMuted,
                        ),
                    )
                    Text(option.label, color = PortalColors.Text, fontSize = 16.sp)
                }
                if (i < TRANSITION_OPTIONS.lastIndex) {
                    Spacer(Modifier.height(2.dp))
                }
            }
        }
    }

    @Composable
    private fun LivePreviewCard() {
        var now by remember { mutableStateOf(System.currentTimeMillis()) }
        val transition = prefs.getString(ConfigReceiver.KEY_TRANSITION, ConfigReceiver.DEFAULT_TRANSITION)
            ?: ConfigReceiver.DEFAULT_TRANSITION
        val use24Hour = clock24HourEnabled()
        val temp = if (
            prefs.getBoolean(
                ConfigReceiver.KEY_WEATHER_FAHRENHEIT,
                ConfigReceiver.DEFAULT_WEATHER_FAHRENHEIT,
            )
        ) "72°F" else "22°C"
        val timeFmt = remember(use24Hour) {
            SimpleDateFormat(if (use24Hour) "HH:mm" else "h:mm a", Locale.getDefault())
        }
        val dateFmt = remember { SimpleDateFormat("EEE, d MMM", Locale.getDefault()) }
        LaunchedEffect(use24Hour) {
            while (true) {
                now = System.currentTimeMillis()
                delay(1000)
            }
        }
        Card("Live preview") {
            Box(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF151515), Color(0xFF28364A), Color(0xFF171717)),
                        ),
                    )
                    .padding(horizontal = 22.dp, vertical = 24.dp),
            ) {
                Column {
                    Text(
                        timeFmt.format(Date(now)),
                        color = Color.White,
                        fontSize = 46.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${dateFmt.format(Date(now))}  $temp  ☁️ Cloudy",
                        color = PortalColors.Text.copy(alpha = 0.86f),
                        fontSize = 17.sp,
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PreviewChip("Transition: ${transitionLabel(transition)}")
                        PreviewChip(if (use24Hour) "24-hour clock" else "12-hour clock")
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Body("A quick look at the clock style, weather line, and current transition before you leave setup.")
        }
    }

    @Composable
    private fun PreviewChip(label: String) {
        Text(
            label,
            color = PortalColors.Text,
            fontSize = 13.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(Color(0x2AFFFFFF))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }

    /**
     * "Time per photo": a slider that snaps across [DELAY_PRESETS] (4 sec … 1 day). The slider value
     * is the preset INDEX (a plain linear ms slider can't span that range). The label updates live
     * while dragging; the pref is committed on release so we don't thrash prefs every tick.
     */
    @Composable
    private fun DurationSliderRow(onChanged: () -> Unit) {
        var idx by remember {
            mutableStateOf(nearestPresetIndex(getLong(ConfigReceiver.KEY_DELAY_MS, ConfigReceiver.DEFAULT_DELAY_MS)))
        }
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Time per photo", color = PortalColors.Text, fontSize = 18.sp, modifier = Modifier.weight(1f))
                Text(
                    fmtDelay(DELAY_PRESETS[idx]),
                    color = PortalColors.Blue, fontSize = 18.sp, fontWeight = FontWeight.Medium,
                )
            }
            Slider(
                value = idx.toFloat(),
                onValueChange = { idx = it.roundToInt() },
                valueRange = 0f..(DELAY_PRESETS.size - 1).toFloat(),
                steps = DELAY_PRESETS.size - 2,
                onValueChangeFinished = {
                    setLong(ConfigReceiver.KEY_DELAY_MS, DELAY_PRESETS[idx]); onChanged()
                },
                colors = SliderDefaults.colors(
                    thumbColor = PortalColors.Blue,
                    activeTrackColor = PortalColors.Blue,
                    inactiveTrackColor = PortalColors.Text.copy(alpha = 0.18f),
                    // Hide the per-step tick dots — 13 of them clutter the track.
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent,
                ),
            )
            // Muted range endpoints so the span (seconds → a day) is legible at a glance.
            Row(Modifier.fillMaxWidth()) {
                Text(
                    fmtDelay(DELAY_PRESETS.first()),
                    color = PortalColors.TextMuted, fontSize = 12.sp, modifier = Modifier.weight(1f),
                )
                Text(fmtDelay(DELAY_PRESETS.last()), color = PortalColors.TextMuted, fontSize = 12.sp)
            }
        }
    }

    // ----------------------------------------------------------------- prefs/helpers

    private fun getLong(key: String, def: Long) = prefs.getLong(key, def)
    private fun setLong(key: String, v: Long) = prefs.edit().putLong(key, v).apply()
    private fun getInt(key: String, def: Int) = prefs.getInt(key, def)
    private fun setInt(key: String, v: Int) = prefs.edit().putInt(key, v).apply()
    private fun clock24HourEnabled(): Boolean =
        if (prefs.contains(ConfigReceiver.KEY_CLOCK_24H)) {
            prefs.getBoolean(ConfigReceiver.KEY_CLOCK_24H, ConfigReceiver.DEFAULT_CLOCK_24H)
        } else {
            android.text.format.DateFormat.is24HourFormat(this)
        }

    @Composable
    private fun TimeSliderRow(label: String, key: String, def: Int, onChanged: () -> Unit) {
        var minute by remember(key) { mutableIntStateOf(getInt(key, def)) }
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = PortalColors.Text, fontSize = 18.sp, modifier = Modifier.weight(1f))
                Text(
                    fmtTimeOfDay(minute),
                    color = PortalColors.Blue, fontSize = 18.sp, fontWeight = FontWeight.Medium,
                )
            }
            Slider(
                value = (minute / TIME_STEP_MINUTES).toFloat(),
                onValueChange = { minute = it.roundToInt() * TIME_STEP_MINUTES },
                valueRange = 0f..((24 * 60 / TIME_STEP_MINUTES) - 1).toFloat(),
                steps = (24 * 60 / TIME_STEP_MINUTES) - 2,
                onValueChangeFinished = {
                    setInt(key, minute); onChanged()
                },
                colors = SliderDefaults.colors(
                    thumbColor = PortalColors.Blue,
                    activeTrackColor = PortalColors.Blue,
                    inactiveTrackColor = PortalColors.Text.copy(alpha = 0.18f),
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent,
                ),
            )
            Row(Modifier.fillMaxWidth()) {
                Text("12:00 AM", color = PortalColors.TextMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text("11:45 PM", color = PortalColors.TextMuted, fontSize = 12.sp)
            }
        }
    }

    companion object {
        private const val PREVIEW_W = 720 // 16:9 thumbnail decode size for the album preview
        private const val PREVIEW_H = 405
        private const val UPDATE_CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000
        private const val TIME_STEP_MINUTES = 15

        // "Time per photo" presets the slider snaps across (4 sec … 1 day, ms). Keeps the old
        // values (4s/6s/10s/30s/60s) so previously-saved delays still land on a stop.
        private val DELAY_PRESETS = longArrayOf(
            4_000, 6_000, 10_000, 30_000, // seconds
            60_000, 300_000, 600_000, 1_800_000, // 1m, 5m, 10m, 30m
            3_600_000, 10_800_000, 21_600_000, 43_200_000, // 1h, 3h, 6h, 12h
            86_400_000, // 1 day
        )
        private val FADE_CHOICES = longArrayOf(2000, 1200, 500)
        private val FADE_LABELS = arrayOf("Slow", "Normal", "Fast")
        private val TRANSITION_OPTIONS = listOf(
            TransitionOption("crossfade", "Crossfade"),
            TransitionOption("slide", "Slide"),
            TransitionOption("zoom", "Zoom"),
            TransitionOption("zoom_fade", "Zoom fade"),
            TransitionOption("instant", "Instant"),
            TransitionOption("push", "Push"),
        )

        private fun cycle(choices: LongArray, cur: Long, fallback: Int): Long {
            for (i in choices.indices) if (choices[i] == cur) return choices[(i + 1) % choices.size]
            return choices[fallback]
        }

        private fun fadeLabel(ms: Long): String {
            for (i in FADE_CHOICES.indices) if (FADE_CHOICES[i] == ms) return FADE_LABELS[i]
            return "Normal"
        }

        /** Index of the preset closest to [ms] (so a legacy/odd saved value still maps to a stop). */
        private fun nearestPresetIndex(ms: Long): Int {
            var best = 0
            for (i in DELAY_PRESETS.indices) {
                if (Math.abs(DELAY_PRESETS[i] - ms) < Math.abs(DELAY_PRESETS[best] - ms)) best = i
            }
            return best
        }

        private fun fmtDelay(ms: Long): String = when {
            ms >= 86_400_000 -> plural(ms / 86_400_000, "day")
            ms >= 3_600_000 -> plural(ms / 3_600_000, "hour")
            ms >= 60_000 -> "${ms / 60_000} min"
            else -> "${ms / 1000} sec"
        }

        private fun fmtTimeOfDay(minute: Int): String {
            val normalized = ((minute % (24 * 60)) + (24 * 60)) % (24 * 60)
            val hour24 = normalized / 60
            val mins = normalized % 60
            val hour12 = when (val h = hour24 % 12) {
                0 -> 12
                else -> h
            }
            val ampm = if (hour24 < 12) "AM" else "PM"
            return String.format(java.util.Locale.getDefault(), "%d:%02d %s", hour12, mins, ampm)
        }

        private fun plural(n: Long, unit: String): String = "$n $unit" + if (n == 1L) "" else "s"

        private fun transitionLabel(id: String): String =
            TRANSITION_OPTIONS.firstOrNull { it.id == id }?.label ?: "Crossfade"

        private fun albumPlaybackLabel(id: String): String = when (id) {
            "album_priority" -> "Album priority"
            else -> "Shuffled merge"
        }

        private data class TransitionOption(val id: String, val label: String)
    }
}
