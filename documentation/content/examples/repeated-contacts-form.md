{%
title = "Stable repeated contact rows"
description = "Caller-provided row keys preserve contact identity while typed form operations add, remove, and reorder rows."
order = 5
section = examples
%}

Edit a contact to see row-local validation, add a blank contact, and move rows to
watch their `contact-*` identities remain stable in the order summary. Remove a
row, submit valid contacts, then reset to restore the original keyed rows.

@:example(repeated-contacts-form)

Related guidance: [model repeated rows](../guides/typed-forms-and-validation.md#model-repeated-rows).
