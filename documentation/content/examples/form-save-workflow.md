{%
title = "Form save workflow"
description = "Revision-bound submission tokens coordinate dirty state, persistence responses, stale completions, and reset."
order = 8
section = examples
%}

A form that saves in the background. The workflow tracks whether the draft
differs from the last saved baseline, ties each save to the revision it
submitted, and ignores completions that arrive too late. Instead of a real
service, buttons simulate the save's outcome.

## Try it {#try-it}

1. Type a title, such as `Release notes`, and click **Begin save**. **Save
   state** shows `saving`, and the simulation buttons appear.
2. Click **Simulate success**. **Save state** returns to `idle`, **Dirty** to
   `false`, and **Baseline advancements** to 1.
3. Click **Replay stale success**. The notice reads "A stale completion was
   ignored and changed no workflow state."
4. Edit the title, click **Begin save**, then **Simulate failure**. An alert
   reads "This draft could not be saved. Your changes are still available."
   Click **Dismiss failure** to hide it without changing the draft.
5. Clear the title and click **Begin save**. The notice reads "Save did not
   start because the typed form is invalid."

@:example(form-save-workflow)

## How it works {#how-it-works}

The status panel shows the workflow's dirty state, revision, save state, and
the number of successful baseline advancements. It never repeats the raw form
input.

- Starting a save returns a `FormSubmissionToken` for the submitted revision.
  The simulation buttons carry that exact token, as a persistence callback
  would. A completion with an obsolete token, such as **Replay stale success**,
  changes nothing.
- **Begin another save** while a save is active reads "A second save was
  rejected because one save is already active." One workflow runs one save at a
  time.
- Success advances the baseline but keeps edits made while the save was in
  flight. **Reset to baseline** then returns to that accepted baseline; while a
  save is active, reset is blocked.
- `failureForCurrentRevision` shows the failure alert only while the draft is
  the one that failed. Identical-value updates keep it; editing the title hides
  it while the save state stays `failed`. **Dismiss failure** calls
  `dismissFailure`, which returns the save state to `idle` without changing the
  draft, dirty state, baseline, or revision.

## Related guides {#related-guides}

- [Saving forms](../guides/saving-forms.md#coordinate-form-workflow) explains
  the workflow and its transitions.
