package org.koitharu.kotatsu.tracker.ui.feed.adapter

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.databinding.ItemFeedSkeletonBinding
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.LoadingState

fun feedLoadingStateAD() = adapterDelegateViewBinding<LoadingState, ListModel, ItemFeedSkeletonBinding>(
	{ inflater, parent -> ItemFeedSkeletonBinding.inflate(inflater, parent, false) },
) {
	val animator = ObjectAnimator.ofFloat(binding.skeletonRoot, "alpha", 0.4f, 1.0f).apply {
		duration = 1000
		repeatMode = ValueAnimator.REVERSE
		repeatCount = ValueAnimator.INFINITE
	}

	bind {
		animator.start()
	}

	onViewDetachedFromWindow {
		animator.cancel()
	}
}
