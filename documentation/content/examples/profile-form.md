{%
title = "Typed profile form"
description = "Schema-owned fields and a typed submitter rebuild validated profile previews and saves from native form events."
order = 6
section = examples
%}

A typed form with three validated fields and two submit buttons. Valid input
becomes a `Profile`, and the button that submitted the form becomes a typed
`Intent`.

## Try It {#try-it}

1. Type `ada` into **Email**. "Enter a valid email address." appears under that
   field, while the fields you have not used stay quiet.
2. Click **Preview profile** with the other fields still empty. Every field now
   shows its error.
3. Fill in **Name**, **Email**, and **Biography**, then click **Preview
   profile**. A status reads "Previewing *name*'s profile." Click **Save
   profile**, and it reads "Saved *name*'s profile."
4. Click **Reset form** to clear every field and message.

@:example(profile-form)

## How It Works {#how-it-works}

- `Profile.Definition` declares the fields and their validation once, and
  `Root.product[Profile]` decodes valid input into a `Profile`.
- `Profile.Definition.onChange` stores each change event's form, which shows
  errors only for the fields the visitor has used. `onSubmit` shows them all.
- `onSubmit(Profile.Submitter)` also decodes the button that submitted the form
  into `Profile.Intent.Preview` or `Profile.Intent.Save`.
- `submission.replaceTextWith("Saving...")` relabels the save button while the
  submission is in flight.

## Related Guides {#related-guides}

- [Typed forms and validation](../guides/typed-forms-and-validation.md#define-a-rooted-form)
  builds this kind of form step by step.
- [Form feedback and recovery](../guides/form-feedback-and-recovery.md#prerequisites)
  explains when errors appear.
