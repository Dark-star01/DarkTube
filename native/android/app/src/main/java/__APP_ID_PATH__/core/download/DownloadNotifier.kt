package __APP_ID__.core.download

import __APP_ID__.data.db.DownloadEntity

/** Notifications for downloads. Technical text never appears here. */
interface DownloadNotifier {
    fun progress(count: Int, title: String?, progress: Float)
    fun completed(e: DownloadEntity)
    fun failed(e: DownloadEntity)
}
