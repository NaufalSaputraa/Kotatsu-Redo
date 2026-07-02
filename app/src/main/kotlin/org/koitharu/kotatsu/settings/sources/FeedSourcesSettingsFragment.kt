package org.koitharu.kotatsu.settings.sources

import android.os.Bundle
import androidx.preference.CheckBoxPreference
import androidx.preference.PreferenceCategory
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.runBlocking
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

		val category = PreferenceCategory(context).apply {
			title = getString(R.string.select_feed_sources)
			screen.addPreference(this)
		}

		val enabledSources = runBlocking {
			sourcesRepository.getEnabledSources()
		}

		val selectedSources = settings.feedSources.toMutableSet()
		if (selectedSources.isEmpty()) {
			val pinned = runBlocking { sourcesRepository.getPinnedSources() }
			if (pinned.isNotEmpty()) {
				selectedSources.addAll(pinned.map { it.unwrap().name })
			} else {
				selectedSources.addAll(enabledSources.take(5).map { it.unwrap().name })
			}
			settings.feedSources = selectedSources
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
