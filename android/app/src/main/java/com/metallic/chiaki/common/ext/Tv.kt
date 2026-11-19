// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common.ext

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.content.pm.PackageManager

/** True on Android TV / Google TV: driven by a remote or gamepad, no touchscreen */
val Context.isTv: Boolean
	get() = (getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
			|| packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
