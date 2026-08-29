package eu.odran.archipelago

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat

private enum class ResponsiveRole { CARD, FULL_SPAN }

internal enum class CompanionWindowWidthClass { COMPACT, MEDIUM, EXPANDED }

internal fun companionWindowWidthClass(widthDp: Int): CompanionWindowWidthClass = when {
    widthDp >= CompanionUi.WIDE_BREAKPOINT_DP -> CompanionWindowWidthClass.EXPANDED
    widthDp >= CompanionUi.TABLET_BREAKPOINT_DP -> CompanionWindowWidthClass.MEDIUM
    else -> CompanionWindowWidthClass.COMPACT
}

internal fun shouldUseSplitLayout(widthDp: Int, cardCount: Int): Boolean =
    cardCount >= 2 && companionWindowWidthClass(widthDp) == CompanionWindowWidthClass.EXPANDED

internal fun shouldLayOutActionsHorizontally(
    widthDp: Int,
    visibleChildCount: Int,
    minimumChildWidthDp: Int,
    gapDp: Int,
): Boolean = visibleChildCount <= 1 ||
    widthDp >= visibleChildCount * minimumChildWidthDp + (visibleChildCount - 1) * gapDp

private data class CompanionPalette(
    val background: Int,
    val surface: Int,
    val primary: Int,
    val primarySoft: Int,
    val primaryBorder: Int,
    val primaryButton: Int,
    val active: Int,
    val activeSoft: Int,
    val activeBorder: Int,
    val activeBubble: Int,
    val warning: Int,
    val warningSoft: Int,
    val warningBorder: Int,
    val text: Int,
    val textMuted: Int,
    val border: Int,
    val danger: Int,
    val panelSurface: Int,
    val neutralSoft: Int,
    val errorSoft: Int,
    val errorBorder: Int,
    val errorBubble: Int,
    val disabledText: Int,
    val disabledFill: Int,
    val quietFill: Int,
    val quietDisabledFill: Int,
)

