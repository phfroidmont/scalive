{%
title = "Form controls"
description = "Render textareas, selects, checkboxes, and custom inputs for a typed form, and route several submit buttons to typed actions."
order = 12
section = guides
group = "Interfaces and input"
%}

## Before you start {#prerequisites}

This guide extends the profile form from
[Typed forms and validation](typed-forms-and-validation.md#render-fields-and-errors),
which renders text and email inputs with their errors. It covers the other
controls a form needs.

## Render richer controls {#render-richer-controls}

Ask the form, or the form signal, for a field's
@:apiSymbol(class:scalive.FormControl)`FormControl`@:@, then render the control
you need. A control provides `text`, `email`, `tel`, `date`, `search`,
`password`, `hidden`, `checkbox`, `textarea`, and `select` helpers. Normal
attributes such as `autocomplete`, `maxlength`, `required`, `multiple`, or CSS
classes remain available:

```scala fragment
val bio  = profileForm.field(Profile.Bio)
val role = profileForm.field(Profile.Role)

bio.textarea(rows := 6, bio.validationAttributes)
role.select(List("reader" -> "Reader", "editor" -> "Editor"), role.validationAttributes)
```

Every helper renders the retained raw value the visitor typed, not the decoded
domain value, so a control keeps showing malformed input while its error is
visible. The native `tel`, `date`, and `search` helpers do not introduce
parsing, trimming, or domain validation. ARIA feedback stays opt-in through
`validationAttributes`.

The control owns the `id`, `name`, `value`, `type`, `checked`, `phx-change`, and
`phx-blur` attributes of what it renders: a helper throws
`IllegalArgumentException` when its modifiers set one. Use `onBlur` for a blur
message, and `inputAttributes` for a custom input that renders its own element.

A select marks every option found in `rawValues`; for a `multiple` select, use a
repeated-value field such as `Root.texts`.

There are no input rendering helpers for numeric, radio-group, or file controls.
Use `Root.field` plus ordinary HTML controls for custom decoding, and use
`liveFileInput` with the [upload API](uploads-and-consumption.md#prerequisites)
for files.

An unfocused native date input can receive an invalid `value` attribute from a
server update while exposing an empty DOM `input.value`. A later form snapshot
contains the browser's current value, not the original attribute, and can
replace the retained server value. Use a text input when arbitrary malformed
date text must remain editable and visible.

## Decode a Boolean checkbox {#decode-a-boolean-checkbox}

For a Boolean field, use
@:apiSymbol(def:scalive.FieldInput.checkbox)`FieldInput.checkbox()`@:@ to pair
strict decoding with typed initialization and updates:

@:sourceRegion(documentation/site/src/scalive/docs/examples/FormRecipes.scala, form-checkbox-input)

A checkbox is checked when its submitted value occurs in `rawValues`. Its
default checked value is `"true"`, or pass an explicit value. The helper does
not generate a hidden unchecked value.

The codec decodes absence as `false` and exactly one `"true"` value as `true`.
Any other single value produces `invalid_checkbox`; multiple values produce
`duplicate_value`, even when identical. Encoding `false` omits the value, while
encoding `true` emits the checked token. Both issues are configurable through
`invalidIssue` and `duplicateIssue`.

Custom tokens must match on both sides: pair `FieldInput.checkbox("yes")` with
`control.checkbox("yes")`. Matching is exact, without trimming or case folding;
an explicitly configured empty token is distinct from absence. The default
codec rejects `"on"` and `"false"`, and does not accept hidden unchecked fallback
inputs. In change and submit payloads, omitted or disabled controls decode as
false; the codec does not preserve their previous values. A checkbox-only
[repeated row](repeated-form-rows.md#model-repeated-rows) still needs its
`row.presence()` control to retain the row when unchecked. Requiring a checked
value is a separate semantic rule, expressible with
`field.validate(issue)(identity)`.

## Offer several checkbox choices {#offer-several-checkbox-choices}

For repeated checkbox choices, use `Root.texts`, not the Boolean
`FieldInput.checkbox` codec. A control's `item(key: String)` returns a
`FormControlItem`, so each checkbox has its own label target while all items
submit under the same field name:

@:sourceRegion(documentation/site/src/scalive/docs/examples/FormRecipes.scala, form-repeated-checkbox-items)

The recipe uses a static `"news"` token and a `Signal[String]` research token;
both `item.checkbox(checkedValue, mods*)` overloads require an explicit token.
Each item's `id` and `name` are `Signal[String]`. Here every name is exactly
`subscriptions[topics]`: no item key, index, or extra `[]` is appended. A
submission with no successful items decodes to `Vector.empty`.

An item key is an immutable, stable identity, independent of its submitted
token; it is not a signal. Arbitrary strings, including the empty string, are
allowed; `null` is rejected. Keys are encoded losslessly into ASCII IDs scoped
to the field, its repeated-row identity when present, and the form instance
when the form is
[bound](form-feedback-and-recovery.md#choose-when-feedback-appears). The exact
ID format is private: use `item.id`, not a reconstructed ID. Keep keys unique
among items of the same rendered control.

Checked state uses exact membership of the current token in the parent's
`rawValues`. Items with different keys but the same token are checked together;
their submitted values do not distinguish them. Changing a reactive token updates
the checkbox projection but does not rewrite those raw values or migrate an old
selection. Native successful-control rules still apply: unchecked, disabled, and
removed checkboxes are omitted from the next form snapshot. There are no hidden
unchecked fallbacks or automatic preservation of omitted selections.

Use a `fieldset` and `legend` for the group and a separate label targeting each
item's ID. Pass the parent's `validationAttributes` to each checkbox explicitly
and render one parent `errorFeedback` for the group. Configure any application
`.onBlur(...)` callback on the parent **before** calling `.item(...)`. With
[`FormFeedback.AfterBlur`](form-feedback-and-recovery.md#choose-when-feedback-appears),
blurring **any** item makes the parent's feedback eligible, including when focus
moves to another item in the same group. This is not whole-group blur; custom
composite widgets still own that boundary.

For dynamic choices, use `.splitBy` with stable choice keys and create each item
inside the keyed rendering scope. Do not use changing tokens or display indexes
as identity. Choice keys distinguish checkboxes for one repeated-value field;
they are not form-row keys.

## Route multiple submit actions {#route-multiple-submit-actions}

Use one definition-owned @:apiSymbol(class:scalive.FormSubmitter)`FormSubmitter`@:@
when several native submit buttons send the same typed form to different
operations, such as **Preview profile** and **Save profile**. Give each enum
case an explicit wire value so renaming a Scala case does not silently change
the browser contract:

@:sourceRegion(documentation/site/src/scalive/docs/examples/ProfileFormExample.scala, profile-form-submitter)

Bind the submitter once on the form, so the submit message receives the decoded
action alongside the event:

@:sourceRegion(documentation/site/src/scalive/docs/examples/ProfileFormExample.scala, profile-form-submit-binding)

Render each button from the same submitter:

@:sourceRegion(documentation/site/src/scalive/docs/examples/ProfileFormExample.scala, profile-form-submit-buttons)

Then handle each decoded action:

@:sourceRegion(documentation/site/src/scalive/docs/examples/ProfileFormExample.scala, profile-form-submit-handler)

The binding decodes exactly one value from lossless `FormData`. Missing, unknown,
and duplicate values remain distinct `DecodeError` cases. There is no implicit
default for an Enter-triggered or programmatic submission without a named
submitter. Every decoded action remains untrusted and must be authorized before
performing state-changing work.

The action result and `event.form.result` are deliberately independent. A
preview can inspect the rebuilt field state even when the complete domain
product is invalid, while Save can require a valid value. All buttons still
submit the form, so all validation errors become visible. Keep continuous
validation on `onChange`; use an ordinary typed click message for an operation
that does not need the submitted form.

The default browser name is generated below the form root in Scalive's reserved
namespace. Supply a checked custom name only when an existing HTTP or Phoenix
boundary requires one:

```scala fragment
val CompatibleSubmitter =
  Definition.submitter(Intent.values, wireName = "action")(_.wireValue)
```

Use `Submitter.attributes(intent)` for a native submit `input`, a
form-associated external control, or other custom markup. The runnable
[typed profile form](../examples/profile-form.md) demonstrates both buttons.

## Write a custom input {#write-a-custom-input}

A custom `FieldInput` defines both structural decoding and the inverse used by
typed server updates. Render it with ordinary HTML and the control's
`inputAttributes`, which carry the retained raw value rather than the decoded
domain value, so a text control can show malformed input even when decoding
fails:

@:sourceRegion(documentation/site/src/scalive/docs/examples/FormRecipes.scala, form-custom-field-input)

Use the lower-level `FormCodec` only when a custom control or transport adapter
does not need definition-owned state, bounded projection, or interaction-aware
errors:

@:sourceRegion(documentation/site/src/scalive/docs/examples/FormRecipes.scala, form-raw-codec)

## Related tasks {#related-tasks}

- Choose when each control's errors appear with [Form feedback and recovery](form-feedback-and-recovery.md#choose-when-feedback-appears).
- Repeat a group of controls with [Repeated form rows](repeated-form-rows.md#prerequisites).
- Accept files with [File uploads](uploads-and-consumption.md#prerequisites).
