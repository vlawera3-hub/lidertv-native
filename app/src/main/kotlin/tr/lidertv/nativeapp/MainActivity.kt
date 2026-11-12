package tr.lidertv.nativeapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import tr.lidertv.nativeapp.ui.design.spx
import tr.lidertv.nativeapp.ui.common.design
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import tr.lidertv.nativeapp.data.prefs.SidebarPosition
import tr.lidertv.nativeapp.data.prefs.UserPreferencesStore
import tr.lidertv.nativeapp.data.repo.HistoryRepository
import tr.lidertv.nativeapp.data.repo.PlaybackContext
import tr.lidertv.nativeapp.data.repo.ProviderRepository
import tr.lidertv.nativeapp.data.sync.SyncScheduler
import tr.lidertv.nativeapp.nav.Routes
import tr.lidertv.nativeapp.ui.AppViewModel
import tr.lidertv.nativeapp.ui.categories.CategoriesScreen
import tr.lidertv.nativeapp.ui.common.ScreenFocusHost
import tr.lidertv.nativeapp.ui.components.SidebarNav
import tr.lidertv.nativeapp.ui.favorites.FavoritesScreen
import tr.lidertv.nativeapp.ui.guide.GuideGridScreen
import tr.lidertv.nativeapp.ui.home.HomeScreen
import tr.lidertv.nativeapp.ui.live.LiveScreen
import tr.lidertv.nativeapp.ui.movies.MovieDetailScreen
import tr.lidertv.nativeapp.ui.player.PlayerScreen
import tr.lidertv.nativeapp.ui.search.SearchScreen
import tr.lidertv.nativeapp.ui.series.SeriesDetailScreen
import tr.lidertv.nativeapp.ui.settings.SettingsScreen
import tr.lidertv.nativeapp.ui.theme.LiderTvTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Carries a one-shot "open this URL+title in the player as soon as the
 * Composition is up" intent. Set by [MainActivity.kickoffStartupTasks] when
 * `autoPlayLastOnLaunch` is enabled; consumed by [LiderTvAppRoot] once.
 */
object StartupNav {
    data class Pending(val url: String, val title: String)
    val pending = MutableStateFlow<Pending?>(null)
    /** Route à ouvrir une fois l'application affichée (ex. « Regarder le direct » depuis le chargement). */
    val pendingRoute = MutableStateFlow<String?>(null)
    /** Débogage uniquement : rubrique de Réglages à ouvrir. */
    val debugRub = MutableStateFlow<Int?>(null)
    /** Demande d'appairage cloud depuis un autre écran (accueil vide) : Réglages › Sources la consomme. */
    val cloudPairRequest = MutableStateFlow(false)
    /** Demande d'envoi depuis le téléphone (accueil vide) : Réglages › Sources ouvre la fenêtre « Depuis le téléphone ». */
    val phoneSendRequest = MutableStateFlow(false)
    /** Demande d'ouverture de la Recherche (touche Recherche / micro de la télécommande), depuis n'importe quel écran. */
    val searchRequest = MutableStateFlow(0)
    /** Build debug : requête préremplie (Recherche) et thème forcé (captures). */
    val debugQuery = MutableStateFlow<String?>(null)
    val debugTheme = MutableStateFlow<String?>(null)
    /** Accueil vide (tactile) : ouvrir Réglages et lancer l'appairage cloud. */
    val startPairing = MutableStateFlow(false)
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private companion object { const val REQ_CHANNEL_BROWSABLE = 4107 }

    // Tout ce qui ne sert qu'en tâche de fond est injecté en Lazy et résolu sur Dispatchers.IO : avant, l'injection de
    // l'activité construisait sur le thread principal ProviderRepository (OkHttp + clients), CloudSyncManager (lectures
    // synchrones de préférences), RemindersScheduler (canal de notification, Binder), RecordingScheduler… avant setContent.
    @Inject lateinit var prefsStore: dagger.Lazy<UserPreferencesStore>
    @Inject lateinit var providerRepo: dagger.Lazy<ProviderRepository>
    @Inject lateinit var historyRepo: dagger.Lazy<HistoryRepository>
    @Inject lateinit var playback: PlaybackContext
    @Inject lateinit var syncCoordinator: dagger.Lazy<tr.lidertv.nativeapp.data.sync.SyncCoordinator>
    @Inject lateinit var deepLinks: dagger.Lazy<tr.lidertv.nativeapp.nav.DeepLinkHandler>
    @Inject lateinit var recordingScheduler: dagger.Lazy<tr.lidertv.nativeapp.data.recording.RecordingScheduler>
    @Inject lateinit var cloudSync: dagger.Lazy<tr.lidertv.nativeapp.data.config.CloudSyncManager>
    @Inject lateinit var remindersScheduler: dagger.Lazy<tr.lidertv.nativeapp.data.reminders.RemindersScheduler>
    @Inject lateinit var googleTv: dagger.Lazy<tr.lidertv.nativeapp.data.tv.GoogleTvSync>
    @Inject lateinit var phoneRemote: dagger.Lazy<tr.lidertv.nativeapp.data.remote.RemoteController>
    @Inject lateinit var remoteBus: tr.lidertv.nativeapp.data.remote.RemoteBus

