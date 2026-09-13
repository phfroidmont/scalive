{%
title = "Form save workflow"
description = "Revision-bound submission tokens coordinate dirty state, persistence responses, stale completions, and reset."
order = 11
section = examples
%}

Enter a title and choose **Begin save**. The status panel exposes canonical dirty
state, revision, save state, and the number of successful baseline advancements;
it never repeats raw form input. Submitting an empty title demonstrates an
invalid start, while **Begin another save** demonstrates `AlreadySaving`.

While a save is active, edit the title or try **Reset to baseline**, then use the
explicit success, failure, or cancellation controls. These buttons stand in for
a persistence callback and carry the exact `FormSubmissionToken` captured by
that submission. After completion, **Replay stale success** shows
that an obsolete token cannot mutate newer workflow state. Success advances the
baseline while preserving edits made at a newer revision; reset then returns to
that accepted baseline.

Simulate failure without changing the submitted draft to see
`failureForCurrentRevision` feedback. Identical-value updates retain it;
editing the title hides it while the save state remains `failed`. A failure
arriving after an edit during saving is recorded but does not show feedback for
the newer draft. The transition notice describes what happened separately from
the current failure alert.

Choose **Dismiss failure** to call `dismissFailure`: the save state returns to
`idle` without changing the draft, dirty state, baseline, or revision. This is
not a reset or cancellation. The example also uses `isSaving` to distinguish
ordinary edits from edits made while a submission is in flight.

@:example(form-save-workflow)

Related guidance: [coordinate dirty state and saving](../guides/typed-forms-and-validation.md#coordinate-form-workflow).
