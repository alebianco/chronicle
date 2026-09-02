package io.github.mattpvaughn.chronicle.util

import androidx.annotation.MainThread
import androidx.lifecycle.*
import kotlinx.coroutines.*

inline fun <T> LiveData<Event<T>>.observeEvent(
  owner: LifecycleOwner,
  crossinline onEventUnhandledContent: (T) -> Unit,
) {
  observe(owner) { it.getContentIfNotHandled()?.let(onEventUnhandledContent) }
}

fun <T> MutableLiveData<Event<T>>.postEvent(value: T) {
  postValue(Event(value))
}

/**
 * An in-place alternative to [Transformations.map] with [mapFunction] dispatched via
 * [Dispatchers.IO]
 */
@MainThread
fun <X, Y> mapAsync(
  source: LiveData<X>,
  scope: CoroutineScope,
  dispatcher: CoroutineDispatcher = Dispatchers.IO,
  mapFunction: suspend (X) -> Y,
): LiveData<Y> {
  val result = MediatorLiveData<Y>()
  result.addSource(source) { x ->
    scope.launch {
      // TODO: why does compiler think this can be nullable?
      result.value = withContext(dispatcher) { mapFunction(x) }!!
    }
  }
  return result
}

/**
 * Emits only when [keySelector] changes — a `distinctUntilChanged` on a *derived* key.
 *
 * Room invalidates per **table**, so every `LiveData` query on `Audiobook` re-emits on every write
 * to it, and `ProgressUpdater` writes once a second during playback. On the Home screen that meant
 * three shelf queries re-running per second, each deserializing `Audiobook.chapters` for every
 * book returned — measured at 88% janky frames and a GC every ~4s (cu-110).
 *
 * The key must be **cheap to compute and complete enough to notice a real change**. Comparing whole
 * `Audiobook`s defeats the purpose (`equals` walks the serialized chapters string); comparing ids
 * alone is worse than useless — `LibraryViewModel` did that and its progress bars silently stopped
 * updating, because a genuine progress change produced an unchanged key. [booksKey] is the shape to
 * copy: identity plus the fields the UI actually renders.
 */
fun <T> LiveData<T>.distinctBy(keySelector: (T) -> Any?): LiveData<T> {
  val result = MediatorLiveData<T>()
  var lastKey: Any? = NO_KEY_YET
  result.addSource(this) { value ->
    val key = keySelector(value)
    if (key != lastKey) {
      lastKey = key
      result.value = value
    }
  }
  return result
}

/**
 * A sentinel distinct from `null`, so a first emission whose key is genuinely `null` is not
 * mistaken for "unchanged" and swallowed.
 */
private val NO_KEY_YET = Any()

/**
 * The identity of a book list *as the UI draws it*: id, cached state and progress.
 *
 * Deliberately not the whole [io.github.mattpvaughn.chronicle.data.model.Audiobook] and deliberately
 * not just the ids — see [distinctBy]. Progress is included because every list row renders a
 * progress bar, so dropping it freezes them; `title`/`author`/`thumb` are omitted because a
 * metadata edit arrives through a refresh that changes `updatedAt` and the id set rarely, and the
 * cost of comparing them per tick is not worth catching that.
 */
fun List<io.github.mattpvaughn.chronicle.data.model.Audiobook>.booksKey(): List<Triple<String, Boolean, Long>> =
  map { Triple(it.id, it.isCached, it.progress) }

/** A wrapper for data exposed via [LiveData] representing an event */
open class Event<out T>(private val content: T) {
  var hasBeenHandled = false
    private set // Allow external read but not write

  /** Returns the content and prevents its use again. */
  fun getContentIfNotHandled(): T? {
    return if (hasBeenHandled) {
      null
    } else {
      hasBeenHandled = true
      content
    }
  }

  /** Returns the content, even if it's already been handled. */
  fun peekContent(): T = content
}