    override fun onStop() {
        super.onStop()
        tr.lidertv.nativeapp.ui.common.AppForeground.visible = false
        tr.lidertv.nativeapp.perf.PerfTelemetry.setTracking(false)
        // Télécommande sur le téléphone : le serveur local s'arrête quand l'appli quitte le premier plan.
        lifecycleScope.launch(Dispatchers.IO) { runCatching { phoneRemote.get().onForeground(false) } }
    }

    override fun onStart() {
        super.onStart()
        tr.lidertv.nativeapp.ui.common.AppForeground.visible = true
        tr.lidertv.nativeapp.perf.PerfTelemetry.setTracking(true)
        lifecycleScope.launch(Dispatchers.IO) { runCatching { phoneRemote.get().onForeground(true) } }
        // Retour sur l'appli (sortie de veille comprise) : guide rechargé s'il est périmé — l'appli reste en mémoire
        // des jours sur une box et le guide ne couvre que les 24 h suivant sa synchro.
        if (::providerRepo.isInitialized) lifecycleScope.launch(Dispatchers.IO) { providerRepo.get().refreshEpgIfStale() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // SplashScreen API : affiche le thème de lancement tout de suite et évite
        // l'écran noir pendant l'init Hilt/Room.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (!tr.lidertv.nativeapp.ui.common.isTelevision(this)) {
            // Téléphone / tablette : barres système transparentes (le contenu passe dessous, les marges viennent des insets).
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        }
        RemoteLog.info("activity", "onCreate restoredState=${savedInstanceState != null}")
        handleDebugIntent(intent)
        handleDeepLink(intent)
        // Ré-arme rappels et enregistrements programmés (alarmes perdues après une mise à jour ou un arrêt forcé).
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { remindersScheduler.get().rescheduleAll() }
            runCatching { recordingScheduler.get().rearmAll() }
        }
        setContent { Root() }
        // Télécommande sur le téléphone : flèches / OK / Retour injectés DANS l'appli (aucune permission système).
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                remoteBus.actions.collect { a -> if (a is tr.lidertv.nativeapp.data.remote.RemoteAction.Key) injectKey(a.key) }
            }
        }
        tr.lidertv.nativeapp.perf.PerfTelemetry.attach(this)
        // Android 12+ : le geste « accueil » entre en image dans l'image sans passer par onUserLeaveHint, tant que le lecteur est affiché.
        if (android.os.Build.VERSION.SDK_INT >= 31 && !tr.lidertv.nativeapp.ui.common.isTelevision(this)) {
            lifecycleScope.launch {
                androidx.compose.runtime.snapshotFlow { tr.lidertv.nativeapp.ui.design.Ux.playerActive }.collect { shown ->
                    runCatching {
                        setPictureInPictureParams(
                            android.app.PictureInPictureParams.Builder().setAspectRatio(android.util.Rational(16, 9)).setAutoEnterEnabled(shown).build(),
                        )
                    }
                }
            }
        }
        kickoffStartupTasks(coldStart = savedInstanceState == null)
        // Google TV : invite système « Afficher la chaîne Nouveautés sur l'accueil ? », une seule fois, quand la chaîne
        // existe sans être affichée. Après l'accueil (30 s), jamais pendant une lecture ni appli en arrière-plan.
        lifecycleScope.launch {
            kotlinx.coroutines.delay(45_000)
            if (!tr.lidertv.nativeapp.ui.common.AppForeground.visible || tr.lidertv.nativeapp.ui.design.Ux.playerActive) return@launch
            runCatching {
                val intent = kotlinx.coroutines.withContext(Dispatchers.IO) { googleTv.get().browsableRequest() } ?: return@runCatching
                @Suppress("DEPRECATION") startActivityForResult(intent, REQ_CHANNEL_BROWSABLE)
            }
        }
        // Auto-update flow: query GitHub Releases on launch and, if a newer
        // version is found, download + fire the system install Intent without
        // asking the user first. They still get the OS's "Install this app?"
        // prompt — that one can't be skipped without device-owner privileges.
        lifecycleScope.launch {
            // Pas pendant le démarrage : on laisse l'interface devenir interactive d'abord
            // (et l'invite système d'installation ne vole pas le focus au lancement).
            kotlinx.coroutines.delay(30_000)
            val info = tr.lidertv.nativeapp.update.UpdateChecker.checkForUpdate()
                ?: return@launch
            tr.lidertv.nativeapp.RemoteLog.info(
                "update",
                "auto-installing ${info.tag}",
            )
            runCatching {
                tr.lidertv.nativeapp.update.UpdateChecker
                    .downloadAndInstall(this@MainActivity, info)
            }.onFailure {
                tr.lidertv.nativeapp.RemoteLog.warn(
                    "update",
                    "auto-install failed: ${it.javaClass.simpleName} ${it.message}",
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CHANNEL_BROWSABLE) runCatching { googleTv.get().onBrowsableResult(resultCode == RESULT_OK) }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleDebugIntent(intent)
        handleDeepLink(intent)
    }

    /** Lien profond `lidertv://…` (notification de rappel, Google TV) : résolu dans le catalogue local. */
    private fun handleDeepLink(intent: android.content.Intent?) {
        // Recherche vocale : ACTION_SEARCH + requête ; sinon lien profond lidertv://… (VIEW).
        val voiceQuery = if (intent?.action == android.content.Intent.ACTION_SEARCH) intent.getStringExtra(android.app.SearchManager.QUERY)?.trim()?.takeIf { it.isNotEmpty() } else null
        val link = voiceQuery?.let { tr.lidertv.nativeapp.nav.DeepLink.Search(it) }
            ?: tr.lidertv.nativeapp.nav.DeepLink.parse(intent?.data?.toString()) ?: return
        lifecycleScope.launch(Dispatchers.IO) { runCatching { deepLinks.get().handle(link) } }
    }

    /** Build debug seulement : `am start --es debug_route settings --ei debug_rub 1` ouvre un écran précis (captures, tests). */
    private fun handleDebugIntent(intent: android.content.Intent?) {
        if (!BuildConfig.DEBUG || intent == null) return
        intent.getStringExtra("debug_route")?.let { StartupNav.pendingRoute.value = it }
        intent.getStringExtra("debug_query")?.let { StartupNav.debugQuery.value = it }
        intent.getStringExtra("debug_theme")?.let { StartupNav.debugTheme.value = it }
        tr.lidertv.nativeapp.ui.common.DebugConnectivity.forceOffline = intent.getBooleanExtra("debug_offline", false)
        if (intent.hasExtra("debug_rub")) StartupNav.debugRub.value = intent.getIntExtra("debug_rub", 0)
        // Mesures (debug) : moteur (auto|exo|vlc), décodage (auto|hw|sw) et préréglage de tampon.
        intent.getStringExtra("debug_lang")?.let { v -> lifecycleScope.launch(Dispatchers.IO) { prefsStore.get().setLanguage(v) } }
        intent.getStringExtra("debug_engine")?.let { v -> lifecycleScope.launch(Dispatchers.IO) { prefsStore.get().setPlayerEngine(v) } }
        intent.getStringExtra("debug_decoder")?.let { v -> lifecycleScope.launch(Dispatchers.IO) { prefsStore.get().setDecoderMode(v) } }
        intent.getStringExtra("debug_buffer")?.let { v -> lifecycleScope.launch(Dispatchers.IO) { prefsStore.get().setBufferPreset(v) } }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        tr.lidertv.nativeapp.ui.mobile.PipState.active = isInPictureInPictureMode
    }

    /**
     * When the user presses Home while a stream is playing, enter PiP so the
     * stream keeps going in a corner. Falls back silently on devices that
     * don't support it (some TV firmwares).
     */
    /** Touches Recherche et micro de la télécommande : ouvrent la Recherche par-dessus l'écran courant (Retour y revient). */
    private val keyGuard = tr.lidertv.nativeapp.ui.common.KeyGuard { RemoteLog.info("keys", it) }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action != android.view.KeyEvent.ACTION_MULTIPLE && keyGuard.shouldDrop(
                event.keyCode, event.action == android.view.KeyEvent.ACTION_DOWN, event.repeatCount,
                event.eventTime, android.os.SystemClock.uptimeMillis(), event.downTime,
            )) return true
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            tr.lidertv.nativeapp.ui.common.InputClock.lastKeyCode = event.keyCode
            tr.lidertv.nativeapp.ui.common.InputClock.lastKeyMs = android.os.SystemClock.uptimeMillis()
        }
        if (event.keyCode == android.view.KeyEvent.KEYCODE_SEARCH || event.keyCode == android.view.KeyEvent.KEYCODE_VOICE_ASSIST) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) StartupNav.searchRequest.value += 1
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** Envoie une touche (appui + relâchement) au contenu de l'activité, comme une vraie télécommande. */
    private fun injectKey(k: tr.lidertv.nativeapp.data.remote.RemoteKey) {
        val code = when (k) {
            tr.lidertv.nativeapp.data.remote.RemoteKey.UP -> android.view.KeyEvent.KEYCODE_DPAD_UP
            tr.lidertv.nativeapp.data.remote.RemoteKey.DOWN -> android.view.KeyEvent.KEYCODE_DPAD_DOWN
            tr.lidertv.nativeapp.data.remote.RemoteKey.LEFT -> android.view.KeyEvent.KEYCODE_DPAD_LEFT
            tr.lidertv.nativeapp.data.remote.RemoteKey.RIGHT -> android.view.KeyEvent.KEYCODE_DPAD_RIGHT
            tr.lidertv.nativeapp.data.remote.RemoteKey.OK -> android.view.KeyEvent.KEYCODE_DPAD_CENTER
            tr.lidertv.nativeapp.data.remote.RemoteKey.BACK -> android.view.KeyEvent.KEYCODE_BACK
        }
        val t = android.os.SystemClock.uptimeMillis()
        window.decorView.dispatchKeyEvent(android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_DOWN, code, 0))
        window.decorView.dispatchKeyEvent(android.view.KeyEvent(t, t + 40, android.view.KeyEvent.ACTION_UP, code, 0))
    }

    override fun onSearchRequested(): Boolean { StartupNav.searchRequest.value += 1; return true }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        if (playback.current.value == null) return
        // TV : jamais d'image dans l'image. Beaucoup de box l'« acceptent » sans rien afficher : le son continuait
        // après avoir quitté l'application. Le lecteur se met en pause à l'arrêt de l'activité (PlayerScreen).
        if (tr.lidertv.nativeapp.ui.common.isTelevision(this)) return
        // Téléphone / tablette : image dans l'image seulement depuis l'écran du lecteur (jamais depuis l'accueil).
        if (!tr.lidertv.nativeapp.ui.mobile.shouldEnterPip(hasPlayback = true, playerShown = tr.lidertv.nativeapp.ui.design.Ux.playerActive)) return
        runCatching {
            val params = android.app.PictureInPictureParams.Builder()
                .setAspectRatio(android.util.Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        }
    }

    /**
     * Best-effort startup tasks. Both are gated by user prefs.
     *
     *  1. Auto-sync providers if `autoSyncOnLaunch` is on AND the configured
     *     [UserPrefs.syncIntervalHours] interval has elapsed since the last
     *     successful sync. Interval 0 means "every launch".
     *  2. Auto-play the most recently watched item by emitting a Pending
     *     entry on [StartupNav.pending], which the NavGraph picks up.
     */
    private fun kickoffStartupTasks(coldStart: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            val prefs = prefsStore.get().flow.first()
            // Écran d'ouverture (Réglages › Affichage) : seulement au vrai lancement (pas après une rotation ni une
            // recréation), et jamais par-dessus un lien profond déjà en attente. Tout de suite, avant le délai qui suit.
            val target = tr.lidertv.nativeapp.ui.simple.SimpleHome.startupTarget(prefs.simpleMode, tr.lidertv.nativeapp.nav.StartScreen.parse(prefs.startScreen, prefs.autoPlayLastOnLaunch))
            val deepLinkPending = StartupNav.pending.value != null || StartupNav.pendingRoute.value != null
            if (coldStart && !deepLinkPending && target is tr.lidertv.nativeapp.nav.StartupTarget.Route) StartupNav.pendingRoute.value = target.route
            if (coldStart && !deepLinkPending && target == tr.lidertv.nativeapp.nav.StartupTarget.LastChannel) {
                val firstProvider = providerRepo.get().observeProviders().first().firstOrNull()
                if (firstProvider != null) {
                    // Dernière CHAÎNE regardée ; à défaut (aucune chaîne dans l'historique), le dernier élément regardé.
                    val last = historyRepo.get().recentByKind(firstProvider.id, "LIVE", 1).first().firstOrNull()
                        ?: historyRepo.get().recent(firstProvider.id, 1).first().firstOrNull()
                    if (last != null) {
                        playback.set(PlaybackContext.Item(
                            providerId = last.providerId, kind = last.kind, remoteId = last.remoteId,
                            title = last.title, poster = last.poster, streamUrl = last.streamUrl,
                            parentRemoteId = last.parentRemoteId,
                        ))
                        StartupNav.pending.value = StartupNav.Pending(last.streamUrl, last.title)
                    }
                }
            }

            // Appli déjà remplie : synchro cloud, catalogue, rappels… attendent que l'accueil soit affiché (3 s) au lieu
            // de concurrencer son premier rendu sur un CPU modeste. Première installation : rien n'est retardé.
            if (providerRepo.get().observeProviders().first().any { it.lastLiveSyncAt > 0 }) kotlinx.coroutines.delay(3_000)

            // (Re-)apply the background sync schedule from the stored prefs
            // every time the app starts so a re-install / OS restart picks up
            // where we left off.
            // Une seule décision par mode (avant : posé puis annulé à chaque lancement en mode manuel).
            SyncScheduler.schedule(this@MainActivity, if (prefs.syncMode == "auto" || prefs.syncMode == "scheduled") prefs.syncIntervalHours else 0)
            // Sources du compte cloud : à l'ouverture, puis toutes les 6 h (travail périodique).
            tr.lidertv.nativeapp.data.config.CloudSyncWorker.schedule(this@MainActivity)
            cloudSync.get().watchDisplayPrefs()
            cloudSync.get().watchSharedState()
            runCatching { cloudSync.get().sync() }

            // Synchro incrémentale : le TTL (par partie du catalogue) décide de ce qui est rechargé ;
            // une source jamais synchronisée ou vide l'est TOUJOURS, même si la synchro auto est coupée.
            val all = providerRepo.get().observeProviders().first()
            // Mode : auto (TTL selon l'appareil/le réseau) · à chaque lancement · planifiée (travail périodique) · manuelle.
            all.forEach { p -> if (p.lastLiveSyncAt == 0L || (prefs.syncMode != "manual" && prefs.syncMode != "scheduled" && prefs.autoSyncOnLaunch)) syncCoordinator.get().request(p.id) }
            if (prefs.syncMode == "scheduled") SyncScheduler.scheduleDaily(this@MainActivity, prefs.syncHour, prefs.syncUnmeteredOnly)

        }
    }
}

