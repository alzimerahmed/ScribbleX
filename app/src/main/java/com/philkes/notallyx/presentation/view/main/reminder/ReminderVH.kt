package com.philkes.notallyx.presentation.view.main.reminder

import androidx.recyclerview.widget.RecyclerView
import com.philkes.notallyx.R
import com.philkes.notallyx.data.model.Reminder
import com.philkes.notallyx.data.model.toRepetitionText
import com.philkes.notallyx.databinding.RecyclerReminderBinding
import com.philkes.notallyx.presentation.format
import com.philkes.notallyx.presentation.viewmodel.preference.DateFormat
import com.philkes.notallyx.presentation.viewmodel.preference.TimeFormat

class ReminderVH(
    private val binding: RecyclerReminderBinding,
    private val dateFormat: DateFormat,
    private val timeFormat: TimeFormat,
    private val listener: ReminderListener,
) : RecyclerView.ViewHolder(binding.root) {

    fun bind(value: Reminder) {
        binding.apply {
            val location = value.location
            if (location != null) {
                DateTime.text =
                    itemView.context.getString(
                        R.string.location_reminder_coordinates,
                        location.latitude,
                        location.longitude,
                    )
                Repetition.text =
                    itemView.context.getString(
                        R.string.location_reminder_radius,
                        location.radius.toString(),
                    )
            } else {
                DateTime.text =
                    value.dateTime.format(dateFormat, timeFormat, ensureFullFormat = true)
                Repetition.text = value.toRepetitionText(itemView.context)
            }
            EditButton.setOnClickListener { listener.edit(value) }
            DeleteButton.setOnClickListener { listener.delete(value) }
        }
    }
}
