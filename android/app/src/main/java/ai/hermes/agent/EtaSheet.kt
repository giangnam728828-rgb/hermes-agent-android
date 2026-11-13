package ai.hermes.agent

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/**
 * 2.16.2：全 App 统一的 Eta 风格底部弹层（BottomSheet）。
 *
 * 用户反馈：系统 AlertDialog 是「突然冒出一整块、挡在那里，UI 和动效跟其它
 * 界面完全不一样」。这里改成与 Eta 截图一致的：半透明遮罩 + 底部滑入的白色
 * 圆角大面板 + 顶部拖拽小横条 + 居中标题 + 可滚动内容 + 固定底部按钮区。
 *
 * 动效全部走 [EtaMotion]，与页面切页 / 列表 / 按钮反馈同一套参数，因此
 * 「弹出来的动效跟其它界面不一样」的问题一并根治；系统窗口动画被显式关闭，
 * 避免原生缩放淡入混进来。减少动态效果时自动零动画。
 */
class EtaSheet private constructor(
    private val activity: Activity,
    private val dialog: Dialog,
    private val panel: LinearLayout,
    private val dim: View,
    val body: LinearLayout,
    val footer: LinearLayout,
) {
    private var closing = false
    private var onDismiss: () -> Unit = {}

    fun onDismiss(block: () -> Unit): EtaSheet {
        onDismiss = block
        return this
    }

    fun dismiss() {
        if (closing) return
        closing = true
        EtaMotion.fadeOut(activity, dim)
        EtaMotion.sheetOut(activity, panel) {
            runCatching { dialog.dismiss() }
            onDismiss()
        }
    }

    fun playEnter() {
        dim.post {
            EtaMotion.fadeIn(activity, dim)
            EtaMotion.sheetIn(activity, panel)
        }
    }

    companion object {

        /** 顶部两角圆角的白色面板。 */
        private fun topRounded(radiusDp: Int): GradientDrawable = GradientDrawable().apply {
            setColor(Color.parseColor(EtaUi.CARD))
            val r = radiusDp * android.content.res.Resources.getSystem().displayMetrics.density
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }

        fun dp(context: Activity, v: Int): Int =
            (v * context.resources.displayMetrics.density).toInt()

        /**
         * @param tall true = 面板占屏幕约 88%（三个模型编辑界面，符合 Eta 截图的大留白观感）；
         *             false = 内容自适应（简单确认弹窗）。
         */
        fun show(
            activity: Activity,
            title: String,
            tall: Boolean = true,
            onBuilt: (EtaSheet) -> Unit,
        ): EtaSheet {
            val dm = activity.resources.displayMetrics
            val screenH = dm.heightPixels

            val root = FrameLayout(activity)

            val dim = View(activity).apply {
                setBackgroundColor(Color.parseColor("#66000000"))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
            root.addView(dim)

            val panel = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = topRounded(26)
                val lp = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    if (tall) (screenH * 0.88f).toInt() else ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                lp.gravity = Gravity.BOTTOM
                layoutParams = lp
                // 高度上限保护：tall=false 且内容超高时也不越界
                if (!tall) clipChildren = false
            }

            // 顶部拖拽小横条
            panel.addView(View(activity).apply {
                background = EtaUi.rounded("#D7DBE2", 3)
                val w = dp(activity, 40)
                layoutParams = LinearLayout.LayoutParams(w, dp(activity, 4)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = dp(activity, 12)
                    bottomMargin = dp(activity, 4)
                }
            })

            // 居中标题
            panel.addView(TextView(activity).apply {
                text = title
                textSize = 20f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor(EtaUi.TEXT))
                gravity = Gravity.CENTER
                setPadding(dp(activity, 20), dp(activity, 10), dp(activity, 20), dp(activity, 14))
            })

            // 可滚动内容
            val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            val scroller = ScrollView(activity).apply {
                isVerticalScrollBarEnabled = false
                setBackgroundColor(Color.parseColor(EtaUi.CARD))
                addView(body, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ))
            }
            body.setPadding(dp(activity, 20), dp(activity, 4), dp(activity, 20), dp(activity, 8))
            panel.addView(scroller, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            ))

            // 固定底部按钮区
            val footer = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor(EtaUi.CARD))
                setPadding(dp(activity, 20), dp(activity, 8), dp(activity, 20), dp(activity, 24))
            }
            panel.addView(footer, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ))

            root.addView(panel)

            val dialog = Dialog(activity).apply {
                requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
                setContentView(root)
                setCancelable(false)
                // 关闭系统窗口动画与系统 dim，动效与遮罩全部自绘，保证一致。
                window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                window?.setWindowAnimations(0)
                window?.setDimAmount(0f)
                window?.setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                )
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                }
            }
            // 返回键 / 点遮罩都走统一退出动画
            dialog.setOnKeyListener { _, keyCode, _ ->
                if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                    (root.getTag() as? EtaSheet)?.dismiss()
                    true
                } else false
            }

            val sheet = EtaSheet(activity, dialog, panel, dim, body, footer)
            root.setTag(sheet)
            dim.setOnClickListener { sheet.dismiss() }
            // panel 消费点击，避免点到内容时穿透到遮罩关闭
            panel.isClickable = true

            onBuilt(sheet)
            dialog.show()
            sheet.playEnter()
            return sheet
        }

        // ---------------------------------------------------------- 视觉组件

        /** 灰底圆角输入框，标签在上、值在下。readOnly 时不可编辑（Model ID）。 */
        fun field(
            activity: Activity,
            label: String,
            value: String = "",
            hint: String = "",
            numeric: Boolean = false,
            readOnly: Boolean = false,
        ): Pair<View, EditText> {
            val box = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = EtaUi.rounded(EtaUi.FIELD, 16)
                setPadding(dp(activity, 18), dp(activity, 14), dp(activity, 18), dp(activity, 14))
            }
            box.addView(TextView(activity).apply {
                text = label
                textSize = 12f
                setTextColor(Color.parseColor(EtaUi.MUTED))
            })
            val input = EditText(activity).apply {
                setText(value)
                this.hint = hint
                textSize = 19f
                setTextColor(Color.parseColor(EtaUi.TEXT))
                setHintTextColor(Color.parseColor(EtaUi.MUTED))
                background = null
                setSingleLine(true)
                setPadding(0, dp(activity, 4), 0, 0)
                if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
                if (readOnly) {
                    isFocusable = false
                    isClickable = false
                    // 值的文字保持深色（ETA 截图里 Model ID 是深色只读值）；
                    // 只有 hint 才是浅灰。
                }
            }
            box.addView(input)
            return box to input
        }

        /** 一行可单选的胶囊快捷项：128K/200K/256K/1M 或 Off~Max 档位。选中蓝底蓝字。 */
        fun chipBar(
            activity: Activity,
            labels: List<String>,
            initial: Int = -1,
            perRow: Int = 4,
            onPick: (Int, String) -> Unit,
        ): ChipBar = ChipBar(activity, labels, initial, perRow, onPick)

        class ChipBar(
            private val activity: Activity,
            private val labels: List<String>,
            initial: Int,
            perRow: Int,
            private val onPick: (Int, String) -> Unit,
        ) {
            val view = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            private val chips = mutableListOf<TextView>()
            var selected: Int = initial
                private set

            init {
                var row: LinearLayout? = null
                labels.forEachIndexed { i, label ->
                    if (i % perRow == 0) {
                        row = LinearLayout(activity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            setPadding(0, dp(activity, 6), 0, dp(activity, 6))
                        }
                        view.addView(row)
                    }
                    val chip = TextView(activity).apply {
                        text = label
                        textSize = 15f
                        gravity = Gravity.CENTER
                        setTextColor(Color.parseColor(EtaUi.TEXT))
                        background = outlined()
                        setPadding(dp(activity, 8), dp(activity, 10), dp(activity, 8), dp(activity, 10))
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                            .apply { setMargins(dp(activity, 4), 0, dp(activity, 4), 0) }
                        setOnClickListener { select(i); onPick(i, label); EtaMotion.pop(activity, this) }
                    }
                    chips.add(chip)
                    row!!.addView(chip)
                }
                paint()
            }

            fun select(i: Int) {
                selected = i
                paint()
            }

            fun clear() {
                selected = -1
                paint()
            }

            private fun paint() {
                chips.forEachIndexed { i, c ->
                    val on = i == selected
                    c.background = if (on) EtaUi.rounded(EtaUi.ACCENT_SOFT, 20)
                    else outlined()
                    c.setTextColor(Color.parseColor(if (on) EtaUi.ACCENT else EtaUi.TEXT))
                }
            }

            private fun outlined(): GradientDrawable = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = 20 * activity.resources.displayMetrics.density
                setStroke(maxOf(1, dp(activity, 1)), Color.parseColor("#33333F"))
            }
        }

        /** 「支援思考」这类：主标题 + 副标题 + 右侧开关。 */
        fun switchRow(
            activity: Activity,
            title: String,
            sub: String,
            checked: Boolean,
        ): Pair<View, Switch> {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(activity, 18), 0, dp(activity, 6))
            }
            val texts = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            texts.addView(TextView(activity).apply {
                text = title
                textSize = 20f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor(EtaUi.TEXT))
            })
            texts.addView(TextView(activity).apply {
                text = sub
                textSize = 13f
                setTextColor(Color.parseColor(EtaUi.SUB))
                setPadding(0, dp(activity, 3), 0, 0)
            })
            row.addView(texts)
            val sw = Switch(activity).apply { isChecked = checked }
            row.addView(sw)
            return row to sw
        }

        /** 灰色说明小字。 */
        fun hint(activity: Activity, text: String): TextView = TextView(activity).apply {
            this.text = text
            textSize = 12f
            setTextColor(Color.parseColor(EtaUi.MUTED))
            setPadding(0, dp(activity, 6), 0, dp(activity, 6))
        }

        /** 文字链接（恢复自动 = 蓝色，删除模型 = 红色）。 */
        fun link(activity: Activity, text: String, danger: Boolean, onClick: () -> Unit): TextView =
            TextView(activity).apply {
                this.text = text
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor(if (danger) "#DC2626" else EtaUi.ACCENT))
                setPadding(dp(activity, 4), dp(activity, 12), dp(activity, 20), dp(activity, 12))
                setOnClickListener { onClick() }
            }

        /**
         * 圆角按钮。filled=true 蓝色实心（储存），false 浅灰（取消）；
         * enabled=false 时用浅蓝置灰且不可点（对应 Eta「未勾选/未填」态）。
         */
        fun button(
            activity: Activity,
            text: String,
            filled: Boolean,
            enabled: Boolean = true,
            // 允许先建按钮、稍后再挂点击（例如「新增模型」的确认按钮要等
            // 用户勾选后才能用，点击逻辑也在那之后才绑定）。
            onClick: () -> Unit = {},
        ): TextView = TextView(activity).apply {
            this.text = text
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(if (filled) "#FFFFFF" else EtaUi.TEXT))
            background = EtaUi.rounded(
                when {
                    !enabled -> "#C7D6F5"
                    filled -> EtaUi.ACCENT
                    else -> EtaUi.FIELD
                }, 26,
            )
            setPadding(dp(activity, 14), dp(activity, 15), dp(activity, 14), dp(activity, 15))
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.9f
            // 读运行时的 isEnabled（setButtonEnabled 会动态改），不捕获初值。
            setOnClickListener { if (isEnabled) onClick() }
            EtaMotion.press(activity, this)
        }

        /** 两个并排按钮（取消 / 储存）。返回储存按钮，便于后续动态改启用态。 */
        fun dualButtons(
            activity: Activity,
            cancelText: String,
            confirmText: String,
            confirmEnabled: Boolean = true,
            onCancel: () -> Unit,
            onConfirm: () -> Unit,
        ): Pair<View, TextView> {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(activity, 6), 0, 0)
            }
            val cancel = button(activity, cancelText, filled = false) { onCancel() }
            val confirm = button(activity, confirmText, filled = true, enabled = confirmEnabled) { onConfirm() }
            row.addView(cancel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(0, 0, dp(activity, 6), 0) })
            row.addView(confirm, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(dp(activity, 6), 0, 0, 0) })
            return row to confirm
        }

        /** 运行时切换「储存」按钮启用/置灰。 */
        fun setButtonEnabled(activity: Activity, btn: TextView, on: Boolean, text: String? = null) {
            text?.let { btn.text = it }
            btn.isEnabled = on
            btn.background = EtaUi.rounded(if (on) EtaUi.ACCENT else "#C7D6F5", 26)
            btn.alpha = if (on) 1f else 0.9f
        }

        /** 简单确认弹窗（用于 MainActivity 的备份 / 恢复 / 提示）。 */
        fun confirm(
            activity: Activity,
            title: String,
            message: String,
            confirmText: String,
            onConfirm: () -> Unit,
        ): EtaSheet = show(activity, title, tall = false) { sheet ->
            sheet.body.addView(TextView(activity).apply {
                text = message
                textSize = 15f
                setTextColor(Color.parseColor(EtaUi.SUB))
                setPadding(0, dp(activity, 6), 0, dp(activity, 18))
            })
            val (row, _) = dualButtons(
                activity, activity.getString(R.string.action_cancel), confirmText,
                confirmEnabled = true,
                onCancel = { sheet.dismiss() },
                onConfirm = { sheet.dismiss(); onConfirm() },
            )
            sheet.footer.addView(row)
        }
    }
}
