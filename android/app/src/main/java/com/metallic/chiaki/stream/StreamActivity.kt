// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.AlertDialog
import android.graphics.Matrix
import android.content.Intent
import android.os.*
import android.view.*
import android.widget.EditText
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.R
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.common.ext.isTv
import com.metallic.chiaki.common.ext.viewModelFactory
import com.metallic.chiaki.settings.SettingsActivity
import com.metallic.chiaki.databinding.ActivityStreamBinding
import com.metallic.chiaki.lib.ConnectInfo
import com.metallic.chiaki.lib.ConnectVideoProfile
import com.metallic.chiaki.session.*
import com.metallic.chiaki.touchcontrols.DefaultTouchControlsFragment
import com.metallic.chiaki.touchcontrols.TouchControlsFragment
import com.metallic.chiaki.touchcontrols.TouchpadOnlyFragment
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlin.math.min

private sealed class DialogContents
private object StreamQuitDialog: DialogContents()
private object CreateErrorDialog: DialogContents()
private object PinRequestDialog: DialogContents()

class StreamActivity : AppCompatActivity()
{
	companion object
	{
		const val EXTRA_CONNECT_INFO = "connect_info"
		private const val HIDE_UI_TIMEOUT_MS = 2000L
		private const val HUD_SAMPLE_MS = 200L
		private const val MAX_AUTO_RECONNECTS = 3
		private const val AUTO_RECONNECT_DELAY_MS = 1500L
		// CHIAKI_QUIT_REASON_CTRL_UNKNOWN, _STREAM_CONNECTION_UNKNOWN (lib/include/chiaki/session.h)
		private val AUTO_RECONNECT_REASONS = setOf(7, 10)
		private const val HUD_SAMPLES_PER_TICK = 5
	}

	private lateinit var preferences: Preferences
	private lateinit var hud: StreamHud
	private var subtitles: SubtitleTranslator? = null
	private var subtitleOverlay: SubtitleOverlay? = null
	private var tv = false
	// Set while our own Settings screen is open on top, so the stream keeps running behind it
	private var keepSessionOnPause = false

	private lateinit var viewModel: StreamViewModel
	private lateinit var binding: ActivityStreamBinding
	private lateinit var insetsController: WindowInsetsControllerCompat

