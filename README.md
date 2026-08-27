# Scalive

[![Publish snapshot](https://github.com/phfroidmont/scalive/actions/workflows/publish-snapshot.yml/badge.svg)](https://github.com/phfroidmont/scalive/actions/workflows/publish-snapshot.yml)
[![Documentation](https://img.shields.io/badge/docs-scalive.dev-ff334f)](https://scalive.dev)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

**Live interfaces. Typed end to end.**

Scalive is a Scala 3 reimplementation of the Phoenix LiveView programming model.
It keeps application state, rendering, and effects on the server, handles browser
interactions as typed messages, and sends efficient HTML updates through the
Phoenix LiveView client.

Scalive provides Scala-first APIs for typed models, messages, routes, forms,
components, and HTML, with ZIO for effects. It targets observable LiveView
behavior and feature coverage rather than Phoenix source or API compatibility.

> [!WARNING]
> Scalive is alpha software. APIs may change without compatibility shims, and
> feature coverage and production maturity are still evolving. Review the
> [project status](https://scalive.dev/project) and
> [compatibility matrix](https://scalive.dev/project/compatibility) before use.

## A LiveView In Scala

```scala
import scalive.*
import zio.{Task, ZIO}

final class CounterLiveView extends LiveView[CounterLiveView.Msg, Int]:
  import CounterLiveView.Msg

  def mount(ctx: MountContext): Task[Int] =
    ZIO.succeed(0)

  def handleMessage(model: Int, ctx: MessageContext) =
    case Msg.Decrement => ZIO.succeed(model - 1)
    case Msg.Increment => ZIO.succeed(model + 1)

  def view(model: Signal[Int]): HtmlElement[Msg] =
    mainTag(
      h1("Scalive counter"),
      button(typ := "button", on.click(Msg.Decrement), "Decrease"),
      outputTag(aria.live := "polite", model.map(_.toString)),
      button(typ := "button", on.click(Msg.Increment), "Increase")
    )

object CounterLiveView:
  enum Msg:
    case Decrement, Increment
```

[Run the counter](https://scalive.dev/examples/counter) or
[build it from scratch](https://scalive.dev/learn/quick-start).

## Start Here

- [Learn the programming model](https://scalive.dev/learn)
- [Explore runnable examples](https://scalive.dev/examples)
- [Browse the API reference](https://scalive.dev/api)
- [Check Phoenix LiveView compatibility](https://scalive.dev/project/compatibility)

## Development

Enter the repository development environment and run the common checks:

```bash
nix develop
mill __.reformat + __.fix
mill __.test
mill documentation.check
```

The [snapshot workflow](.github/workflows/publish-snapshot.yml) also runs the
root-slice and upstream Phoenix LiveView browser suites.

Report bugs, missing behavior, and focused feature requests through
[GitHub issues](https://github.com/phfroidmont/scalive/issues).

## License

Scalive is available under the [MIT License](LICENSE).
