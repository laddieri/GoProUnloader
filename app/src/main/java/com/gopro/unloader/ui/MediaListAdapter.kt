package com.gopro.unloader.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.gopro.unloader.R
import com.gopro.unloader.model.DownloadStatus
import com.gopro.unloader.model.MediaFile
import com.gopro.unloader.model.TranscodeStatus

class MediaListAdapter(
    private val onSelectionChanged: () -> Unit = {}
) : ListAdapter<MediaFile, MediaListAdapter.ViewHolder>(DIFF_CALLBACK) {

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val cbSelect: CheckBox = itemView.findViewById(R.id.cb_select)
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
        val file = getItem(position)

        // Checkbox — only interactive before transfer starts
        val transferring = file.downloadStatus != DownloadStatus.PENDING
        holder.cbSelect.isEnabled = !transferring
        holder.cbSelect.setOnCheckedChangeListener(null)
        holder.cbSelect.isChecked = file.selected
        holder.cbSelect.setOnCheckedChangeListener { _, checked ->
            file.selected = checked
            onSelectionChanged()
        }

        holder.tvName.text = file.name
        holder.tvSize.text = formatSize(file.size)

        val dur = formatDuration(file.duration)
        if (dur.isNotEmpty()) {
            holder.tvDuration.text = dur
            holder.tvDuration.visibility = View.VISIBLE
        } else {
            holder.tvDuration.visibility = View.GONE
        }

        when {
            file.downloadStatus == DownloadStatus.DOWNLOADING -> {
                holder.tvStatus.text = "Downloading… ${file.downloadProgress}%"
                holder.tvStatus.visibility = View.VISIBLE
                holder.progressBar.visibility = View.VISIBLE
                holder.progressBar.progress = file.downloadProgress
            }
            file.transcodeStatus == TranscodeStatus.TRANSCODING -> {
                holder.tvStatus.text = "Transcoding… ${file.transcodeProgress}%"
                holder.tvStatus.visibility = View.VISIBLE
                holder.progressBar.visibility = View.VISIBLE
                holder.progressBar.progress = file.transcodeProgress
            }
            file.downloadStatus == DownloadStatus.ERROR -> {
                holder.tvStatus.text = "Error"
                holder.tvStatus.visibility = View.VISIBLE
                holder.progressBar.visibility = View.GONE
            }
            file.downloadStatus == DownloadStatus.SKIPPED -> {
                holder.tvStatus.text = "Skipped (already exists)"
                holder.tvStatus.visibility = View.VISIBLE
                holder.progressBar.visibility = View.GONE
            }
            file.transcodeStatus == TranscodeStatus.DONE -> {
                holder.tvStatus.text = "Done ✓"
                holder.tvStatus.visibility = View.VISIBLE
                holder.progressBar.visibility = View.GONE
            }
            file.downloadStatus == DownloadStatus.DOWNLOADED -> {
                holder.tvStatus.text = "Downloaded"
                holder.tvStatus.visibility = View.VISIBLE
                holder.progressBar.visibility = View.GONE
            }
            else -> {
                holder.tvStatus.visibility = View.GONE
                holder.progressBar.visibility = View.GONE
            }
        }
    }

    fun selectAll(selected: Boolean) {
        currentList.forEach { it.selected = selected }
        notifyItemRangeChanged(0, itemCount)
        onSelectionChanged()
    }

    fun selectedCount(): Int = currentList.count { it.selected }

    private fun formatDuration(seconds: Long): String {
        if (seconds <= 0) return ""
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
            bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
            bytes >= 1_024 -> "%.1f KB".format(bytes / 1_024.0)
            else -> "$bytes B"
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<MediaFile>() {
            override fun areItemsTheSame(oldItem: MediaFile, newItem: MediaFile) =
                oldItem.name == newItem.name && oldItem.directory == newItem.directory

            override fun areContentsTheSame(oldItem: MediaFile, newItem: MediaFile) =
                oldItem.selected == newItem.selected &&
                    oldItem.duration == newItem.duration &&
                    oldItem.downloadStatus == newItem.downloadStatus &&
                    oldItem.transcodeStatus == newItem.transcodeStatus &&
                    oldItem.downloadProgress == newItem.downloadProgress &&
                    oldItem.transcodeProgress == newItem.transcodeProgress
        }
    }
}
