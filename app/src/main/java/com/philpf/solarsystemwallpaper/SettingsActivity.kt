package com.philpf.solarsystemwallpaper

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Settings screen reachable from the system wallpaper picker (android:settingsActivity in res/xml/wallpaper.xml). */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.settings_title)
        supportFragmentManager
            .beginTransaction()
            .replace(android.R.id.content, SettingsFragment())
            .commit()
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        private val dateFormat = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.settings_preferences, rootKey)

            val prefs = WallpaperPrefs(requireContext())
            val startDatePref = findPreference<Preference>("pref_custom_start_date")
            val resetPref = findPreference<Preference>("pref_reset_to_now")

            updateStartDateSummary(startDatePref, prefs)

            startDatePref?.setOnPreferenceClickListener {
                showDateTimePicker(prefs.simulatedMillis()) { pickedMillis ->
                    prefs.setCustomStartDate(pickedMillis)
                    updateStartDateSummary(startDatePref, prefs)
                }
                true
            }

            resetPref?.setOnPreferenceClickListener {
                prefs.resetCustomStartToNow()
                updateStartDateSummary(startDatePref, prefs)
                true
            }
        }

        private fun updateStartDateSummary(pref: Preference?, prefs: WallpaperPrefs) {
            pref ?: return
            pref.summary = dateFormat.format(Date(prefs.simulatedMillis()))
        }

        private fun showDateTimePicker(initialMillis: Long, onPicked: (Long) -> Unit) {
            val calendar = Calendar.getInstance().apply { timeInMillis = initialMillis }
            DatePickerDialog(
                requireContext(),
                { _, year, month, dayOfMonth ->
                    calendar.set(Calendar.YEAR, year)
                    calendar.set(Calendar.MONTH, month)
                    calendar.set(Calendar.DAY_OF_MONTH, dayOfMonth)
                    TimePickerDialog(
                        requireContext(),
                        { _, hourOfDay, minute ->
                            calendar.set(Calendar.HOUR_OF_DAY, hourOfDay)
                            calendar.set(Calendar.MINUTE, minute)
                            calendar.set(Calendar.SECOND, 0)
                            onPicked(calendar.timeInMillis)
                        },
                        calendar.get(Calendar.HOUR_OF_DAY),
                        calendar.get(Calendar.MINUTE),
                        true,
                    ).show()
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH),
            ).show()
        }
    }
}
