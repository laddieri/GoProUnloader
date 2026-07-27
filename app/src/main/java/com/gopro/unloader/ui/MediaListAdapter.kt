package com.gopro.unloader.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.gopro.unloader.R
import com.gopro.unloader.model.MediaRow
import com.gopro.unloader.util.ThumbnailLoader

/**
 * Renders the camera's file list.
 *
 * Works from immutable [MediaRow] snapshots so DiffUtil can actually tell one
 * emission from the next, and reports ticks back through [onSelectionChanged]
 * rather than holding selection state of its own.
 */
class MediaListAdapter(
    private val thumbnailLoader: ThumbnailLoader? = null,
    private val onSelectionChanged: (directory: String, name: String, selected: Boolean) -> Unit
) : ListAdapter<MediaRow, MediaListAdapter.ViewHolder>(DIFF_CALLBACK) {

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val cbSelect: CheckBox = itemView.findViewById(R.id.cb_select)
        val ivThumb: ImageView = itemView.findViewById(R.id.iv_thumbnail)
        val tvThumbPlaceholder: TextView = itemView.findViewById(R.id.tv_thumb_placeholder)
        val tvName: TextView = itemView.findViewById(R.id.tv_file_name)
        val tvDuration: TextView = itemView.findViewById(R.id.tv_duration)
        val tvSize: TextView = itemView.findViewById(R.id.tv_file_size)
        val tvStatus: TextView = itemView.findViewById(R.id.tv_file_status)
        val progressBar: ProgressBar = itemView.findViewById(R.id.progress_file)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_media, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val row = getItem(position)

        // Detach the listener before setting the state, or restoring a
        // recycled row would fire it and toggle the wrong file.
        holder.cbSelect.setOnCheckedChangeListener(null)
        holder.cbSelect.isChecked = row.selected
        holder.cbSelect.isEnabled = row.selectable
        holder.cbSelect.setOnCheckedChangeListener { _, checked ->
            onSelectionChanged(row.directory, row.name, checked)
        }

        holder.tvName.text = row.name
        holder.tvSize.text = row.sizeText

        if (row.durationText != null) {
            holder.tvDuration.text = row.durationText
            holder.tvDuration.visibility = View.VISIBLE
        } else {
            holder.tvDuration.visibility = View.GONE
        }

        if (row.statusText != null) {
            holder.tvStatus.text = row.statusText
            holder.tvStatus.visibility = View.VISIBLE
        } else {
            holder.tvStatus.visibility = View.GONE
        }

        if (row.progress != null) {
            holder.progressBar.progress = row.progress
            holder.progressBar.visibility = View.VISIBLE
        } else {
            holder.progressBar.visibility = View.GONE
        }

        // Thumbnail. The placeholder shows through until an image arrives, and
        // stays put if the camera has none for this file.
        holder.tvThumbPlaceholder.setText(R.string.thumb_loading)
        holder.tvThumbPlaceholder.visibility = View.VISIBLE
        if (thumbnailLoader == null) {
            holder.ivThumb.setImageBitmap(null)
            holder.tvThumbPlaceholder.setText(R.string.thumb_none)
        } else {
            thumbnailLoader.load(
                key = row.cameraPath,
                thumbUrl = row.thumbUrl,
                view = holder.ivThumb,
                onLoaded = { holder.tvThumbPlaceholder.visibility = View.GONE },
                onMissing = { holder.tvThumbPlaceholder.setText(R.string.thumb_none) }
            )
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<MediaRow>() {
            override fun areItemsTheSame(oldItem: MediaRow, newItem: MediaRow) =
                oldItem.name == newItem.name && oldItem.directory == newItem.directory

            // MediaRow is a data class, so this compares every rendered field.
            override fun areContentsTheSame(oldItem: MediaRow, newItem: MediaRow) =
                oldItem == newItem
        }
    }
}
