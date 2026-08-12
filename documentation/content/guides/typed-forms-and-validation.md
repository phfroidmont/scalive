{%
title = "Typed forms and validation"
description = "Decode rooted browser forms into domain values with normalization, validation, and accessible feedback."
order = 20
section = guides
%}

## Define A Rooted Form {#define-a-rooted-form}

Start with a @:apiSymbol(class:scalive.FormRoot)`FormRoot`@:@, define fields relative to it, and combine those fields
into one domain constructor:

```scala
final case class Profile(name: String, email: String)

object Profile:
  val Root = FormRoot("profile")

  val Name = Root
    .string("name")
    .map(_.trim)
    .required("Name is required.")

  val Email = Root
    .string("email")
    .map(_.trim)
    .required("Email is required.")
    .validate("Enter a valid email address.")(EmailPattern.matches)

  val Definition = Root.form(Profile.apply)(Name, Email)
```

The root gives each field a complete browser name such as `profile[name]` and
keeps fields from unrelated forms out of the definition. The constructor
produces `Profile` only when every field decodes successfully.

Normalize before validating. In this example, @:apiSymbol(def:scalive.FormField.map)`map`@:@ runs before
@:apiSymbol(def:scalive.FormField.required)`required`@:@, so whitespace-only input is blank and valid values enter the domain
model without surrounding whitespace.

## Accumulate Field Errors {#accumulate-field-errors}

Fields combined by @:apiSymbol(def:scalive.FormRoot.form)`FormRoot.form`@:@ accumulate independent decoding errors in field
order. An invalid name and email therefore produce both path-specific errors,
rather than stopping after the first field.

Use @:apiSymbol(def:scalive.FormField.required)`required`@:@ for blank strings and
@:apiSymbol(def:scalive.FormField.validate)`validate`@:@ for domain rules that retain the decoded field type. Each error keeps its
@:apiSymbol(class:scalive.FormPath)`FormPath`@:@, which
lets rendering associate feedback with the exact input.

The complete executable profile definition also limits biography length:

@:sourceRegion(documentation/site/src/scalive/docs/examples/ProfileFormExample.scala, profile-form-example)

## Keep Form State In The Model {#keep-form-state-in-the-model}

Create pristine form state during mount and store the
@:apiSymbol(class:scalive.RootedForm)`RootedForm`@:@ in the
@:apiSymbol(trait:scalive.LiveView)`LiveView`@:@ model:

```scala
def mount(ctx: MountContext): LiveIO[Model] =
  ZIO.succeed(Model(Profile.Definition.initial()))
```

@:apiSymbol(def:scalive.FormDefinition.initial)`FormDefinition.initial`@:@ decodes the initial raw values immediately, so its state may contain
errors for required fields. Those errors are intentionally not visible yet:
the form has not been submitted and no field is used.

Rebuild the rooted form from each typed event. This preserves raw browser input,
decoded values or errors, used fields, and submission state together:

```scala
case Msg.Validate(event) =>
  ZIO.succeed(model.copy(form = Profile.Definition.from(event), saved = None))
```

## Handle Change And Submit Events {#handle-change-and-submit-events}

Bind both events through the rooted form:

```scala
form(
  profileForm.onChange(Msg.Validate(_)),
  profileForm.onSubmit(Msg.Save(_)),
  // fields and actions
)
```

Both messages carry @:apiSymbol(class:scalive.FormEvent)`FormEvent[Profile]`@:@. Its
@:apiSymbol(val:scalive.FormEvent.value)`value`@:@ is either accumulated
@:apiSymbol(class:scalive.FormErrors)`FormErrors`@:@ or the decoded `Profile`. Change events also retain their target
and used-field state. Submit events mark submitted fields as used, making all
relevant feedback visible.

On submit, persist or pass the domain value only from the `Right` branch. Do not
re-decode strings manually in @:apiSymbol(def:scalive.LiveView.handleMessage)`handleMessage`@:@:

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

## Render Fields And Feedback {#render-fields-and-feedback}

Ask the @:apiSymbol(class:scalive.RootedForm)`RootedForm`@:@ for a view of each definition, then use its generated ID,
name, current raw value, and validation helpers:

```scala
val emailField = profileForm.field(Profile.Email)

label(forId := emailField.id, "Email")
emailField.email(emailField.validationAttributes)
emailField.errorFeedback()
```

@:apiSymbol(def:scalive.FormFieldView.validationAttributes)`validationAttributes`@:@ connects the input to its feedback and adds
`aria-invalid` when visible errors exist. @:apiSymbol(def:scalive.FormFieldView.errorFeedback)`errorFeedback`@:@ renders a live region
whose messages remain hidden until that field is used or the form is submitted.
Use a real `label` with @:apiSymbol(lazy-val:scalive.forId)`forId`@:@ and keep success feedback in a separate status
region.

## Reset Deliberately {#reset-deliberately}

A reset message should construct fresh initial form state and clear any saved
result. This resets raw values, used fields, submission state, and visible
errors together. Replacing only the input strings can leave stale validation
state behind.

Try change, invalid submit, valid submit, and reset behavior in the
[typed profile form example](../examples/profile-form.md).
