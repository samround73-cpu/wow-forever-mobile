package app.gamenative.ui.screen.wow

import android.content.Context
import android.graphics.Insets
import android.os.Build
import android.os.Environment
import android.view.WindowInsets as AndroidWindowInsets
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.gamenative.BuildConfig
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.ui.screen.xserver.MobileTouchControls
import app.gamenative.data.TouchGestureConfig
import app.gamenative.events.AndroidEvent
import app.gamenative.ui.screen.wow.WowClientDownloader.BUILD_INFO
import app.gamenative.ui.screen.wow.WowClientDownloader.EXE_NAME
import app.gamenative.ui.screen.wow.WowClientDownloader.FLAVOR_DIR
import app.gamenative.ui.screen.wow.WowClientDownloader.LEGACY_FLAVOR_DIR
import app.gamenative.ui.screen.wow.WowClientDownloader.LEGACY_PRODUCT
import app.gamenative.ui.screen.wow.WowClientDownloader.TARGET_PRODUCT
import app.gamenative.ui.theme.WowBackgroundGradient
import app.gamenative.ui.theme.WowBronze
import app.gamenative.ui.theme.WowError
import app.gamenative.ui.theme.WowGold
import app.gamenative.ui.theme.WowMuted
import app.gamenative.ui.theme.WowSubtle
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.AppUpdater
import app.gamenative.utils.BrightnessManager
import app.gamenative.utils.Net
import app.gamenative.utils.StorageUtils
import app.gamenative.utils.renderReleaseNotes
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import com.winlator.contents.ContentProfile
import com.winlator.contents.ContentsManager
import com.winlator.core.FileUtils
import com.winlator.core.TarCompressorUtils
import com.winlator.xenvironment.ImageFs
import com.winlator.xenvironment.ImageFsInstaller
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.IOException

