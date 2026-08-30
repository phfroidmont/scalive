{%
title = "Typed forms and validation"
description = "Decode rooted browser forms into domain values with normalization, recovery, richer controls, and accessible feedback."
order = 11
section = guides
group = "Interfaces and input"
%}

## Before You Start {#prerequisites}

You need a `LiveView` with a typed model and messages, and you should be able to
render controls and bind browser events. Review [Models and messages](../learn/models-and-messages.md)
or [HTML and event bindings](html-dsl-and-event-bindings.md) if either part is
not yet familiar.

## Define A Rooted Form {#define-a-rooted-form}

Start with a @:apiSymbol(class:scalive.FormRoot)`FormRoot`@:@, define fields relative
to it, and combine those fields into one domain constructor:

```scala
final case class Profile(name: String, email: String)

object Profile:
  val Root = FormRoot("profile")

  val Name = Root
    .string("name")
    .map(_.trim)
    .required("validation.name.required")

  val Email = Root
    .string("email")
    .map(_.trim)
    .required("validation.email.required")
    .validate("validation.email.invalid")(EmailPattern.matches)

  val Definition = Root.form(Profile.apply)(Name, Email)
```

The stable root value gives each field a complete browser name such as
`profile[name]`. Its singleton owner type prevents fields from another root,
even one with the same runtime name, from being combined accidentally. The
constructor produces `Profile` only when every field decodes successfully.

Normalize before validating. Here @:apiSymbol(def:scalive.FormField.map)`map`@:@
runs before @:apiSymbol(def:scalive.FormField.required)`required`@:@, so
whitespace-only input is blank and valid values enter the domain without
surrounding whitespace.

## Accumulate Field Errors {#accumulate-field-errors}

Fields combined by @:apiSymbol(def:scalive.FormRoot.form)`FormRoot.form`@:@
accumulate independent decoding errors in field order. An invalid name and
email therefore produce both path-specific errors rather than stopping after
the first field.

Use `string` for a scalar that treats absence as `""` and rejects duplicates,
`requiredString` for exactly one non-empty value, `optionalString` for
`Option[String]`, and `strings` for repeated values. Use `Root.field` when a
custom decoder must own cardinality and validation. Then compose normalization
and domain rules with `map`, `required`, and `validate`.

Each error retains its @:apiSymbol(class:scalive.FormPath)`FormPath`@:@, allowing
rendering to associate feedback with the exact input. The complete executable
profile definition also limits biography length:

@:sourceRegion(documentation/site/src/scalive/docs/examples/ProfileFormExample.scala, profile-form-example)

## Keep Form State In The Model {#keep-form-state-in-the-model}

Create pristine form state during mount and store the
@:apiSymbol(class:scalive.RootedForm)`RootedForm`@:@ in the model:

`Task[A]` is the effect returned from LiveView lifecycle methods, and
`ZIO.succeed` creates one that cannot fail.

```scala
def mount(ctx: MountContext): Task[Model] =
  ZIO.succeed(Model(Profile.Definition.initial()))
```

Pass owner-checked initial raw values when editing existing data:

```scala
Profile.Definition.initial(
  Profile.Name.initial(existing.name),
  Profile.Email.initial(existing.email)
)
```

@:apiSymbol(def:scalive.FormDefinition.initial)`FormDefinition.initial`@:@ decodes
the initial raw values immediately, so state may contain required-field errors.
Those errors are intentionally not visible yet: the form has not been submitted
and no field is used.

Rebuild the rooted form from every typed event. This preserves raw browser
input, decoded values or errors, used fields, and submission state together:

```scala
case Msg.Validate(event) =>
  ZIO.succeed(model.copy(form = Profile.Definition.from(event), saved = None))
```

## Handle Change And Submit Events {#handle-change-and-submit-events}

Bind both events through the rooted form:

```scala
form(
  idAttr := "profile-form",
  profileForm.onChange(Msg.Validate(_)),
  profileForm.onSubmit(Msg.Save(_)),
  // fields and actions
)
```

Both messages carry @:apiSymbol(class:scalive.FormEvent)`FormEvent[Profile]`@:@.
Its @:apiSymbol(val:scalive.FormEvent.value)`value`@:@ is either accumulated
@:apiSymbol(class:scalive.FormErrors)`FormErrors`@:@ or the decoded `Profile`.
Change events retain their target and used-field state. Submit events set
`submitted = true`, making all relevant feedback visible. `submitter` identifies
the successful named submit control when the client supplies one.

On submit, persist or pass the domain value only from the `Right` branch. Do not
re-decode strings manually in `handleMessage`:

```scala
case Msg.Save(event) =>
  event.value match
    case Right(profile) => save(profile).as(model.copy(
      form = Profile.Definition.from(event),
      saved = Some(profile)
    ))
    case Left(_) => ZIO.succeed(model.copy(
      form = Profile.Definition.from(event),
      saved = None
    ))
```