@androidx.tv.material3.ExperimentalTvMaterial3Api
@Composable
private fun Root(vm: AppViewModel = hiltViewModel()) {
    val prefs by vm.prefs.collectAsState()
    val ctxForDevice = androidx.compose.ui.platform.LocalContext.current
    val lang = tr.lidertv.nativeapp.i18n.AppLang.fromCode(prefs.language)
    val strings = tr.lidertv.nativeapp.i18n.stringsFor(lang)
    // « Système » résout la langue de l'appareil : un téléphone en arabe doit aussi passer en RTL.
    val resolvedLang = if (lang == tr.lidertv.nativeapp.i18n.AppLang.System &&
        androidx.compose.ui.platform.LocalConfiguration.current.locales.get(0)?.language == "ar")
        tr.lidertv.nativeapp.i18n.AppLang.Arabic else lang
    val direction = if (resolvedLang == tr.lidertv.nativeapp.i18n.AppLang.Arabic)
        androidx.compose.ui.unit.LayoutDirection.Rtl
    else
        androidx.compose.ui.unit.LayoutDirection.Ltr
    val adaptiveVm: tr.lidertv.nativeapp.adaptive.AdaptiveViewModel = hiltViewModel()
    val adaptive by adaptiveVm.state.collectAsState()
    val lowRam = adaptive.auto.lowRam
    androidx.compose.runtime.CompositionLocalProvider(
        tr.lidertv.nativeapp.ui.common.LocalLowRam provides lowRam,
        tr.lidertv.nativeapp.adaptive.LocalAdaptive provides adaptive,
        tr.lidertv.nativeapp.i18n.LocalStrings provides strings,
        tr.lidertv.nativeapp.i18n.LocalDs provides tr.lidertv.nativeapp.i18n.designStringsFor(lang),
        tr.lidertv.nativeapp.ui.mobile.LocalMobileStrings provides tr.lidertv.nativeapp.ui.mobile.mobileStringsFor(lang),
        tr.lidertv.nativeapp.ui.common.LocalChannelDisplay provides tr.lidertv.nativeapp.ui.common.ChannelDisplay(prefs.showChannelLogos, tr.lidertv.nativeapp.ui.common.GuideNameWidth.parse(prefs.guideNameWidth)),
        androidx.compose.ui.platform.LocalLayoutDirection provides direction,
        tr.lidertv.nativeapp.ui.design.LocalAnimations provides tr.lidertv.nativeapp.ui.design.Animations.resolve(
            tr.lidertv.nativeapp.ui.design.AnimationLevel.parse(prefs.animations), adaptive.auto.uiEffects,
        ),
    ) {
        val dbgTheme by StartupNav.debugTheme.collectAsState()
        tr.lidertv.nativeapp.ui.theme.ApplyUxTheme(
            dbgTheme?.let { tr.lidertv.nativeapp.data.prefs.AppTheme.parse(it) } ?: prefs.theme,
            accent = tr.lidertv.nativeapp.ui.design.AccentChoice.parse(prefs.accent),
            textSize = tr.lidertv.nativeapp.ui.design.TextSize.parse(prefs.textSize),
        )
        tr.lidertv.nativeapp.ui.common.ProvideUiScale {
        LiderTvTheme {
            // L'assistant de premier lancement REMPLACE l'application au lieu de
            // se superposer : avant, l'accueil restait composé derrière (premier
            // focalisable = barre latérale), donc la télécommande pilotait un
            // écran invisible et « Suivant » ne recevait jamais le focus.
            val onboarding: tr.lidertv.nativeapp.ui.onboarding.OnboardingViewModel = hiltViewModel()
            val showOnboarding by onboarding.show.collectAsState()
            val profileGate: tr.lidertv.nativeapp.ui.profile.ProfileGateViewModel = hiltViewModel()
            val askProfile by profileGate.needsSelection.collectAsState()
            when {
                askProfile == null -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                askProfile == true && showOnboarding == false -> tr.lidertv.nativeapp.ui.profile.WhoIsWatchingScreen()
                else -> when (showOnboarding) {
                null -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                true -> {
                    // L'assistant est hors de la racine qui fournit l'OverlayHost : sans le sien,
                    // ses modales (appairage cloud, formulaires) étaient composées DANS le volet
                    // de l'étape, sous son en-tête — logo en double, boutons écrasés.
                    val onboardingOverlays = androidx.compose.runtime.remember { tr.lidertv.nativeapp.ui.common.OverlayHost() }
                    Box(Modifier.fillMaxSize()) {
                        androidx.compose.runtime.CompositionLocalProvider(tr.lidertv.nativeapp.ui.common.LocalOverlayHost provides onboardingOverlays) {
                            tr.lidertv.nativeapp.ui.onboarding.OnboardingWizard(
                                onOpenSettings = { /* user can re-enter Settings via sidebar */ },
                                vm = onboarding,
                            )
                        }
                        tr.lidertv.nativeapp.ui.common.OverlayLayer(onboardingOverlays)
                    }
                }
                false -> {
                    val first: tr.lidertv.nativeapp.ui.sync.FirstSyncViewModel = hiltViewModel()
                    val firstState by first.state.collectAsState()
                    val ui = firstState?.ui
                    when {
                        // Jamais d'écran noir muet : sur une box lente en pleine écriture du catalogue,
                        // la première réponse de la base peut prendre plusieurs secondes.
                        firstState == null -> StartupLoading()
                        ui != null -> tr.lidertv.nativeapp.ui.sync.FirstSyncScreen(
                            ui,
                            onWatchLive = { StartupNav.pendingRoute.value = Routes.LIVE; first.leaveToLive() },
                            onRetry = { first.retry(ui.providerId) },
                            onFixSource = { StartupNav.pendingRoute.value = Routes.SETTINGS; first.dismiss() },
                        )
                        else -> Box(Modifier.fillMaxSize()) { LiderTvAppRoot(prefs.sidebarPosition, tr.lidertv.nativeapp.ui.simple.homeKindFor(prefs) == tr.lidertv.nativeapp.ui.simple.HomeKind.SIMPLE) }
                    }
                }
                }
            }
        }
        }
    }
}