private const val CONTAINER_ID = "wow_forever"

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WoWForeverScreen(
    onLaunch: (appId: String) -> Unit,
    onOpenLanguage: () -> Unit,
    retryLoginRequested: Boolean = false,
    onLoginRetryHandled: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gpu = remember { GpuProfile.detect(context) }
    val tabletLayout = LocalConfiguration.current.usesTabletLayout

    var gamePath by remember { mutableStateOf(GamePath.load(context)) }
    var files by remember { mutableStateOf(GamePath.Status()) }
    var isLaunching by remember { mutableStateOf(false) }
    var launchJob by remember { mutableStateOf<Job?>(null) }
    var region by remember(gamePath, files) { mutableStateOf(GameRegion.current(context, gamePath)) }
    var resolution by remember { mutableStateOf(RenderResolution.current(context, gpu.screenSize)) }
    var statusText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showStoragePermissionDialog by remember { mutableStateOf(false) }
    var versionStatus by remember { mutableStateOf<WowClientDownloader.VersionCheckResult?>(null) }
    var versionCheckJob by remember { mutableStateOf<Job?>(null) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var isUpdating by remember { mutableStateOf(false) }
    var updateStatusText by remember { mutableStateOf("") }
    var appUpdate by remember { mutableStateOf<AppUpdater.Release?>(null) }
    var isDownloadingAppUpdate by remember { mutableStateOf(false) }
    var appUpdateProgress by remember { mutableFloatStateOf(0f) }
    var showAppUpdateDialog by remember { mutableStateOf(false) }
    var showAppUpdatePermissionDialog by remember { mutableStateOf(false) }
    var pendingInstallPermission by remember { mutableStateOf(false) }
    var downloadedApk by remember { mutableStateOf<File?>(null) }
    var showUpgradeDialog by remember { mutableStateOf(false) }

    suspend fun fetchAppUpdate(): AppUpdater.Release? {
        val release = AppUpdater.check()
        appUpdate = release
        if (release != null) {
            showAppUpdateDialog = true
        }
        return release
    }

    fun checkAppUpdate() {
        scope.launch { fetchAppUpdate() }
    }

    fun installOrRequestPermission(apk: File) {
        if (AppUpdater.canInstall(context)) {
            AppUpdater.install(context, apk)
        } else {
            pendingInstallPermission = true
            showAppUpdatePermissionDialog = true
        }
    }

    fun performAppUpdate() {
        val target = appUpdate ?: return
        if (isDownloadingAppUpdate) return
        downloadedApk?.takeIf { it.exists() && it.length() == target.sizeBytes }?.let { apk ->
            installOrRequestPermission(apk)
            return
        }
        isDownloadingAppUpdate = true
        appUpdateProgress = 0f
        errorMessage = null
        scope.launch {
            try {
                val apk = AppUpdater.download(context, target) { progress ->
                    appUpdateProgress = progress
                }
                downloadedApk = apk
                isDownloadingAppUpdate = false
                installOrRequestPermission(apk)
            } catch (e: Exception) {
                Timber.e(e, "Error downloading app update")
                errorMessage = context.getString(
                    R.string.mobile_launcher_error_details,
                    context.getString(R.string.mobile_launcher_app_update_failed),
                    e.message.orEmpty(),
                )
                isDownloadingAppUpdate = false
            }
        }
    }

    fun checkVersionStatus() {
        versionCheckJob?.cancel()
        val checkedPath = gamePath
        val checkedRegion = region
        if (!File(checkedPath, BUILD_INFO).isFile) return
        versionCheckJob = scope.launch {
            val result = withContext(Dispatchers.IO) {
                WowClientDownloader.checkVersion(File(checkedPath), checkedRegion.cdnRegion)
            }
            if (checkedPath == gamePath && checkedRegion == region && !isLaunching && !isUpdating) versionStatus = result
        }
    }

    fun checkFiles(): Boolean {
        files = GamePath.status(context, gamePath)
        if (files.isWrongGame) {
            errorMessage = context.getString(R.string.mobile_launcher_wrong_game, files.products.joinToString(), TARGET_PRODUCT)
        }
        return files.isReady
    }

    fun denyStorageAccess() {
        files = files.copy(hasStorageAccess = false)
        showStoragePermissionDialog = true
    }

    fun resetLaunch() {
        launchJob?.cancel()
        launchJob = null
        isLaunching = false
        statusText = ""
    }

    fun launchGame() {
        if (isLaunching || isUpdating || files.isWrongGame) return
        if (!files.hasStorageAccess) {
            showStoragePermissionDialog = true
            return
        }
        if (files.needsInstall) return
        versionCheckJob?.cancel()
        isLaunching = true
        errorMessage = null
        statusText = context.getString(R.string.mobile_launcher_prepare)

        launchJob = scope.launch {
            try {
                val screenSize = fitScreenSizeToWindow(context, resolution.size)
                val containerId = withContext(Dispatchers.IO) {
                    prepareLaunch(context, File(gamePath), gpu, screenSize) { msg ->
                        scope.launch(Dispatchers.Main) {
                            if (launchJob?.isActive == true) statusText = msg
                        }
                    }
                }
                statusText = context.getString(R.string.mobile_launcher_starting)
                onLaunch(containerId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: StorageAccessDeniedException) {
                isLaunching = false
                statusText = ""
                denyStorageAccess()
            } catch (e: Exception) {
                Timber.e(e, "Error launching WoW Forever")
                errorMessage = e.message ?: context.getString(R.string.mobile_launcher_start_failed)
                statusText = context.getString(R.string.mobile_launcher_start_failed)
                isLaunching = false
            }
        }
    }

    LifecycleResumeEffect(retryLoginRequested) {
        if (retryLoginRequested) {
            onLoginRetryHandled()
            resetLaunch()
            if (checkFiles()) launchGame()
        }
        onPauseOrDispose { }
    }

    LifecycleResumeEffect(gamePath) {
        // Storage access may have just been granted, which can reveal an install that was invisible before.
        if (!isUpdating && !isLaunching) gamePath = GamePath.load(context)
        checkFiles()
        if (!isLaunching) statusText = ""
        if (pendingInstallPermission && AppUpdater.canInstall(context)) {
            pendingInstallPermission = false
            downloadedApk?.takeIf { it.exists() }?.let { AppUpdater.install(context, it) }
        }
        if (!isLaunching && !isUpdating && files.isReady && versionStatus == null && versionCheckJob?.isActive != true) {
            checkVersionStatus()
        }
        onPauseOrDispose { }
    }

    DisposableEffect(Unit) {
        val reset: (Any) -> Unit = { resetLaunch() }
        PluviaApp.events.on<AndroidEvent.ForceCloseApp, Unit>(reset)
        PluviaApp.events.on<AndroidEvent.GameSessionEnded, Unit>(reset)
        onDispose {
            PluviaApp.events.off<AndroidEvent.ForceCloseApp, Unit>(reset)
            PluviaApp.events.off<AndroidEvent.GameSessionEnded, Unit>(reset)
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val path = StorageUtils.getPathFromTreeUri(context, uri) ?: return@rememberLauncherForActivityResult
        val root = GamePath.findGameRoot(File(path)) ?: GamePath.installDirFor(File(path)).also { it.mkdirs() }
        errorMessage = null
        gamePath = root.absolutePath
        GamePath.save(context, gamePath)
        versionStatus = null
        val ok = checkFiles()
        if (!files.hasStorageAccess) {
            showStoragePermissionDialog = true
        } else if (ok) {
            checkVersionStatus()
        }
    }

    val addonPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        errorMessage = null
        scope.launch {
            try {
                val installed = withContext(Dispatchers.IO) {
                    val zip = File(context.cacheDir, "addon-import.zip")
                    try {
                        context.contentResolver.openInputStream(uri)?.use { input -> zip.outputStream().use { input.copyTo(it) } }
                            ?: throw IOException(context.getString(R.string.mobile_launcher_file_error))
                        AddonImporter.install(zip, File(gamePath, "$FLAVOR_DIR/Interface/AddOns"))
                    } finally {
                        zip.delete()
                    }
                }
                SnackbarManager.show(context.getString(R.string.mobile_launcher_addon_installed, installed.joinToString()))
            } catch (e: Exception) {
                Timber.e(e, "Add-on import failed")
                if (e.isPermissionDenied()) denyStorageAccess()
                errorMessage = context.getString(
                    R.string.mobile_launcher_error_details,
                    context.getString(R.string.mobile_launcher_addon_failed),
                    e.message.orEmpty(),
                )
            }
        }
    }

    val canPlay = !isLaunching && !isUpdating && !files.needsInstall && !files.isWrongGame

    val view = LocalView.current
    DisposableEffect(isUpdating) {
        view.keepScreenOn = isUpdating
        onDispose { view.keepScreenOn = false }
    }

    // Progress shows under the cards, which is below the fold on a landscape handheld.
    val scrollState = rememberScrollState()
    LaunchedEffect(isUpdating, isLaunching) {
        if (isUpdating || isLaunching) scrollState.animateScrollTo(scrollState.maxValue)
    }

    fun downloadGameFiles(
        failure: String,
        work: (File, WowClientDownloader.ProgressListener) -> Unit,
        onSuccess: () -> Unit,
    ) {
        if (isUpdating || isLaunching) return
        if (!files.hasStorageAccess) {
            showStoragePermissionDialog = true
            return
        }
        isUpdating = true
        updateStatusText = context.getString(R.string.mobile_launcher_connecting_cdn)
        errorMessage = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    work(File(gamePath)) { message, args ->
                        val status = context.getString(message, *args)
                        scope.launch(Dispatchers.Main) { updateStatusText = status }
                    }
                }
                isUpdating = false
                onSuccess()
            } catch (e: Exception) {
                Timber.e(e, failure)
                if (e.isPermissionDenied()) denyStorageAccess()
                errorMessage = context.getString(R.string.mobile_launcher_error_details, failure, e.message.orEmpty())
                isUpdating = false
            }
        }
    }

    fun performInstall() {
        showUpgradeDialog = false
        downloadGameFiles(context.getString(R.string.mobile_launcher_install_failed), { root, onStatus -> WowClientDownloader.install(root, region.cdnRegion, onStatus) }) {
            versionStatus = null
            if (checkFiles()) {
                checkVersionStatus()
            }
        }
    }

    fun performUpdate() {
        val target = versionStatus ?: return
        downloadGameFiles(context.getString(R.string.mobile_launcher_update_failed), { root, onStatus -> WowClientDownloader.updateGame(root, target, onStatus) }) {
            checkFiles()
            versionStatus = target.copy(isOutdated = false, localVersion = target.remoteVersion)
        }
    }

    fun repairClient() {
        val selectedRegion = region
        downloadGameFiles(context.getString(R.string.mobile_repair_failed), { root, onStatus ->
            WowClientDownloader.repair(root, selectedRegion.cdnRegion, onStatus)
        }) {
            checkFiles()
            checkVersionStatus()
            SnackbarManager.show(context.getString(R.string.mobile_repair_done))
        }
    }

    LaunchedEffect(gamePath) {
        versionStatus = null
        val ready = checkFiles()
        if (!files.hasStorageAccess) showStoragePermissionDialog = true
        if (files.isLegacyBeta) showUpgradeDialog = true
        if (ready) {
            checkVersionStatus()
            checkAppUpdate()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(WowBackgroundGradient))
            .safeDrawingPadding()
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .then(if (tabletLayout) Modifier.widthIn(max = 720.dp) else Modifier)
                .fillMaxSize()
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = "WORLD OF WARCRAFT",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Serif,
                    letterSpacing = 3.sp,
                    color = Color(0xFFF0E6D2)
                )
                Text(
                    text = "FOREVER MOBILE",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    color = WowGold
                )
                Spacer(modifier = Modifier.height(6.dp))
                val edition = stringResource(R.string.mobile_launcher_edition, gpu.edition)
                Text(
                    text = if (tabletLayout) stringResource(R.string.mobile_launcher_tablet_edition, edition) else edition,
                    fontSize = 11.sp,
                    color = WowSubtle,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .border(1.dp, Color(0xFF2A3C54), RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF131D2E).copy(alpha = 0.85f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    SectionTitle(stringResource(R.string.mobile_launcher_readiness), WowGold)
                    Spacer(modifier = Modifier.height(14.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CheckItem(
                            label = if (files.exeExists) stringResource(R.string.mobile_launcher_arm64_installed, EXE_NAME)
                                else stringResource(R.string.mobile_launcher_arm64_missing),
                            ready = files.exeExists,
                        )
                        CheckItem(label = stringResource(R.string.mobile_launcher_game_data), ready = files.dataExists)
                        CheckItem(
                            label = when {
                                files.isWrongGame -> stringResource(R.string.mobile_launcher_product_info, BUILD_INFO, TARGET_PRODUCT)
                                files.isLegacyBeta -> stringResource(R.string.mobile_launcher_beta_info, BUILD_INFO)
                                else -> stringResource(R.string.mobile_launcher_install_info, BUILD_INFO)
                            },
                            ready = files.hasForever,
                        )
                        CheckItem(label = stringResource(R.string.mobile_launcher_storage_access), ready = files.hasStorageAccess)
                        appUpdate?.let {
                            CheckItem(
                                label = stringResource(R.string.mobile_launcher_app_build_update, BuildConfig.VERSION_NAME, it.version),
                                ready = false,
                            )
                        } ?: CheckItem(
                            label = stringResource(R.string.mobile_launcher_app_build, BuildConfig.VERSION_NAME),
                            ready = true,
                        )
                        versionStatus?.let { version ->
                            CheckItem(
                                label = if (version.isOutdated) {
                                    stringResource(R.string.mobile_launcher_client_build_update, version.localVersion, version.remoteVersion)
                                } else {
                                    stringResource(R.string.mobile_launcher_client_build, version.localVersion)
                                },
                                ready = !version.isOutdated,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = Color(0xFF1E2D44))
                    Spacer(modifier = Modifier.height(12.dp))

                    Text(text = stringResource(R.string.mobile_launcher_path, gamePath), fontSize = 11.sp, color = WowSubtle, maxLines = 2)

                    Row {
                        OptionMenu(
                            stringResource(R.string.mobile_launcher_region, stringResource(region.label)),
                            Icons.Default.Public, GameRegion.entries, region, { context.getString(it.label) }, !isLaunching && !isUpdating,
                        ) {
                            GameRegion.save(context, it)
                            region = it
                            // Regions can be on different builds, so the install may now need an update.
                            versionStatus = null
                            checkVersionStatus()
                        }
                        OptionMenu(
                            stringResource(R.string.mobile_launcher_resolution, resolution.label),
                            Icons.Default.AspectRatio, RenderResolution.entries, resolution, { it.label }, !isLaunching,
                        ) {
                            RenderResolution.save(context, it)
                            resolution = it
                        }
                    }
                    var touchControlsHidden by remember { mutableStateOf(MobileTouchControls.isOverlayHidden(context)) }
                    LinkButton(
                        stringResource(
                            if (touchControlsHidden) R.string.mobile_launcher_touch_controls_hidden
                            else R.string.mobile_launcher_touch_controls_shown,
                        ),
                        if (touchControlsHidden) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        !isLaunching,
                    ) {
                        touchControlsHidden = !touchControlsHidden
                        MobileTouchControls.setOverlayHidden(context, touchControlsHidden)
                    }

                    errorMessage?.let {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(text = stringResource(R.string.mobile_launcher_error, it), fontSize = 12.sp, color = WowError, fontWeight = FontWeight.Medium)
                    }
                }
            }

            if (files.needsInstall && files.hasStorageAccess) {
                Spacer(modifier = Modifier.height(16.dp))
                InstallCard(
                    isUpgrade = files.isLegacyBeta,
                    gamePath = gamePath,
                    freeSpaceBytes = remember(gamePath, isUpdating) { GamePath.freeSpaceBytes(gamePath) },
                    enabled = !isUpdating && !isLaunching,
                    onChangeLocation = { folderPicker.launch(null) },
                )
            }

            versionStatus?.takeIf { it.isOutdated && !files.needsInstall }?.let { version ->
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .border(1.dp, Color(0xFFE53E3E).copy(alpha = 0.6f), RoundedCornerShape(16.dp)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2D1515).copy(alpha = 0.85f)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = WowError, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            SectionTitle(stringResource(R.string.mobile_launcher_update_required), WowError)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (version.remoteVersion == version.localVersion) {
                                stringResource(R.string.mobile_update_region_files, stringResource(region.label))
                            } else {
                                stringResource(R.string.mobile_launcher_update_build, version.remoteVersion, version.localVersion)
                            },
                            fontSize = 12.sp,
                            color = Color(0xFFFED7D7),
                            lineHeight = 16.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.mobile_launcher_update_instructions),
                            fontSize = 11.sp,
                            color = Color(0xFFCBD5E1)
                        )
                    }
                }
            }

            appUpdate?.let { update ->
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .border(1.dp, WowGold.copy(alpha = 0.6f), RoundedCornerShape(16.dp)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E2838).copy(alpha = 0.85f)),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = WowGold, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            SectionTitle(stringResource(R.string.mobile_launcher_app_update_available, update.version), WowGold)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.mobile_launcher_app_update_info),
                            fontSize = 12.sp,
                            color = Color(0xFFCBD5E1),
                            lineHeight = 16.sp,
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        PrimaryButton(
                            text = when {
                                isDownloadingAppUpdate -> stringResource(R.string.mobile_launcher_download_app)
                                downloadedApk?.exists() == true -> stringResource(R.string.mobile_launcher_install_app, update.version)
                                else -> stringResource(R.string.mobile_launcher_update_app, update.version)
                            },
                            icon = Icons.Default.Refresh,
                            enabled = !isDownloadingAppUpdate && !isLaunching && !isUpdating,
                            onClick = {
                                if (downloadedApk?.exists() == true) {
                                    performAppUpdate()
                                } else {
                                    showAppUpdateDialog = true
                                }
                            },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (isLaunching || isUpdating) {
                    CircularProgressIndicator(color = WowGold, modifier = Modifier.size(36.dp))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = if (isUpdating) updateStatusText else statusText,
                        fontSize = 13.sp,
                        color = Color(0xFFE2E8F0),
                        textAlign = TextAlign.Center
                    )
                } else {
                    when {
                        !files.hasStorageAccess -> PrimaryButton(
                            text = stringResource(R.string.mobile_launcher_grant_storage),
                            icon = Icons.Default.FolderOpen,
                            enabled = !isLaunching && !isUpdating,
                            onClick = { StorageUtils.requestManageExternalStoragePermission(context) },
                        )
                        files.needsInstall -> {
                            PrimaryButton(
                                text = if (files.isLegacyBeta) stringResource(R.string.mobile_launcher_upgrade) else stringResource(R.string.mobile_launcher_download_install),
                                icon = Icons.Default.Download,
                                enabled = !isLaunching && !isUpdating,
                                onClick = ::performInstall,
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            OutlinedButton(
                                onClick = { folderPicker.launch(null) },
                                modifier = Modifier.fillMaxWidth(0.85f).height(48.dp),
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(20.dp), tint = WowGold)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(stringResource(R.string.mobile_launcher_locate_files), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = WowGold)
                            }
                        }
                        versionStatus?.isOutdated == true -> {
                            PrimaryButton(
                                text = stringResource(R.string.mobile_launcher_update_to, versionStatus?.remoteVersion.orEmpty()),
                                icon = Icons.Default.Refresh,
                                enabled = canPlay,
                                onClick = ::performUpdate,
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            OutlinedButton(
                                onClick = { launchGame() },
                                enabled = canPlay,
                                modifier = Modifier.fillMaxWidth(0.85f).height(48.dp),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Text(text = stringResource(R.string.mobile_launcher_launch_outdated), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = WowError)
                            }
                        }
                        else -> PrimaryButton(
                            text = if (files.isWrongGame) stringResource(R.string.mobile_launcher_requires_forever) else stringResource(R.string.mobile_launcher_play),
                            icon = Icons.Default.PlayArrow,
                            enabled = canPlay,
                            onClick = ::launchGame,
                        )
                    }

                    if (canPlay && files.hasStorageAccess) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.mobile_launcher_browser_login_hint),
                            modifier = Modifier.fillMaxWidth(0.85f),
                            fontSize = 12.sp,
                            color = WowMuted,
                            textAlign = TextAlign.Center,
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Wrap the utility buttons on narrow portrait screens.
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                    ) {
                        LinkButton(stringResource(R.string.mobile_launcher_refresh), Icons.Default.Refresh) {
                            checkFiles()
                            checkVersionStatus()
                            checkAppUpdate()
                        }
                        if (!files.needsInstall) {
                            LinkButton(stringResource(R.string.mobile_launcher_location), Icons.Default.FolderOpen) { folderPicker.launch(null) }
                            if (files.hasForever && files.hasStorageAccess) {
                                LinkButton(stringResource(R.string.mobile_repair_client), Icons.Default.CheckCircle, onClick = ::repairClient)
                            }
                            // Some file managers label zips as generic binary files, so both types are offered.
                            LinkButton(stringResource(R.string.mobile_launcher_addon), Icons.Default.Extension, enabled = !isLaunching && !isUpdating) {
                                addonPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                            }
                        }
                        LinkButton(stringResource(R.string.mobile_diagnostics), Icons.Default.Analytics) {
                            showDiagnostics = true
                        }
                        LinkButton(
                            stringResource(R.string.language), Icons.Default.Language,
                            enabled = !isDownloadingAppUpdate, onClick = onOpenLanguage,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showDiagnostics) MobileDiagnosticsDialog { showDiagnostics = false }
    if (showUpgradeDialog) {
        AlertDialog(
            onDismissRequest = { showUpgradeDialog = false },
            title = { Text(text = stringResource(R.string.mobile_launcher_beta_title), fontWeight = FontWeight.Bold, fontSize = 17.sp, color = WowGold) },
            text = {
                Text(
                    text = stringResource(R.string.mobile_launcher_beta_details, FLAVOR_DIR),
                    fontSize = 13.sp,
                    color = Color(0xFFE2E8F0),
                    lineHeight = 18.sp,
                )
            },
            confirmButton = {
                Button(
                    onClick = ::performInstall,
                    colors = ButtonDefaults.buttonColors(containerColor = WowBronze, contentColor = Color.White),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(stringResource(R.string.mobile_launcher_upgrade), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        showUpgradeDialog = false
                        folderPicker.launch(null)
                    }) {
                        Text(stringResource(R.string.mobile_launcher_choose_folder), color = WowMuted)
                    }
                    TextButton(onClick = { showUpgradeDialog = false }) {
                        Text(stringResource(R.string.mobile_launcher_later), color = WowMuted)
                    }
                }
            },
            containerColor = Color(0xFF1B2838),
            shape = RoundedCornerShape(16.dp),
        )
    }

    if (showStoragePermissionDialog) {
        AlertDialog(
            onDismissRequest = { showStoragePermissionDialog = false },
            title = { Text(text = stringResource(R.string.mobile_launcher_storage_title), fontWeight = FontWeight.Bold, fontSize = 17.sp, color = WowGold) },
            text = {
                Text(
                    text = stringResource(R.string.mobile_launcher_storage_details),
                    fontSize = 13.sp,
                    color = Color(0xFFE2E8F0),
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showStoragePermissionDialog = false
                        StorageUtils.requestManageExternalStoragePermission(context)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = WowBronze, contentColor = Color.White),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(stringResource(R.string.mobile_launcher_open_settings), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showStoragePermissionDialog = false }) {
                    Text(stringResource(R.string.cancel), color = WowMuted)
                }
            },
            containerColor = Color(0xFF1B2838),
            shape = RoundedCornerShape(16.dp),
        )
    }

    if (showAppUpdateDialog) {
        appUpdate?.let { update ->
            AlertDialog(
                onDismissRequest = {
                    if (!isDownloadingAppUpdate) showAppUpdateDialog = false
                },
                title = {
                    Text(
                        text = stringResource(R.string.mobile_launcher_app_update_available, update.version),
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        color = WowGold,
                    )
                },
                text = {
                    Column {
                        if (update.notes.isNotBlank()) {
                            Text(
                                text = renderReleaseNotes(update.notes),
                                fontSize = 12.sp,
                                color = Color(0xFFCBD5E1),
                                lineHeight = 16.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 240.dp)
                                    .verticalScroll(rememberScrollState()),
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                        if (isDownloadingAppUpdate) {
                            Text(
                                text = stringResource(R.string.mobile_launcher_percent, (appUpdateProgress * 100).toInt()),
                                fontSize = 12.sp,
                                color = WowGold,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { appUpdateProgress },
                                modifier = Modifier.fillMaxWidth(),
                                color = WowGold,
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = ::performAppUpdate,
                        enabled = !isDownloadingAppUpdate,
                        colors = ButtonDefaults.buttonColors(containerColor = WowBronze, contentColor = Color.White),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(
                            text = when {
                                isDownloadingAppUpdate -> stringResource(R.string.mobile_launcher_download_app)
                                downloadedApk?.exists() == true -> stringResource(R.string.install)
                                else -> stringResource(R.string.mobile_launcher_update_now)
                            },
                            fontWeight = FontWeight.Bold,
                        )
                    }
                },
                dismissButton = {
                    if (!isDownloadingAppUpdate) {
                        TextButton(onClick = { showAppUpdateDialog = false }) {
                            Text(stringResource(R.string.mobile_launcher_later), color = WowMuted)
                        }
                    }
                },
                containerColor = Color(0xFF1B2838),
                shape = RoundedCornerShape(16.dp),
            )
        }
    }

    if (showAppUpdatePermissionDialog) {
        AlertDialog(
            onDismissRequest = { showAppUpdatePermissionDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.mobile_launcher_install_permission),
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = WowGold,
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.mobile_launcher_install_permission_details),
                    fontSize = 13.sp,
                    color = Color(0xFFE2E8F0),
                    lineHeight = 18.sp,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showAppUpdatePermissionDialog = false
                        pendingInstallPermission = true
                        AppUpdater.openInstallPermissionSettings(context)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = WowBronze, contentColor = Color.White),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(stringResource(R.string.mobile_launcher_open_settings), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAppUpdatePermissionDialog = false }) {
                    Text(stringResource(R.string.cancel), color = WowMuted)
                }
            },
            containerColor = Color(0xFF1B2838),
            shape = RoundedCornerShape(16.dp),
        )
    }
}

