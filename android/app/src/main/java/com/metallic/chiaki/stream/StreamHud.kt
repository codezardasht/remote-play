// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.content.Context
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import androidx.core.content.ContextCompat
import com.metallic.chiaki.R
import com.metallic.chiaki.lib.StreamStats
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The in-stream stats line, same as the desktop HUD (gui/src/qml/StreamView.qml):
 * "● Stable  60 FPS  1080p  0.0% loss  14.8 Mbps  12 ms  Local", plus a second line
 * naming the most serious current problem and its likely cause.
 * Call [tick] once per second with fresh stats.
 */
class StreamHud(private val context: Context)
{
	private val good = ContextCompat.getColor(context, R.color.hud_good)
	private val warn = ContextCompat.getColor(context, R.color.hud_warn)
	private val bad = ContextCompat.getColor(context, R.color.hud_bad)
	private val dim = ContextCompat.getColor(context, R.color.ps_text_dim)

	var remote = false

	// Packet loss is measured over 200 ms windows; average the last second like the desktop does
	private val lossSamples = ArrayDeque<Double>()
	fun sampleLoss(loss: Double)
	{
		lossSamples.addLast(loss)
		while(lossSamples.size > 5)
			lossSamples.removeFirst()
	}

	private var lastFrames = -1L
	private var lastTickMs = 0L
	private var lastFramesLost = -1L
	private var peakFps = 0
	private var warmupTicks = 3
	private var holdTicks = 0
	private var stability = 0
	private var issueTitle = ""
	private var issueReason = ""

	fun reset()
	{
		lastFrames = -1L; lastTickMs = 0L; lastFramesLost = -1L; peakFps = 0; warmupTicks = 3; holdTicks = 0
		stability = 0; issueTitle = ""; issueReason = ""
		lossSamples.clear()
	}

	private fun levelColor(level: Int) = when(level) { 0 -> good; 1 -> warn; else -> bad }

	private fun resolutionName(w: Int, h: Int) = when
	{
		h <= 0 -> ""
		h in 1070..1090 -> "1080p"
		h in 710..730 -> "720p"
		h in 530..550 -> "540p"
		h in 350..370 -> "360p"
		else -> "${w}×$h"
	}

	/** Returns the text to show, given stats sampled one second after the previous call */
	fun tick(stats: StreamStats): CharSequence
	{
		// Divide by the real time since the last tick: the timer can fire late when the app is busy
		val now = android.os.SystemClock.elapsedRealtime()
		val elapsed = now - lastTickMs
		val fps = if(lastFrames >= 0 && elapsed > 0) (max(0L, stats.framesShown - lastFrames) * 1000.0 / elapsed).roundToInt() else 0
		lastFrames = stats.framesShown
		lastTickMs = now
		val lostDelta = if(lastFramesLost >= 0) max(0L, stats.framesLost - lastFramesLost).toInt() else 0
		lastFramesLost = stats.framesLost
		val loss = (if(lossSamples.isEmpty()) stats.packetLoss else lossSamples.average()) * 100.0
		evaluate(fps, loss, lostDelta, stats.framesShown > 0)

		val sb = SpannableStringBuilder()
		fun add(text: String, color: Int, bold: Boolean = false, size: Float = 1f)
		{
			val start = sb.length
			sb.append(text)
			sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
			if(bold)
				sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
			if(size != 1f)
				sb.setSpan(RelativeSizeSpan(size), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
		}
		fun item(value: String, unit: String, color: Int)
		{
			sb.append("   ")
			add(value, color, bold = true)
			add(" $unit", dim)
		}

		add("● ", levelColor(stability))
		add(context.getString(when(stability) { 0 -> R.string.hud_stable; 1 -> R.string.hud_unstable; else -> R.string.hud_lag }), levelColor(stability), bold = true)
		if(stats.framesShown > 0)
			item(fps.toString(), "FPS", levelColor(if(fps >= 55) 0 else if(fps >= 40) 1 else 2))
		resolutionName(stats.width, stats.height).takeIf { it.isNotEmpty() }?.let {
			sb.append("   ")
			add(it, Color.WHITE, bold = true)
		}
		item(String.format("%.1f%%", loss), context.getString(R.string.hud_loss), levelColor(if(loss < 1) 0 else if(loss < 5) 1 else 2))
		if(stats.bitrateMbps > 0)
			item(String.format("%.1f", stats.bitrateMbps), "Mbps", Color.WHITE)
		if(stats.pingMs > 0)
			item(stats.pingMs.toString(), "ms", levelColor(if(stats.pingMs <= 30) 0 else if(stats.pingMs <= 80) 1 else 2))
		sb.append("   ")
		add(context.getString(if(remote) R.string.hud_remote else R.string.hud_local), if(remote) Color.rgb(0xff, 0xcc, 0x66) else Color.rgb(0x7c, 0xc4, 0xff), bold = true)

		if(stability > 0)
		{
			sb.append("\n")
			add(issueTitle, levelColor(stability), bold = true, size = 0.93f)
			add("  $issueReason", Color.rgb(0xe6, 0xee, 0xfb), size = 0.93f)
		}
		return sb
	}

	// Picks the most serious current problem and explains its likely cause (desktop: evaluateStability)
	private fun evaluate(fps: Int, loss: Double, lostDelta: Int, hasVideo: Boolean)
	{
		if(fps > peakFps)
			peakFps = fps
		if(warmupTicks > 0)
		{
			warmupTicks--
			return
		}
		val expectedFps = if(peakFps >= 50) 60 else if(peakFps >= 25) 30 else 0
		var sev = 0
		var title = ""
		var reason = ""
		fun pick(s: Int, t: Int, r: String)
		{
			if(s > sev) { sev = s; title = context.getString(t); reason = r }
		}

		if(hasVideo && fps == 0)
			pick(2, R.string.hud_issue_freeze, context.getString(R.string.hud_issue_freeze_reason))
		if(loss >= 8)
			pick(2, R.string.hud_issue_wifi_unstable, context.getString(R.string.hud_issue_wifi_unstable_reason, loss))
		else if(loss >= 3)
			pick(1, R.string.hud_issue_wifi_weak, context.getString(R.string.hud_issue_wifi_weak_reason, loss))
		if(lostDelta > 0)
			pick(if(lostDelta >= 5) 2 else 1, R.string.hud_issue_glitch, context.getString(R.string.hud_issue_glitch_reason, lostDelta))
		if(expectedFps > 0 && fps > 0 && fps < expectedFps * 0.8 && loss < 1)
			pick(1, R.string.hud_issue_low_fps, context.getString(R.string.hud_issue_low_fps_reason, fps, expectedFps))

		if(sev > 0)
		{
			// Show a problem for at least 4 s so it can be read; a worse one replaces a milder one at once
			if(sev >= stability || holdTicks <= 0)
			{
				stability = sev; issueTitle = title; issueReason = reason
			}
			holdTicks = 4
		}
		else if(holdTicks > 0)
			holdTicks--
		else
		{
			stability = 0; issueTitle = ""; issueReason = ""
		}
	}
}
