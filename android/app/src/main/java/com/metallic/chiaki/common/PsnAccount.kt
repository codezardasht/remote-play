// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.metallic.chiaki.lib.PsnNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PSN login for Remote Play over the internet, same requests as the desktop app
 * (gui/include/psnaccountid.h, gui/src/psntoken.cpp, gui/src/psnaccountid.cpp).
 * Tokens are kept in [Preferences.psnLogin] in the desktop's format, so they can move both ways.
 */
object PsnAccount
{
	private const val TAG = "PsnAccount"
	private const val CLIENT_ID = "ba495a24-818c-472b-b12d-ff231c1b5745"
	private const val CLIENT_SECRET = "mvaiZkRsAsI1IBkY"
	const val REDIRECT_URL = "https://remoteplay.dl.playstation.net/remoteplay/redirect"
	private const val SCOPE = "psn:clientapp referenceDataService:countryConfig.read pushNotification:webSocket.desktop.connect sessionManager:remotePlaySession.system.update"
	private const val TOKEN_URL = "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/token"
	private const val AUTHORIZE_URL = "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/authorize"

	// Desktop stores the expiry as QDateTime "yyyy-MM-dd HH:mm:ss t", e.g. "2026-11-19 04:09:02 +03"
	private fun expiryFormat() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss X", Locale.US)

	class PsnException(message: String, val loginExpired: Boolean = false): Exception(message)

	fun loginUrl(): String
	{
		val duid = PsnNative.generateDuid() ?: ""
		fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
		return "$AUTHORIZE_URL?service_entity=urn:service-entity:psn&response_type=code" +
				"&client_id=$CLIENT_ID&redirect_uri=${enc(REDIRECT_URL)}&scope=${enc(SCOPE)}" +
				"&request_locale=en_US&ui=pr&service_logo=ps&layout_type=popup&smcid=remoteplay" +
				"&prompt=always&PlatformPrivacyWs1=minimal&duid=$duid&"
	}

	/** The `code` from the redirect after logging in, or null if [url] is not the redirect */
	fun codeFromRedirect(url: String): String? =
		if(url.startsWith(REDIRECT_URL)) Uri.parse(url).getQueryParameter("code") else null

	fun isLoggedIn(context: Context) = Preferences(context).psnLogin?.refreshToken != null

	fun logout(context: Context)
	{
		Preferences(context).psnLogin = null
	}

	/** Account id as the 8 raw bytes the console expects, or null */
	fun accountIdBytes(context: Context): ByteArray? =
		Preferences(context).psnLogin?.accountId?.let {
			try { Base64.decode(it, Base64.DEFAULT).takeIf { b -> b.size == 8 } } catch(e: IllegalArgumentException) { null }
		}

	/** Finish logging in with the code from the redirect: get tokens and the account id, then store them */
	suspend fun loginWithCode(context: Context, code: String) = withContext(Dispatchers.IO) {
		val tokens = tokenRequest("grant_type=authorization_code&code=$code&scope=$SCOPE&redirect_uri=$REDIRECT_URL&")
		val accountId = fetchAccountId(tokens.getString("access_token"))
		store(context, tokens, accountId)
	}

	/** A valid access token, refreshed first if it runs out within a minute. Throws [PsnException]. */
	suspend fun accessToken(context: Context): String = withContext(Dispatchers.IO) {
		val login = Preferences(context).psnLogin ?: throw PsnException("Not logged in to PSN", loginExpired = true)
		val expiry = login.authTokenExpiry?.let { parseExpiry(it) } ?: 0L
		val token = login.authToken
		if(token != null && System.currentTimeMillis() + 60_000 < expiry)
			return@withContext token
		val refresh = login.refreshToken ?: throw PsnException("Not logged in to PSN", loginExpired = true)
		val tokens = tokenRequest("grant_type=refresh_token&refresh_token=$refresh&scope=$SCOPE&redirect_uri=$REDIRECT_URL&")
		val accountId = login.accountId ?: fetchAccountId(tokens.getString("access_token"))
		store(context, tokens, accountId)
		tokens.getString("access_token")
	}

	private fun parseExpiry(s: String): Long? =
		s.toLongOrNull() ?: try { expiryFormat().parse(s)?.time } catch(e: Exception) { null }

	private fun store(context: Context, tokens: JSONObject, accountId: String)
	{
		val expiry = System.currentTimeMillis() + tokens.optLong("expires_in", 3600) * 1000
		Preferences(context).psnLogin = SerializedPsnLogin(
			accountId = accountId,
			authToken = tokens.getString("access_token"),
			refreshToken = tokens.optString("refresh_token").takeIf { it.isNotEmpty() }
				?: Preferences(context).psnLogin?.refreshToken,
			authTokenExpiry = expiryFormat().format(Date(expiry)))
	}

	private fun basicAuth() = "Basic " + Base64.encodeToString("$CLIENT_ID:$CLIENT_SECRET".toByteArray(), Base64.NO_WRAP)

	private fun tokenRequest(body: String): JSONObject
	{
		val conn = URL(TOKEN_URL).openConnection() as HttpURLConnection
		try
		{
			conn.requestMethod = "POST"
			conn.connectTimeout = 10_000
			conn.readTimeout = 15_000
			conn.doOutput = true
			conn.setRequestProperty("Authorization", basicAuth())
			conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
			conn.outputStream.use { it.write(body.toByteArray()) }
			val status = conn.responseCode
			if(status == 400 || status == 401)
				throw PsnException("PSN login expired, please log in again", loginExpired = true)
			if(status !in 200..299)
				throw PsnException("PSN token request failed (HTTP $status)")
			return JSONObject(conn.inputStream.bufferedReader().readText())
		}
		catch(e: IOException)
		{
			Log.e(TAG, "Token request failed", e)
			throw PsnException("Could not reach PSN: ${e.message}")
		}
		finally
		{
			conn.disconnect()
		}
	}

	/** user_id (decimal) as 8 little-endian bytes, base64: the format the desktop stores */
	private fun fetchAccountId(accessToken: String): String
	{
		val conn = URL("$TOKEN_URL/$accessToken").openConnection() as HttpURLConnection
		try
		{
			conn.connectTimeout = 10_000
			conn.readTimeout = 15_000
			conn.setRequestProperty("Authorization", basicAuth())
			if(conn.responseCode !in 200..299)
				throw PsnException("Could not read the PSN account id (HTTP ${conn.responseCode})")
			val userId = JSONObject(conn.inputStream.bufferedReader().readText()).getString("user_id").toULong()
			val bytes = ByteArray(8) { i -> ((userId shr (8 * i)) and 0xffu).toByte() }
			return Base64.encodeToString(bytes, Base64.NO_WRAP)
		}
		catch(e: IOException)
		{
			throw PsnException("Could not reach PSN: ${e.message}")
		}
		finally
		{
			conn.disconnect()
		}
	}

	/** curl inside the native library verifies Sony's certificates against this bundle (see third-party/CMakeLists.txt) */
	fun installCaBundle(context: Context)
	{
		val target = File(context.filesDir, "cacert.pem")
		try
		{
			val assetSize = context.assets.openFd("cacert.pem").use { it.length }
			if(target.isFile && target.length() == assetSize)
				return
		}
		catch(e: IOException) { /* compressed asset: just copy */ }
		try
		{
			context.assets.open("cacert.pem").use { input -> target.outputStream().use { input.copyTo(it) } }
		}
		catch(e: IOException)
		{
			Log.e(TAG, "Failed to install CA bundle", e)
		}
	}
}