private fun prefs(context: Context) = context.getSharedPreferences("wow_forever", Context.MODE_PRIVATE)

/**
 * The Battle.net region WoW logs into. Without the Battle.net app to pick one, WoW stops at a region picker before
 * login, so the launcher writes it to Config.wtf as the portal.
 */
enum class GameRegion(@StringRes val label: Int) {
    US(R.string.mobile_region_americas),
    EU(R.string.mobile_region_europe),
    KR(R.string.mobile_region_korea),
    TW(R.string.mobile_region_taiwan);

    /** The region's name in Blizzard's patch server tables. */
    val cdnRegion get() = name.lowercase()

    companion object {
        private const val KEY_REGION = "region"

        /** The region picked in the launcher, or null to keep the install's current portal. */
        fun saved(context: Context): GameRegion? = prefs(context).getString(KEY_REGION, null)?.let(::of)

        fun save(context: Context, region: GameRegion) {
            prefs(context).edit().putString(KEY_REGION, region.name).apply()
        }

        fun current(context: Context, gamePath: String): GameRegion = saved(context) ?: of(portalOf(File(gamePath))) ?: US

        private fun of(portal: String) = entries.firstOrNull { it.name.equals(portal, ignoreCase = true) }
    }
}

/** The 16:9 render budget, before it's reshaped to the window's aspect ratio. Defaults to the GPU profile's size. */
enum class RenderResolution(val size: String, val label: String) {
    HD("1280x720", "720p"), HD_PLUS("1600x900", "900p"), FULL_HD("1920x1080", "1080p");

