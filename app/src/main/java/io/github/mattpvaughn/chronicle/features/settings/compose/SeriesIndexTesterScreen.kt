package io.github.mattpvaughn.chronicle.features.settings.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.mattpvaughn.chronicle.R
import io.github.mattpvaughn.chronicle.data.model.LibraryParseSummary
import io.github.mattpvaughn.chronicle.data.model.PatternAttempt
import io.github.mattpvaughn.chronicle.ui.theme.ChronicleTheme

/**
 * Shows how the series-numbering rules read a title (cu-151, migrated in cu-202).
 *
 * The half of cu-147/cu-148 that lets a user see what a rule *does* before trusting it. tvnamer has
 * the config file and not this, and its open issue #216 is a user who could not tell whether their
 * pattern was wrong or the tool was broken.
 *
 * One `LazyColumn` rather than a `NestedScrollView` wrapping two `nestedScrollingEnabled=false`
 * `RecyclerView`s — that arrangement measures every row of both lists eagerly, which is what
 * disabling nested scrolling means.
 */
@Composable
fun SeriesIndexTesterScreen(
  state: SeriesIndexTesterUiState,
  onTitleSortChanged: (String) -> Unit,
  onSampleChosen: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  // `MaterialTheme` defines `colorScheme.background` but paints nothing (cu-181).
  Surface(modifier = modifier.fillMaxSize()) {
    val padding = dimensionResource(R.dimen.screen_horizontal_padding)

    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = padding)) {
      item(key = "input") {
        Column {
          Spacer(Modifier.height(16.dp))
          Text(
            text = stringResource(R.string.series_rules_input_label),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Spacer(Modifier.height(8.dp))
          // The text field owns the cursor. The View version needed a guarded two-way sync
          // (`doAfterTextChanged` plus an observer that skipped equal values) because writing the
          // field from its own observer moved the caret mid-typing; hoisted state has one
          // direction and no such hazard.
          OutlinedTextField(
            value = state.titleSort,
            onValueChange = onTitleSortChanged,
            label = { Text(stringResource(R.string.series_rules_input_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
          )
        }
      }

      // The verdict, only once there is something to have a verdict about.
      if (state.hasInput) {
        item(key = "result") {
          val winner = state.winningRule
          Text(
            text =
              if (winner == null) {
                stringResource(R.string.series_rules_result_none)
              } else {
                stringResource(
                  R.string.series_rules_result_found,
                  winner.capturedIndex.orEmpty(),
                  winner.patternName,
                )
              },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(vertical = 12.dp),
          )
        }
      }

      if (state.attempts.isNotEmpty()) {
        item(key = "rules-title") {
          SectionHeading(stringResource(R.string.series_rules_rules_title))
        }
        // Keyed by rule name: the rule set is fixed while the screen is open, so a name is a
        // stable identity and re-typing rebinds rather than recreating every row.
        items(state.attempts, key = { it.patternName }) { attempt ->
          RuleVerdictRow(attempt, isWinner = attempt == state.winningRule)
        }
      }

      item(key = "summary") {
        // Worded as an upper bound, never a defect count: a standalone novel legitimately has no
        // series position, so a perfectly tagged library still reports a large number.
        state.summary?.let { summary ->
          Text(
            text =
              stringResource(
                R.string.series_rules_summary,
                summary.withTitleSort,
                summary.total,
                summary.parsed,
                summary.unparsed,
              ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 12.dp),
          )
        }
      }

      item(key = "order") {
        Text(
          text = stringResource(state.ruleOrderLabel.stringRes()),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      item(key = "samples-title") {
        SectionHeading(stringResource(R.string.series_rules_samples_title))
      }

      // Nothing is said until the library has actually been read — an empty list means "nothing
      // needs fixing" only once we know what is in the library (cu-68's mirror risk).
      if (state.libraryLoaded) {
        if (state.samples.isEmpty()) {
          item(key = "samples-empty") {
            // "Nothing left to fix" is a real state and a reassuring one, so it is said rather
            // than shown as an empty space.
            Text(
              text = stringResource(R.string.series_rules_samples_empty),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        } else {
          item(key = "samples-explanation") {
            Text(
              text = stringResource(R.string.series_rules_samples_explanation),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          items(state.samples, key = { it }) { sample ->
            SampleTitleRow(sample, onClick = { onSampleChosen(sample) })
          }
        }
      }

      item(key = "tail") { Spacer(Modifier.height(24.dp)) }
    }
  }
}

@Composable
private fun SectionHeading(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.onSurface,
    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
  )
}

/**
 * One rule's verdict.
 *
 * "Matched and decided" versus "also matched, but too late" is the distinction the screen exists
 * for: more than one rule routinely matches, and a flat "matched" list leaves the user unable to
 * tell which reading the app actually took (cu-146).
 */
@Composable
private fun RuleVerdictRow(
  attempt: PatternAttempt,
  isWinner: Boolean,
) {
  val verdict =
    when {
      isWinner -> stringResource(R.string.series_rules_verdict_won)
      attempt.succeeded -> stringResource(R.string.series_rules_verdict_also_matched)
      else -> attempt.rejectedReason.orEmpty()
    }
  val origin =
    stringResource(
      if (attempt.isUserDefined) {
        R.string.series_rules_rule_origin_yours
      } else {
        R.string.series_rules_rule_origin_builtin
      },
    )

  Column(
    modifier =
      Modifier
        .fillMaxWidth()
        .padding(vertical = 6.dp)
        .semantics {
          contentDescription = "Rule ${attempt.patternName}, $origin. $verdict"
        },
  ) {
    Row(modifier = Modifier.fillMaxWidth()) {
      Text(
        text = attempt.patternName,
        style = MaterialTheme.typography.bodyLarge,
        fontFamily = FontFamily.Monospace,
        color =
          if (isWinner) {
            MaterialTheme.colorScheme.primary
          } else {
            MaterialTheme.colorScheme.onSurface
          },
        modifier = Modifier.weight(1f),
      )
      Text(
        text = origin,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Text(
      text = verdict,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun SampleTitleRow(
  title: String,
  onClick: () -> Unit,
) {
  Text(
    text = title,
    style = MaterialTheme.typography.bodyMedium,
    fontFamily = FontFamily.Monospace,
    color = MaterialTheme.colorScheme.onSurface,
    modifier =
      Modifier
        .fillMaxWidth()
        .clickable(onClick = onClick)
        .padding(vertical = 10.dp)
        .semantics { contentDescription = "Test the title $title" },
  )
}

private fun RuleOrderLabel.stringRes(): Int =
  when (this) {
    RuleOrderLabel.NoUserRules -> R.string.series_rules_order_none
    RuleOrderLabel.Before -> R.string.series_rules_order_before
    RuleOrderLabel.After -> R.string.series_rules_order_after
    RuleOrderLabel.Replace -> R.string.series_rules_order_replace
  }

@Preview
@Composable
private fun SeriesIndexTesterPreview() {
  ChronicleTheme {
    SeriesIndexTesterScreen(
      state =
        SeriesIndexTesterUiState(
          titleSort = "Mistborn, Book 2 - The Well of Ascension",
          attempts =
            listOf(
              PatternAttempt("audnexus", matched = true, capturedIndex = "2"),
              PatternAttempt("seanap", matched = true, capturedIndex = "2"),
              PatternAttempt("comma-trail", matched = false, rejectedReason = "No match"),
            ),
          summary = LibraryParseSummary(total = 196, withTitleSort = 138, parsed = 80, unparsed = 58),
          samples = listOf("The Hobbit", "Dune"),
        ),
      onTitleSortChanged = {},
      onSampleChosen = {},
    )
  }
}
