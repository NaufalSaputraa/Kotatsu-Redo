package org.koitharu.kotatsu.tracker.ui.feed

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.unwrap
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.ui.util.ReversibleAction
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.list.domain.QuickFilterListener
import org.koitharu.kotatsu.list.ui.model.EmptyState
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.LoadingState
import org.koitharu.kotatsu.list.ui.model.toErrorState
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.tracker.domain.GetPopularFeedUseCase
import org.koitharu.kotatsu.tracker.domain.UpdatesListQuickFilter
import org.koitharu.kotatsu.tracker.ui.feed.model.FeedItem
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

private const val PAGE_SIZE = 20

@HiltViewModel
class FeedViewModel @Inject constructor(
	private val settings: AppSettings,
	private val sourcesRepository: MangaSourcesRepository,
	private val getPopularFeedUseCase: GetPopularFeedUseCase,
	private val quickFilter: UpdatesListQuickFilter,
) : BaseViewModel(), QuickFilterListener by quickFilter {

	private val currentPage = MutableStateFlow(0)
	private val isReady = AtomicBoolean(false)
	private var loadJob: Job? = null

	val timeRange = MutableStateFlow(GetPopularFeedUseCase.TimeRange.WEEKLY)
	val mangaList = MutableStateFlow<List<Pair<Manga, MangaSource>>>(emptyList())
	val failedSources = MutableStateFlow<List<MangaSource>>(emptyList())
	val isFeedLoading = MutableStateFlow(false)
	val hasNextPage = MutableStateFlow(true)

	// Keep these to satisfy bindings in FeedFragment and FeedMenuProvider
	val isHeaderEnabled = MutableStateFlow(false)
	val isRunning = isFeedLoading
	val onActionDone = MutableEventFlow<ReversibleAction>()

	@Suppress("USELESS_CAST")
	val content = combine(
		mangaList,
		isFeedLoading,
	) { list, loading ->
		val result = ArrayList<ListModel>((list.size * 1.4).toInt().coerceAtLeast(3))
		if (list.isEmpty() && !loading) {
			result += EmptyState(
				icon = R.drawable.ic_empty_feed,
				textPrimary = R.string.text_empty_holder_primary,
				textSecondary = R.string.text_feed_holder,
				actionStringRes = 0,
			)
		} else {
			isReady.set(true)
			list.forEach { (manga, _) ->
				result += FeedItem(
					id = manga.id,
					override = null,
					manga = manga,
					count = 0,
					isNew = false
				)
			}
			if (loading) {
				result += LoadingState()
			}
		}
		result as List<ListModel>
	}.catch { e ->
		emit(listOf(e.toErrorState(canRetry = false)))
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, listOf(LoadingState()))

	init {
		combine(
			settings.observeAsFlow(AppSettings.KEY_FEED_SOURCES) { feedSources },
			timeRange
		) { sources, range ->
			Pair(sources, range)
		}.onEach { (sources, range) ->
			resetAndLoad(sources, range)
		}.launchIn(viewModelScope)

		currentPage.onEach { page ->
			if (page > 0) {
				loadPage(settings.feedSources, timeRange.value, page)
			}
		}.launchIn(viewModelScope)
	}

	fun requestMoreItems() {
		if (isReady.compareAndSet(true, false) && hasNextPage.value && !isFeedLoading.value) {
			currentPage.value += 1
		}
	}

	fun update() {
		resetAndLoad(settings.feedSources, timeRange.value)
	}

	fun setHeaderEnabled(value: Boolean) {
		// No-op
	}

	fun clearFeed(clearCounters: Boolean) {
		// No-op
	}

	fun onItemClick(item: FeedItem) {
		// No-op
	}

	private fun resetAndLoad(sources: Set<String>, range: GetPopularFeedUseCase.TimeRange) {
		launchJob(Dispatchers.Default) {
			loadJob?.cancelAndJoin()
			mangaList.value = emptyList()
			failedSources.value = emptyList()
			currentPage.value = 0
			hasNextPage.value = true
			loadPage(sources, range, 0)
		}
	}

	private fun loadPage(sources: Set<String>, range: GetPopularFeedUseCase.TimeRange, page: Int) {
		loadJob = launchJob(Dispatchers.Default) {
			var activeSources = sources
			if (activeSources.isEmpty()) {
				val enabled = sourcesRepository.getEnabledSources()
				val top = sourcesRepository.getPinnedSources()
				activeSources = if (top.isNotEmpty()) {
					top.map { it.unwrap().name }.toSet()
				} else {
					enabled.take(5).map { it.unwrap().name }.toSet()
				}
				if (activeSources.isNotEmpty()) {
					settings.feedSources = activeSources
				} else {
					return@launchJob
				}
			}

			isFeedLoading.value = true
			val result = getPopularFeedUseCase(activeSources, page, PAGE_SIZE, range)
			
			val currentList = ArrayList(mangaList.value)
			if (page == 0) {
				currentList.clear()
			}
			currentList.addAll(result.items)
			mangaList.value = currentList
			
			failedSources.value = result.failedSources
			hasNextPage.value = result.items.isNotEmpty()
			isReady.set(true)
			isFeedLoading.value = false
		}
	}
}
