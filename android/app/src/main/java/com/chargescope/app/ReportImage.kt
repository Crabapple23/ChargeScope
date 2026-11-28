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

    private data class ReportSpec(
        val timestampMs: Long,
        val statusKind: Status,
        val statusLabel: String,
        val note: String?,
        val headline: String,
        val leftTile: Pair<String, String>,
        val rightTile: Pair<String, String>,
        val basicRows: List<Pair<String, String>>,
        val descLines: List<String>,
        val specTitle: String,
        val specText: String?,
        val evidenceLines: List<String>,
    )

    fun exportPng(ctx: Context, ui: UiState, dualCell: Boolean, isDark: Boolean, out: OutputStream): Boolean {
        val a = ui.analysis ?: return false
        val s = ui.latest

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

        val spec = ReportSpec(
            timestampMs = System.currentTimeMillis(),
            statusKind = a.status,
            statusLabel = a.status.label,
            note = null,
            headline = a.headline,
            leftTile = "驱动报告协议" to (a.driver ?: "未报告"),
            rightTile = "预测当前协议" to (a.predicted ?: "无法预测"),
            basicRows = basicRows,
            descLines = a.description.lines().filter { it.isNotBlank() },
            specTitle = "协议规格参考（上限值）",
            specText = a.protocolSpec,
            evidenceLines = a.evidence.sortedByDescending { it.level }.take(4).map { "L${it.level}  ${it.text}" },
        )
        return renderPng(spec, isDark, out)
    }

    fun exportArchivePng(entry: ArchiveEntry, isDark: Boolean, out: OutputStream): Boolean {
        val statusKind = Status.entries.firstOrNull { it.label == entry.status } ?: Status.UNKNOWN
        val basicRows = listOf(
            "设备" to entry.device,
            "记录电量" to (entry.levelPct?.let { "$it%" } ?: "—"),
            "输入电压峰值" to (fmt(entry.maxVinV, "V", 1) ?: "—"),
        )
        val descLines = buildList {
            addAll(entry.description.lines().filter { it.isNotBlank() })
            if (entry.candidates.isNotEmpty()) {
                add("候选协议：${entry.candidates.joinToString("；")}")
            }
        }
        val spec = ReportSpec(
            timestampMs = entry.wallMs,
            statusKind = statusKind,
            statusLabel = entry.status,
            note = entry.note.trim().ifEmpty { null },
            headline = entry.headline,
            leftTile = "输入功率峰值" to (fmt(entry.maxInputW, "W", 1) ?: "—"),
            rightTile = "电池侧功率峰值" to (fmt(entry.maxBatteryW, "W", 1) ?: "—"),
            basicRows = basicRows,
            descLines = descLines,
            specTitle = "档位说明",
            specText = entry.specText.ifBlank { null },
            evidenceLines = entry.evidence.take(6),
        )
        return renderPng(spec, isDark, out)
    }

    private fun renderPng(spec: ReportSpec, isDark: Boolean, out: OutputStream): Boolean {
        // Palette
        val bgColor = if (isDark) Color.parseColor("#0E0E10") else Color.parseColor("#F4F6FA")
        val cardColor = if (isDark) Color.parseColor("#1C1C1E") else Color.WHITE
        val tileColor = if (isDark) Color.parseColor("#26262A") else Color.parseColor("#F2F4F8")
        val dividerColor = if (isDark) Color.parseColor("#2E2E33") else Color.parseColor("#E6E8EE")
        val textPrimary = if (isDark) Color.parseColor("#F2F2F7") else Color.parseColor("#111114")
        val textSecondary = if (isDark) Color.parseColor("#9A9AA4") else Color.parseColor("#666873")
        val accentBlue = if (isDark) Color.parseColor("#4FC3F7") else Color.parseColor("#1976D2")

        val (badgeFg, badgeBg) = when (spec.statusKind) {
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
        val pNote = textPaint(30f, accentBlue, bold = true)
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
        val noteLayout = spec.note?.let { layoutText(it, pNote, contentWidth) }
        val headlineLayout = layoutText(spec.headline, pHeadline, contentWidth)
        val leftLayout = layoutText(spec.leftTile.second, pValue, halfTileW - 40)
        val rightLayout = layoutText(spec.rightTile.second, pValue, halfTileW - 40)
        val tileHeight = (maxOf(leftLayout.height, rightLayout.height) + 74).toFloat()

        val descLayouts = spec.descLines.map { layoutText(it, pBody, contentWidth) }
        val specLayout = spec.specText?.let { layoutText(it, pSmall, contentWidth - 40) }
        val evLayouts = spec.evidenceLines.map { layoutText(it, pSmall, contentWidth) }

        // Measure total height
        var h = OUTER_PAD + CARD_PAD
        h += 80f // header row
        if (noteLayout != null) {
            h += 14f + noteLayout.height
        }
        h += 28f + headlineLayout.height
        h += 32f + tileHeight
        h += 68f + spec.basicRows.size * 50f // basic info section
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
            canvas.drawText(AppLog.time(spec.timestampMs), x0, y + 58f, pTime)

            val badgeText = spec.statusLabel
            val badgeW = pBadge.measureText(badgeText) + 44f
            val badgeRect = RectF(xRight - badgeW, y + 4f, xRight, y + 54f)
            paintFill.color = badgeBg
            canvas.drawRoundRect(badgeRect, 25f, 25f, paintFill)
            canvas.drawText(badgeText, badgeRect.left + 22f, badgeRect.top + 35f, pBadge)

            y += 80f

            // Optional user note (for archives)
            if (noteLayout != null) {
                canvas.save()
                canvas.translate(x0, y)
                noteLayout.draw(canvas)
                canvas.restore()
                y += noteLayout.height + 14f
            }

            // Headline
            canvas.save()
            canvas.translate(x0, y)
            headlineLayout.draw(canvas)
            canvas.restore()
            y += headlineLayout.height + 28f

            // Two metric tiles
            val leftTile = RectF(x0, y, x0 + halfTileW, y + tileHeight)
            val rightTile = RectF(x0 + halfTileW + 20f, y, xRight, y + tileHeight)
            paintFill.color = tileColor
            canvas.drawRoundRect(leftTile, TILE_RADIUS, TILE_RADIUS, paintFill)
            canvas.drawRoundRect(rightTile, TILE_RADIUS, TILE_RADIUS, paintFill)

            canvas.drawText(spec.leftTile.first, leftTile.left + 20f, leftTile.top + 38f, pLabel)
            canvas.save()
            canvas.translate(leftTile.left + 20f, leftTile.top + 54f)
            leftLayout.draw(canvas)
            canvas.restore()

            canvas.drawText(spec.rightTile.first, rightTile.left + 20f, rightTile.top + 38f, pLabel)
            canvas.save()
            canvas.translate(rightTile.left + 20f, rightTile.top + 54f)
            rightLayout.draw(canvas)
            canvas.restore()

            y += tileHeight + 32f

            // Basic Info section
            canvas.drawLine(x0, y, xRight, y, paintStroke)
            y += 28f
            canvas.drawText("基本信息", x0, y + 20f, pSection)
            y += 40f

            val pValRight = textPaint(26f, textPrimary, bold = false).apply { textAlign = Paint.Align.RIGHT }
            for ((k, v) in spec.basicRows) {
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
                canvas.drawText(spec.specTitle, specRect.left + 20f, specRect.top + 32f, pSection)
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
