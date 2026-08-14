{%
title = "HTML and event bindings"
description = "Build typed HTML, set attributes, bind browser events to messages, and key collection entries."
order = 10
section = guides
group = "Interfaces and input"
%}

## Prerequisites {#prerequisites}

Start with the `LiveView` model and messages introduced in
[Models and messages](../learn/models-and-messages.md). The DSL uses ordinary
Scala expressions and collections rather than introducing a template language.

## Build An HTML Tree {#build-an-html-tree}

Import `scalive.*`, then call tag values such as @:apiSymbol(lazy-val:scalive.div)`div`@:@,
@:apiSymbol(lazy-val:scalive.button)`button`@:@, and @:apiSymbol(lazy-val:scalive.table)`table`@:@.
Pass attributes and children in document order:

```scala
def render(model: Model): HtmlElement[Msg] =
  sectionTag(
    cls        := "cart",
    aria.label := "Shopping cart",
    h1("Cart"),
    p(s"${model.itemCount} items")
  )
```

A tag call produces an @:apiSymbol(class:scalive.HtmlElement)`HtmlElement[Msg]`@:@. Strings
become escaped text content, nested elements become child content, and an
`IterableOnce` of modifiers can be passed directly. Use ordinary Scala
expressions for conditional and repeated content.

Use the named tag definitions when they exist. The DSL gives Scala-safe names to
HTML names that would otherwise conflict with Scala or another exported symbol:
for example, @:apiSymbol(lazy-val:scalive.sectionTag)`sectionTag`@:@,
@:apiSymbol(lazy-val:scalive.headerTag)`headerTag`@:@,
@:apiSymbol(lazy-val:scalive.htmlRootTag)`htmlRootTag`@:@,
@:apiSymbol(lazy-val:scalive.headTag)`headTag`@:@, and
@:apiSymbol(lazy-val:scalive.idAttr)`idAttr`@:@.
Use @:apiSymbol(def:scalive.htmlTag)`htmlTag(name)`@:@ only when the framework does not
provide the element you need.

## Move Content And Wrap Focus {#move-content-and-wrap-focus}

Use @:apiSymbol(def:scalive.portal)`portal`@:@ when content must remain owned by its
LiveView but appear elsewhere in the document, such as below a root-level modal
container:

```scala
portal("cart-dialog", target = DomSelector.css("#modal-root"))(
  sectionTag(aria.label := "Cart", "...")
)
```

The helper renders a source `<template>` and moves one generated wrapper to the
explicit CSS target in the browser. Keep `id` stable and unique, ensure the target
exists, and use `container` and `wrapperClass` only to customize that wrapper.
`DomSelector.current` and invalid container tag names are rejected, but selector
syntax and target existence are not checked server-side. A portal preserves event,
hook, component, and nested LiveView ownership; it is not a security boundary and
does not make untrusted HTML safe.

Use @:apiSymbol(def:scalive.focusWrap)`focusWrap`@:@ for content whose keyboard focus
should cycle at its boundaries:

```scala
focusWrap("cart-dialog-focus", cls := "dialog-body")(
  button(on.click(Msg.Close), "Close")
)
```

Keep its `id` stable and unique. Pass only wrapper attributes and bindings in
`mods`; do not override `id` or `phx-hook`, and put all child content in the second
argument list so the generated focus sentinels remain first and last. The helper
depends on the Phoenix client hook. It does not add dialog roles, labels, background
inertness, authorization, or a no-JavaScript focus trap; provide those separately.

## Set Typed Attributes {#set-typed-attributes}

Assign attributes with @:apiSymbol(def:scalive.HtmlAttr.:=)`:=`@:@. Each @:apiSymbol(class:scalive.HtmlAttr)`HtmlAttr[V]`@:@
accepts its declared Scala value type, so `disabled := model.lines.isEmpty` takes
a `Boolean` while `cls := "cart"` takes a `String`. Boolean presence attributes
are emitted when true and omitted when false.

Use @:apiSymbol(def:scalive.dataAttr)`dataAttr(name)`@:@ for application `data-*` attributes and
the @:apiSymbol(object:scalive.aria)`aria`@:@ namespace for ARIA attributes:

```scala
button(
  typ                    := "button",
  dataAttr("product")    := product.sku,
  aria.label             := s"Add ${product.name}",
  disabled               := !product.available,
  on.click(Msg.Add(product)),
  product.name
)
```

