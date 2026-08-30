package io.github.mattpvaughn.chronicle.features.library

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.databinding.ListItemSearchResultAudiobookBinding
import io.github.mattpvaughn.chronicle.features.library.LibraryFragment.AudiobookClick
import io.github.mattpvaughn.chronicle.views.bindImageRounded

class AudiobookSearchAdapter(private val audiobookClick: AudiobookClick) : ListAdapter<Audiobook, AudiobookSearchAdapter.ViewHolder>(
  AudiobookDiffCallback(),
) {
  private var serverConnected: Boolean = false

  override fun onCreateViewHolder(
    parent: ViewGroup,
    viewType: Int,
  ): ViewHolder {
    return ViewHolder.from(parent)
  }

  override fun onBindViewHolder(
    holder: ViewHolder,
    position: Int,
  ) {
    holder.bind(getItem(position), audiobookClick, serverConnected)
  }

  fun setServerConnected(serverConnected: Boolean) {
    this.serverConnected = serverConnected
    notifyDataSetChanged()
  }

  class ViewHolder private constructor(val binding: ListItemSearchResultAudiobookBinding) :
    RecyclerView.ViewHolder(binding.root) {
      fun bind(
        audiobook: Audiobook,
        searchResultClick: AudiobookClick,
        isConnected: Boolean,
      ) {
        // Was binding expressions in list_item_search_result_audiobook.xml.
        binding.searchResultRoot.setOnClickListener { searchResultClick.onClick(audiobook) }
        binding.title.text = audiobook.title
        binding.author.text = audiobook.author
        binding.bookCoverImg.contentDescription = audiobook.title
        bindImageRounded(binding.bookCoverImg, audiobook.thumb, isConnected)
        binding.notPlayedDogEar.isVisible =
          audiobook.viewCount == 0L && audiobook.progress == 0L
      }

      companion object {
        fun from(viewGroup: ViewGroup): ViewHolder {
          val inflater = LayoutInflater.from(viewGroup.context)
          val binding =
            ListItemSearchResultAudiobookBinding.inflate(inflater, viewGroup, false)
          return ViewHolder(binding)
        }
      }
    }
}
