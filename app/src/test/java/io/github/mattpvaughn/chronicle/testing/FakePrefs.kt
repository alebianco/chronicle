package io.github.mattpvaughn.chronicle.testing

import android.content.SharedPreferences

/**
 * A `SharedPreferences` with a **working listener list**.
 *
 * Promoted out of two suites during the Hilt migration, where the same class was written twice privately, so the
 * Hilt test module can bind it.
 *
 * A relaxed `SharedPreferences` mock is the wrong tool and silently so: it returns `false`/`null`
 * from the getters and **drops the listener registration**, so a `preferenceFlow` built on it
 * never emits, the `combine` downstream never fires, and every assertion about the result passes
 * against a flow that produced nothing.
 */
class FakePrefs : SharedPreferences {
  private val booleans = mutableMapOf<String, Boolean>()
  private val strings = mutableMapOf<String, String>()
  private val listeners = mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()

  override fun getBoolean(
    key: String,
    defValue: Boolean,
  ) = booleans[key] ?: defValue

  override fun getString(
    key: String,
    defValue: String?,
  ) = strings[key] ?: defValue

  override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
    listeners += listener
  }

  override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
    listeners -= listener
  }

  override fun getAll(): MutableMap<String, *> = mutableMapOf<String, Any>()

  override fun getStringSet(
    key: String,
    defValues: MutableSet<String>?,
  ): MutableSet<String>? = defValues

  override fun getInt(
    key: String,
    defValue: Int,
  ) = defValue

  override fun getLong(
    key: String,
    defValue: Long,
  ) = defValue

  override fun getFloat(
    key: String,
    defValue: Float,
  ) = defValue

  override fun contains(key: String) = booleans.containsKey(key) || strings.containsKey(key)

  override fun edit(): SharedPreferences.Editor = throw UnsupportedOperationException()
}
