// android/app/src/main/java/top/izuna/foliamajor/widget/LyricsWidgetRenderer.kt
// 桌面歌词小部件的绘制：把 WallpaperLyricsState 的一份快照画成一张 Bitmap。
//
// 为什么不复用 LyricsRenderer（壁纸那层）：它是 GLSurfaceView 的 Renderer，只往系统给的
// Surface 上画；小部件要的是一张静态 Bitmap，塞进 RemoteViews 的 ImageView。
// 观感上这两处必须像同一个人画的，所以常量（换行 420ms、呼吸 0.05、扫光用 clipRect 裁
// 已唱部分再叠高亮色）照壁纸/悬浮窗那两层的值来，不另立一套。

package top.izuna.foliamajor.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import top.izuna.foliamajor.wallpaper.WallpaperLyricsState
import kotlin.math.min

/**
 * 小部件的尺寸档位。
 *
 * 这些不是「像素」，而是「这个格子放得下几行字」。同一档位在不同 DPI、不同桌面网格上
 * 实际像素宽度差很多，所以布局按档位分，具体的字号与间距再按传入的宽高算。
 */
enum class LyricsWidgetSize {
    /** 1x1：一句歌词 + 一条进度。空间只够这些，多画一样都会挤。 */
    COMPACT,
    /** 2x2：上一行、当前行、下一行，加封面与进度。 */
    MEDIUM,
    /** 其他尺寸（1x2、4x1 等）先按 2x2 的骨架画，按实际宽高压一压。 */
    WIDE,
}

object LyricsWidgetRenderer {

    /** 换行走进来的时长，与壁纸 / 悬浮窗同一个数。 */
    private const val LINE_TRANSITION_MS = 420L

    /** 焦点行呼吸的幅度（±5%），三层共用。 */
    private const val LINE_BREATH = 0.05f

    /** 已唱部分之后那一点点渐隐的宽度（按字号比例），让扫光的边缘不是刀切。 */
    private const val SWEEP_SOFTEN_RATIO = 0.35f

    /**
     * 按实际像素尺寸选档位。
     *
     * 判据用「短边能放几行」而不是长宽比：1x2 与 2x1 在桌面旋转后是同一块地方，
     * 用长宽比会把它们分到不同档位，用户转个屏小部件就换了长相。
     *
     * `density` 必须给：44 是 **dp**，直接拿 px 去除会让 3x 屏上的格子凭空多算 3 倍行数 ——
     * 2x1（约 110dp 高 = 330px）会被判成能放 7 行，于是画成 WIDE 的空白大格子。
     */
    fun sizeFor(widthPx: Int, heightPx: Int, density: Float): LyricsWidgetSize {
        if (widthPx <= 0 || heightPx <= 0) return LyricsWidgetSize.COMPACT
        val safeDensity = if (density > 0f) density else 1f
        val heightDp = heightPx / safeDensity
        // 一行字连同行距大约要 44dp。
        val lineBudget = (heightDp / 44f).toInt()
        return when {
            lineBudget <= 1 -> LyricsWidgetSize.COMPACT
            lineBudget <= 3 -> LyricsWidgetSize.MEDIUM
            else -> LyricsWidgetSize.WIDE
        }
    }

