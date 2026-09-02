package scalive.protocol.phoenix

import zio.Task
import zio.ZIO
import zio.json.ast.Json
import zio.test.*

import scalive.*
import scalive.render.*
import scalive.streams.*

object PhoenixRenderedEncoderSpec extends ZIOSpecDefault:
  final case class Model(text: String, raw: String, title: Option[String], disabled: Boolean)
  final case class StreamItem(id: String, label: String)
  final case class NestedStreamItem(id: String, nested: LiveStream[StreamItem])
  final case class KeyedStreamItem(id: String, values: Vector[(String, String)])

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
    def mount(props: String, ctx: MountContext): Task[Unit] = ZIO.unit
    def handleMessage(props: String, model: Unit, ctx: MessageContext): String => Task[Unit] =
      _ => ZIO.unit
    def view(
      props: Signal[String],
      model: Signal[Unit],
      self: ComponentRef[String]
    ): HtmlElement[String] = span(props)

  object TestView extends LiveView.Eventless[Unit]:
    def mount(ctx: MountContext): Task[Unit] = ZIO.unit
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
           "<main><div id=\"child&amp;&quot;\" data-phx-session=\"join&lt;&amp;&quot;\" data-phx-static=\"static&lt;&amp;&quot;\" data-phx-sticky class=\"phx-loading\"><span>disconnected &lt;child&gt;</span></div></main>"
       yield assertTrue(
         html._2 == expected,
         encoded._2 == Json.Obj(
           "s" -> Json.Arr(
             Json.Str(
               "<main><div id=\"child&amp;&quot;\" data-phx-session=\"join&lt;&amp;&quot;\" data-phx-static=\"static&lt;&amp;&quot;\" data-phx-sticky class=\"phx-loading\"><span>"
             ),
             Json.Str("</span></div></main>")
           ),
           "0" -> Json.Str("disconnected &lt;child&gt;")
         ),
         !expected.contains("data-phx-parent-id"),
         !expected.contains("lv:child")
       )
    },
    test("projects a connected nested LiveView with an empty static token") {
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
           "<div><div id=\"child\" data-phx-parent-id=\"parent\" data-phx-session=\"join-secret\" data-phx-static=\"\"></div></div>"
       yield assertTrue(
         html._2 == expected,
         encoded._2 == Json.Obj("s" -> Json.Arr(Json.Str(expected))),
         expected.contains("data-phx-static=\"\""),
        !expected.contains("data-phx-sticky"),
        !expected.contains("phx-loading")
      )
    },
    test("disconnected HTML keeps alternate empty-keyed components isolated from root choices") {
      def items(values: Signal[Vector[Int]]) =
        values.splitBy(identity)((_, value) => span(value.map(_.toString)))
      val directDefinition = new LiveComponent[Vector[Int], String, Vector[Int]]:
        def mount(props: Vector[Int], ctx: MountContext) = ZIO.succeed(props)
        def handleMessage(props: Vector[Int], model: Vector[Int], ctx: MessageContext) = _ =>
          ZIO.succeed(model)
        def view(props: Signal[Vector[Int]], model: Signal[Vector[Int]], self: ComponentRef[String]) =
          div(idAttr := "result", items(model))
      val asyncDefinition =
        new LiveComponent[AsyncValue[Vector[Int]], String, AsyncValue[Vector[Int]]]:
          def mount(props: AsyncValue[Vector[Int]], ctx: MountContext) = ZIO.succeed(props)
          def handleMessage(
            props: AsyncValue[Vector[Int]],
            model: AsyncValue[Vector[Int]],
            ctx: MessageContext
          ) = _ => ZIO.succeed(model)
          def view(
            props: Signal[AsyncValue[Vector[Int]]],
            model: Signal[AsyncValue[Vector[Int]]],
            self: ComponentRef[String]
          ) =
            val current = model.map(AsyncValue.currentValue)
            div(
              idAttr := "result",
              current.map(_.isDefined).chooseMod(
                items(current.map(_.getOrElse(Vector.empty))),
                ""
              )
            )
      val rootCompiled = RenderProgram.compile[(String, AsyncValue[Vector[Int]]), Nothing](input =>
        val caseName = input.map(_._1)
        val data     = input.map(_._2)
        val current  = data.map(AsyncValue.currentValue)
        div(
          Signal.when(caseName.map(_ == "second"))(div("separator")),
          caseName
            .map(_ == "first").chooseMod(
              current.map(_.isDefined).chooseMod(
                liveComponent(
                  directDefinition,
                  "direct",
                  current.map(_.getOrElse(Vector.empty))
                ),
                ""
              ),
              liveComponent(asyncDefinition, "async", data)
            ),
          div(button("load"), button("remove"))
        )
      )
      val componentCompiled = RenderProgram.compile[AsyncValue[Vector[Int]], String](model =>
        val current = model.map(AsyncValue.currentValue)
        div(
          idAttr := "result",
          current.map(_.isDefined).chooseMod(
            items(current.map(_.getOrElse(Vector.empty))),
            ""
          )
        )
      )
      val token = Object()
      val ref   = ComponentRef.runtime[String](token)

      for
        rootProgram      <- ZIO.fromEither(rootCompiled)
        componentProgram <- ZIO.fromEither(componentCompiled)
        root             <- rootProgram.evaluate("second" -> AsyncValue.ok(Vector.empty))
        component        <- componentProgram.evaluate(AsyncValue.ok(Vector.empty))
        resolved <- ZIO.fromEither(
                      root.resolveComponents(
                        Vector(resolve(root.componentRequirements.head, ref, token, component))
                      )
                    )
        html <- ZIO.fromEither(PhoenixRenderedEncoder.fullHtml(resolved.tree))
      yield assertTrue(
        html._2 ==
          "<div><div>separator</div><div id=\"result\" data-phx-component=\"1\"></div><div><button>load</button><button>remove</button></div></div>"
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
           "<div><div id=\"child\" data-phx-parent-id=\"parent\" data-phx-session=\"join-secret\" data-phx-static=\"\"><div><span data-phx-component=\"1\">component child</span><span id=\"row\">stream child</span></div></div></div>"
      )
    },
    test("uses a sole same-id nested LiveView as the direct stream row container") {
      val identity = LiveStreamIdentity.fresh()
      val handle = stream(
        identity,
        1L,
        Vector(StreamItem("item-1", "nested")),
        Vector((StreamItem("item-1", "nested"), StreamAt.Last, None, false))
      )
      val compiled = RenderProgram.compile[LiveStream[StreamItem], Nothing](model =>
        div(
          model.stream((domId, _) => div(idAttr := domId, liveView(domId, TestView)))
        )
      )

      for
        program   <- ZIO.fromEither(compiled)
        candidate <- program.evaluate(handle)
        resolved <- ZIO.fromEither(
                      candidate.resolveNested(
                        Vector(resolveNested(candidate.nestedRequirements.head, Object()))
                      )
                    )
        html <- ZIO.fromEither(PhoenixRenderedEncoder.fullHtml(resolved.tree))
       yield assertTrue(
         html._2 ==
           "<div><div id=\"item-1\" data-phx-parent-id=\"parent\" data-phx-session=\"join-secret\" data-phx-static=\"\"></div></div>"
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
               "<div><div id=\"child\" data-phx-parent-id=\"parent\" data-phx-session=\"join-secret\" data-phx-static=\"\"></div></div>"
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
    test("projects row-local bindings as keyed stream dynamics") {
      val identity = LiveStreamIdentity.fresh()
      val initial = stream(
        identity,
        1L,
        Vector(StreamItem("a", "one"), StreamItem("b", "two"))
      )
      val compiled = RenderProgram.compile[LiveStream[StreamItem], String](model =>
        div(
          model.stream((domId, item) =>
            button(
              idAttr              := domId,
              phx.value("row-id") := domId,
              on.click(item.map(_.id)),
              item.map(_.label)
            )
          )
        )
      )

      for
        program   <- ZIO.fromEither(compiled)
        candidate <- program.evaluate(initial)
        encoded   <- ZIO.fromEither(PhoenixRenderedEncoder.initial(candidate.tree))
        html      <- ZIO.fromEither(PhoenixRenderedEncoder.html(encoded._1))
      yield assertTrue(
        html.contains("id=\"a\""),
        html.contains("id=\"b\""),
        html.contains("phx-value-row-id=\"a\""),
        html.contains("phx-value-row-id=\"b\""),
        html.contains("phx-click=\""),
        field(dynamic(encoded._2), "k") match
          case Json.Obj(fields) => fields.toMap.get("kc").contains(Json.Num(2))
          case _                => false
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
    test("fully encodes a nested stream reset in an outer updateOnly row") {
      val outerIdentity  = LiveStreamIdentity.fresh()
      val nestedIdentity = LiveStreamIdentity.fresh()
      val firstNested = stream(
        nestedIdentity,
        1L,
        Vector(StreamItem("a", "A"), StreamItem("b", "B"))
      )
      val resetNested = stream(
        nestedIdentity,
        2L,
        Vector(StreamItem("b", "B"), StreamItem("a", "A")),
        Vector(
          (StreamItem("b", "B"), StreamAt.Last, None, false),
          (StreamItem("a", "A"), StreamAt.Last, None, false)
        ),
        reset = true
      )
      def outer(generation: Long, item: NestedStreamItem, update: Boolean) = LiveStream(
        outerIdentity,
        "groups",
        generation,
        Vector(LiveStreamEntry(item.id, item)),
        if update then
          Vector(LiveStreamInsert(LiveStreamEntry(item.id, item), StreamAt.Last, None, true))
        else Vector.empty,
        Vector.empty,
        reset = false
      )
      val compiled = RenderProgram.compile[LiveStream[NestedStreamItem], Nothing](model =>
        div(
          model.stream((domId, item) =>
            div(
              idAttr := domId,
              item.map(_.nested).stream((nestedId, nestedItem) =>
                span(idAttr := nestedId, nestedItem.map(_.label))
              )
            )
          )
        )
      )

      for
        program <- ZIO.fromEither(compiled)
        firstItem = NestedStreamItem("group", firstNested)
        first <- program.evaluate(outer(1L, firstItem, update = false))
        initial <- ZIO.fromEither(PhoenixRenderedEncoder.initial(first.tree))
        resetItem = NestedStreamItem("group", resetNested)
        reset <- program.evaluate(outer(2L, resetItem, update = true), Some(first.commit))
        updated <- ZIO.fromEither(
          PhoenixRenderedEncoder.update(initial._1, TreeDiffer.diff(first.tree, reset.tree))
        )
        outerRow = field(field(dynamic(updated._2), "k").asInstanceOf[Json.Obj], "0")
          .asInstanceOf[Json.Obj]
        nestedPayload = field(outerRow, "1").asInstanceOf[Json.Obj]
      yield assertTrue(
        field(nestedPayload, "k") == Json.Obj(
          "0" -> Json.Obj("0" -> Json.Str("b"), "1" -> Json.Str("B")),
          "1" -> Json.Obj("0" -> Json.Str("a"), "1" -> Json.Str("A")),
          "kc" -> Json.Num(2)
        ),
        field(nestedPayload, "stream") == Json.Arr(
          Json.Str("1"),
          Json.Arr(
            Json.Arr(Json.Str("b"), Json.Num(-1), Json.Null, Json.Bool(false)),
            Json.Arr(Json.Str("a"), Json.Num(-1), Json.Null, Json.Bool(false))
          ),
          Json.Arr(),
          Json.Bool(true)
        )
      )
    },
    test("selects a retained outer stream row when only its nested stream changes") {
      val outerIdentity  = LiveStreamIdentity.fresh()
      val nestedIdentity = LiveStreamIdentity.fresh()
      val firstNested = stream(
        nestedIdentity,
        1L,
        Vector(StreamItem("a", "A"), StreamItem("b", "B"))
      )
      val changedNested = stream(
        nestedIdentity,
        2L,
        Vector(StreamItem("c", "C"), StreamItem("d", "D")),
        Vector(
          (StreamItem("c", "C"), StreamAt.Last, None, false),
          (StreamItem("d", "D"), StreamAt.Last, None, false)
        ),
        deletes = Vector("a"),
        reset = true
      )
      def outer(generation: Long, nested: LiveStream[StreamItem]) = LiveStream(
        outerIdentity,
        "groups",
        generation,
        Vector(LiveStreamEntry("group", NestedStreamItem("group", nested))),
        Vector.empty,
        Vector.empty,
        reset = false
      )
      val compiled = RenderProgram.compile[LiveStream[NestedStreamItem], Nothing](model =>
        div(
          model.stream((domId, item) =>
            div(
              idAttr := domId,
              item.map(_.nested).stream((nestedId, nestedItem) =>
                span(idAttr := nestedId, nestedItem.map(_.label))
              )
            )
          )
        )
      )

      for
        program <- ZIO.fromEither(compiled)
        first   <- program.evaluate(outer(1L, firstNested))
        initial <- ZIO.fromEither(PhoenixRenderedEncoder.initial(first.tree))
        changed <- program.evaluate(outer(2L, changedNested), Some(first.commit))
        updated <- ZIO.fromEither(
                     PhoenixRenderedEncoder.update(
                       initial._1,
                       TreeDiffer.diff(first.tree, changed.tree)
                     )
                   )
        html <- ZIO.fromEither(PhoenixRenderedEncoder.html(updated._1))
      yield assertTrue(
        updated._2 == Json.Obj(
          "0" -> Json.Obj(
            "k" -> Json.Obj(
              "0" -> Json.Obj(
                "0" -> Json.Str("group"),
                "1" -> Json.Obj(
                  "k" -> Json.Obj(
                    "0" -> Json.Obj("0" -> Json.Str("c"), "1" -> Json.Str("C")),
                    "1" -> Json.Obj("0" -> Json.Str("d"), "1" -> Json.Str("D")),
                    "kc" -> Json.Num(2)
                  ),
                  "stream" -> Json.Arr(
                    Json.Str("1"),
                    Json.Arr(
                      Json.Arr(Json.Str("c"), Json.Num(-1), Json.Null, Json.Bool(false)),
                      Json.Arr(Json.Str("d"), Json.Num(-1), Json.Null, Json.Bool(false))
                    ),
                    Json.Arr(Json.Str("a")),
                    Json.Bool(true)
                  )
                )
              ),
              "kc" -> Json.Num(1)
            ),
            "stream" -> Json.Arr(Json.Str("0"), Json.Arr(), Json.Arr())
          )
        ),
        html ==
          "<div><div id=\"group\"><span id=\"c\">C</span><span id=\"d\">D</span></div></div>"
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
      val rootCompiled = RenderProgram.compile[Boolean, Nothing](selected =>
        div(selected.when(span(liveComponent(TestComponent, "stream", "stream"))))
      )
      val childCompiled = RenderProgram.compile[LiveStream[StreamItem], Nothing](model =>
        div(model.stream((domId, item) => span(idAttr := domId, item.map(_.label))))
      )
      val token = Object()
      val ref = ComponentRef.runtime[String](token)
      for
        rootProgram <- ZIO.fromEither(rootCompiled)
        childProgram <- ZIO.fromEither(childCompiled)
        root <- rootProgram.evaluate(true)
        child <- childProgram.evaluate(handle)
        resolved <- ZIO.fromEither(
          root.resolveComponents(Vector(resolve(root.componentRequirements.head, ref, token, child)))
        )
        encoded <- ZIO.fromEither(PhoenixRenderedEncoder.initial(resolved.tree))
        absent <- rootProgram.evaluate(false, Some(resolved.commit))
        removed <- ZIO.fromEither(
                     PhoenixRenderedEncoder.update(
                       encoded._1,
                       TreeDiffer.diff(resolved.tree, absent.tree)
                     )
                   )
        destroyed = removed._1.markComponentsForDeletion(Vector(1)).destroyComponents(Vector(1))
        components = field(encoded._2, "c").asInstanceOf[Json.Obj]
        component = field(components, "1").asInstanceOf[Json.Obj]
      yield assertTrue(
        encoded._1.cidForToken(token).contains(1),
        encoded._1.streamRef(identity).contains("0"),
        removed._1.streamRef(identity).contains("0"),
        destroyed._1.streamRef(identity).isEmpty,
        field(component, "s") == Json.Arr(
          Json.Str("<div data-phx-component=\"1\">"),
          Json.Str("</div>")
        ),
        field(component, "r") == Json.Num(1),
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
        updated._1.cidForToken(token).contains(1),
        updated._1.tokenForCid(1).contains(token),
        field(dynamic(updated._2), "stream") ==
          Json.Arr(Json.Str("0"), Json.Arr(), Json.Arr(Json.Str("a"))),
        projectedStreamRows(updated._1).isEmpty
      )
    },
    test("encodes ordinary keyed comprehensions with Phoenix k, kc, and km") {
      val compiled = RenderProgram.compile[Vector[(String, String)], Nothing](items =>
        div(items.splitBy(_._1)((_, item) => span(item.map(_._2))))
      )
      val child = (tag: String, value: String) => Json.Obj(
        "0" -> Json.Obj(
          "s" -> Json.Arr(Json.Str(s"<$tag>"), Json.Str(s"</$tag>")),
          "0" -> Json.Str(value)
        )
      )
      def keyed(fields: (String, Json)*): Json.Obj =
        val rows = fields.filterNot(_._1 == "km") :+ ("kc" -> Json.Num(2))
        val move = fields.filter(_._1 == "km")
        Json.Obj("0" -> Json.Obj("k" -> Json.Obj((rows ++ move)*)))

      for
        program <- ZIO.fromEither(compiled)
        first   <- program.evaluate(Vector("a" -> "A", "b" -> "B"))
        initial <- ZIO.fromEither(PhoenixRenderedEncoder.initial(first.tree))
        reordered <- program.evaluate(Vector("b" -> "B", "a" -> "A"), Some(first.commit))
        afterReorder <- ZIO.fromEither(
                          PhoenixRenderedEncoder.update(
                            initial._1,
                            TreeDiffer.diff(first.tree, reordered.tree)
                          )
                        )
        updated <- program.evaluate(Vector("b" -> "B2", "a" -> "A"), Some(reordered.commit))
        afterUpdate <- ZIO.fromEither(
                         PhoenixRenderedEncoder.update(
                           afterReorder._1,
                           TreeDiffer.diff(reordered.tree, updated.tree)
                         )
                       )
        moved <- program.evaluate(Vector("a" -> "A", "b" -> "B3"), Some(updated.commit))
        afterMoveUpdate <- ZIO.fromEither(
                             PhoenixRenderedEncoder.update(
                               afterUpdate._1,
                               TreeDiffer.diff(updated.tree, moved.tree)
                             )
                           )
        inserted <- program.evaluate(Vector("a" -> "A", "c" -> "C"), Some(moved.commit))
        afterInsertDelete <- ZIO.fromEither(
                               PhoenixRenderedEncoder.update(
                                 afterMoveUpdate._1,
                                 TreeDiffer.diff(moved.tree, inserted.tree)
                               )
                             )
        empty <- program.evaluate(Vector.empty, Some(inserted.commit))
        afterEmpty <- ZIO.fromEither(
                        PhoenixRenderedEncoder.update(
                          afterInsertDelete._1,
                          TreeDiffer.diff(inserted.tree, empty.tree)
                        )
                      )
        nonempty <- program.evaluate(Vector("d" -> "D"), Some(empty.commit))
        afterNonempty <- ZIO.fromEither(
                           PhoenixRenderedEncoder.update(
                             afterEmpty._1,
                             TreeDiffer.diff(empty.tree, nonempty.tree)
                           )
                         )
        initiallyEmpty <- program.evaluate(Vector.empty)
        emptyInitial    <- ZIO.fromEither(PhoenixRenderedEncoder.initial(initiallyEmpty.tree))
        html            <- ZIO.fromEither(PhoenixRenderedEncoder.html(afterInsertDelete._1))
      yield assertTrue(
        initial._2 == Json.Obj(
          "s" -> Json.Arr(Json.Str("<div>"), Json.Str("</div>")),
          "0" -> Json.Obj(
            "s" -> Json.Arr(Json.Str(""), Json.Str("")),
            "k" -> Json.Obj(
              "0" -> child("span", "A"),
              "1" -> child("span", "B"),
              "kc" -> Json.Num(2)
            )
          )
        ),
        afterReorder._2 == keyed(
          "0" -> Json.Num(1),
          "1" -> Json.Num(0),
          "km" -> Json.Bool(true)
        ),
        afterUpdate._2 == keyed("0" -> Json.Obj("0" -> Json.Obj("0" -> Json.Str("B2")))),
        afterMoveUpdate._2 == keyed(
          "0" -> Json.Num(1),
          "1" -> Json.Arr(
            Json.Num(0),
            Json.Obj("0" -> Json.Obj("0" -> Json.Str("B3")))
          ),
          "km" -> Json.Bool(true)
        ),
        afterInsertDelete._2 == keyed("1" -> child("span", "C")),
        afterEmpty._2 == Json.Obj("0" -> Json.Obj("k" -> Json.Obj("kc" -> Json.Num(0)))),
        afterNonempty._2 == Json.Obj(
          "0" -> Json.Obj(
            "k" -> Json.Obj("0" -> child("span", "D"), "kc" -> Json.Num(1))
          )
        ),
        emptyInitial._2 == Json.Obj(
          "s" -> Json.Arr(Json.Str("<div>"), Json.Str("</div>")),
          "0" -> Json.Obj(
            "s" -> Json.Arr(Json.Str(""), Json.Str("")),
            "k" -> Json.Obj("kc" -> Json.Num(0))
          )
        ),
        html == "<div><span>A</span><span>C</span></div>"
      )
    },
    test("boxes heterogeneous ordinary keyed row templates as dynamic zero") {
      val compiled = RenderProgram.compile[Vector[(String, String, String)], Nothing](items =>
        div(
          items.splitBy(_._1) { (_, item) =>
            div(
              item.map(_._2).choose(
                "span" -> span(item.map(_._3)),
                "button" -> button(item.map(_._3))
              )
            )
          }
        )
      )
      for
        program <- ZIO.fromEither(compiled)
        candidate <- program.evaluate(
                       Vector(("span", "span", "one"), ("button", "button", "two"))
                     )
        encoded <- ZIO.fromEither(PhoenixRenderedEncoder.initial(candidate.tree))
        html    <- ZIO.fromEither(PhoenixRenderedEncoder.html(encoded._1))
        rows = field(dynamic(encoded._2), "k").asInstanceOf[Json.Obj]
      yield assertTrue(
        field(field(rows, "0").asInstanceOf[Json.Obj], "0") == Json.Obj(
          "s" -> Json.Arr(Json.Str("<div><span>"), Json.Str("</span></div>")),
          "0" -> Json.Str("one")
        ),
        field(field(rows, "1").asInstanceOf[Json.Obj], "0") == Json.Obj(
          "s" -> Json.Arr(Json.Str("<div><button>"), Json.Str("</button></div>")),
          "0" -> Json.Str("two")
        ),
        field(rows, "kc") == Json.Num(2),
        html == "<div><div><span>one</span></div><div><button>two</button></div></div>"
      )
    },
    test("emits a nested keyed reorder through its retained outer keyed row") {
      val compiled = RenderProgram.compile[Vector[(String, Vector[(String, String)])], Nothing](
        groups =>
          div(
            groups.splitBy(_._1) { (_, group) =>
              div(group.map(_._2).splitBy(_._1)((_, item) => span(item.map(_._2))))
            }
          )
      )
      val firstValue = Vector("group" -> Vector("a" -> "A", "b" -> "B"))
      val nextValue  = Vector("group" -> Vector("b" -> "B", "a" -> "A"))

      for
        program <- ZIO.fromEither(compiled)
        first   <- program.evaluate(firstValue)
        initial <- ZIO.fromEither(PhoenixRenderedEncoder.initial(first.tree))
        next    <- program.evaluate(nextValue, Some(first.commit))
        updated <- ZIO.fromEither(
                     PhoenixRenderedEncoder.update(
                       initial._1,
                       TreeDiffer.diff(first.tree, next.tree)
                     )
                   )
      yield assertTrue(
        updated._2 == Json.Obj(
          "0" -> Json.Obj(
            "k" -> Json.Obj(
              "0" -> Json.Obj(
                "0" -> Json.Obj(
                  "0" -> Json.Obj(
                    "k" -> Json.Obj(
                      "0"  -> Json.Num(1),
                      "1"  -> Json.Num(0),
                      "kc" -> Json.Num(2),
                      "km" -> Json.Bool(true)
                    )
                  )
                )
              ),
              "kc" -> Json.Num(1)
            )
          )
        )
      )
    },
    test("emits a keyed reorder as a sparse component diff") {
      val rootCompiled = RenderProgram.compile[Unit, Nothing](_ =>
        div(liveComponent(TestComponent, "keyed", "keyed"))
      )
      val componentCompiled = RenderProgram.compile[Vector[(String, String)], Nothing](items =>
        div(items.splitBy(_._1)((_, item) => span(item.map(_._2))))
      )
      val token = Object()
      val ref   = ComponentRef.runtime[String](token)

      for
        rootProgram      <- ZIO.fromEither(rootCompiled)
        componentProgram <- ZIO.fromEither(componentCompiled)
        root             <- rootProgram.evaluate(())
        first            <- componentProgram.evaluate(Vector("a" -> "A", "b" -> "B"))
        resolvedFirst <- ZIO.fromEither(
                           root.resolveComponents(
                             Vector(resolve(root.componentRequirements.head, ref, token, first))
                           )
                         )
        initial <- ZIO.fromEither(PhoenixRenderedEncoder.initial(resolvedFirst.tree))
        nextComponent <- componentProgram.evaluate(
                           Vector("b" -> "B", "a" -> "A"),
                           Some(first.commit)
                         )
        nextRoot <- rootProgram.evaluate((), Some(resolvedFirst.commit))
        resolvedNext <- ZIO.fromEither(
                          nextRoot.resolveComponents(
                            Vector(
                              resolve(
                                nextRoot.componentRequirements.head,
                                ref,
                                token,
                                nextComponent
                              )
                            )
                          )
                        )
        updated <- ZIO.fromEither(
                     PhoenixRenderedEncoder.update(
                       initial._1,
                       TreeDiffer.diff(resolvedFirst.tree, resolvedNext.tree)
                     )
                   )
      yield assertTrue(
        updated._2 == Json.Obj(
          "c" -> Json.Obj(
            "1" -> Json.Obj(
              "0" -> Json.Obj(
                "k" -> Json.Obj(
                  "0"  -> Json.Num(1),
                  "1"  -> Json.Num(0),
                  "kc" -> Json.Num(2),
                  "km" -> Json.Bool(true)
                )
              )
            )
          )
        )
      )
    },
    test("selects a stream row whose keyed descendant changed") {
      val identity = LiveStreamIdentity.fresh()
      def handle(values: Vector[(String, String)]) = LiveStream(
        identity,
        "items",
        1L,
        Vector(LiveStreamEntry("row", KeyedStreamItem("row", values))),
        Vector.empty,
        Vector.empty,
        reset = false
      )
      val compiled = RenderProgram.compile[LiveStream[KeyedStreamItem], Nothing](model =>
        div(
          model.stream((domId, item) =>
            div(
              idAttr := domId,
              item.map(_.values).splitBy(_._1)((_, value) => span(value.map(_._2)))
            )
          )
        )
      )

      for
        program <- ZIO.fromEither(compiled)
        first   <- program.evaluate(handle(Vector("a" -> "A", "b" -> "B")))
        initial <- ZIO.fromEither(PhoenixRenderedEncoder.initial(first.tree))
        next <- program.evaluate(
                  handle(Vector("b" -> "B", "a" -> "A")),
                  Some(first.commit)
                )
        updated <- ZIO.fromEither(
                     PhoenixRenderedEncoder.update(
                       initial._1,
                       TreeDiffer.diff(first.tree, next.tree)
                     )
                   )
        streamDiff = dynamic(updated._2)
        streamRows = field(streamDiff, "k").asInstanceOf[Json.Obj]
        row        = field(streamRows, "0").asInstanceOf[Json.Obj]
      yield assertTrue(
        !streamDiff.fields.toMap.contains("s"),
        field(row, "1") == Json.Obj(
          "k" -> Json.Obj(
            "0"  -> Json.Num(1),
            "1"  -> Json.Num(0),
            "kc" -> Json.Num(2),
            "km" -> Json.Bool(true)
          )
        )
      )
    },
    test("rejects invalid keyed row deltas before changing reusable state") {
      val compiled = RenderProgram.compile[Vector[(String, String)], Nothing](items =>
        div(items.splitBy(_._1)((_, item) => span(item.map(_._2))))
      )

      for
        program <- ZIO.fromEither(compiled)
        first   <- program.evaluate(Vector("a" -> "A", "b" -> "B"))
        initial <- ZIO.fromEither(PhoenixRenderedEncoder.initial(first.tree))
        reordered <- program.evaluate(
                       Vector("b" -> "B", "a" -> "A"),
                       Some(first.commit)
                     )
        reorderDelta = TreeDiffer.diff(first.tree, reordered.tree)
        reorderedBase = reordered.commit
        (revision, keyedId, retainedRows) = reorderDelta match
          case RenderDelta.Update(revision, Vector(RenderChange.Keyed(id, rows))) =>
            (revision, id, rows)
          case _ => throw AssertionError("expected one keyed reorder")
        duplicateRetain = PhoenixRenderedEncoder.update(
                            initial._1,
                            RenderDelta.Update(
                              revision,
                              Vector(RenderChange.Keyed(keyedId, Vector.fill(2)(retainedRows.head)))
                            )
                          )
        recovered <- ZIO.fromEither(PhoenixRenderedEncoder.update(initial._1, reorderDelta))
        inserted <- program.evaluate(
                      Vector("b" -> "B", "a" -> "A", "c" -> "C"),
                      Some(reorderedBase)
                    )
        insertDelta = TreeDiffer.diff(reordered.tree, inserted.tree)
        (insertRevision, insertId, insertRows) = insertDelta match
          case RenderDelta.Update(revision, Vector(RenderChange.Keyed(id, rows))) =>
            (revision, id, rows)
          case _ => throw AssertionError("expected one keyed insertion")
        insertedState <- ZIO.fromEither(
                           PhoenixRenderedEncoder.update(recovered._1, insertDelta)
                         )
        existingInsert = PhoenixRenderedEncoder.update(insertedState._1, insertDelta)
        insertedRow = insertRows.collectFirst { case KeyedRowChange.Insert(row) => row }
          .getOrElse(throw AssertionError("expected an inserted row"))
        unknownRetain = PhoenixRenderedEncoder.update(
                          recovered._1,
                          RenderDelta.Update(
                            insertRevision,
                            Vector(
                              RenderChange.Keyed(
                                insertId,
                                retainedRows :+ KeyedRowChange.Retain(
                                  insertedRow.id,
                                  Vector.empty
                                )
                              )
                            )
                          )
                        )
        duplicateInsert = PhoenixRenderedEncoder.update(
                            recovered._1,
                            RenderDelta.Update(
                              insertRevision,
                              Vector(
                                RenderChange.Keyed(
                                  insertId,
                                  insertRows :+ KeyedRowChange.Insert(insertedRow)
                                )
                              )
                            )
                          )
        duplicateSemanticTarget = PhoenixRenderedEncoder.update(
                                    initial._1,
                                    RenderDelta.Update(
                                      revision,
                                      Vector(
                                        RenderChange.Replace(
                                          keyedId,
                                          first.tree.root.children.head
                                        ),
                                        RenderChange.Keyed(keyedId, retainedRows)
                                      )
                                    )
                                  )
        stillUsable <- ZIO.fromEither(PhoenixRenderedEncoder.update(initial._1, reorderDelta))
      yield assertTrue(
        duplicateRetain == Left(PhoenixEncodingError.InvalidKeyedRowChange),
        unknownRetain == Left(PhoenixEncodingError.InvalidKeyedRowChange),
        existingInsert == Left(PhoenixEncodingError.InvalidKeyedRowChange),
        duplicateInsert == Left(PhoenixEncodingError.InvalidKeyedRowChange),
        duplicateSemanticTarget == Left(PhoenixEncodingError.DuplicateTarget(keyedId)),
        recovered._2.fields.nonEmpty,
        stillUsable._2 == recovered._2
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
    test("projects repeated dynamic composite modifiers through one sparse slot") {
      val compiled = RenderProgram.compile[String, Nothing] { model =>
        div(cls := "base", cls := model)
      }

      for
        program <- ZIO.fromEither(compiled)
        first   <- program.evaluate("active active")
        initial <- ZIO.fromEither(PhoenixRenderedEncoder.initial(first.tree))
        second  <- program.evaluate("wide", Some(first.commit))
        updated <- ZIO.fromEither(
                     PhoenixRenderedEncoder.update(
                       initial._1,
                       TreeDiffer.diff(first.tree, second.tree)
                     )
                   )
        html <- ZIO.fromEither(PhoenixRenderedEncoder.html(updated._1))
      yield assertTrue(
        reconstruct(initial._2) == "<div class=\"base active\"></div>",
        updated._2.fields.map(_._1).toSet == Set("0"),
        html == HtmlRenderer.render(second.tree)
      )
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
              "0" -> Json.Str("left"),
              "r" -> Json.Num(1)
            ),
            "2" -> Json.Obj(
              "s" -> Json.Arr(
                Json.Str("<span data-phx-component=\"2\">"),
                Json.Str("</span>")
              ),
              "0" -> Json.Str("right"),
              "r" -> Json.Num(1)
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
        forced <- ZIO.fromEither(
                    PhoenixRenderedEncoder.update(
                      initial._1.markComponentsForDeletion(Vector(2)),
                      delta
                    )
                  )
        forcedComponent = field(
                            field(forced._2, "c").asInstanceOf[Json.Obj],
                            "2"
                          ).asInstanceOf[Json.Obj]
        html    <- ZIO.fromEither(PhoenixRenderedEncoder.fullHtml(resolvedRoot.tree))
      yield assertTrue(
        initial._1.cidForToken(outerToken).contains(1),
        initial._1.cidForToken(innerToken).contains(2),
        updated._2 == Json.Obj(
          "c" -> Json.Obj("2" -> Json.Obj("0" -> Json.Str("after")))
        ),
        forcedComponent.fields.toMap.contains("s"),
        field(forcedComponent, "r") == Json.Num(1),
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
          Json.Str("<button"),
          Json.Str(" data-phx-component=\"1\">"),
          Json.Str("</button>")
        ),
        fields("0") == Json.Str(" phx-target=\"1\""),
        fields("1") == Json.Str("click"),
        html._2 ==
          "<div><button phx-target=\"1\" data-phx-component=\"1\">click</button></div>",
        html._1.cidForToken(token).contains(1)
      )
    },
    test("retains and reuses removed CIDs until the browser confirms destruction") {
      val rootCompiled = RenderProgram.compile[Boolean, Nothing](selected =>
        div(selected.when(span(liveComponent(TestComponent, "child", "child"))))
      )
      val childCompiled = RenderProgram.compile[String, Nothing](value => span(value))
      val firstToken    = Object()
      val firstRef      = ComponentRef.runtime[String](firstToken)
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
                                  firstRef,
                                  firstToken,
                                  child
                               )
                             )
                           )
                         )
        marked = removed._1.markComponentsForDeletion(Vector(1))
        reintroduced <- ZIO.fromEither(
                           PhoenixRenderedEncoder.update(
                             marked,
                             TreeDiffer.diff(absent.tree, resolvedAgain.tree)
                           )
                         )
        ignored   = reintroduced._1.destroyComponents(Vector(1))
        delayed   = reintroduced._1.markComponentsForDeletion(Vector(1))
        stale     = delayed.destroyComponents(Vector(1))
        destroyed = marked.destroyComponents(Vector(1))
        fresh <- ZIO.fromEither(
                   PhoenixRenderedEncoder.update(
                     destroyed._1,
                     TreeDiffer.diff(absent.tree, resolvedAgain.tree)
                   )
                 )
      yield assertTrue(
        initial._1.tokenForCid(1).contains(firstToken),
        removed._2 == Json.Obj("s" -> Json.Arr(Json.Str("<div></div>"))),
        removed._1.tokenForCid(1).contains(firstToken),
        removed._1.cidForToken(firstToken).contains(1),
        reintroduced._2 == Json.Obj(
          "s" -> Json.Arr(Json.Str("<div><span>"), Json.Str("</span></div>")),
          "0" -> Json.Num(1),
          "c" -> Json.Obj(
            "1" -> Json.Obj(
              "s" -> Json.Arr(
                Json.Str("<span data-phx-component=\"1\">"),
                Json.Str("</span>")
              ),
              "0" -> Json.Str("child"),
              "r" -> Json.Num(1)
            )
          )
        ),
        reintroduced._1.cidForToken(firstToken).contains(1),
        ignored._2.isEmpty,
        ignored._1.cidForToken(firstToken).contains(1),
        stale._2.isEmpty,
        stale._1.cidForToken(firstToken).contains(1),
        destroyed._2 == Vector(1),
        destroyed._1.cidForToken(firstToken).isEmpty,
        fresh._1.cidForToken(firstToken).contains(2)
      )
    },
    test("fully re-encodes a retained component when its keyed row is reinserted") {
      val rootCompiled = RenderProgram.compile[Vector[(String, String)], Nothing](items =>
        div(
          items.splitBy(_._1)((_, item) =>
            span(liveComponent(TestComponent, "child", item.map(_._2)))
          )
        )
      )
      val childCompiled = RenderProgram.compile[String, Nothing](value => span(value))
      val token         = Object()
      val ref           = ComponentRef.runtime[String](token)

      for
        rootProgram  <- ZIO.fromEither(rootCompiled)
        childProgram <- ZIO.fromEither(childCompiled)
        first        <- rootProgram.evaluate(Vector("row" -> "before"))
        firstChild   <- childProgram.evaluate("before")
        resolvedFirst <- ZIO.fromEither(
                           first.resolveComponents(
                             Vector(
                               resolve(
                                 first.componentRequirements.head,
                                 ref,
                                 token,
                                 firstChild
                               )
                             )
                           )
                         )
        initial <- ZIO.fromEither(PhoenixRenderedEncoder.initial(resolvedFirst.tree))
        absent  <- rootProgram.evaluate(Vector.empty, Some(resolvedFirst.commit))
        removed <- ZIO.fromEither(
                     PhoenixRenderedEncoder.update(
                       initial._1,
                       TreeDiffer.diff(resolvedFirst.tree, absent.tree)
                     )
                   )
        next      <- rootProgram.evaluate(Vector("row" -> "after"), Some(absent.commit))
        nextChild <- childProgram.evaluate("after", Some(firstChild.commit))
        resolvedNext <- ZIO.fromEither(
                          next.resolveComponents(
                            Vector(
                              resolve(
                                next.componentRequirements.head,
                                ref,
                                token,
                                nextChild
                              )
                            )
                          )
                        )
        reinserted <- ZIO.fromEither(
                        PhoenixRenderedEncoder.update(
                          removed._1.markComponentsForDeletion(Vector(1)),
                          TreeDiffer.diff(absent.tree, resolvedNext.tree)
                        )
                      )
        row = field(field(dynamic(reinserted._2), "k").asInstanceOf[Json.Obj], "0")
          .asInstanceOf[Json.Obj]
        component = field(field(reinserted._2, "c").asInstanceOf[Json.Obj], "1")
          .asInstanceOf[Json.Obj]
        staleDestruction = reinserted._1.destroyComponents(Vector(1))
        html             <- ZIO.fromEither(PhoenixRenderedEncoder.html(reinserted._1))
      yield assertTrue(
        field(field(row, "0").asInstanceOf[Json.Obj], "0") == Json.Num(1),
        component == Json.Obj(
          "s" -> Json.Arr(
            Json.Str("<span data-phx-component=\"1\">"),
            Json.Str("</span>")
          ),
          "0" -> Json.Str("after"),
          "r" -> Json.Num(1)
        ),
        reinserted._1.cidForToken(token).contains(1),
        staleDestruction._2.isEmpty,
        staleDestruction._1.cidForToken(token).contains(1),
        html == "<div><span><span data-phx-component=\"1\">after</span></span></div>"
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
              "0" -> Json.Str("child"),
              "r" -> Json.Num(1)
            )
          )
        ),
        replaced._1.cidForToken(firstToken).contains(1),
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
              "0" -> Json.Num(2),
              "r" -> Json.Num(1)
            ),
            "2" -> Json.Obj(
              "s" -> Json.Arr(
                Json.Str("<span data-phx-component=\"2\">"),
                Json.Str("</span>")
              ),
              "0" -> Json.Str("inner"),
              "r" -> Json.Num(1)
            )
          )
        ),
        added._1.cidForToken(innerToken).contains(2),
        removed._2 == Json.Obj(
          "c" -> Json.Obj(
            "1" -> Json.Obj(
              "s" -> Json.Arr(Json.Str("<div data-phx-component=\"1\"></div>")),
              "r" -> Json.Num(1)
            )
          )
        ),
        removed._1.tokenForCid(2).contains(innerToken)
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
