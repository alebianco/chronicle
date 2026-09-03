package io.github.mattpvaughn.chronicle.features.library

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.local.ViewStyleKind
import io.github.mattpvaughn.chronicle.data.model.Audiobook
import io.github.mattpvaughn.chronicle.data.model.isCompleted
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
      viewStyleInt = viewStyleIntFor(value)
      notifyDataSetChanged()
      field = value
    }
  private var viewStyleInt: Int = viewStyleIntFor(initialViewStyle)

  /**
   * Maps a stored view style to this adapter's internal constant, falling back to the grid.
   *
   * See `ViewStyle.kt` for why: this mapping existed **seven times** across the library and
   * collections screens, and every copy ended in `throw IllegalStateException`. Two were property
   * initializers, so an out-of-range preference crashed at construction as soon as the list
   * rendered — on every launch, since the value is persisted (cu-133).
   */
  private fun viewStyleIntFor(style: String): Int =
    when (ViewStyleKind.of(style)) {
      ViewStyleKind.CoverGrid -> COVER_GRID
      ViewStyleKind.TextOnly -> TEXT_ONLY
      ViewStyleKind.Details -> DETAILS
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
      bindProgressIndicators(binding.notPlayedDogEar, binding.bookProgress, audiobook)
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

/**
 * Binds the two progress indicators for a book, in one place for both the grid and the list.
 *
 * Three states, where there used to be two. A finished book previously rendered as *in progress* at
 * whatever position it held — and after "mark as read" reset the position to 0, it rendered as
 * **not played**, indistinguishable from a book never opened. That is the owner's report that the
 * list state is not what they expect (cu-86).
 *
 * - **not started** — the dog-ear, as before.
 * - **finished** — a full progress bar. [Audiobook.isCompleted] is authoritative: it honours an
 *   explicit `viewCount` regardless of position (decision-16), so a marked-as-read book at 0%
 *   shows complete rather than untouched.
 * - **in progress** — the bar at its real position.
 *
 * A full bar is a deliberately modest way to show "finished": a distinct badge is a visual-design
 * decision, and this keeps the fix to behaviour rather than inventing UI.
 */
internal fun bindProgressIndicators(
  notPlayedDogEar: View,
  bookProgress: ProgressBar,
  audiobook: Audiobook,
) {
  val isCompleted = audiobook.isCompleted()
  val isUnstarted = !isCompleted && audiobook.viewCount == 0L && audiobook.progress == 0L

  notPlayedDogEar.isVisible = isUnstarted
  val barMax = audiobook.duration.toInt().coerceAtLeast(1)
  bookProgress.max = barMax
  bookProgress.progress = if (isCompleted) barMax else audiobook.progress.toInt()
  bookProgress.isVisible = isCompleted || audiobook.progress > 0L
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
    bindProgressIndicators(binding.notPlayedDogEar, binding.bookProgress, audiobook)
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
