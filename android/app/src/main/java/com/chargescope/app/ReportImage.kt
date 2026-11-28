package com.chargescope.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.OutputStream

/**
 * Renders a crisp, share-ready PNG report card of the current protocol analysis and basic device/battery info.
 * Runs off the main thread; uses only standard Android Canvas/StaticLayout APIs (zero external dependencies).
 */
object ReportImage {
    private const val WIDTH = 1080
    private const val OUTER_PAD = 44f
    private const val CARD_PAD = 40f
    private const val CARD_RADIUS = 36f
    private const val TILE_RADIUS = 24f

    fun exportPng(ctx: Context, ui: UiState, dualCell: Boolean, isDark: Boolean, out: OutputStream): Boolean {
        val a = ui.analysis ?: return false
        val s = ui.latest

        // Palette
        val bgColor = if (isDark) Color.parseColor("#0E0E10") else Color.parseColor("#F4F6FA")
        val cardColor = if (isDark) Color.parseColor("#1C1C1E") else Color.WHITE
        val tileColor = if (isDark) Color.parseColor("#26262A") else Color.parseColor("#F2F4F8")
        val dividerColor = if (isDark) Color.parseColor("#2E2E33") else Color.parseColor("#E6E8EE")
        val textPrimary = if (isDark) Color.parseColor("#F2F2F7") else Color.parseColor("#111114")
        val textSecondary = if (isDark) Color.parseColor("#9A9AA4") else Color.parseColor("#666873")
        val accentBlue = if (isDark) Color.parseColor("#4FC3F7") else Color.parseColor("#1976D2")

        val (badgeFg, badgeBg) = when (a.status) {
            Status.CONFIRMED -> if (isDark) Color.parseColor("#66BB6A") to Color.parseColor("#1B3320")
                else Color.parseColor("#2E7D32") to Color.parseColor("#E8F5E9")
            Status.POSSIBLE -> if (isDark) Color.parseColor("#FFB74D") to Color.parseColor("#332817")
                else Color.parseColor("#EF6C00") to Color.parseColor("#FFF3E0")
            Status.CONFLICT -> if (isDark) Color.parseColor("#FF6B6B") to Color.parseColor("#381B1B")
                else Color.parseColor("#D32F2F") to Color.parseColor("#FFEBEE")
            Status.UNKNOWN, Status.NOT_CHARGING -> textSecondary to tileColor
        }

        // Paints
        val paintFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val paintStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = dividerColor
        }

        fun textPaint(sizePx: Float, color: Int, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = sizePx
            this.color = color
            typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
        }

        val pBrand = textPaint(30f, accentBlue, bold = true)
        val pTime = textPaint(24f, textSecondary)
        val pBadge = textPaint(26f, badgeFg, bold = true)
        val pHeadline = textPaint(44f, textPrimary, bold = true)
        val pSection = textPaint(26f, accentBlue, bold = true)
        val pLabel = textPaint(25f, textSecondary)
        val pValue = textPaint(28f, textPrimary, bold = true)
        val pBody = textPaint(27f, textPrimary)
        val pSmall = textPaint(23f, textSecondary)

        val contentWidth = (WIDTH - OUTER_PAD * 2 - CARD_PAD * 2).toInt()
        val halfTileW = ((contentWidth - 20) / 2)

        fun layoutText(text: String, tp: TextPaint, width: Int): StaticLayout =
            StaticLayout.Builder.obtain(text, 0, text.length, tp, width.coerceAtLeast(1))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(6f, 1.0f)
                .setIncludePad(false)
                .build()

        // Pre-compute sections & text layouts to measure exact bitmap height
        val headlineLayout = layoutText(a.headline, pHeadline, contentWidth)
        val driverVal = a.driver ?: "未报告"
        val predVal = a.predicted ?: "无法预测"
        val driverLayout = layoutText(driverVal, pValue, halfTileW - 40)
        val predLayout = layoutText(predVal, pValue, halfTileW - 40)
        val tileHeight = (maxOf(driverLayout.height, predLayout.height) + 74).toFloat()

