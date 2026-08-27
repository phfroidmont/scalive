{%
title = "Designing Dynamic HTML"
description = "The API design journey from the first dynamic-value prototype to Scalive's current signal-backed view API."
order = 2
section = project
%}

## The Missing Information {#the-question}

The earliest Scalive experiment was only a model, a tiny HTML DSL, and a renderer
that printed an initial result followed by an update. Even at that stage, the
central question was already visible: what should a Scala API for dynamic HTML
look like?

Phoenix's rendered format separates static fragments from dynamic values. If a
template contains a fixed `<h1>` around a title that may change, the server can
send the tag once and send later titles in a numbered slot. HEEx knows where
those boundaries are because it is a template language with an explicit
interpolation syntax.

Scalive uses a Scala HTML DSL instead. By the time an HTML constructor receives
a `String`, the string does not say whether it is a constant or a value read
from the current model. Both of these expressions have the same type:

```scala
h1("Products")
h1(model.title)
```

The obvious answer was to mark values derived from the model. I also considered
recovering that information with macros, and later tried removing the distinction
from the API and finding changes by rendering the whole view again. None of these
choices was only about diff encoding. They changed what could be written in a
view and how much the renderer had to infer afterwards.

I wanted to keep application state as one ordinary Scala value and use ordinary
functions to derive what the page needed. The problem was that, by the time those
derived values reached the HTML DSL, the renderer had lost the information it
needed to produce a good diff.

Whatever solution I chose also had to work for attributes, conditional branches,
and collections. At the same time, I did not want application code to maintain a
parallel graph of caches, subscriptions, dirty flags, and cleanup. That machinery
felt like the renderer's responsibility. Most of the designs that followed were
attempts to preserve just enough information for the renderer without making
that machinery part of the application API.

## A Function From The Model {#a-function-from-the-model}

The first prototype represented a dynamic value as a function from the model.
Stripped only of its throwaway example names, its API was small enough to show
directly:

```scala
trait LiveView[Model]:
  val model = Dyn[Model, Model](identity)
  def render: HtmlTag[Model]

object ProductsView extends LiveView[Products]:
  def render =
    div(
      div("Available now"),
      model(_.title)
    )
```

Conceptually, `Dyn[I, O]` was little more than `I => O`. Calling
`model(_.title)` composed another function onto the root model projection. The
HTML builder could therefore tell that the nested `div` was static while the
title had to be evaluated for each model.

For text, this worked well. The model stayed typed, dynamic positions were
obvious, and the renderer could construct the HTML shape once. Storing the
dynamic values in traversal order also happened to line up neatly with Phoenix's
numbered slots.

The trouble started when the shape itself needed to change. A slot number says
where a value appears in one compiled shape. It says nothing about a conditional
element that disappears or a collection entry that moves while keeping its own
dynamic values and event bindings. Position was enough for scalar text, but I
needed something else before conditionals and collections could work reliably.

## Putting State In Dyn {#putting-state-in-dyn}

My next attempts gave dynamic values more responsibility. In one version, each
`Dyn` carried a key into an assigns-style state map. Changing an assign marked
the corresponding projections as dirty. In another, more Laminar-inspired
version, a root `Var` owned the current model and mapped `Dyn` values retained
their previous result:

```scala
val model = Var(initialModel)

mainTag(
  h1(model(_.title)),
  model(_.products).splitBy(_.id) { (_, product) =>
    articleTag(product(_.name))
  }
)
```

Now a mapped value could remember whether its output had changed, so the renderer
did not have to evaluate every projection. `splitBy` could also retain one
projected row for each domain key. Reordering products no longer meant pretending
that the product at index zero was the same product as before.

The API looked appealing because it resembled a client-side reactive library,
but the implementation responsibilities were very different. These values did
not have a browser component lifecycle around them. Making them observable
would have required subscription ownership and cleanup, so the renderer instead
propagated changes through explicit internal synchronization. It had to update
parents before children, reset changed flags after a successful diff, and retain
or discard keyed values at exactly the right moment.

That bookkeeping was hidden from application code, but it was still encoded in
the same mutable objects exposed by the application-facing abstraction. The
state identity and render identity were too closely coupled. It was also
possible to place a correctly typed `Dyn` from the wrong state root into a view,
an error the type parameters could not distinguish.

I still liked one part of this design: only the application can say what makes a
collection entry the same entry, so asking for a key was the right call. What I
no longer liked was storing retained render state in mutable objects that looked
like application values. That state belonged in the renderer.

## Rendering From A Plain Model {#rendering-from-a-plain-model}

The cleanest way to remove the problems around `Dyn` was to remove `Dyn` itself:

```scala
def view(model: Products): HtmlElement =
  mainTag(
    h1(model.title),
    model.products.map { product =>
      articleTag(product.name)
    }
  )
```

I liked this API immediately. The model is an ordinary value. Conditionals use
`if` and `match`, collections use `map`, and helper methods accept whatever types
make sense for the application. There is no separate vocabulary for reading
state inside a view and no dynamic value that can belong to the wrong root.

The renderer can support that API by invoking `view` for every model revision,
normalizing the new HTML tree, and comparing it with the previous tree. Dynamic
HTML becomes whatever differs between those two results. Stable collection keys
can remain explicit as an optimization and as a statement of row identity, but
ordinary scalar values need no marker.

For a while, this looked like the ideal division of responsibility: application
code writes unconstrained Scala, and the runtime works out the rest.

