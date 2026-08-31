package io.github.mattpvaughn.chronicle.features.player

import androidx.work.WorkInfo

/**
 * Whether any progress report has permanently failed, meaning the listening position on
 * the server is behind the one on the device.
 *
 * Only [WorkInfo.State.FAILED] counts, and the reasons the other states do not are the
 * whole design:
 *
 * - `CANCELLED` is *ordinary operation*. Reports are enqueued per track with
 *   [androidx.work.ExistingWorkPolicy.REPLACE], so every newer position cancels the
 *   pending older one. Treating that as a problem would show a permanent warning during
 *   normal playback.
 * - `ENQUEUED` is the steady state between ticks, and also where a retrying report waits
 *   out its backoff. Both are the mechanism working.
 * - `RUNNING`/`SUCCEEDED` are self-evidently fine.
 *
 * So this is deliberately failure-only rather than a synced/pending/failed indicator: the
 * other two states cannot be distinguished from noise here, and a badge that blinks every
 * ten seconds teaches people to ignore it. An indicator that lies is worse than none.
 */
fun hasFailedSync(workInfos: List<WorkInfo>): Boolean = workInfos.any { it.state == WorkInfo.State.FAILED }
