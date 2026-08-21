package scalive.render

import zio.ZIO
import zio.test.*

import scalive.*

object LinkSpec extends ZIOSpecDefault:
  final case class Destinations(typed: LiveLocation, raw: String)

  private val first = Destinations((scalive.live / "first").location, "/raw-first")
  private val next  = Destinations((scalive.live / "next").location, "/raw-next")

  private def expectedLinks(typed: String, raw: String): Vector[String] = Vector(
    s"""<a href="$typed" data-phx-link="redirect" data-phx-link-state="push">typed push navigate</a>""",
    s"""<a href="$typed" data-phx-link="redirect" data-phx-link-state="replace">typed replace navigate</a>""",
    s"""<a href="$typed" data-phx-link="patch" data-phx-link-state="push">typed push patch</a>""",
    s"""<a href="$typed" data-phx-link="patch" data-phx-link-state="replace">typed replace patch</a>""",
    s"""<a href="$raw" data-phx-link="redirect" data-phx-link-state="push">unsafe push navigate</a>""",
    s"""<a href="$raw" data-phx-link="redirect" data-phx-link-state="replace">unsafe replace navigate</a>""",
    s"""<a href="$raw" data-phx-link="patch" data-phx-link-state="push">unsafe push patch</a>""",
    s"""<a href="$raw" data-phx-link="patch" data-phx-link-state="replace">unsafe replace patch</a>"""
  )

  def spec = suite("LinkSpec")(
    test("renders and updates every signal-backed navigation link") {
      val compiled = RenderProgram.compile[Destinations, Nothing] { model =>
        val typed = model.map(_.typed)
        val raw   = model.map(_.raw)

        div(
          link.pushNavigate(typed, "typed push navigate"),
          link.replaceNavigate(typed, "typed replace navigate"),
          link.pushPatch(typed, "typed push patch"),
          link.replacePatch(typed, "typed replace patch"),
          link.pushNavigateUnsafe(raw, "unsafe push navigate"),
          link.replaceNavigateUnsafe(raw, "unsafe replace navigate"),
          link.pushPatchUnsafe(raw, "unsafe push patch"),
          link.replacePatchUnsafe(raw, "unsafe replace patch")
        )
      }

      for
        program          <- ZIO.fromEither(compiled)
        initialCandidate <- program.evaluate(first)
        nextCandidate    <- program.evaluate(next, Some(initialCandidate.commit))
        initialHtml = HtmlRenderer.render(initialCandidate.tree)
        nextHtml    = HtmlRenderer.render(nextCandidate.tree)
      yield assertTrue(
        expectedLinks(first.typed.href, first.raw).forall(initialHtml.contains),
        expectedLinks(next.typed.href, next.raw).forall(nextHtml.contains),
        expectedLinks(next.typed.href, next.raw).forall(link => !initialHtml.contains(link)),
        expectedLinks(first.typed.href, first.raw).forall(link => !nextHtml.contains(link))
      )
    }
  )
end LinkSpec
