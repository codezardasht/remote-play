// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.PopupMenu
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.metallic.chiaki.R
import com.metallic.chiaki.common.DiscoveredDisplayHost
import com.metallic.chiaki.common.DisplayHost
import com.metallic.chiaki.common.ManualDisplayHost
import com.metallic.chiaki.common.ext.inflate
import com.metallic.chiaki.databinding.ItemDisplayHostBinding
import com.metallic.chiaki.lib.DiscoveryHost

class DisplayHostDiffCallback(val old: List<DisplayHost>, val new: List<DisplayHost>): DiffUtil.Callback()
{
	override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) = (old[oldItemPosition] == new[newItemPosition])
	override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) = (old[oldItemPosition] == new[newItemPosition])
	override fun getOldListSize() = old.size
	override fun getNewListSize() = new.size
}

class DisplayHostRecyclerViewAdapter(
	val horizontal: Boolean,
	val clickCallback: (DisplayHost) -> Unit,
	val wakeupCallback: (DisplayHost) -> Unit,
	val editCallback: (DisplayHost) -> Unit,
	val deleteCallback: (DisplayHost) -> Unit,
	val psnCallback: (DisplayHost) -> Unit
): RecyclerView.Adapter<DisplayHostRecyclerViewAdapter.ViewHolder>()
{
	var hosts: List<DisplayHost> = listOf()
		set(value)
		{
			val diff = DiffUtil.calculateDiff(DisplayHostDiffCallback(field, value))
			field = value
			diff.dispatchUpdatesTo(this)
		}

	class ViewHolder(val binding: ItemDisplayHostBinding): RecyclerView.ViewHolder(binding.root)

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder
	{
		val binding = ItemDisplayHostBinding.inflate(LayoutInflater.from(parent.context), parent, false)
		if(horizontal)
		{
			// TV: a row of large cards, like the console carousel on the desktop app
			val dp = parent.resources.displayMetrics.density
			binding.root.layoutParams = binding.root.layoutParams.also {
				it.width = (420 * dp).toInt()
				it.height = (300 * dp).toInt()
			}
		}
		binding.root.setOnFocusChangeListener { v, hasFocus ->
			v.animate().scaleX(if(hasFocus) 1.05f else 1f).scaleY(if(hasFocus) 1.05f else 1f).setDuration(180).start()
		}
		return ViewHolder(binding)
	}

	override fun getItemCount() = hosts.count()

	override fun onBindViewHolder(holder: ViewHolder, position: Int)
	{
		val context = holder.itemView.context
		val host = hosts[position]
		holder.binding.also {
			it.nameTextView.text = host.name
			it.hostTextView.text = context.getString(R.string.display_host_host, host.host)
			val id = host.id
			it.idTextView.text =
				if(id != null)
					context.getString(
						if(host.isRegistered)
							R.string.display_host_id_registered
						else
							R.string.display_host_id_unregistered,
						id)
				else
					""
			it.bottomInfoTextView.text = (host as? DiscoveredDisplayHost)?.discoveredHost?.let { discoveredHost ->
				if(discoveredHost.runningAppName != null || discoveredHost.runningAppTitleid != null)
					context.getString(R.string.display_host_app_title_id, discoveredHost.runningAppName ?: "", discoveredHost.runningAppTitleid ?: "")
				else
					""
			} ?: ""
			it.discoveredIndicatorLayout.visibility = if(host is DiscoveredDisplayHost) View.VISIBLE else View.GONE
			it.stateIndicatorImageView.setImageResource(
				when
				{
					host is DiscoveredDisplayHost -> when(host.discoveredHost.state)
					{
						DiscoveryHost.State.STANDBY -> if(host.isPS5) R.drawable.ic_console_ps5_standby else R.drawable.ic_console_standby
						DiscoveryHost.State.READY -> if(host.isPS5) R.drawable.ic_console_ps5_ready else R.drawable.ic_console_ready
						else -> if(host.isPS5) R.drawable.ic_console_ps5 else R.drawable.ic_console
					}
					host.isPS5 -> R.drawable.ic_console_ps5
					else -> R.drawable.ic_console
				}
			)
			it.root.setOnClickListener { clickCallback(host) }

			val canWakeup = host.registeredHost != null
			val canEditDelete = host is ManualDisplayHost
			if(canWakeup || canEditDelete)
			{
				it.menuButton.isVisible = true
				// On TV the small menu button is hard to reach: long-press OK on the card opens the same menu
				it.root.setOnLongClickListener { _ -> it.menuButton.performClick() }
				it.menuButton.setOnClickListener { _ ->
					val menu = PopupMenu(context, it.menuButton)
					menu.menuInflater.inflate(R.menu.display_host, menu.menu)
					menu.menu.findItem(R.id.action_wakeup).isVisible = canWakeup
					menu.menu.findItem(R.id.action_connect_psn).isVisible = canWakeup
					menu.menu.findItem(R.id.action_edit).isVisible = canEditDelete
					menu.menu.findItem(R.id.action_delete).isVisible = canEditDelete
					menu.setOnMenuItemClickListener { menuItem ->
						when(menuItem.itemId)
						{
							R.id.action_wakeup -> wakeupCallback(host)
							R.id.action_connect_psn -> psnCallback(host)
							R.id.action_edit -> editCallback(host)
							R.id.action_delete -> deleteCallback(host)
							else -> return@setOnMenuItemClickListener false
						}
						true
					}
					menu.show()
				}
			}
			else
			{
				it.menuButton.isGone = true
				it.menuButton.setOnClickListener(null)
				it.root.setOnLongClickListener(null)
			}
		}
	}
}