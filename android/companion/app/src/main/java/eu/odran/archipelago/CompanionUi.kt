package eu.odran.archipelago

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

private class ResponsiveScreenLayout(context: Context) : LinearLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = View.MeasureSpec.getSize(widthMeasureSpec)
        val widthMode = View.MeasureSpec.getMode(widthMeasureSpec)
        val maximumWidth = CompanionUi.dp(context, CompanionUi.MAX_CONTENT_WIDTH_DP)
        val measuredWidth = when (widthMode) {
            View.MeasureSpec.UNSPECIFIED -> maximumWidth
            else -> minOf(availableWidth, maximumWidth)
        }
        val tablet = measuredWidth >= CompanionUi.dp(context, CompanionUi.TABLET_BREAKPOINT_DP)
        val horizontalPadding = CompanionUi.dp(context, if (tablet) 36 else 20)
        val topPadding = CompanionUi.dp(context, if (tablet) 32 else 24)
        val bottomPadding = CompanionUi.dp(context, if (tablet) 40 else 32)
        if (
            paddingLeft != horizontalPadding || paddingTop != topPadding ||
            paddingRight != horizontalPadding || paddingBottom != bottomPadding
        ) {
            setPadding(horizontalPadding, topPadding, horizontalPadding, bottomPadding)
        }
        super.onMeasure(
            View.MeasureSpec.makeMeasureSpec(measuredWidth, View.MeasureSpec.EXACTLY),
            heightMeasureSpec,
        )
    }
}

/** Small programmatic UI kit shared by the companion's Android-only screens. */
object CompanionUi {
    const val TABLET_BREAKPOINT_DP = 600
    const val MAX_CONTENT_WIDTH_DP = 880

