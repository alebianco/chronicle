package io.github.mattpvaughn.chronicle.features.collections

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo.Companion.VIEW_STYLE_COVER_GRID
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo.Companion.VIEW_STYLE_DETAILS_LIST
import io.github.mattpvaughn.chronicle.data.local.PrefsRepo.Companion.VIEW_STYLE_TEXT_LIST
import io.github.mattpvaughn.chronicle.data.model.Collection
import io.github.mattpvaughn.chronicle.databinding.GridItemCollectionBinding
import io.github.mattpvaughn.chronicle.databinding.ListItemCollectionTextOnlyBinding
import io.github.mattpvaughn.chronicle.databinding.ListItemCollectionWithDetailsBinding
import io.github.mattpvaughn.chronicle.features.library.overrideWidth
import io.github.mattpvaughn.chronicle.features.library.setSquareAspectRatio
import io.github.mattpvaughn.chronicle.views.bindImageRounded

/**
 * The item-count subtitle shown under a collection's title.
 *
 * Was the `` `` + collection.childCount + ` items` `` binding expression shared by all three
 * collection item layouts; kept verbatim (including the un-pluralised, untranslated " items")
 * so the conversion is behaviour-preserving.
 */
private fun itemCountLabel(collection: Collection): String = "${collection.childCount} items"

class CollectionsAdapter(
  initialViewStyle: String,
  private val isVertical: Boolean,
  private val isSquare: Boolean,
  private val collectionClick: CollectionsFragment.CollectionClick,
) : ListAdapter<Collection, RecyclerView.ViewHolder>(CollectionsDiffCallback()) {
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
        holder.bind(getItem(position), collectionClick, serverConnected)
      }
      is TextOnlyViewHolder -> {
        holder.bind(getItem(position), collectionClick)
      }
      is DetailsStyleViewHolder -> {
        holder.bind(getItem(position), collectionClick, serverConnected, isSquare)
      }
      else -> throw IllegalStateException("Unknown view type")
    }
  }

  fun setServerConnected(serverConnected: Boolean) {
    this.serverConnected = serverConnected
    notifyDataSetChanged()
  }

  class ViewHolder(
    val binding: GridItemCollectionBinding,
    private val isVertical: Boolean,
    private val isSquare: Boolean,
  ) : RecyclerView.ViewHolder(binding.root) {
    fun bind(
      collection: Collection,
      collectionClick: CollectionsFragment.CollectionClick,
      serverConnected: Boolean,
    ) {
      // Was binding expressions in grid_item_collection.xml.
      setSquareAspectRatio(binding.gridItemRoot, isSquare)
      overrideWidth(
        binding.gridItemRoot,
        if (isVertical) {
          binding.root.resources.getDimension(R.dimen.audiobook_match_parent)
        } else {
          binding.root.resources.getDimension(R.dimen.audiobook_item_width)
        },
      )
      binding.gridItemRoot.setOnClickListener { collectionClick.onClick(collection) }
      binding.title.text = collection.title
      binding.author.text = itemCountLabel(collection)
      binding.bookCoverImg.contentDescription = collection.title
      bindImageRounded(binding.bookCoverImg, collection.thumb, serverConnected)
    }

    companion object {
      fun from(
        viewGroup: ViewGroup,
        isVertical: Boolean,
        isSquare: Boolean,
      ): ViewHolder {
        val inflater = LayoutInflater.from(viewGroup.context)
        val binding = GridItemCollectionBinding.inflate(inflater, viewGroup, false)
        return ViewHolder(binding, isVertical, isSquare)
      }
    }
  }

  class TextOnlyViewHolder(val binding: ListItemCollectionTextOnlyBinding) :
    RecyclerView.ViewHolder(binding.root) {
    fun bind(
      collection: Collection,
      collectionClick: CollectionsFragment.CollectionClick,
    ) {
      // Was binding expressions in list_item_collection_text_only.xml.
      binding.textOnlyRoot.setOnClickListener { collectionClick.onClick(collection) }
      binding.title.text = collection.title
      binding.author.text = itemCountLabel(collection)
    }

    companion object {
      fun from(viewGroup: ViewGroup): TextOnlyViewHolder {
        val inflater = LayoutInflater.from(viewGroup.context)
        val binding =
          ListItemCollectionTextOnlyBinding.inflate(inflater, viewGroup, false)
        return TextOnlyViewHolder(binding)
      }
    }
  }
}

class DetailsStyleViewHolder(
  val binding: ListItemCollectionWithDetailsBinding,
  val isSquare: Boolean,
) : RecyclerView.ViewHolder(binding.root) {
  fun bind(
    collection: Collection,
    collectionClick: CollectionsFragment.CollectionClick,
    serverConnected: Boolean,
    isSquare: Boolean,
  ) {
    // Was binding expressions in list_item_collection_with_details.xml.
    setSquareAspectRatio(binding.detailsRoot, isSquare)
    binding.detailsRoot.setOnClickListener { collectionClick.onClick(collection) }
    binding.title.text = collection.title
    binding.author.text = itemCountLabel(collection)
    binding.bookCoverImg.contentDescription = collection.title
    bindImageRounded(binding.bookCoverImg, collection.thumb, serverConnected)
  }

  companion object {
    fun from(
      viewGroup: ViewGroup,
      isSquare: Boolean,
    ): DetailsStyleViewHolder {
      val inflater = LayoutInflater.from(viewGroup.context)
      val binding =
        ListItemCollectionWithDetailsBinding.inflate(inflater, viewGroup, false)
      return DetailsStyleViewHolder(binding, isSquare)
    }
  }
}

class CollectionsDiffCallback : DiffUtil.ItemCallback<Collection>() {
  override fun areItemsTheSame(
    oldItem: Collection,
    newItem: Collection,
  ): Boolean {
    return oldItem.id == newItem.id
  }

  /** Changes which require a redraw of the view */
  override fun areContentsTheSame(
    oldItem: Collection,
    newItem: Collection,
  ): Boolean {
    return oldItem.childCount == newItem.childCount &&
      oldItem.thumb == newItem.thumb &&
      oldItem.title == newItem.title
  }
}
