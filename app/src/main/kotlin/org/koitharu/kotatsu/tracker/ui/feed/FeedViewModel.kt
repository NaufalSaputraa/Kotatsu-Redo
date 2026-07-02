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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.MangaState

private const val PAGE_SIZE = 20

@HiltViewModel
class FeedViewModel @Inject constructor(
	@dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context,
	private val settings: AppSettings,
	private val sourcesRepository: MangaSourcesRepository,
	private val getPopularFeedUseCase: GetPopularFeedUseCase,
	private val quickFilter: UpdatesListQuickFilter,
) : BaseViewModel(), QuickFilterListener by quickFilter {

	private val cacheFile = java.io.File(context.cacheDir, "feed_cache.json")
	private val currentPage = AtomicInteger(0)
	private val isReady = AtomicBoolean(false)
	private var loadJob: Job? = null

	val timeRange = MutableStateFlow(GetPopularFeedUseCase.TimeRange.WEEKLY)
	val mangaList = MutableStateFlow<List<Pair<Manga, MangaSource>>>(emptyList())
	val failedSources = MutableStateFlow<List<MangaSource>>(emptyList())
	val isFeedLoading = MutableStateFlow(false)
	val hasNextPage = MutableStateFlow(true)

	val isHeaderEnabled = MutableStateFlow(false)
	val isRunning = isFeedLoading
	val onActionDone = MutableEventFlow<ReversibleAction>()

	val content = combine(
		mangaList,
		isFeedLoading,
	) { list: List<Pair<Manga, MangaSource>>, loading: Boolean ->
		buildList<ListModel> {
			if (list.isEmpty() && !loading) {
				add(
					EmptyState(
						icon = R.drawable.ic_empty_feed,
						textPrimary = R.string.text_empty_holder_primary,
						textSecondary = R.string.text_feed_holder,
						actionStringRes = 0,
					)
				)
			} else {
				isReady.set(true)
				list.forEach { (manga, _) ->
					add(
						FeedItem(
							id = manga.id,
							override = null,
							manga = manga,
							count = 0,
							isNew = false
						)
					)
				}
				if (loading) {
					add(LoadingState())
				}
			}
		}
	}.catch { e ->
		android.util.Log.e("FeedViewModel", "Error in content flow", e)
		val detailMessage = "${e.javaClass.simpleName}: ${e.message}\n" +
				e.stackTrace.take(3).joinToString("\n") { "at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})" }
		val debugException = RuntimeException(detailMessage, e)
		emit(listOf(debugException.toErrorState(canRetry = true)))
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, listOf(LoadingState()))

	init {
		launchJob(Dispatchers.IO) {
			val cached = loadFeedFromCache()
			if (cached.isNotEmpty()) {
				mangaList.value = cached
			}
		}

		combine(
			settings.observeAsFlow(AppSettings.KEY_FEED_SOURCES) { feedSources },
			timeRange
		) { sources, range ->
			Pair(sources, range)
		}.onEach { (sources, range) ->
			resetAndLoad(sources, range)
		}.launchIn(viewModelScope)
	}

	fun requestMoreItems() {
		if (isReady.compareAndSet(true, false) && hasNextPage.value && !isFeedLoading.value) {
			val nextPage = currentPage.incrementAndGet()
			loadPage(settings.feedSources, timeRange.value, nextPage)
		}
	}

	fun update() {
		resetAndLoad(settings.feedSources, timeRange.value)
	}

	private fun resetAndLoad(sources: Set<String>, range: GetPopularFeedUseCase.TimeRange) {
		launchJob(Dispatchers.Default) {
			loadJob?.cancelAndJoin()
			mangaList.value = emptyList()
			failedSources.value = emptyList()
			currentPage.set(0)
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

			mangaList.update { old ->
				if (page == 0) result.items else old + result.items
			}

			if (page == 0 && result.items.isNotEmpty()) {
				launchJob(Dispatchers.IO) {
					saveFeedToCache(result.items)
				}
			}

			failedSources.value = result.failedSources
			hasNextPage.value = result.items.isNotEmpty()
			isReady.set(true)
			isFeedLoading.value = false
		}
	}

	private fun saveFeedToCache(list: List<Pair<Manga, MangaSource>>) {
		try {
			val jsonArray = JSONArray()
			for ((manga, source) in list) {
				val jsonObject = JSONObject().apply {
					put("id", manga.id)
					put("title", manga.title)
					put("url", manga.url)
					put("publicUrl", manga.publicUrl)
					put("rating", manga.rating.toDouble())
					put("contentRating", manga.contentRating?.name)
					put("coverUrl", manga.coverUrl)
					put("largeCoverUrl", manga.largeCoverUrl)
					put("state", manga.state?.name)
					put("authors", JSONArray(manga.authors))
					put("source", source.name)
				}
				jsonArray.put(jsonObject)
			}
			cacheFile.writeText(jsonArray.toString())
		} catch (e: Exception) {
			// Ignore
		}
	}

	private fun loadFeedFromCache(): List<Pair<Manga, MangaSource>> {
		if (!cacheFile.exists()) return emptyList()
		return try {
			val jsonArray = JSONArray(cacheFile.readText())
			val result = mutableListOf<Pair<Manga, MangaSource>>()
			for (i in 0 until jsonArray.length()) {
				val obj = jsonArray.getJSONObject(i)
				val authors = mutableSetOf<String>()
				val authorsArray = obj.optJSONArray("authors")
				if (authorsArray != null) {
					for (j in 0 until authorsArray.length()) {
						authors.add(authorsArray.getString(j))
					}
				}
				val sourceName = obj.getString("source")
				val source = org.koitharu.kotatsu.core.model.MangaSource(sourceName)
				val manga = Manga(
					id = obj.getLong("id"),
					title = obj.getString("title"),
					altTitles = emptySet(),
					url = obj.getString("url"),
					publicUrl = obj.getString("publicUrl"),
					rating = obj.optDouble("rating", -1.0).toFloat(),
					contentRating = obj.optString("contentRating").takeIf { it.isNotEmpty() }?.let { runCatching { ContentRating.valueOf(it) }.getOrNull() },
					coverUrl = obj.optString("coverUrl").takeIf { it.isNotEmpty() },
					largeCoverUrl = obj.optString("largeCoverUrl").takeIf { it.isNotEmpty() },
					state = obj.optString("state").takeIf { it.isNotEmpty() }?.let { runCatching { MangaState.valueOf(it) }.getOrNull() },
					authors = authors,
					source = source,
					tags = emptySet(),
					chapters = null
				)
				result.add(Pair(manga, source))
			}
			result
		} catch (e: Exception) {
			emptyList()
		}
	}
}