    companion object {
        private const val KEY_RESOLUTION = "resolution"

        fun current(context: Context, default: String): RenderResolution =
            prefs(context).getString(KEY_RESOLUTION, null)?.let { saved -> entries.firstOrNull { it.name == saved } }
                ?: entries.first { it.size == default }

        fun save(context: Context, resolution: RenderResolution) {
            prefs(context).edit().putString(KEY_RESOLUTION, resolution.name).apply()
        }
    }
}

object GamePath {
    private const val KEY_GAME_PATH = "game_path"

    private val defaultPath = File(Environment.getExternalStorageDirectory(), "WoW Forever")

    data class Status(
        val exeExists: Boolean = false,
        val dataExists: Boolean = false,
        val buildInfoExists: Boolean = false,
        val products: Set<String> = emptySet(),
        val hasStorageAccess: Boolean = true,
    ) {
        val hasForever get() = TARGET_PRODUCT in products
        val isLegacyBeta get() = !hasForever && LEGACY_PRODUCT in products
        val isWrongGame get() = buildInfoExists && products.isNotEmpty() && !hasForever && !isLegacyBeta
        val needsInstall get() = !isWrongGame && !(hasForever && dataExists)
        val isReady get() = exeExists && dataExists && hasForever && hasStorageAccess
    }

