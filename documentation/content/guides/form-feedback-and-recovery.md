{%
title = "Form feedback and recovery"
description = "Choose when a field's errors appear, normalize values on blur, and restore a form's values after a reconnect."
order = 13
section = guides
group = "Interfaces and input"
%}

## Before you start {#prerequisites}

This guide extends the profile form from
[Typed forms and validation](typed-forms-and-validation.md#handle-change-and-submit-events).
By default, a field's errors appear once the visitor has used that field, and
every error appears after a submit. That suits most forms; read on when errors
should wait until the visitor leaves a field, or when a form must behave
differently after a reconnect.

## Understand the default {#understand-the-default}

The Phoenix client marks fields the visitor has not used yet with `_unused_*`
markers in each change. Scalive reads those markers into the form's used-field
set on change and recovery, and `errorFeedback` shows a field's errors only
once it is used. A submit makes every field's errors eligible until the form is
reset.

Putting `phx.noUnusedField := true` on a form or control suppresses those
markers, so every received field is treated as used. Keep that switch
exceptional; the default gives field-local feedback before submit.

## Choose when feedback appears {#choose-when-feedback-appears}

Choose `FormFeedback.AfterBlur` when an invalid field should stay quiet until
focus leaves it. For signal-backed forms, `formSignal.bind` ties rendering and
events to one stable, instance-unique `DomRef` and takes the feedback policy;
the default `FormFeedback.WhenUsed` follows Phoenix's unused markers.

Apply every `FormUpdate` to the form in the **current** model, not to a form
captured during rendering. `AppliedFormUpdate.valuesChanged` is false for
feedback-only transitions, so status such as a save confirmation need not be
cleared just because a field blurred. Submission remains a separately typed
`Definition.Event`:

@:sourceRegion(documentation/site/src/scalive/docs/examples/BlurFeedbackExample.scala, blur-feedback-example)

Include `binding.modifiers` exactly once when supplying your own `form` tag:
under `AfterBlur`, the modifiers include the nameless hidden child required by
blur transport. `binding.render(...)` also rejects overrides of its owned ID and
event bindings. Keep the feedback policy stable for a rendered form instance;
the binding is the policy's configuration point.

A bound control's `id` and feedback ID are scoped by the form `DomRef`; use its
`id`, `validationAttributes`, and `errorFeedback` together for labels and ARIA.
Bound controls reject overrides of binding-owned attributes, while plain and
signal-backed field views append caller modifiers without those restrictions.
For a custom scalar input, `inputAttributes` supplies its retained `id`, `name`,
value, and blur wiring.

## Normalize a value on blur {#normalize-a-value-on-blur}

Calling `.onBlur(value => Msg.NormalizePhone(value.trim))` on a bound control
sends an application message after the feedback update. The command order does
not wait for server acknowledgement, so it is not a race-free normalization
mechanism; there is no `normalizeOnBlur` API. A later full-form snapshot can
still overwrite a server-normalized value before its patch reaches the browser.

Scalar `.onBlur` uses the browser's `value`, or an empty string when absent; it
is not the complete value vector of a multi-select. Within a `LiveComponent`,
use the usual `phx.target(self)` on the form and on controls with
application-specific blur callbacks.

For a composite control, the widget owns its logical focus boundary and
dispatches `control.blurred` only when focus leaves the whole widget. In keyed
rendering, bind a row control with `binding.field(rows, key, field)` so blur
state follows the stable row key. A bound row control must only be evaluated
while its row exists. Outside a keyed loop, use
`binding.optionalField(rows, key, field)(control => ...)` or the overload
accepting a `BoundFormField`; it omits the control safely when the row
disappears.

## Reset interaction without losing values {#reset-interaction}

`form.pristine` clears interaction while retaining current values, validation
errors, and the active feedback policy. `Definition.fromValues(form.values)`
revalidates with default pristine interaction; the binding reapplies its
configured policy. `Definition.initial(...)` creates fresh values and
interaction.

Feedback-only operations (`pristine` and `withAllErrorsVisible`) preserve
malformed payload errors rather than silently dropping them through
revalidation.

## Recover a form after reconnect {#recover-a-form-after-reconnect}

When the connection drops and the page remounts, the Phoenix client sends the
values still in the browser through the form's change binding. Give a
recoverable form a stable, unique DOM `id` and keep its change binding; storing
`event.form`, as the change handler already does, restores the values and
validation state.

Use a dedicated typed recovery message when recovery needs different behavior.
Pass a second callback to `onChange` and handle the extra message:

```scala fragment
Profile.Definition.onChange(Msg.Validate(_), Msg.Recover(_))

case Msg.Recover(event) =>
  ZIO.succeed(model.copy(form = event.form))
```

The recovery callback runs for successful and failed decoding. Inspect
`event.kind == FormEventKind.Recovered` when one message type handles multiple
event sources. Recovery is distinct from submission, so used-field visibility
still comes from recovered payload markers. A fresh mount starts with no blur
history: applying a recovery update to retained server state preserves it, but
applying it to a freshly mounted form restores values without old blur history.

Disable client recovery explicitly when replay would be unsafe or the server is
authoritative. Add this attribute to the form:

```scala fragment
phx.autoRecover := "ignore"
```

Recovery restores what the browser still holds; it is not draft storage. It does
not survive a deliberate page exit, replace a database-backed draft, recover
file input bytes, or merge concurrent edits. Persist drafts in an application
service when those guarantees are required.

## Related tasks {#related-tasks}

- Test changed-field visibility and submission with [Testing](testing.md#test-typed-form-behavior); keep automatic recovery assertions at the [browser boundary](testing.md#test-in-a-browser).
- Render more control types with [Form controls](form-controls.md#prerequisites).
- Warn before leaving a dirty form with [Navigation guards](navigation-guards.md#prerequisites).
