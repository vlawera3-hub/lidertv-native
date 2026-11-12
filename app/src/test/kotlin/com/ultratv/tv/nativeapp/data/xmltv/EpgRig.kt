package tr.lidertv.nativeapp.data.xmltv

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import tr.lidertv.nativeapp.adaptive.AdaptiveProfile
import tr.lidertv.nativeapp.adaptive.NetworkMonitor
import tr.lidertv.nativeapp.data.db.CategoryEntity
import tr.lidertv.nativeapp.data.db.ChannelEntity
import tr.lidertv.nativeapp.data.db.ProviderDao
import tr.lidertv.nativeapp.data.db.ProviderEntity
import tr.lidertv.nativeapp.data.db.UltraDb
import tr.lidertv.nativeapp.data.m3u.M3uParser
import tr.lidertv.nativeapp.data.parental.ParentalStore
import tr.lidertv.nativeapp.data.prefs.ProviderLimitsStore
import tr.lidertv.nativeapp.data.prefs.UserPreferencesStore
import tr.lidertv.nativeapp.data.profile.InMemoryProfileStateStore
import tr.lidertv.nativeapp.data.profile.ProfileRepository
import tr.lidertv.nativeapp.data.repo.ProviderRepository
import tr.lidertv.nativeapp.data.repo.SyncStatusBus
import tr.lidertv.nativeapp.data.stalker.StalkerClient
import tr.lidertv.nativeapp.data.stalker.StalkerEndpointStore
import tr.lidertv.nativeapp.data.xtream.XtreamClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.File

/** Dépôt réel (Room sur fichier, WAL) branché sur un faux serveur : aucun réseau. Sert aux tests et au banc du guide. */
class EpgRig(
    val server: FakeFeedServer,
    dir: File,
    parserFactory: (OkHttpClient) -> XmltvParser = { XmltvParser(it) },
    kind: String = "XTREAM",
    channels: List<Pair<String, String?>> = emptyList(),
) {
    val ctx: Context get() = ApplicationProvider.getApplicationContext()
    val db: UltraDb
    val repo: ProviderRepository
    val bus = SyncStatusBus()
    val limitsStore: ProviderLimitsStore
    val prefs: UserPreferencesStore
    val providerId: Long

    init {
        dir.mkdirs()
        db = Room.databaseBuilder(ctx, UltraDb::class.java, File(dir, "db.sqlite").absolutePath)
            .addCallback(tr.lidertv.nativeapp.data.db.DefaultProfileCallback())
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
        val ok = OkHttpClient.Builder().addInterceptor(server.interceptor).build()
        val profiles = ProfileRepository(db.profileDao(), InMemoryProfileStateStore(), CoroutineScope(Dispatchers.Default))
        limitsStore = ProviderLimitsStore(ctx)
        prefs = UserPreferencesStore(ctx, profiles)
        repo = ProviderRepository(
            providerDao = ProviderDao(db.providerDao()), channelDao = db.channelDao(), movieDao = db.movieDao(), seriesDao = db.seriesDao(),
            episodeDao = db.episodeDao(), categoryDao = db.categoryDao(), xtream = XtreamClient(ok), m3u = M3uParser(ok), parental = ParentalStore(ctx),
            syncStatus = bus, xmltv = parserFactory(ok), epgDao = db.epgDao(), db = db, prefs = prefs,
            adaptive = AdaptiveProfile(ctx, NetworkMonitor(ctx)), limits = limitsStore,
            langDisabled = tr.lidertv.nativeapp.data.prefs.LangDisabledStore(ctx),
            stalker = StalkerClient(ok, StalkerEndpointStore.inMemory()),
        )
        providerId = runBlocking {
            val pid = db.providerDao().upsert(ProviderEntity(name = "fictif", kind = kind, baseUrl = "http://serveur-fictif.invalid", username = "test", password = "test", active = true))
            if (channels.isNotEmpty()) db.channelDao().upsertAll(channels.mapIndexed { i, (rid, epg) ->
                ChannelEntity(providerId = pid, remoteId = "r$i", name = "Chaine $i", logo = null, categoryId = null, streamUrl = "http://serveur-fictif.invalid/live/$i.ts", epgChannelId = epg)
            })
            pid
        }
    }

    fun close() { server.closeAll(); runCatching { db.close() } }

    fun count(table: String): Int = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `$table`").use { it.moveToFirst(); it.getInt(0) }

    suspend fun addCategory(remoteId: String, enabled: Boolean) {
        db.categoryDao().upsertAll(listOf(CategoryEntity(providerId = providerId, kind = "LIVE", remoteId = remoteId, name = "Cat $remoteId", enabled = enabled)))
    }
}