    /**
     * 画一张小部件位图。
     *
     * @param snapshot 当前歌词快照；为 null 或没有歌词时画占位内容，而不是留白 ——
     *   桌面上一块透明/全黑的格子比「暂无播放」更让人以为坏掉了。
     * @param accentFallback 快照里没有取到强调色时用的颜色（一般传主题色）。
     */
    fun render(
        snapshot: WallpaperLyricsState.Snapshot?,
        widthPx: Int,
        heightPx: Int,
        density: Float,
        accentFallback: Int = DEFAULT_ACCENT,
    ): Bitmap {
        val safeWidth = widthPx.coerceAtLeast(1)
        val safeHeight = heightPx.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(safeWidth, safeHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val size = sizeFor(safeWidth, safeHeight, density)
        val accent = snapshot?.accent?.takeIf { Color.alpha(it) > 0 && it != Color.TRANSPARENT } ?: accentFallback
        // 小部件自己不带背景图：桌面壁纸已经在那儿了，再铺一层会把它盖住。
        // 只给一层很淡的圆形光晕，保证白字压在浅色壁纸上也读得清。
        drawBackdrop(canvas, safeWidth, safeHeight, accent, density)
        if (snapshot == null || snapshot.lines.isNullOrEmpty()) {
            drawPlaceholder(canvas, safeWidth, safeHeight, density)
            return bitmap
        }
        when (size) {
            LyricsWidgetSize.COMPACT -> drawCompact(canvas, snapshot, safeWidth, safeHeight, density, accent)
            LyricsWidgetSize.MEDIUM -> drawMedium(canvas, snapshot, safeWidth, safeHeight, density, accent)
            LyricsWidgetSize.WIDE -> drawWide(canvas, snapshot, safeWidth, safeHeight, density, accent)
        }
        return bitmap
    }

    // ---- 各档位的布局 ------------------------------------------------------

    private fun drawCompact(
        canvas: Canvas,
        snapshot: WallpaperLyricsState.Snapshot,
        width: Int,
        height: Int,
        density: Float,
        accent: Int,
    ) {
        val lines = snapshot.lines ?: return
        val positionMs = snapshot.positionMs()
        val index = snapshot.indexAt(positionMs)
        val progress = snapshot.progressAt(positionMs)
        val text = lines.getOrNull(index)?.text?.trim().orEmpty()
        // 1x1 里的字号按宽度定，再被高度压一次：宁可字小，也不要一句话被裁成两半。
        val fontSize = min(width * 0.105f, height * 0.20f)
        val paint = textPaint(fontSize, Color.WHITE)
        val textWidth = paint.measureText(text)
        val maxWidth = width - dp(12f, density) * 2
        val visible = if (textWidth <= maxWidth) text else ellipsize(paint, text, maxWidth)
        val baseline = height * 0.52f
        drawSweptLine(canvas, visible, width / 2f, baseline, paint, accent, progress, center = true)
        // 底部一条进度：1x1 放不下封面，这条细线是唯一能看出「唱到哪了」的东西。
        drawProgressBar(canvas, width, height, progress, accent, density)
    }

    private fun drawMedium(
        canvas: Canvas,
        snapshot: WallpaperLyricsState.Snapshot,
        width: Int,
        height: Int,
        density: Float,
        accent: Int,
    ) {
        val lines = snapshot.lines ?: return
        val positionMs = snapshot.positionMs()
        val index = snapshot.indexAt(positionMs)
        val progress = snapshot.progressAt(positionMs)
        val padding = dp(10f, density)
        // 三行字的字号：宽度是主要约束（中文一句通常十几个字），高度兜底。
        val fontSize = min((width - padding * 2) / 9f, height * 0.13f)
        val rowGap = fontSize * 1.55f
        val centerY = height * 0.50f
        val previous = lines.getOrNull(index - 1)?.text?.trim().orEmpty()
        val currentLine = lines.getOrNull(index)
        val current = currentLine?.text?.trim().orEmpty()
        val next = lines.getOrNull(index + 1)?.text?.trim().orEmpty()
        val maxWidth = width - padding * 2

        // 上一行：淡、缩小，给当前行让出视觉重量。
        if (previous.isNotEmpty()) {
            val paint = textPaint(fontSize * 0.82f, Color.WHITE).apply { alpha = 0x66 }
            drawCentered(canvas, ellipsize(paint, previous, maxWidth), width / 2f, centerY - rowGap, paint)
        }
        // 当前行：高亮扫光 + 极缓呼吸。呼吸幅度做成「跨帧可见」的程度，
        // 太小的话按 600ms 采样根本看不出来，反而白算。
        val breath = 1f + LINE_BREATH * kotlin.math.sin(
            (positionMs % 2400L).toDouble() / 2400.0 * 2 * Math.PI,
        ).toFloat()
        // 有翻译行时把当前行往上挪半行，给译文明出位置 —— 否则两行会叠在一起。
        val translation = currentLine?.translation?.trim().orEmpty()
        val hasTranslation = snapshot.showTranslation && translation.isNotEmpty()
        val currentBaseline = if (hasTranslation) centerY - fontSize * 0.34f else centerY
        val currentPaint = textPaint(fontSize * breath, Color.WHITE)
        drawSweptLine(
            canvas, ellipsize(currentPaint, current, maxWidth), width / 2f, currentBaseline,
            currentPaint, accent, progress, center = true,
        )
        if (hasTranslation) {
            val paint = textPaint(fontSize * 0.66f, Color.WHITE).apply { alpha = 0xAA }
            drawCentered(canvas, ellipsize(paint, translation, maxWidth), width / 2f, centerY + fontSize * 0.86f, paint)
        }
        // 下一行：同上，淡且缩小。
        if (next.isNotEmpty()) {
            val paint = textPaint(fontSize * 0.82f, Color.WHITE).apply { alpha = 0x55 }
            drawCentered(canvas, ellipsize(paint, next, maxWidth), width / 2f, centerY + rowGap, paint)
        }
        drawProgressBar(canvas, width, height, progress, accent, density)
    }

    /**
     * 4 行以上的格子（2x2、3x2、4x2 这些）。
     *
     * 与 MEDIUM 的区别是**前后各多给一行**：MEDIUM 只能放「上一行 + 当前 + 下一行」，
     * 大格子上前后各留两行才填得满；否则中间那块字挤在正中、上下大片空白，看着像没画完。
     * 越远的行越淡越小，形成往中心收的层次。
     *
     * 翻译行照旧挤在当前行下方 —— 它属于「这一句」，不该被算进上下文行里。
     */
    private fun drawWide(
        canvas: Canvas,
        snapshot: WallpaperLyricsState.Snapshot,
        width: Int,
        height: Int,
        density: Float,
        accent: Int,
    ) {
        val lines = snapshot.lines ?: return
        val positionMs = snapshot.positionMs()
        val index = snapshot.indexAt(positionMs)
        val progress = snapshot.progressAt(positionMs)
        val padding = dp(14f, density)
        val fontSize = min((width - padding * 2) / 11f, height * 0.115f)
        val rowGap = fontSize * 1.5f
        val centerY = height * 0.50f
        val maxWidth = width - padding * 2
        val currentLine = lines.getOrNull(index)
        val current = currentLine?.text?.trim().orEmpty()
        val translation = currentLine?.translation?.trim().orEmpty()
        val hasTranslation = snapshot.showTranslation && translation.isNotEmpty()

        // 上下各两行的上下文。越远越淡：0.34 / 0.18 两档，比 MEDIUM 的单档更有纵深。
        for (distance in 2 downTo 1) {
            val alpha = if (distance == 1) 0x50 else 0x2C
            val scale = if (distance == 1) 0.80f else 0.72f
            for (direction in intArrayOf(-1, 1)) {
                // 有翻译行时上下文往外让半行，避免和译文撞在一起。
                val offset = (rowGap * distance + if (hasTranslation) fontSize * 0.5f else 0f) * direction
                val text = lines.getOrNull(index + distance * direction)?.text?.trim().orEmpty()
                if (text.isEmpty()) continue
                val paint = textPaint(fontSize * scale, Color.WHITE).apply { this.alpha = alpha }
                drawCentered(canvas, ellipsize(paint, text, maxWidth), width / 2f, centerY + offset, paint)
            }
        }

        val breath = 1f + LINE_BREATH * kotlin.math.sin(
            (positionMs % 2400L).toDouble() / 2400.0 * 2 * Math.PI,
        ).toFloat()
        val currentBaseline = if (hasTranslation) centerY - fontSize * 0.34f else centerY
        val currentPaint = textPaint(fontSize * breath, Color.WHITE)
        drawSweptLine(
            canvas, ellipsize(currentPaint, current, maxWidth), width / 2f, currentBaseline,
            currentPaint, accent, progress, center = true,
        )
        if (hasTranslation) {
            val paint = textPaint(fontSize * 0.62f, Color.WHITE).apply { alpha = 0xAA }
            drawCentered(canvas, ellipsize(paint, translation, maxWidth), width / 2f, centerY + fontSize * 0.82f, paint)
        }
        drawProgressBar(canvas, width, height, progress, accent, density)
    }

    // ---- 公共绘制 ----------------------------------------------------------

    /**
     * 画一行「已唱部分高亮」的歌词。
     *
     * 做法与壁纸 / 悬浮窗一致：先用普通色画整行，再裁出已唱那一段叠高亮色。
     * 裁切边缘之外再补一条半透明的同色带，让扫光的推进边界不是一刀切 ——
     * 按 600ms 采样时，这一条比精确的边界更让人觉得「在动」。
     */
    private fun drawSweptLine(
        canvas: Canvas,
        text: String,
        centerX: Float,
        baseline: Float,
        paint: TextPaint,
        accent: Int,
        progress: Float,
        center: Boolean,
    ) {
        if (text.isEmpty()) return
        val width = paint.measureText(text)
        val left = if (center) centerX - width / 2f else centerX
        if (center) drawCentered(canvas, text, centerX, baseline, paint) else canvas.drawText(text, left, baseline, paint)

        val sung = width * (progress.coerceIn(0f, 1f))
        if (sung <= 0f) return
        val soften = paint.textSize * SWEEP_SOFTEN_RATIO
        val accentPaint = textPaint(paint.textSize, accent).apply { alpha = paint.alpha }
        val save = canvas.save()
        canvas.clipRect(left, baseline - paint.textSize * 1.6f, left + sung, baseline + paint.textSize * 0.6f)
        if (center) drawCentered(canvas, text, centerX, baseline, accentPaint)
        else canvas.drawText(text, left, baseline, accentPaint)
        canvas.restoreToCount(save)

        // 边缘那条渐隐带：只在进度还没有走到头时画，走完了再画会显得尾巴发脏。
        if (sung < width) {
            val softPaint = textPaint(paint.textSize, accent).apply {
                alpha = (paint.alpha * 0.35f).toInt()
            }
            val save2 = canvas.save()
            canvas.clipRect(left + sung, baseline - paint.textSize * 1.6f, left + sung + soften, baseline + paint.textSize * 0.6f)
            if (center) drawCentered(canvas, text, centerX, baseline, softPaint)
            else canvas.drawText(text, left, baseline, softPaint)
            canvas.restoreToCount(save2)
        }
    }

    private fun drawCentered(canvas: Canvas, text: String, centerX: Float, baseline: Float, paint: TextPaint) {
        if (text.isEmpty()) return
        val width = paint.measureText(text)
        canvas.drawText(text, centerX - width / 2f, baseline, paint)
    }

    private fun drawProgressBar(canvas: Canvas, width: Int, height: Int, progress: Float, accent: Int, density: Float) {
        val barHeight = dp(2.5f, density).coerceAtLeast(1f)
        val inset = dp(14f, density)
        val y = height - inset
        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; alpha = 0x2E }
        val filled = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; alpha = 0xCC }
        val radius = barHeight / 2f
        val left = inset
        val right = width - inset
        canvas.drawRoundRect(RectF(left, y, right, y + barHeight), radius, radius, track)
        val progressRight = left + (right - left) * progress.coerceIn(0f, 1f)
        if (progressRight > left) {
            canvas.drawRoundRect(RectF(left, y, progressRight, y + barHeight), radius, radius, filled)
        }
    }

    /**
     * 一层很淡的径向光晕。
     *
     * 小部件不能自带不透明背景（会盖住用户的壁纸），但没有底衬时白字压在浅色壁纸上
     * 几乎读不出来。用一小团从中心往外淡出的暗色就够：它只在字附近起作用，
     * 稍远一点就透明了，看不出「有一块矩形」。
     */
    private fun drawBackdrop(canvas: Canvas, width: Int, height: Int, accent: Int, density: Float) {
        val radius = maxOf(width, height).toFloat() * 0.72f
        val shader = android.graphics.RadialGradient(
            width / 2f, height / 2f, radius,
            intArrayOf(Color.argb(0x52, 0, 0, 0), Color.argb(0x28, 0, 0, 0), Color.TRANSPARENT),
            floatArrayOf(0f, 0.55f, 1f),
            android.graphics.Shader.TileMode.CLAMP,
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        // 左上角一小道强调色，让空白的小部件也有辨识度；不占版面。
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent
            alpha = 0x40
            maskFilter = android.graphics.BlurMaskFilter(dp(6f, density), android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawCircle(dp(6f, density), dp(6f, density), dp(10f, density), glow)
    }

    private fun drawPlaceholder(canvas: Canvas, width: Int, height: Int, density: Float) {
        val paint = textPaint(min(width * 0.09f, height * 0.18f), Color.WHITE).apply { alpha = 0x99 }
        drawCentered(canvas, "暂无播放", width / 2f, height * 0.58f, paint)
    }

    private fun ellipsize(paint: TextPaint, text: String, maxWidth: Float): String {
        if (maxWidth <= 0f || text.isEmpty()) return ""
        if (paint.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        var low = 0
        var high = text.length
        // 二分找「加上省略号仍放得下」的最长前缀。逐字减要重排很多次，
        // 小部件每 600ms 要画一帧，这个开销值得省。
        while (low < high) {
            val mid = (low + high + 1) / 2
            val candidate = text.substring(0, mid) + ellipsis
            if (paint.measureText(candidate) <= maxWidth) low = mid else high = mid - 1
        }
        return if (low <= 0) "" else text.substring(0, low) + ellipsis
    }

    private fun textPaint(sizePx: Float, color: Int): TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sizePx.coerceAtLeast(1f)
        this.color = color
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isSubpixelText = true
    }

    private fun dp(value: Float, density: Float): Float = value * density

    /** 快照没给强调色时的兜底：偏青的蓝，和默认主题同一个调子。 */
    private const val DEFAULT_ACCENT = 0xFF4FD1C5.toInt()
}
