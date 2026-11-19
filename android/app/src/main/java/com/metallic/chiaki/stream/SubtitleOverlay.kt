// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.isVisible
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.databinding.ActivityStreamBinding
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Shows the translated subtitles like the desktop app (gui/src/qml/StreamView.qml): film-style white
 * text with a soft shadow, the size of the game's own line. "Clean" puts it just above the game's
 * English line; "Cover" puts it over the English line on a blurred patch of the scene around it.
 * The position is decided once per line and then stays put.
 */
class SubtitleOverlay(private val binding: ActivityStreamBinding, private val preferences: Preferences): SubtitleTranslator.Listener
{
	private val handler = Handler(Looper.getMainLooper())
	private val block = binding.subtitleBlock
	private val textView = binding.subtitleText
	private val originalView = binding.subtitleOriginal
	private val cover = binding.subtitleCover

	private var lockedBox: RectF? = null
	private var anchorBottom = -1f
	private var shown = false
	private var shownAt = 0L
	private var complete = true
	private var coverBox: RectF? = null
	private val coverStyle get() = preferences.subtitlesCoverStyle

	private val hideRunnable = Runnable { setShown(false) }
	private val anchorResetRunnable = Runnable { anchorBottom = -1f }

	/** Where the video is drawn inside the subtitle layer */
	private fun videoRect(): RectF
	{
		val sv = binding.surfaceView
		val a = IntArray(2)
		val b = IntArray(2)
		sv.getLocationInWindow(a)
		binding.subtitleLayer.getLocationInWindow(b)
		val x = (a[0] - b[0]).toFloat()
		val y = (a[1] - b[1]).toFloat()
		return RectF(x, y, x + sv.width, y + sv.height)
	}

	override fun onSubtitle(text: String, original: String, complete: Boolean, box: RectF?, newLine: Boolean)
	{
		this.complete = complete
		textView.text = text
		originalView.text = original
		originalView.isVisible = coverStyle && preferences.subtitlesShowOriginal && original.isNotEmpty()
		if(newLine)
		{
			lockedBox = box?.takeIf { it.width() > 0 }
			lockedBox?.let { b ->
				if(anchorBottom < 0 || b.top < anchorBottom || b.top - anchorBottom > 0.08f)
					anchorBottom = b.top
			}
			handler.removeCallbacks(anchorResetRunnable)
			setShown(true)
		}
		layoutBlock()
		setHideTime()
	}

	override fun onSourceCleared()
	{
		// The game's line went away: our translation follows soon after, but not before it could be read
		if(!shown || !complete) return
		val elapsed = SystemClock.elapsedRealtime() - shownAt
		val left = max(0L, 1500L - elapsed)
		handler.removeCallbacks(hideRunnable)
		handler.postDelayed(hideRunnable, left)
	}

	override fun onCover(patch: Bitmap?, box: RectF?)
	{
		coverBox = box
		if(patch == null || box == null || !coverStyle)
		{
			cover.isVisible = false
			cover.setImageDrawable(null)
		}
		else
		{
			cover.setImageBitmap(patch) // a tiny bitmap scaled up with filtering looks like a blur
			layoutCover()
			cover.isVisible = shown
		}
		layoutBlock()
	}

	override fun onStatus(text: String?, error: Boolean)
	{
		binding.subtitleStatus.text = text ?: ""
		binding.subtitleStatus.isVisible = error && !text.isNullOrEmpty()
	}

	override fun onCleared()
	{
		setShown(false)
		cover.isVisible = false
	}

	private fun setShown(show: Boolean)
	{
		shown = show
		if(show)
			shownAt = SystemClock.elapsedRealtime()
		else
		{
			handler.postDelayed(anchorResetRunnable, 6000) // a new scene may put its subtitles elsewhere
			cover.isVisible = false
		}
		block.animate().alpha(if(show) 1f else 0f).setDuration(140).start()
	}

	private fun setHideTime()
	{
		handler.removeCallbacks(hideRunnable)
		val length = textView.text.length
		val ms = if(!complete) 10_000L else (2200L + length * 80L).coerceIn(3000L, 9000L)
		handler.postDelayed(hideRunnable, ms)
	}

	private fun layoutCover()
	{
		val b = coverBox ?: return
		val v = videoRect()
		val lp = cover.layoutParams as FrameLayout.LayoutParams
		lp.width = (b.width() * v.width()).roundToInt()
		lp.height = (b.height() * v.height()).roundToInt()
		lp.leftMargin = (v.left + b.left * v.width()).roundToInt()
		lp.topMargin = (v.top + b.top * v.height()).roundToInt()
		lp.gravity = Gravity.TOP or Gravity.START
		cover.layoutParams = lp
	}

	private fun layoutBlock()
	{
		val v = videoRect()
		if(v.width() <= 0) return
		// Film-subtitle size: about the same as the game's own subtitles
		val fontPx = (v.height() * 0.028f).coerceIn(14f, 40f)
		textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontPx)
		originalView.setTextSize(TypedValue.COMPLEX_UNIT_PX, max(11f, fontPx * 0.6f))
		val maxTextWidth = (v.width() * 0.80f).roundToInt()
		textView.maxWidth = maxTextWidth
		originalView.maxWidth = maxTextWidth

		val source = lockedBox?.let { b -> RectF(v.left + b.left * v.width(), v.top + b.top * v.height(), v.left + b.right * v.width(), v.top + b.bottom * v.height()) }
		val coverMode = coverStyle && source != null

		// Cover without a scene patch: a slim dark strip
		block.background = if(coverStyle && !cover.isVisible)
			GradientDrawable().also { it.setColor(Color.argb(209, 5, 5, 8)); it.cornerRadius = 10f }
		else null

		block.measure(View.MeasureSpec.makeMeasureSpec(maxTextWidth + block.paddingLeft + block.paddingRight, View.MeasureSpec.AT_MOST),
			View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
		var w = block.measuredWidth
		var h = block.measuredHeight
		if(coverMode && source != null)
		{
			w = max(w, source.width().roundToInt() + 16)
			h = max(h, source.height().roundToInt() + 8)
		}
		w = min(w, (v.width() * 0.9f).roundToInt())

		val cx = if(coverMode && source != null) source.centerX() else v.centerX()
		val top = when
		{
			coverMode && source != null -> source.centerY() - h / 2f
			anchorBottom >= 0 -> v.top + anchorBottom * v.height() - 4 - h // ends just above the English line
			else -> v.top + v.height() * 0.80f - h
		}
		val lp = block.layoutParams as FrameLayout.LayoutParams
		lp.width = w
		lp.height = h
		lp.gravity = Gravity.TOP or Gravity.START
		lp.leftMargin = (cx - w / 2f).roundToInt().coerceIn(v.left.roundToInt(), max(v.left.roundToInt(), (v.right - w).roundToInt()))
		lp.topMargin = top.roundToInt().coerceAtLeast(v.top.roundToInt())
		block.layoutParams = lp
		if(abs(block.alpha) > 0.01f && coverStyle && coverBox != null)
			cover.isVisible = true
	}

	fun release()
	{
		handler.removeCallbacksAndMessages(null)
	}
}