    fun status(context: Context, path: String): Status {
        val root = File(path)
        return Status(
            exeExists = File(root, "$FLAVOR_DIR/$EXE_NAME").let { it.isFile && it.length() > 0L },
            dataExists = File(root, "Data").listFiles()?.isNotEmpty() == true,
            buildInfoExists = File(root, BUILD_INFO).isFile,
            products = productsOf(root),
            hasStorageAccess = StorageUtils.hasStoragePermission(context, path),
        )
    }

    /**
     * The folder the user picked, as long as it still exists. Otherwise the first default location (internal storage, then
     * each SD card) that holds an install, preferring the full release over the beta.
     */
    fun load(context: Context): String {
        val saved = prefs(context).getString(KEY_GAME_PATH, null)?.let(::File)
        if (saved != null && saved.isDirectory) return saved.absolutePath
        val defaults = listOf(defaultPath) + context.getExternalFilesDirs(null).drop(1).filterNotNull()
            .map { File(it.path.substringBefore("/Android/"), defaultPath.name) }
        val usable = defaults.firstOrNull { TARGET_PRODUCT in productsOf(it) } ?: defaults.firstOrNull { LEGACY_PRODUCT in productsOf(it) }
        return (usable ?: saved ?: defaultPath).absolutePath
    }

    /** Installs into [picked] if it's empty, or into a new "WoW Forever" folder inside it (e.g. an SD card's root). */
    fun installDirFor(picked: File): File =
        if (picked.listFiles().isNullOrEmpty()) picked else File(picked, defaultPath.name)

    fun save(context: Context, path: String) {
        prefs(context).edit().putString(KEY_GAME_PATH, path).apply()
    }

    fun isReady(context: Context, path: String = load(context)): Boolean =
        path.isNotEmpty() && status(context, path).isReady