@androidx.tv.material3.ExperimentalTvMaterial3Api
@Composable
private fun LiderTvAppRoot(sidebarPosition: SidebarPosition, simpleHome: Boolean) {
    val nav = rememberNavController()
    // Le lecteur reste sombre : état DÉRIVÉ des destinations visibles (écrit avant NavHost, donc
    // avant toute lecture des jetons par le lecteur ; aucun compteur à tenir à jour).
    val visibleEntries by nav.visibleEntries.collectAsState()
    val playerShown = tr.lidertv.nativeapp.ui.theme.isPlayerShown(visibleEntries.map { it.destination.route })
    if (tr.lidertv.nativeapp.ui.design.Ux.playerActive != playerShown) tr.lidertv.nativeapp.ui.design.Ux.playerActive = playerShown
    val touch = tr.lidertv.nativeapp.ui.mobile.LocalTouch.current
    val layout = tr.lidertv.nativeapp.ui.mobile.navLayoutFor(
        tv = !touch,
        widthDp = tr.lidertv.nativeapp.ui.common.LocalUiWidthDp.current,
        heightDp = tr.lidertv.nativeapp.ui.mobile.LocalUiHeightDp.current,
    )
    // Barres système : icônes sombres sur fond clair (thème clair), toujours claires dans le lecteur.
    if (touch) {
        val view = androidx.compose.ui.platform.LocalView.current
        val lightBars = tr.lidertv.nativeapp.ui.theme.isLightEffective(tr.lidertv.nativeapp.ui.design.Ux.themeLight, playerShown)
        androidx.compose.runtime.SideEffect {
            val w = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            val c = WindowCompat.getInsetsController(w, view)
            c.isAppearanceLightStatusBars = lightBars
            c.isAppearanceLightNavigationBars = lightBars
        }
    }

    // One-shot: as soon as we have a NavController, consume any pending
    // auto-play request set during startup.
    val pending by StartupNav.pending.collectAsState()
    val pendingRoute by StartupNav.pendingRoute.collectAsState()
    LaunchedEffect(pendingRoute) {
        val r = pendingRoute ?: return@LaunchedEffect
        nav.navigate(r)
        StartupNav.pendingRoute.value = null
    }
    val searchReq by StartupNav.searchRequest.collectAsState()
    LaunchedEffect(searchReq) {
        if (searchReq > 0 && nav.currentBackStackEntry?.destination?.route != Routes.SEARCH) nav.navigate(Routes.SEARCH) { launchSingleTop = true }
    }
    LaunchedEffect(pending) {
        val p = pending ?: return@LaunchedEffect
        // Lecture demandée depuis le téléphone pendant que le lecteur est ouvert : on le remplace, on n'en empile pas un second.
        if (nav.currentBackStackEntry?.destination?.route?.startsWith("player") == true) nav.popBackStack()
        nav.navigate(Routes.player(p.url, p.title))
        StartupNav.pending.value = null
    }

    val overlays = androidx.compose.runtime.remember { tr.lidertv.nativeapp.ui.common.OverlayHost() }
    // Session du Direct partagée entre l'aperçu de la liste et le lecteur plein écran (OK sans rechargement du flux).
    val liveScope = androidx.compose.runtime.rememberCoroutineScope()
    val sharedLive = androidx.compose.runtime.remember { tr.lidertv.nativeapp.ui.player.engine.SharedLiveSession(liveScope) }
    androidx.compose.runtime.DisposableEffect(sharedLive) { onDispose { sharedLive.stop() } }
    val openSearch: () -> Unit = { nav.navigate(Routes.SEARCH) { launchSingleTop = true } }
    val navBack: () -> Unit = { nav.popBackStack() }
    Surface(
        modifier = Modifier.fillMaxSize(),
        colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
    ) {
      androidx.compose.runtime.CompositionLocalProvider(tr.lidertv.nativeapp.ui.common.LocalOverlayHost provides overlays, tr.lidertv.nativeapp.ui.mobile.LocalOpenSearch provides openSearch, tr.lidertv.nativeapp.ui.mobile.LocalNavBack provides navBack, tr.lidertv.nativeapp.ui.player.engine.LocalSharedLive provides sharedLive) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        val backEntryRoute by nav.currentBackStackEntryAsState()
        val onPlayer = tr.lidertv.nativeapp.ui.mobile.isFullscreenRoute(backEntryRoute?.destination?.route)
        when (layout) {
            tr.lidertv.nativeapp.ui.mobile.NavLayout.BOTTOM_TABS, tr.lidertv.nativeapp.ui.mobile.NavLayout.SIDE_RAIL -> {
                val profileVm: tr.lidertv.nativeapp.ui.profile.ProfileViewModel = androidx.hilt.navigation.compose.hiltViewModel()
                val prof by profileVm.current.collectAsState()
                tr.lidertv.nativeapp.ui.mobile.MobileScaffold(
                    layout = layout, navController = nav, fullscreen = onPlayer,
                    profile = prof?.let { tr.lidertv.nativeapp.ui.mobile.ProfileChip(it.initial, it.color) },
                    onProfile = { profileVm.requestSwitch() },
                    banner = { tr.lidertv.nativeapp.ui.common.SyncStatusBanner(onFixSource = { nav.navigate(Routes.SETTINGS) }) },
                ) { NavGraph(nav, simpleHome) }
            }
            tr.lidertv.nativeapp.ui.mobile.NavLayout.TV_RAIL -> Column(Modifier.fillMaxSize()) {
                tr.lidertv.nativeapp.ui.common.SyncStatusBanner(onFixSource = { nav.navigate(Routes.SETTINGS) })
                // Le contenu est décalé de la largeur REPLIÉE du rail ; le rail déplié passe par-dessus
                // (même Box) sans jamais décaler ni re-mesurer le contenu.
                // Le lecteur est plein écran : ni rail ni marge (sinon le rail volerait le focus et recouvrirait la vidéo).
                val backEntry by nav.currentBackStackEntryAsState()
                // Mode simple : les grandes tuiles remplacent le menu sur l'écran d'accueil (rail masqué, contenu sans marge).
                val fullscreen = backEntry?.destination?.route?.startsWith("player") == true ||
                    tr.lidertv.nativeapp.ui.simple.SimpleHome.railHidden(simpleHome, backEntry?.destination?.route)
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(start = if (fullscreen) 0.dp else tr.lidertv.nativeapp.ui.components.RAIL_COLLAPSED_PX.let { (it / 2f).dp }),
                    ) { NavGraph(nav, simpleHome) }
                    if (!fullscreen) SidebarNav(navController = nav)
                }
            }
        }
        tr.lidertv.nativeapp.ui.common.OverlayLayer(overlays)
        tr.lidertv.nativeapp.ui.common.ToasterHost()
        tr.lidertv.nativeapp.ui.common.ReminderBannerHost()
        // Alertes de l’administrateur (tableau de bord) : pendant la lecture plein écran, seules les urgentes (en bandeau).
        tr.lidertv.nativeapp.ui.common.AnnouncementHost(suppressed = onPlayer)
        }
      }
    }
}

