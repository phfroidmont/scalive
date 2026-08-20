package scalive.protocol.phoenix

import zio.ZIO
import zio.json.ast.Json
import zio.test.*

import scalive.*
import scalive.render.*
import scalive.streams.*

object PhoenixRenderedEncoderSpec extends ZIOSpecDefault:
  final case class Model(text: String, raw: String, title: Option[String], disabled: Boolean)
  final case class StreamItem(id: String, label: String)

  private def stream(
    identity: LiveStreamIdentity,
    generation: Long,
    rows: Vector[StreamItem],
    inserts: Vector[(StreamItem, StreamAt, Option[StreamLimit], Boolean)] = Vector.empty,
    deletes: Vector[String] = Vector.empty,
    reset: Boolean = false
  ): LiveStream[StreamItem] = LiveStream(
    identity,
    "items",
    generation,
    rows.map(item => LiveStreamEntry(item.id, item)),
    inserts.map { case (item, at, limit, updateOnly) =>
      LiveStreamInsert(LiveStreamEntry(item.id, item), at, limit, updateOnly)
    },
    deletes,
    reset
  )

  object TestComponent extends LiveComponent[String, String, Unit]:
    def mount(props: String, ctx: MountContext): LiveIO[Unit] = ZIO.unit
    def handleMessage(props: String, model: Unit, ctx: MessageContext): String => LiveIO[Unit] =
      _ => ZIO.unit
    def view(
      props: Signal[String],
      model: Signal[Unit],
      self: ComponentRef[String]
    ): HtmlElement[String] = span(props)

  object TestView extends LiveView.Eventless[Unit]:
    def mount(ctx: MountContext): LiveIO[Unit] = ZIO.unit
    def view(model: Signal[Unit]): HtmlElement[Nothing] = div()

  private def resolve(
    requirement: ComponentRequirement[?],
    ref: ComponentRef[String],
    token: Object,
    child: RenderCandidate[?]
  ): ComponentResolution =
    requirement.resolve(
      ref.asInstanceOf[ComponentRef[requirement.Message]],
      token,
      child.asInstanceOf[RenderCandidate[requirement.Message]]
    )

  private def resolveNested(
    requirement: NestedRequirement,
    token: Object,
    parentDomId: String = "parent",
    joinCredential: String = "join-secret",
    staticCredential: Option[String] = None,
    loading: Boolean = false,
    child: Option[EvaluatedTree] = None
  ): NestedResolution =
    requirement.resolve(
      instanceToken = token,
      parentDomId = parentDomId,
      topic = s"lv:${requirement.applicationId}",
      joinCredential = joinCredential,
      staticCredential = staticCredential,
      loading = loading,
      child = child
    )

  private def projectedStreamRows(state: PhoenixRenderedState): Vector[String] =
    def loop(node: PhoenixRenderedEncoder.ProjectedNode): Vector[String] =
      node.parts.flatMap {
        case PhoenixRenderedEncoder.ProjectedPart.Node(child) => loop(child)
        case PhoenixRenderedEncoder.ProjectedPart.Stream(value) => value.rows.map(_.domId)
        case _ => Vector.empty
      }
    loop(state.root) ++ state.components.valuesIterator.flatMap(component => loop(component.root))

  private def dynamic(rendered: Json.Obj, index: Int = 0): Json.Obj =
    field(rendered, index.toString).asInstanceOf[Json.Obj]

  private def field(rendered: Json.Obj, name: String): Json =
    rendered.fields.toMap.apply(name)

  override def spec = suite("PhoenixRenderedEncoderSpec")(
    test("projects exact escaped nested LiveView wire attributes and disconnected HTML") {
      val parentCompiled = RenderProgram.compile[Unit, Nothing](_ =>
        mainTag(liveView("child&\"", TestView, sticky = true))
      )
      val childCompiled = RenderProgram.compile[String, Nothing](value => span(value))
      val token         = Object()

      for
        parentProgram <- ZIO.fromEither(parentCompiled)
        childProgram  <- ZIO.fromEither(childCompiled)
        parent        <- parentProgram.evaluate(())
        child         <- childProgram.evaluate("disconnected <child>")
        resolved <- ZIO.fromEither(
                      parent.resolveNested(
                        Vector(
                          resolveNested(
                            parent.nestedRequirements.head,
                            token,
                            parentDomId = "parent&\"",
                            joinCredential = "join<&\"",
                            staticCredential = Some("static<&\""),
                            loading = true,
                            child = Some(child.tree)
                          )
                        )
                      )
                    )
        encoded <- ZIO.fromEither(PhoenixRenderedEncoder.initial(resolved.tree))
        html    <- ZIO.fromEither(PhoenixRenderedEncoder.fullHtml(resolved.tree))
        expected =
          "<main><div id=\"child&amp;&quot;\" data-phx-parent-id=\"parent&amp;&quot;\" data-phx-session=\"join&lt;&amp;&quot;\" data-phx-static=\"static&lt;&amp;&quot;\" data-phx-sticky class=\"phx-loading\"><span>disconnected &lt;child&gt;</span></div></main>"
      yield assertTrue(
        html._2 == expected,
        encoded._2 == Json.Obj(
          "s" -> Json.Arr(
            Json.Str(
              "<main><div id=\"child&amp;&quot;\" data-phx-parent-id=\"parent&amp;&quot;\" data-phx-session=\"join&lt;&amp;&quot;\" data-phx-static=\"static&lt;&amp;&quot;\" data-phx-sticky class=\"phx-loading\"><span>"
            ),
            Json.Str("</span></div></main>")
          ),
          "0" -> Json.Str("disconnected &lt;child&gt;")
        ),
        !expected.contains("lv:child")
      )
    },
    test("projects a connected nested LiveView as an empty container without optional attributes") {
      val compiled = RenderProgram.compile[Unit, Nothing](_ => div(liveView("child", TestView)))

      for
        program    <- ZIO.fromEither(compiled)
        candidate  <- program.evaluate(())
        resolved   <- ZIO.fromEither(
                        candidate.resolveNested(
                          Vector(resolveNested(candidate.nestedRequirements.head, Object()))
                        )
                      )
        encoded    <- ZIO.fromEither(PhoenixRenderedEncoder.initial(resolved.tree))
        html       <- ZIO.fromEither(PhoenixRenderedEncoder.fullHtml(resolved.tree))
        expected =
          "<div><div id=\"child\" data-phx-parent-id=\"parent\" data-phx-session=\"join-secret\"></div></div>"
      yield assertTrue(
        html._2 == expected,
        encoded._2 == Json.Obj("s" -> Json.Arr(Json.Str(expected))),
        !expected.contains("data-phx-static"),
        !expected.contains("data-phx-sticky"),
        !expected.contains("phx-loading")
      )
    },
    test("uses normal component and stream projection inside a disconnected nested child") {
      val identity = LiveStreamIdentity.fresh()
      val handle = stream(
        identity,
        1L,
        Vector(StreamItem("row", "stream child")),
        Vector((StreamItem("row", "stream child"), StreamAt.Last, None, false))
      )
      val parentCompiled = RenderProgram.compile[Unit, Nothing](_ => div(liveView("child", TestView)))
      val childCompiled = RenderProgram.compile[LiveStream[StreamItem], Nothing](model =>
        div(
          liveComponent(TestComponent, "component", "component child"),
          model.stream((domId, item) => span(idAttr := domId, item.map(_.label)))
        )
      )
      val componentCompiled = RenderProgram.compile[String, Nothing](value => span(value))
      val componentToken = Object()
      val componentRef   = ComponentRef.runtime[String](componentToken)

      for
        parentProgram    <- ZIO.fromEither(parentCompiled)
        childProgram     <- ZIO.fromEither(childCompiled)
        componentProgram <- ZIO.fromEither(componentCompiled)
        parent           <- parentProgram.evaluate(())
        child            <- childProgram.evaluate(handle)
        component        <- componentProgram.evaluate("component child")
        componentTree <- ZIO.fromEither(
                           child.resolveComponents(
                             Vector(
                               resolve(
                                 child.componentRequirements.head,
                                 componentRef,
                                 componentToken,
                                 component
                               )
                             )
                           )
                         )
        resolved <- ZIO.fromEither(
                      parent.resolveNested(
                        Vector(
                          resolveNested(
                            parent.nestedRequirements.head,
                            Object(),
                            child = Some(componentTree.tree)
                          )
                        )
                      )
                    )
        encoded <- ZIO.fromEither(PhoenixRenderedEncoder.initial(resolved.tree))
        html    <- ZIO.fromEither(PhoenixRenderedEncoder.fullHtml(resolved.tree))
        streamPayload = field(encoded._2, "1").asInstanceOf[Json.Obj]
      yield assertTrue(
        encoded._1.cidForToken(componentToken).contains(1),
        encoded._1.streamRef(identity).contains("0"),
        field(streamPayload, "stream").asInstanceOf[Json.Arr].elements.head == Json.Str("0"),
        html._2 ==
          "<div><div id=\"child\" data-phx-parent-id=\"parent\" data-phx-session=\"join-secret\"><div><span data-phx-component=\"1\">component child</span><span id=\"row\">stream child</span></div></div></div>"
      )
    },
    test("rejects unresolved nested nodes and replaces only changed nested instances") {
      val compiled = RenderProgram.compile[Unit, Nothing](_ => div(liveView("child", TestView)))
      val retainedToken = Object()
      val changedToken  = Object()

      for
        program    <- ZIO.fromEither(compiled)
        unresolved <- program.evaluate(())
        first <- ZIO.fromEither(
                   unresolved.resolveNested(
                     Vector(resolveNested(unresolved.nestedRequirements.head, retainedToken))
                   )
                 )
        initial <- ZIO.fromEither(PhoenixRenderedEncoder.initial(first.tree))
        base = first.commit
        retainedCandidate <- program.evaluate((), Some(base))
        retained <- ZIO.fromEither(
                      retainedCandidate.resolveNested(
                        Vector(
                          resolveNested(retainedCandidate.nestedRequirements.head, retainedToken)
                        )
                      )
                    )
        retainedUpdate <- ZIO.fromEither(
                            PhoenixRenderedEncoder.update(
                              initial._1,
                              TreeDiffer.diff(first.tree, retained.tree)
                            )
                          )
        changedCandidate <- program.evaluate((), Some(base))
        changed <- ZIO.fromEither(
                     changedCandidate.resolveNested(
                       Vector(resolveNested(changedCandidate.nestedRequirements.head, changedToken))
                     )
                   )
        changedUpdate <- ZIO.fromEither(
                           PhoenixRenderedEncoder.update(
                             initial._1,
                             TreeDiffer.diff(first.tree, changed.tree)
                           )
                         )
      yield assertTrue(
        PhoenixRenderedEncoder.initial(unresolved.tree) ==
          Left(PhoenixEncodingError.UnresolvedNested(unresolved.tree.root.children.head.id)),
        retainedUpdate._2 == Json.Obj.empty,
        changedUpdate._2 == Json.Obj(
          "s" -> Json.Arr(
            Json.Str(
              "<div><div id=\"child\" data-phx-parent-id=\"parent\" data-phx-session=\"join-secret\"></div></div>"
            )
          )
        )
      )
    },
    test("projects an initial stream as a Phoenix 1.1 keyed comprehension") {
      val identity = LiveStreamIdentity.fresh()
      val first = stream(
        identity,
        1L,
        Vector(StreamItem("a", "one"), StreamItem("b", "two")),
        Vector(
          (StreamItem("a", "one"), StreamAt.Last, None, false),
          (StreamItem("b", "two"), StreamAt.Last, None, false)
        )
      )
      val compiled = RenderProgram.compile[LiveStream[StreamItem], Nothing](model =>
        div(model.stream((domId, item) => span(idAttr := domId, item.map(_.label))))
      )
      for
        program <- ZIO.fromEither(compiled)
        candidate <- program.evaluate(first)
        encoded <- ZIO.fromEither(PhoenixRenderedEncoder.initial(candidate.tree))
        html <- ZIO.fromEither(PhoenixRenderedEncoder.fullHtml(candidate.tree))
        secondHandle = stream(
          identity,
          2L,
          Vector(StreamItem("c", "three"), StreamItem("a", "one"), StreamItem("b", "two")),
          Vector((StreamItem("c", "three"), StreamAt.First, None, false))
        )
        second <- program.evaluate(secondHandle, Some(candidate.commit))
        appended <- ZIO.fromEither(
          PhoenixRenderedEncoder.update(encoded._1, TreeDiffer.diff(candidate.tree, second.tree))
        )
        thirdHandle = stream(
          identity,
          2L,
          Vector(StreamItem("c", "three"), StreamItem("a", "updated"), StreamItem("b", "two"))
        )
        third <- program.evaluate(thirdHandle, Some(second.commit))
        rowUpdated <- ZIO.fromEither(
          PhoenixRenderedEncoder.update(appended._1, TreeDiffer.diff(second.tree, third.tree))
        )
      yield assertTrue(
        encoded._1.streamRef(identity).contains("0"),
        html._2 == "<div><span id=\"a\">one</span><span id=\"b\">two</span></div>",
        encoded._2 == Json.Obj(
          "s" -> Json.Arr(Json.Str("<div>"), Json.Str("</div>")),
          "0" -> Json.Obj(
            "s" -> Json.Arr(Json.Str("<span id=\""), Json.Str("\">"), Json.Str("</span>")),
            "k" -> Json.Obj(
              "0" -> Json.Obj("0" -> Json.Str("a"), "1" -> Json.Str("one")),
              "1" -> Json.Obj("0" -> Json.Str("b"), "1" -> Json.Str("two")),
              "kc" -> Json.Num(2)
            ),
            "stream" -> Json.Arr(
              Json.Str("0"),
              Json.Arr(
                Json.Arr(Json.Str("a"), Json.Num(-1), Json.Null, Json.Bool(false)),
                Json.Arr(Json.Str("b"), Json.Num(-1), Json.Null, Json.Bool(false))
              ),
              Json.Arr()
            )
          )
        ),
        appended._2 == Json.Obj(
          "0" -> Json.Obj(
            "k" -> Json.Obj(
              "0" -> Json.Obj("0" -> Json.Str("c"), "1" -> Json.Str("three")),
              "kc" -> Json.Num(1)
            ),
            "stream" -> Json.Arr(
              Json.Str("0"),
              Json.Arr(Json.Arr(Json.Str("c"), Json.Num(0), Json.Null, Json.Bool(false))),
              Json.Arr()
            )
          )
        ),
        rowUpdated._2 == Json.Obj(
          "0" -> Json.Obj(
            "k" -> Json.Obj(
              "0" -> Json.Obj("0" -> Json.Str("a"), "1" -> Json.Str("updated")),
              "kc" -> Json.Num(1)
            ),
            "stream" -> Json.Arr(Json.Str("0"), Json.Arr(), Json.Arr())
          )
        )
      )
    },
    test("encodes Index and Last positions, signed limits, and retains the bounded order") {
      val identity = LiveStreamIdentity.fresh()
      val initialHandle = stream(identity, 1L, Vector(StreamItem("a", "one"), StreamItem("b", "two")))
      val nextHandle = stream(
        identity,
        2L,
        Vector(StreamItem("d", "four"), StreamItem("b", "two"), StreamItem("e", "five")),
        Vector(
          (StreamItem("d", "four"), StreamAt.Index(1), Some(StreamLimit.KeepFirst(3)), false),
          (StreamItem("e", "five"), StreamAt.Last, Some(StreamLimit.KeepLast(3)), false)
        )
      )
      val compiled = RenderProgram.compile[LiveStream[StreamItem], Nothing](model =>
        div(model.stream((domId, item) => span(idAttr := domId, item.map(_.label))))
      )
      for
        program <- ZIO.fromEither(compiled)
        first <- program.evaluate(initialHandle)
        initial <- ZIO.fromEither(PhoenixRenderedEncoder.initial(first.tree))
        next <- program.evaluate(nextHandle, Some(first.commit))
        updated <- ZIO.fromEither(
          PhoenixRenderedEncoder.update(initial._1, TreeDiffer.diff(first.tree, next.tree))
        )
        streamDiff = dynamic(updated._2)
      yield assertTrue(
        field(streamDiff, "stream") == Json.Arr(
          Json.Str("0"),
          Json.Arr(
            Json.Arr(Json.Str("d"), Json.Num(1), Json.Num(3), Json.Bool(false)),
            Json.Arr(Json.Str("e"), Json.Num(-1), Json.Num(-3), Json.Bool(false))
          ),
          Json.Arr()
        ),
        field(streamDiff, "k") == Json.Obj(
          "0" -> Json.Obj("0" -> Json.Str("d"), "1" -> Json.Str("four")),
          "1" -> Json.Obj("0" -> Json.Str("e"), "1" -> Json.Str("five")),
          "kc" -> Json.Num(2)
        ),
        projectedStreamRows(updated._1) == Vector("d", "b", "e")
      )
    },
    test("keeps updateOnly missing IDs out of retained state and updates existing IDs in place") {
      val identity = LiveStreamIdentity.fresh()
      val firstHandle = stream(
        identity,
        1L,
        Vector(StreamItem("a", "one"), StreamItem("b", "two"))
      )
      val updateOnlyHandle = stream(
        identity,
        2L,
        Vector(
          StreamItem("a", "one"),
          StreamItem("b", "updated"),
          StreamItem("ghost", "ignored")
        ),
        Vector(
          (StreamItem("b", "updated"), StreamAt.First, None, true),
          (StreamItem("ghost", "ignored"), StreamAt.Last, None, true)
        )
      )
      val insertedHandle = stream(
        identity,
        3L,
        Vector(StreamItem("a", "one"), StreamItem("b", "updated"), StreamItem("ghost", "inserted")),
        Vector((StreamItem("ghost", "inserted"), StreamAt.Last, None, false))
      )
      val compiled = RenderProgram.compile[LiveStream[StreamItem], Nothing](model =>
        div(model.stream((domId, item) => span(idAttr := domId, item.map(_.label))))
      )
      for
        program <- ZIO.fromEither(compiled)
        first <- program.evaluate(firstHandle)
        initial <- ZIO.fromEither(PhoenixRenderedEncoder.initial(first.tree))
        updateOnly <- program.evaluate(updateOnlyHandle, Some(first.commit))
        updated <- ZIO.fromEither(
          PhoenixRenderedEncoder.update(initial._1, TreeDiffer.diff(first.tree, updateOnly.tree))
        )
        inserted <- program.evaluate(insertedHandle, Some(updateOnly.commit))
        added <- ZIO.fromEither(
          PhoenixRenderedEncoder.update(updated._1, TreeDiffer.diff(updateOnly.tree, inserted.tree))
        )
        metadata = field(dynamic(updated._2), "stream")
      yield assertTrue(
        metadata == Json.Arr(
          Json.Str("0"),
          Json.Arr(
            Json.Arr(Json.Str("b"), Json.Num(0), Json.Null, Json.Bool(true)),
            Json.Arr(Json.Str("ghost"), Json.Num(-1), Json.Null, Json.Bool(true))
          ),
          Json.Arr()
        ),
        projectedStreamRows(updated._1) == Vector("a", "b"),
        projectedStreamRows(added._1) == Vector("a", "b", "ghost")
      )
    },
    test("encodes delete and reset metadata and applies both to retained rows") {
      val identity = LiveStreamIdentity.fresh()
      val firstHandle = stream(identity, 1L, Vector(StreamItem("a", "one"), StreamItem("b", "two")))
      val deletedHandle = stream(
        identity,
        2L,
        Vector(StreamItem("b", "two")),
        deletes = Vector("a")
      )
      val resetHandle = stream(
        identity,
        3L,
        Vector(StreamItem("c", "three"), StreamItem("a", "again")),
        Vector(
          (StreamItem("c", "three"), StreamAt.Last, None, false),
          (StreamItem("a", "again"), StreamAt.Last, None, false)
        ),
        reset = true
      )
      val compiled = RenderProgram.compile[LiveStream[StreamItem], Nothing](model =>
        div(model.stream((domId, item) => span(idAttr := domId, item.map(_.label))))
      )
      for
        program <- ZIO.fromEither(compiled)
        first <- program.evaluate(firstHandle)
        initial <- ZIO.fromEither(PhoenixRenderedEncoder.initial(first.tree))
        deleted <- program.evaluate(deletedHandle, Some(first.commit))
        afterDelete <- ZIO.fromEither(
          PhoenixRenderedEncoder.update(initial._1, TreeDiffer.diff(first.tree, deleted.tree))
        )
        reset <- program.evaluate(resetHandle, Some(deleted.commit))
        afterReset <- ZIO.fromEither(
          PhoenixRenderedEncoder.update(afterDelete._1, TreeDiffer.diff(deleted.tree, reset.tree))
        )
      yield assertTrue(
        field(dynamic(afterDelete._2), "stream") ==
          Json.Arr(Json.Str("0"), Json.Arr(), Json.Arr(Json.Str("a"))),
        field(dynamic(afterDelete._2), "k") == Json.Obj("kc" -> Json.Num(0)),
        projectedStreamRows(afterDelete._1) == Vector("b"),
        field(dynamic(afterReset._2), "stream") == Json.Arr(
          Json.Str("0"),
          Json.Arr(
            Json.Arr(Json.Str("c"), Json.Num(-1), Json.Null, Json.Bool(false)),
            Json.Arr(Json.Str("a"), Json.Num(-1), Json.Null, Json.Bool(false))
          ),
          Json.Arr(),
          Json.Bool(true)
        ),
        projectedStreamRows(afterReset._1) == Vector("c", "a")
      )
    },
    test("retires stream identities and never reuses their connection-local references") {
      val firstIdentity = LiveStreamIdentity.fresh()
      val secondIdentity = LiveStreamIdentity.fresh()
      val firstHandle = stream(firstIdentity, 1L, Vector(StreamItem("a", "one")))
      val secondHandle = stream(secondIdentity, 1L, Vector(StreamItem("b", "two")))
      val firstAgain = stream(firstIdentity, 1L, Vector(StreamItem("c", "three")))
      val compiled = RenderProgram.compile[LiveStream[StreamItem], Nothing](model =>
        div(model.stream((domId, item) => span(idAttr := domId, item.map(_.label))))
      )
      for
        program <- ZIO.fromEither(compiled)
        first <- program.evaluate(firstHandle)
        initial <- ZIO.fromEither(PhoenixRenderedEncoder.initial(first.tree))
        second <- program.evaluate(secondHandle, Some(first.commit))
        replaced <- ZIO.fromEither(
          PhoenixRenderedEncoder.update(initial._1, TreeDiffer.diff(first.tree, second.tree))
        )
        third <- program.evaluate(firstAgain, Some(second.commit))
        reintroduced <- ZIO.fromEither(
          PhoenixRenderedEncoder.update(replaced._1, TreeDiffer.diff(second.tree, third.tree))
        )
      yield assertTrue(
        initial._1.streamRef(firstIdentity).contains("0"),
        replaced._1.streamRef(firstIdentity).isEmpty,
        replaced._1.streamRef(secondIdentity).contains("1"),
        field(dynamic(replaced._2), "stream").asInstanceOf[Json.Arr].elements.head == Json.Str("1"),
        reintroduced._1.streamRef(secondIdentity).isEmpty,
        reintroduced._1.streamRef(firstIdentity).contains("2"),
        field(dynamic(reintroduced._2), "stream").asInstanceOf[Json.Arr].elements.head == Json.Str("2")
      )
    },
    test("projects a stream inside a resolved component CID payload") {
      val identity = LiveStreamIdentity.fresh()
      val handle = stream(
        identity,
        1L,
        Vector(StreamItem("a", "one")),
        Vector((StreamItem("a", "one"), StreamAt.Last, None, false))
      )
      val rootCompiled = RenderProgram.compile[Unit, Nothing](_ =>
        div(liveComponent(TestComponent, "stream", "stream"))
      )
      val childCompiled = RenderProgram.compile[LiveStream[StreamItem], Nothing](model =>
        div(model.stream((domId, item) => span(idAttr := domId, item.map(_.label))))
      )
      val token = Object()
      val ref = ComponentRef.runtime[String](token)
      for
        rootProgram <- ZIO.fromEither(rootCompiled)
        childProgram <- ZIO.fromEither(childCompiled)
        root <- rootProgram.evaluate(())
        child <- childProgram.evaluate(handle)
        resolved <- ZIO.fromEither(
          root.resolveComponents(Vector(resolve(root.componentRequirements.head, ref, token, child)))
        )
        encoded <- ZIO.fromEither(PhoenixRenderedEncoder.initial(resolved.tree))
        components = field(encoded._2, "c").asInstanceOf[Json.Obj]
        component = field(components, "1").asInstanceOf[Json.Obj]
      yield assertTrue(
        encoded._1.cidForToken(token).contains(1),
        encoded._1.streamRef(identity).contains("0"),
        field(component, "s") == Json.Arr(
          Json.Str("<div data-phx-component=\"1\">"),
          Json.Str("</div>")
        ),
        field(dynamic(component), "stream") == Json.Arr(
          Json.Str("0"),
          Json.Arr(Json.Arr(Json.Str("a"), Json.Num(-1), Json.Null, Json.Bool(false))),
          Json.Arr()
        ),
        projectedStreamRows(encoded._1) == Vector("a")
      )
    },
    test("retains and retires a component nested in a stream row") {
      val identity = LiveStreamIdentity.fresh()
      val initialHandle = stream(
        identity,
        1L,
        Vector(StreamItem("a", "one")),
        Vector((StreamItem("a", "one"), StreamAt.Last, None, false))
      )
      val removedHandle = stream(
        identity,
        2L,
        Vector.empty,
        deletes = Vector("a")
      )
      val rootCompiled = RenderProgram.compile[LiveStream[StreamItem], Nothing](model =>
        div(
          model.stream((domId, _) =>
            div(idAttr := domId, liveComponent(TestComponent, domId, domId))
          )
        )
      )
      val childCompiled = RenderProgram.compile[String, Nothing](value => span(value))
      val token = Object()
      val ref = ComponentRef.runtime[String](token)
      for
        rootProgram <- ZIO.fromEither(rootCompiled)
        childProgram <- ZIO.fromEither(childCompiled)
        unresolved <- rootProgram.evaluate(initialHandle)
        child <- childProgram.evaluate("child")
        resolved <- ZIO.fromEither(
          unresolved.resolveComponents(
            Vector(resolve(unresolved.componentRequirements.head, ref, token, child))
          )
        )
        initial <- ZIO.fromEither(PhoenixRenderedEncoder.initial(resolved.tree))
        removed <- rootProgram.evaluate(removedHandle, Some(resolved.commit))
        updated <- ZIO.fromEither(
          PhoenixRenderedEncoder.update(initial._1, TreeDiffer.diff(resolved.tree, removed.tree))
        )
        row = field(dynamic(initial._2), "k").asInstanceOf[Json.Obj]
        rowZero = field(row, "0").asInstanceOf[Json.Obj]
      yield assertTrue(
        initial._1.cidForToken(token).contains(1),
        field(initial._2, "c").asInstanceOf[Json.Obj].fields.map(_._1).toSet == Set("1"),
        field(rowZero, "1") == Json.Num(1),
        updated._1.cidForToken(token).isEmpty,
        updated._1.tokenForCid(1).isEmpty,
        field(dynamic(updated._2), "stream") ==
          Json.Arr(Json.Str("0"), Json.Arr(), Json.Arr(Json.Str("a"))),
        projectedStreamRows(updated._1).isEmpty
      )
    },
    test("initial rendered maps reconstruct exactly to HtmlRenderer") {
      val compiled = RenderProgram.compile[Model, Nothing] { model =>
        div(
          cls := "static",
          title.optional(model.map(_.title)),
          disabled := model.map(_.disabled),
          model.map(_.text),
          rawHtml(model.map(_.raw))
        )
      }
      val input = Model("safe < &", "<b>raw</b>", Some("quoted \""), disabled = true)
      for
        program   <- ZIO.fromEither(compiled)
        candidate <- program.evaluate(input)
        encoded   <- ZIO.fromEither(PhoenixRenderedEncoder.initial(candidate.tree))
      yield assertTrue(reconstruct(encoded._2) == HtmlRenderer.render(candidate.tree))
    },
    test("uses stable dense slots and sparse updates without s") {
      val compiled = RenderProgram.compile[Model, Nothing] { model =>
        div(
          title.optional(model.map(_.title)),
          disabled := model.map(_.disabled),
          model.map(_.text),
          rawHtml(model.map(_.raw))
        )
      }
      val firstModel  = Model("one", "<i>raw</i>", None, disabled = false)
      val secondModel = firstModel.copy(text = "two &", title = Some("tip"), disabled = true)
      for
        program <- ZIO.fromEither(compiled)
        first   <- program.evaluate(firstModel)
        initial <- ZIO.fromEither(PhoenixRenderedEncoder.initial(first.tree))
        second  <- program.evaluate(secondModel, Some(first.commit))
        updated <- ZIO.fromEither(
          PhoenixRenderedEncoder.update(initial._1, TreeDiffer.diff(first.tree, second.tree))
        )
        empty <- ZIO.fromEither(PhoenixRenderedEncoder.update(updated._1, RenderDelta.Empty))
      yield assertTrue(
        !updated._2.fields.exists(_._1 == "s"),
        updated._2.fields.map(_._1).toSet == Set("0", "1", "2"),
        empty._2 == Json.Obj.empty
      )
    },
    test("a full replacement rebuilds the complete protocol projection") {
      val firstProgram  = RenderProgram.compile[String, Nothing](value => div(value))
      val secondProgram = RenderProgram.compile[String, Nothing](value => div(span(value)))
      for
        first      <- ZIO.fromEither(firstProgram)
        second     <- ZIO.fromEither(secondProgram)
        firstTree  <- first.evaluate("old")
        secondTree <- second.evaluate("new &")
        initial    <- ZIO.fromEither(PhoenixRenderedEncoder.initial(firstTree.tree))
        replaced <- ZIO.fromEither(
          PhoenixRenderedEncoder.update(initial._1, RenderDelta.Replace(secondTree.tree))
        )
      yield assertTrue(
        replaced._2.fields.exists(_._1 == "s"),
        reconstruct(replaced._2) == HtmlRenderer.render(secondTree.tree)
      )
    },
    test("a structural replacement targets the previous template id and emits a full map") {
      val firstProgram  = RenderProgram.compile[String, Nothing](value => div(span(value)))
      val secondProgram = RenderProgram.compile[String, Nothing](value => div(button(value)))
      for
        first      <- ZIO.fromEither(firstProgram)
        second     <- ZIO.fromEither(secondProgram)
        firstTree  <- first.evaluate("old")
        secondTree <- second.evaluate("new")
        initial    <- ZIO.fromEither(PhoenixRenderedEncoder.initial(firstTree.tree))
        previousId = firstTree.tree.root.children.head.id
        replacement = secondTree.tree.root.children.head
        replaced <- ZIO.fromEither(
          PhoenixRenderedEncoder.update(
            initial._1,
            RenderDelta.Update(
              secondTree.tree.revision,
              Vector(RenderChange.Replace(previousId, replacement))
            )
          )
        )
      yield assertTrue(
        replaced._2.fields.exists(_._1 == "s"),
        reconstruct(replaced._2) == HtmlRenderer.render(secondTree.tree)
      )
    },
    test("flash insertion points remain transparent across structural updates") {
      val notice = FlashKind("notice")
      val compiled = RenderProgram.compile[Map[FlashKind, String], Nothing](
        _ => div("before", flash(notice)(message => span(message)), "after"),
        identity
      )
      for
        program <- ZIO.fromEither(compiled)
        absent  <- program.evaluate(Map.empty)
        initial <- ZIO.fromEither(PhoenixRenderedEncoder.initial(absent.tree))
        present <- program.evaluate(Map(notice -> "saved"), Some(absent.commit))
        inserted <- ZIO.fromEither(
                      PhoenixRenderedEncoder.update(
                        initial._1,
                        TreeDiffer.diff(absent.tree, present.tree)
                      )
                    )
        removed <- program.evaluate(Map.empty, Some(present.commit))
        deleted <- ZIO.fromEither(
                     PhoenixRenderedEncoder.update(
                       inserted._1,
                       TreeDiffer.diff(present.tree, removed.tree)
                     )
                   )
      yield assertTrue(
        reconstruct(initial._2) == "<div>beforeafter</div>",
        reconstruct(inserted._2) == "<div>before<span>saved</span>after</div>",
        reconstruct(deleted._2) == "<div>beforeafter</div>"
      )
    },
    test("assigns sibling CIDs in declaration order and isolates state lookups") {
      val rootCompiled = RenderProgram.compile[Unit, Nothing](_ =>
        div(
          liveComponent(TestComponent, "left", "left"),
          liveComponent(TestComponent, "right", "right")
        )
      )
      val childCompiled = RenderProgram.compile[String, Nothing](value => span(value))
      val leftToken     = Object()
      val rightToken    = Object()
      val leftRef       = ComponentRef.runtime[String](leftToken)
      val rightRef      = ComponentRef.runtime[String](rightToken)
      for
        rootProgram  <- ZIO.fromEither(rootCompiled)
        childProgram <- ZIO.fromEither(childCompiled)
        root         <- rootProgram.evaluate(())
        left         <- childProgram.evaluate("left")
        right        <- childProgram.evaluate("right")
        leftRequirement  = root.componentRequirements(0)
        rightRequirement = root.componentRequirements(1)
        resolved <- ZIO.fromEither(
                      root.resolveComponents(
                        Vector(
                          resolve(leftRequirement, leftRef, leftToken, left),
                          resolve(rightRequirement, rightRef, rightToken, right)
                        )
                      )
                    )
        encoded <- ZIO.fromEither(PhoenixRenderedEncoder.initial(resolved.tree))
        isolated <- ZIO.fromEither(PhoenixRenderedEncoder.initial(resolved.tree))
        html     <- ZIO.fromEither(PhoenixRenderedEncoder.fullHtml(resolved.tree))
      yield assertTrue(
        encoded._2 == Json.Obj(
          "s" -> Json.Arr(Json.Str("<div>"), Json.Str(""), Json.Str("</div>")),
          "0" -> Json.Num(1),
          "1" -> Json.Num(2),
          "c" -> Json.Obj(
            "1" -> Json.Obj(
              "s" -> Json.Arr(
                Json.Str("<span data-phx-component=\"1\">"),
                Json.Str("</span>")
              ),
              "0" -> Json.Str("left")
            ),
            "2" -> Json.Obj(
              "s" -> Json.Arr(
                Json.Str("<span data-phx-component=\"2\">"),
                Json.Str("</span>")
              ),
              "0" -> Json.Str("right")
            )
          )
        ),
        encoded._1.cidForToken(leftToken).contains(1),
        encoded._1.tokenForCid(2).contains(rightToken),
        isolated._1.cidForToken(leftToken).contains(1),
        html._2 ==
          "<div><span data-phx-component=\"1\">left</span><span data-phx-component=\"2\">right</span></div>",
        html._1.cidForToken(leftToken).contains(1),
        html._1.cidForToken(rightToken).contains(2)
      )
    },
    test("allocates a nested component after its parent and emits a component-only sparse diff") {
      val rootCompiled = RenderProgram.compile[Unit, Nothing](_ =>
        div(liveComponent(TestComponent, "outer", "outer"))
      )
      val outerCompiled = RenderProgram.compile[Unit, Nothing](_ =>
        div(liveComponent(TestComponent, "inner", "inner"))
      )
      val innerCompiled = RenderProgram.compile[String, Nothing](value => span(value))
      val outerToken    = Object()
      val innerToken    = Object()
      val outerRef      = ComponentRef.runtime[String](outerToken)
      val innerRef      = ComponentRef.runtime[String](innerToken)
      for
        rootProgram  <- ZIO.fromEither(rootCompiled)
        outerProgram <- ZIO.fromEither(outerCompiled)
        innerProgram <- ZIO.fromEither(innerCompiled)
        root         <- rootProgram.evaluate(())
        outer        <- outerProgram.evaluate(())
        inner        <- innerProgram.evaluate("before")
        innerRequirement = outer.componentRequirements.head
        resolvedOuter <- ZIO.fromEither(
                           outer.resolveComponents(
                             Vector(resolve(innerRequirement, innerRef, innerToken, inner))
                           )
                         )
        outerRequirement = root.componentRequirements.head
        resolvedRoot <- ZIO.fromEither(
                          root.resolveComponents(
                            Vector(
                              resolve(
                                outerRequirement,
                                outerRef,
                                outerToken,
                                resolvedOuter
                              )
                            )
                          )
                        )
        initial <- ZIO.fromEither(PhoenixRenderedEncoder.initial(resolvedRoot.tree))
        currentInner <- innerProgram.evaluate("after", Some(inner.commit))
        delta = RenderDelta.Update(
          resolvedRoot.tree.revision,
          Vector(
            RenderChange.Component(
              outerToken,
              RenderDelta.Update(
                resolvedOuter.tree.revision,
                Vector(
                  RenderChange.Component(
                    innerToken,
                    TreeDiffer.diff(inner.tree, currentInner.tree)
                  )
                )
              )
            )
          )
        )
        updated <- ZIO.fromEither(PhoenixRenderedEncoder.update(initial._1, delta))
        html    <- ZIO.fromEither(PhoenixRenderedEncoder.fullHtml(resolvedRoot.tree))
      yield assertTrue(
        initial._1.cidForToken(outerToken).contains(1),
        initial._1.cidForToken(innerToken).contains(2),
        updated._2 == Json.Obj(
          "c" -> Json.Obj("2" -> Json.Obj("0" -> Json.Str("after")))
        ),
        updated._1.cidForToken(innerToken).contains(2),
        html._2 ==
          "<div><div data-phx-component=\"1\"><span data-phx-component=\"2\">before</span></div></div>",
        html._1.cidForToken(outerToken).contains(1),
        html._1.cidForToken(innerToken).contains(2)
      )
    },
    test("resolves an explicit component target to its CID") {
      val rootCompiled = RenderProgram.compile[Unit, Nothing](_ =>
        div(liveComponent(TestComponent, "child", "child"))
      )
      val token = Object()
      val ref   = ComponentRef.runtime[String](token)
      val childCompiled = RenderProgram.compile[String, String](value =>
        button(phx.target(ref), value)
      )
      for
        rootProgram  <- ZIO.fromEither(rootCompiled)
        childProgram <- ZIO.fromEither(childCompiled)
        root         <- rootProgram.evaluate(())
        child        <- childProgram.evaluate("click")
        requirement = root.componentRequirements.head
        resolved <- ZIO.fromEither(
                      root.resolveComponents(Vector(resolve(requirement, ref, token, child)))
                    )
        encoded <- ZIO.fromEither(PhoenixRenderedEncoder.initial(resolved.tree))
        html    <- ZIO.fromEither(PhoenixRenderedEncoder.fullHtml(resolved.tree))
        rootFields      = encoded._2.fields.toMap
        componentFields = rootFields("c").asInstanceOf[Json.Obj].fields.toMap
        component       = componentFields("1").asInstanceOf[Json.Obj]
        fields          = component.fields.toMap
      yield assertTrue(
        fields("s") == Json.Arr(
          Json.Str("<button phx-target=\"1\" data-phx-component=\"1\">"),
          Json.Str("</button>")
        ),
        html._2 ==
          "<div><button phx-target=\"1\" data-phx-component=\"1\">click</button></div>",
        html._1.cidForToken(token).contains(1)
      )
    },
    test("retires removed CIDs and assigns a fresh CID after reintroduction") {
      val rootCompiled = RenderProgram.compile[Boolean, Nothing](selected =>
        div(selected.when(span(liveComponent(TestComponent, "child", "child"))))
      )
      val childCompiled = RenderProgram.compile[String, Nothing](value => span(value))
      val firstToken    = Object()
      val secondToken   = Object()
      val firstRef      = ComponentRef.runtime[String](firstToken)
      val secondRef     = ComponentRef.runtime[String](secondToken)
      for
        rootProgram  <- ZIO.fromEither(rootCompiled)
        childProgram <- ZIO.fromEither(childCompiled)
        child        <- childProgram.evaluate("child")
        present      <- rootProgram.evaluate(true)
        resolvedPresent <- ZIO.fromEither(
                             present.resolveComponents(
                               Vector(
                                 resolve(
                                   present.componentRequirements.head,
                                   firstRef,
                                   firstToken,
                                   child
                                 )
                               )
                             )
                           )
        initial <- ZIO.fromEither(PhoenixRenderedEncoder.initial(resolvedPresent.tree))
        absent  <- rootProgram.evaluate(false, Some(resolvedPresent.commit))
        removed <- ZIO.fromEither(
                     PhoenixRenderedEncoder.update(
                       initial._1,
                       TreeDiffer.diff(resolvedPresent.tree, absent.tree)
                     )
                   )
        presentAgain <- rootProgram.evaluate(true, Some(absent.commit))
        resolvedAgain <- ZIO.fromEither(
                           presentAgain.resolveComponents(
                             Vector(
                               resolve(
                                 presentAgain.componentRequirements.head,
                                 secondRef,
                                 secondToken,
                                 child
                               )
                             )
                           )
                         )
        reintroduced <- ZIO.fromEither(
                          PhoenixRenderedEncoder.update(
                            removed._1,
                            TreeDiffer.diff(absent.tree, resolvedAgain.tree)
                          )
                        )
      yield assertTrue(
        initial._1.tokenForCid(1).contains(firstToken),
        removed._2 == Json.Obj("s" -> Json.Arr(Json.Str("<div></div>"))),
        removed._1.tokenForCid(1).isEmpty,
        removed._1.cidForToken(firstToken).isEmpty,
        reintroduced._2 == Json.Obj(
          "s" -> Json.Arr(Json.Str("<div><span>"), Json.Str("</span></div>")),
          "0" -> Json.Num(2),
          "c" -> Json.Obj(
            "2" -> Json.Obj(
              "s" -> Json.Arr(
                Json.Str("<span data-phx-component=\"2\">"),
                Json.Str("</span>")
              ),
              "0" -> Json.Str("child")
            )
          )
        ),
        reintroduced._1.cidForToken(secondToken).contains(2),
        reintroduced._1.tokenForCid(1).isEmpty
      )
    },
    test("replaces a component token at the same declaration with a monotonic CID") {
      val rootCompiled  = RenderProgram.compile[Unit, Nothing](_ =>
        div(liveComponent(TestComponent, "child", "child"))
      )
      val childCompiled = RenderProgram.compile[String, Nothing](value => span(value))
      val firstToken    = Object()
      val secondToken   = Object()
      val firstRef      = ComponentRef.runtime[String](firstToken)
      val secondRef     = ComponentRef.runtime[String](secondToken)
      for
        rootProgram  <- ZIO.fromEither(rootCompiled)
        childProgram <- ZIO.fromEither(childCompiled)
        child        <- childProgram.evaluate("child")
        first        <- rootProgram.evaluate(())
        resolvedFirst <- ZIO.fromEither(
                           first.resolveComponents(
                             Vector(
                               resolve(
                                 first.componentRequirements.head,
                                 firstRef,
                                 firstToken,
                                 child
                               )
                             )
                            )
                          )
        initial <- ZIO.fromEither(PhoenixRenderedEncoder.initial(resolvedFirst.tree))
        second  <- rootProgram.evaluate((), Some(resolvedFirst.commit))
        resolvedSecond <- ZIO.fromEither(
                            second.resolveComponents(
                              Vector(
                                resolve(
                                  second.componentRequirements.head,
                                  secondRef,
                                  secondToken,
                                  child
                                )
                              )
                            )
                          )
        replaced <- ZIO.fromEither(
                      PhoenixRenderedEncoder.update(
                        initial._1,
                        TreeDiffer.diff(resolvedFirst.tree, resolvedSecond.tree)
                      )
                    )
      yield assertTrue(
        replaced._2 == Json.Obj(
          "s" -> Json.Arr(Json.Str("<div>"), Json.Str("</div>")),
          "0" -> Json.Num(2),
          "c" -> Json.Obj(
            "2" -> Json.Obj(
              "s" -> Json.Arr(
                Json.Str("<span data-phx-component=\"2\">"),
                Json.Str("</span>")
              ),
              "0" -> Json.Str("child")
            )
          )
        ),
        replaced._1.cidForToken(firstToken).isEmpty,
        replaced._1.cidForToken(secondToken).contains(2)
      )
    },
    test("emits parent and nested payloads for component-scoped addition and removal") {
      val rootCompiled = RenderProgram.compile[Unit, Nothing](_ =>
        div(liveComponent(TestComponent, "outer", "outer"))
      )
      val outerCompiled = RenderProgram.compile[Boolean, Nothing](selected =>
        div(selected.when(span(liveComponent(TestComponent, "inner", "inner"))))
      )
      val innerCompiled = RenderProgram.compile[String, Nothing](value => span(value))
      val outerToken    = Object()
      val innerToken    = Object()
      val outerRef      = ComponentRef.runtime[String](outerToken)
      val innerRef      = ComponentRef.runtime[String](innerToken)
      for
        rootProgram  <- ZIO.fromEither(rootCompiled)
        outerProgram <- ZIO.fromEither(outerCompiled)
        innerProgram <- ZIO.fromEither(innerCompiled)
        root         <- rootProgram.evaluate(())
        outerAbsent  <- outerProgram.evaluate(false)
        resolvedRootAbsent <- ZIO.fromEither(
                                root.resolveComponents(
                                  Vector(
                                    resolve(
                                      root.componentRequirements.head,
                                      outerRef,
                                      outerToken,
                                      outerAbsent
                                    )
                                  )
                                )
                              )
        initial <- ZIO.fromEither(PhoenixRenderedEncoder.initial(resolvedRootAbsent.tree))
        inner   <- innerProgram.evaluate("inner")
        outerPresent <- outerProgram.evaluate(true, Some(outerAbsent.commit))
        resolvedOuterPresent <- ZIO.fromEither(
                                  outerPresent.resolveComponents(
                                    Vector(
                                      resolve(
                                        outerPresent.componentRequirements.head,
                                        innerRef,
                                        innerToken,
                                        inner
                                      )
                                    )
                                  )
                                )
        currentRoot <- rootProgram.evaluate((), Some(resolvedRootAbsent.commit))
        resolvedRootPresent <- ZIO.fromEither(
                                 currentRoot.resolveComponents(
                                   Vector(
                                     resolve(
                                       currentRoot.componentRequirements.head,
                                       outerRef,
                                       outerToken,
                                       resolvedOuterPresent
                                     )
                                   )
                                 )
                               )
        added <- ZIO.fromEither(
                   PhoenixRenderedEncoder.update(
                     initial._1,
                     TreeDiffer.diff(resolvedRootAbsent.tree, resolvedRootPresent.tree)
                   )
                 )
        outerAbsentAgain <- outerProgram.evaluate(false, Some(resolvedOuterPresent.commit))
        finalRoot        <- rootProgram.evaluate((), Some(resolvedRootPresent.commit))
        resolvedRootFinal <- ZIO.fromEither(
                               finalRoot.resolveComponents(
                                 Vector(
                                   resolve(
                                     finalRoot.componentRequirements.head,
                                     outerRef,
                                     outerToken,
                                     outerAbsentAgain
                                   )
                                 )
                               )
                             )
        removed <- ZIO.fromEither(
                     PhoenixRenderedEncoder.update(
                       added._1,
                       TreeDiffer.diff(resolvedRootPresent.tree, resolvedRootFinal.tree)
                     )
                   )
      yield assertTrue(
        added._2 == Json.Obj(
          "c" -> Json.Obj(
            "1" -> Json.Obj(
              "s" -> Json.Arr(
                Json.Str("<div data-phx-component=\"1\"><span>"),
                Json.Str("</span></div>")
              ),
              "0" -> Json.Num(2)
            ),
            "2" -> Json.Obj(
              "s" -> Json.Arr(
                Json.Str("<span data-phx-component=\"2\">"),
                Json.Str("</span>")
              ),
              "0" -> Json.Str("inner")
            )
          )
        ),
        added._1.cidForToken(innerToken).contains(2),
        removed._2 == Json.Obj(
          "c" -> Json.Obj(
            "1" -> Json.Obj(
              "s" -> Json.Arr(Json.Str("<div data-phx-component=\"1\"></div>"))
            )
          )
        ),
        removed._1.tokenForCid(2).isEmpty
      )
    },
    test("rejects stale and ambiguous targets without mutating prior state") {
      val validRootCompiled = RenderProgram.compile[Unit, Nothing](_ =>
        div(liveComponent(TestComponent, "valid", "valid"))
      )
      val staleRootCompiled = RenderProgram.compile[Unit, Nothing](_ =>
        div(liveComponent(TestComponent, "stale", "stale"))
      )
      val ambiguousRootCompiled = RenderProgram.compile[Unit, Nothing](_ =>
        div(
          liveComponent(TestComponent, "first", "first"),
          liveComponent(TestComponent, "second", "second")
        )
      )
      val token       = Object()
      val ref         = ComponentRef.runtime[String](token)
      val staleRef    = ComponentRef.runtime[String](Object())
      val unknownRef  = ComponentRef.runtime[String](Object())
      val sharedRef   = ComponentRef.runtime[String](Object())
      val secondToken = Object()
      val validChild  = RenderProgram.compile[String, Nothing](value => span(value))
      val staleChild  = RenderProgram.compile[String, String](value =>
        button(phx.target(staleRef), value)
      )
      val unknownChild = RenderProgram.compile[String, String](value =>
        button(phx.target(unknownRef), value)
      )
      val targetedChild = RenderProgram.compile[String, String](value =>
        button(phx.target(sharedRef), value)
      )
      for
        validRootProgram     <- ZIO.fromEither(validRootCompiled)
        staleRootProgram     <- ZIO.fromEither(staleRootCompiled)
        ambiguousRootProgram <- ZIO.fromEither(ambiguousRootCompiled)
        validChildProgram    <- ZIO.fromEither(validChild)
        staleChildProgram    <- ZIO.fromEither(staleChild)
        unknownChildProgram  <- ZIO.fromEither(unknownChild)
        targetedChildProgram <- ZIO.fromEither(targetedChild)
        validRoot            <- validRootProgram.evaluate(())
        valid                <- validChildProgram.evaluate("valid")
        resolvedValid <- ZIO.fromEither(
                           validRoot.resolveComponents(
                             Vector(
                               resolve(
                                 validRoot.componentRequirements.head,
                                 staleRef,
                                 token,
                                 valid
                               )
                             )
                           )
                         )
        initial <- ZIO.fromEither(PhoenixRenderedEncoder.initial(resolvedValid.tree))
        staleRoot  <- staleRootProgram.evaluate(())
        stale      <- staleChildProgram.evaluate("stale")
        resolvedStale <- ZIO.fromEither(
                           staleRoot.resolveComponents(
                             Vector(resolve(staleRoot.componentRequirements.head, ref, token, stale))
                           )
                         )
        staleResult = PhoenixRenderedEncoder.update(
          initial._1,
          RenderDelta.Replace(resolvedStale.tree)
        )
        unknown <- unknownChildProgram.evaluate("unknown")
        resolvedUnknown <- ZIO.fromEither(
                             staleRoot.resolveComponents(
                               Vector(
                                 resolve(
                                   staleRoot.componentRequirements.head,
                                   ref,
                                   token,
                                   unknown
                                 )
                               )
                             )
                           )
        unknownTargetResult = PhoenixRenderedEncoder.initial(resolvedUnknown.tree)
        ambiguousRoot <- ambiguousRootProgram.evaluate(())
        targeted      <- targetedChildProgram.evaluate("first")
        plain         <- validChildProgram.evaluate("second")
        resolvedAmbiguous <- ZIO.fromEither(
                               ambiguousRoot.resolveComponents(
                                 Vector(
                                   resolve(
                                     ambiguousRoot.componentRequirements(0),
                                     sharedRef,
                                     token,
                                     targeted
                                   ),
                                   resolve(
                                     ambiguousRoot.componentRequirements(1),
                                     sharedRef,
                                     secondToken,
                                     plain
                                   )
                                 )
                               )
                             )
        ambiguousResult = PhoenixRenderedEncoder.initial(resolvedAmbiguous.tree)
        unknownResult = PhoenixRenderedEncoder.update(
          initial._1,
          RenderDelta.Update(
            resolvedValid.tree.revision,
            Vector(RenderChange.Component(Object(), RenderDelta.Empty))
          )
        )
        stillUsable <- ZIO.fromEither(
                         PhoenixRenderedEncoder.update(initial._1, RenderDelta.Empty)
                       )
        validAfter     <- validChildProgram.evaluate("after", Some(valid.commit))
        validRootAfter <- validRootProgram.evaluate((), Some(resolvedValid.commit))
        resolvedValidAfter <- ZIO.fromEither(
                                validRootAfter.resolveComponents(
                                  Vector(
                                    resolve(
                                      validRootAfter.componentRequirements.head,
                                      staleRef,
                                      token,
                                      validAfter
                                    )
                                  )
                                )
                              )
        recovered <- ZIO.fromEither(
                       PhoenixRenderedEncoder.update(
                         initial._1,
                         TreeDiffer.diff(resolvedValid.tree, resolvedValidAfter.tree)
                       )
                     )
      yield assertTrue(
        staleResult == Left(PhoenixEncodingError.UnknownComponentTarget),
        unknownTargetResult == Left(PhoenixEncodingError.UnknownComponentTarget),
        ambiguousResult == Left(PhoenixEncodingError.UnknownComponentTarget),
        unknownResult == Left(PhoenixEncodingError.UnknownComponentToken),
        initial._1.cidForToken(token).contains(1),
        initial._1.tokenForCid(1).contains(token),
        stillUsable._1.cidForToken(token).contains(1),
        stillUsable._2 == Json.Obj.empty,
        recovered._2 == Json.Obj(
          "c" -> Json.Obj("1" -> Json.Obj("0" -> Json.Str("after")))
        ),
        recovered._1.cidForToken(token).contains(1)
      )
    }
  )

  private def reconstruct(rendered: Json.Obj): String =
    val fields = rendered.fields.toMap
    fields("s") match
      case Json.Arr(statics) =>
        statics.zipWithIndex.map { case (static, index) =>
          val value = static match
            case Json.Str(text) => text
            case _              => throw AssertionError("static segment is not a string")
          value + fields
            .get(index.toString).collect { case Json.Str(dynamic) => dynamic }.getOrElse("")
        }.mkString
      case _ => throw AssertionError("rendered map has no static array")