/** A [MediatorLiveData] implementation with typed in-place declaration on two [LiveData] */
class DoubleLiveData<T, K, S>(
  source1: LiveData<T>,
  source2: LiveData<K>,
  private val combine: (data1: T?, data2: K?) -> S,
) : MediatorLiveData<S>() {
  private var data1: T? = null
  private var data2: K? = null

  init {
    super.addSource(source1) {
      data1 = it
      publish()
    }
    super.addSource(source2) {
      data2 = it
      publish()
    }
  }

  /**
   * Publishes only when the combined result actually changed.
   *
   * This used to assign unconditionally, and that is a systemic cost rather than a local one:
   * these combinators sit downstream of Room `LiveData` on the `Audiobook` and `MediaItemTrack`
   * tables, which Room invalidates **per table** — so `ProgressUpdater`'s once-a-second write
   * re-emitted every one of them, and each assignment fanned out to every observer, each of which
   * writes to a view and invalidates it.
   *
   * Measured on a device during playback (DRAFT-117): 1312 `View.measure` calls in 18 s and 87%
   * janky frames, for values that had not changed. Suppressing the no-op assignment here fixes it
   * for all 23 call sites at once rather than one `distinctUntilChanged` at a time.
   *
   * Equality is the combined result's own `equals`, so a combiner returning a data class or a
   * primitive dedupes correctly. One returning a fresh mutable list every call will not — such a
   * combiner should project to something comparable (see `booksKey`).
   */
  private fun publish() {
    val next = combine(data1, data2)
    if (!hasPublished || next != value) {
      hasPublished = true
      value = next
    }
  }

  private var hasPublished = false

  override fun <T : Any?> addSource(
    source: LiveData<T>,
    onChanged: Observer<in T>,
  ) {
    throw UnsupportedOperationException()
  }

  override fun <T : Any?> removeSource(toRemote: LiveData<T>) {
    throw UnsupportedOperationException()
  }
}

/** A [MediatorLiveData] implementation with typed in-place declaration on three [LiveData] */
class TripleLiveData<T, K, S, R>(
  source1: LiveData<T>,
  source2: LiveData<K>,
  source3: LiveData<S>,
  private val combine: (data1: T?, data2: K?, data3: S?) -> R,
) : MediatorLiveData<R>() {
  private var data1: T? = null
  private var data2: K? = null
  private var data3: S? = null

  init {
    super.addSource(source1) {
      data1 = it
      value = combine(data1, data2, data3)
    }
    super.addSource(source2) {
      data2 = it
      value = combine(data1, data2, data3)
    }
    super.addSource(source3) {
      data3 = it
      value = combine(data1, data2, data3)
    }
  }

  override fun <T : Any?> addSource(
    source: LiveData<T>,
    onChanged: Observer<in T>,
  ): Unit = throw UnsupportedOperationException()

  override fun <T : Any?> removeSource(toRemote: LiveData<T>): Unit = throw UnsupportedOperationException()
}

/** A [MediatorLiveData] implementation with typed in-place declaration on four [LiveData] */
class QuadLiveData<T, K, S, Q, R>(
  source1: LiveData<T>,
  source2: LiveData<K>,
  source3: LiveData<S>,
  source4: LiveData<Q>,
  private val combine: (data1: T?, data2: K?, data3: S?, data4: Q?) -> R,
) : MediatorLiveData<R>() {
  private var data1: T? = null
  private var data2: K? = null
  private var data3: S? = null
  private var data4: Q? = null

  init {
    super.addSource(source1) {
      data1 = it
      value = combine(data1, data2, data3, data4)
    }
    super.addSource(source2) {
      data2 = it
      value = combine(data1, data2, data3, data4)
    }
    super.addSource(source3) {
      data3 = it
      value = combine(data1, data2, data3, data4)
    }
    super.addSource(source4) {
      data4 = it
      value = combine(data1, data2, data3, data4)
    }
  }

  override fun <T : Any?> addSource(
    source: LiveData<T>,
    onChanged: Observer<in T>,
  ): Unit = throw UnsupportedOperationException()

  override fun <T : Any?> removeSource(toRemote: LiveData<T>): Unit = throw UnsupportedOperationException()
}

