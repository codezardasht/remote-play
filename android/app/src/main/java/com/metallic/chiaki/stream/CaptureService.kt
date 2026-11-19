// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.*
import android.util.Log
import android.view.PixelCopy
import android.view.Surface
import java.nio.ByteBuffer

/**
 * Copies part of the video surface for the subtitle translator, in its own process (":capture").
 *
 * On TVs that show video on a hardware overlay (e.g. Realtek), one PixelCopy takes over a second
 * and keeps the calling process's render thread busy the whole time. In the app's own process that
 * stalls every UI update and with it the controller input. Here it only blocks this process.
 *
 * Protocol (Messenger): MSG_COPY with data {surface: Surface, rect: Rect, width, height, memory: SharedMemory};
 * replies MSG_COPY_DONE with arg1 = PixelCopy result, the RGBA pixels written to the shared memory.
 */
open class CaptureService: Service()
{
	companion object
	{
		private const val TAG = "CaptureService"
		const val MSG_COPY = 1
		const val MSG_COPY_DONE = 2
		const val KEY_SURFACE = "surface"
		const val KEY_RECT = "rect"
		const val KEY_WIDTH = "width"
		const val KEY_HEIGHT = "height"
		const val KEY_MEMORY = "memory"
	}

	private val thread = HandlerThread("Capture").also { it.start() }
	private val handler = Handler(thread.looper) { msg -> if(msg.what == MSG_COPY) copy(msg); true }
	private val messenger = Messenger(handler)

	private var memory: SharedMemory? = null
	private var mapped: ByteBuffer? = null
	private var bitmap: Bitmap? = null

	override fun onBind(intent: Intent): IBinder = messenger.binder

	override fun onDestroy()
	{
		thread.quitSafely()
		super.onDestroy()
	}

	private fun copy(msg: Message)
	{
		val replyTo = msg.replyTo ?: return
		fun reply(result: Int)
		{
			try { replyTo.send(Message.obtain(null, MSG_COPY_DONE, result, 0)) }
			catch(e: RemoteException) { }
		}
		if(Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) { reply(PixelCopy.ERROR_UNKNOWN); return }
		val data = msg.data
		data.classLoader = javaClass.classLoader
		val surface = data.getParcelable<Surface>(KEY_SURFACE)
		val rect = data.getParcelable<Rect>(KEY_RECT)
		val w = data.getInt(KEY_WIDTH)
		val h = data.getInt(KEY_HEIGHT)
		data.getParcelable<SharedMemory>(KEY_MEMORY)?.let { m ->
			mapped?.let { SharedMemory.unmap(it) }
			memory?.close()
			memory = m
			mapped = m.mapReadWrite()
		}
		val out = mapped
		if(surface == null || rect == null || w <= 0 || h <= 0 || out == null || out.capacity() < w * h * 4 || !surface.isValid)
		{
			surface?.release()
			reply(PixelCopy.ERROR_SOURCE_INVALID)
			return
		}
		val bmp = bitmap?.takeIf { it.width == w && it.height == h }
			?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bitmap = it }
		try
		{
			PixelCopy.request(surface, rect, bmp, { result ->
				if(result == PixelCopy.SUCCESS)
				{
					out.position(0)
					bmp.copyPixelsToBuffer(out)
				}
				surface.release()
				reply(result)
			}, handler)
		}
		catch(e: Exception)
		{
			Log.w(TAG, "Copy failed: $e")
			surface.release()
			reply(PixelCopy.ERROR_SOURCE_INVALID)
		}
	}
}

/** More capture processes: one copy can take over a second, so they take turns */
class CaptureService2: CaptureService()
class CaptureService3: CaptureService()