@androidx.tv.material3.ExperimentalTvMaterial3Api
@Composable
private fun NavGraph(nav: androidx.navigation.NavHostController, simpleHome: Boolean) {
    // Ship the current route to the worker on every back-stack change. Lets us
    // see in /logs which screen the user was on right before a silent crash.
    androidx.compose.runtime.LaunchedEffect(nav) {
        nav.currentBackStackEntryFlow.collect { entry ->
            RemoteLog.info("nav", "→ ${entry.destination.route ?: "(unknown)"}")
            tr.lidertv.nativeapp.perf.PerfTelemetry.onRoute(entry.destination.route)
        }
    }
    NavHost(
        navController = nav,
        startDestination = Routes.HOME,
        // Pas de fondu de 700 ms entre écrans : sur un CPU de Chromecast c'est
        // du travail de composition en double, et pendant la transition le focus
        // pouvait atterrir dans l'écran sortant.
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
    ) {
        screen(Routes.HOME) {
            if (simpleHome) tr.lidertv.nativeapp.ui.simple.SimpleHomeScreen(
                onNavigate = { route -> nav.navigate(route) },
                onPlay = { url, title -> nav.navigate(Routes.player(url, title)) },
                onGoSettings = { nav.navigate(Routes.SETTINGS) },
            ) else HomeScreen(
                onGoLive = { nav.navigate(Routes.LIVE) },
                onGoMovies = { nav.navigate(Routes.MOVIES) },
                onGoSeries = { nav.navigate(Routes.SERIES) },
                onGoSettings = { nav.navigate(Routes.SETTINGS) },
                onGoCloud = { StartupNav.cloudPairRequest.value = true; nav.navigate(Routes.SETTINGS) },
                onGoPhone = { StartupNav.phoneSendRequest.value = true; nav.navigate(Routes.SETTINGS) },
                onGoGuide = { nav.navigate(Routes.GUIDE) },
                onGoSearch = { nav.navigate(Routes.SEARCH) },
                onGoFavorites = { nav.navigate(Routes.FAVORITES) },
                onPlay = { url, title -> nav.navigate(Routes.player(url, title)) },
                onOpenMovie = { id -> nav.navigate(Routes.movieDetail(id)) },
                onOpenSeries = { id -> nav.navigate(Routes.seriesDetail(id)) },
            )
        }
        screen(Routes.SIMPLE_CHANNELS) {
            tr.lidertv.nativeapp.ui.simple.SimpleChannelsScreen(onPlay = { url, title -> nav.navigate(Routes.player(url, title)) })
        }
        screen(Routes.LIVE) {
            LiveScreen(onPlay = { url, title -> nav.navigate(Routes.player(url, title)) })
        }
        screen(Routes.MOVIES) {
            androidx.compose.runtime.CompositionLocalProvider(tr.lidertv.nativeapp.ui.design.LocalPosterKind provides tr.lidertv.nativeapp.data.tmdb.TmdbKind.MOVIE) {
                tr.lidertv.nativeapp.ui.catalog.CatalogGridScreen(tr.lidertv.nativeapp.ui.catalog.CatalogKind.MOVIES, onOpen = { id -> nav.navigate(Routes.movieDetail(id)) })
            }
        }
        screen(
            Routes.MOVIE_DETAIL,
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { entry ->
            val id = entry.arguments?.getLong("id") ?: -1L
            MovieDetailScreen(
                movieId = id,
                onPlay = { url, title -> nav.navigate(Routes.player(url, title)) },
                onBack = { nav.popBackStack() },
                onOpenPerson = { name -> nav.navigate(Routes.person(name)) },
                onOpenMovie = { mid -> nav.navigate(Routes.movieDetail(mid)) },
            )
        }
        screen(
            Routes.PERSON,
            arguments = listOf(navArgument("name") { type = NavType.StringType; defaultValue = "" }),
        ) { entry ->
            tr.lidertv.nativeapp.ui.person.PersonScreen(
                name = entry.arguments?.getString("name").orEmpty(),
                onOpenMovie = { id -> nav.navigate(Routes.movieDetail(id)) },
                onOpenSeries = { id -> nav.navigate(Routes.seriesDetail(id)) },
            )
        }
        screen(Routes.SERIES) {
            androidx.compose.runtime.CompositionLocalProvider(tr.lidertv.nativeapp.ui.design.LocalPosterKind provides tr.lidertv.nativeapp.data.tmdb.TmdbKind.TV) {
                tr.lidertv.nativeapp.ui.catalog.CatalogGridScreen(tr.lidertv.nativeapp.ui.catalog.CatalogKind.SERIES, onOpen = { id -> nav.navigate(Routes.seriesDetail(id)) })
            }
        }
        screen(
            Routes.SERIES_DETAIL,
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { entry ->
            val id = entry.arguments?.getLong("id") ?: -1L
            SeriesDetailScreen(
                seriesId = id,
                onPlayEpisode = { url, title -> nav.navigate(Routes.player(url, title)) },
                onBack = { nav.popBackStack() },
                onOpenPerson = { name -> nav.navigate(Routes.person(name)) },
            )
        }
        screen(Routes.SEARCH) {
            SearchScreen(
                onOpenChannel = { url, title -> nav.navigate(Routes.player(url, title)) },
                onOpenMovie = { id -> nav.navigate(Routes.movieDetail(id)) },
                onOpenSeries = { id -> nav.navigate(Routes.seriesDetail(id)) },
            )
        }
        screen(Routes.GUIDE) {
            GuideGridScreen(
                onPlayChannel = { ch -> nav.navigate(Routes.player(ch.streamUrl, ch.name)) },
                onPlayUrl = { url, title -> nav.navigate(Routes.player(url, title)) },
            )
        }
        screen("categories") { CategoriesScreen(onBack = { nav.popBackStack() }) }
        screen("account") { tr.lidertv.nativeapp.ui.account.AccountScreen() }
        screen("diagnostic") { tr.lidertv.nativeapp.ui.settings.DiagnosticScreen() }
        screen("locked-channels") { tr.lidertv.nativeapp.ui.parental.LockedChannelsScreen(onBack = { nav.popBackStack() }) }
        screen("recordings") {
            tr.lidertv.nativeapp.ui.recordings.RecordingsScreen(
                onPlayLocal = { url, title -> nav.navigate(Routes.player(url, title)) },
            )
        }
        // Téléchargements hors ligne : lit uniquement la base, fonctionne sans réseau.
        screen(Routes.DOWNLOADS) {
            tr.lidertv.nativeapp.ui.downloads.DownloadsScreen(onPlay = { url, title -> nav.navigate(Routes.player(url, title)) })
        }
        screen(Routes.FAVORITES) {
            FavoritesScreen(
                onPlayChannel = { url, title -> nav.navigate(Routes.player(url, title)) },
                onBrowseLive = { nav.navigate(Routes.LIVE) },
                onOpenMovie = { id -> nav.navigate(Routes.movieDetail(id)) },
                onOpenSeries = { id -> nav.navigate(Routes.seriesDetail(id)) },
            )
        }
        screen(Routes.SETTINGS) { SettingsScreen(onNavigate = { route -> nav.navigate(route) }) }
        composable(
            route = Routes.PLAYER,
            arguments = listOf(
                navArgument("url") { type = NavType.StringType; defaultValue = "" },
                navArgument("title") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            val rawUrl = entry.arguments?.getString("url").orEmpty()
            val rawTitle = entry.arguments?.getString("title").orEmpty()
            val url = java.net.URLDecoder.decode(rawUrl, "UTF-8")
            val title = java.net.URLDecoder.decode(rawTitle, "UTF-8")
            PlayerScreen(
                url = url, title = title, onBack = { nav.popBackStack() }, onHome = { nav.popBackStack(Routes.HOME, false) },
                // Raccourcis de l'OSD du Direct (Ma liste, Films, Séries, Enregistrements, Réglages) : le lecteur se ferme, puis la route s'ouvre.
                onNavigate = { route -> nav.popBackStack(); nav.navigate(route) { launchSingleTop = true } },
            )
        }
    }
}


/** `composable` + focus D-pad initial et restauration (voir [ScreenFocusHost]). */
private fun NavGraphBuilder.screen(
    route: String,
    arguments: List<androidx.navigation.NamedNavArgument> = emptyList(),
    content: @Composable (androidx.navigation.NavBackStackEntry) -> Unit,
) {
    composable(route, arguments = arguments) { entry ->
        ScreenFocusHost { content(entry) }
    }
}

/** Attente sobre (logo + « Chargement… ») pendant que la base répond, au lieu d'un écran noir. */
@Composable
private fun StartupLoading() {
    Box(Modifier.fillMaxSize().background(tr.lidertv.nativeapp.ui.design.Ux.Bg), contentAlignment = androidx.compose.ui.Alignment.Center) {
        Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(28.design)) {
            tr.lidertv.nativeapp.ui.design.LogoMark(96)
            androidx.compose.material3.CircularProgressIndicator(color = tr.lidertv.nativeapp.ui.design.Ux.Accent, strokeWidth = 5.design, modifier = Modifier.size(56.design))
            androidx.tv.material3.Text(tr.lidertv.nativeapp.i18n.LocalDs.current.loadingLabel, color = tr.lidertv.nativeapp.ui.design.Ux.Text2, fontSize = 24.spx)
        }
    }
}
