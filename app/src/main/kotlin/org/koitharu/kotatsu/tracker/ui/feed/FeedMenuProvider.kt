package org.koitharu.kotatsu.tracker.ui.feed

import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.core.view.MenuProvider
import org.koitharu.kotatsu.R

class FeedMenuProvider(
	private val viewModel: FeedViewModel,
	private val onFeedSourcesClick: () -> Unit,
) : MenuProvider {

	override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
		menuInflater.inflate(R.menu.opt_feed, menu)
	}

	override fun onMenuItemSelected(menuItem: MenuItem): Boolean = when (menuItem.itemId) {
		R.id.action_update -> {
			viewModel.update()
			true
		}

		R.id.action_feed_sources -> {
			onFeedSourcesClick()
			true
		}

		else -> false
	}
}
