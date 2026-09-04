package io.github.mattpvaughn.chronicle.features.settings

import io.github.mattpvaughn.chronicle.data.local.PrefsRepo

fun setPreferencesForList(
  settingsList: SettingsList,
  prefs: List<PreferenceModel>,
  prefsRepo: PrefsRepo,
) {
  settingsList.setPreferences(prefs, prefsRepo)
}