        val deviceStr = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"
        val modeStr = when (Prefs.privMode(ctx)) {
            1 -> "Root"
            2 -> "Shizuku"
            else -> "公开 API"
        } + if (dualCell) " · 双电芯" else ""

        val basicRows = buildList<Pair<String, String>> {
            add("设备" to deviceStr)
            add("采集通道" to modeStr)
            add("电量 / 状态" to "${s?.levelPct?.let { "$it%" } ?: "—"} · ${statusText(s?.status) ?: "—"}")
            add("电池侧功率" to (fmt(ui.batteryPowerW, "W") ?: "—"))
            add("输入侧功率" to (fmt(ui.inputPowerW, "W") ?: "—"))
            add(
                "电压 / 电流 / 温度" to (
                    (fmt(s?.voltageMv?.let { it / 1000.0 }, "V") ?: "—") + " · " +
                        (fmt(ui.batteryCurrentMa, "mA", 0) ?: "—") + " · " +
                        (fmt(s?.tempDeci?.let { it / 10.0 }, "°C", 1) ?: "—")
                    ),
            )
            val design = s?.let { Analyzer.designCapacityMah(it, dualCell) }
            val usable = s?.let { Analyzer.usableCapacityMah(it, dualCell) }
            val calcH = s?.let { Analyzer.calculatedHealthPct(it, dualCell) }
            val soh = s?.let { Analyzer.driverSohPct(it) }
            val cyc = s?.let { Analyzer.batteryCycleCount(it) }
            if (design != null || usable != null || calcH != null || soh != null || cyc != null) {
                val healthStr = buildList {
                    if (soh != null) add("系统 $soh%")
                    if (calcH != null) add("估算 $calcH%")
                    if (usable != null && design != null) add("$usable/$design mAh")
                    if (cyc != null) add("${cyc}次循环")
                }.joinToString(" · ")
                if (healthStr.isNotEmpty()) add("电池健康" to healthStr)
            }
        }

        val descLayouts = a.description.lines().filter { it.isNotBlank() }.map { layoutText(it, pBody, contentWidth) }
        val specLayout = a.protocolSpec?.let { layoutText(it, pSmall, contentWidth - 40) }
        val evLayouts = a.evidence.sortedByDescending { it.level }.take(4).map {
            layoutText("L${it.level}  ${it.text}", pSmall, contentWidth)
        }

        // Measure total height
        var h = OUTER_PAD + CARD_PAD
        h += 80f // header row
        h += 28f + headlineLayout.height
        h += 32f + tileHeight
        h += 68f + basicRows.size * 50f // basic info section
        if (descLayouts.isNotEmpty()) {
            h += 76f + descLayouts.sumOf { it.height + 14 }
        }
        if (specLayout != null) {
            h += 24f + specLayout.height + 68f
        }
        if (evLayouts.isNotEmpty()) {
            h += 80f + evLayouts.sumOf { it.height + 10 }
        }
        h += 84f // footer
        h += CARD_PAD + OUTER_PAD

        val bmp = Bitmap.createBitmap(WIDTH, h.toInt().coerceAtLeast(400), Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bmp)
            canvas.drawColor(bgColor)

            // Main rounded card
            val cardRect = RectF(OUTER_PAD, OUTER_PAD, WIDTH - OUTER_PAD, h - OUTER_PAD)
            paintFill.color = cardColor
            canvas.drawRoundRect(cardRect, CARD_RADIUS, CARD_RADIUS, paintFill)
            canvas.drawRoundRect(cardRect, CARD_RADIUS, CARD_RADIUS, paintStroke)

            val x0 = OUTER_PAD + CARD_PAD
            val xRight = WIDTH - OUTER_PAD - CARD_PAD
            var y = OUTER_PAD + CARD_PAD

            // Header: Brand + Time on left, Status badge on right
            canvas.drawText("充了个电 · ChargeScope", x0, y + 28f, pBrand)
            canvas.drawText(AppLog.time(System.currentTimeMillis()), x0, y + 58f, pTime)

