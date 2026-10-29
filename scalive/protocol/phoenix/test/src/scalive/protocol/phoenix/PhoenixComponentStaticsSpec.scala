package scalive.protocol.phoenix

import zio.Task
import zio.ZIO
import zio.json.ast.Json
import zio.test.*

import scalive.*
import scalive.render.*

/** How a component sent in full shares the statics of another one the browser holds or receives in
  * the same payload.
  */
object PhoenixComponentStaticsSpec extends ZIOSpecDefault:
  object Card extends LiveComponent[String, String, Unit]:
    def mount(props: String, ctx: MountContext): Task[Unit] = ZIO.unit
    def handleMessage(props: String, model: Unit, ctx: MessageContext): String => Task[Unit] =
      _ => ZIO.unit
    def view(props: Signal[String], model: Signal[Unit]): HtmlElement[String] = span(props)

  object Badge extends LiveComponent[String, String, Unit]:
    def mount(props: String, ctx: MountContext): Task[Unit] = ZIO.unit
    def handleMessage(props: String, model: Unit, ctx: MessageContext): String => Task[Unit] =
      _ => ZIO.unit
    def view(props: Signal[String], model: Signal[Unit]): HtmlElement[String] = span(props)

  /** The renders of a page whose components each render with the view their id names. */
  final class Page[Model](
    root: RenderProgram[Model, Nothing],
    views: Map[String, RenderProgram[String, Nothing]]):
    private val tokens    = collection.mutable.Map.empty[String, Object]
    private var committed = Option.empty[CommittedRender[Nothing]]
    private var children  = Map.empty[String, CommittedRender[Nothing]]
    private var tree      = Option.empty[EvaluatedTree]

    /** The delta from the previous render of the page to its render at `model`. */
    def render(model: Model): Task[RenderDelta] =
      for
        candidate <- root.evaluate(model, committed)
        resolved  <- resolve(candidate, children)
      yield
        val (next, nextChildren) = resolved
        val delta = tree.fold(RenderDelta.Replace(next.tree))(TreeDiffer.diff(_, next.tree))
        committed = Some(next.commit)
        children = nextChildren
        tree = Some(next.tree)
        delta

    private def resolve(
      candidate: RenderCandidate[Nothing],
      committedChildren: Map[String, CommittedRender[Nothing]]
    ): Task[(RenderCandidate[Nothing], Map[String, CommittedRender[Nothing]])] =
      ZIO
        .foldLeft(candidate.componentRequirements)(
          (Vector.empty[ComponentResolution], committedChildren)
        ) { case ((resolutions, committedChildren), requirement) =>
          val id = requirement.applicationId
          for
            child <- views(viewName(id))
                       .evaluate(requirement.props.asInstanceOf[String], committedChildren.get(id))
            resolvedChild <- resolve(child, committedChildren)
          yield
            val (resolvedCandidate, nestedChildren) = resolvedChild
            val token                               = tokens.getOrElseUpdate(id, Object())
            val resolution                          = requirement.resolve(
              ComponentRef.runtime[requirement.Message](token),
              token,
              resolvedCandidate.asInstanceOf[RenderCandidate[requirement.Message]]
            )
            (resolutions :+ resolution, nestedChildren.updated(id, resolvedCandidate.commit))
        }.flatMap { case (resolutions, committedChildren) =>
          ZIO
            .fromEither(candidate.resolveComponents(resolutions))
            .mapError(error => RuntimeException(error.toString))
            .map(_ -> committedChildren)
        }
  end Page

  // A component's view is named by its id up to the first dash, so `card-1` renders with `card`.
  private def viewName(id: String): String = id.takeWhile(_ != '-')

  private def page[Model](
    root: Signal[Model] => HtmlElement[Nothing],
    views: (String, Signal[String] => HtmlElement[Nothing])*
  ): Task[Page[Model]] =
    for
      rootProgram <- ZIO.fromEither(RenderProgram.compile[Model, Nothing](root))
      programs    <- ZIO.foreach(views.toVector) { (name, view) =>
                       ZIO.fromEither(RenderProgram.compile[String, Nothing](view)).map(name -> _)
                     }
    yield Page(rootProgram, programs.toMap)

  /** A page listing a `Card` for each id and its props. */
  private def cards(views: (String, Signal[String] => HtmlElement[Nothing])*)
    : Task[Page[Vector[(String, String)]]] =
    page[Vector[(String, String)]](
      items =>
        div(items.splitBy(_._1)((id, item) => div(component(Card, id).render(item.map(_._2))))),
      views*
    )

  private def ids(values: String*): Vector[(String, String)] = values.toVector.map(id => id -> id)

  private def join(delta: RenderDelta): Task[(PhoenixRenderedState, Json.Obj)] =
    delta match
      case RenderDelta.Replace(tree) =>
        ZIO.fromEither(PhoenixRenderedEncoder.initial(tree)).mapError(e => RuntimeException(e.toString))
      case other => ZIO.dieMessage(s"a join renders the whole tree: $other")

  private def update(
    state: PhoenixRenderedState,
    delta: RenderDelta
  ): Task[(PhoenixRenderedState, Json.Obj)] =
    ZIO.fromEither(PhoenixRenderedEncoder.update(state, delta)).mapError(e => RuntimeException(e.toString))

  private def components(payload: Json.Obj): Map[String, Json.Obj] =
    payload.get("c") match
      case Some(value: Json.Obj) =>
        value.fields.map((cid, json) => cid -> json.asInstanceOf[Json.Obj]).toMap
      case _ => Map.empty

  private val spanStatics = Json.Arr(Json.Str("<span>"), Json.Str("</span>"))

  override def spec = suite("PhoenixComponentStaticsSpec")(
    test("instances of one component mounted together send their statics once") {
      for
        page    <- cards("card" -> (value => span(value)))
        delta   <- page.render(ids("card-1", "card-2", "card-3"))
        initial <- join(delta)
        sent = components(initial._2)
      yield assertTrue(
        sent("1") == Json.Obj("s" -> spanStatics, "0" -> Json.Str("card-1"), "r" -> Json.Num(1)),
        sent("2") == Json.Obj("s" -> Json.Num(1), "0" -> Json.Str("card-2"), "r" -> Json.Num(1)),
        sent("3") == Json.Obj("s" -> Json.Num(1), "0" -> Json.Str("card-3"), "r" -> Json.Num(1))
      )
    },
    test("a component added later refers to the statics of one the browser holds") {
      for
        page    <- cards("card" -> (value => span(value)))
        initial <- page.render(ids("card-1")).flatMap(join)
        added   <- page.render(ids("card-1", "card-2")).flatMap(update(initial._1, _))
      yield assertTrue(
        components(added._2)("2") ==
          Json.Obj("s" -> Json.Num(-1), "0" -> Json.Str("card-2"), "r" -> Json.Num(1))
      )
    },
    test("a component pending deletion or destroyed is never referred to") {
      for
        page    <- cards("card" -> (value => span(value)))
        initial <- page.render(ids("card-1", "card-2")).flatMap(join)
        removed <- page.render(ids("card-2")).flatMap(update(initial._1, _))
        pending = removed._1.markComponentsForDeletion(Vector(1))
        // Only card-2, which the browser still shows, may be referred to.
        addedBeside <- page.render(ids("card-2", "card-3")).flatMap(update(pending, _))
        emptied     <- page.render(Vector.empty).flatMap(update(addedBeside._1, _))
        allPending = emptied._1.markComponentsForDeletion(Vector(2, 3))
        addedAlone <- page.render(ids("card-4")).flatMap(update(allPending, _))
        emptiedAgain <- page.render(Vector.empty).flatMap(update(addedAlone._1, _))
        destroyed = emptiedAgain._1
                      .markComponentsForDeletion(Vector(4)).destroyComponents(Vector(2, 3, 4))
        addedAfter <- page.render(ids("card-5")).flatMap(update(destroyed._1, _))
      yield assertTrue(
        components(addedBeside._2)("3").get("s").contains(Json.Num(-2)),
        components(addedAlone._2)("4").get("s").contains(spanStatics),
        destroyed._2 == Vector(2, 3, 4),
        components(addedAfter._2)("5").get("s").contains(spanStatics)
      )
    },
    test("components with different statics keep their own, nested statics included") {
      for
        page <- cards(
                  "card"  -> (value => span(value)),
                  "row"   -> (value => p(value)),
                  "left"  -> (value => div(value.map(_ => true).when(em("left")))),
                  "right" -> (value => div(value.map(_ => true).when(em("right"))))
                )
        initial <- page.render(ids("card-1", "row-1", "left-1", "right-1", "left-2")).flatMap(join)
        sent = components(initial._2)
      yield assertTrue(
        sent("2").get("s").contains(Json.Arr(Json.Str("<p>"), Json.Str("</p>"))),
        sent("3") == Json.Obj(
          "s" -> Json.Arr(Json.Str("<div>"), Json.Str("</div>")),
          "0" -> Json.Obj("s" -> Json.Arr(Json.Str("<em>left</em>"))),
          "r" -> Json.Num(1)
        ),
        sent("4").get("s").contains(Json.Arr(Json.Str("<div>"), Json.Str("</div>"))),
        // The nested statics come from the referenced component too.
        sent("5") == Json.Obj("s" -> Json.Num(3), "0" -> Json.Obj(), "r" -> Json.Num(1))
      )
    },
    test("a component refers to one whose nested statics a diff changed to its own") {
      val toggled: Signal[String] => HtmlElement[Nothing] =
        value => div(value.map(_.endsWith("!")).when(em("shown")))
      for
        page    <- cards("card" -> toggled)
        initial <- page.render(Vector("card-1" -> "hidden")).flatMap(join)
        shown   <- page.render(Vector("card-1" -> "shown!")).flatMap(update(initial._1, _))
        added   <- page
                   .render(Vector("card-1" -> "shown!", "card-2" -> "shown!", "card-3" -> "hidden"))
                   .flatMap(update(shown._1, _))
        sent = components(added._2)
      yield assertTrue(
        sent("2") == Json.Obj("s" -> Json.Num(-1), "0" -> Json.Obj(), "r" -> Json.Num(1)),
        sent("3").get("s").contains(Json.Arr(Json.Str("<div>"), Json.Str("</div>")))
      )
    },
    test("different components rendering the same statics share them") {
      for
        page <- page[String](
                  value =>
                    div(
                      component(Card, "card-1").render(value),
                      component(Badge, "badge-1").render(value)
                    ),
                  "card"  -> (value => span(value)),
                  "badge" -> (value => span(value))
                )
        initial <- page.render("shared").flatMap(join)
      yield assertTrue(
        components(initial._2)("2") ==
          Json.Obj("s" -> Json.Num(1), "0" -> Json.Str("shared"), "r" -> Json.Num(1))
      )
    }
  )
end PhoenixComponentStaticsSpec
