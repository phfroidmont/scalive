{%
title = "Saving forms"
description = "Track unsaved changes, save a valid form in the background, and apply each save's result to the right draft."
order = 15
section = guides
group = "Interfaces and input"
%}

## Before You Start {#prerequisites}

This guide extends the profile form from
[Typed forms and validation](typed-forms-and-validation.md#handle-change-and-submit-events),
whose submit handler saves before it returns. That is enough when saving is
fast. When saving is slow, the visitor can keep editing, or the page needs
"unsaved changes" and reset to the last saved values, wrap the form in a
@:apiSymbol(class:scalive.FormWorkflow)`FormWorkflow`@:@. It also uses
[asynchronous work](async-work.md#run-finite-work-with-a-typed-key).

## Wrap The Form In A Workflow {#coordinate-form-workflow}

The model stores `Definition.Workflow[SaveError]` instead of the form. It holds
the current form, the last saved values (the baseline), and the state of at most
one save. Submit starts the save:

@:sourceRegion(documentation/fixtures/guides/src/forms/ProfileSaveLiveView.scala, forms-workflow-start)

`beginSave` returns the next workflow together with a submission that captures
the valid value. The handler starts persistence with `ctx.async.start` and
returns the `Saving` workflow in the same turn. The completion arrives as a
later message, so it always finds the matching `Saving` state, even when the
save finishes immediately. The invalid branch renders `next.current` with all
errors visible, and `AlreadySaving` rejects a second submit while one is in
flight.

The view reads the current form from `workflow.map(_.current)`. `isDirty`
compares the current values with the baseline, and `isSaving` reports whether a
save is in flight; the complete recipe below uses both to label the button and
show "You have unsaved changes."

## Apply The Save Result {#apply-the-save-result}

Each save captures a valid value, its editable values, a token, and a revision.
The completion message carries the captured `FormSubmissionToken` unchanged.
Apply exactly one completion transition for it:

@:sourceRegion(documentation/fixtures/guides/src/forms/ProfileSaveLiveView.scala, forms-workflow-complete)

A completion carrying a token from another save attempt is `Stale` and leaves
the model unchanged.

A successful save advances the baseline and replaces the current form only
when no newer revision exists; edits made while saving stay current and remain
dirty. Use `saveFailed` to retain the failed submission for retry diagnostics
and `saveCancelled` to return to idle without advancing the baseline.

## Show A Failure For The Submitted Draft {#show-a-failure-for-the-submitted-draft}

Use `failureForCurrentRevision` when failure feedback should apply only to the
draft that was submitted. The view renders it with
`workflow.map(_.failureForCurrentRevision).option(...)`, and an explicit dismiss
action preserves the draft and its interaction state:

@:sourceRegion(documentation/fixtures/guides/src/forms/ProfileSaveLiveView.scala, forms-workflow-failure)

The query returns a failure only when the failed submission's revision equals
the current revision. Blur, feedback-only updates, and identical-value updates
retain relevance. Changing editable values hides the feedback but keeps the
failed submission in `save` for diagnostics. Editing away and back does not
revive the failure, because the revision has advanced.

A matching failure completion can be `Applied` after newer edits while
`failureForCurrentRevision` returns `None`: accepting a completion token is not
the same as deciding its feedback applies to the current draft. Applications
can still inspect `save` for failures whose display policy is not draft-specific.

`dismissFailure` clears the stored `Failed` state to `Idle`, preserving the
current form, baseline, revision, and submission-token correlation. It leaves
idle and saving workflows unchanged; it does not cancel persistence or reset
the form. Failure storage is the last retained failed attempt, not an audit log.

## Reset To The Baseline {#reset-to-the-baseline}

Call `workflow.reset` to return to the baseline, and handle
`FormWorkflowReset.Saving` explicitly:

@:sourceRegion(documentation/fixtures/guides/src/forms/ProfileSaveLiveView.scala, forms-workflow-reset)

`reset` returns `FormWorkflowReset.Saving(current, submission)` instead of
discarding an in-flight save, so cancellation policy remains an application
decision. Do not replace an active workflow with a fresh one: that discards its
in-flight correlation state and restarts submission token generation.

## Study The Complete Recipe {#study-the-complete-recipe}

@:sourceRegion(documentation/fixtures/guides/src/forms/ProfileSaveLiveView.scala, forms-workflow)

The [form save workflow example](../examples/form-save-workflow.md) provides
deterministic controls for invalid and overlapping starts, success, failure,
cancellation, revision-specific failure feedback and dismissal, edits during
save, stale replay, blocked reset, and baseline advancement.

## Related Tasks {#related-tasks}

- Warn before leaving a form with unsaved changes with [Navigation guards](navigation-guards.md#prerequisites).
- Cancel or replace background work with [Asynchronous work](async-work.md#cancel-explicitly-when-it-is-user-visible).
- Test submission and validation with [Testing](testing.md#test-typed-form-behavior).