	private val uiVisibilityHandler = Handler(Looper.getMainLooper())

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)

		val connectInfo = IntentCompat.getParcelableExtra(intent, EXTRA_CONNECT_INFO, ConnectInfo::class.java)
		if(connectInfo == null)
		{
			finish()
			return
		}

		viewModel = ViewModelProvider(this, viewModelFactory {
			StreamViewModel(application, connectInfo)
		})[StreamViewModel::class.java]

		viewModel.input.observe(this)
		preferences = Preferences(this)
		hud = StreamHud(this)
		hud.remote = connectInfo.duid != null
		tv = isTv

		binding = ActivityStreamBinding.inflate(layoutInflater)
		setContentView(binding.root)

		viewModel.input.menuRequestCallback = { showStreamMenu() }
		subtitleOverlay = SubtitleOverlay(binding, preferences).also { overlay ->
			subtitles = SubtitleTranslator(applicationContext, binding.surfaceView, overlay)
		}
		// Ask the TV for its Game Mode (ALLM): no picture processing, which otherwise adds 50-150 ms of lag
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
			window.setPreferMinimalPostProcessing(true)
		// TV remotes with a pointer (air mouse) would otherwise leave a cursor over the game
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
			binding.root.pointerIcon = PointerIcon.getSystemIcon(this, PointerIcon.TYPE_NULL)
		onBackPressedDispatcher.addCallback(this) { showStreamMenu() }

		if(tv)
		{
			// No touchscreen: no on-screen controls or touch overlay, everything goes through the in-game menu
			viewModel.setOnScreenControlsEnabled(false)
			viewModel.setTouchpadOnlyEnabled(false)
			supportFragmentManager.findFragmentById(R.id.controlsFragment)?.view?.isGone = true
			supportFragmentManager.findFragmentById(R.id.touchpadOnlyFragment)?.view?.isGone = true
		}

		WindowCompat.setDecorFitsSystemWindows(window, false)
		insetsController = WindowCompat.getInsetsController(window, window.decorView)
		insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

		ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { _, insets ->
			val systemBars = insets.isVisible(WindowInsetsCompat.Type.systemBars())
			if(systemBars && !tv)
				showOverlay()
			else
				hideOverlay()
			insets
		}

		viewModel.onScreenControlsEnabled.observe(this, Observer {
			if(binding.onScreenControlsSwitch.isChecked != it)
				binding.onScreenControlsSwitch.isChecked = it
			if(binding.onScreenControlsSwitch.isChecked)
				binding.touchpadOnlySwitch.isChecked = false
		})
		binding.onScreenControlsSwitch.setOnCheckedChangeListener { _, isChecked ->
			viewModel.setOnScreenControlsEnabled(isChecked)
			showOverlay()
		}

		viewModel.touchpadOnlyEnabled.observe(this, Observer {
			if(binding.touchpadOnlySwitch.isChecked != it)
				binding.touchpadOnlySwitch.isChecked = it
			if(binding.touchpadOnlySwitch.isChecked)
				binding.onScreenControlsSwitch.isChecked = false
		})
		binding.touchpadOnlySwitch.setOnCheckedChangeListener { _, isChecked ->
			viewModel.setTouchpadOnlyEnabled(isChecked)
			showOverlay()
		}

		if(tv)
			binding.overlay.isGone = true

		binding.displayModeToggle.addOnButtonCheckedListener { _, _, _ ->
			adjustStreamViewAspect()
			showOverlay()
		}

		//viewModel.session.attachToTextureView(textureView)
		viewModel.session.attachToSurfaceView(binding.surfaceView)
		viewModel.session.state.observe(this, Observer { this.stateChanged(it) })
		adjustStreamViewAspect()

		if(Preferences(this).rumbleEnabled)
		{
			val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
			viewModel.session.rumbleState.observe(this, Observer {
				val amplitude = min(255, (it.left.toInt() + it.right.toInt()) / 2)
				vibrator.cancel()
				if(amplitude == 0)
					return@Observer
				if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
					vibrator.vibrate(VibrationEffect.createOneShot(1000, amplitude))
				else
					vibrator.vibrate(1000)
			})
		}
	}

	private var controlsJob: Job? = null

	override fun onAttachFragment(fragment: Fragment)
	{
		super.onAttachFragment(fragment)
		if(fragment is TouchControlsFragment)
		{
			controlsJob?.cancel()
			controlsJob = fragment.controllerState
				.onEach { viewModel.input.touchControllerState = it }
				.launchIn(lifecycleScope)
			fragment.onScreenControlsEnabled = viewModel.onScreenControlsEnabled
			if(fragment is TouchpadOnlyFragment)
				fragment.touchpadOnlyEnabled = viewModel.touchpadOnlyEnabled
		}
	}

	override fun onResume()
	{
		super.onResume()
		hideSystemUI()
		keepSessionOnPause = false
		viewModel.session.resume()
		updateHudVisibility()
		hud.reset()
		uiVisibilityHandler.post(hudRunnable)
	}

	override fun onPause()
	{
		super.onPause()
		uiVisibilityHandler.removeCallbacks(hudRunnable)
		if(!keepSessionOnPause)
		{
			viewModel.session.pause()
			subtitles?.refresh(false)
		}
	}

	override fun onStop()
	{
		super.onStop()
		// Leaving the app (Home on the remote) always ends the stream, even with Settings open
		if(keepSessionOnPause && !isChangingConfigurations)
		{
			keepSessionOnPause = false
			viewModel.session.pause()
		}
	}

	private var hudSamples = 0
	private val hudRunnable = object: Runnable {
		override fun run()
		{
			if(viewModel.session.state.value == StreamStateConnected)
			{
				if(!preferences.streamStats)
				{
					if(++hudSamples >= HUD_SAMPLES_PER_TICK)
					{
						hudSamples = 0
						subtitles?.refresh(true)
					}
				}
				else viewModel.session.session?.stats()?.let {
					hud.sampleLoss(it.packetLoss)
					if(++hudSamples >= HUD_SAMPLES_PER_TICK)
					{
						hudSamples = 0
						subtitles?.refresh(true)
						binding.hudTextView.text = hud.tick(it)
					}
				}
			}
			updateHudVisibility()
			uiVisibilityHandler.postDelayed(this, HUD_SAMPLE_MS)
		}
	}

	private fun updateHudVisibility()
	{
		binding.hudTextView.isVisible = preferences.streamStats
				&& viewModel.session.state.value == StreamStateConnected
				&& binding.hudTextView.text.isNotEmpty()
	}

	private var menuDialog: androidx.appcompat.app.AlertDialog? = null

	/** In-game menu: Back on the TV remote, Share + Options on a controller, or the system Back gesture on phones */
	private fun showStreamMenu()
	{
		if(menuDialog?.isShowing == true || dialog != null)
			return
		viewModel.input.releaseAll()
		val modeNames = mapOf(
			R.id.display_mode_normal_button to getString(R.string.stream_menu_display_fit),
			R.id.display_mode_zoom_button to getString(R.string.stream_menu_display_zoom),
			R.id.display_mode_stretch_button to getString(R.string.stream_menu_display_stretch))
		val modeOrder = listOf(R.id.display_mode_normal_button, R.id.display_mode_zoom_button, R.id.display_mode_stretch_button)
		val currentMode = binding.displayModeToggle.checkedButtonId

		data class Item(val label: String, val action: () -> Unit)
		val items = listOf(
			Item(getString(R.string.stream_menu_resume)) { },
			Item(getString(if(preferences.streamStats) R.string.stream_menu_stats_hide else R.string.stream_menu_stats_show)) {
				preferences.streamStats = !preferences.streamStats
				hud.reset()
				updateHudVisibility()
			},
			Item(getString(R.string.stream_menu_display_mode, modeNames[currentMode] ?: "")) {
				val next = modeOrder[(modeOrder.indexOf(currentMode) + 1) % modeOrder.size]
				binding.displayModeToggle.check(next)
				showStreamMenu()
			},
			Item(getString(R.string.stream_menu_subtitles, when
			{
				!preferences.subtitlesEnabled -> getString(R.string.stream_menu_subtitles_off)
				preferences.subtitlesLanguage == "ar" -> "العربية"
				else -> "کوردی"
			})) {
				// Off → Kurdish Sorani → Arabic → Off
				when
				{
					!preferences.subtitlesEnabled -> { preferences.subtitlesEnabled = true; preferences.subtitlesLanguage = "ckb" }
					preferences.subtitlesLanguage == "ckb" -> preferences.subtitlesLanguage = "ar"
					else -> preferences.subtitlesEnabled = false
				}
				subtitles?.refresh(viewModel.session.state.value == StreamStateConnected)
				showStreamMenu()
			},
			Item(getString(R.string.stream_menu_fix_picture)) { viewModel.session.session?.requestIdr() },
			Item(getString(R.string.stream_menu_settings)) {
				keepSessionOnPause = true
				startActivity(Intent(this, SettingsActivity::class.java))
			},
			Item(getString(R.string.stream_menu_disconnect)) { finish() })

		menuDialog = MaterialAlertDialogBuilder(this)
			.setTitle(R.string.stream_menu_title)
			.setItems(items.map { it.label }.toTypedArray()) { _, which ->
				menuDialog = null
				items[which].action()
			}
			.setOnDismissListener {
				if(menuDialog?.isShowing != true)
					menuDialog = null
				hideSystemUI()
			}
			.create()
		menuDialog?.show()
	}

	override fun onDestroy()
	{
		super.onDestroy()
		uiVisibilityHandler.removeCallbacks(autoReconnectRunnable)
		subtitles?.release()
		subtitleOverlay?.release()
		controlsJob?.cancel()
	}

	private fun reconnect()
	{
		viewModel.session.shutdown()
		viewModel.session.resume()
	}

	private val hideSystemUIRunnable = Runnable { hideSystemUI() }

	private fun showOverlay()
	{
		binding.overlay.isVisible = true
		binding.overlay.animate()
			.alpha(1.0f)
			.setListener(object: AnimatorListenerAdapter()
			{
				override fun onAnimationEnd(animation: Animator)
				{
					binding.overlay.alpha = 1.0f
				}
			})
		uiVisibilityHandler.removeCallbacks(hideSystemUIRunnable)
		uiVisibilityHandler.postDelayed(hideSystemUIRunnable, HIDE_UI_TIMEOUT_MS)
	}

	private fun hideOverlay()
	{
		binding.overlay.animate()
			.alpha(0.0f)
			.setListener(object: AnimatorListenerAdapter()
			{
				override fun onAnimationEnd(animation: Animator)
				{
					binding.overlay.isGone = true
				}
			})
	}

	override fun onWindowFocusChanged(hasFocus: Boolean)
	{
		super.onWindowFocusChanged(hasFocus)
		if(hasFocus)
			hideSystemUI()
	}

	private fun hideSystemUI()
	{
		insetsController.hide(WindowInsetsCompat.Type.systemBars())
	}

	private var dialogContents: DialogContents? = null
	private var dialog: AlertDialog? = null
		set(value)
		{
			field = value
			if(value == null)
				dialogContents = null
		}

	// Short network dropouts (Wi-Fi hiccup, switching to a cable) end the stream with an "unknown"
	// reason: reconnect by itself a few times before asking, like the desktop app
	private var autoReconnects = 0
	private val autoReconnectRunnable = Runnable { reconnect() }

	private fun stateChanged(state: StreamState)
	{
		if(state == StreamStateConnected)
			autoReconnects = 0
		subtitles?.refresh(state == StreamStateConnected)
		if(state is StreamStateQuit && state.reason.value in AUTO_RECONNECT_REASONS && autoReconnects < MAX_AUTO_RECONNECTS && !isFinishing)
		{
			autoReconnects++
			binding.progressBar.isVisible = true
			binding.statusTextView.isVisible = true
			binding.statusTextView.text = getString(R.string.stream_status_reconnecting, autoReconnects, MAX_AUTO_RECONNECTS)
			uiVisibilityHandler.removeCallbacks(autoReconnectRunnable)
			uiVisibilityHandler.postDelayed(autoReconnectRunnable, AUTO_RECONNECT_DELAY_MS)
			return
		}

		val connecting = state == StreamStateConnecting || state == StreamStatePsnConnecting
		binding.progressBar.visibility = if(connecting) View.VISIBLE else View.GONE
		binding.statusTextView.isVisible = connecting
		binding.statusTextView.setText(if(state == StreamStatePsnConnecting) R.string.stream_status_psn else R.string.stream_status_connecting)

		when(state)
		{
			is StreamStateQuit ->
			{
				if(dialogContents != StreamQuitDialog)
				{
					if(state.reason.isError)
					{
						dialog?.dismiss()
						val reasonStr = state.reasonString
						val dialog = MaterialAlertDialogBuilder(this)
							.setMessage(getString(R.string.alert_message_session_quit, state.reason.toString())
									+ (if(reasonStr != null) "\n$reasonStr" else ""))
							.setPositiveButton(R.string.action_reconnect) { _, _ ->
								dialog = null
								reconnect()
							}
							.setOnCancelListener {
								dialog = null
								finish()
							}
							.setNegativeButton(R.string.action_quit_session) { _, _ ->
								dialog = null
								finish()
							}
							.create()
						dialogContents = StreamQuitDialog
						dialog.show()
					}
					else
						finish()
				}
			}

			is StreamStateConnectFailed ->
			{
				if(dialogContents != StreamQuitDialog)
				{
					dialog?.dismiss()
					val dialog = MaterialAlertDialogBuilder(this)
						.setMessage(state.message)
						.setPositiveButton(R.string.action_reconnect) { _, _ ->
							dialog = null
							reconnect()
						}
						.setOnCancelListener {
							dialog = null
							finish()
						}
						.setNegativeButton(R.string.action_quit_session) { _, _ ->
							dialog = null
							finish()
						}
						.create()
					dialogContents = StreamQuitDialog
					dialog.show()
				}
			}

			is StreamStateCreateError ->
			{
				if(dialogContents != CreateErrorDialog)
				{
					dialog?.dismiss()
					val dialog = MaterialAlertDialogBuilder(this)
						.setMessage(getString(R.string.alert_message_session_create_error, state.error.errorCode.toString()))
						.setOnDismissListener {
							dialog = null
							finish()
						}
						.setNegativeButton(R.string.action_quit_session) { _, _ -> }
						.create()
					dialogContents = CreateErrorDialog
					dialog.show()
				}
			}

			is StreamStateLoginPinRequest ->
			{
				if(dialogContents != PinRequestDialog)
				{
					dialog?.dismiss()

					val view = layoutInflater.inflate(R.layout.dialog_login_pin, null)
					val pinEditText = view.findViewById<EditText>(R.id.pinEditText)

					val dialog = MaterialAlertDialogBuilder(this)
						.setMessage(
							if(state.pinIncorrect)
								R.string.alert_message_login_pin_request_incorrect
							else
								R.string.alert_message_login_pin_request)
						.setView(view)
						.setPositiveButton(R.string.action_login_pin_connect) { _, _ ->
							dialog = null
							viewModel.session.setLoginPin(pinEditText.text.toString())
						}
						.setOnCancelListener {
							dialog = null
							finish()
						}
						.setNegativeButton(R.string.action_quit_session) { _, _ ->
							dialog = null
							finish()
						}
						.create()
					dialogContents = PinRequestDialog
					dialog.show()
				}
			}
			else ->{}
		}
	}

	private fun adjustTextureViewAspect(textureView: TextureView)
	{
		val trans = TextureViewTransform(viewModel.session.connectInfo.videoProfile, textureView)
		val resolution = trans.resolutionFor(TransformMode.fromButton(binding.displayModeToggle.checkedButtonId))
		Matrix().also {
			textureView.getTransform(it)
			it.setScale(resolution.width / trans.viewWidth, resolution.height / trans.viewHeight)
			it.postTranslate((trans.viewWidth - resolution.width) * 0.5f, (trans.viewHeight - resolution.height) * 0.5f)
			textureView.setTransform(it)
		}
	}

	private fun adjustSurfaceViewAspect()
	{
		val videoProfile = viewModel.session.connectInfo.videoProfile
		binding.aspectRatioLayout.aspectRatio = videoProfile.width.toFloat() / videoProfile.height.toFloat()
		binding.aspectRatioLayout.mode = TransformMode.fromButton(binding.displayModeToggle.checkedButtonId)
	}

	private fun adjustStreamViewAspect() = adjustSurfaceViewAspect()

	override fun dispatchKeyEvent(event: KeyEvent) = viewModel.input.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
	override fun onGenericMotionEvent(event: MotionEvent) = viewModel.input.onGenericMotionEvent(event) || super.onGenericMotionEvent(event)
}

