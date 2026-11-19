// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

import android.app.Activity
import android.content.Intent
import android.util.Log
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.R
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.common.PsnAccount
import com.metallic.chiaki.common.RegisteredHost
import com.metallic.chiaki.discovery.DiscoveryManager
import com.metallic.chiaki.lib.ConnectInfo
import com.metallic.chiaki.lib.PsnNative
import com.metallic.chiaki.stream.StreamActivity
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * Connects to a registered console the way the desktop app does:
 * local when the console answers on this network (waking it first if it sleeps),
 * otherwise over the internet through PSN.
 */
class ConsoleConnector(
	private val activity: Activity,
	private val scope: CoroutineScope,
	private val discoveryManager: DiscoveryManager)
{
	companion object
	{
		private const val TAG = "ConsoleConnector"
		private const val WAKE_TIMEOUT_MS = 45_000L
	}

	enum class ConsoleState { READY, STANDBY, NO_ANSWER }

	private var job: Job? = null
	private var progress: AlertDialog? = null

	fun connect(host: String, registeredHost: RegisteredHost, forcePsn: Boolean = false)
	{
		job?.cancel()
		val name = registeredHost.serverNickname ?: host
		val ps5 = registeredHost.target.isPS5
		job = scope.launch {
			try
			{
				if(!forcePsn)
				{
					showProgress(activity.getString(R.string.connect_checking, name))
					var state = probe(host, ps5)
					if(state == ConsoleState.STANDBY)
					{
						showProgress(activity.getString(R.string.connect_waking, name))
						discoveryManager.sendWakeup(host, registeredHost.rpRegistKey, ps5)
						state = waitUntilReady(host, registeredHost, ps5)
					}
					if(state == ConsoleState.READY)
					{
						dismissProgress()
						startStream(ConnectInfo(ps5, host, registeredHost.rpRegistKey, registeredHost.rpKey, Preferences(activity).videoProfile))
						return@launch
					}
					Log.i(TAG, "$name does not answer on this network ($state), trying PSN")
				}
				connectPsn(name, ps5)
			}
			catch(e: CancellationException)
			{
				throw e
			}
			catch(e: PsnAccount.PsnException)
			{
				dismissProgress()
				showError(e.message ?: "", offerLogin = e.loginExpired)
			}
			finally
			{
				if(job?.isActive != true)
					dismissProgress()
			}
		}
	}

	private suspend fun connectPsn(name: String, ps5: Boolean)
	{
		if(!PsnAccount.isLoggedIn(activity))
		{
			dismissProgress()
			showError(activity.getString(R.string.connect_not_reachable_no_psn, name), offerLogin = true)
			return
		}
		showProgress(activity.getString(R.string.connect_psn_finding, name))
		val token = PsnAccount.accessToken(activity)
		val accountId = PsnAccount.accountIdBytes(activity)
			?: throw PsnAccount.PsnException("The PSN login has no account id, please log in again", loginExpired = true)
		val devices = withContext(Dispatchers.IO) {
			PsnNative.listDevices(token, ps5) ?: PsnNative.listDevices(token, ps5)
		} ?: throw PsnAccount.PsnException(activity.getString(R.string.connect_psn_list_failed))
		val candidates = devices.filter { it.remotePlayEnabled }
		val device = candidates.firstOrNull { it.name.equals(name, ignoreCase = true) }
			?: candidates.singleOrNull()
			?: throw PsnAccount.PsnException(
				if(devices.isEmpty()) activity.getString(R.string.connect_psn_no_consoles)
				else activity.getString(R.string.connect_psn_not_found, name, devices.joinToString { it.name }))
		dismissProgress()
		startStream(ConnectInfo(ps5, "", ByteArray(0x10), ByteArray(0x10), Preferences(activity).videoProfile,
			duid = device.duid, psnToken = token, psnAccountId = accountId))
	}

	private suspend fun waitUntilReady(host: String, registeredHost: RegisteredHost, ps5: Boolean): ConsoleState
	{
		val deadline = System.currentTimeMillis() + WAKE_TIMEOUT_MS
		var lastWake = System.currentTimeMillis()
		while(System.currentTimeMillis() < deadline)
		{
			delay(1000)
			if(probe(host, ps5) == ConsoleState.READY)
			{
				delay(1500) // Remote Play on the console starts a moment after it reports ready
				return ConsoleState.READY
			}
			if(System.currentTimeMillis() - lastWake > 8000)
			{
				discoveryManager.sendWakeup(host, registeredHost.rpRegistKey, ps5)
				lastWake = System.currentTimeMillis()
			}
		}
		return ConsoleState.NO_ANSWER
	}

	/**
	 * Asks the console directly (unicast discovery, works across subnets) whether it is on.
	 * Same as consoleAnswersDiscovery in the desktop app: up to 800 ms, resent every 250 ms.
	 */
	suspend fun probe(host: String, ps5: Boolean): ConsoleState = withContext(Dispatchers.IO) {
		val port = if(ps5) 9302 else 987
		val version = if(ps5) "00030010" else "00020020"
		val request = "SRCH * HTTP/1.1\ndevice-discovery-protocol-version:$version\n".toByteArray()
		try
		{
			DatagramSocket().use { socket ->
				socket.soTimeout = 250
				val address = InetAddress.getByName(host)
				val buf = ByteArray(2048)
				repeat(4) {
					socket.send(DatagramPacket(request, request.size, address, port))
					try
					{
						val packet = DatagramPacket(buf, buf.size)
						socket.receive(packet)
						val firstLine = String(packet.data, 0, packet.length).lineSequence().firstOrNull() ?: ""
						return@withContext when
						{
							firstLine.contains(" 200 ") -> ConsoleState.READY
							firstLine.contains(" 620 ") -> ConsoleState.STANDBY
							else -> ConsoleState.NO_ANSWER
						}
					}
					catch(e: SocketTimeoutException) { }
				}
			}
		}
		catch(e: Exception)
		{
			Log.w(TAG, "Probe of $host failed: $e")
		}
		ConsoleState.NO_ANSWER
	}

	private fun startStream(connectInfo: ConnectInfo)
	{
		Intent(activity, StreamActivity::class.java).let {
			it.putExtra(StreamActivity.EXTRA_CONNECT_INFO, connectInfo)
			activity.startActivity(it)
		}
	}

	private fun showProgress(message: String)
	{
		val p = progress
		if(p != null && p.isShowing)
		{
			p.setMessage(message)
			return
		}
		progress = MaterialAlertDialogBuilder(activity)
			.setMessage(message)
			.setNegativeButton(R.string.action_connect_cancel_connect) { _, _ -> job?.cancel() }
			.setOnCancelListener { job?.cancel() }
			.show()
	}

	private fun dismissProgress()
	{
		progress?.dismiss()
		progress = null
	}

	private fun showError(message: String, offerLogin: Boolean)
	{
		val builder = MaterialAlertDialogBuilder(activity)
			.setMessage(message)
			.setPositiveButton(R.string.action_import_failed_ack) { _, _ -> }
		if(offerLogin)
			builder.setNeutralButton(R.string.action_psn_login) { _, _ ->
				activity.startActivity(Intent(activity, PsnLoginActivity::class.java))
			}
		builder.show()
	}

	fun cancel()
	{
		job?.cancel()
		dismissProgress()
	}
}