private class ResponsiveScreenLayout(context: Context) : LinearLayout(context) {
    private val splitBounds = mutableMapOf<View, Rect>()
    private var splitLayout = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = View.MeasureSpec.getSize(widthMeasureSpec)
        val widthMode = View.MeasureSpec.getMode(widthMeasureSpec)
        val cardCount = (0 until childCount).count { index ->
            val child = getChildAt(index)
            child.visibility != View.GONE && child.tag == ResponsiveRole.CARD
        }
        val availableWidthDp = (availableWidth / context.resources.displayMetrics.density).toInt()
        val canSplit = shouldUseSplitLayout(availableWidthDp, cardCount)
        val maximumWidth = CompanionUi.dp(
            context,
            if (canSplit) CompanionUi.MAX_WIDE_CONTENT_WIDTH_DP else CompanionUi.MAX_CONTENT_WIDTH_DP,
        )
        val measuredWidth = when (widthMode) {
            View.MeasureSpec.UNSPECIFIED -> maximumWidth
            else -> minOf(availableWidth, maximumWidth)
        }
        val tablet = measuredWidth >= CompanionUi.dp(context, CompanionUi.TABLET_BREAKPOINT_DP)
        val horizontalPadding = CompanionUi.dp(context, when {
            canSplit -> 40
            tablet -> 36
            else -> 20
        })
        val topPadding = CompanionUi.dp(context, if (tablet) 32 else 24)
        val bottomPadding = CompanionUi.dp(context, if (tablet) 40 else 32)
        if (
            paddingLeft != horizontalPadding || paddingTop != topPadding ||
            paddingRight != horizontalPadding || paddingBottom != bottomPadding
        ) {
            setPadding(horizontalPadding, topPadding, horizontalPadding, bottomPadding)
        }
        splitLayout = canSplit
        if (splitLayout) {
            measureSplitLayout(measuredWidth, heightMeasureSpec)
            return
        }
        splitBounds.clear()
        super.onMeasure(
            View.MeasureSpec.makeMeasureSpec(measuredWidth, View.MeasureSpec.EXACTLY),
            heightMeasureSpec,
        )
    }

    private fun measureSplitLayout(measuredWidth: Int, heightMeasureSpec: Int) {
        splitBounds.clear()
        val columnGap = CompanionUi.dp(context, CompanionUi.WIDE_COLUMN_GAP_DP)
        val contentWidth = (measuredWidth - paddingLeft - paddingRight).coerceAtLeast(0)
        val columnWidth = ((contentWidth - columnGap) / 2).coerceAtLeast(0)
        val columnBottoms = intArrayOf(paddingTop, paddingTop)

        repeat(childCount) { index ->
            val child = getChildAt(index)
            if (child.visibility == View.GONE) return@repeat
            val margins = child.layoutParams as? ViewGroup.MarginLayoutParams
                ?: ViewGroup.MarginLayoutParams(child.layoutParams)
            val isCard = child.tag == ResponsiveRole.CARD
            val availableChildWidth = if (isCard) columnWidth else contentWidth
            val childWidth = (availableChildWidth - margins.leftMargin - margins.rightMargin)
                .coerceAtLeast(0)
            val childHeightSpec = getChildMeasureSpec(
                heightMeasureSpec,
                paddingTop + paddingBottom + margins.topMargin + margins.bottomMargin,
                margins.height,
            )
            child.measure(
                View.MeasureSpec.makeMeasureSpec(childWidth, View.MeasureSpec.EXACTLY),
                childHeightSpec,
            )

            if (isCard) {
                val column = if (columnBottoms[0] <= columnBottoms[1]) 0 else 1
                val left = paddingLeft + column * (columnWidth + columnGap) + margins.leftMargin
                val top = columnBottoms[column] + margins.topMargin
                splitBounds[child] = Rect(left, top, left + child.measuredWidth, top + child.measuredHeight)
                columnBottoms[column] = top + child.measuredHeight + margins.bottomMargin
            } else {
                val top = maxOf(columnBottoms[0], columnBottoms[1]) + margins.topMargin
                val left = paddingLeft + margins.leftMargin
                splitBounds[child] = Rect(left, top, left + child.measuredWidth, top + child.measuredHeight)
                val bottom = top + child.measuredHeight + margins.bottomMargin
                columnBottoms[0] = bottom
                columnBottoms[1] = bottom
            }
        }

        val desiredHeight = maxOf(columnBottoms[0], columnBottoms[1]) + paddingBottom
        setMeasuredDimension(
            measuredWidth,
            resolveSize(maxOf(desiredHeight, suggestedMinimumHeight), heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (!splitLayout) {
            super.onLayout(changed, left, top, right, bottom)
            return
        }
        repeat(childCount) { index ->
            val child = getChildAt(index)
            if (child.visibility != View.GONE) splitBounds[child]?.let { bounds ->
                child.layout(bounds.left, bounds.top, bounds.right, bounds.bottom)
            }
        }
    }
}

@SuppressLint("ViewConstructor")
private class ResponsiveActionLayout(
    context: Context,
    private val minimumChildWidthDp: Int,
    private val gapDp: Int,
    private val compactLastChild: Boolean = false,
) : LinearLayout(context) {
    private var horizontalLayout = true

    init {
        orientation = HORIZONTAL
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = if (View.MeasureSpec.getMode(widthMeasureSpec) == View.MeasureSpec.UNSPECIFIED) {
            Int.MAX_VALUE
        } else {
            (View.MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
        }
        val availableWidthDp = (availableWidth / resources.displayMetrics.density).toInt()
        val visibleChildren = (0 until childCount).map(::getChildAt).filter { it.visibility != GONE }
        val useHorizontal = shouldLayOutActionsHorizontally(
            availableWidthDp,
            visibleChildren.size,
            minimumChildWidthDp,
            gapDp,
        )
        if (horizontalLayout != useHorizontal) {
            horizontalLayout = useHorizontal
            orientation = if (useHorizontal) HORIZONTAL else VERTICAL
        }
        val gap = CompanionUi.dp(context, gapDp)
        visibleChildren.forEachIndexed { index, child ->
            val params = child.layoutParams as LayoutParams
            val compact = useHorizontal && compactLastChild && index == visibleChildren.lastIndex
            val targetWidth = when {
                !useHorizontal -> LayoutParams.MATCH_PARENT
                compact -> LayoutParams.WRAP_CONTENT
                else -> 0
            }
            val targetWeight = if (useHorizontal && !compact) 1f else 0f
            val targetEndMargin = if (useHorizontal && index != visibleChildren.lastIndex) gap else 0
            val targetTopMargin = if (!useHorizontal && index != 0) gap else 0
            val changed = params.width != targetWidth ||
                params.height != LayoutParams.WRAP_CONTENT ||
                params.weight != targetWeight || params.marginStart != 0 ||
                params.marginEnd != targetEndMargin || params.topMargin != targetTopMargin
            if (useHorizontal) {
                params.width = targetWidth
                params.height = LayoutParams.WRAP_CONTENT
                params.weight = targetWeight
                params.marginStart = 0
                params.marginEnd = targetEndMargin
                params.topMargin = targetTopMargin
            } else {
                params.width = targetWidth
                params.height = LayoutParams.WRAP_CONTENT
                params.weight = targetWeight
                params.marginStart = 0
                params.marginEnd = targetEndMargin
                params.topMargin = targetTopMargin
            }
            if (changed) child.layoutParams = params
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}

@SuppressLint("ViewConstructor")
private class ResponsivePageTitleLayout(
    context: Context,
    title: String,
    subtitle: String,
) : LinearLayout(context) {
    private val titleView = TextView(context).apply {
        text = title
        setTextColor(CompanionUi.text)
        setTypeface(typeface, Typeface.BOLD)
        ViewCompat.setAccessibilityHeading(this, true)
    }
    private val subtitleView = TextView(context).apply {
        text = subtitle
        textSize = 15f
        setTextColor(CompanionUi.textMuted)
        setPadding(0, CompanionUi.dp(context, 6), 0, 0)
    }
    private var roomy: Boolean? = null

    init {
        tag = ResponsiveRole.FULL_SPAN
        orientation = VERTICAL
        addView(titleView, CompanionUi.fullWidth())
        addView(subtitleView, CompanionUi.fullWidth())
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthDp = (View.MeasureSpec.getSize(widthMeasureSpec) / resources.displayMetrics.density).toInt()
        val updatedRoomy = widthDp >= CompanionUi.TABLET_BREAKPOINT_DP
        if (roomy != updatedRoomy) {
            roomy = updatedRoomy
            titleView.textSize = if (updatedRoomy) 30f else 27f
            setPadding(
                CompanionUi.dp(context, 4),
                0,
                CompanionUi.dp(context, 4),
                CompanionUi.dp(context, if (updatedRoomy) 12 else 8),
            )
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}

@SuppressLint("ViewConstructor")
private class ResponsiveCardLayout(
    context: Context,
    title: String,
    subtitle: String?,
) : LinearLayout(context) {
    private val titleView = TextView(context).apply {
        text = title
        setTextColor(CompanionUi.text)
        setTypeface(typeface, Typeface.BOLD)
        ViewCompat.setAccessibilityHeading(this, true)
    }
    private var roomy: Boolean? = null

    init {
        tag = ResponsiveRole.CARD
        orientation = VERTICAL
        background = CompanionUi.roundedBackground(context, CompanionUi.surface, CompanionUi.border, 16)
        elevation = CompanionUi.dp(context, 2).toFloat()
        addView(titleView, CompanionUi.fullWidth())
        if (!subtitle.isNullOrBlank()) {
            addView(TextView(context).apply {
                text = subtitle
                textSize = 14f
                setTextColor(CompanionUi.textMuted)
                setPadding(0, CompanionUi.dp(context, 4), 0, CompanionUi.dp(context, 8))
            }, CompanionUi.fullWidth())
        } else {
            addView(View(context), LayoutParams(1, CompanionUi.dp(context, 8)))
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthDp = (View.MeasureSpec.getSize(widthMeasureSpec) / resources.displayMetrics.density).toInt()
        val updatedRoomy = widthDp >= 520
        if (roomy != updatedRoomy) {
            roomy = updatedRoomy
            titleView.textSize = if (updatedRoomy) 20f else 19f
            val horizontal = CompanionUi.dp(context, if (updatedRoomy) 22 else 18)
            val vertical = CompanionUi.dp(context, if (updatedRoomy) 20 else 16)
            setPadding(horizontal, vertical, horizontal, vertical)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}

@SuppressLint("ViewConstructor")
private class ResponsiveFlowLayout(
    context: Context,
    horizontalGapDp: Int,
    verticalGapDp: Int,
) : ViewGroup(context) {
    private val horizontalGap = CompanionUi.dp(context, horizontalGapDp)
    private val verticalGap = CompanionUi.dp(context, verticalGapDp)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = View.MeasureSpec.getMode(widthMeasureSpec)
        val availableWidth = if (widthMode == View.MeasureSpec.UNSPECIFIED) {
            Int.MAX_VALUE
        } else {
            (View.MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
        }
        var lineWidth = 0
        var lineHeight = 0
        var maximumLineWidth = 0
        var contentHeight = 0
        var itemsOnLine = 0
        repeat(childCount) { index ->
            val child = getChildAt(index)
            if (child.visibility == GONE) return@repeat
            measureChildWithMargins(child, widthMeasureSpec, paddingLeft + paddingRight, heightMeasureSpec, 0)
            val margins = child.layoutParams as MarginLayoutParams
            val childWidth = child.measuredWidth + margins.leftMargin + margins.rightMargin
            val childHeight = child.measuredHeight + margins.topMargin + margins.bottomMargin
            val proposedWidth = lineWidth + if (itemsOnLine == 0) 0 else horizontalGap + childWidth
            if (itemsOnLine > 0 && proposedWidth > availableWidth) {
                maximumLineWidth = maxOf(maximumLineWidth, lineWidth)
                contentHeight += lineHeight + verticalGap
                lineWidth = childWidth
                lineHeight = childHeight
                itemsOnLine = 1
            } else {
                lineWidth = proposedWidth
                lineHeight = maxOf(lineHeight, childHeight)
                itemsOnLine += 1
            }
        }
        maximumLineWidth = maxOf(maximumLineWidth, lineWidth)
        if (itemsOnLine > 0) contentHeight += lineHeight
        setMeasuredDimension(
            resolveSize(maximumLineWidth + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(contentHeight + paddingTop + paddingBottom, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val availableWidth = (right - left - paddingLeft - paddingRight).coerceAtLeast(0)
        var x = paddingLeft
        var y = paddingTop
        var lineHeight = 0
        var itemsOnLine = 0
        repeat(childCount) { index ->
            val child = getChildAt(index)
            if (child.visibility == GONE) return@repeat
            val margins = child.layoutParams as MarginLayoutParams
            val childWidth = child.measuredWidth + margins.leftMargin + margins.rightMargin
            val childHeight = child.measuredHeight + margins.topMargin + margins.bottomMargin
            val usedWidth = x - paddingLeft
            if (itemsOnLine > 0 && usedWidth + horizontalGap + childWidth > availableWidth) {
                x = paddingLeft
                y += lineHeight + verticalGap
                lineHeight = 0
                itemsOnLine = 0
            }
            if (itemsOnLine > 0) x += horizontalGap
            val childLeft = x + margins.leftMargin
            val childTop = y + margins.topMargin
            child.layout(
                childLeft,
                childTop,
                childLeft + child.measuredWidth,
                childTop + child.measuredHeight,
            )
            x += childWidth
            lineHeight = maxOf(lineHeight, childHeight)
            itemsOnLine += 1
        }
    }

    override fun generateDefaultLayoutParams() = MarginLayoutParams(
        LayoutParams.WRAP_CONTENT,
        LayoutParams.WRAP_CONTENT,
    )

    override fun generateLayoutParams(attributes: android.util.AttributeSet?) =
        MarginLayoutParams(context, attributes)

    override fun generateLayoutParams(params: LayoutParams?) = MarginLayoutParams(params)

    override fun checkLayoutParams(params: LayoutParams?) = params is MarginLayoutParams
}

/** Small programmatic UI kit shared by the companion's Android-only screens. */
object CompanionUi {
    const val TABLET_BREAKPOINT_DP = 600
    const val WIDE_BREAKPOINT_DP = 840
    const val MAX_CONTENT_WIDTH_DP = 880
    const val MAX_WIDE_CONTENT_WIDTH_DP = 1280
    const val WIDE_COLUMN_GAP_DP = 16

    private val lightPalette = CompanionPalette(
        background = Color.rgb(246, 247, 251),
        surface = Color.WHITE,
        primary = Color.rgb(49, 87, 164),
        primarySoft = Color.rgb(232, 238, 250),
        primaryBorder = Color.rgb(181, 197, 229),
        primaryButton = Color.rgb(49, 87, 164),
        active = Color.rgb(24, 117, 76),
        activeSoft = Color.rgb(230, 246, 238),
        activeBorder = Color.rgb(177, 218, 197),
        activeBubble = Color.rgb(242, 251, 246),
        warning = Color.rgb(151, 91, 9),
        warningSoft = Color.rgb(255, 245, 219),
        warningBorder = Color.rgb(235, 207, 145),
        text = Color.rgb(27, 35, 52),
        textMuted = Color.rgb(91, 100, 119),
        border = Color.rgb(222, 226, 235),
        danger = Color.rgb(179, 38, 30),
        panelSurface = Color.rgb(249, 250, 253),
        neutralSoft = Color.rgb(241, 243, 247),
        errorSoft = Color.rgb(252, 235, 233),
        errorBorder = Color.rgb(236, 181, 177),
        errorBubble = Color.rgb(255, 247, 246),
        disabledText = Color.rgb(132, 140, 157),
        disabledFill = Color.rgb(224, 227, 234),
        quietFill = Color.rgb(241, 243, 247),
        quietDisabledFill = Color.rgb(245, 246, 248),
    )
    private val darkPalette = CompanionPalette(
        background = Color.rgb(17, 20, 27),
        surface = Color.rgb(27, 32, 42),
        primary = Color.rgb(143, 175, 255),
        primarySoft = Color.rgb(35, 49, 76),
        primaryBorder = Color.rgb(67, 88, 132),
        primaryButton = Color.rgb(73, 104, 177),
        active = Color.rgb(99, 210, 154),
        activeSoft = Color.rgb(26, 57, 44),
        activeBorder = Color.rgb(58, 107, 82),
        activeBubble = Color.rgb(22, 48, 38),
        warning = Color.rgb(244, 188, 77),
        warningSoft = Color.rgb(61, 47, 22),
        warningBorder = Color.rgb(111, 83, 34),
        text = Color.rgb(235, 239, 247),
        textMuted = Color.rgb(170, 180, 198),
        border = Color.rgb(57, 65, 80),
        danger = Color.rgb(255, 132, 124),
        panelSurface = Color.rgb(31, 37, 48),
        neutralSoft = Color.rgb(40, 46, 57),
        errorSoft = Color.rgb(76, 36, 36),
        errorBorder = Color.rgb(130, 65, 61),
        errorBubble = Color.rgb(54, 29, 30),
        disabledText = Color.rgb(114, 123, 140),
        disabledFill = Color.rgb(45, 51, 62),
        quietFill = Color.rgb(36, 42, 52),
        quietDisabledFill = Color.rgb(40, 45, 55),
    )
    private var palette = lightPalette

    val background: Int get() = palette.background
    val surface: Int get() = palette.surface
    val primary: Int get() = palette.primary
    val primarySoft: Int get() = palette.primarySoft
    val primaryBorder: Int get() = palette.primaryBorder
    val active: Int get() = palette.active
    val activeSoft: Int get() = palette.activeSoft
    val activeBorder: Int get() = palette.activeBorder
    val activeBubble: Int get() = palette.activeBubble
    val warning: Int get() = palette.warning
    val warningSoft: Int get() = palette.warningSoft
    val warningBorder: Int get() = palette.warningBorder
    val text: Int get() = palette.text
    val textMuted: Int get() = palette.textMuted
    val border: Int get() = palette.border
    val danger: Int get() = palette.danger
    val panelSurface: Int get() = palette.panelSurface
    val neutralSoft: Int get() = palette.neutralSoft
    val errorSoft: Int get() = palette.errorSoft
    val errorBorder: Int get() = palette.errorBorder
    val errorBubble: Int get() = palette.errorBubble

    internal fun configure(darkMode: Boolean) {
        palette = if (darkMode) darkPalette else lightPalette
    }

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

    fun actionRow(
        context: Context,
        minimumChildWidthDp: Int = 156,
        gapDp: Int = 6,
    ): LinearLayout = ResponsiveActionLayout(context, minimumChildWidthDp, gapDp)

    fun inlineActionRow(
        context: Context,
        minimumChildWidthDp: Int = 128,
        gapDp: Int = 8,
    ): LinearLayout = ResponsiveActionLayout(
        context,
        minimumChildWidthDp,
        gapDp,
        compactLastChild = true,
    )

    fun flowRow(
        context: Context,
        horizontalGapDp: Int = 6,
        verticalGapDp: Int = 6,
    ): ViewGroup = ResponsiveFlowLayout(context, horizontalGapDp, verticalGapDp)

    fun pageTitle(context: Context, title: String, subtitle: String): LinearLayout =
        ResponsivePageTitleLayout(context, title, subtitle)

    fun card(context: Context, title: String, subtitle: String? = null): LinearLayout =
        ResponsiveCardLayout(context, title, subtitle)

    fun panel(context: Context, selected: Boolean = false, active: Boolean = false): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = dp(context, 14)
            setPadding(padding, padding, padding, padding)
            background = roundedBackground(
                context,
                when {
                    active -> activeSoft
                    selected -> primarySoft
                    else -> panelSurface
                },
                when {
                    active -> CompanionUi.active
                    selected -> primary
                    else -> border
                },
                12,
            )
        }

    enum class StatusTone { NEUTRAL, INFO, ACTIVE, WARNING, ERROR }

    fun statusChip(context: Context, label: String, tone: StatusTone = StatusTone.NEUTRAL) =
        TextView(context).apply {
            text = label
            textSize = 12f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            val colors = when (tone) {
                StatusTone.ACTIVE -> Triple(activeSoft, activeBorder, active)
                StatusTone.INFO -> Triple(primarySoft, primaryBorder, primary)
                StatusTone.WARNING -> Triple(warningSoft, warningBorder, warning)
                StatusTone.ERROR -> Triple(errorSoft, errorBorder, danger)
                StatusTone.NEUTRAL -> Triple(neutralSoft, border, textMuted)
            }
            setTextColor(colors.third)
            background = roundedBackground(context, colors.first, colors.second, 20)
            setPadding(dp(context, 10), dp(context, 5), dp(context, 10), dp(context, 5))
        }

    fun statusChip(context: Context, status: RoomStatusPresentation) =
        statusChip(context, status.label, status.level.toStatusTone()).apply {
            contentDescription = context.getString(
                R.string.room_status_description,
                status.label.lowercase(),
                status.summary,
            )
            tooltipText = status.summary
        }

    fun styleStatus(
        textView: TextView,
        level: CompanionStatusLevel,
        compact: Boolean = false,
    ) = textView.apply {
        val colors = statusColors(level)
        textSize = if (compact) 13f else 14f
        setTextColor(colors.third)
        background = roundedBackground(context, colors.first, colors.second, 10)
        val horizontal = dp(context, if (compact) 10 else 12)
        val vertical = dp(context, if (compact) 7 else 10)
        setPadding(horizontal, vertical, horizontal, vertical)
        setLineSpacing(0f, 1.08f)
    }

    fun wrapContentParams(context: Context, endMargin: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { marginEnd = dp(context, endMargin) }

    fun cardParams(context: Context, topMargin: Int = 12) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
        this.topMargin = dp(context, topMargin)
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
        setTextColor(statefulColors(Color.WHITE, palette.disabledText))
        backgroundTintList = statefulColors(palette.primaryButton, palette.disabledFill)
    }

    fun styleSecondary(button: Button) = button.apply {
        isAllCaps = false
        textSize = 15f
        minHeight = dp(context, 48)
        setTextColor(statefulColors(primary, palette.disabledText))
        backgroundTintList = statefulColors(primarySoft, palette.disabledFill)
    }

    fun styleQuiet(button: Button) = button.apply {
        isAllCaps = false
        textSize = 14f
        minHeight = dp(context, 44)
        setTextColor(statefulColors(textMuted, palette.disabledText))
        backgroundTintList = statefulColors(palette.quietFill, palette.quietDisabledFill)
    }

    fun styleDanger(button: Button) = button.apply {
        isAllCaps = false
        textSize = 14f
        minHeight = dp(context, 44)
        setTextColor(danger)
        backgroundTintList = ColorStateList.valueOf(errorSoft)
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

    private fun statusColors(level: CompanionStatusLevel): Triple<Int, Int, Int> = when (level) {
        CompanionStatusLevel.SUCCESS -> Triple(activeSoft, activeBorder, active)
        CompanionStatusLevel.INFO -> Triple(primarySoft, primaryBorder, primary)
        CompanionStatusLevel.WARNING -> Triple(warningSoft, warningBorder, warning)
        CompanionStatusLevel.ERROR -> Triple(errorSoft, errorBorder, danger)
        CompanionStatusLevel.NEUTRAL -> Triple(neutralSoft, border, textMuted)
    }

    private fun CompanionStatusLevel.toStatusTone(): StatusTone = when (this) {
        CompanionStatusLevel.SUCCESS -> StatusTone.ACTIVE
        CompanionStatusLevel.INFO -> StatusTone.INFO
        CompanionStatusLevel.WARNING -> StatusTone.WARNING
        CompanionStatusLevel.ERROR -> StatusTone.ERROR
        CompanionStatusLevel.NEUTRAL -> StatusTone.NEUTRAL
    }
}

/** Status text which automatically applies the app-wide semantic palette. */
@SuppressLint("ViewConstructor")
class CompanionStatusView(
    context: Context,
    private val hideWhenEmpty: Boolean = false,
) : TextView(context) {
    private var explicitLevel: CompanionStatusLevel? = null

    init {
        minLines = 1
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(value: Editable?) {
                render(value?.toString().orEmpty())
            }
        })
        render("")
    }

    fun show(message: CharSequence, level: CompanionStatusLevel) {
        explicitLevel = level
        text = message
        explicitLevel = null
    }

    private fun render(message: String) {
        visibility = if (hideWhenEmpty && message.isBlank()) View.GONE else View.VISIBLE
        if (message.isBlank()) return
        val level = explicitLevel ?: classifyStatusMessage(message)
        CompanionUi.styleStatus(this, level)
        contentDescription = context.getString(
            R.string.status_description,
            level.name.lowercase(),
            message,
        )
    }
}
