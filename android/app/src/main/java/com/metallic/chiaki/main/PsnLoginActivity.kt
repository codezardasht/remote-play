// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.metallic.chiaki.R
import com.metallic.chiaki.common.PsnAccount
import kotlinx.coroutines.launch

/**
 * Sony's PSN login page in a WebView. When it redirects to the Remote Play redirect URL,
 * the code from it is exchanged for tokens (same as the desktop PSN login dialog).
 * On a TV it is easier to log in on the desktop app and send the login over with
 * scripts/desktop-settings-to-android.py --push.
 */
class PsnLoginActivity : AppCompatActivity()
{
	private var done = false

	@SuppressLint("SetJavaScriptEnabled")
	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		title = getString(R.string.action_psn_login)
		CookieManager.getInstance().removeAllCookies(null)
		val webView = WebView(this)
		webView.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
		webView.settings.javaScriptEnabled = true
		webView.settings.domStorageEnabled = true
		webView.webViewClient = object: WebViewClient() {
			override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
				handle(request.url.toString())

			@Deprecated("Deprecated in Java")
			override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = handle(url)
		}
		setContentView(webView)
		webView.loadUrl(PsnAccount.loginUrl())
	}

	private fun handle(url: String): Boolean
	{
		val code = PsnAccount.codeFromRedirect(url) ?: return false
		if(done)
			return true
		done = true
		lifecycleScope.launch {
			try
			{
				PsnAccount.loginWithCode(this@PsnLoginActivity, code)
				Toast.makeText(this@PsnLoginActivity, R.string.psn_login_success, Toast.LENGTH_LONG).show()
			}
			catch(e: Exception)
			{
				Toast.makeText(this@PsnLoginActivity, getString(R.string.psn_login_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
			}
			finish()
		}
		return true
	}
}
