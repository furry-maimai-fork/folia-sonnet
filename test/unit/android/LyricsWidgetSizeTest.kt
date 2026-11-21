package top.izuna.foliamajor

import org.junit.Assert.assertEquals
import org.junit.Test
import top.izuna.foliamajor.widget.LyricsWidgetRenderer
import top.izuna.foliamajor.widget.LyricsWidgetSize

// test/unit/android/LyricsWidgetSizeTest.kt
// 桌面歌词小部件的档位判定（issue #562）。
//
// 只用纯函数 sizeFor 来钉，不碰 Canvas —— 它没有 Android 依赖，JVM 上直接跑。
// 钉两件事：三种档位的边界对不对；以及 44 这个数是 **dp**，不能被当成 px 直接除
// （那个错会让 3x 屏上的格子凭空多算三倍行数，2x1 被画成 WIDE 的空白大格）。

class LyricsWidgetSizeTest {

    @Test
    fun `一行高的格子是 COMPACT`() {
        // 2x1 在 3x 屏上约 110dp 高。1 行格子的典型高度是 44dp 上下。
        assertEquals(LyricsWidgetSize.COMPACT, LyricsWidgetRenderer.sizeFor(600, 132, 3f))
        // 44dp * 3 = 132px 正好落到 lineBudget = 1。
        assertEquals(LyricsWidgetSize.COMPACT, LyricsWidgetRenderer.sizeFor(300, 132, 3f))
    }

    @Test
    fun `两三行高的格子是 MEDIUM`() {
        // 110dp 高、3x 屏 = 330px：lineBudget = 2 → MEDIUM。
        assertEquals(LyricsWidgetSize.MEDIUM, LyricsWidgetRenderer.sizeFor(660, 330, 3f))
        // 3 行：132 * 3 = 396px。
        assertEquals(LyricsWidgetSize.MEDIUM, LyricsWidgetRenderer.sizeFor(660, 396, 3f))
    }

    @Test
    fun `四行以上是 WIDE`() {
        // 4 行 = 44dp * 4 = 176dp = 528px @3x。
        assertEquals(LyricsWidgetSize.WIDE, LyricsWidgetRenderer.sizeFor(660, 528, 3f))
        assertEquals(LyricsWidgetSize.WIDE, LyricsWidgetRenderer.sizeFor(990, 800, 3f))
    }

    @Test
    fun `44 按 dp 算，不按 px 算`() {
        // 同一个 330px 高，密度不同就是不同的格子：3x 屏上它是 110dp（2 行 → MEDIUM），
        // 1x 屏上它是 330dp（7 行 → WIDE）。如果把 44 当 px 直接除，两种都会算成 7 行，
        // 3x 屏上那块就画成了填不满的 WIDE。
        assertEquals(LyricsWidgetSize.MEDIUM, LyricsWidgetRenderer.sizeFor(660, 330, 3f))
        assertEquals(LyricsWidgetSize.WIDE, LyricsWidgetRenderer.sizeFor(660, 330, 1f))
    }

    @Test
    fun `尺寸不合法时退回 COMPACT，密度不合法时按 1 算`() {
        assertEquals(LyricsWidgetSize.COMPACT, LyricsWidgetRenderer.sizeFor(0, 300, 3f))
        assertEquals(LyricsWidgetSize.COMPACT, LyricsWidgetRenderer.sizeFor(300, 0, 3f))
        assertEquals(LyricsWidgetSize.COMPACT, LyricsWidgetRenderer.sizeFor(-1, -1, 3f))
        // density = 0 会让除零变成 Infinity，必须退回 1f 而不是崩。
        assertEquals(LyricsWidgetSize.WIDE, LyricsWidgetRenderer.sizeFor(660, 528, 0f))
    }
}