    fun findGameRoot(picked: File): File? =
        sequenceOf(picked, picked.parentFile, File(picked, "WoW Forever"), File(picked, "World of Warcraft"))
            .filterNotNull()
            .firstOrNull { File(it, BUILD_INFO).isFile }

    fun freeSpaceBytes(path: String): Long =
        generateSequence(File(path)) { it.parentFile }.firstOrNull { it.exists() }?.usableSpace ?: 0L

    private fun productsOf(root: File) = WowClientDownloader.installedProducts(File(root, BUILD_INFO))
}

@Composable
private fun InstallCard(isUpgrade: Boolean, gamePath: String, freeSpaceBytes: Long, enabled: Boolean, onChangeLocation: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth(0.9f)
            .border(1.dp, WowGold.copy(alpha = 0.6f), RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E2838).copy(alpha = 0.85f)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Download, contentDescription = null, tint = WowGold, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                SectionTitle(if (isUpgrade) stringResource(R.string.mobile_launcher_beta_title) else stringResource(R.string.mobile_launcher_install_game), WowGold)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (isUpgrade) {
                    stringResource(R.string.mobile_launcher_beta_install, FLAVOR_DIR)
                } else {
                    stringResource(R.string.mobile_launcher_install_details)
                },
                fontSize = 12.sp,
                color = Color(0xFFCBD5E1),
                lineHeight = 16.sp,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.mobile_launcher_install_location, gamePath, freeSpaceBytes / 1e9),
                fontSize = 11.sp,
                color = WowSubtle,
            )
            if (!isUpgrade) {
                LinkButton(stringResource(R.string.mobile_launcher_change_folder), Icons.Default.FolderOpen, enabled = enabled, onClick = onChangeLocation)
                Text(
                    text = stringResource(R.string.mobile_launcher_find_hint),
                    fontSize = 11.sp,
                    color = WowSubtle,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, color: Color) {
    Text(text = text, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = color)
}

@Composable
private fun PrimaryButton(text: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(0.85f).height(56.dp),
        colors = ButtonDefaults.buttonColors(containerColor = WowBronze, contentColor = Color.White),
        shape = RoundedCornerShape(14.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(10.dp))
        Text(text = text, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}

@Composable
private fun LinkButton(text: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = WowMuted)
        Spacer(modifier = Modifier.width(6.dp))
        Text(text, fontSize = 12.sp, color = WowMuted, maxLines = 1)
    }
}

