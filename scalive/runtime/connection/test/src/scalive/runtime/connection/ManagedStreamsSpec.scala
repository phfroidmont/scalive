package scalive.runtime.connection

import zio.*
import zio.http.URL
import zio.test.*

import scalive.*
import scalive.render.*
import scalive.runtime.contracts.CommandId
import scalive.streams.*

object ManagedStreamsSpec extends ZIOSpecDefault:
  private val config = ConnectionConfig.make(8, 8, 8, 8, 8).toOption.get
  private val metadata = RootConnectionMetadata(staticChanged = true, connectParams = Map.empty)

  private final case class Item(id: String, label: String)
  private final case class RootModel(stream: LiveStream[Item], marker: String)

  private enum RootMessage:
    case Insert(item: Item, at: StreamAt = StreamAt.Last, updateOnly: Boolean = false)
    case Delete(id: String)
    case Reset(items: Vector[Item], at: StreamAt = StreamAt.Last)
    case InsertButKeepOld(item: Item)
    case InsertConcurrently(items: Vector[Item], finalItem: Item)
    case Unrelated
    case FailAfterInsert(
      item: Item,
      entered: Promise[Nothing, Unit],
      release: Promise[Nothing, Unit])

  private final class RootFixture(definition: LiveStreamDef[Item, String], initial: Vector[Item])
      extends LiveView[RootMessage, RootModel]:
    def mount(ctx: MountContext): Task[RootModel] =
      ctx.streams.create(definition, initial).map(RootModel(_, "mounted"))

    def handleMessage(model: RootModel, ctx: MessageContext): RootMessage => Task[RootModel] =
      case RootMessage.Insert(item, at, updateOnly) =>
        ctx.streams.insert(definition, item, at, updateOnly).map(RootModel(_, "inserted"))
      case RootMessage.Delete(id) =>
        ctx.streams.delete(definition, id).map(RootModel(_, "deleted"))
      case RootMessage.Reset(items, at) =>
        ctx.streams.reset(definition, items, at).map(RootModel(_, "reset"))
      case RootMessage.InsertButKeepOld(item) =>
        ctx.streams.insert(definition, item).as(model.copy(marker = "stale"))
      case RootMessage.InsertConcurrently(items, finalItem) =>
        ZIO.foreachParDiscard(items)(ctx.streams.insert(definition, _)) *>
          ctx.streams.insert(definition, finalItem).map(RootModel(_, "concurrent"))
      case RootMessage.Unrelated => ZIO.succeed(model.copy(marker = "unrelated"))
      case RootMessage.FailAfterInsert(item, entered, release) =>
        ctx.streams.insert(definition, item) *>
          entered.succeed(()).unit *> release.await *> ZIO.fail(Exception("rollback"))

    def view(model: Signal[RootModel]): HtmlElement[RootMessage] =
      div(
        dataAttr("marker") := model.map(_.marker),
        model.map(_.stream).renderIn(div)(item => span(item.map(_.label)))
      )

  override def spec = suite("ManagedStreamsSpec")(
    test("connected streams mount as snapshots and later operations stay stream-specific") {
      ZIO.scoped {
        val definition = LiveStreamDef.byId[Item, String]("items")(_.id)
        for
          outputs <- Queue.unbounded[ConnectionOutput]
          connection <- RootConnection.start(
                          config,
                          metadata,
                          RootFixture(definition, Vector(Item("a", "one"), Item("b", "two"))),
                          outputs.offer(_).unit
                        )
          joined <- outputs.take
          _      <- connection.submitInfo(RootMessage.Insert(Item("c", "three"), StreamAt.First))
          inserted <- outputs.take
          _         <- connection.submitInfo(RootMessage.Delete("b"))
          deleted   <- outputs.take
          _         <- connection.submitInfo(RootMessage.Reset(Vector(Item("d", "four"))))
          reset     <- outputs.take
        yield assertTrue(
          joined match
            case ConnectionOutput.Joined(RenderDelta.Replace(tree), _) =>
              HtmlRenderer.render(tree).contains(
                "<div id=\"items\" phx-update=\"stream\"><span id=\"items-a\">one</span><span id=\"items-b\">two</span></div>"
              )
            case _ => false,
          onlyStreamChanges(inserted).exists(_.operations.inserts.map(_.row.domId) == Vector("items-c")),
          onlyStreamChanges(deleted).exists(_.operations.deletes == Vector("items-b")),
          onlyStreamChanges(reset).exists(_.operations.reset),
          Seq(inserted, deleted, reset).forall(output => !changes(output).exists(_.isInstanceOf[RenderChange.Replace]))
        )
      }
    },
    test("placement limit and updateOnly metadata survive connection output") {
      ZIO.scoped {
        val definition = LiveStreamDef.byId[Item, String]("limited")(_.id).keepLast(2)
        for
          outputs <- Queue.unbounded[ConnectionOutput]
          connection <- RootConnection.start(
                          config,
                          metadata,
                          RootFixture(definition, Vector(Item("a", "one"), Item("b", "two"))),
                          outputs.offer(_).unit
                        )
          _ <- outputs.take
          _ <- connection.submitInfo(
                 RootMessage.Insert(Item("a", "updated"), StreamAt.Index(1), updateOnly = true)
               )
          output <- outputs.take
          insert = onlyStreamChanges(output).flatMap(_.operations.inserts).headOption
        yield assertTrue(
          insert.exists(_.at == StreamAt.Index(1)),
          insert.flatMap(_.limit).contains(StreamLimit.KeepLast(2)),
          insert.exists(_.updateOnly),
          insert.exists(_.row.domId == "limited-a")
        )
      }
    },
    test("a failed stream turn publishes neither its model nor its candidate render") {
      ZIO.scoped {
        val definition = LiveStreamDef.byId[Item, String]("rollback-items")(_.id)
        for
          entered <- Promise.make[Nothing, Unit]
          release <- Promise.make[Nothing, Unit]
          outputs <- Queue.unbounded[ConnectionOutput]
          connection <- RootConnection.start(
                          config,
                          metadata,
                          RootFixture(definition, Vector(Item("a", "committed"))),
                          outputs.offer(_).unit
                        )
          joined <- outputs.take
          initialHtml = fullHtml(joined)
          committedBefore <- connection.inspectModel
          failing <- connection
                       .submitInfo(
                         RootMessage.FailAfterInsert(Item("b", "uncommitted"), entered, release)
                       ).either.fork
          _ <- entered.await
          _      <- release.succeed(())
          failed <- failing.join
          _      <- connection.awaitClosed
          unpublished <- outputs.poll
        yield assertTrue(
          initialHtml.contains("committed"),
          !initialHtml.contains("uncommitted"),
          committedBefore.marker == "mounted",
          committedBefore.stream.entries.map(_.domId) == Vector("rollback-items-a"),
          failed.left.exists(_.isInstanceOf[ConnectionError.SessionFailed]),
          unpublished.isEmpty
        )
      }
    },
    test("an unrelated turn retains stream identity and permits a later operation") {
      ZIO.scoped {
        val definition = LiveStreamDef.byId[Item, String]("retained")(_.id)
        for
          outputs <- Queue.unbounded[ConnectionOutput]
          connection <- RootConnection.start(
                          config,
                          metadata,
                          RootFixture(definition, Vector(Item("a", "one"))),
                          outputs.offer(_).unit
                        )
          joined   <- outputs.take
          identity  = streamNodes(joined).head.identity
          _        <- connection.submitInfo(RootMessage.Unrelated)
          unrelated <- outputs.take
          _        <- connection.submitInfo(RootMessage.Insert(Item("b", "two")))
          inserted <- outputs.take
          change    = onlyStreamChanges(inserted).headOption
        yield assertTrue(
          onlyStreamChanges(unrelated).isEmpty,
          change.exists(_.identity eq identity),
          change.exists(_.operations.inserts.map(_.row.domId) == Vector("retained-b"))
        )
      }
    },
    test("rendering a stale stream handle fails before publishing inconsistent DOM state") {
      ZIO.scoped {
        val definition = LiveStreamDef.byId[Item, String]("stale")(_.id)
        for
          outputs <- Queue.unbounded[ConnectionOutput]
          connection <- RootConnection.start(
                          config,
                          metadata,
                          RootFixture(definition, Vector(Item("a", "one"))),
                          outputs.offer(_).unit
                        )
          _      <- outputs.take
          result <- connection.submitInfo(RootMessage.InsertButKeepOld(Item("b", "two"))).either
          closed <- connection.awaitClosed.timeout(1.second)
          output <- outputs.poll
        yield assertTrue(
          result.left.exists(_.isInstanceOf[ConnectionError.SessionFailed]),
          closed.nonEmpty,
          output.isEmpty
        )
      }
    },
    test("parallel stream operations retain every update and a coherent generation") {
      ZIO.scoped {
        val definition = LiveStreamDef.byId[Item, String]("parallel")(_.id)
        val concurrent = (1 to 32).map(index => Item(index.toString, s"item $index")).toVector
        val finalItem  = Item("final", "final")
        for
          connection <- RootConnection.start(
                          config,
                          metadata,
                          RootFixture(definition, Vector(Item("initial", "initial"))),
                          _ => ZIO.unit
                        )
          _ <- connection.submitInfo(RootMessage.InsertConcurrently(concurrent, finalItem))
          model <- connection.inspectModel
          ids    = model.stream.entries.map(_.domId).toSet
          expected = ("parallel-initial" +: concurrent.map(item => s"parallel-${item.id}") :+
                       "parallel-final").toSet
        yield assertTrue(ids == expected, model.stream.generation == 34L)
      }
    },
    test("a component cannot render a stream owned by its root") {
      ZIO.scoped {
        val definition = LiveStreamDef.byId[Item, String]("foreign")(_.id)
        val componentDefinition = new LiveComponent.Eventless[LiveStream[Item], Unit]:
          def mount(props: LiveStream[Item], ctx: MountContext) = ZIO.unit
          def view(
            props: Signal[LiveStream[Item]],
            model: Signal[Unit],
            self: ComponentRef[Nothing]
          ) = props.renderIn(div)(item => span(item.map(_.label)))
        val instance = component(componentDefinition, "foreign-stream-owner")
        val root = new LiveView[Unit, LiveStream[Item]]:
          def mount(ctx: MountContext) =
            ctx.streams.create(definition, Vector(Item("a", "one")))
          def handleMessage(model: LiveStream[Item], ctx: MessageContext)
            : Unit => Task[LiveStream[Item]] = _ => ZIO.succeed(model)
          def view(model: Signal[LiveStream[Item]]) = div(instance.render(model))
        for
          result <- RootConnection.start(config, metadata, root, _ => ZIO.unit).either
        yield assertTrue(result.left.exists(_.isInstanceOf[ConnectionError.SessionFailed]))
      }
    },
    test("root and component owners isolate equal names") {
      ZIO.scoped {
        val rootDef      = LiveStreamDef.byId[Item, String]("shared")(_.id)
        val componentDef = LiveStreamDef.byId[Item, String]("shared")(_.id)
        val definition = new LiveComponent[Unit, Item, LiveStream[Item]]:
          def mount(props: Unit, ctx: MountContext) =
            ctx.streams.create(componentDef, Vector(Item("component", "component")))
          def handleMessage(
            props: Unit,
            model: LiveStream[Item],
            ctx: MessageContext
          ): Item => Task[LiveStream[Item]] = item => ctx.streams.insert(componentDef, item)
          def view(
            props: Signal[Unit],
            model: Signal[LiveStream[Item]],
            self: ComponentRef[Item]
          ) = model.renderIn(div)(item => span(item.map(_.label)))
        val instance = component(definition, "shared-owner")
        val root = new LiveView[RootMessage, RootModel]:
          def mount(ctx: MountContext) =
            ctx.streams.create(rootDef, Vector(Item("root", "root"))).map(RootModel(_, "mounted"))
          def handleMessage(model: RootModel, ctx: MessageContext): RootMessage => Task[RootModel] =
            case RootMessage.Insert(item, at, updateOnly) =>
              ctx.streams.insert(rootDef, item, at, updateOnly).map(RootModel(_, "inserted"))
            case _ => ZIO.succeed(model)
          def view(model: Signal[RootModel]) =
            div(model.map(_.stream).renderIn(div)(item => span(item.map(_.label))), instance.render(()))
        for
          outputs    <- Queue.unbounded[ConnectionOutput]
          connection <- RootConnection.start(config, metadata, root, outputs.offer(_).unit)
          joined     <- outputs.take
          initial     = streamNodes(joined)
          componentId <- connection.inspectComponentIds.map(_.head)
          _           <- connection.submitInfo(RootMessage.Insert(Item("root-2", "root two")))
          rootOutput  <- outputs.take
          command      = CommandId.fresh().toOption.get
          _           <- connection.submitComponentMessage(command, componentId, Item("component-2", "component two"))
          componentOutput <- outputs.take
          rootChange = onlyStreamChanges(rootOutput).headOption
          componentChange = allStreamChanges(componentOutput).headOption
        yield assertTrue(
          initial.size == 2,
          !(initial(0).identity eq initial(1).identity),
          rootChange.exists(_.operations.inserts.exists(_.row.domId == "shared-root-2")),
          componentChange.exists(_.operations.inserts.exists(_.row.domId == "shared-component-2")),
          rootChange.zip(componentChange).forall((left, right) => !(left.identity eq right.identity))
        )
      }
    },
    test("a dormant component retains its stream state and identity when reintroduced") {
      ZIO.scoped {
        val definition = LiveStreamDef.byId[Item, String]("component-items")(_.id)
        for
          mounts <- Ref.make(0)
          componentDefinition = new LiveComponent.Eventless[Unit, LiveStream[Item]]:
                                  def mount(props: Unit, ctx: MountContext) =
                                    mounts.updateAndGet(_ + 1).flatMap(number =>
                                      ctx.streams.create(
                                        definition,
                                        Vector(Item(s"mount-$number", s"mount $number"))
                                      )
                                    )
                                  def view(
                                    props: Signal[Unit],
                                    model: Signal[LiveStream[Item]],
                                    self: ComponentRef[Nothing]
                                  ) = model.renderIn(div)(item => span(item.map(_.label)))
          instance = component(componentDefinition, "replaceable")
          root = new LiveView[Boolean, Boolean]:
                   def mount(ctx: MountContext) = ZIO.succeed(true)
                   def handleMessage(model: Boolean, ctx: MessageContext): Boolean => Task[Boolean] =
                     ZIO.succeed(_)
                   def view(model: Signal[Boolean]) =
                     div(model.when(div(instance.render(()))))
          outputs    <- Queue.unbounded[ConnectionOutput]
          connection <- RootConnection.start(config, metadata, root, outputs.offer(_).unit)
          joined     <- outputs.take
          firstIdentity = streamNodes(joined).head.identity
          firstId       <- connection.inspectComponentIds.map(_.head)
          _             <- connection.submitInfo(false)
          _             <- outputs.take
          _             <- connection.submitInfo(true)
          reintroduced  <- outputs.take
          secondId      <- connection.inspectComponentIds.map(_.head)
          secondStream   = streamNodes(reintroduced).head
          mountCount    <- mounts.get
        yield assertTrue(
          firstId == secondId,
          firstIdentity eq secondStream.identity,
          secondStream.rows.map(_.domId) == Vector("component-items-mount-1"),
          mountCount == 1
        )
      }
    },
    test("disconnected root and component streams render full independent HTML") {
      ZIO.scoped {
        val rootDef      = LiveStreamDef.byId[Item, String]("root-items")(_.id)
        val componentDef = LiveStreamDef.byId[Item, String]("component-items")(_.id)
        for
          rootMounts      <- Ref.make(0)
          componentMounts <- Ref.make(0)
          componentDefinition = new LiveComponent.Eventless[Unit, LiveStream[Item]]:
                                  def mount(props: Unit, ctx: MountContext) =
                                    componentMounts.updateAndGet(_ + 1).flatMap(number =>
                                      ctx.streams.create(
                                        componentDef,
                                        Vector(Item(s"c-$number", s"component $number"))
                                      )
                                    )
                                  def view(
                                    props: Signal[Unit],
                                    model: Signal[LiveStream[Item]],
                                    self: ComponentRef[Nothing]
                                  ) = model.renderIn(ul)(item => li(item.map(_.label)))
          instance = component(componentDefinition, "disconnected-stream")
          root = new LiveView[Unit, LiveStream[Item]]:
                   def mount(ctx: MountContext) =
                     rootMounts.updateAndGet(_ + 1).flatMap(number =>
                       ctx.streams.create(rootDef, Vector(Item(s"r-$number", s"root $number")))
                     )
                   def handleMessage(model: LiveStream[Item], ctx: MessageContext)
                     : Unit => Task[LiveStream[Item]] = _ => ZIO.succeed(model)
                   def view(model: Signal[LiveStream[Item]]) =
                     div(model.renderIn(ul)(item => li(item.map(_.label))), instance.render(()))
          turn <- DisconnectedRootTurn.make[Unit, LiveStream[Item]](
                    root.hooks,
                    URL.root,
                    Map.empty
                  )
          disconnectedModel <- root.mount(turn.mountContext)
          disconnectedHtml <- DisconnectedComponentRenderer.renderTurnWith(
                                root.view,
                                disconnectedModel,
                                turn
                              )((tree, _) => ZIO.succeed(HtmlRenderer.render(tree)))
          _       <- turn.runAfterRender(disconnectedModel)
          outputs <- Queue.unbounded[ConnectionOutput]
          _       <- RootConnection.start(config, metadata, root, outputs.offer(_).unit)
          joined  <- outputs.take
          connectedHtml = fullHtml(joined)
          rootCount      <- rootMounts.get
          componentCount <- componentMounts.get
        yield assertTrue(
          disconnectedHtml.contains("<ul id=\"root-items\" phx-update=\"stream\"><li id=\"root-items-r-1\">root 1</li></ul>"),
          disconnectedHtml.contains("<ul id=\"component-items\" phx-update=\"stream\"><li id=\"component-items-c-1\">component 1</li></ul>"),
          connectedHtml.contains("id=\"root-items-r-2\""),
          connectedHtml.contains("id=\"component-items-c-2\""),
          !connectedHtml.contains("root-items-r-1"),
          !connectedHtml.contains("component-items-c-1"),
          rootCount == 2,
          componentCount == 2
        )
      }
    }
  ) @@ TestAspect.timeout(10.seconds)

  private def delta(output: ConnectionOutput): RenderDelta = output match
    case ConnectionOutput.Joined(value, _)                => value
    case ConnectionOutput.Reply(_, value, _)              => value
    case ConnectionOutput.Diff(value, _)                  => value
    case ConnectionOutput.JoinedNavigation(value, _, _)   => value
    case ConnectionOutput.ReplyNavigation(_, value, _, _) => value
    case ConnectionOutput.DiffNavigation(value, _, _)     => value
    case other => throw AssertionError(s"output has no render delta: $other")

  private def changes(output: ConnectionOutput): Vector[RenderChange] = delta(output) match
    case RenderDelta.Update(_, values) => values
    case _                             => Vector.empty

  private def onlyStreamChanges(output: ConnectionOutput): Vector[RenderChange.Stream] =
    changes(output).collect { case value: RenderChange.Stream => value }

  private def allStreamChanges(output: ConnectionOutput): Vector[RenderChange.Stream] =
    def nested(change: RenderChange): Vector[RenderChange.Stream] = change match
      case value: RenderChange.Stream => Vector(value)
      case RenderChange.Component(_, RenderDelta.Update(_, values)) => values.flatMap(nested)
      case _ => Vector.empty
    changes(output).flatMap(nested)

  private def streamNodes(output: ConnectionOutput): Vector[EvaluatedNode.Stream] =
    def visit(node: EvaluatedNode): Vector[EvaluatedNode.Stream] = node match
      case value: EvaluatedNode.Stream    => Vector(value)
      case value: EvaluatedNode.Element   => value.children.flatMap(visit)
      case value: EvaluatedNode.Component =>
        value.resolution.toVector.flatMap(result => visit(result.child.root))
      case value: EvaluatedNode.Choice => value.child.toVector.flatMap(visit)
      case value: EvaluatedNode.Flash  => value.child.toVector.flatMap(visit)
      case value: EvaluatedNode.Keyed  => value.rows.flatMap(row => visit(row.child))
      case _                           => Vector.empty
    delta(output) match
      case RenderDelta.Replace(tree) => visit(tree.root)
      case RenderDelta.Update(_, values) =>
        values.flatMap {
          case RenderChange.Replace(_, node) => visit(node)
          case RenderChange.Component(_, RenderDelta.Replace(tree)) => visit(tree.root)
          case _ => Vector.empty
        }
      case RenderDelta.Empty => Vector.empty

  private def fullHtml(output: ConnectionOutput): String = delta(output) match
    case RenderDelta.Replace(tree) => HtmlRenderer.render(tree)
    case other                     => throw AssertionError(s"expected full render, got $other")
end ManagedStreamsSpec
