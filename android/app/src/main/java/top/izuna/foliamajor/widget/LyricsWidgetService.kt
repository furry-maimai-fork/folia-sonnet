// android/app/src/main/java/top/izuna/foliamajor/widget/LyricsWidgetService.kt
// 小部件的刷新节拍：每 POLL_INTERVAL_MS 问一次 WallpaperLyricsState，变了就重画。
//
// 为什么要有服务：RemoteViews 没有「定时器」。AppWidgetProvider 的 updatePeriodMillis
// 最细 30 分钟（系统下限），拿它跟歌词是没法看的。所以只有两种做法 ——
// AlarmManager 反复唤醒（耗电更差），或者一个只做轮询的轻服务。这里选后者，
// 并且只在「桌面上至少有一块小部件」时运行，最后一块被摘掉就停。

package top.izuna.foliamajor.widget

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import top.izuna.foliamajor.wallpaper.WallpaperLyricsState

class LyricsWidgetService : Service() {

    companion object {
        private const val TAG = "LyricsWidget"

        /**
         * 轮询间隔。
         *
         * 与 FloatingLyricsOverlay 的 POLL_INTERVAL_MS 一致 —— 两层画的是同一首歌的同一条
         * 歌词，节拍不同会在视觉上互相打架（小部件的扫光比悬浮窗慢一拍，看着像卡了）。
         *
         * 动态壁纸开着时用 WALLPAPER_ACTIVE_INTERVAL_MS：壁纸那层已经在以帧率画着，
         * 小部件再 600ms 推一张图是纯粹的双倍开销。
         */
        private const val POLL_INTERVAL_MS = 600L
        private const val WALLPAPER_ACTIVE_INTERVAL_MS = 2000L

        fun ensureRunning(context: Context) {
            try {
                context.startService(Intent(context, LyricsWidgetService::class.java))
            } catch (error: Exception) {
                // Android 8+ 的后台启动限制：用户在桌面添加小部件时应用通常处于前台，
                // 不该触发。真触发了也不该崩，下一轮 onUpdate 会再试一次。
                Log.w(TAG, "[LyricsWidget] 启动轮询服务失败", error)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LyricsWidgetService::class.java))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var stopped = false

    /** 上一次画的内容指纹：没变就不重画，省一次 Bitmap 分配与跨进程传输。 */
    private var lastSignature: Long = 0L
    private var lastTickAt: Long = 0L

    private val tick = object : Runnable {
        override fun run() {
            if (stopped) return
            val now = SystemClock.elapsedRealtime()
            val signature = signatureNow()
            // 歌词行或播放状态变了要立刻重画；没变也要按节拍重画 ——
            // 同一句还在唱，扫光进度在推进，那也算「变了」。
            val interval = intervalNow()
            val due = now - lastTickAt >= interval
            if (signature != lastSignature || due) {
                lastSignature = signature
                lastTickAt = now
                LyricsWidgetProvider.refreshAll(this@LyricsWidgetService)
            }
            handler.postDelayed(this, interval)
        }
    }

    /** 当前该用哪个节拍：动态壁纸在画着就让一让。 */
    private fun intervalNow(): Long {
        val snapshot = WallpaperLyricsState.get()
        val wallpaperDrawing = snapshot.lines?.isNotEmpty() == true &&
            "minimal" != snapshot.visualizerMode &&
            snapshot.playing
        return if (wallpaperDrawing) WALLPAPER_ACTIVE_INTERVAL_MS else POLL_INTERVAL_MS
    }

    /**
     * 内容指纹：把「会改变画面」的几项拼成一个数。
     *
     * 用「当前行下标 + 扫光进度（按 1/64 量化）+ 播放状态」而不是整份快照：
     * 快照里还有封面、字体、主题等字段，它们变了也未必影响这一帧，
     * 全算进来会让每一次 heartbeat 都触发重画。
     */
    private fun signatureNow(): Long {
        val snapshot = WallpaperLyricsState.get()
        val lines = snapshot.lines
        if (lines.isNullOrEmpty()) return 1L
        val positionMs = snapshot.positionMs()
        val index = snapshot.indexAt(positionMs)
        val progress = (snapshot.progressAt(positionMs) * 64).toInt().coerceIn(0, 64)
        return (index.toLong() shl 16) or (progress.toLong() shl 8) or (if (snapshot.playing) 1L else 0L)
    }

    override fun onCreate() {
        super.onCreate()
        handler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopped = false
        // 不粘性：进程被系统收掉时不要自己复活 —— 小部件已经不在了的话，
        // 复活一个只轮询不干活的空服务没有意义。onEnabled 会在下次添加时重新拉起。
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopped = true
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
