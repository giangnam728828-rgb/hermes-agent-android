package ai.hermes.agent

import android.content.Context
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

/**
 * 2.16.2：全 App 统一动效。用户明确要求「不是只给三个界面加动效，而是整个
 * APP 都要加动效，并保证能够流畅使用」。
 *
 * 设计约束（流畅性优先）：
 * 1. 全部用 ViewPropertyAnimator + 硬件层，时长 ≤ 260ms，不占用主线程做逐帧计算；
 * 2. 尊重系统「减少动态效果 / 动画时长缩放」：缩放为 0 时直接置终态，零动画；
 * 3. 只动 alpha / translation / scale 这类可由 RenderThread 合成的属性，
 *    不触发重新布局（不动 LayoutParams），避免掉帧；
 * 4. 所有动画 fire-and-forget，不持有 Activity 引用，不阻塞交互；
 * 5. 一套参数（时长 / 插值 / 位移）全 App 复用，保证「动效看起来一致」。
 */
object EtaMotion {

    private fun scale(context: Context): Float = runCatching {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
        )
    }.getOrDefault(1f)

    /** 系统关闭动画时返回 false，动画直接跳过（无障碍 / 省电场景）。 */
    fun enabled(context: Context): Boolean = scale(context) > 0.01f

    private fun dur(context: Context, base: Long): Long =
        if (enabled(context)) (base * scale(context)).toLong().coerceAtLeast(1) else 0L

    /** 页面 / 内容块入场：淡入 + 轻微上移。所有页面切页、Tab 切换共用。 */
    fun enter(context: Context, view: View, fromDp: Float = 16f, delay: Long = 0) {
        val d = dur(context, 230)
        if (d == 0L) {
            view.alpha = 1f
            view.translationY = 0f
            return
        }
        val shift = fromDp * view.resources.displayMetrics.density
        view.alpha = 0f
        view.translationY = shift
        view.animate().alpha(1f).translationY(0f)
            .setDuration(d).setStartDelay(delay)
            .setInterpolator(DecelerateInterpolator(1.5f))
            .withLayer().start()
    }

    /** 列表项错峰入场。只在数据重建时调用，避免滚动时反复触发。 */
    fun stagger(context: Context, container: ViewGroup, stepMs: Long = 26, cap: Int = 10) {
        if (!enabled(context)) return
        for (i in 0 until minOf(container.childCount, cap)) {
            enter(context, container.getChildAt(i), fromDp = 12f, delay = i * stepMs)
        }
    }

    /** 遮罩淡入。 */
    fun fadeIn(context: Context, view: View, delay: Long = 0) {
        val d = dur(context, 200)
        if (d == 0L) {
            view.alpha = 1f
            return
        }
        view.alpha = 0f
        view.animate().alpha(1f).setDuration(d).setStartDelay(delay).withLayer().start()
    }

    fun fadeOut(context: Context, view: View, onEnd: () -> Unit = {}) {
        val d = dur(context, 170)
        if (d == 0L) {
            view.alpha = 0f
            onEnd()
            return
        }
        view.animate().alpha(0f).setDuration(d).withLayer()
            .setListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) = onEnd()
            }).start()
    }

    /** BottomSheet 面板从底部滑入。 */
    fun sheetIn(context: Context, panel: View, onEnd: () -> Unit = {}) {
        val d = dur(context, 260)
        val h = panel.height.toFloat().coerceAtLeast(panel.resources.displayMetrics.density * 400)
        if (d == 0L) {
            panel.translationY = 0f
            onEnd()
            return
        }
        panel.translationY = h
        panel.animate().translationY(0f).setDuration(d)
            .setInterpolator(DecelerateInterpolator(1.6f))
            .withLayer()
            .setListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) = onEnd()
            }).start()
    }

    /** BottomSheet 面板滑出。 */
    fun sheetOut(context: Context, panel: View, onEnd: () -> Unit) {
        val d = dur(context, 200)
        val h = panel.height.toFloat().coerceAtLeast(panel.resources.displayMetrics.density * 400)
        if (d == 0L) {
            onEnd()
            return
        }
        panel.animate().translationY(h).setDuration(d)
            .setInterpolator(DecelerateInterpolator(1.2f))
            .withLayer()
            .setListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) = onEnd()
            }).start()
    }

    /** 通用按压反馈：按下轻微缩小，松开回弹。不消费触摸，点击照常触发。 */
    fun press(context: Context, view: View) {
        if (!enabled(context)) return
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(0.965f).scaleY(0.965f)
                        .setDuration(dur(context, 90)).setInterpolator(DecelerateInterpolator())
                        .start()
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f)
                        .setDuration(dur(context, 150))
                        .setInterpolator(OvershootInterpolator(1.1f)).start()
            }
            false
        }
    }

    /** 小标记（如单选圈）被选中时的弹一下，给用户即时反馈。 */
    fun pop(context: Context, view: View) {
        val d = dur(context, 200)
        if (d == 0L) return
        view.animate().scaleX(1f).scaleY(1f).setDuration(d)
            .setInterpolator(OvershootInterpolator(1.8f)).withLayer().start()
    }
}
