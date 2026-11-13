package ai.hermes.agent

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Eta 风格的轻量 View 组件工厂。
 *
 * 项目没有引入 Material / AppCompat / Compose 依赖，全部用平台 View 代码构建，
 * 避免改动 74KB 的主布局 XML，也保证 release 构建链不变。配色取浅灰底 +
 * 白色圆角卡片 + 蓝色强调，与用户提供的 Eta 截图一致。
 */
object EtaUi {

    const val BG = "#0A0A0F"
    const val CARD = "#14141C"
    const val FIELD = "#1E1E28"
    const val TEXT = "#F2F2F5"
    const val SUB = "#A8A8B4"
    const val MUTED = "#6C6C78"
    const val LINE = "#2A2A34"
    const val ACCENT = "#E8B44A"
    const val ACCENT_SOFT = "#2A2318"
    const val CHIP = "#1E1E28"

    fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private val density: Float
        get() = android.content.res.Resources.getSystem().displayMetrics.density

    fun rounded(color: String, radiusDp: Int, stroke: String? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(Color.parseColor(color))
            cornerRadius = radiusDp * density
            stroke?.let { setStroke(maxOf(1, (density).toInt()), Color.parseColor(it)) }
        }

    fun card(context: Context, radius: Int = 16): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(CARD, radius)
        val p = context.dp(4)
        setPadding(p, p, p, p)
    }

    fun sectionLabel(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.parseColor(SUB))
        setPadding(context.dp(4), context.dp(18), context.dp(4), context.dp(8))
    }

    fun bigTitle(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 30f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.parseColor(TEXT))
        setPadding(context.dp(4), context.dp(6), context.dp(4), context.dp(14))
    }

    fun searchBox(context: Context, hint: String, onText: (String) -> Unit): EditText =
        EditText(context).apply {
            this.hint = hint
            textSize = 15f
            setTextColor(Color.parseColor(TEXT))
            setHintTextColor(Color.parseColor(MUTED))
            setSingleLine(true)
            background = rounded(FIELD, 14)
            setPadding(context.dp(16), context.dp(13), context.dp(16), context.dp(13))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) = onText(s?.toString().orEmpty())
            })
        }

    fun fieldBox(
        context: Context,
        label: String,
        value: String,
        hint: String = "",
        password: Boolean = false,
    ): Pair<LinearLayout, EditText> {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(FIELD, 14)
            setPadding(context.dp(16), context.dp(12), context.dp(16), context.dp(12))
        }
        box.addView(TextView(context).apply {
            text = label
            textSize = 12f
            setTextColor(Color.parseColor(MUTED))
        })
        val input = EditText(context).apply {
            setText(value)
            this.hint = hint
            textSize = 16f
            setTextColor(Color.parseColor(TEXT))
            setHintTextColor(Color.parseColor(MUTED))
            setSingleLine(true)
            background = null
            setPadding(0, context.dp(2), 0, 0)
            if (password) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
        }
        box.addView(input)
        return box to input
    }

    /** 带图标位、主副标题、右侧箭头的行（用于「新增供应商」「模型管理」）。 */
    fun actionRow(
        context: Context,
        icon: String,
        title: String,
        sub: String,
        onClick: () -> Unit,
        iconBg: String = ACCENT_SOFT,
        iconColor: String = ACCENT,
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(14), context.dp(16), context.dp(14), context.dp(16))
        background = rounded(CARD, 0)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        EtaMotion.press(context, this)
        addView(TextView(context).apply {
            text = icon
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(iconColor))
            background = rounded(iconBg, 11)
            val s = context.dp(38)
            layoutParams = LinearLayout.LayoutParams(s, s)
        })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = context.dp(14) }
            addView(TextView(context).apply {
                text = title
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor(TEXT))
            })
            if (sub.isNotBlank()) addView(TextView(context).apply {
                text = sub
                textSize = 12f
                setTextColor(Color.parseColor(SUB))
                setPadding(0, context.dp(3), 0, 0)
            })
        })
        addView(TextView(context).apply {
            text = "›"
            textSize = 22f
            setTextColor(Color.parseColor(MUTED))
        })
    }

    fun divider(context: Context): View = View(context).apply {
        setBackgroundColor(Color.parseColor(LINE))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, context.dp(1),
        ).apply { leftMargin = context.dp(14); rightMargin = context.dp(14) }
    }

    fun chip(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 11f
        setTextColor(Color.parseColor(SUB))
        background = rounded(CHIP, 8)
        setPadding(context.dp(8), context.dp(3), context.dp(8), context.dp(3))
    }

    /** 右边的单选圈：选中显示蓝底勾，未选中为空心圆。 */
    fun radio(context: Context, checked: Boolean): TextView = TextView(context).apply {
        text = if (checked) "✓" else ""
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(Color.parseColor("#FFFFFF"))
        background = if (checked) rounded(ACCENT, 12, ACCENT)
        else GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = context.dp(12).toFloat()
            setStroke(context.dp(2), Color.parseColor("#B9BFC8"))
        }
        val s = context.dp(24)
        layoutParams = LinearLayout.LayoutParams(s, s)
    }

    /** 分段 Tab（设定 / 模型）。 */
    fun segmented(
        context: Context,
        labels: List<String>,
        onSelect: (Int) -> Unit,
    ): Pair<LinearLayout, (Int) -> Unit> {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(FIELD, 12)
            setPadding(context.dp(4), context.dp(4), context.dp(4), context.dp(4))
        }
        val buttons = labels.mapIndexed { index, label ->
            TextView(context).apply {
                text = label
                textSize = 15f
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
                setPadding(context.dp(10), context.dp(11), context.dp(10), context.dp(11))
                setOnClickListener { onSelect(index); EtaMotion.pop(context, this) }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                EtaMotion.press(context, this)
            }.also { row.addView(it) }
        }
        val select: (Int) -> Unit = { active ->
            buttons.forEachIndexed { i, b ->
                b.background = if (i == active) rounded(CARD, 10) else null
                b.setTextColor(Color.parseColor(if (i == active) TEXT else SUB))
            }
        }
        return row to select
    }

    fun primaryButton(context: Context, text: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 15f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#FFFFFF"))
            background = rounded(ACCENT, 13)
            setPadding(context.dp(14), context.dp(14), context.dp(14), context.dp(14))
            setOnClickListener { onClick() }
            EtaMotion.press(context, this)
        }

    fun linkRow(context: Context, label: String, value: String, onClick: () -> Unit): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(4), context.dp(16), context.dp(4), context.dp(16))
            setOnClickListener { onClick() }
            EtaMotion.press(context, this)
            addView(TextView(context).apply {
                text = label
                textSize = 16f
                setTextColor(Color.parseColor(TEXT))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(context).apply {
                text = value
                textSize = 14f
                setTextColor(Color.parseColor(SUB))
            })
            addView(TextView(context).apply {
                text = "  ›"
                textSize = 18f
                setTextColor(Color.parseColor(MUTED))
            })
        }
}
