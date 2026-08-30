package io.github.mattpvaughn.chronicle.features.login

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.mattpvaughn.chronicle.data.model.ServerModel
import io.github.mattpvaughn.chronicle.databinding.ListItemServerBinding

class ServerListAdapter(val clickListener: ServerClickListener) :
  ListAdapter<ServerModel, ServerListAdapter.ServerViewHolder>(
    ServerDiffCallback(),
  ) {
  override fun onBindViewHolder(
    holder: ServerViewHolder,
    position: Int,
  ) {
    holder.bind(getItem(position), clickListener)
  }

  override fun onCreateViewHolder(
    parent: ViewGroup,
    viewType: Int,
  ): ServerViewHolder {
    return ServerViewHolder.from(
      parent,
    )
  }

  class ServerViewHolder private constructor(val binding: ListItemServerBinding) :
    RecyclerView.ViewHolder(binding.root) {
      fun bind(
        server: ServerModel,
        clickListener: ServerClickListener,
      ) {
        // Was binding expressions in list_item_server.xml.
        binding.libraryName.text = server.name
        binding.serverItemRoot.setOnClickListener { clickListener.onClick(server) }
      }

      companion object {
        fun from(parent: ViewGroup): ServerViewHolder {
          val layoutInflater = LayoutInflater.from(parent.context)
          val binding = ListItemServerBinding.inflate(layoutInflater, parent, false)
          return ServerViewHolder(
            binding,
          )
        }
      }
    }
}

class ServerDiffCallback : DiffUtil.ItemCallback<ServerModel>() {
  override fun areItemsTheSame(
    oldItem: ServerModel,
    newItem: ServerModel,
  ): Boolean {
    return oldItem.name == newItem.name
  }

  override fun areContentsTheSame(
    oldItem: ServerModel,
    newItem: ServerModel,
  ): Boolean {
    return oldItem == newItem
  }
}
