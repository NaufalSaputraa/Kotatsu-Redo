package org.koitharu.kotatsu.tracker.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.SortOrder
import javax.inject.Inject

private const val MAX_CONCURRENT_REQUESTS = 4

class GetPopularFeedUseCase @Inject constructor(
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val sourcesRepository: MangaSourcesRepository,
) {

	enum class TimeRange {
		DAILY,
		WEEKLY,
		MONTHLY
	}

	data class FeedResult(
		val items: List<Pair<Manga, MangaSource>>,
		val failedSources: List<MangaSource>
	)

	private val sourceLookup: Map<String, MangaParserSource> by lazy {
		MangaParserSource.entries.associateBy { it.name }
	}

	suspend operator fun invoke(
		sources: Set<String>,
		page: Int,
		pageSize: Int,
		timeRange: TimeRange
	): FeedResult = withContext(Dispatchers.IO) {
		val activeSources = sources.mapNotNull { name -> sourceLookup[name] }

		if (activeSources.isEmpty()) {
			return@withContext FeedResult(emptyList(), emptyList())
		}

		val requestedSortOrder = when (timeRange) {
			TimeRange.DAILY -> SortOrder.POPULARITY_TODAY
			TimeRange.WEEKLY -> SortOrder.POPULARITY_WEEK
			TimeRange.MONTHLY -> SortOrder.POPULARITY_MONTH
		}

		val semaphore = Semaphore(MAX_CONCURRENT_REQUESTS)
		val deferredResults = activeSources.map { source ->
			async {
				semaphore.withPermit {
					val repository = mangaRepositoryFactory.create(source)
					val order = if (requestedSortOrder in repository.sortOrders) {
						requestedSortOrder
					} else {
						SortOrder.POPULARITY
					}
					runCatching {
						repository.getList(page * pageSize, order, null)
					}.mapCatching { list ->
						list.map { it to source }
					}
				}
			}
		}

		val results = deferredResults.awaitAll()
		val failedSources = mutableListOf<MangaSource>()
		val successfulLists = mutableListOf<List<Pair<Manga, MangaSource>>>()

		for (i in results.indices) {
			val res = results[i]
			val source = activeSources[i]
			if (res.isSuccess) {
				successfulLists.add(res.getOrThrow())
			} else {
				failedSources.add(source)
			}
		}

		// Interleave popular manga results across successful sources
		val maxLen = successfulLists.maxOfOrNull { it.size } ?: 0
		val items = ArrayList<Pair<Manga, MangaSource>>(maxLen * successfulLists.size)
		for (idx in 0 until maxLen) {
			for (list in successfulLists) {
				if (idx < list.size) {
					items.add(list[idx])
				}
			}
		}

		FeedResult(items, failedSources)
	}
}