## Recover A Form After Reconnect {#recover-a-form-after-reconnect}

Give a recoverable form a stable, unique DOM `id` and keep its change binding.
Phoenix normally recovers client form values through the change event after a
LiveView reconnect. Rebuilding with `Definition.from(event)` applies the same
codec and restores the raw values and validation state.

Use a dedicated typed recovery message when recovery needs different behavior:

```scala
form(
  idAttr := "profile-form",
  profileForm.onChange(Msg.Validate(_)),
  profileForm.onSubmit(Msg.Save(_)),
  profileForm.onRecover(Msg.Recover(_)),
  // controls
)

case Msg.Recover(event) =>
  ZIO.succeed(model.copy(form = Profile.Definition.from(event)))
```

The recovery callback runs for successful and failed decoding. Inspect
`event.recovery` when one message type handles multiple event sources. Recovery
is distinct from submission: it does not itself set `submitted = true`, so
used-field visibility still comes from recovered payload markers.

Disable client auto-recovery explicitly when replay would be unsafe or the
server is authoritative:

```scala
form(
  idAttr := "payment-form",
  paymentForm.disableRecovery,
  paymentForm.onSubmit(Msg.Pay(_)),
  // controls
)
```

Current recovery is browser/LiveView protocol recovery, not durable draft
storage. It does not survive a deliberate page exit, replace a database-backed
draft, recover file input bytes, or merge concurrent edits. Persist drafts in an
application service when those guarantees are required.

## Render Richer Controls {#render-richer-controls}

Ask the rooted form for a field view, then use its generated ID, name, current
raw value, and validation helpers:

```scala
val emailField = profileForm.field(Profile.Email)
val messages = Map(
  "validation.email.required" -> "Email is required.",
  "validation.email.invalid"  -> "Enter a valid email address."
)

label(forId := emailField.id, "Email")
emailField.email(emailField.validationAttributes)
emailField.errorFeedback { error =>
  messages(error.message)
}
```

`FormFieldView` currently provides `text`, `email`, `password`, `hidden`,
`checkbox`, `textarea`, and `select` helpers. They append caller modifiers, so
normal attributes such as `autocomplete`, `maxlength`, `required`, `multiple`,
or CSS classes remain available:

```scala
val bioField  = profileForm.field(Profile.Bio)
val roleField = profileForm.field(Profile.Role)

bioField.textarea(rows := 6, bioField.validationAttributes)
roleField.select(
  List("reader" -> "Reader", "editor" -> "Editor"),
  roleField.validationAttributes
)
```

A checkbox is checked when its submitted value occurs in `rawValues`. Its
default checked value is `"true"`, or pass an explicit value. The helper does
not generate a hidden unchecked value, so model absence deliberately in the
field decoder. A select marks every option found in `rawValues`; for a
`multiple` select, use a repeated-value field such as `Root.strings`.

There are no current typed convenience helpers for numeric, date, radio-group,
or file controls. Use `Root.field` plus ordinary HTML controls for custom
decoding, and use `liveFileInput` with the upload API for files. The existing
helpers preserve raw strings; they do not parse numbers or dates implicitly.

@:apiSymbol(def:scalive.FormFieldView.validationAttributes)`validationAttributes`@:@
connects the input to feedback and adds `aria-invalid` when visible errors
exist. @:apiSymbol(def:scalive.FormFieldView.errorFeedback)`errorFeedback`@:@
renders a stable live region whose errors remain hidden until that field is
used or the form is submitted. Scalive owns the feedback ID, live-region
attributes, visibility rules, and `form-errors` and `form-error` wrappers. The
renderer owns only each error's presentation, so `FormError.message` may remain
a stable localization key instead of pre-rendered text.

The signal-backed field accepts the same pattern and passes a retained error
signal to the renderer:

```scala
val emailField = profileFormSignal.field(Profile.Email)

emailField.errorFeedback { error =>
  error.map(value => messages(value.message))
}
```

Use a real `label` with `forId` and keep success feedback in a separate status
region.

## Reset Deliberately {#reset-deliberately}

A reset message should construct fresh initial form state and clear any saved
result. This resets raw values, used fields, submission state, and visible
errors together. Replacing only input strings can leave stale validation state
behind.

Try change, invalid submit, valid submit, and reset behavior in the
[typed profile form example](../examples/profile-form.md).

## Related Tasks {#related-tasks}

- Use [Ordinary HTTP forms and redirects](http-forms-and-redirects.md) when the browser should perform a normal GET or POST.
- Use [File uploads](uploads-and-consumption.md) for file inputs and resource ownership.
- Use [Authentication and sessions](authentication.md) for credential forms and protected routes.
- Use [Testing](testing.md) to exercise duplicate fields, invalid submission, recovery, and reset behavior.
