// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.preference.PreferenceManager
import com.metallic.chiaki.R
import com.metallic.chiaki.lib.Codec
import com.metallic.chiaki.lib.ConnectVideoProfile
import com.metallic.chiaki.lib.VideoFPSPreset
import com.metallic.chiaki.lib.VideoResolutionPreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max
import kotlin.math.min

class Preferences(context: Context)
{
	// PSN login and API keys live in their own file, which is left out of Android backups
	private val secretPreferences = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)

	enum class Resolution(val value: String, @StringRes val title: Int, val preset: VideoResolutionPreset)
	{
		RES_360P("360p", R.string.preferences_resolution_title_360p, VideoResolutionPreset.RES_360P),
		RES_540P("540p", R.string.preferences_resolution_title_540p, VideoResolutionPreset.RES_540P),
		RES_720P("720p", R.string.preferences_resolution_title_720p, VideoResolutionPreset.RES_720P),
		RES_1080P("1080p", R.string.preferences_resolution_title_1080p, VideoResolutionPreset.RES_1080P),
	}

	enum class FPS(val value: String, @StringRes val title: Int, val preset: VideoFPSPreset)
	{
		FPS_30("30", R.string.preferences_fps_title_30, VideoFPSPreset.FPS_30),
		FPS_60("60", R.string.preferences_fps_title_60, VideoFPSPreset.FPS_60)
	}

	enum class Codec(val value: String, @StringRes val title: Int, val codec: com.metallic.chiaki.lib.Codec)
	{
		CODEC_H264("h264", R.string.preferences_codec_title_h264, com.metallic.chiaki.lib.Codec.CODEC_H264),
		CODEC_H265("h265", R.string.preferences_codec_title_h265, com.metallic.chiaki.lib.Codec.CODEC_H265)
	}

	companion object
	{
		// Live subtitle translation with Claude (same settings as the desktop app)
		const val SUBTITLES_ENABLED_KEY = "subtitles_enabled"
		const val SUBTITLES_LANGUAGE_KEY = "subtitles_language"   // "ckb" (Kurdish Sorani) or "ar"
		const val SUBTITLES_MODEL_KEY = "subtitles_model"
		const val SUBTITLES_STYLE_KEY = "subtitles_style"         // "0" clean (above the English), "1" cover
		const val SUBTITLES_INSTANT_KEY = "subtitles_instant"
		const val SUBTITLES_SHOW_ORIGINAL_KEY = "subtitles_show_original"
		const val CLAUDE_API_KEY_KEY = "claude_api_key"
		const val SUBTITLES_MODEL_DEFAULT = "claude-haiku-5-5"

		val resolutionDefault = Resolution.RES_720P
		val resolutionAll = Resolution.values()
		val fpsDefault = FPS.FPS_60
		val fpsAll = FPS.values()
		val codecDefault = Codec.CODEC_H265
		val codecAll = Codec.values()
	}

	private val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
	private val sharedPreferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
		when(key)
		{
			resolutionKey -> _bitrateAutoFlow.value = bitrateAuto
		}
	}.also { sharedPreferences.registerOnSharedPreferenceChangeListener(it) }

	private val resources = context.resources

	val discoveryEnabledKey get() = resources.getString(R.string.preferences_discovery_enabled_key)
	var discoveryEnabled
		get() = sharedPreferences.getBoolean(discoveryEnabledKey, true)
		set(value) { sharedPreferences.edit().putBoolean(discoveryEnabledKey, value).apply() }

	val onScreenControlsEnabledKey get() = resources.getString(R.string.preferences_on_screen_controls_enabled_key)
	var onScreenControlsEnabled
		get() = sharedPreferences.getBoolean(onScreenControlsEnabledKey, true)
		set(value) { sharedPreferences.edit().putBoolean(onScreenControlsEnabledKey, value).apply() }

	val touchpadOnlyEnabledKey get() = resources.getString(R.string.preferences_touchpad_only_enabled_key)
	var touchpadOnlyEnabled
		get() = sharedPreferences.getBoolean(touchpadOnlyEnabledKey, false)
		set(value) { sharedPreferences.edit().putBoolean(touchpadOnlyEnabledKey, value).apply() }

	val rumbleEnabledKey get() = resources.getString(R.string.preferences_rumble_enabled_key)
	var rumbleEnabled
		get() = sharedPreferences.getBoolean(rumbleEnabledKey, true)
		set(value) { sharedPreferences.edit().putBoolean(rumbleEnabledKey, value).apply() }

	val motionEnabledKey get() = resources.getString(R.string.preferences_motion_enabled_key)
	var motionEnabled
		get() = sharedPreferences.getBoolean(motionEnabledKey, true)
		set(value) { sharedPreferences.edit().putBoolean(motionEnabledKey, value).apply() }

	val buttonHapticEnabledKey get() = resources.getString(R.string.preferences_button_haptic_enabled_key)
	var buttonHapticEnabled
		get() = sharedPreferences.getBoolean(buttonHapticEnabledKey, true)
		set(value) { sharedPreferences.edit().putBoolean(buttonHapticEnabledKey, value).apply() }

	val logVerboseKey get() = resources.getString(R.string.preferences_log_verbose_key)
	var logVerbose
		get() = sharedPreferences.getBoolean(logVerboseKey, false)
		set(value) { sharedPreferences.edit().putBoolean(logVerboseKey, value).apply() }

	val swapCrossMoonKey get() = resources.getString(R.string.preferences_swap_cross_moon_key)
	var swapCrossMoon
		get() = sharedPreferences.getBoolean(swapCrossMoonKey, false)
		set(value) { sharedPreferences.edit().putBoolean(swapCrossMoonKey, value).apply() }

	val resolutionKey get() = resources.getString(R.string.preferences_resolution_key)
	var resolution
		get() = sharedPreferences.getString(resolutionKey, resolutionDefault.value)?.let { value ->
			Resolution.values().firstOrNull { it.value == value }
		} ?: resolutionDefault
		set(value) { sharedPreferences.edit().putString(resolutionKey, value.value).apply() }

	val fpsKey get() = resources.getString(R.string.preferences_fps_key)
	var fps
		get() = sharedPreferences.getString(fpsKey, fpsDefault.value)?.let { value ->
			FPS.values().firstOrNull { it.value == value }
		}  ?: fpsDefault
		set(value) { sharedPreferences.edit().putString(fpsKey, value.value).apply() }

	fun validateBitrate(bitrate: Int) = max(2000, min(50000, bitrate))
	val bitrateKey get() = resources.getString(R.string.preferences_bitrate_key)
	var bitrate
		get() = sharedPreferences.getInt(bitrateKey, 0).let { if(it == 0) null else validateBitrate(it) }
		set(value) { sharedPreferences.edit().putInt(bitrateKey, if(value != null) validateBitrate(value) else 0).apply() }
	val bitrateAuto get() = videoProfileDefaultBitrate.bitrate
	private val _bitrateAutoFlow by lazy { MutableStateFlow(bitrateAuto) }
	val bitrateAutoFlow: StateFlow<Int> get() = _bitrateAutoFlow.asStateFlow()

	val codecKey get() = resources.getString(R.string.preferences_codec_key)
	var codec
		get() = sharedPreferences.getString(codecKey, codecDefault.value)?.let { value ->
			Codec.values().firstOrNull { it.value == value }
		}  ?: codecDefault
		set(value) { sharedPreferences.edit().putString(codecKey, value.value).apply() }

	private val videoProfileDefaultBitrate get() = ConnectVideoProfile.preset(resolution.preset, fps.preset, codec.codec)
	val videoProfile get() = videoProfileDefaultBitrate.let {
		val bitrate = bitrate
		if(bitrate == null)
			it
		else
			it.copy(bitrate = bitrate)
	}

	val streamStatsKey get() = resources.getString(R.string.preferences_stream_stats_key)
	var streamStats
		get() = sharedPreferences.getBoolean(streamStatsKey, true)
		set(value) { sharedPreferences.edit().putBoolean(streamStatsKey, value).apply() }

	var psnLogin: SerializedPsnLogin?
		get() = secretPreferences.getString("psn_refresh_token", null)?.let {
			SerializedPsnLogin(
				accountId = secretPreferences.getString("psn_account_id", null),
				authToken = secretPreferences.getString("psn_auth_token", null),
				refreshToken = it,
				authTokenExpiry = secretPreferences.getString("psn_auth_token_expiry", null))
		}
		set(value) {
			secretPreferences.edit()
				.putString("psn_account_id", value?.accountId)
				.putString("psn_auth_token", value?.authToken)
				.putString("psn_refresh_token", value?.refreshToken)
				.putString("psn_auth_token_expiry", value?.authTokenExpiry)
				.apply()
		}

	// Live subtitle translation with Claude: see the subtitle constants in the companion object

	var subtitlesEnabled
		get() = sharedPreferences.getBoolean(SUBTITLES_ENABLED_KEY, false)
		set(value) { sharedPreferences.edit().putBoolean(SUBTITLES_ENABLED_KEY, value).apply() }
	var subtitlesLanguage
		get() = sharedPreferences.getString(SUBTITLES_LANGUAGE_KEY, "ckb") ?: "ckb"
		set(value) { sharedPreferences.edit().putString(SUBTITLES_LANGUAGE_KEY, value).apply() }
	var subtitlesModel
		get() = sharedPreferences.getString(SUBTITLES_MODEL_KEY, SUBTITLES_MODEL_DEFAULT) ?: SUBTITLES_MODEL_DEFAULT
		set(value) { sharedPreferences.edit().putString(SUBTITLES_MODEL_KEY, value).apply() }
	var subtitlesCoverStyle
		get() = sharedPreferences.getString(SUBTITLES_STYLE_KEY, "0") == "1"
		set(value) { sharedPreferences.edit().putString(SUBTITLES_STYLE_KEY, if(value) "1" else "0").apply() }
	var subtitlesInstant
		get() = sharedPreferences.getBoolean(SUBTITLES_INSTANT_KEY, true)
		set(value) { sharedPreferences.edit().putBoolean(SUBTITLES_INSTANT_KEY, value).apply() }
	var subtitlesShowOriginal
		get() = sharedPreferences.getBoolean(SUBTITLES_SHOW_ORIGINAL_KEY, false)
		set(value) { sharedPreferences.edit().putBoolean(SUBTITLES_SHOW_ORIGINAL_KEY, value).apply() }
	var claudeApiKey
		get() = secretPreferences.getString(CLAUDE_API_KEY_KEY, "")?.trim() ?: ""
		set(value) { secretPreferences.edit().putString(CLAUDE_API_KEY_KEY, value.trim()).apply() }

	// Settings without their own property (settings screen fallback)
	fun getRawBoolean(key: String, def: Boolean) = sharedPreferences.getBoolean(key, def)
	fun putRawBoolean(key: String, value: Boolean) = sharedPreferences.edit().putBoolean(key, value).apply()
	fun getRawString(key: String, def: String?) =
		if(key == CLAUDE_API_KEY_KEY) claudeApiKey else sharedPreferences.getString(key, def)
	fun putRawString(key: String, value: String?)
	{
		if(key == CLAUDE_API_KEY_KEY) claudeApiKey = value ?: ""
		else sharedPreferences.edit().putString(key, value).apply()
	}
}