/**
 * A wrapper around [MediatorLiveData] which allows for typed in-place declaration on four
 * [LiveData] combined via a suspend function on [Dispatchers.IO]
 */
class QuadLiveDataAsync<T, K, S, Q, R>(
  private val scope: CoroutineScope,
  source1: LiveData<T>,
  source2: LiveData<K>,
  source3: LiveData<S>,
  source4: LiveData<Q>,
  private val combine: suspend (data1: T?, data2: K?, data3: S?, data4: Q?) -> R,
) : MediatorLiveData<R>() {
  private var data1: T? = null
  private var data2: K? = null
  private var data3: S? = null
  private var data4: Q? = null

  init {
    super.addSource(source1) {
      data1 = it
      computeValue()
    }
    super.addSource(source2) {
      data2 = it
      computeValue()
    }
    super.addSource(source3) {
      data3 = it
      computeValue()
    }
    super.addSource(source4) {
      data4 = it
      computeValue()
    }
  }

  private fun computeValue() {
    scope.launch {
      value = withContext(Dispatchers.IO) { combine(data1, data2, data3, data4) }
    }
  }

  override fun <T : Any?> addSource(
    source: LiveData<T>,
    onChanged: Observer<in T>,
  ): Unit = throw UnsupportedOperationException()

  override fun <T : Any?> removeSource(toRemote: LiveData<T>): Unit = throw UnsupportedOperationException()
}

/**
 * A wrapper around [MediatorLiveData] which allows for typed in-place declaration on five
 * [LiveData] combined via a suspend function on [Dispatchers.IO]
 */
class QuintLiveDataAsync<T, K, S, Q, P, R>(
  private val scope: CoroutineScope,
  source1: LiveData<T>,
  source2: LiveData<K>,
  source3: LiveData<S>,
  source4: LiveData<Q>,
  source5: LiveData<P>,
  private val combine: suspend (data1: T?, data2: K?, data3: S?, data4: Q?, data5: P?) -> R,
) : MediatorLiveData<R>() {
  private var data1: T? = null
  private var data2: K? = null
  private var data3: S? = null
  private var data4: Q? = null
  private var data5: P? = null

  init {
    super.addSource(source1) {
      data1 = it
      computeValue()
    }
    super.addSource(source2) {
      data2 = it
      computeValue()
    }
    super.addSource(source3) {
      data3 = it
      computeValue()
    }
    super.addSource(source4) {
      data4 = it
      computeValue()
    }
    super.addSource(source5) {
      data5 = it
      computeValue()
    }
  }

  private fun computeValue() {
    scope.launch {
      value = withContext(Dispatchers.IO) { combine(data1, data2, data3, data4, data5) }
    }
  }

  override fun <T : Any?> addSource(
    source: LiveData<T>,
    onChanged: Observer<in T>,
  ): Unit = throw UnsupportedOperationException()

  override fun <T : Any?> removeSource(toRemote: LiveData<T>): Unit = throw UnsupportedOperationException()
}

/**
 * [MediatorLiveData] implementation for in-place declaration of arbitrary number of [LiveData].
 *
 * Useful for combining large numbers of [LiveData], but disadvantaged by the lack of type-safety
 * caused by storing data as a [List<Any>]
 */
class CombinedLiveData<R>(
  vararg liveData: LiveData<*>,
  private val combine: (data: List<Any?>) -> R,
) : MediatorLiveData<R>() {
  private val data: MutableList<Any?> = MutableList(liveData.size) { null }

  init {
    for (i in liveData.indices) {
      super.addSource(liveData[i]) {
        data[i] = it
        value = combine(data)
      }
    }
  }
}
