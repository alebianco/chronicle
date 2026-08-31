package io.github.mattpvaughn.chronicle.features.library

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo.Companion.VIEW_STYLE_COVER_GRID
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo.Companion.VIEW_STYLE_DETAILS_LIST
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo.Companion.VIEW_STYLE_TEXT_LIST
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.databinding.GridItemAudiobookBinding
import io.github.mattpvaughn.chronicle.databinding.ListItemAudiobookTextOnlyBinding
import io.github.mattpvaughn.chronicle.databinding.ListItemAudiobookWithDetailsBinding
import io.github.mattpvaughn.chronicle.views.bindImageRounded

class AudiobookAdapter(
  initialViewStyle: String,
  private val isVertical: Boolean,
  private val isSquare: Boolean,
  private val audiobookClick: LibraryFragment.AudiobookClick,
) : ListAdapter<Audiobook, RecyclerView.ViewHolder>(AudiobookDiffCallback()) {
  private val COVER_GRID = 1
  private val TEXT_ONLY = 2
  private val DETAILS = 3
  var viewStyle: String = initialViewStyle
    set(value) {
      viewStyleInt =
        when (value) {
          VIEW_STYLE_COVER_GRID -> COVER_GRID
          VIEW_STYLE_TEXT_LIST -> TEXT_ONLY
          VIEW_STYLE_DETAILS_LIST -> DETAILS
          else -> throw IllegalStateException("Unknown view style")
        }
      notifyDataSetChanged()
      field = value
    }
  private var viewStyleInt: Int =
    when (initialViewStyle) {
      VIEW_STYLE_COVER_GRID -> COVER_GRID
      VIEW_STYLE_TEXT_LIST -> TEXT_ONLY
      VIEW_STYLE_DETAILS_LIST -> DETAILS
      else -> throw IllegalStateException("Unknown view style")
    }

  private var serverConnected: Boolean = false

  // RecyclerView requires a Long, so a String id is hashed rather than parsed: `toLong()`
  // throws NumberFormatException on exactly the non-numeric ids cu-71 exists to allow.
  // A stable-id collision only costs an animation, not correctness.
  override fun getItemId(position: Int): Long {
    return getItem(position).id.hashCode().toLong()
  }

  override fun getItemViewType(position: Int): Int {
    return viewStyleInt
  }

  override fun onCreateViewHolder(
    parent: ViewGroup,
    viewType: Int,
  ): RecyclerView.ViewHolder {
    return when (viewType) {
      COVER_GRID -> ViewHolder.from(parent, isVertical, isSquare)
      TEXT_ONLY -> TextOnlyViewHolder.from(parent)
      DETAILS -> DetailsStyleViewHolder.from(parent, isSquare)
      else -> throw IllegalStateException("Unknown view type")
    }
  }

  override fun onBindViewHolder(
    holder: RecyclerView.ViewHolder,
    position: Int,
  ) {
    when (holder) {
      is ViewHolder -> {
        holder.bind(getItem(position), audiobookClick, serverConnected)
      }
      is TextOnlyViewHolder -> {
        holder.bind(getItem(position), audiobookClick)
      }
      is DetailsStyleViewHolder -> {
        holder.bind(getItem(position), audiobookClick, serverConnected)
      }
      else -> throw IllegalStateException("Unknown view type")
    }
  }

  fun setServerConnected(serverConnected: Boolean) {
    this.serverConnected = serverConnected
    notifyDataSetChanged()
  }

  class ViewHolder(
    val binding: GridItemAudiobookBinding,
    private val isVertical: Boolean,
    private val isSquare: Boolean,
  ) : RecyclerView.ViewHolder(binding.root) {
    fun bind(
      audiobook: Audiobook,
      audiobookClick: LibraryFragment.AudiobookClick,
      serverConnected: Boolean,
    ) {
      // Was binding expressions in grid_item_audiobook.xml.
      setSquareAspectRatio(binding.gridItemRoot, isSquare)
      overrideWidth(
        binding.gridItemRoot,
        if (isVertical) {
          binding.root.resources.getDimension(R.dimen.audiobook_match_parent)
        } else {
          binding.root.resources.getDimension(R.dimen.audiobook_item_width)
        },
      )
      binding.gridItemRoot.setOnClickListener { audiobookClick.onClick(audiobook) }
      binding.title.text = audiobook.title
      binding.author.text = audiobook.author
      binding.bookCoverImg.contentDescription = audiobook.title
      bindImageRounded(binding.bookCoverImg, audiobook.thumb, serverConnected)
      binding.notPlayedDogEar.isVisible = audiobook.viewCount == 0L && audiobook.progress == 0L
      binding.bookProgress.max = audiobook.duration.toInt()
      binding.bookProgress.progress = audiobook.progress.toInt()
      binding.bookProgress.isVisible = audiobook.progress > 0L
    }

    companion object {
      fun from(
        viewGroup: ViewGroup,
        isVertical: Boolean,
        isSquare: Boolean,
      ): ViewHolder {
        val inflater = LayoutInflater.from(viewGroup.context)
        val binding = GridItemAudiobookBinding.inflate(inflater, viewGroup, false)
        return ViewHolder(binding, isVertical, isSquare)
      }
    }
  }

  class TextOnlyViewHolder(val binding: ListItemAudiobookTextOnlyBinding) :
    RecyclerView.ViewHolder(binding.root) {
    fun bind(
      audiobook: Audiobook,
      audiobookClick: LibraryFragment.AudiobookClick,
    ) {
      // Was binding expressions in list_item_audiobook_text_only.xml.
      binding.textOnlyItemRoot.setOnClickListener { audiobookClick.onClick(audiobook) }
      binding.title.text = audiobook.title
      binding.author.text = audiobook.author
      binding.bookProgress.text = formatProgress(audiobook)
    }

    companion object {
      fun from(viewGroup: ViewGroup): TextOnlyViewHolder {
        val inflater = LayoutInflater.from(viewGroup.context)
        val binding =
          ListItemAudiobookTextOnlyBinding.inflate(inflater, viewGroup, false)
        return TextOnlyViewHolder(binding)
      }
    }
  }
}

