package scalive.protocol.phoenix

import zio.Task
import zio.ZIO
import zio.test.*

import scalive.*
import scalive.render.*

/** Checks that the browser client, fed the initial payload and every later diff, renders what the
  * server rendered.
  */
object PhoenixRenderedRoundTripSpec extends ZIOSpecDefault:
  final case class Item(
    key: String,
    label: String,
    kind: Int,
    flagged: Boolean,
    tags: Vector[String])
  final case class Page(mode: Int, open: Boolean, title: String, items: Vector[Item])

  private def view(page: Signal[Page]): HtmlElement[Nothing] =
    div(
      h1(cls := "header", page.map(_.title)),
      page
        .map(_.mode).choose(
          0 -> span("zero ", page.map(_.title)),
          1 -> Vector(strong("one"), p(page.map(_.title))),
          2 -> "two"
        ),
      page
        .map(_.open).when(
          div(page.map(_.mode).choose(0 -> em("nested"), 1 -> em(page.map(_.title))))
        ),
      ul(
        page
          .map(_.items).splitBy(_.key)((key, item) =>
            li(
              idAttr := key,
              item.map(_.label),
              item.map(_.kind).choose(0 -> b("bold"), 1 -> i(item.map(_.label))),
              item.map(_.flagged).when(em("!")),
              item.map(_.tags).splitBy(identity)((tag, _) => span(tag))
            )
          )
      ),
      p(page.map(_.title))
    )

  object Label extends LiveComponent[String, String, Unit]:
    def mount(props: String, ctx: MountContext): Task[Unit] = ZIO.unit
    def handleMessage(props: String, model: Unit, ctx: MessageContext): String => Task[Unit] =
      _ => ZIO.unit
    def view(props: Signal[String], model: Signal[Unit]): HtmlElement[String] = span(props)

  private def withComponents(page: Signal[Page]): HtmlElement[Nothing] =
    div(
      h1(page.map(_.title)),
      component(Label, "outer").render(page.map(_.title)),
      page
        .map(_.open).when(
          p(
            "inner ",
            component(Label, "inner").render(page.map(_.title.reverse)),
            component(Label, "laterList").render(page.map(_.title))
          )
        ),
      component(Label, "wrap").render(page.map(_.title)),
      component(Label, "list").render(page.map(_.title)),
      page.map(_.mode).choose(0 -> em("zero"), 1 -> b(page.map(_.title)))
    )

  private def labelView(value: Signal[String]): HtmlElement[Nothing] =
    div(
      value,
      value.map(_.startsWith("b")).when(em("starts with b"))
    )

  private def listView(value: Signal[String]): HtmlElement[Nothing] =
    ul(value.map(_.toVector.distinct.map(_.toString)).splitBy(identity)((char, _) => li(char)))

  // Shows nothing of its own, so only the component it holds changes.
  private def wrapView(value: Signal[String]): HtmlElement[Nothing] =
    div(cls := "wrap", component(Label, "nested").render(value))

  private val views =
    Map(
      "outer"     -> labelView,
      "inner"     -> labelView,
      "wrap"      -> wrapView,
      "nested"    -> labelView,
      "list"      -> listView,
      "laterList" -> listView
    )

  private val tokens = views.map((id, _) => id -> Object())

  private def resolveComponents(
    root: RenderCandidate[Nothing],
    programs: Map[String, RenderProgram[String, Nothing]],
    committed: Map[String, CommittedRender[Nothing]]
  ): ZIO[Any, Any, (RenderCandidate[Nothing], Map[String, CommittedRender[Nothing]])] =
    ZIO
      .foldLeft(root.componentRequirements)((Vector.empty[ComponentResolution], committed)) {
        case ((resolutions, committed), requirement) =>
          val id = requirement.applicationId
          for
            candidate <-
              programs(id).evaluate(requirement.props.asInstanceOf[String], committed.get(id))
            resolved <- resolveComponents(candidate, programs, committed)
          yield
            val (child, committedChildren) = resolved
            val token                      = tokens(id)
            val resolution                 = requirement.resolve(
              ComponentRef.runtime[requirement.Message](token),
              token,
              child.asInstanceOf[RenderCandidate[requirement.Message]]
            )
            (resolutions :+ resolution, committedChildren.updated(id, child.commit))
      }.flatMap { case (resolutions, committed) =>
        ZIO.fromEither(root.resolveComponents(resolutions)).map(_ -> committed)
      }

  // The client renders each component's root with its CID, which the server tree leaves out.
  private def withoutCids(html: String): String = html.replaceAll(" data-phx-component=\"\\d+\"", "")

  private val label = Gen.elements("a", "b <&>", "c")
  private val item  = for
    key     <- Gen.elements("k1", "k2", "k3", "k4")
    text    <- label
    kind    <- Gen.int(0, 2)
    flagged <- Gen.boolean
    tags    <- Gen.listOfBounded(0, 3)(Gen.elements("x", "y", "z")).map(_.distinct.toVector)
  yield Item(key, text, kind, flagged, tags)
  private val page = for
    mode  <- Gen.int(0, 3)
    open  <- Gen.boolean
    title <- label
    items <- Gen.listOfBounded(0, 4)(item).map(_.distinctBy(_.key).toVector)
  yield Page(mode, open, title, items)

  override def spec = suite("PhoenixRenderedRoundTripSpec")(
    test("the client renders the server HTML after every diff") {
      check(Gen.listOfBounded(1, 6)(page)) { pages =>
        for
          program <- ZIO.fromEither(RenderProgram.compile[Page, Nothing](view))
          first   <- program.evaluate(pages.head)
          initial <- ZIO.fromEither(PhoenixRenderedEncoder.initial(first.tree))
          client = PhoenixClient.join(initial._2)
          joined = client.html == HtmlRenderer.render(first.tree)
          updates <- ZIO.foldLeft(pages.tail)((first.commit, initial._1, Vector.empty[Boolean])) {
                       case ((committed, state, results), next) =>
                         for
                           candidate <- program.evaluate(next, Some(committed))
                           delta = TreeDiffer.diff(committed.tree, candidate.tree)
                           encoded <- ZIO.fromEither(PhoenixRenderedEncoder.update(state, delta))
                           _ = client.patch(encoded._2)
                         yield (
                           candidate.commit,
                           encoded._1,
                           results :+ (client.html == HtmlRenderer.render(candidate.tree))
                         )
                     }
        yield assertTrue(joined, updates._3.forall(identity))
      }
    },
    test("the client renders the server HTML after every diff with components") {
      check(Gen.listOfBounded(1, 6)(page)) { pages =>
        for
          program <- ZIO.fromEither(RenderProgram.compile[Page, Nothing](withComponents))
          programs <- ZIO.foreach(views)((id, view) =>
                        ZIO.fromEither(RenderProgram.compile[String, Nothing](view)).map(id -> _)
                      )
          root    <- program.evaluate(pages.head)
          first   <- resolveComponents(root, programs, Map.empty)
          initial <- ZIO.fromEither(PhoenixRenderedEncoder.initial(first._1.tree))
          client = PhoenixClient.join(initial._2)
          joined = withoutCids(client.html) == HtmlRenderer.render(first._1.tree)
          start  = (first._1.commit, first._2, initial._1, Vector.empty[Boolean])
          updates <- ZIO.foldLeft(pages.tail)(start) {
                       case ((committed, children, state, results), next) =>
                         for
                           candidate <- program.evaluate(next, Some(committed))
                           resolved  <- resolveComponents(candidate, programs, children)
                           delta = TreeDiffer.diff(committed.tree, resolved._1.tree)
                           encoded <- ZIO.fromEither(PhoenixRenderedEncoder.update(state, delta))
                           _ = client.patch(encoded._2)
                           rendered = withoutCids(client.html)
                         yield (
                           resolved._1.commit,
                           resolved._2,
                           encoded._1,
                           results :+ (rendered == HtmlRenderer.render(resolved._1.tree))
                         )
                     }
        yield assertTrue(joined, updates._4.forall(identity))
      }
    }
  )
end PhoenixRenderedRoundTripSpec