enum class TransformMode
{
	FIT,
	STRETCH,
	ZOOM;

	companion object
	{
		fun fromButton(displayModeButtonId: Int)
			= when (displayModeButtonId)
			{
				R.id.display_mode_stretch_button -> STRETCH
				R.id.display_mode_zoom_button -> ZOOM
				else -> FIT
			}
	}
}

class TextureViewTransform(private val videoProfile: ConnectVideoProfile, private val textureView: TextureView)
{
	private val contentWidth : Float get() = videoProfile.width.toFloat()
	private val contentHeight : Float get() = videoProfile.height.toFloat()
	val viewWidth : Float get() = textureView.width.toFloat()
	val viewHeight : Float get() = textureView.height.toFloat()
	private val contentAspect : Float get() =  contentHeight / contentWidth

	fun resolutionFor(mode: TransformMode): Resolution
		= when(mode)
		{
			TransformMode.STRETCH -> strechedResolution
			TransformMode.ZOOM -> zoomedResolution
			TransformMode.FIT -> normalResolution
		}

	private val strechedResolution get() = Resolution(viewWidth, viewHeight)

	private val zoomedResolution get() =
		if(viewHeight > viewWidth * contentAspect)
		{
			val zoomFactor = viewHeight / contentHeight
			Resolution(contentWidth * zoomFactor, viewHeight)
		}
		else
		{
			val zoomFactor = viewWidth / contentWidth
			Resolution(viewWidth, contentHeight * zoomFactor)
		}

	private val normalResolution get() =
		if(viewHeight > viewWidth * contentAspect)
			Resolution(viewWidth, viewWidth * contentAspect)
		else
			Resolution(viewHeight / contentAspect, viewHeight)
}


data class Resolution(val width: Float, val height: Float)
