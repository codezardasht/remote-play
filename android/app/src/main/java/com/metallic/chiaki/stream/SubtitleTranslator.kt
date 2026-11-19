// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.SharedMemory
import java.nio.ByteBuffer
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceView
import com.metallic.chiaki.R
import com.metallic.chiaki.common.Preferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Live subtitle translation with Claude, ported from the desktop app (gui/src/subtitletranslator.cpp).
 *
 * A capture thread copies the subtitle area (bottom 30 %, middle 68 % of the picture) out of the
 * SurfaceView with PixelCopy, one copy at a time, and checks on the device whether new subtitle text
 * appeared and stopped changing. Only then is that small greyscale image sent to the Claude API,
 * which reads the line and translates it into Kurdish Sorani or Arabic. Gameplay without subtitles
 * costs nothing but the copies.
 *
 * All translation state lives on the main thread; capture and network run on their own threads.
 */
class SubtitleTranslator(
	private val context: Context,
	private val surfaceView: SurfaceView,
	private val listener: Listener)
{
	interface Listener
	{
		/** A line (new or growing). box: where the game drew it, as fractions of the video, or null */
		fun onSubtitle(text: String, original: String, complete: Boolean, box: RectF?, newLine: Boolean)
		/** The game's own subtitle went away */
		fun onSourceCleared()
		/** Cover style: a tiny picture of the scene around the game's line, and where it goes (null = none) */
		fun onCover(patch: Bitmap?, box: RectF?)
		fun onStatus(text: String?, error: Boolean)
		fun onCleared()
	}

	private data class Config(
		val enabled: Boolean, val apiKey: String, val model: String, val language: String,
		val instant: Boolean, val cover: Boolean)

	companion object
	{
		private const val TAG = "Subtitles"
		private const val GRAB_INTERVAL_MS = 120L     // the desktop looks 8 times a second; PixelCopy may be slower
		private const val MIN_SEND_GAP_MS = 250L
		private const val MAX_IN_FLIGHT = 2
		private const val BACKUP_AFTER_MS = 900L
		private const val KEEP_ALIVE_MS = 20_000L
		private const val CELL = 2
		private const val MIN_INK_CELLS = 120
		private const val STABLE_IOU = 0.70
		private const val NEW_IOU = 0.55
		private const val MAX_IMAGE_WIDTH = 1568
		private const val RECENT_LINES = 4
		private const val AREA_LEFT = 0.16f
		private const val AREA_RIGHT = 0.84f
		private const val AREA_TOP = 0.70f
		private const val API_URL = "https://api.anthropic.com/v1/messages"
		private const val API_VERSION = "2026-10-21"

		fun languageName(code: String) = if(code == "ar") "Arabic" else "Kurdish Sorani"

		fun modelLabel(model: String) = when
		{
			model.startsWith("claude-haiku") -> "Claude Haiku 5.5"
			model.startsWith("claude-sonnet") -> "Claude Sonnet 5.5"
			model.startsWith("claude-opus") -> "Claude Opus 5.5"
			else -> model
		}

		private fun systemPrompt(language: String): String
		{
			val langRules = if(language == "ar")
				"- Write clear Modern Standard Arabic with simple, everyday vocabulary and no diacritics.\n" +
				"- Write names of people and places in Arabic script as they are pronounced.\n"
			else
				"- Write Central Kurdish (Sorani) in the Kurdish Arabic-based alphabet, using its own letters " +
				"(ە ێ ۆ ڕ ڵ ڤ گ چ پ ژ) and spelling. Not Kurmanji, not Latin script, and use common Sorani words " +
				"rather than Persian or Arabic ones where a Sorani word exists.\n" +
				"- Write names of people and places in Sorani script as they are pronounced " +
				"(for example Franklin → فرانکلین, Los Santos → لۆس سانتۆس).\n"
			val lang = languageName(language)
			return "You read subtitles from video game pictures and translate them into $lang for the player.\n\n" +
				"The image is a greyscale cut-out around text near the bottom of one frame of a PlayStation game. It may contain a subtitle " +
				"(spoken dialogue, often starting with the speaker's name and a colon) and/or an on-screen mission or " +
				"objective text. It may also contain parts of the game world, the HUD, a minimap, button prompts, money, " +
				"ammo or score counters: those are not subtitles.\n\n" +
				"Answer in plain text, nothing else:\n" +
				"Line 1: the subtitle or objective text translated into $lang.\n" +
				"Line 2: the same text in the original language exactly as written, joined into one line.\n" +
				"If there is no readable subtitle or objective text, or it is too cut off to understand, answer only: NONE\n\n" +
				"Rules:\n" +
				"- Leave out HUD numbers, button prompts and text that belongs to the game world (signs, posters, number plates).\n" +
				"- Leave out a speaker name at the start of the line (for example \"FRANKLIN:\").\n" +
				"- Use a natural spoken style the player understands at a glance, about as short as the original. Keep the " +
				"meaning, tone and attitude of slang and jokes instead of translating word by word. Swearing may stay, but " +
				"don't make it stronger.\n" +
				langRules
		}

		// Haiku 5.5 may run without thinking (fastest); Sonnet and Opus keep adaptive thinking at low effort
		private fun addSpeedSettings(body: JSONObject, model: String)
		{
			body.put("output_config", JSONObject().put("effort", "low"))
			if(model.startsWith("claude-haiku"))
				body.put("thinking", JSONObject().put("type", "disabled"))
		}

		private fun iou(a: ByteArray?, b: ByteArray?): Double
		{
			if(a == null || b == null || a.size != b.size || a.isEmpty())
				return 0.0
			var inter = 0
			var uni = 0
			for(i in a.indices)
			{
				val x = a[i].toInt()
				val y = b[i].toInt()
				inter += x and y
				uni += x or y
			}
			return if(uni > 0) inter.toDouble() / uni else 1.0
		}

		private val lineLabel = Regex("^(line\\s*\\d\\s*:\\s*)", RegexOption.IGNORE_CASE)
		private fun cleanLine(line: String): String
		{
			var l = line.trim().replace(lineLabel, "")
			if(l.startsWith("\"")) l = l.substring(1)
			if(l.endsWith("\"")) l = l.dropLast(1)
			return l.trim()
		}

		private fun isNone(answer: String) = answer.trim().startsWith("NONE")
	}

	private val main = Handler(Looper.getMainLooper())
	private val captureThread = HandlerThread("SubtitleCapture").also { it.start() }
	private val capture = Handler(captureThread.looper)
	private val network = Executors.newCachedThreadPool()

	@Volatile private var config = readConfig()
	@Volatile private var running = false   // stream connected and subtitles on
	@Volatile private var copyBusy = false
	@Volatile private var copyStartMs = 0L

	// The copy runs in CaptureService's own process so its long wait can't stall this app's UI and input
	@Volatile private var service: Messenger? = null
	private var serviceBound = false
	private var serviceFailed = false
	private var sharedMemory: SharedMemory? = null  // capture thread only
	private var sharedBuffer: ByteBuffer? = null
	private var memorySent = false
	private var serviceRequest: Triple<Bitmap, Rect, Pair<Int, Int>>? = null
	private val replies by lazy { Messenger(Handler(captureThread.looper) { msg ->
		if(msg.what == CaptureService.MSG_COPY_DONE)
			onServiceCopy(msg.arg1)
		true
	}) }
	private val connection = object: ServiceConnection {
		override fun onServiceConnected(name: ComponentName, binder: IBinder)
		{
			capture.post { memorySent = false; copyBusy = false }
			service = Messenger(binder)
			Log.i(TAG, "Capture process connected")
		}

		override fun onServiceDisconnected(name: ComponentName)
		{
			service = null
			capture.post { copyBusy = false }
			Log.w(TAG, "Capture process went away")
		}
	}

	init
	{
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1)
		{
			serviceBound = try { context.bindService(Intent(context, CaptureService::class.java), connection, Context.BIND_AUTO_CREATE) }
				catch(e: Exception) { false }
		}
		serviceFailed = !serviceBound
		if(serviceFailed)
			Log.w(TAG, "Capture process not available, copying in the app (may delay input on some TVs)")
	}

	// Capture thread only
	private var prevMask: ByteArray? = null
	private var sentMask: ByteArray? = null
	private var emptySamples = 0
	private var lastLiveBox: RectF? = null
	private var lastSendMs = -100_000L
	private var captureBitmap: Bitmap? = null

	// Main thread only
	private var inFlight = 0
	private var nextSeq = 0
	private var shownSeq = 0
	private var pending: Triple<ByteArray, RectF, Long>? = null
	private val recentLines = ArrayDeque<String>()
	private var currentText = ""
	private var currentComplete = true
	private var currentOriginal = ""
	private var statusText: String? = null
	private var statusError = false
	var costUsd = 0.0
		private set
	var requestCount = 0
		private set

	private fun readConfig(): Config
	{
		val p = Preferences(context)
		return Config(p.subtitlesEnabled, p.claudeApiKey, p.subtitlesModel, p.subtitlesLanguage, p.subtitlesInstant, p.subtitlesCoverStyle)
	}

	/** Main thread: call when the stream connects/disconnects and after settings may have changed */
	fun refresh(sessionConnected: Boolean)
	{
		val c = readConfig()
		config = c
		val nowActive = c.enabled && sessionConnected && c.apiKey.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
		if(nowActive != running)
		{
			running = nowActive
			if(nowActive)
			{
				warmConnection()
				main.postDelayed(keepAlive, KEEP_ALIVE_MS)
				capture.post { prevMask = null; sentMask = null; emptySamples = 0; lastLiveBox = null }
				capture.post(captureLoop)
			}
			else
			{
				main.removeCallbacks(keepAlive)
				capture.removeCallbacks(captureLoop)
				pending = null
				recentLines.clear()
				shownSeq = nextSeq // answers still on the way are dropped
				clearSubtitle()
				listener.onCover(null, null)
			}
		}
		val missingKey = context.getString(R.string.subtitles_missing_key)
		if(c.enabled && sessionConnected && c.apiKey.isEmpty())
			setStatus(missingKey, true)
		else if(statusError && (!c.enabled || statusText == missingKey))
			setStatus(null, false)
	}

	fun release()
	{
		running = false
		main.removeCallbacksAndMessages(null)
		capture.removeCallbacksAndMessages(null)
		captureThread.quitSafely()
		network.shutdownNow()
		if(serviceBound)
		{
			try { context.unbindService(connection) } catch(e: Exception) { }
			serviceBound = false
		}
		service = null
	}

	private val keepAlive = object: Runnable {
		override fun run()
		{
			if(!running) return
			warmConnection()
			main.postDelayed(this, KEEP_ALIVE_MS)
		}
	}

	// Open the TLS connection to Claude now (HttpURLConnection keeps it for reuse), so a line doesn't wait for it
	private fun warmConnection()
	{
		network.execute {
			try
			{
				val conn = URL("https://api.anthropic.com/").openConnection() as HttpURLConnection
				conn.connectTimeout = 5000
				conn.readTimeout = 5000
				conn.responseCode
				conn.inputStream.use { it.readBytes() }
			}
			catch(e: Exception) { /* 404 or offline: fine, the connection is what counts */ }
		}
	}

	// ---- Capture thread ----

	private val captureLoop = object: Runnable {
		override fun run()
		{
			if(!running) return
			// Don't wait forever for an answer that may never come (capture process restarted)
			if(copyBusy && SystemClock.elapsedRealtime() - copyStartMs > 4000)
				copyBusy = false
			if(!copyBusy)
				grab()
			capture.postDelayed(this, GRAB_INTERVAL_MS)
		}
	}

	private fun grab()
	{
		if(Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
		val holder = surfaceView.holder
		val frame = holder.surfaceFrame
		val w = frame.width()
		val h = frame.height()
		if(w < 64 || h < 64 || !holder.surface.isValid) return
		val src = Rect((w * AREA_LEFT).toInt(), (h * AREA_TOP).toInt(), (w * AREA_RIGHT).toInt(), h)
		// Copy at about the stream's own resolution (720p / 1080p): enough to read, less to process
		val scale = min(1f, 1100f / w)
		val bw = max(64, (src.width() * scale).toInt())
		val bh = max(32, (src.height() * scale).toInt())
		val bmp = captureBitmap?.takeIf { it.width == bw && it.height == bh }
			?: Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888).also { captureBitmap = it }
		if(!serviceFailed)
		{
			val messenger = service ?: return // still connecting
			copyBusy = true
			copyStartMs = SystemClock.elapsedRealtime()
			try
			{
				val bytes = bw * bh * 4
				val data = Bundle()
				if(sharedBuffer == null || sharedBuffer!!.capacity() < bytes || !memorySent)
				{
					if(sharedBuffer == null || sharedBuffer!!.capacity() < bytes)
					{
						sharedBuffer?.let { SharedMemory.unmap(it) }
						sharedMemory?.close()
						val m = SharedMemory.create("subtitle-capture", bytes)
						sharedMemory = m
						sharedBuffer = m.mapReadOnly()
					}
					data.putParcelable(CaptureService.KEY_MEMORY, sharedMemory)
					memorySent = true
				}
				data.putParcelable(CaptureService.KEY_SURFACE, holder.surface)
				data.putParcelable(CaptureService.KEY_RECT, src)
				data.putInt(CaptureService.KEY_WIDTH, bw)
				data.putInt(CaptureService.KEY_HEIGHT, bh)
				serviceRequest = Triple(bmp, src, Pair(w, h))
				val msg = Message.obtain(null, CaptureService.MSG_COPY)
				msg.data = data
				msg.replyTo = replies
				messenger.send(msg)
			}
			catch(e: Exception)
			{
				Log.w(TAG, "Asking the capture process failed: $e")
				memorySent = false
				copyBusy = false
			}
			return
		}
		copyBusy = true
		copyStartMs = SystemClock.elapsedRealtime()
		try
		{
			PixelCopy.request(surfaceView, src, bmp, { result ->
				// Listener runs on the capture thread
				try
				{
					if(result == PixelCopy.SUCCESS && running)
						processFrame(bmp, w, h, src)
				}
				catch(e: Exception)
				{
					Log.e(TAG, "Processing a frame failed", e)
				}
				finally
				{
					copyBusy = false
				}
			}, capture)
		}
		catch(e: IllegalArgumentException)
		{
			copyBusy = false // surface went away
		}
	}

	// Capture thread: the capture process has written the pixels into the shared memory
	private fun onServiceCopy(result: Int)
	{
		val req = serviceRequest
		serviceRequest = null
		try
		{
			val buf = sharedBuffer
			if(req != null && buf != null && result == PixelCopy.SUCCESS && running)
			{
				val (bmp, src, size) = req
				buf.position(0)
				bmp.copyPixelsFromBuffer(buf)
				processFrame(bmp, size.first, size.second, src)
			}
		}
		catch(e: Exception)
		{
			Log.e(TAG, "Processing a frame failed", e)
		}
		finally
		{
			copyBusy = false
		}
	}

	private fun processFrame(bmp: Bitmap, surfW: Int, surfH: Int, src: Rect)
	{
		val cw = bmp.width
		val ch = bmp.height
		val pixels = IntArray(cw * ch)
		bmp.getPixels(pixels, 0, cw, 0, 0, cw, ch)
		val area = ByteArray(cw * ch)
		for(i in pixels.indices)
		{
			val p = pixels[i]
			area[i] = ((((p shr 16) and 0xff) * 77 + ((p shr 8) and 0xff) * 150 + (p and 0xff) * 29) shr 8).toByte()
		}
		fun lum(x: Int, y: Int) = area[y * cw + x].toInt() and 0xff

		// Text = bright pixels next to a dark outline or shadow. Mark cells that contain such pixels.
		val mw = (cw + CELL - 1) / CELL
		val mh = (ch + CELL - 1) / CELL
		val mask = ByteArray(mw * mh)
		var ink = 0
		for(y in 0 until ch)
		{
			val row = y * cw
			for(x in 0 until cw)
			{
				if((area[row + x].toInt() and 0xff) < 190) continue
				val ci = (y / CELL) * mw + x / CELL
				if(mask[ci].toInt() != 0) continue
				var dark = false
				var d = 1
				while(d <= 3 && !dark)
				{
					if((x - d >= 0 && lum(x - d, y) <= 80) || (x + d < cw && lum(x + d, y) <= 80))
						dark = true
					else if((y - d >= 0 && lum(x, y - d) <= 80) || (y + d < ch && lum(x, y + d) <= 80))
						dark = true
					d++
				}
				if(dark)
				{
					mask[ci] = 1
					ink++
				}
			}
		}

		// Thresholds were tuned on the desktop at the stream's resolution; scale for this copy's size
		val minInk = max(40, (MIN_INK_CELLS * (cw / 870.0)).toInt())
		if(ink < minInk)
		{
			if(++emptySamples == 2)
			{
				sentMask = null
				lastLiveBox = null
				main.post { listener.onCover(null, null); listener.onSourceCleared() }
			}
			prevMask = mask
			return
		}
		emptySamples = 0

		var minCx = mw; var maxCx = -1; var minCy = mh; var maxCy = -1
		for(cy in 0 until mh) for(cx in 0 until mw)
		{
			if(mask[cy * mw + cx].toInt() == 0) continue
			if(cx < minCx) minCx = cx
			if(cx > maxCx) maxCx = cx
			if(cy < minCy) minCy = cy
			if(cy > maxCy) maxCy = cy
		}
		// Where the text is, as fractions of the whole picture
		val sx = src.width().toFloat() / cw
		val sy = src.height().toFloat() / ch
		val textBox = RectF(
			(src.left + minCx * CELL * sx) / surfW,
			(src.top + minCy * CELL * sy) / surfH,
			(src.left + (maxCx + 1) * CELL * sx) / surfW,
			(src.top + (maxCy + 1) * CELL * sy) / surfH)
		lastLiveBox = textBox

		if(config.cover)
		{
			val patch = scenePatch(pixels, area, cw, ch, minCx * CELL, minCy * CELL, (maxCx + 1) * CELL, (maxCy + 1) * CELL)
			if(patch != null)
			{
				val (bitmap, pr) = patch
				val patchBox = RectF(
					(src.left + pr.left * sx) / surfW, (src.top + pr.top * sy) / surfH,
					(src.left + pr.right * sx) / surfW, (src.top + pr.bottom * sy) / surfH)
				main.post { listener.onCover(bitmap, patchBox) }
			}
		}

		val iouPrev = iou(mask, prevMask)
		val iouSent = iou(mask, sentMask)
		val stable = iouPrev >= STABLE_IOU
		val isNew = iouSent < NEW_IOU
		val now = SystemClock.elapsedRealtime()
		prevMask = mask
		if(!(stable && isNew && now - lastSendMs >= MIN_SEND_GAP_MS))
			return
		Log.i(TAG, "new line seen: ink $ink, same as previous look ${(iouPrev * 100).toInt()}%, same as last sent ${(iouSent * 100).toInt()}%")
		sentMask = mask
		lastSendMs = now

		// Only the text and a margin around it: fewer image tokens = faster and cheaper.
		// Wide side margin: coloured words (names) are darker and may not count as "text" above.
		val padX = (140 * cw / 870.0).toInt()
		val padY = (18 * ch / 216.0).toInt()
		val bx0 = max(0, minCx * CELL - padX)
		val by0 = max(0, minCy * CELL - padY)
		val bx1 = min(cw, (maxCx + 1) * CELL + padX)
		val by1 = min(ch, (maxCy + 1) * CELL + padY)
		val iw = max(1, bx1 - bx0)
		val ih = max(1, by1 - by0)
		val grey = IntArray(iw * ih)
		for(y in 0 until ih) for(x in 0 until iw)
		{
			val v = lum(bx0 + x, by0 + y)
			grey[y * iw + x] = (0xff shl 24) or (v shl 16) or (v shl 8) or v
		}
		var image = Bitmap.createBitmap(grey, iw, ih, Bitmap.Config.ARGB_8888)
		if(image.width > MAX_IMAGE_WIDTH)
			image = Bitmap.createScaledBitmap(image, MAX_IMAGE_WIDTH, image.height * MAX_IMAGE_WIDTH / image.width, true)
		val out = ByteArrayOutputStream()
		image.compress(Bitmap.CompressFormat.JPEG, 85, out)
		val jpeg = out.toByteArray()
		main.post { sendImage(jpeg, textBox, now) }
	}

	/**
	 * Average colour of the scene around (and behind) the text, as a tiny bitmap. Pixels that look like
	 * the letters or their outline are left out, so the patch shows the scene, not the text.
	 */
	private fun scenePatch(pixels: IntArray, area: ByteArray, cw: Int, ch: Int, tx0: Int, ty0: Int, tx1: Int, ty1: Int): Pair<Bitmap, Rect>?
	{
		val padX = max(16, (ch / 0.30 * 0.03).toInt())
		val padY = max(10, (ch / 0.30 * 0.016).toInt())
		val region = Rect(max(0, tx0 - padX), max(0, ty0 - padY), min(cw, tx1 + padX), min(ch, ty1 + padY))
		if(region.width() < 8 || region.height() < 4) return null
		val pw = (region.width() / 24).coerceIn(8, 64)
		val ph = (region.height() / 12).coerceIn(3, 12)
		val sum = DoubleArray(pw * ph * 3)
		val count = IntArray(pw * ph)
		var y = region.top
		while(y < region.bottom)
		{
			val cy = min(ph - 1, (y - region.top) * ph / region.height())
			var x = region.left
			while(x < region.right)
			{
				val l = area[y * cw + x].toInt() and 0xff
				val inText = x in tx0 until tx1 && y in ty0 until ty1
				if(!(inText && (l >= 160 || l <= 70)))
				{
					val cx = min(pw - 1, (x - region.left) * pw / region.width())
					val i = cy * pw + cx
					val p = pixels[y * cw + x]
					sum[i * 3] += ((p shr 16) and 0xff).toDouble()
					sum[i * 3 + 1] += ((p shr 8) and 0xff).toDouble()
					sum[i * 3 + 2] += (p and 0xff).toDouble()
					count[i]++
				}
				x += 2
			}
			y += 2
		}
		val avg = DoubleArray(3)
		var filled = 0
		for(i in count.indices)
		{
			if(count[i] == 0) continue
			for(k in 0 until 3) avg[k] += sum[i * 3 + k] / count[i]
			filled++
		}
		if(filled == 0) return null
		for(k in 0 until 3) avg[k] = avg[k] / filled
		val colors = IntArray(pw * ph) { i ->
			fun c(k: Int) = (if(count[i] > 0) sum[i * 3 + k] / count[i] else avg[k]).toInt().coerceIn(0, 255)
			(0xff shl 24) or (c(0) shl 16) or (c(1) shl 8) or c(2)
		}
		return Bitmap.createBitmap(colors, pw, ph, Bitmap.Config.ARGB_8888) to region
	}

	// ---- Main thread: requests ----

	private fun sendImage(jpeg: ByteArray, box: RectF, seenMs: Long)
	{
		if(!running) return
		if(inFlight >= MAX_IN_FLIGHT)
		{
			pending = Triple(jpeg, box, seenMs) // keep only the newest line
			return
		}
		startRequest(jpeg, box, seenMs)
	}

	private inner class Request(val seq: Int, val box: RectF, val seenMs: Long, val body: ByteArray, val apiKey: String, val model: String)
	{
		val sentMs = SystemClock.elapsedRealtime()
		val connections = mutableListOf<HttpURLConnection>()
		var copies = 0
		var open = 0
		var winner: Int = -1
		var answer = ""
		var noSubtitle = false
		var lineComplete = false
		var done = false
		var inTokens = 0.0
		var outTokens = 0.0
	}

	private fun buildRequest(c: Config, jpeg: ByteArray): ByteArray
	{
		val content = JSONArray()
		if(recentLines.isNotEmpty())
			content.put(JSONObject().put("type", "text").put("text",
				"Earlier lines in this scene (use the same names and wording):\n- " + recentLines.joinToString("\n- ")))
		content.put(JSONObject().put("type", "image").put("source", JSONObject()
			.put("type", "base64").put("media_type", "image/jpeg")
			.put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))))
		content.put(JSONObject().put("type", "text").put("text", "Read and translate the subtitle in this image."))
		val body = JSONObject()
			.put("model", c.model)
			.put("max_tokens", 1024)
			.put("stream", true)
			.put("system", systemPrompt(c.language))
			.put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
		addSpeedSettings(body, c.model)
		return body.toString().toByteArray()
	}

	private fun startRequest(jpeg: ByteArray, box: RectF, seenMs: Long)
	{
		val c = config
		if(c.apiKey.isEmpty()) return
		val req = Request(++nextSeq, box, seenMs, buildRequest(c, jpeg), c.apiKey, c.model)
		inFlight++
		sendCopy(req)
		// Claude sometimes takes 2-3 s to start: if it hasn't after a short wait, send the same request
		// again and use whichever answers first (costs a little more, only when Claude is slow)
		main.postDelayed({
			if(req.winner < 0 && !req.done && req.open > 0 && running && req.copies < 2)
			{
				Log.i(TAG, "line ${req.seq}: Claude is slow, sending a backup request")
				sendCopy(req)
			}
		}, BACKUP_AFTER_MS)
	}

	private fun sendCopy(req: Request)
	{
		val copy = req.copies++
		req.open++
		network.execute { runCopy(req, copy) }
	}

	// Network thread: one copy of a request, streaming the answer back to the main thread
	private fun runCopy(req: Request, copy: Int)
	{
		var http = 0
		var errorBody = ""
		var networkError: String? = null
		var stopReason = ""
		var streamError: String? = null
		try
		{
			val conn = URL(API_URL).openConnection() as HttpURLConnection
			main.post { req.connections.add(conn) }
			conn.requestMethod = "POST"
			conn.connectTimeout = 10_000
			conn.readTimeout = 20_000
			conn.doOutput = true
			conn.setRequestProperty("content-type", "application/json")
			conn.setRequestProperty("x-api-key", req.apiKey)
			conn.setRequestProperty("anthropic-version", API_VERSION)
			conn.outputStream.use { it.write(req.body) }
			http = conn.responseCode
			if(http != 200)
				errorBody = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
			else
			{
				conn.inputStream.bufferedReader().useLines { lines ->
					for(raw in lines)
					{
						val line = raw.trim()
						if(!line.startsWith("data:")) continue
						val event = try { JSONObject(line.substring(5).trim()) } catch(e: Exception) { continue }
						when(event.optString("type"))
						{
							"message_start" -> {
								val t = event.optJSONObject("message")?.optJSONObject("usage")?.optDouble("input_tokens") ?: 0.0
								main.post { req.inTokens = t }
							}
							"content_block_delta" -> {
								val delta = event.optJSONObject("delta") ?: continue
								if(delta.optString("type") != "text_delta") continue
								val text = delta.optString("text")
								main.post { onDelta(req, copy, text) }
							}
							"message_delta" -> {
								stopReason = event.optJSONObject("delta")?.optString("stop_reason") ?: ""
								val t = event.optJSONObject("usage")?.optDouble("output_tokens") ?: 0.0
								main.post { req.outTokens = t }
							}
							"error" -> streamError = event.optJSONObject("error")?.optString("message")
						}
					}
				}
			}
		}
		catch(e: IOException)
		{
			networkError = e.message ?: e.javaClass.simpleName
		}
		val result = CopyResult(http, errorBody, networkError, stopReason, streamError)
		main.post { finishCopy(req, copy, result) }
	}

	private class CopyResult(val http: Int, val errorBody: String, val networkError: String?, val stopReason: String, val streamError: String?)

	private fun onDelta(req: Request, copy: Int, text: String)
	{
		if(req.winner >= 0 && req.winner != copy) return
		if(req.winner < 0)
		{
			req.winner = copy
			// The other copy is no longer needed
			req.connections.forEachIndexed { i, c -> if(i != copy) network.execute { c.disconnect() } }
		}
		req.answer += text
		updateLine(req, false)
	}

	/** Shows the translation (first line of the answer), word by word in instant mode */
	private fun updateLine(req: Request, final: Boolean)
	{
		if(req.noSubtitle || req.lineComplete) return
		val trimmed = req.answer.trim()
		if(isNone(trimmed)) { req.noSubtitle = true; return }
		if(!final && "NONE".startsWith(trimmed)) return // "N", "NO", "NON" could still become NONE
		val firstChar = req.answer.indexOfFirst { !it.isWhitespace() }
		val newline = if(firstChar >= 0) req.answer.indexOf('\n', firstChar) else -1
		val complete = final || newline >= 0
		var part = if(newline >= 0) req.answer.substring(0, newline) else req.answer
		if(!complete)
		{
			if(!config.instant) return
			val space = part.lastIndexOf(' ')
			if(space <= 0) return // whole words only
			part = part.substring(0, space)
		}
		val text = cleanLine(part)
		if(text.isEmpty()) return
		if(complete) req.lineComplete = true
		if(req.seq < shownSeq) return // a newer line is already on screen

		if(req.seq > shownSeq)
		{
			shownSeq = req.seq
			currentText = text
			currentComplete = complete
			currentOriginal = ""
			val now = SystemClock.elapsedRealtime()
			Log.i(TAG, "line ${req.seq} on screen ${now - req.seenMs} ms after it was seen (${req.sentMs - req.seenMs} ms to send, ${now - req.sentMs} ms Claude)")
			listener.onSubtitle(text, "", complete, req.box, true)
		}
		else if(text != currentText || complete != currentComplete)
		{
			currentText = text
			currentComplete = complete
			listener.onSubtitle(text, currentOriginal, complete, req.box, false)
		}
	}

	private fun finishCopy(req: Request, copy: Int, r: CopyResult)
	{
		req.open--
		if(req.done || (req.winner >= 0 && req.winner != copy) || (req.winner < 0 && req.open > 0))
			return
		req.done = true
		inFlight = max(0, inFlight - 1)
		val c = config
		try
		{
			if(r.http == 0 || r.networkError != null && r.http < 400)
			{
				setStatus(context.getString(R.string.subtitles_no_connection, r.networkError ?: ""), true)
				return
			}
			if(r.http != 200)
			{
				reportHttpError(r.http, r.errorBody, c)
				return
			}
			if(r.streamError != null)
			{
				Log.w(TAG, "Claude stream error ${r.streamError}")
				setStatus(context.getString(R.string.subtitles_busy), true)
				return
			}
			addCost(c, req.inTokens * req.copies, req.outTokens)
			if(statusError) setStatus(null, false)
			if(r.stopReason != "end_turn")
			{
				Log.i(TAG, "Subtitle request skipped, stop_reason ${r.stopReason}")
				return
			}
			updateLine(req, true)
			val lines = req.answer.trim().split('\n').filter { it.isNotBlank() }
			val translation = if(lines.isEmpty() || isNone(req.answer)) "" else cleanLine(lines[0])
			if(translation.isEmpty())
				Log.i(TAG, "line ${req.seq}: Claude found no subtitle in the image")
			else
			{
				val original = if(lines.size > 1) cleanLine(lines.drop(1).joinToString(" ")) else ""
				if(shownSeq == req.seq && currentOriginal != original)
				{
					currentOriginal = original
					listener.onSubtitle(currentText, original, currentComplete, req.box, false)
				}
				recentLines.addLast("$original => $translation")
				while(recentLines.size > RECENT_LINES) recentLines.removeFirst()
			}
		}
		finally
		{
			val p = pending
			if(p != null && running && inFlight < MAX_IN_FLIGHT)
			{
				pending = null
				startRequest(p.first, p.second, p.third)
			}
		}
	}

	private fun addCost(c: Config, inTokens: Double, outTokens: Double)
	{
		val (inPrice, outPrice) = when
		{
			c.model.startsWith("claude-haiku-5-5") -> 0.10 to 0.50
			c.model.startsWith("claude-sonnet-5-5") -> 2.00 to 10.00
			c.model.startsWith("claude-opus-5-5") -> 4.00 to 20.00
			else -> 0.0 to 0.0
		}
		costUsd += (inTokens * inPrice + outTokens * outPrice) / 1e6
		requestCount++
	}

	private fun reportHttpError(http: Int, body: String, c: Config)
	{
		val message = try { JSONObject(body).optJSONObject("error")?.optString("message") ?: "" } catch(e: Exception) { "" }
		val text = when(http)
		{
			401 -> context.getString(R.string.subtitles_key_rejected)
			403 -> context.getString(R.string.subtitles_model_forbidden, modelLabel(c.model))
			404 -> context.getString(R.string.subtitles_model_missing, modelLabel(c.model))
			429 -> context.getString(R.string.subtitles_too_many)
			500, 502, 503, 529 -> context.getString(R.string.subtitles_busy)
			else -> message.ifEmpty { context.getString(R.string.subtitles_error, http) }
		}
		Log.w(TAG, "Claude API error $http $message")
		setStatus(text, true)
	}

	private fun setStatus(text: String?, error: Boolean)
	{
		if(statusText == text && statusError == error) return
		statusText = text
		statusError = error
		listener.onStatus(text, error)
	}

	private fun clearSubtitle()
	{
		if(currentText.isEmpty() && currentOriginal.isEmpty()) return
		currentText = ""
		currentOriginal = ""
		currentComplete = true
		listener.onCleared()
	}
}
