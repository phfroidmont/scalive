{%
title = "HTML and event bindings"
description = "Build typed HTML, compose token-list attributes, bind clicks, keys, scrolling, and other browser events to messages, key collection entries, and render static HTML."
order = 10
section = guides
group = "Interfaces and input"
%}

## Before you start {#prerequisites}

Import `scalive.*` wherever you construct HTML. Static pages use ordinary Scala
values, while LiveViews derive dynamic content from a model `Signal`. The DSL
uses Scala expressions and collections rather than introducing a template
language. This guide builds on
[Rendering, bindings, and diffs](../learn/rendering-and-dom-updates.md#render-from-the-model),
which introduces views, conditional content, and keyed lists.

## Build an HTML tree {#build-an-html-tree}

Call tag values such as @:apiSymbol(lazy-val:scalive.div)`div`@:@,
@:apiSymbol(lazy-val:scalive.button)`button`@:@, and @:apiSymbol(lazy-val:scalive.table)`table`@:@,
and pass attributes and children in document order:

@:sourceRegion(documentation/fixtures/guides/src/bindings/CartMarkup.scala, bindings-tree)

A tag call produces an @:apiSymbol(class:scalive.HtmlElement)`HtmlElement[Msg]`@:@. Strings
become escaped text content, nested elements become child content, and an
`Option` or other `IterableOnce` of modifiers or elements can be passed
directly, as in `ul(items.map(item => li(item.name)))`. Derive dynamic text and
attributes with signal `.map`, as the item count does here. The `view` method
constructs this signal-backed view graph once per graph lifetime rather than
rebuilding the tree after every model update.

Use the named tag definitions when they exist. The DSL gives Scala-safe names to
HTML names that would otherwise conflict with Scala or another exported symbol:
for example, @:apiSymbol(lazy-val:scalive.sectionTag)`sectionTag`@:@,
@:apiSymbol(lazy-val:scalive.headerTag)`headerTag`@:@,
@:apiSymbol(lazy-val:scalive.htmlRootTag)`htmlRootTag`@:@,
@:apiSymbol(lazy-val:scalive.headTag)`headTag`@:@, and
@:apiSymbol(lazy-val:scalive.idAttr)`idAttr`@:@.
Use @:apiSymbol(def:scalive.htmlTag)`htmlTag(name)`@:@ only when the framework does not
provide the element you need.

## Set typed attributes {#set-typed-attributes}

Assign attributes with @:apiSymbol(def:scalive.HtmlAttr.:=)`:=`@:@. Each @:apiSymbol(class:scalive.HtmlAttr)`HtmlAttr[V]`@:@
accepts its declared Scala value type, either as a plain value or as a signal of
that type. `cls := "cart"` takes a `String`, and `disabled := true` takes a
`Boolean`. Inside `view`, where `model` is a `Signal[Model]`,
`disabled := model.map(_.lines.isEmpty)` takes a `Signal[Boolean]` and updates
when the model changes. Boolean presence attributes are emitted when true and
omitted when false.

### Compose token-list attributes {#compose-token-list-attributes}

Repeat `cls` when several modifiers each add classes to one element. Some HTML
attributes contain a space-separated token list rather than one scalar value.
Their @:apiSymbol(class:scalive.CompositeHtmlAttr)`CompositeHtmlAttr`@:@
definitions let independent modifiers contribute to one rendered attribute. The
built-in @:apiSymbol(val:scalive.cls)`cls`@:@,
@:apiSymbol(lazy-val:scalive.rel)`rel`@:@, and
@:apiSymbol(lazy-val:scalive.role)`role`@:@ definitions use this behavior:

@:sourceRegion(documentation/fixtures/guides/src/bindings/CartMarkup.scala, bindings-token-list)

Values are split on HTML ASCII whitespace. Empty tokens are ignored, exact
duplicate tokens keep their first position, and declaration order is preserved. The
example therefore renders `class="panel selected expanded wide"` while `expanded`
is `true`, and `class="panel selected wide"` otherwise. An attribute with no
remaining tokens is omitted.

Static values, signals, optional signals, and directly selected modifiers can be
mixed. Use @:apiSymbol(def:scalive.CompositeHtmlAttr.optional)`cls.optional(...)`@:@
for a `Signal[Option[String]]`; a `cls := ...` branch selected by `when` or
@:apiSymbol(extension:scalive.Signal.choose)`choose`@:@ joins the same attribute.
Scalar attributes do not compose or use last-declaration-wins semantics. Declaring
the same scalar attribute more than once is an error, as is mixing composite and
scalar definitions for the same rendered name.

For `role`, order is significant because user agents use the first supported role.
Scalive does not validate ARIA role names; prefer an element with the required native
semantics when one exists. Use
@:apiSymbol(def:scalive.CompositeHtmlAttr.apply)`CompositeHtmlAttr(name)`@:@ for a
custom attribute only when its grammar is a space-separated token list.

### Use namespaced and custom attributes {#use-namespaced-and-custom-attributes}

Use @:apiSymbol(def:scalive.dataAttr)`dataAttr(name)`@:@ for application `data-*` attributes and
the @:apiSymbol(object:scalive.aria)`aria`@:@ namespace for ARIA attributes:

@:sourceRegion(documentation/fixtures/guides/src/bindings/CartMarkup.scala, bindings-namespaced-attributes)

For an attribute absent from the DSL, use @:apiSymbol(def:scalive.htmlAttr)`htmlAttr(name, encoder)`@:@ with
an explicit encoder rather than assembling rendered HTML:

@:sourceRegion(documentation/fixtures/guides/src/bindings/CartMarkup.scala, bindings-custom-attribute)

Avoid @:apiSymbol(def:scalive.rawHtml)`rawHtml`@:@ for ordinary content. It bypasses escaping and should be limited
to HTML that the application already trusts.

## Bind events to messages {#bind-events-to-messages}

To react when the user acts on an element, add a binding from the
@:apiSymbol(object:scalive.on)`on`@:@ namespace that produces the view's message
type. A constant message is enough when the event carries no application value:

@:sourceRegion(documentation/fixtures/guides/src/bindings/CartMarkup.scala, bindings-messages)

The message type remains part of the whole tree. If
@:apiSymbol(def:scalive.LiveView.view)`view`@:@ returns
`HtmlElement[Msg]`, a binding that produces another message type does not
compile. To send the current value of a row, such as the product of one cart
line, pass a `Signal` of the message as shown in
[Send a row's value with its message](../learn/rendering-and-dom-updates.md#send-row-specific-messages).

`on` creates Phoenix LiveView `phx-*` bindings, not native inline HTML event
attributes: `on.click` renders `phx-click`, and `on.change` renders `phx-change`
rather than `onchange`. Native inline attributes such as `onchange` are available
only through the generic @:apiSymbol(def:scalive.htmlAttr)`htmlAttr`@:@ escape
hatch. They contain raw client-side code and do not dispatch a typed Scalive
message. Prefer the explicit boundaries in
[Browser commands, events, and hooks](browser-integration.md#choose-the-boundary)
when behavior must run in the browser.

### Choose a binding {#choose-a-binding}

Each row binds one family of browser events. Every binding accepts a message, a
`Signal` of a message, or a client-side
[`JS` command](browser-integration.md#compose-client-only-commands), except the
connection bindings in the last row, which accept only `JS` commands.

| Binds | Use | Example |
| --- | --- | --- |
| A click on the element or inside it | @:apiSymbol(lazy-val:scalive.on.click)`on.click`@:@ | `button(on.click(Msg.Save), "Save")` |
| A click that starts outside the element | @:apiSymbol(lazy-val:scalive.on.clickAway)`on.clickAway`@:@ | `div(on.clickAway(Msg.Close), "Menu")` |
| A key pressed or released while the element has focus | @:apiSymbol(lazy-val:scalive.on.keyDown)`on.keyDown`@:@, @:apiSymbol(lazy-val:scalive.on.keyUp)`on.keyUp`@:@ | `input(on.keyDown.key(Key.Enter).withValue(Msg.AddTag(_)))` |
| A key pressed or released anywhere in the window | @:apiSymbol(lazy-val:scalive.on.windowKeyDown)`on.windowKeyDown`@:@, @:apiSymbol(lazy-val:scalive.on.windowKeyUp)`on.windowKeyUp`@:@ | `div(on.windowKeyDown.key(Key.Escape)(Msg.Close))` |
| The element itself gaining or losing focus | @:apiSymbol(lazy-val:scalive.on.focus)`on.focus`@:@, @:apiSymbol(lazy-val:scalive.on.blur)`on.blur`@:@ | `input(on.blur.withValue(Msg.NameEntered(_)))` |
| The browser window gaining or losing focus | @:apiSymbol(lazy-val:scalive.on.windowFocus)`on.windowFocus`@:@, @:apiSymbol(lazy-val:scalive.on.windowBlur)`on.windowBlur`@:@ | `div(on.windowBlur(Msg.Pause))` |
| A form changing or being submitted | @:apiSymbol(lazy-val:scalive.on.change)`on.change`@:@, @:apiSymbol(lazy-val:scalive.on.submit)`on.submit`@:@ | `form(on.submit(Msg.Save), button("Save"))` |
| The element's first or last child reaching the top or bottom of the viewport | @:apiSymbol(lazy-val:scalive.on.viewportTop)`on.viewportTop`@:@, @:apiSymbol(lazy-val:scalive.on.viewportBottom)`on.viewportBottom`@:@ | `ul(idAttr := "results", on.viewportBottom(Msg.LoadMore))` |
| Fewer events from any binding above | @:apiSymbol(def:scalive.HtmlAttrBinding.debounce)`debounce`@:@, @:apiSymbol(def:scalive.HtmlAttrBinding.throttle)`throttle`@:@, @:apiSymbol(def:scalive.HtmlAttrBinding.debounceOnBlur)`debounceOnBlur`@:@ | `on.keyUp.debounce(300.millis).withValue(Msg.Search(_))` |
| The LiveView connecting or losing its connection | @:apiSymbol(lazy-val:scalive.connection.onConnect)`connection.onConnect`@:@, @:apiSymbol(lazy-val:scalive.connection.onDisconnect)`connection.onDisconnect`@:@ | `p(connection.onDisconnect(JS.addClass("offline")))` |

### Combine bindings in one view {#combine-bindings-in-one-view}

This LiveView filters a product list as the visitor types, closes a help panel
on an outside click or the Escape key, and shows more results when the last row
scrolls into view:

@:sourceRegion(documentation/fixtures/guides/src/bindings/ProductSearchLiveView.scala, bindings-liveview)

The sections below explain each binding and its limits.

### Read event values {#read-event-values}

Use @:apiSymbol(def:scalive.HtmlAttrBinding.withValue)`withValue`@:@ when the
event's `value` should construct the message, as the search input does with
`withValue(Msg.Search(_))`. For a control, `value` is its current value.
`withValue` supplies an empty string when the payload has no `value`. Use
@:apiSymbol(def:scalive.HtmlAttrBinding.withValueOption)`withValueOption`@:@ when
that case is meaningful; it preserves it as `None`. An unchecked checkbox or
radio button sends no `value`.

@:apiSymbol(def:scalive.HtmlAttrBinding.withBoolValue)`withBoolValue`@:@ and
@:apiSymbol(def:scalive.HtmlAttrBinding.withBoolValueOption)`withBoolValueOption`@:@
read `on`, `yes`, and `true` as `true`, and `off`, `no`, and `false` as `false`.
For a missing or other value, `withBoolValue` supplies `false` and
`withBoolValueOption` supplies `None`.

Every binding also has a function form that receives the whole payload as
`Map[String, String]`. Key bindings include the key under `key`, for example
`on.keyDown(payload => Msg.Pressed(payload.getOrElse("key", "")))`. Payload
values come from the browser, so validate them before use.

### Limit event rates {#limit-event-rates}

To avoid sending a message for every keystroke, configure a rate limit on the
binding before supplying the message. The search input uses
`on.keyUp.debounce(300.millis)`. Durations are `zio.Duration` values, so the
syntax comes with `import zio.*`.

- @:apiSymbol(def:scalive.HtmlAttrBinding.debounce)`debounce(duration)`@:@ sends
  the latest event once no new event has arrived for `duration`. A pending event
  is sent immediately when the element loses focus.
- @:apiSymbol(def:scalive.HtmlAttrBinding.throttle)`throttle(duration)`@:@ sends
  the first event immediately and ignores further events until `duration` has
  passed. On a key-down binding, a press of a different key is sent immediately.
- @:apiSymbol(def:scalive.HtmlAttrBinding.debounceOnBlur)`debounceOnBlur`@:@
  waits until the element loses focus, then sends the latest event.

Durations are rendered in milliseconds, and negative durations are rejected.
The rate limit is an attribute of the element (`phx-debounce` or
`phx-throttle`), so it applies to every binding on that element. Configure one
rate limit per element: repeating `debounce` or `throttle` on another binding of
the same element is rejected as a duplicate attribute.

The browser reads the rate limit from the element that has the binding, except
for change events, where it reads it from the control that changed. To rate-limit a form's
change events, put @:apiSymbol(lazy-val:scalive.phx.debounce)`phx.debounce := 300`@:@
on the control rather than configuring the form's binding.

### Filter keys {#filter-keys}

To react to one key only, call
@:apiSymbol(def:scalive.KeyHtmlAttrBinding.key)`key(value)`@:@ on a key binding,
as the help panel does with `on.windowKeyDown.key(Key.Escape)`. The
@:apiSymbol(object:scalive.Key)`Key`@:@ object provides `Escape`, `Enter`, `Tab`,
`Space`, and the four arrow keys. Create other keys with
@:apiSymbol(def:scalive.Key.apply)`Key(value)`@:@, using the browser's
`KeyboardEvent.key` value. The browser client compares keys without regard to
case.

An element key binding fires only while that element has focus, so put it on
the control itself. A window binding fires wherever focus is, except for an
event whose target has its own binding of the same kind.

The key filter is also an attribute of the element (`phx-key`), so it applies to
every key binding on that element. Do not combine a filtered `on.keyDown` with
an unfiltered `on.keyUp` on one element. The example handles Escape with a
window binding, which leaves the search input's key binding unfiltered.

### Close on an outside click {#close-on-an-outside-click}

To close a menu or panel when the visitor clicks elsewhere, add
@:apiSymbol(lazy-val:scalive.on.clickAway)`on.clickAway`@:@ to it. In the
example, the help panel sends `CloseHelp` when a click starts outside it.

The binding fires only while its element is visible on screen. Render the
element only while it is open, as the example does with `when`, so a closed
panel does not send a message for every click on the page.

A click-away binding runs before the click binding of the clicked element.
Clicking **Help** while the panel is open therefore sends `CloseHelp` and then
`OpenHelp`, and the panel stays open. A button that toggled the panel would
close it and immediately reopen it, so give the opening control a message that
only opens.

A click inside [portal](#move-content-and-wrap-focus) content counts as a click
on the portal's source element in its LiveView. A menu that contains a portal
therefore does not close when the visitor clicks the moved content.

### Load more while scrolling {#load-more-while-scrolling}

To load more rows as the visitor scrolls, add
@:apiSymbol(lazy-val:scalive.on.viewportBottom)`on.viewportBottom`@:@ to the list
container. It fires when the container's last child reaches the bottom of the
viewport while the visitor scrolls down.
@:apiSymbol(lazy-val:scalive.on.viewportTop)`on.viewportTop`@:@ fires when the
first child reaches the top while the visitor scrolls up.

- Give the container a stable, unique `id`. The Phoenix client attaches a
  scroll hook to it.
- The events fire only during scrolling, so the first page of rows must overflow
  the scroll area.
- The client throttles these events itself.
- The payload's `id` is the DOM `id` of the child that reached the edge.

For a long or unbounded list, consider a
[stream](streams-and-collection-updates.md#define-stable-identity-and-retention)
with a retention limit so the browser does not keep every row.

### React to connection changes {#react-to-connection-changes}

To change the page when the LiveView connects or loses its connection, give
@:apiSymbol(lazy-val:scalive.connection.onConnect)`connection.onConnect`@:@ and
@:apiSymbol(lazy-val:scalive.connection.onDisconnect)`connection.onDisconnect`@:@
a client-side `JS` command. For example,
`connection.onDisconnect(JS.addClass("offline"))` adds a class, and
`connection.onConnect(JS.removeClass("offline"))` removes it.

`onConnect` runs each time the LiveView joins, including after a reconnect.
`onDisconnect` runs shortly after the connection is lost. Both accept only `JS`
commands, because the Phoenix client runs nothing else for them, and a
disconnected page cannot reach the server anyway. To start server work when the
page connects, use `mount` as in
[Load data when the page connects](../learn/loading-data-and-live-updates.md#load-data-when-the-page-connects).
To show or hide an element by connection state, use
@:apiSymbol(lazy-val:scalive.connection.visibleWhenConnected)`connection.visibleWhenConnected`@:@
and
@:apiSymbol(lazy-val:scalive.connection.visibleWhenDisconnected)`connection.visibleWhenDisconnected`@:@,
described in
[Render connection state declaratively](../learn/lifecycle-and-connection-behavior.md#render-connection-state-declaratively).

### Bind form changes {#bind-form-changes}

To validate a form while the visitor edits it, prefer the typed form helpers
described in
[Typed forms and validation](typed-forms-and-validation.md#handle-change-and-submit-events).
They install the same `phx-change` binding as `on.change`.

Phoenix drives `phx-change` from both browser `input` and `change` events, so a
text control normally sends changes while the user edits rather than only when
the native `change` event commits. On a form, the binding sends its successful
controls and identifies the changed control. A binding on an individual control
overrides the form's change binding, sends only that control, and still
requires the control to belong to a form. A standalone input, like the search
field above, uses a key binding instead.

The client throws, only in the browser console, when a control with a change
binding has no form, and never reads `phx-change` on other elements. Scalive
flags these: the disconnected render logs a warning naming the element, and
`ConnectedRender` fails the test whose render contains one; see
[an input that sends nothing](troubleshooting.md#diagnose-an-input-that-sends-nothing).

### Handle events from elements that just left the page {#handle-events-from-removed-elements}

The browser sends an event from the page it shows, which can be one update
behind the server. A visitor may click a search result while the server is
replacing the results, or a row's button while another tab deletes the row.
Scalive delivers such an event: for 10 seconds after an update removes an
element, its bindings still produce the message they rendered, so the handler
receives what the visitor clicked.

The handler therefore sees messages for state that has since changed, as it
already does when two tabs edit the same data. Check the current model before
acting, for example that the row still exists or that no save is running, and
keep permission checks in the handler or the service it calls rather than
relying on a button being hidden. Connected-turn guards run first, as for any
other event. After 10 seconds, an event from a removed element is ignored: the
browser gets an empty reply, which releases the element it disabled while
waiting.

A component that leaves the page keeps handling events until the browser has
removed it, which follows shortly after: its handler runs and its outputs reach
its owner, but it is not rendered. Its async work stops when it leaves the page,
and it cannot start more. Once the browser has removed it, an event addressed to
it is ignored the same way. Phoenix LiveView handles both cases alike.

## Show conditional and repeated content {#show-conditional-and-repeated-content}

To show content only in some states, or one element per entry of a collection,
use the staged signal operators that
[Rendering, bindings, and diffs](../learn/rendering-and-dom-updates.md#show-content-conditionally)
teaches with the cart:

- @:apiSymbol(extension:scalive.Signal.when)`when`@:@ renders content while a
  `Signal[Boolean]` is `true`.
- @:apiSymbol(extension:scalive.Signal.option)`option`@:@ renders content while a
  `Signal[Option[A]]` holds a value.
- @:apiSymbol(extension:scalive.Signal.choose)`choose`@:@ switches between two
  branches, or between value-to-branch pairs. It renders nothing for a value
  without a pair.
- @:apiSymbol(extension:scalive.Signal.matchCases)`matchCases`@:@ renders one branch
  per case of an enum or sealed type, and the compiler checks that every case
  has one.
- @:apiSymbol(extension:scalive.splitBy)`splitBy`@:@ renders one keyed element per
  entry of a collection signal.

A branch is anything an element accepts: text, an element, a component, a
`Vector` of siblings, or an attribute such as `active.when(cls := "active")`.
The branches of one choice are either all content or all the same attribute.

The view is built before any model value exists, so an `if` cannot inspect a
signal. Each operator builds its branches once and switches between them as the
model changes.

### Key repeated content {#key-repeated-content}

To render one element per entry and keep each entry's identity across updates,
use @:apiSymbol(extension:scalive.splitBy)`splitBy(key) { ... }`@:@ on a
collection signal whose entries have stable domain identity. Choose a key that is
unique within that collection and does not change while the entry represents the
same entity:

@:sourceRegion(documentation/fixtures/guides/src/bindings/CartMarkup.scala, bindings-keyed-rows)

The function receives the key and a `Signal` of the entry. Scalive's keyed diff
uses the keys to match entries between updates. It sends new entries, changed
entries, and position changes; unchanged entries are not resent, and an
unchanged collection produces no diff. Prefer a SKU, database identifier, or
another domain key. Use
@:apiSymbol(extension:scalive.splitByIndex)`splitByIndex`@:@ only when
position is the identity and reordering is not meaningful.

The `splitBy` key remains server-side and is not rendered as an HTML `id`.
Phoenix's client merges the compact keyed payload before patching the resulting
HTML. Add a stable HTML `id` to each repeated root, as the row above does, when
the corresponding browser node itself must survive a move, such as a row
containing focused input or browser-managed state.

The [shopping cart example](../examples/shopping-cart.md) combines typed
attributes, product-specific event messages, conditional content, and SKU-keyed
rows in one view. For the model and handler behind it, read
[Models, messages, and effects](../learn/models-and-messages.md#one-model-one-message-type).
For the diffing model, read
[Rendering, bindings, and diffs](../learn/rendering-and-dom-updates.md#from-tree-changes-to-dom-changes).
For explicit ID-addressed inserts and deletes in frequently changing collections, read
[Streams and collection updates](streams-and-collection-updates.md#choose-streams-deliberately).

## Write reusable helpers {#write-reusable-helpers}

Keep reusable markup in ordinary functions. Pass a `Signal` to a helper whose
content follows the model, and keep formatting and other calculations in plain
functions, as
[Write helpers that take signals](../learn/rendering-and-dom-updates.md#write-helpers-that-take-signals)
explains.

Accept @:apiSymbol(type-alias:scalive.Mod.Input)`Mod.Input[Msg]`@:@
when callers should be able to supply individual modifiers, `Option` values, or
collections. Forward every input unchanged with `mods*`:

@:sourceRegion(documentation/fixtures/guides/src/bindings/Helpers.scala, bindings-passthrough)

When adding the component's own modifiers, normalize the caller inputs with
@:apiSymbol(def:scalive.Mod.flatten)`Mod.flatten`@:@:

@:sourceRegion(documentation/fixtures/guides/src/bindings/Helpers.scala, bindings-card)

The helper and caller classes combine into `class="card featured"`. This makes
component-owned classes composable without requiring the helper to inspect caller
modifiers. `Mod.flatten` is also available when a function must inspect,
reorder, store, or insert caller modifiers before generated trailing content.

## Render static HTML {#render-static-html}

Use @:apiSymbol(def:scalive.StaticHtml.render)`StaticHtml.render`@:@ when markup does not need a
LiveView lifecycle. It evaluates the concrete tree once and returns a ZIO effect containing the
serialized string:

@:sourceRegion(documentation/fixtures/guides/src/bindings/StatusPage.scala, bindings-static-html)

This produces `<!doctype html><html lang="en"><head><title>Status</title></head><body><main><h1>Ready</h1></main></body></html>`.
Static rendering uses the same HTML validation, escaping, raw-content handling, and attribute
serialization as LiveView rendering. Construct dynamic application content from ordinary Scala
values before calling `StaticHtml.render`.

The root must be an `HtmlElement[Nothing]`, which excludes event bindings that produce an owner
message. Ordinary reusable markup functions remain supported. `StaticHtml` can serialize
client-only `JS` commands into `phx-*` attributes, but those attributes are inert unless the
Phoenix LiveView client is loaded and a `LiveSocket` is initialized as described in
[Client setup and static assets](static-assets-and-client-setup.md#connect-live-socket). A standalone
static page should not rely on those commands unless it deliberately includes that browser runtime.

Server event bindings, flash content, managed streams, stateful LiveComponents, and nested
LiveViews require lifecycle state and fail with a typed
@:apiSymbol(type-alias:scalive.StaticHtmlError)`StaticHtmlError`@:@ instead of being silently
discarded or mounted.

## Advanced client-side switches {#advanced-client-side-switches}

Two uncommon client-side switches use the typed `phx` attribute namespace directly.
Scalive does not wrap them in form or DOM helpers because they carry no application
message or server-side value and apply across raw and typed controls.

Put @:apiSymbol(lazy-val:scalive.phx.noUnusedField)`phx.noUnusedField := true`@:@ on a
form or individual form-associated control to omit Phoenix's `_unused_*` markers for
that scope. Without those markers, received fields are treated as used when deriving
validation visibility.

Put @:apiSymbol(lazy-val:scalive.phx.patchFocused)`phx.patchFocused := true`@:@ on an
editable form-associated control, including a form-associated custom element, when
normal DOM patching should continue while it is focused. Focused controls otherwise
preserve browser-managed state during a patch.

## Move content and wrap focus {#move-content-and-wrap-focus}

Use @:apiSymbol(def:scalive.portal)`portal`@:@ when content must remain owned by its
LiveView but appear elsewhere in the document, such as below a root-level modal
container:

@:sourceRegion(documentation/fixtures/guides/src/bindings/CartDialog.scala, bindings-portal)

The helper renders a source `<template>` and moves one generated wrapper to the
explicit CSS target in the browser. Keep `id` stable and unique, ensure the target
exists, and use `container` and `wrapperClass` only to customize that wrapper.
`DomSelector.current` and invalid container tag names are rejected, but selector
syntax and target existence are not checked server-side. A portal preserves event,
hook, component, and nested LiveView ownership; it is not a security boundary and
does not make untrusted HTML safe.

Use @:apiSymbol(def:scalive.focusWrap)`focusWrap`@:@ for content whose keyboard focus
should cycle at its boundaries. This dialog body also closes on Escape with a
window key binding:

@:sourceRegion(documentation/fixtures/guides/src/bindings/CartDialog.scala, bindings-focus-wrap)

Keep its `id` stable and unique. Pass only wrapper attributes and bindings in
`mods`; do not override `id` or `phx-hook`, and put all child content in the second
argument list so the generated focus sentinels remain first and last. The helper
depends on the Phoenix client hook. It does not add dialog roles, labels, background
inertness, authorization, or a no-JavaScript focus trap; provide those separately.
Apply @:apiSymbol(lazy-val:scalive.inert)`inert := dialogOpen`@:@ to the background
region, not the dialog, when that region must stop receiving user input and focus.
Inert content leaves the accessibility tree and receives no default visual treatment,
so keep the active region perceivable and visually distinguish inactive content.

## Check accessibility in the browser {#check-accessibility-in-the-browser}

Typed attributes and focus helpers do not make a UI accessible automatically.
For each interactive flow:

- prefer the semantic element for the action or content;
- give controls visible labels and accessible names;
- complete the flow by keyboard and verify focus order, visibility, and restoration;
- expose validation, progress, and other live feedback without unexpectedly moving focus; and
- test the patched UI with browser accessibility tooling and representative assistive technology.

## Related tasks {#related-tasks}

- Give frequently changing rows targeted updates with [Streams and collection updates](streams-and-collection-updates.md#prerequisites).
- Run client-side commands from bindings with [Browser commands, events, and hooks](browser-integration.md#compose-client-only-commands).
- Build checked links and destinations with [Routes, parameters, and navigation](routes-and-navigation.md#prerequisites).
- Assert rendered forms and markup with [Testing LiveViews](testing.md#prerequisites).