@Composable
private fun <T> OptionMenu(
    text: String,
    icon: ImageVector,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    enabled: Boolean,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        LinkButton(text, icon, enabled) { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    trailingIcon = { if (option == selected) Icon(Icons.Default.Check, contentDescription = null, tint = WowGold) },
                    onClick = {
                        expanded = false
                        if (option != selected) onSelect(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun CheckItem(label: String, ready: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (ready) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = null,
            tint = if (ready) Color(0xFF48BB78) else Color(0xFFED8936),
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(text = label, fontSize = 13.sp, color = if (ready) Color(0xFFCBD5E1) else Color(0xFFED8936))
    }
}

private class StorageAccessDeniedException(cause: Throwable) : Exception(cause)

private fun Throwable.isPermissionDenied() =
    message?.let { "EPERM" in it || "Operation not permitted" in it } == true

private const val PROTON_VERSION = "proton-11.0-90624-arm64ec"
private const val WINE_ENTRY = "$PROTON_VERSION-1"

/**
 * Reshapes the GPU's 16:9 render budget to the current window's aspect ratio so the game fills
 * non-16:9 screens (e.g. a foldable's near-square inner display) instead of being letterboxed.
 * The pixel count stays the same as [baseSize], so GPU cost matches the 16:9 default.
 */
private fun fitScreenSizeToWindow(context: Context, baseSize: String): String {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        val display = context.resources.displayMetrics
        return fitScreenSize(baseSize, display.widthPixels, display.heightPixels)
    }
    val metrics = context.getSystemService(WindowManager::class.java).currentWindowMetrics
    val width = metrics.bounds.width()
    val height = metrics.bounds.height()
    val windowed = BrightnessManager.findActivity(context)?.isInMultiWindowMode == true
    // When starting fullscreen from portrait, the game rotates to landscape after this measurement.
    val bars = if (windowed || (!PrefManager.mobileGameFullscreen && width >= height)) {
        metrics.windowInsets.getInsetsIgnoringVisibility(AndroidWindowInsets.Type.systemBars())
    } else {
        Insets.NONE
    }
    return fitScreenSize(baseSize, width - bars.left - bars.right, height - bars.top - bars.bottom)
}

internal fun fitScreenSize(baseSize: String, windowWidth: Int, windowHeight: Int): String {
    if (windowWidth <= 0 || windowHeight <= 0) return baseSize
    val (baseWidth, baseHeight) = baseSize.split("x").map { it.toInt() }
    val aspect = maxOf(windowWidth, windowHeight).toFloat() / minOf(windowWidth, windowHeight)
    val height = sqrt(baseWidth * baseHeight / aspect)
    val width = height * aspect
    return "${(width / 2).roundToInt() * 2}x${(height / 2).roundToInt() * 2}"
}

private val launchLock = Mutex()

private suspend fun prepareLaunch(
    context: Context,
    gameRoot: File,
    gpu: GpuProfile,
    screenSize: String,
    onStatus: (String) -> Unit,
): String = launchLock.withLock {
    val region = GameRegion.current(context, gameRoot.path)
    MobileLaunchDiagnostics.start(context, gameRoot, region, gpu)
    check(File(gameRoot, BUILD_INFO).exists()) { context.getString(R.string.mobile_launcher_build_info_missing, BUILD_INFO, gameRoot.path) }
    try {
        ensureGameConfig(gameRoot, screenSize, GameRegion.saved(context))
        installMobileAddon(context, gameRoot)
    } catch (e: Exception) {
        if (e.isPermissionDenied()) throw StorageAccessDeniedException(e)
        throw e
    }

    val arm64Exe = File(gameRoot, "$FLAVOR_DIR/$EXE_NAME")
    if (!arm64Exe.isFile || arm64Exe.length() == 0L || WowClientDownloader.hasInterruptedUpdate(gameRoot)) {
        WowClientDownloader.download(gameRoot, region.cdnRegion) { message, args ->
            onStatus(context.getString(message, *args))
        }
        check(arm64Exe.isFile && arm64Exe.length() > 0L) { context.getString(R.string.mobile_launcher_arm64_install_failed) }
    }

    currentCoroutineContext().ensureActive()
    installBundledComponents(context, gpu, onStatus)
    prepareRuntime(context, onStatus)
    MobileLaunchDiagnostics.runtimeReady()
    currentCoroutineContext().ensureActive()

    onStatus(context.getString(R.string.mobile_launcher_wine_container))
    val containerManager = ContainerManager(context)
    val config = containerConfig(gameRoot, gpu, screenSize, WowMachineIdentity.load(context))
    val container = containerManager.getContainerById(CONTAINER_ID)
    if (container == null) {
        val prefix = File(ImageFs.find(context).rootDir, "home/${ImageFs.USER}-$CONTAINER_ID")
        if (prefix.isDirectory && File(prefix, ".container").length() == 0L) {
            check(FileUtils.delete(prefix)) { context.getString(R.string.mobile_launcher_prefix_cleanup_failed) }
        }
        val created = containerManager.createContainer(CONTAINER_ID, config)
        check(created != null && created.configFile.length() > 0) {
            context.getString(R.string.mobile_launcher_prefix_error, freeInternalMiB(context))
        }
    } else {
        container.loadData(config)
        container.saveData()
    }
    currentCoroutineContext().ensureActive()
    MobileLaunchDiagnostics.containerReady()
    CONTAINER_ID
}

private fun containerConfig(gameRoot: File, gpu: GpuProfile, screenSize: String, identity: WowMachineIdentity): JSONObject {
    val samsungEnv = if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) " FD_DEV_FEATURES=enable_tp_ubwc_flag_hint=1" else ""
    val turnipEnv = gpu.tuDebug?.let { " MESA_VK_WSI_PRESENT_MODE=mailbox TU_DEBUG=$it" }.orEmpty()
    val pixelLogEnv = if (BuildConfig.PIXEL_VULKAN_TEST) " DXVK_LOG_LEVEL=info DXVK_LOG_PATH=." else ""
    val envVars = "WRAPPER_MAX_IMAGE_COUNT=0 ZINK_DESCRIPTORS=lazy ZINK_DEBUG=compact,deck_emu MESA_SHADER_CACHE_DISABLE=false MESA_SHADER_CACHE_MAX_SIZE=512MB mesa_glthread=true WINEESYNC=0$turnipEnv$pixelLogEnv VKD3D_SHADER_MODEL=6_0 PULSE_LATENCY_MSEC=144 ${WowMachineIdentity.HOSTNAME_ENV}=${identity.computerName}$samsungEnv"
    return JSONObject().apply {
        put("id", CONTAINER_ID)
        put("name", "WoW Forever")
        put("screenSize", screenSize)
        put("envVars", envVars)
        put("graphicsDriver", gpu.graphicsWrapper)
        put("graphicsDriverVersion", gpu.driver)
        put("graphicsDriverConfig", gpu.graphicsDriverConfig)
        put("displayRenderer", "vulkan")
        put("dxwrapper", "dxvk-${gpu.dxvkVersion}-1")
        put("dxwrapperConfig", "version=${gpu.dxvkVersion}-1")
        put("wineVersion", WINE_ENTRY)
        put("containerVariant", "bionic")
        put("fexcoreVersion", "2609-0")
        put("drives", Container.DEFAULT_DRIVES + "G:${gameRoot.path}")
        put("executablePath", "G:\\$FLAVOR_DIR\\$EXE_NAME")
        put("execArgs", "-d3d11")
        put("showFPS", true)
        // Keep running in the background: a suspended client drops its Battle.net connection, e.g. while the
        // player fetches an authenticator code from another app.
        put("touchscreenMode", true)
        put("gestureConfig", TouchGestureConfig(
            longPressEnabled = true,
            longPressMouseBehavior = TouchGestureConfig.MOUSE_BEHAVIOR_CLICK,
        ).toJson())
        put("suspendPolicy", Container.SUSPEND_POLICY_NEVER)
        put("startupSelection", Container.STARTUP_SELECTION_AGGRESSIVE.toInt())
        put("wow64Mode", true)
        put("wincomponents", "direct3d=0,directsound=0,directinput8=0,directinput=0,directmusic=0,directshow=0,directplay=0,vcrun2010=0,wmdecoder=0,opengl=0")
    }
}

private val CONFIG_DEFAULTS = linkedMapOf(
    "agentUID" to "\"$TARGET_PRODUCT\"",
    "gxApi" to "\"D3D11\"",
    "textLocale" to "\"enUS\"",
    "audioLocale" to "\"enUS\"",
    "gxMaximize" to "\"1\"",
    "graphicsQuality" to "\"0\"",
    "ResampleQuality" to "\"0\"",
    "RenderScale" to "\"1\"",
    "farclip" to "\"1200\"",
    "horizonClip" to "\"1200\"",
    "RAIDfarclip" to "\"1200\"",
    "RAIDhorizonClip" to "\"1200\"",
    "shadowMode" to "\"0\"",
    "graphicsShadowQuality" to "\"0\"",
    "raidGraphicsShadowQuality" to "\"0\"",
    "worldBaseMip" to "\"0\"",
    "RAIDworldBaseMip" to "\"0\"",
    "graphicsTextureResolution" to "\"2\"",
    "raidGraphicsTextureResolution" to "\"2\"",
    "componentTextureLevel" to "\"0\"",
    "RAIDcomponentTextureLevel" to "\"0\"",
    "entityShadowFadeScale" to "\"0\"",
    "refraction" to "\"0\"",
    "groundEffectDensity" to "\"16\"",
    "GamePadEnable" to "\"1\"",
    "InputDeviceInterfaceStyle" to "\"0\"",
    // Answers the first-run Screen Narrator prompt, which otherwise blocks login.txt auto-login.
    "showScreenNarrationDialog" to "\"0\"",
    "accessibilityScreenNarrationEnabled" to "\"0\"",
)

internal fun ensureGameConfig(root: File, screenSize: String, region: GameRegion?) {
    val flavorDir = File(root, FLAVOR_DIR)
    flavorDir.mkdirs()
    val flavorInfo = File(flavorDir, ".flavor.info")
    if (!flavorInfo.exists()) {
        flavorInfo.writeText("Product Flavor!STRING:0\n$TARGET_PRODUCT\n")
    }
    // Handheld defaults are only written on first boot so in-game changes stick, with a few exceptions that are
    // forced on every launch. gxApi: DXVK only works with D3D11, and a config copied from a Mac would otherwise
    // select Metal. Resolutions match the container, which the launcher sizes: WoW's "auto" picks a 720p size
    // because it sees a low-end Intel GPU via Wine, and saves that size on exit as if the player chose it.
    // The portal is the region picked in the launcher, or kept as is (filled from the install's region when
    // missing); without the Battle.net app to supply it, WoW otherwise stops at a region picker before login.
    val configWtf = File(root, CONFIG_WTF)
    val betaConfigWtf = File(root, "$LEGACY_FLAVOR_DIR/WTF/Config.wtf")
    if (!configWtf.exists() && betaConfigWtf.isFile) {
        // Carry the player's settings over from the beta, minus the lines that pointed it at the beta servers.
        configWtf.parentFile?.mkdirs()
        val betaOnly = listOf("SET portal ", "SET agentUID ", "SET CACHE-")
        val carried = betaConfigWtf.readLines().filterNot { line -> betaOnly.any { line.startsWith(it, ignoreCase = true) } }
        configWtf.writeText((carried + "SET agentUID ${CONFIG_DEFAULTS.getValue("agentUID")}").joinToString("\n") + "\n")
    }
    if (!configWtf.exists()) {
        configWtf.parentFile?.mkdirs()
        configWtf.writeText(CONFIG_DEFAULTS.entries.joinToString("\n") { "SET ${it.key} ${it.value}" } + "\n")
    }

    // Select the desktop interface before WoW loads its UI so its gamepad action bars never open over the touch HUD.
    val forced = mapOf(
        "gxApi" to CONFIG_DEFAULTS.getValue("gxApi"),
        "InputDeviceInterfaceStyle" to CONFIG_DEFAULTS.getValue("InputDeviceInterfaceStyle"),
        "portal" to "\"${region?.name ?: portalOf(root)}\"",
    ) + RESOLUTION_KEYS.associateWith { "\"$screenSize\"" }
    val forcedLines = forced.map { (key, value) -> "SET $key $value" }
    val original = configWtf.readLines()
    val (current, kept) = original.partition { line -> forced.keys.any { line.startsWith("SET $it ", ignoreCase = true) } }
    if (current.size != forcedLines.size || !current.containsAll(forcedLines)) {
        configWtf.writeText((kept + forcedLines).joinToString("\n", postfix = "\n"))
    }
}

private val RESOLUTION_KEYS = listOf("gxFullscreenResolution", "gxWindowedResolution")

private const val CONFIG_WTF = "$FLAVOR_DIR/WTF/Config.wtf"

// The portal Config.wtf already points at, else the install's region from .build.info. Runs while the launcher is
// composed, before storage access may be granted: after a reinstall, files the old install left can't be read yet.
private fun portalOf(root: File) =
    runCatching { File(root, CONFIG_WTF).takeIf { it.isFile }?.readLines() }.getOrNull()
        ?.firstOrNull { it.startsWith("SET portal ", ignoreCase = true) }
        ?.substringAfter(" ")?.substringAfter(" ")?.trim('"')?.takeIf { it.isNotBlank() }
        ?: WowClientDownloader.readBuildInfo(File(root, BUILD_INFO))?.get("Branch")?.takeIf { it.isNotBlank() }?.uppercase()
        ?: WowClientDownloader.defaultBranch().uppercase()

private suspend fun prepareRuntime(context: Context, onStatus: (String) -> Unit) {
    val image = ImageFs.find(context)
    if (!ImageFsInstaller.requiresInstall(image, Container.BIONIC)) {
        ImageFsInstaller.ensureBionicLib(context, image.rootDir)
        return
    }

    val archive = File(context.filesDir, "imagefs_bionic.txz")
    if (archive.length() == 0L && context.assets.list("")?.contains(archive.name) != true) {
        onStatus(context.getString(R.string.mobile_launcher_runtime_download))
        var previous = -1
        Net.fetchFileWithFallback(archive.name, archive) { ratio ->
            val progress = (ratio * 100).toInt().coerceIn(0, 100)
            if (progress != previous) {
                previous = progress
                onStatus(context.getString(R.string.mobile_launcher_runtime_download_progress, progress))
            }
        }
    }
    currentCoroutineContext().ensureActive()

    onStatus(context.getString(R.string.mobile_launcher_runtime_install))
    var previous = -1
    val installed = ImageFsInstaller.installIfNeededFuture(
        context, context.assets, Container.BIONIC, WINE_ENTRY,
    ) { percent ->
        val bounded = percent.coerceIn(0, 100)
        if (bounded != previous) {
            previous = bounded
            onStatus(context.getString(R.string.mobile_launcher_runtime_install_progress, bounded))
        }
    }.get()
    check(installed && !ImageFsInstaller.requiresInstall(image, Container.BIONIC)) {
        context.getString(R.string.mobile_launcher_runtime_error, freeInternalMiB(context))
    }
}

private fun freeInternalMiB(context: Context) = context.filesDir.usableSpace / (1024 * 1024)

private fun installBundledComponents(context: Context, gpu: GpuProfile, onStatus: (String) -> Unit) {
    gpu.bundledDriver?.let { driverAsset ->
        val driverDir = File(context.filesDir, "contents/adrenotools/${gpu.driver}")
        installComponent(driverDir, R.string.mobile_launcher_graphics_install, onStatus, context) { staging ->
            FileUtils.extractZipFromAssets(context, "bundled_components/$driverAsset", staging) &&
                File(staging, "meta.json").length() > 0
        }
    }

    val protonDir = File(ImageFs.getSharedProtonDir(context), PROTON_VERSION)
    installComponent(protonDir, R.string.mobile_launcher_proton_install, onStatus, context) { staging ->
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, "bundled_components/$PROTON_VERSION.wcp", staging) &&
            listOf("profile.json", "bin/wine", "prefixPack.txz").all { File(staging, it).length() > 0 }
    }

    val dxvkDir = File(ContentsManager.getContentTypeDir(context, ContentProfile.ContentType.CONTENT_TYPE_DXVK), gpu.dxvkVersion)
    installComponent(dxvkDir, R.string.mobile_launcher_dxvk_install, onStatus, context) { staging ->
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, "bundled_components/dxvk-${gpu.dxvkVersion}.wcp", staging) &&
            listOf("profile.json", "system32/d3d11.dll", "system32/dxgi.dll").all { File(staging, it).length() > 0 }
    }
}

private fun installComponent(
    destination: File,
    @StringRes step: Int,
    onStatus: (String) -> Unit,
    context: Context,
    extract: (File) -> Boolean,
) {
    if (File(destination, ".mobile-install-complete").isFile) return
    onStatus(context.getString(step))
    val staging = File(destination.parentFile, ".${destination.name}.part")
    FileUtils.delete(staging)
    try {
        check(staging.mkdirs() && extract(staging)) {
            context.getString(R.string.mobile_launcher_component_error, destination.name, freeInternalMiB(context))
        }
        check(File(staging, ".mobile-install-complete").createNewFile())
        if (destination.exists()) check(FileUtils.delete(destination)) { "Could not replace the incomplete ${destination.name}." }
        check(staging.renameTo(destination)) { "Could not install ${destination.name}." }
    } finally {
        FileUtils.delete(staging)
    }
}
