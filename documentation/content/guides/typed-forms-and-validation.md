{%
title = "Typed forms and validation"
description = "Define a form once, validate it as the visitor types, decode a valid submission into a domain value, and render fields with their errors."
order = 11
section = guides
group = "Interfaces and input"
%}

## Before you start {#prerequisites}

You need a `LiveView` with a typed model and messages, and you should be able to
render controls and bind browser events. [Your first form](../learn/your-first-form.md)
builds a complete two-field form step by step; start there if this is your first
Scalive form.

This guide follows one profile form from its definition to a reset. The pages
after it build on the same form:

- [Form controls](form-controls.md#prerequisites): textareas, selects,
  checkboxes, custom inputs, and several submit buttons.
- [Form feedback and recovery](form-feedback-and-recovery.md#prerequisites):
  when errors appear, and restoring a form after a reconnect.
- [Repeated form rows](repeated-form-rows.md#prerequisites): rows that can be
  added, removed, and reordered.
- [Saving forms](saving-forms.md#prerequisites): dirty state and saving in the
  background.

A filter form or a single select that needs no validation can skip the
definition: [read small forms](html-dsl-and-event-bindings.md#read-small-forms)
decodes their fields into a case class.

## Define a rooted form {#define-a-rooted-form}

Start with a @:apiSymbol(class:scalive.FormRoot)`FormRoot`@:@, define fields relative
to it, and combine those fields into one domain constructor:

@:sourceRegion(documentation/fixtures/guides/src/forms/Profile.scala, forms-definition)

The root gives each field a complete browser name such as `profile[name]`. Its
singleton owner type prevents fields from another root, even one with the same
runtime name, from being combined accidentally.
@:apiSymbol(def:scalive.FormRoot.product)`product`@:@ produces `Profile` only
when every field decodes successfully.

Normalize before validating. Here @:apiSymbol(def:scalive.FormField.map)`map`@:@
runs before @:apiSymbol(def:scalive.FormField.required)`required`@:@, so
whitespace-only input is blank and valid values enter the domain without
surrounding whitespace. A `FieldIssue` carries the stable message or
localization key and an optional machine-readable code.

The definition is plain data with no reference to a LiveView, so tests, other
pages, and [ordinary HTTP forms](http-forms-and-redirects.md#prerequisites) can
reuse it.

## Keep form state in the model {#keep-form-state-in-the-model}

Store the definition's `Form` type in the model. `Definition.Event` is the
matching event type, so model and message declarations stay tied to this exact
definition:

@:sourceRegion(documentation/fixtures/guides/src/forms/ProfileLiveView.scala, forms-model)

Create pristine form state during mount:

@:sourceRegion(documentation/fixtures/guides/src/forms/ProfileLiveView.scala, forms-mount)

Pass owner-checked typed initial values when editing existing data:

@:sourceRegion(documentation/fixtures/guides/src/forms/ProfileLiveView.scala, forms-initial-values)

@:apiSymbol(def:scalive.FormDefinition.initial)`FormDefinition.initial`@:@ decodes
the initial editable values immediately, so state may contain required-field errors.
Those errors are intentionally not visible yet: the form has not been submitted
and no field is used.

### Keep definitions static {#keep-definitions-static}

Declare each definition once, as a `val`, as above. Its forms, events, and
workflows are typed by that exact definition, which is what lets the model hold
a `Definition.Form` and a message carry a `Definition.Event`.
@:apiSymbol(def:scalive.FormDefinition.withLimits)`withLimits`@:@,
`map`, and `emap` return a new definition with new types, so apply them to the
`val` rather than building one per record.

Limits bound what an untrusted payload may contain, and a form's rows must fit
them: `initial` rejects more rows than `maxRowsPerGroup`. Choose a fixed bound
with headroom over any real data, and check rules that depend on the data, such
as how many rows one record may gain, in the handler or when saving.

When a definition must be built from the data, for example to show a
capacity error inline, its types belong to that one value. Keep it with its
form in a small class:

@:sourceRegion(documentation/fixtures/guides/src/forms/GuestListForm.scala, forms-per-record-definition)

A signal of such a form cannot name its schema, but its `field` and `presence`
controls do not need it. Read the row keys from the form value, and decode
events in the handler, because a binding is built once, with the static
definition:

@:sourceRegion(documentation/fixtures/guides/src/forms/GuestListForm.scala, forms-per-record-render)

## Handle change and submit {#handle-change-and-submit-events}

Bind both LiveView events through the form definition, and read fields from the
form stored in the model:

@:sourceRegion(documentation/fixtures/guides/src/forms/ProfileLiveView.scala, forms-render)

`Profile.Definition.onChange(Msg.Validate(_))` sends `Msg.Validate` each time a
control changes. The event already contains the rebuilt and validated form, so
the handler stores it:

@:sourceRegion(documentation/fixtures/guides/src/forms/ProfileLiveView.scala, forms-change)

Always store `event.form`. It keeps the rendered values and errors in step with
the browser; without it, the next render shows the previous form.

`Profile.Definition.onSubmit(Msg.Save(_))` sends `Msg.Save` when the visitor
submits. Use the domain value only from the `Right` branch of
`event.form.result`, and do not re-decode strings in `handleMessage`:

@:sourceRegion(documentation/fixtures/guides/src/forms/ProfileLiveView.scala, forms-submit)

A submitted form makes every error visible, while a changed form shows errors
only for fields the visitor has used.
[Form feedback and recovery](form-feedback-and-recovery.md#choose-when-feedback-appears)
covers the alternatives.

`onChange` installs Phoenix's `phx-change` binding, not the native HTML
`onchange` attribute. The LiveView client observes both browser `input` and
`change` events, so text fields normally produce change messages while the
visitor edits. Because the binding is on the form, each message contains the
form's successful controls and identifies the changed control. See
[HTML and event bindings](html-dsl-and-event-bindings.md#bind-events-to-messages)
for the distinction between LiveView bindings and native event attributes.

## Render fields and errors {#render-fields-and-errors}

Inside `view`, `model` is a `Signal[Model]`. `model.map(_.form)` selects the form
as a `Signal[Form]`, and its `field` method returns a
@:apiSymbol(class:scalive.FormControl)`FormControl`@:@ with the field's `id`,
`name`, current value, and errors. In the render recipe above, `name.text(...)`
renders a text input with all of them and `email.email(...)` an email input.

@:apiSymbol(def:scalive.FormControl.validationAttributes)`validationAttributes`@:@
connects the input to its feedback and adds `aria-invalid` when visible errors
exist. @:apiSymbol(def:scalive.FormControl.errorFeedback)`errorFeedback`@:@
renders a stable live region whose errors stay hidden until that field is used
or the form is submitted. Scalive owns the feedback ID, live-region attributes,
visibility rules, and the `form-errors` and `form-error` wrappers. You render
only each error, so `FormError.message` can stay a stable localization key. The
recipe translates each key:

@:sourceRegion(documentation/fixtures/guides/src/forms/ProfileLiveView.scala, forms-messages)

`errorFeedback` passes a `Signal[FormError]` to the renderer, so the recipe maps
it: `error.map(value => message(value.message))`. Use a real `label`
with `forId` for each input, and keep success feedback, such as the saved
message, in a separate status region.

### Render from a form value {#render-from-a-form-value}

`field` returns the same control on a plain `Form` value, for pages without a
form signal: static HTML rendered with
@:apiSymbol(def:scalive.StaticHtml.render)`StaticHtml.render`@:@, a root layout,
or a form that never changes, such as a login page. A control from a form value
renders exactly like one from a form signal, so one rendering helper serves
both:

@:sourceRegion(documentation/site/src/scalive/docs/examples/FormRecipes.scala, form-shared-controls)

`passwordInput` only sees a
@:apiSymbol(class:scalive.FormControl)`FormControl`@:@. A control from a form
value is fixed to that value; inside a LiveView, render a form that changes from
its signal so the page follows the model.

To read a field's data in a handler or test, use `fieldView`, which returns a
read-only @:apiSymbol(class:scalive.FormFieldView)`FormFieldView`@:@ with its
raw values, decoded input, and errors. A row's `fieldView` and the form's
`fieldViewOption` do the same for repeated rows.

## Reset deliberately {#reset-deliberately}

A reset message should construct fresh initial form state and clear any saved
result:

@:sourceRegion(documentation/fixtures/guides/src/forms/ProfileLiveView.scala, forms-reset)

This resets raw values, used fields, submission state, and visible errors
together. Replacing only input strings can leave stale validation state behind.

Try change, invalid submit, valid submit, and reset behavior in the
[typed profile form example](../examples/profile-form.md).

## Choose field types and combine errors {#accumulate-field-errors}

Fields combined by `product` accumulate independent decoding errors in field
order. An invalid name and email therefore produce both errors rather than
stopping after the first field. `product` takes a named tuple that names each
part after the case class field it fills, in order, as in
`Root.product[Profile]((name = Name, email = Email))`. The compiler checks the
names and the value types, so a missing, misnamed, or reordered field is
rejected even when two fields have the same type. A form with one field passes a
one-element named tuple, as in `Root.product[Note]((text = Text))`. Use
scalafmt 3.9.9 or later: with the `RedundantParens` rule, earlier versions
remove the inner parentheses, and the call no longer compiles.

Use `text` for a scalar that treats absence as its default and rejects
duplicates, `optionalText` for `Option[String]`, and `texts` for repeated
values. Use `Root.field("enabled", FieldInput.checkbox())` for a Boolean
checkbox, or supply a custom `FieldInput` to own decoding and encoding; see
[Form controls](form-controls.md#render-richer-controls). Then compose
normalization and domain refinement with `map`, `required`, `validate`, and
`emap`:

```scala fragment
val Age = Root.text("age").emap { text =>
  text.toIntOption.filter(_ >= 0).toRight(
    FieldIssues.one(FieldIssue("validation.age.invalid", Some("invalid_age")))
  )
}
```

`emap` preserves the editable input type (`String` here) while refining the
successful value to another type (`Int`). This is why typed updates can still
encode browser-editable input without requiring an inverse for every domain
refinement.

Use `FormDefinition.emap` after product construction when validation depends on
more than one field. Return errors from the pre-refinement definition so every
issue still has an owner-checked logical address:

@:sourceRegion(documentation/site/src/scalive/docs/examples/FormRecipes.scala, form-cross-field-validation)

Each error retains its owner-scoped @:apiSymbol(class:scalive.FormAddress)`FormAddress`@:@,
so rendering can attach feedback to the exact input.

## Update fields programmatically {#update-fields-programmatically}

Use @:apiSymbol(def:scalive.Form.updated)`Form.updated`@:@ when a server-side action
needs to replace one field with its typed editable input without rebuilding
@:apiSymbol(class:scalive.FormData)`FormData`@:@ or validation state manually. In
`handleMessage`, `model.form` is a plain `Form` value:

@:sourceRegion(documentation/fixtures/guides/src/forms/ProfileLiveView.scala, forms-update)

The input type comes from the field's `FieldInput`: `String` for `text`,
`Option[String]` for `optionalText`, `Vector[String]` for `texts`, and `Boolean`
for `checkbox`. Use `updatedRaw(field, Vector(...))` only when raw browser
values are explicitly needed, such as malformed-control tests. The complete form
is decoded again after every update, while its interaction state is preserved.

For fields submitted repeatedly under one exact name, use
@:apiSymbol(def:scalive.Form.appended)`appended`@:@ to add one value and
@:apiSymbol(def:scalive.Form.removedAt)`removedAt`@:@ to remove one by its
position among that field's values. These operations do not add or remove rows
whose controls use several different names; use
[repeated form rows](repeated-form-rows.md#model-repeated-rows) for those.

Phoenix normally preserves browser-managed state for a focused control during
DOM patches. Add `phx.patchFocused := true` to a control when a programmatic
form update must replace its visible value while it remains focused.

## Read event details {#read-event-details}

Both messages carry `Profile.Definition.Event`. Besides `event.form`,
`event.kind` distinguishes `Changed`, `Submitted`, and `Recovered`, and
`event.meta` contains the logical target, optional raw submitter metadata,
browser target, metadata, and diagnostics.

Use `event.meta.target` when work should be narrowed to the logical field that
changed. It is a `FormAddress`, not an unchecked browser string:

@:sourceRegion(documentation/site/src/scalive/docs/examples/FormRecipes.scala, form-event-target)

`FormValues`, available as `event.form.values`, is the immutable, canonical
editable state. It contains schema-owned scalar values and stable keyed rows,
but not the event target, used fields, or submission metadata. Values from
different definition instances are intentionally unequal. Compare values to a
baseline for dirty checks, and use `Definition.fromValues(values)` when
interaction should become pristine or `fromValues(values, interaction)` when
deliberately preserving it.

The standard Phoenix browser client includes a named submit control in
`event.data` rather than `event.rawSubmitter`; use a definition-owned
[typed submitter](form-controls.md#route-multiple-submit-actions) for
application action routing. Use `RawFormEvent[A]` only for an explicitly
low-level `on.change.form(formCodec)` or equivalent `FormCodec` binding;
definition-backed events should use `Definition.Event` and `event.form`.

## Continue through input {#continue-through-input}

This guide is the typed-form step in the interface and input path:

1. [HTML and event bindings](html-dsl-and-event-bindings.md) for controls and browser events.
2. Typed forms and validation for Live-owned structured input, followed by
   [form controls](form-controls.md), [feedback and recovery](form-feedback-and-recovery.md),
   [repeated rows](repeated-form-rows.md), and [saving](saving-forms.md).
3. [Ordinary HTTP forms and redirects](http-forms-and-redirects.md) for browser POST and Post/Redirect/Get.
4. [Testing](testing.md#test-typed-form-behavior) for validation visibility, submission, and stable rows.

## Related tasks {#related-tasks}

- Use [Ordinary HTTP forms and redirects](http-forms-and-redirects.md) when the browser should perform a normal GET or POST.
- Use [Guard unsaved changes](navigation-guards.md#prerequisites) when leaving a dirty form should require confirmation.
- Use [File uploads](uploads-and-consumption.md) for file inputs and resource ownership.
- Use [Authentication and sessions](authentication.md) for credential forms and protected routes.
- Use [Testing](testing.md#test-typed-form-behavior) to exercise changed-field visibility, invalid submission, stable rows, and reset behavior.
