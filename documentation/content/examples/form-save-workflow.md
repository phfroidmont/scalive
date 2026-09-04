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

@:example(form-save-workflow)

Related guidance: [coordinate dirty state and saving](../guides/typed-forms-and-validation.md#coordinate-form-workflow).
