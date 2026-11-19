// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.session

import android.graphics.SurfaceTexture
import android.util.Log
import android.view.*
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.metallic.chiaki.common.LogManager
import com.metallic.chiaki.lib.*

sealed class StreamState
object StreamStateIdle: StreamState()
object StreamStateConnecting: StreamState()
/** Finding, waking and reaching the console through PSN (can take up to a minute) */
object StreamStatePsnConnecting: StreamState()
data class StreamStateConnectFailed(val message: String): StreamState()
object StreamStateConnected: StreamState()
data class StreamStateCreateError(val error: CreateError): StreamState()
data class StreamStateQuit(val reason: QuitReason, val reasonString: String?): StreamState()
data class StreamStateLoginPinRequest(val pinIncorrect: Boolean): StreamState()

private const val ERR_HOST_DOWN = 8
private const val ERR_HOST_UNREACH = 9

class StreamSession(val connectInfo: ConnectInfo, val logManager: LogManager, val logVerbose: Boolean, val input: StreamInput)
{
	var session: Session? = null
		private set

	private val _state = MutableLiveData<StreamState>(StreamStateIdle)
	val state: LiveData<StreamState> get() = _state
	private val _rumbleState = MutableLiveData<RumbleEvent>(RumbleEvent(0U, 0U))
	val rumbleState: LiveData<RumbleEvent> get() = _rumbleState

	private var surfaceTexture: SurfaceTexture? = null
	private var surface: Surface? = null

	init
	{
		input.controllerStateChangedCallback = {
			session?.setControllerState(it)
		}
	}

	private var psnThread: Thread? = null
	private var started = false

	fun shutdown()
	{
		val session = session
		session?.psnCancel()
		psnThread?.join()
		psnThread = null
		if(started)
			session?.stop()
		session?.dispose(started)
		started = false
		this.session = null
		_state.value = StreamStateIdle
		//surfaceTexture?.release()
	}

	fun pause()
	{
		shutdown()
	}

	fun resume()
	{
		if(session != null)
			return
		try
		{
			val session = Session(connectInfo, logManager.createNewFile().file.absolutePath, logVerbose)
			session.eventCallback = this::eventCallback
			this.session = session
			if(connectInfo.duid != null)
			{
				_state.value = StreamStatePsnConnecting
				psnThread = Thread({
					val err = session.psnConnect(connectInfo.ps5)
					synchronized(this) {
						if(this.session !== session)
							return@Thread
						if(err.isSuccess)
						{
							_state.postValue(StreamStateConnecting)
							started = true
							session.start()
						}
						else
							_state.postValue(StreamStateConnectFailed(psnErrorMessage(err)))
					}
				}, "PsnConnect").also { it.start() }
			}
			else
			{
				_state.value = StreamStateConnecting
				started = true
				session.start()
			}
			val surface = surface
			if(surface != null)
				session.setSurface(surface)
		}
		catch(e: CreateError)
		{
			_state.value = StreamStateCreateError(e)
		}
	}

	private fun psnErrorMessage(err: ErrorCode) = when(err.value)
	{
		// CHIAKI_ERR_HOST_DOWN / CHIAKI_ERR_HOST_UNREACH / CHIAKI_ERR_CANCELED (lib/include/chiaki/common.h)
		ERR_HOST_DOWN -> "The console did not answer over PSN. Check that it is in rest mode with \"Stay connected to the internet\" on, or switched on."
		ERR_HOST_UNREACH -> "PSN found the console, but no network path to it opened (strict NAT on one side). Try again, or use a cable / another network."
		else -> "Connecting over PSN failed: $err"
	}

	private fun eventCallback(event: Event)
	{
		when(event)
		{
			is ConnectedEvent -> _state.postValue(StreamStateConnected)
			is QuitEvent -> _state.postValue(
				StreamStateQuit(
					event.reason,
					event.reasonString
				)
			)
			is LoginPinRequestEvent -> _state.postValue(
				StreamStateLoginPinRequest(
					event.pinIncorrect
				)
			)
			is RumbleEvent -> _rumbleState.postValue(event)
		}
	}

	fun attachToSurfaceView(surfaceView: SurfaceView)
	{
		surfaceView.holder.addCallback(object: SurfaceHolder.Callback {
			override fun surfaceCreated(holder: SurfaceHolder) { }

			override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int)
			{
				val surface = holder.surface
				this@StreamSession.surface = surface
				session?.setSurface(surface)
				// A new decoder started mid-stream (e.g. back from Settings) needs a keyframe to show anything
				if(_state.value == StreamStateConnected)
					session?.requestIdr()
			}

			override fun surfaceDestroyed(holder: SurfaceHolder)
			{
				this@StreamSession.surface = null
				session?.setSurface(null)
			}
		})
	}

	fun attachToTextureView(textureView: TextureView)
	{
		textureView.surfaceTextureListener = object: TextureView.SurfaceTextureListener {
			override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int)
			{
				if(surfaceTexture != null)
					return
				surfaceTexture = surface
				this@StreamSession.surface = Surface(surfaceTexture)
				session?.setSurface(Surface(surface))
			}

			override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean
			{
				// return false if we want to keep the surface texture
				return surfaceTexture == null
			}

			override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) { }
			override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
		}

		val surfaceTexture = surfaceTexture
		if(surfaceTexture != null)
			textureView.setSurfaceTexture(surfaceTexture)
	}

	fun setLoginPin(pin: String)
	{
		session?.setLoginPin(pin)
	}
}