For an attribute absent from the DSL, use @:apiSymbol(def:scalive.htmlAttr)`htmlAttr(name, encoder)`@:@ with
an explicit encoder rather than assembling rendered HTML:

```scala
private val popover = htmlAttr("popover", scalive.codecs.StringAsIsEncoder)

div(popover := "manual", "Details")
```

Avoid @:apiSymbol(def:scalive.rawHtml)`rawHtml`@:@ for ordinary content. It bypasses escaping and should be limited
to HTML that the application already trusts.

## Bind Events To Messages {#bind-events-to-messages}

Use the @:apiSymbol(object:scalive.on)`on`@:@ bindings to produce the view's message
type. A constant binding is enough when the event carries no application value:

```scala
enum Msg:
  case Add(product: Product)
  case Clear

button(on.click(Msg.Add(product)), "Add")
button(on.click(Msg.Clear), "Clear")
```

The message type remains part of the whole tree. If
@:apiSymbol(def:scalive.LiveView.render)`render`@:@ returns
`HtmlElement[Msg]`, a binding that produces another message type does not
compile. The shopping cart uses this directly for product-specific add and
remove messages.

Use @:apiSymbol(def:scalive.HtmlAttrBinding.withValue)`withValue`@:@ when an event's `value`
should construct the message, and use
@:apiSymbol(def:scalive.HtmlAttrBinding.withValueOption)`withValueOption`@:@ when a missing value is
meaningful:

```scala
enum Msg:
  case SearchChanged(value: String)

input(
  typ := "search",
  on.blur.withValue(Msg.SearchChanged.apply)
)
```

@:apiSymbol(def:scalive.HtmlAttrBinding.withValue)`withValue`@:@ supplies an empty string when the payload has no `value`.
@:apiSymbol(def:scalive.HtmlAttrBinding.withValueOption)`withValueOption`@:@ preserves that case as `None`. The lower-level function form of
@:apiSymbol(lazy-val:scalive.on.click)`on.click`@:@ receives the
binding payload as `Map[String, String]`.

Configure rate limiting with @:apiSymbol(def:scalive.HtmlAttrBinding.debounce)`debounce(duration)`@:@
before supplying the message. Durations are rendered in milliseconds, and
negative durations are rejected:

```scala
import scala.concurrent.duration.*

input(on.blur.debounce(300.millis).withValue(Msg.SearchChanged.apply))
```

## Key Repeated Content {#key-repeated-content}

Use @:apiSymbol(extension:scalive.splitBy)`splitBy(key)(render)`@:@ when collection entries have stable
domain identity. Choose a key that is unique within that rendered collection and
does not change while the entry represents the same entity:

```scala
tbody(
  model.lines.splitBy(_.product.sku) { (sku, line) =>
    tr(
      dataAttr("cart-line") := sku,
      td(line.product.name),
      td(line.quantity.toString)
    )
  }
)
```

Scalive's tree diff uses the keys to match entries between renders. Current
tests cover unchanged keyed subtrees producing no diff, reorders producing
index changes without resending unchanged entry payloads, changed entries being
merged into reorder payloads, and deletion reducing the keyed count. Prefer a
SKU, database identifier, or another domain key. Use
@:apiSymbol(extension:scalive.splitByIndex)`splitByIndex`@:@ only when
position is the identity and reordering is not meaningful.

The migrated shopping cart combines typed attributes, product-specific event
messages, conditional content, and SKU-keyed rows in one render function:

@:sourceRegion(documentation/site/src/scalive/docs/examples/ShoppingCartExample.scala, shopping-cart-example)

For the model and handler behind this tree, read
[Models, messages, and effects](../learn/models-and-messages.md#one-model-one-message-type).
For the diffing model, read
[Rendering, bindings, and diffs](../learn/rendering-and-dom-updates.md#from-tree-changes-to-dom-changes).
For targeted inserts and deletes in frequently changing collections, read
[Streams and collection updates](streams-and-collection-updates.md#choose-streams-deliberately).

## Related Tasks {#related-tasks}

- Give frequently changing rows targeted updates with [Streams and collection updates](streams-and-collection-updates.md#prerequisites).
- Build checked links and destinations with [Routes, parameters, and navigation](routes-and-navigation.md#prerequisites).
- Assert rendered forms and markup with [Testing LiveViews](testing.md#prerequisites).