    val background: Int = Color.rgb(246, 247, 251)
    val surface: Int = Color.WHITE
    val primary: Int = Color.rgb(49, 87, 164)
    val primarySoft: Int = Color.rgb(232, 238, 250)
    val active: Int = Color.rgb(24, 117, 76)
    val activeSoft: Int = Color.rgb(230, 246, 238)
    val activeBorder: Int = Color.rgb(177, 218, 197)
    val warning: Int = Color.rgb(151, 91, 9)
    val warningSoft: Int = Color.rgb(255, 245, 219)
    val warningBorder: Int = Color.rgb(235, 207, 145)
    val text: Int = Color.rgb(27, 35, 52)
    val textMuted: Int = Color.rgb(91, 100, 119)
    val border: Int = Color.rgb(222, 226, 235)
    val danger: Int = Color.rgb(179, 38, 30)

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun screen(context: Context): LinearLayout = ResponsiveScreenLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(CompanionUi.background)
    }

    fun scrollView(context: Context, content: View): ScrollView = ScrollView(context).apply {
        isFillViewport = true
        setBackgroundColor(CompanionUi.background)
        addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL,
            ),
        )
    }

    fun responsiveHost(context: Context, content: View): FrameLayout = FrameLayout(context).apply {
        setBackgroundColor(CompanionUi.background)
        addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER_HORIZONTAL,
            ),
        )
    }

    fun pageTitle(context: Context, title: String, subtitle: String): LinearLayout =
        LinearLayout(context).apply {
            val tablet = context.resources.configuration.screenWidthDp >= TABLET_BREAKPOINT_DP
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 4), 0, dp(context, 4), dp(context, 8))
            addView(TextView(context).apply {
                text = title
                textSize = if (tablet) 30f else 27f
                setTextColor(CompanionUi.text)
                setTypeface(typeface, Typeface.BOLD)
            }, fullWidth())
            addView(TextView(context).apply {
                text = subtitle
                textSize = 15f
                setTextColor(textMuted)
                setPadding(0, dp(context, 6), 0, 0)
            }, fullWidth())
        }

    fun card(context: Context, title: String, subtitle: String? = null): LinearLayout =
        LinearLayout(context).apply {
            val tablet = context.resources.configuration.screenWidthDp >= TABLET_BREAKPOINT_DP
            orientation = LinearLayout.VERTICAL
            val horizontal = dp(context, if (tablet) 22 else 18)
            val vertical = dp(context, if (tablet) 20 else 16)
            setPadding(horizontal, vertical, horizontal, vertical)
            background = roundedBackground(context, surface, border, 16)
            elevation = dp(context, 2).toFloat()
            addView(TextView(context).apply {
                text = title
                textSize = if (tablet) 20f else 19f
                setTextColor(CompanionUi.text)
                setTypeface(typeface, Typeface.BOLD)
            }, fullWidth())
            if (!subtitle.isNullOrBlank()) {
                addView(TextView(context).apply {
                    text = subtitle
                    textSize = 14f
                    setTextColor(textMuted)
                    setPadding(0, dp(context, 4), 0, dp(context, 8))
                }, fullWidth())
            } else {
                addView(View(context), LinearLayout.LayoutParams(1, dp(context, 8)))
            }
        }

    fun panel(context: Context, selected: Boolean = false, active: Boolean = false): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = dp(context, 14)
            setPadding(padding, padding, padding, padding)
            background = roundedBackground(
                context,
                when {
                    active -> primarySoft
                    selected -> primarySoft
                    else -> Color.rgb(249, 250, 253)
                },
                when {
                    active -> primary
                    selected -> primary
                    else -> border
                },
                12,
            )
        }

    enum class StatusTone { NEUTRAL, ACTIVE, WARNING, ERROR }

    fun statusChip(context: Context, label: String, tone: StatusTone = StatusTone.NEUTRAL) =
        TextView(context).apply {
            text = label
            textSize = 12f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            val colors = when (tone) {
                StatusTone.ACTIVE -> Triple(activeSoft, activeBorder, active)
                StatusTone.WARNING -> Triple(warningSoft, warningBorder, warning)
                StatusTone.ERROR -> Triple(Color.rgb(252, 235, 233), Color.rgb(236, 181, 177), danger)
                StatusTone.NEUTRAL -> Triple(Color.rgb(241, 243, 247), border, textMuted)
            }
            setTextColor(colors.third)
            background = roundedBackground(context, colors.first, colors.second, 20)
            setPadding(dp(context, 10), dp(context, 5), dp(context, 10), dp(context, 5))
        }

    fun wrapContentParams(context: Context, endMargin: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { marginEnd = dp(context, endMargin) }

    fun cardParams(context: Context, topMargin: Int = 12) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
        this.topMargin = dp(
            context,
            if (context.resources.configuration.screenWidthDp >= TABLET_BREAKPOINT_DP) {
                maxOf(topMargin, 14)
            } else {
                topMargin
            },
        )
    }

    fun fullWidth() = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    fun weightedButtonParams(context: Context, endMargin: Int = 0) = LinearLayout.LayoutParams(
        0,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        1f,
    ).apply { marginEnd = dp(context, endMargin) }

    fun stylePrimary(button: Button) = button.apply {
        isAllCaps = false
        textSize = 15f
        minHeight = dp(context, 48)
        setTextColor(statefulColors(Color.WHITE, Color.rgb(132, 140, 157)))
        backgroundTintList = statefulColors(primary, Color.rgb(224, 227, 234))
    }

    fun styleSecondary(button: Button) = button.apply {
        isAllCaps = false
        textSize = 15f
        minHeight = dp(context, 48)
        setTextColor(statefulColors(primary, Color.rgb(132, 140, 157)))
        backgroundTintList = statefulColors(primarySoft, Color.rgb(238, 240, 244))
    }

    fun styleQuiet(button: Button) = button.apply {
        isAllCaps = false
        textSize = 14f
        minHeight = dp(context, 44)
        setTextColor(statefulColors(textMuted, Color.rgb(151, 157, 169)))
        backgroundTintList = statefulColors(Color.rgb(241, 243, 247), Color.rgb(245, 246, 248))
    }

    fun styleDanger(button: Button) = button.apply {
        isAllCaps = false
        textSize = 14f
        minHeight = dp(context, 44)
        setTextColor(danger)
        backgroundTintList = ColorStateList.valueOf(Color.rgb(252, 235, 233))
    }

    fun styleBody(textView: TextView) = textView.apply {
        textSize = 15f
        setTextColor(CompanionUi.text)
        setLineSpacing(0f, 1.08f)
    }

    fun styleMuted(textView: TextView) = textView.apply {
        textSize = 13f
        setTextColor(textMuted)
    }

    fun toggleButton(context: Context, label: String, target: View, expanded: Boolean = false) =
        Button(context).apply {
            target.visibility = if (expanded) View.VISIBLE else View.GONE
            text = if (expanded) "Hide $label" else "Show $label"
            styleQuiet(this)
            setOnClickListener {
                val scrollView = generateSequence(parent as? View) { it.parent as? View }
                    .filterIsInstance<ScrollView>()
                    .firstOrNull()
                val previousY = scrollView?.scrollY
                val show = target.visibility != View.VISIBLE
                target.visibility = if (show) View.VISIBLE else View.GONE
                text = if (show) "Hide $label" else "Show $label"
                if (scrollView != null && previousY != null) scrollView.post {
                    val contentHeight = scrollView.getChildAt(0)?.height ?: 0
                    val maximumY = (contentHeight - scrollView.height).coerceAtLeast(0)
                    scrollView.scrollTo(0, previousY.coerceAtMost(maximumY))
                }
            }
        }

    fun insetTop(view: View, context: Context, top: Int = 8) =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(context, top) }

    fun roundedBackground(
        context: Context,
        fillColor: Int,
        strokeColor: Int,
        radius: Int,
    ) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        color = ColorStateList.valueOf(fillColor)
        cornerRadius = dp(context, radius).toFloat()
        setStroke(dp(context, 1), strokeColor)
    }

    private fun statefulColors(enabled: Int, disabled: Int) = ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(disabled, enabled),
    )
}
