{%
title = "HTML DSL and event bindings"
description = "Build typed HTML, set attributes, bind browser events to messages, and key collection entries."
order = 10
section = guides
%}

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

Configure rate limiting before supplying the message. Durations are rendered in
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
[Models and messages](../learn/models-and-messages.md#one-model-one-message-type).
For the diffing model, read
[Rendering and DOM updates](../learn/rendering-and-dom-updates.md#from-tree-changes-to-dom-changes).