/**
 * "<elapsed>/<total>" for the book progress label — was a `DateUtils.formatElapsedTime`
 * expression in list_item_audiobook_text_only.xml and list_item_audiobook_with_details.xml.
 */
private fun formatProgress(audiobook: Audiobook): String {
  return DateUtils.formatElapsedTime(audiobook.progress / 1000) + "/" +
    DateUtils.formatElapsedTime(audiobook.duration / 1000)
}

class DetailsStyleViewHolder(
  val binding: ListItemAudiobookWithDetailsBinding,
  val isSquare: Boolean,
) : RecyclerView.ViewHolder(binding.root) {
  fun bind(
    audiobook: Audiobook,
    audiobookClick: LibraryFragment.AudiobookClick,
    serverConnected: Boolean,
  ) {
    // Was binding expressions in list_item_audiobook_with_details.xml.
    setSquareAspectRatio(binding.detailsItemRoot, isSquare)
    binding.detailsItemRoot.setOnClickListener { audiobookClick.onClick(audiobook) }
    binding.title.text = audiobook.title
    binding.author.text = audiobook.author
    binding.bookProgressString.text = formatProgress(audiobook)
    binding.bookCoverImg.contentDescription = audiobook.title
    bindImageRounded(binding.bookCoverImg, audiobook.thumb, serverConnected)
    binding.notPlayedDogEar.isVisible = audiobook.viewCount == 0L && audiobook.progress == 0L
    binding.bookProgress.max = audiobook.duration.toInt()
    binding.bookProgress.progress = audiobook.progress.toInt()
    binding.bookProgress.isVisible = audiobook.progress > 0L
  }

  companion object {
    fun from(
      viewGroup: ViewGroup,
      isSquare: Boolean,
    ): DetailsStyleViewHolder {
      val inflater = LayoutInflater.from(viewGroup.context)
      val binding =
        ListItemAudiobookWithDetailsBinding.inflate(inflater, viewGroup, false)
      return DetailsStyleViewHolder(binding, isSquare)
    }
  }
}

class AudiobookDiffCallback : DiffUtil.ItemCallback<Audiobook>() {
  override fun areItemsTheSame(
    oldItem: Audiobook,
    newItem: Audiobook,
  ): Boolean {
    return oldItem.id == newItem.id
  }

  /** Changes which require a redraw of the view */
  override fun areContentsTheSame(
    oldItem: Audiobook,
    newItem: Audiobook,
  ): Boolean {
    return oldItem.thumb == newItem.thumb && oldItem.title == newItem.title &&
      oldItem.author == newItem.author && oldItem.isCached == newItem.isCached &&
      oldItem.progress == newItem.progress
  }
}
