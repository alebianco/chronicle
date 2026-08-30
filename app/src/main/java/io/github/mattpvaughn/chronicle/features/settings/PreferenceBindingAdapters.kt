package io.github.mattpvaughn.chronicle.features.settings

fun setPreferencesForList(
  settingsList: SettingsList,
  prefs: List<PreferenceModel>,
) {
  settingsList.setPreferences(prefs)
}
