package scalive.runtime.connection

import zio.*
import zio.json.ast.Json
import zio.http.URL
import zio.test.*

import scalive.*
import scalive.render.*

object DisconnectedComponentRendererSpec extends ZIOSpecDefault:
  private val config = ConnectionConfig.make(4, 4, 4, 4, 4).toOption.get
  private val metadata = RootConnectionMetadata(false, Map.empty[String, Json])

  private def text(node: EvaluatedNode): String = node match
    case value: EvaluatedNode.Text    => value.value
    case value: EvaluatedNode.Element => value.children.map(text).mkString
    case value: EvaluatedNode.Component =>
      value.resolution.map(result => text(result.child.root)).getOrElse("")
    case value: EvaluatedNode.Choice => value.child.map(text).getOrElse("")
    case value: EvaluatedNode.Flash  => value.child.map(text).getOrElse("")
    case value: EvaluatedNode.Keyed  => value.rows.map(row => text(row.child)).mkString
    case value: EvaluatedNode.Stream => value.rows.map(row => text(row.child)).mkString
    case value: EvaluatedNode.Nested =>
      value.resolution.flatMap(_.child).map(tree => text(tree.root)).getOrElse("")

  private def resolvedNestedIds(node: EvaluatedNode): Vector[String] = node match
    case value: EvaluatedNode.Element => value.children.flatMap(resolvedNestedIds)
    case value: EvaluatedNode.Component =>
      value.resolution.toVector.flatMap(result => resolvedNestedIds(result.child.root))
    case value: EvaluatedNode.Choice => value.child.toVector.flatMap(resolvedNestedIds)
    case value: EvaluatedNode.Flash  => value.child.toVector.flatMap(resolvedNestedIds)
    case value: EvaluatedNode.Keyed  => value.rows.flatMap(row => resolvedNestedIds(row.child))
    case value: EvaluatedNode.Stream => value.rows.flatMap(row => resolvedNestedIds(row.child))
    case value: EvaluatedNode.Nested =>
      value.resolution.toVector.map(_.applicationId) ++
        value.resolution.toVector.flatMap(_.child).flatMap(tree => resolvedNestedIds(tree.root))
    case _: EvaluatedNode.Text => Vector.empty

  private def nestedAnswer(requirement: NestedRequirement): NestedResolution =
    requirement.resolve(
      Object(),
      parentDomId = s"parent-${requirement.applicationId}",
      topic = s"topic-${requirement.applicationId}",
      joinCredential = s"join-${requirement.applicationId}"
    )

  override def spec = suite("DisconnectedComponentRendererSpec")(
    test("disconnected and connected component mounts are independent") {
      ZIO.scoped {
        for
          mounts       <- Ref.make(Vector.empty[Connection[ComponentConnected[Nothing]]])
          afterRenders <- Ref.make(0)
          definition = new LiveComponent.Eventless[String, String]:
                         override val hooks = ComponentLiveHooks.empty[String, Nothing, String]
                           .afterRender((_, _, _) => afterRenders.update(_ + 1))
                         def mount(props: String, ctx: MountContext) =
                           mounts.update(_ :+ ctx.connection).as(props)
                         override def update(props: String, model: String, ctx: UpdateContext) =
                           ZIO.succeed(model)
                         def view(
                           props: Signal[String],
                           model: Signal[String],
                           self: ComponentRef[Nothing]
                         ) = span(model)
          instance = component(definition, "label")
          rendered <- DisconnectedComponentRenderer.renderWith[Unit, Nothing, String](
                        _ => div(instance.render("disconnected")),
                        ()
                      )(tree => ZIO.succeed(text(tree.root)))
          sink <- Queue.unbounded[ConnectionOutput]
          root = new LiveView[Unit, Unit]:
                   def mount(ctx: MountContext) = ZIO.unit
                   def handleMessage(model: Unit, ctx: MessageContext): Unit => LiveIO[Unit] =
                     _ => ZIO.unit
                   def view(model: Signal[Unit]) = div(instance.render("connected"))
          connection <- RootConnection.start(config, metadata, root, sink.offer(_).unit)
          _          <- sink.take
          observed   <- mounts.get
          renderedHooks <- afterRenders.get
        yield assertTrue(
          rendered == "disconnected",
          observed.size == 2,
          observed.head == Connection.Disconnected,
          observed(1).isInstanceOf[Connection.Connected[?]],
          renderedHooks >= 2,
          connection ne null
        )
      }
    },
    test("resolves alternate components with empty keyed content without duplicating siblings") {
      def items(values: Signal[Vector[Int]]) =
        values.splitBy(identity)((_, value) => span(value.map(_.toString)))
      val directDefinition = new LiveComponent.Eventless[Vector[Int], Vector[Int]]:
        def mount(props: Vector[Int], ctx: MountContext) = ZIO.succeed(props)
        override def update(props: Vector[Int], model: Vector[Int], ctx: UpdateContext) =
          ZIO.succeed(props)
        def view(
          props: Signal[Vector[Int]],
          model: Signal[Vector[Int]],
          self: ComponentRef[Nothing]
        ) = div(idAttr := "result", items(model))
      val asyncDefinition =
        new LiveComponent.Eventless[AsyncValue[Vector[Int]], AsyncValue[Vector[Int]]]:
          def mount(props: AsyncValue[Vector[Int]], ctx: MountContext) = ZIO.succeed(props)
          override def update(
            props: AsyncValue[Vector[Int]],
            model: AsyncValue[Vector[Int]],
            ctx: UpdateContext
          ) = ZIO.succeed(props)
          def view(
            props: Signal[AsyncValue[Vector[Int]]],
            model: Signal[AsyncValue[Vector[Int]]],
            self: ComponentRef[Nothing]
          ) =
            val current = model.map(AsyncValue.currentValue)
            div(
              idAttr := "result",
              current.map(_.isDefined).chooseMod(
                items(current.map(_.getOrElse(Vector.empty))),
                ""
              )
            )
      val direct = component(directDefinition, "direct")
      val async  = component(asyncDefinition, "async")
      DisconnectedComponentRenderer
        .renderWith[(String, AsyncValue[Vector[Int]]), Nothing, String](input =>
          val caseName = input.map(_._1)
          val data     = input.map(_._2)
          val current  = data.map(AsyncValue.currentValue)
          div(
            Signal.when(caseName.map(_ == "second"))(div("separator")),
            caseName
              .map(_ == "first").chooseMod(
                current.map(_.isDefined).chooseMod(
                  direct.render(current.map(_.getOrElse(Vector.empty))),
                  ""
                ),
                async.render(data)
              ),
            div(button("load"), button("remove"))
          ),
          "second" -> AsyncValue.ok(Vector.empty)
        )(tree => ZIO.succeed(HtmlRenderer.render(tree)))
        .map(html =>
          assertTrue(
            html ==
              "<div><div>separator</div><div id=\"result\"></div><div><button>load</button><button>remove</button></div></div>"
          )
        )
    },
    test("tree consumption runs before cleanup and success/failure both release one-shot state") {
      for
        mounts <- Ref.make(0)
        definition = new LiveComponent.Eventless[Unit, Int]:
                       def mount(props: Unit, ctx: MountContext) = mounts.updateAndGet(_ + 1)
                       def view(
                         props: Signal[Unit],
                         model: Signal[Int],
                         self: ComponentRef[Nothing]
                       ) = span(model.map(_.toString))
        instance = component(definition, "counter")
        view      = (_: Signal[Unit]) => div(instance.render(()))
        successProgram <- ZIO.fromEither(RenderProgram.compile(view))
        first <- DisconnectedComponentRenderer.renderProgramWith(successProgram, ()) { tree =>
                   ZIO.succeed(text(tree.root))
                 }
        successClosed <- successProgram.evaluate(()).either
        failureProgram <- ZIO.fromEither(RenderProgram.compile(view))
        failed <- DisconnectedComponentRenderer
                    .renderProgramWith(failureProgram, ())(_ =>
                      ZIO.fail(Exception("consumer failed"))
                    ).either
        failureClosed <- failureProgram.evaluate(()).either
        third <- DisconnectedComponentRenderer.renderWith(view, ()) { tree =>
                   ZIO.succeed(text(tree.root))
                 }
        count <- mounts.get
      yield assertTrue(
        first == "1",
        successClosed.isLeft,
        failed.isLeft,
        failureClosed.isLeft,
        third == "3",
        count == 3
      )
    },
    test("disconnected component mount flash is visible in its own projection") {
      val kind = FlashKind("disconnected-own")
      val definition = new LiveComponent.Eventless[String, Unit]:
        def mount(props: String, ctx: MountContext) = ctx.flash.put(kind, props)
        def view(props: Signal[String], model: Signal[Unit], self: ComponentRef[Nothing]) =
          div(scalive.flash(kind)(message => span(message)))
      val instance = component(definition, "own-flash")
      DisconnectedComponentRenderer
        .renderWith[Unit, Nothing, String](_ => div(instance.render("visible")), ()) { tree =>
          ZIO.succeed(text(tree.root))
        }.map(rendered => assertTrue(rendered == "visible"))
    },
    test("disconnected sibling component lifecycles share flash in declaration order") {
      val kind = FlashKind("disconnected-shared")
      val writerDefinition = new LiveComponent.Eventless[Unit, Unit]:
        def mount(props: Unit, ctx: MountContext) = ctx.flash.put(kind, "shared")
        def view(props: Signal[Unit], model: Signal[Unit], self: ComponentRef[Nothing]) =
          div(scalive.flash(kind)(message => span(message)))
      val readerDefinition = new LiveComponent.Eventless[Unit, Unit]:
        def mount(props: Unit, ctx: MountContext) = ZIO.unit
        def view(props: Signal[Unit], model: Signal[Unit], self: ComponentRef[Nothing]) =
          div(scalive.flash(kind)(message => span(message)))
      val writer = component(writerDefinition, "writer")
      val reader = component(readerDefinition, "reader")
      for
        writerFirst <- DisconnectedComponentRenderer.renderWith[Unit, Nothing, String](
                         _ => div(writer.render(()), reader.render(())),
                         ()
                       )(tree => ZIO.succeed(text(tree.root)))
        readerFirst <- DisconnectedComponentRenderer.renderWith[Unit, Nothing, String](
                         _ => div(reader.render(()), writer.render(())),
                         ()
                       )(tree => ZIO.succeed(text(tree.root)))
      yield assertTrue(writerFirst == "sharedshared", readerFirst == "shared")
    },
    test("rejects duplicate definition and application identities across the recursive graph") {
      val leafDefinition = new LiveComponent.Eventless[Unit, Unit]:
        def mount(props: Unit, ctx: MountContext) = ZIO.unit
        def view(props: Signal[Unit], model: Signal[Unit], self: ComponentRef[Nothing]) = span()
      val leaf = component(leafDefinition, "duplicate")
      val parentDefinition = new LiveComponent.Eventless[Unit, Unit]:
        def mount(props: Unit, ctx: MountContext) = ZIO.unit
        def view(props: Signal[Unit], model: Signal[Unit], self: ComponentRef[Nothing]) =
          div(leaf.render(()))
      val parent = component(parentDefinition, "parent")
      for
        siblings <- DisconnectedComponentRenderer
                      .renderWith[Unit, Nothing, Unit](
                        _ => div(leaf.render(()), leaf.render(())),
                        ()
                      )(_ => ZIO.unit).either
        recursive <- DisconnectedComponentRenderer
                       .renderWith[Unit, Nothing, Unit](
                         _ => div(leaf.render(()), parent.render(())),
                         ()
                       )(_ => ZIO.unit).either
        valid <- DisconnectedComponentRenderer.renderWith[Unit, Nothing, String](
                   _ => div(leaf.render(())),
                   ()
                 )(tree => ZIO.succeed(text(tree.root)))
      yield assertTrue(siblings.isLeft, recursive.isLeft, valid == "")
    },
    test("shares component flash with DisconnectedRootTurn and reprojects root declarations once") {
      val oldKind = FlashKind("old-claim")
      val newKind = FlashKind("component-claim")
      val definition = new LiveComponent.Eventless[Unit, Unit]:
        def mount(props: Unit, ctx: MountContext) =
          ctx.flash.clear(oldKind) *> ctx.flash.put(newKind, "root-visible")
        def view(props: Signal[Unit], model: Signal[Unit], self: ComponentRef[Nothing]) = div()
      val instance = component(definition, "flash-owner")
      for
        turn <- DisconnectedRootTurn.make[Unit, Unit](
                  LiveHooks.empty,
                  URL.root,
                  Map(oldKind -> "remove-me")
                )
        consumed <- DisconnectedComponentRenderer.renderTurnWith[Unit, Nothing, (
                      String,
                      Map[FlashKind, String]
                    )](
                      _ => div(
                        scalive.flash(oldKind)(message => span(message)),
                        scalive.flash(newKind)(message => span(message)),
                        instance.render(())
                      ),
                      (),
                      turn
                    )((tree, flash) => ZIO.succeed(text(tree.root) -> flash))
        finalFlash <- turn.flash
      yield assertTrue(
        consumed._1 == "root-visible",
        consumed._2 == Map(newKind -> "root-visible"),
        finalFlash == consumed._2
      )
    },
    test("stabilizes flash-driven component additions and removals without rerunning lifecycles") {
      val kind = FlashKind("graph")
      for
        childMounts <- Ref.make(0)
        childAfters <- Ref.make(0)
        triggerMounts <- Ref.make(0)
        childDefinition = new LiveComponent.Eventless[Unit, Unit]:
                            override val hooks = ComponentLiveHooks.empty[Unit, Nothing, Unit]
                              .afterRender((_, _, _) => childAfters.update(_ + 1))
                            def mount(props: Unit, ctx: MountContext) =
                              childMounts.update(_ + 1)
                            def view(props: Signal[Unit], model: Signal[Unit], self: ComponentRef[Nothing]) =
                              span("child")
        child = component(childDefinition, "conditional")
        addDefinition = new LiveComponent.Eventless[Unit, Unit]:
                          def mount(props: Unit, ctx: MountContext) =
                            triggerMounts.update(_ + 1) *> ctx.flash.put(kind, "show")
                          def view(props: Signal[Unit], model: Signal[Unit], self: ComponentRef[Nothing]) = div()
        add = component(addDefinition, "trigger")
        added <- DisconnectedComponentRenderer.renderWith[Unit, Nothing, String](
                   _ => div(add.render(()), scalive.flash(kind)(_ => div(child.render(())))),
                   ()
                 )(tree => ZIO.succeed(text(tree.root)))
        addCounts <- triggerMounts.get.zip(childMounts.get).zip(childAfters.get)
        removeDefinition = new LiveComponent.Eventless[Unit, Unit]:
                             def mount(props: Unit, ctx: MountContext) = ctx.flash.clear(kind)
                             def view(props: Signal[Unit], model: Signal[Unit], self: ComponentRef[Nothing]) = div()
        remove = component(removeDefinition, "remover")
        removed <- DisconnectedComponentRenderer.renderWith[Unit, Nothing, String](
                      _ => div(remove.render(()), scalive.flash(kind)(_ => div(child.render(())))),
                     (),
                     Map(kind -> "show")
                   )(tree => ZIO.succeed(text(tree.root)))
        finalChildMounts <- childMounts.get
        finalChildAfters <- childAfters.get
      yield assertTrue(
        added == "child",
        addCounts == (1, 1, 1),
        removed == "",
        finalChildMounts == 2,
        finalChildAfters == 2
      )
    },
    test("updates retained disconnected components when flash reprojection changes props") {
      val kind = FlashKind("props")
      for
        mounts  <- Ref.make(0)
        updates <- Ref.make(Vector.empty[String])
        afters  <- Ref.make(0)
        retainedDefinition = new LiveComponent.Eventless[String, String]:
                               override val hooks = ComponentLiveHooks.empty[String, Nothing, String]
                                 .afterRender((_, _, _) => afters.update(_ + 1))
                               def mount(props: String, ctx: MountContext) =
                                 mounts.update(_ + 1).as("mounted")
                               override def update(props: String, model: String, ctx: UpdateContext) =
                                 updates.update(_ :+ props).as(props)
                               def view(
                                 props: Signal[String],
                                 model: Signal[String],
                                 self: ComponentRef[Nothing]
                               ) = span(model)
        retained = component(retainedDefinition, "retained")
        triggerDefinition = new LiveComponent.Eventless[Unit, Unit]:
                              def mount(props: Unit, ctx: MountContext) =
                                ctx.flash.put(kind, "final")
                              def view(props: Signal[Unit], model: Signal[Unit], self: ComponentRef[Nothing]) = div()
        trigger = component(triggerDefinition, "props-trigger")
        rendered <- DisconnectedComponentRenderer.renderWith[Unit, Nothing, String](
                      _ => div(
                        trigger.render(()),
                        scalive.flash(kind)(value => div(retained.render(value)))
                      ),
                      (),
                      Map(kind -> "initial")
                    )(tree => ZIO.succeed(text(tree.root)))
        mountCount <- mounts.get
        updateProps <- updates.get
        afterCount  <- afters.get
      yield assertTrue(
        rendered == "final",
        mountCount == 1,
        updateProps == Vector("initial", "final"),
        afterCount == 2
      )
    },
    test("resolves component descendants before component and root nested declarations") {
      object ChildView extends LiveView.Eventless[Unit]:
        def mount(ctx: MountContext) = ZIO.unit
        def view(model: Signal[Unit]) = span()

      var constructions = 0
      val leafDefinition = new LiveComponent.Eventless[Unit, Unit]:
        def mount(props: Unit, ctx: MountContext) = ZIO.unit
        def view(props: Signal[Unit], model: Signal[Unit], self: ComponentRef[Nothing]) =
          div(liveView("leaf-nested", { constructions += 1; ChildView }))
      val leaf = component(leafDefinition, "leaf-component")
      val parentDefinition = new LiveComponent.Eventless[Unit, Unit]:
        def mount(props: Unit, ctx: MountContext) = ZIO.unit
        def view(props: Signal[Unit], model: Signal[Unit], self: ComponentRef[Nothing]) =
          div(
            leaf.render(()),
            liveView("parent-nested", { constructions += 1; ChildView })
          )
      val parent = component(parentDefinition, "parent-component")

      for
        order <- Ref.make(Vector.empty[String])
        resolver = new DisconnectedNestedResolver:
                     def resolve(requirement: NestedRequirement) =
                       order.update(_ :+ requirement.applicationId).as(nestedAnswer(requirement))
        turn <- DisconnectedRootTurn.make[Unit, Unit](
                  LiveHooks.empty,
                  URL.root,
                  Map.empty
                )
        ids <- DisconnectedComponentRenderer.renderTurnWith[Unit, Nothing, Vector[String]](
                 _ => div(
                   parent.render(()),
                   liveView("root-nested", { constructions += 1; ChildView })
                 ),
                 (),
                 turn,
                 resolver
               )((tree, _) => ZIO.succeed(resolvedNestedIds(tree.root)))
        observed <- order.get
      yield assertTrue(
        ids == Vector("leaf-nested", "parent-nested", "root-nested"),
        observed == Vector(
          "leaf-nested",
          "parent-nested",
          "root-nested"
        ),
        constructions == 0
      )
    },
    test("rejects duplicate nested ids across component and root before consumption") {
      object ChildView extends LiveView.Eventless[Unit]:
        def mount(ctx: MountContext) = ZIO.unit
        def view(model: Signal[Unit]) = span()

      val definition = new LiveComponent.Eventless[Unit, Unit]:
        def mount(props: Unit, ctx: MountContext) = ZIO.unit
        def view(props: Signal[Unit], model: Signal[Unit], self: ComponentRef[Nothing]) =
          div(liveView("duplicate-nested", ChildView))
      val instance = component(definition, "nested-owner")

      for
        consumed <- Ref.make(false)
        turn     <- DisconnectedRootTurn.make[Unit, Unit](LiveHooks.empty, URL.root, Map.empty)
        resolver = new DisconnectedNestedResolver:
                     def resolve(requirement: NestedRequirement) =
                       ZIO.succeed(nestedAnswer(requirement))
        result <- DisconnectedComponentRenderer
                    .renderTurnWith[Unit, Nothing, Unit](
                      _ => div(instance.render(()), liveView("duplicate-nested", ChildView)),
                      (),
                      turn,
                      resolver
                    )((_, _) => consumed.set(true)).either
        wasConsumed <- consumed.get
      yield assertTrue(
        result.left.exists(error =>
          error.isInstanceOf[IllegalArgumentException] &&
            error.getMessage.contains("Duplicate nested LiveView id")
        ),
        !wasConsumed
      )
    },
    test("constructs a nested factory only when the injected resolver requests it") {
      object ChildView extends LiveView.Eventless[Unit]:
        def mount(ctx: MountContext) = ZIO.unit
        def view(model: Signal[Unit]) = span()

      var constructions = 0
      val view = (_: Signal[Unit]) =>
        div(liveView("factory-child", { constructions += 1; ChildView }))

      for
        turn <- DisconnectedRootTurn.make[Unit, Unit](LiveHooks.empty, URL.root, Map.empty)
        resolver = new DisconnectedNestedResolver:
                     def resolve(requirement: NestedRequirement) =
                       ZIO.attempt(requirement.create()).as(nestedAnswer(requirement))
        _ <- DisconnectedComponentRenderer.renderTurnWith(view, (), turn, resolver)((_, _) =>
               ZIO.unit
             )
      yield assertTrue(constructions == 1)
    },
    test("resolver failure closes owned render state and never consumes the tree") {
      object ChildView extends LiveView.Eventless[Unit]:
        def mount(ctx: MountContext) = ZIO.unit
        def view(model: Signal[Unit]) = span()

      for
        consumed <- Ref.make(false)
        afters   <- Ref.make(0)
        definition = new LiveComponent.Eventless[Unit, Unit]:
                       override val hooks = ComponentLiveHooks.empty[Unit, Nothing, Unit]
                         .afterRender((_, _, _) => afters.update(_ + 1))
                       def mount(props: Unit, ctx: MountContext) = ZIO.unit
                       def view(
                         props: Signal[Unit],
                         model: Signal[Unit],
                         self: ComponentRef[Nothing]
                       ) = div(liveView("failing-child", ChildView))
        instance = component(definition, "failing-owner")
        program  <- ZIO.fromEither(
                      RenderProgram.compile[Unit, Nothing](_ => div(instance.render(())))
                    )
        resolver = new DisconnectedNestedResolver:
                     def resolve(requirement: NestedRequirement) =
                       ZIO.fail(Exception("resolver failed"))
        result <- DisconnectedComponentRenderer
                    .renderProgramWith(program, (), nestedResolver = resolver)(_ =>
                      consumed.set(true)
                    ).either
        closed      <- program.evaluate(()).either
        wasConsumed <- consumed.get
        afterCount  <- afters.get
      yield assertTrue(
        result.left.exists(_.getMessage == "resolver failed"),
        closed.isLeft,
        !wasConsumed,
        afterCount == 0
      )
    },
    test("plain disconnected calls explicitly reject nested declarations") {
      object ChildView extends LiveView.Eventless[Unit]:
        def mount(ctx: MountContext) = ZIO.unit
        def view(model: Signal[Unit]) = span()

      var constructions = 0
      DisconnectedComponentRenderer
        .renderWith[Unit, Nothing, Unit](
          _ => div(liveView("unavailable-child", { constructions += 1; ChildView })),
          ()
        )(_ => ZIO.unit).either
        .map(result =>
          assertTrue(
            result.left.exists(_.getMessage.contains("nested LiveViews are unavailable")),
            constructions == 0
          )
        )
    }
  )
end DisconnectedComponentRendererSpec
