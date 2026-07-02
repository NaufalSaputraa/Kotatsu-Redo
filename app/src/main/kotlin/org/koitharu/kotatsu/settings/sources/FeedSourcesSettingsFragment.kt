package org.koitharu.kotatsu.settings.sources

import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.preference.CheckBoxPreference
import androidx.preference.PreferenceCategory
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.model.unwrap
import org.koitharu.kotatsu.core.ui.BasePreferenceFragment
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import javax.inject.Inject

@AndroidEntryPoint
class FeedSourcesSettingsFragment : BasePreferenceFragment(R.string.feed_sources) {

	@Inject
	lateinit var sourcesRepository: MangaSourcesRepository

	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
		val context = preferenceManager.context
		val screen = preferenceManager.createPreferenceScreen(context)
		preferenceScreen = screen
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		viewLifecycleOwner.lifecycleScope.launch {
			loadSources()
		}
	}

	private suspend fun loadSources() {
		val enabledSources = withContext(Dispatchers.IO) {
			sourcesRepository.getEnabledSources()
		}

		val selectedSources = settings.feedSources.toMutableSet()
		if (selectedSources.isEmpty()) {
			val pinned = withContext(Dispatchers.IO) {
				sourcesRepository.getPinnedSources()
			}
			if (pinned.isNotEmpty()) {
				selectedSources.addAll(pinned.map { it.unwrap().name })
			} else {
				selectedSources.addAll(enabledSources.take(5).map { it.unwrap().name })
			}
			settings.feedSources = selectedSources
		}

		val context = preferenceManager.context
		val category = PreferenceCategory(context).apply {
			title = getString(R.string.select_feed_sources)
			preferenceScreen.addPreference(this)
		}

		for (source in enabledSources) {
			val parserSource = source.unwrap() as? MangaParserSource ?: continue
			val pref = CheckBoxPreference(context).apply {
				key = "feed_source_${parserSource.name}"
				title = parserSource.getTitle(context)
				isChecked = parserSource.name in selectedSources
				setOnPreferenceChangeListener { _, newValue ->
					val currentSelected = settings.feedSources.toMutableSet()
					val isCheckedValue = newValue as Boolean
					if (isCheckedValue) {
						currentSelected.add(parserSource.name)
					} else {
						currentSelected.remove(parserSource.name)
					}
					settings.feedSources = currentSelected
					true
				}
			}
			category.addPreference(pref)
		}
	}
}
