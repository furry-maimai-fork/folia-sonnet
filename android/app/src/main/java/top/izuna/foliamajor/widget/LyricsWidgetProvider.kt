// android/app/src/main/java/top/izuna/foliamajor/widget/LyricsWidgetProvider.kt
// 桌面歌词小部件的入口：接收系统的更新请求，把 WallpaperLyricsState 的一份快照画成
// Bitmap，用 RemoteViews 推给每一块已放到桌面的小部件。
//
// 刷新节拍走 LyricsWidgetService（前台服务），这里只负责「现在给我重画一遍」。
// 这样系统那套 updatePeriodMillis（最细 30 分钟）就不必参与：歌词要跟着唱。

package top.izuna.foliamajor.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.RemoteViews
import top.izuna.foliamajor.R
import top.izuna.foliamajor.wallpaper.WallpaperLyricsState

class LyricsWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val TAG = "LyricsWidget"

        /** 重画所有已放置的小部件。服务每次轮询、以及系统更新时都调它。 */
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, LyricsWidgetProvider::class.java))
            if (ids.isEmpty()) return
            for (id in ids) {
                try {
                    manager.updateAppWidget(id, buildViews(context, manager, id))
                } catch (error: Exception) {
                    // 桌面有可能在这一刻把小部件摘掉了（id 失效），抛的是 IllegalArgumentException。
                    // 一块画不出来不该让其余几块一起不更新，所以逐块 try。
                    Log.w(TAG, "[LyricsWidget] 更新小部件 $id 失败", error)
                }
            }
        }

        /** 按这块小部件实际占的像素尺寸画出对应的那一种布局。 */
        private fun buildViews(context: Context, manager: AppWidgetManager, id: Int): RemoteViews {
            val options = manager.getAppWidgetOptions(id)
            val size = resolveSize(context, options)
            val snapshot = WallpaperLyricsState.get()
            val bitmap = LyricsWidgetRenderer.render(
                snapshot = snapshot,
                widthPx = size.first,
                heightPx = size.second,
                density = context.resources.displayMetrics.density,
            )
            val views = RemoteViews(context.packageName, R.layout.lyrics_widget)
            views.setImageViewBitmap(R.id.lyrics_widget_image, bitmap)
            // 点一下进应用：RemoteViews 里只有 PendingIntent 能接点击。
            val launch = Intent(context, mainActivityClass(context)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            views.setOnClickPendingIntent(
                R.id.lyrics_widget_root,
                PendingIntent.getActivity(
                    context, 0, launch,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            return views
        }

        /**
         * 这块小部件占多少像素。
         *
         * 优先用系统给的 `OPTION_APPWIDGET_MIN/MAX_WIDTH`（真机上是当前格子的实际范围，
         * 横竖两个方向各有一对，因为桌面可以旋转）。拿不到时退回 info 里声明的 minWidth ——
         * 少了这一步，某些启动器的首帧会传空 Bundle，画出来是 1x1 的一小块。
         */
        private fun resolveSize(context: Context, options: Bundle?): Pair<Int, Int> {
            val minWidthDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: 0
            val maxWidthDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH) ?: 0
            val minHeightDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) ?: 0
            val maxHeightDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) ?: 0
            val widthDp = maxOf(minWidthDp, maxWidthDp).takeIf { it > 0 } ?: FALLBACK_WIDTH_DP
            val heightDp = maxOf(minHeightDp, maxHeightDp).takeIf { it > 0 } ?: FALLBACK_HEIGHT_DP
            val density = context.resources.displayMetrics.density
            return Pair((widthDp * density).toInt(), (heightDp * density).toInt())
        }

        private fun mainActivityClass(context: Context): Class<*> {
            // 不直接写 MainActivity 的类名：这个 fork 的包名可能被改（见 build.gradle 的
            // applicationId 与 namespace），用包管理器反查启动 Activity 更稳。
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val component = intent?.component
            if (component != null) {
                return try {
                    Class.forName(component.className)
                } catch (error: ClassNotFoundException) {
                    Log.w(TAG, "[LyricsWidget] 启动 Activity 找不到，退回 MainActivity", error)
                    MainActivityFallback
                }
            }
            return MainActivityFallback
        }

        /** 兜底：绝大多数情况下走不到这里。 */
        private val MainActivityFallback: Class<*> = top.izuna.foliamajor.MainActivity::class.java

        /** 拿不到格子尺寸时的兜底（大约 2x2）。 */
        private const val FALLBACK_WIDTH_DP = 140
        private const val FALLBACK_HEIGHT_DP = 140
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        super.onUpdate(context, manager, ids)
        LyricsWidgetService.ensureRunning(context)
        refreshAll(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        id: Int,
        newOptions: Bundle,
    ) {
        super.onAppWidgetOptionsChanged(context, manager, id, newOptions)
        // 用户改了格子大小：按新尺寸立刻重画一次，不必等下一拍。
        try {
            manager.updateAppWidget(id, buildViews(context, manager, id))
        } catch (error: Exception) {
            Log.w(TAG, "[LyricsWidget] 尺寸变化后重画失败", error)
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        // 第一块小部件被放到桌面：开始轮询。
        LyricsWidgetService.ensureRunning(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        // 最后一块被摘掉：轮询没有意义了，收掉服务，不留在后台耗电。
        LyricsWidgetService.stop(context)
    }
}
