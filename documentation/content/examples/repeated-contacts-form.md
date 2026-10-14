{%
title = "Stable repeated contact rows"
description = "Caller-provided row keys preserve contact identity while typed form operations add, remove, and reorder rows."
order = 7
section = examples
%}

A form with a list of contacts. Each row has a stable key, such as
`contact-1`, that stays with the contact when rows are added, removed, or
reordered.

## Try it {#try-it}

1. Click **Move down** on the first contact. **Current order** lists
   `contact-2 — Grace Hopper` before `contact-1 — Ada Lovelace`: the keys moved
   with their contacts.
2. Click **Add contact**. A blank `contact-3` row appears at the end.
3. Click **Save contacts**. The blank row shows "Name is required." and
   "Email is required.", and nothing is saved.
4. Click **Remove** on `contact-3`, then **Save contacts**. The form reads
   "Saved 2 contacts in the displayed order."
5. Click **Reset** to restore the two original rows.

@:example(repeated-contacts-form)

## How it works {#how-it-works}

- `Root.rows("contacts")` declares the repeated group, and
  `Group.product[Contact]((name = Name, email = Email))` declares one row.
- `Contacts.key(number)` builds each row key. The application chooses keys, so
  a row keeps its identity, and its errors, wherever it moves.
- `form.added`, `form.removed`, and `form.movedBefore` change the rows of the
  current form without losing the values typed into other rows.

## Related guides {#related-guides}

- [Repeated form rows](../guides/repeated-form-rows.md#model-repeated-rows)
  explains row keys and row operations.
