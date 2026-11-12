package tr.lidertv.nativeapp.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        ProviderEntity::class,
        ChannelEntity::class,
        MovieEntity::class,
        SeriesEntity::class,
        EpisodeEntity::class,
        CategoryEntity::class,
        FavoriteEntity::class,
        EpgEntity::class,
        WatchHistoryEntity::class,
        RecordingEntity::class,
        tr.lidertv.nativeapp.data.reminders.ReminderEntity::class,
        ChannelFts::class,
        MovieFts::class,
        SeriesFts::class,
        VodInfoEntity::class,
        tr.lidertv.nativeapp.data.tmdb.TmdbInfoEntity::class,
        tr.lidertv.nativeapp.data.profile.ProfileEntity::class,
        tr.lidertv.nativeapp.data.profile.ProfilePrefEntity::class,
        tr.lidertv.nativeapp.data.profile.ProfileHiddenCategoryEntity::class,
        tr.lidertv.nativeapp.data.downloads.DownloadEntity::class,
        TitleGenreEntity::class,
        ChannelOrderEntity::class,
    ],
    version = 22,
    exportSchema = true,
)
abstract class UltraDb : RoomDatabase() {
    abstract fun providerDao(): ProviderRawDao
    abstract fun channelDao(): ChannelDao
    abstract fun movieDao(): MovieDao
    abstract fun seriesDao(): SeriesDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun categoryDao(): CategoryDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun epgDao(): EpgDao
    abstract fun watchHistoryDao(): WatchHistoryDao
    abstract fun recordingDao(): RecordingDao
    abstract fun vodInfoDao(): VodInfoDao
    abstract fun tmdbDao(): tr.lidertv.nativeapp.data.tmdb.TmdbDao
    abstract fun profileDao(): tr.lidertv.nativeapp.data.profile.ProfileDao
    abstract fun reminderDao(): tr.lidertv.nativeapp.data.reminders.ReminderDao
    abstract fun downloadDao(): tr.lidertv.nativeapp.data.downloads.DownloadDao
    abstract fun genreDao(): GenreDao
    abstract fun channelOrderDao(): ChannelOrderDao
}
