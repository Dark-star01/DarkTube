package __APP_ID__

import __APP_ID__.core.download.AndroidDownloadNotifier
import __APP_ID__.core.download.DownloadManager
import __APP_ID__.core.download.DownloadService
import __APP_ID__.core.download.DownloadStorage
import __APP_ID__.core.download.YtDlpEngine
import __APP_ID__.core.extraction.VideoExtractor
import __APP_ID__.core.extraction.newpipe.NewPipeVideoExtractor
import __APP_ID__.core.extraction.newpipe.OkHttpDownloader
import __APP_ID__.core.log.AppLog
import __APP_ID__.core.playback.PipState
import __APP_ID__.core.playback.PlayerConnection
import __APP_ID__.data.db.DarkTubeDatabase
import android.app.Application
import android.content.Context
import android.util.Log

/** Manual DI: one small object holding the app-wide singletons. */
class AppContainer(context: Context) {
    val extractor: VideoExtractor = NewPipeVideoExtractor(OkHttpDownloader())
    val playerConnection = PlayerConnection(context.applicationContext)
    val pip = PipState()

    val database: DarkTubeDatabase by lazy { DarkTubeDatabase.create(context) }
    val downloadStorage = DownloadStorage(context.applicationContext)
    val downloads: DownloadManager by lazy {
        val app = context.applicationContext
        DownloadManager(
            dao = database.downloads(),
            engine = YtDlpEngine(app),
            storage = downloadStorage,
            serviceStarter = { DownloadService.start(app) },
            notifier = AndroidDownloadNotifier(app),
        )
    }
}

class DarkTubeApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        AppLog.platformSink = { level, tag, msg ->
            when (level) {
                AppLog.Level.DEBUG -> Log.d(tag, msg)
                AppLog.Level.INFO -> Log.i(tag, msg)
                AppLog.Level.WARN -> Log.w(tag, msg)
                AppLog.Level.ERROR -> Log.e(tag, msg)
            }
        }
        container = AppContainer(this)
        AppLog.i("App", "DarkTube started")
        // Re-queue anything a previous process left mid-download; the service is started if there is work.
        container.downloads.start()
    }
}
