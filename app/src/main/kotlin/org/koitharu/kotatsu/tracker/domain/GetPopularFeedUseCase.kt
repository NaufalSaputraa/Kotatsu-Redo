package org.koitharu.kotatsu.tracker.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.SortOrder
import javax.inject.Inject

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

	suspend operator fun invoke(
		sources: Set<String>,
		page: Int,
		pageSize: Int,
		timeRange: TimeRange
	): FeedResult = withContext(Dispatchers.IO) {
		val activeSources = sources.mapNotNull { name ->
			MangaParserSource.entries.find { it.name == name }
		}

		if (activeSources.isEmpty()) {
			return@withContext FeedResult(emptyList(), emptyList())
		}

		val requestedSortOrder = when (timeRange) {
			TimeRange.DAILY -> SortOrder.POPULARITY_TODAY
			TimeRange.WEEKLY -> SortOrder.POPULARITY_WEEK
			TimeRange.MONTHLY -> SortOrder.POPULARITY_MONTH
		}

		val deferredResults = activeSources.map { source ->
			async {
				val repository = mangaRepositoryFactory.create(source)
				val order = if (requestedSortOrder in repository.sortOrders) {
					requestedSortOrder
				} else {
					SortOrder.POPULARITY
				}
				runCatching {
					// Retrieve popularity page list from parser
					repository.getList(page * pageSize, order, null)
				}.mapCatching { list ->
					list.map { it to source }
				}
			}
		}

		val results = deferredResults.awaitAll()
		val items = mutableListOf<Pair<Manga, MangaSource>>()
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
