{%
title = "URL state and navigation"
description = "Decode typed URL state, build checked destinations, and choose how a LiveView changes the browser URL."
order = 4
section = learn
%}

URLs are an application boundary as well as navigation history. Declare a route
once, let Scalive decode its parameters, and use that same declaration to build
destinations:

```scala
import scalive.*
import zio.ZIO
import zio.http.URL

object Routes:
  val search =
    (live / "search").queryOptional[String]("q")

final case class Model(query: String)

enum Msg:
  case ClearQuery

final class SearchLiveView
    extends LiveView.Routed[Msg, Model, Option[String]]:

  private def modelFrom(params: Option[String]) =
    Model(params.map(_.trim.take(100)).filter(_.nonEmpty).getOrElse(""))

  def mount(params: Option[String], ctx: MountContext) =
    ZIO.succeed(modelFrom(params))

  override def handleParams(
      model: Model,
      params: Option[String],
      url: URL,
      ctx: ParamsContext
  ) = ZIO.succeed(modelFrom(params))

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.ClearQuery =>
      ctx.nav.replacePatch(Routes.search.location(None)).as(model)

  override def view(model: Signal[Model]) =
    div(
      p("Search: ", model.map(_.query)),
      link.pushPatch(Routes.search.location(Some("scala")), "Search for Scala"),
      button(on.click(Msg.ClearQuery), "Clear")
    )

val routes = Live.router(
  Routes.search -> SearchLiveView()
)
```

`Routes.search` is typed and named. It decodes inbound query state to
`Option[String]`, and `location` encodes the same type into a checked
@:apiSymbol(class:scalive.LiveLocation)`LiveLocation`@:@. A `LiveLocation` cannot be assembled from an
arbitrary string, so route changes remain compiler-visible at call sites.

## Treat Decoded Parameters As Untrusted {#treat-decoded-parameters-as-untrusted}

Typed decoding establishes shape, not trust. Route parameters still came from a
browser. Apply domain validation and bounds, and perform authorization against
server-owned identity and data. The example trims and bounds the query before it
enters the model; a decoded identifier would still need an authorized lookup.

@:apiSymbol(def:scalive.LiveView.mount)`mount`@:@ receives decoded parameters during each disconnected and connected mount.
@:apiSymbol(def:scalive.LiveView.Routed.handleParams)`handleParams`@:@ receives them after mount and after every successful patch, which keeps
Back, Forward, and in-page URL changes synchronized with the model.

## Choose The Navigation Semantics {#choose-the-navigation-semantics}

Use a rendered `link` when the user can activate a destination directly. It
keeps a real `href`, so opening a new tab and disconnected navigation still
work. Use `ctx.nav` when a server-side transition decides the destination, as
the clear action does after handling a message.

- **Patch** keeps the current routed LiveView mounted and calls `handleParams`.
  It suits filters, tabs, and pagination owned by that view.
- **Navigate** changes to another routed LiveView while preserving live
  navigation where route and session boundaries permit it.
- **Redirect** ends the current lifecycle. Use it for mount-time decisions or
  completed ordinary HTTP flows rather than in-view URL state.
- **Push** adds a history entry, so Back returns to the previous state.
- **Replace** overwrites the current history entry, which suits
  canonicalization or transient state.

Rendered links and `ctx.nav` provide the corresponding patch and navigate
choices. Prefer their checked `LiveLocation` APIs; raw-string `Unsafe` variants
give up route and encoding checks.

For parameter mappings, session boundaries, redirects, and every navigation
variant, continue with the full
[routes and navigation guide](../guides/routes-and-navigation.md).