The cost is that the API has erased information the renderer eventually needs.
Before discovering that one title changed, the runtime must call the complete
view, allocate its elements and modifiers, rebuild collection projections, and
compare the result. A newly allocated event-handler closure must be matched with
the binding that occupied the equivalent place in the previous tree. Structural
position becomes identity again, only now the relationship is recovered after
rendering instead of declared while building the view.

Some of that work can be optimized with fingerprints, memoization, and careful
tree differencing. Those techniques improve the implementation, but they do not
restore the semantic information removed from the API. The renderer is still
trying to infer which computations are stable, which values are dependencies,
and which newly created objects represent an existing logical node.

I kept this version because writing views with it was pleasant. Over time it
became clear that removing staging from the method signature had only moved it
into tree reconstruction and identity heuristics. The syntax was simpler because
the renderer was doing more guessing.

## Back To Explicit Staging {#back-to-explicit-staging}

The current API returns to an explicit dynamic value:

```scala
def view(model: Signal[Products]): HtmlElement[Msg] =
  mainTag(
    h1(model.map(_.title)),
    model.map(_.products).splitBy(_.id) { (_, product) =>
      articleTag(
        product.map(_.name),
        button(on.click(product)((value, _) => Msg.Select(value.id)), "Select")
      )
    }
  )
```

This deliberately circles back to the original `Dyn`. A signal is again a staged
projection, and placing it in the HTML tree tells the renderer that the value
belongs to a dynamic slot. What changed was the amount of responsibility carried
by that projection.

`Signal[A]` is read-only. Its `map` and `zip` operations are intended for pure
transformations; it provides no operation for setting a value, sampling the
current value, subscribing, or running an effect. Scala cannot prevent a
function passed to `map` from performing a side effect, but Scalive may skip or
reuse that transformation, so doing so would make the view incorrect. A signal
is a description of how to derive an `A` during rendering, not another
application state container and not a general-purpose reactive stream.

Because a signal cannot be sampled or mutated through the public API, the
renderer can construct the view graph once. Ordinary values in that graph are
static for its lifetime; signals identify the computations that must be sampled
against a candidate model. Reusing one signal expression in multiple places does
not duplicate its transformation work. If its dependencies have not changed,
the transformation does not need to run at all.

Structural operations make the same staging explicit where an ordinary scalar
slot is not enough. `when`, `choose`, and `option` describe the finite ways a
subtree can vary. `splitBy` says both that a collection changes and which domain
value identifies each retained row. The renderer owns the compiled branches,
row scopes, caches, and cleanup behind those operations.

The application still knows what each expression means while constructing this
graph. Keeping that information is the point of returning to an explicit type.

## Why Signal Is Limited {#why-signal-is-limited}

The signal API gives up some of the plain-model version's freedom. This ordinary
Scala conditional cannot inspect a signal:

```scala
if model.showDetails then details(model) else summary(model)
```

The staged equivalent must state that the branch follows the model:

```scala
model.map(_.showDetails).choose(
  details(model),
  summary(model)
)
```

This is more syntax, and helper functions often need to accept signals instead
of plain values. I considered that a real cost. Still, the condition genuinely
changes the shape of the rendered HTML. Writing it explicitly lets Scalive
construct both shapes once and switch between them without rerunning arbitrary
view code.

Collections need one additional piece of information. The application supplies
the domain key to `splitBy`, and the renderer owns the retained row state behind
it. A stable HTML `id` is a separate choice for cases where the browser must
preserve the exact DOM node across a move.

## The Macro Route I Did Not Take {#the-macro-route}

I considered using macros to keep the plain-model API while identifying dynamic
fragments during compilation. In principle, that could have allowed arbitrary
Scala in `view(model)` while rewriting values derived from `model` into staged
nodes for the renderer.

I never built a prototype. The implementation and maintenance complexity already
looked substantial, especially once helper methods and ordinary function
composition were involved. More importantly, it felt too magical and indirect
for my taste. Reading a view would no longer tell the whole story because the
compiler would be introducing the dynamic boundary somewhere out of sight.

I was also worried about compile times if every view required that analysis and
rewriting. Since I did not prototype the approach, I do not have measurements;
it was one more reason not to take on the complexity.

Signals leave the boundary in ordinary Scala code. Static and dynamic values are
visibly different where the distinction matters, without introducing a separate
template language or an invisible compiler transformation.

## Where The Boundary Ended Up {#where-the-boundary-ended-up}

The current design is built around replacing one strongly typed model as the
LiveView's application state rather than mutating render values in place. The
runtime provides a read-only signal for projecting that model into HTML.
Application code declares dynamic dependencies and semantic structure; the
renderer owns evaluation, retention, internal identity, and lifecycle cleanup.

In the end I came back quite close to the first experiment, but with a narrower
role for the dynamic value. A `Signal` marks a dependency. It does not remember
whether it changed, identify a rendered node, or own anything that needs to be
cleaned up. All of that stays inside the renderer.

Some views would be shorter with a plain model. I accept the extra `map`,
`choose`, and `splitBy` calls because they make the model dependency visible when
reading the view. They also give the renderer information that would otherwise
have to be recovered from a new tree or introduced by a compiler transformation.

For how the resulting API behaves in application code, read
[Rendering, bindings, and diffs](../learn/rendering-and-dom-updates.md#render-from-the-model).
For the retained renderer beneath it, read
[Runtime architecture](runtime-architecture.md#retained-rendering).