            val badgeText = a.status.label
            val badgeW = pBadge.measureText(badgeText) + 44f
            val badgeRect = RectF(xRight - badgeW, y + 4f, xRight, y + 54f)
            paintFill.color = badgeBg
            canvas.drawRoundRect(badgeRect, 25f, 25f, paintFill)
            canvas.drawText(badgeText, badgeRect.left + 22f, badgeRect.top + 35f, pBadge)

            y += 80f

            // Headline
            canvas.save()
            canvas.translate(x0, y)
            headlineLayout.draw(canvas)
            canvas.restore()
            y += headlineLayout.height + 28f

            // Two metric tiles: Driver vs Predicted
            val leftTile = RectF(x0, y, x0 + halfTileW, y + tileHeight)
            val rightTile = RectF(x0 + halfTileW + 20f, y, xRight, y + tileHeight)
            paintFill.color = tileColor
            canvas.drawRoundRect(leftTile, TILE_RADIUS, TILE_RADIUS, paintFill)
            canvas.drawRoundRect(rightTile, TILE_RADIUS, TILE_RADIUS, paintFill)

            canvas.drawText("驱动报告协议", leftTile.left + 20f, leftTile.top + 38f, pLabel)
            canvas.save()
            canvas.translate(leftTile.left + 20f, leftTile.top + 54f)
            driverLayout.draw(canvas)
            canvas.restore()

            canvas.drawText("预测当前协议", rightTile.left + 20f, rightTile.top + 38f, pLabel)
            canvas.save()
            canvas.translate(rightTile.left + 20f, rightTile.top + 54f)
            predLayout.draw(canvas)
            canvas.restore()

            y += tileHeight + 32f

            // Basic Info section
            canvas.drawLine(x0, y, xRight, y, paintStroke)
            y += 28f
            canvas.drawText("基本信息", x0, y + 20f, pSection)
            y += 40f

            val pValRight = textPaint(26f, textPrimary, bold = false).apply { textAlign = Paint.Align.RIGHT }
            for ((k, v) in basicRows) {
                canvas.drawText(k, x0, y + 26f, pLabel)
                canvas.drawText(v, xRight, y + 26f, pValRight)
                y += 50f
            }

            // Analysis Summary
            if (descLayouts.isNotEmpty()) {
                y += 10f
                canvas.drawLine(x0, y, xRight, y, paintStroke)
                y += 28f
                canvas.drawText("分析摘要", x0, y + 20f, pSection)
                y += 38f
                for (dl in descLayouts) {
                    canvas.save()
                    canvas.translate(x0, y)
                    dl.draw(canvas)
                    canvas.restore()
                    y += dl.height + 14f
                }
            }

            // Protocol Spec box
            if (specLayout != null) {
                y += 14f
                val specRect = RectF(x0, y, xRight, y + specLayout.height + 58f)
                paintFill.color = tileColor
                canvas.drawRoundRect(specRect, 20f, 20f, paintFill)
                canvas.drawText("协议规格参考（上限值）", specRect.left + 20f, specRect.top + 32f, pSection)
                canvas.save()
                canvas.translate(specRect.left + 20f, specRect.top + 46f)
                specLayout.draw(canvas)
                canvas.restore()
                y = specRect.bottom + 10f
            }

            // Key Evidence
            if (evLayouts.isNotEmpty()) {
                y += 14f
                canvas.drawLine(x0, y, xRight, y, paintStroke)
                y += 28f
                canvas.drawText("关键证据", x0, y + 20f, pSection)
                y += 38f
                for (el in evLayouts) {
                    canvas.save()
                    canvas.translate(x0, y)
                    el.draw(canvas)
                    canvas.restore()
                    y += el.height + 10f
                }
            }

            // Footer
            y += 20f
            canvas.drawLine(x0, y, xRight, y, paintStroke)
            y += 34f
            canvas.drawText(
                "充了个电 v${BuildConfig.VERSION_NAME} · 本地离线生成 · 不含设备唯一标识",
                x0,
                y,
                pSmall,
            )

            return bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        } finally {
            bmp.recycle()
        }
    }
}
