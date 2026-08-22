package scalive.runtime.connection

import zio.*
import zio.http.URL
import zio.json.ast.Json
import zio.test.*

import scalive.*
import scalive.render.*
import scalive.runtime.contracts.*
import scalive.runtime.resources.*
import scalive.upload.*

object UploadContextSpec extends ZIOSpecDefault:
  private val config   = ConnectionConfig.make(8, 8, 8, 8, 8).toOption.get
  private val metadata = RootConnectionMetadata(staticChanged = false, connectParams = Map.empty)

  private def definition(name: String = "files") =
    LiveUploadDef.inMemory(name, LiveUploadAccept.Any)

  private def renderedText(node: EvaluatedNode): String = node match
    case value: EvaluatedNode.Text      => value.value
    case value: EvaluatedNode.Element   => value.children.map(renderedText).mkString
    case value: EvaluatedNode.Component =>
      value.resolution.map(result => renderedText(result.child.root)).getOrElse("")
    case value: EvaluatedNode.Choice => value.child.map(renderedText).getOrElse("")
    case value: EvaluatedNode.Flash  => value.child.map(renderedText).getOrElse("")
    case value: EvaluatedNode.Keyed  => value.rows.map(row => renderedText(row.child)).mkString
    case value: EvaluatedNode.Stream => value.rows.map(row => renderedText(row.child)).mkString
    case value: EvaluatedNode.Nested =>
      value.resolution.flatMap(_.child).map(tree => renderedText(tree.root)).getOrElse("")

  override def spec = suite("UploadContextSpec")(
    test("connected root mount stores and renders an upload that survives an unrelated turn") {
      ZIO.scoped {
        val uploadDef = definition()
        val view      = new LiveView[Unit, LiveUpload[Chunk[Byte]]]:
          def mount(ctx: MountContext) = ctx.uploads.allow(uploadDef)
          def handleMessage(
            model: LiveUpload[Chunk[Byte]],
            ctx: MessageContext
          ): Unit => Task[LiveUpload[Chunk[Byte]]] =
            _ => ctx.uploads.get(uploadDef).someOrFail(Exception("upload disappeared"))
          def view(model: Signal[LiveUpload[Chunk[Byte]]]) =
            div(model.map(_.ref.value))

        for
          outputs    <- Queue.unbounded[ConnectionOutput]
          connection <- RootConnection.start(config, metadata, view, outputs.offer(_).unit)
          joined     <- outputs.take
          mounted    <- connection.inspectModel
          _          <- connection.submitInfo(())
          refreshed  <- connection.inspectModel
          tree       <- connection.inspectTree
        yield assertTrue(
          mounted.entries.isEmpty,
          refreshed.entries.isEmpty,
          refreshed.ref == mounted.ref,
          joined.isInstanceOf[ConnectionOutput.Joined],
          renderedText(tree.root) == mounted.ref.value
        )
      }
    },
    test("progress callback failure is reported after the updated state commits") {
      ZIO.scoped {
        val callbackFailure = Exception("progress callback failed")
        val uploader = new LiveUploadExternalUploader[String]:
          def preflight(client: UploadClientMetadata) =
            ZIO.succeed(
              LiveExternalUploadResult.Ready(
                ExternalUploadClientConfig(Json.Obj("uploader" -> Json.Str("test"))),
                client.fileName
              )
            )
        val uploadDef = LiveUploadDef.external(
          "files",
          LiveUploadAccept.Any,
          uploader,
          progress = Some(new LiveUploadProgress[String]:
            def onProgress(entry: LiveUploadEntry[String]) = ZIO.fail(callbackFailure))
        )
        val view = new LiveView.Eventless[LiveUpload[String]]:
          def mount(ctx: MountContext)                = ctx.uploads.allow(uploadDef)
          def view(model: Signal[LiveUpload[String]]) = div()

        for
          outputs    <- Queue.unbounded[ConnectionOutput]
          connection <- RootConnection.start(config, metadata, view, outputs.offer(_).unit)
          mounted    <- connection.inspectModel
          client = new UploadClientMetadata("a.txt", None, 1L, "text/plain", None, None)
          preflightCommand <- ZIO.fromEither(CommandId.fresh())
          preflight <- connection.preflightUpload(
                         preflightCommand,
                         None,
                         mounted.ref,
                         Vector((UploadEntryRef("entry"), client))
                       )
          progressCommand <- ZIO.fromEither(CommandId.fresh())
          progress <- connection
                        .progressUpload(
                          progressCommand,
                          None,
                          mounted.ref,
                          UploadEntryRef("entry"),
                          50
                        ).either
          regressingCommand <- ZIO.fromEither(CommandId.fresh())
          regressing <- connection.progressUpload(
                          regressingCommand,
                          None,
                          mounted.ref,
                          UploadEntryRef("entry"),
                          40
                        )
        yield assertTrue(
          preflight.isRight,
          progress == Left(ConnectionError.UploadFailed(callbackFailure)),
          regressing == Left(UploadRegistryError.InvalidProgress(UploadEntryRef("entry"), 50, 40))
        )
      }
    },
    test("failed upload candidates neither publish registry changes nor execute commit retirement") {
      for
        lifecycle <- ZIO.fromEither(LifecycleId.fresh())
        owner = OwnerId.Root(lifecycle)
        discarded <- Ref.make(0)
        uploader = new LiveUploadExternalUploader[String]:
                     def preflight(client: UploadClientMetadata) =
                       ZIO.succeed(
                         LiveExternalUploadResult.Ready(
                           ExternalUploadClientConfig(Json.Obj("uploader" -> Json.Str("test"))),
                           "reserved"
                         )
                       )
                     override def discard(result: String) = discarded.update(_ + 1)
        uploadDef = LiveUploadDef.external("files", LiveUploadAccept.Any, uploader)
        key       = UploadKey(uploadDef)
        allowed   =
          UploadRegistry.empty.allow(owner, Epoch.initial, key, UploadRef("committed")).toOption.get
        client    = new UploadClientMetadata("a.txt", None, 1L, "text/plain", None, None)
        preflight = allowed._1
                      .preflight(allowed._2, Vector((UploadEntryRef("entry"), client))).toOption.get
        prepared <- preflight.externalPreparations.head.operation.run
        installed = preflight.registry.installExternal(prepared.toOption.get)
        committed = installed.registry
        journal <- RootTurnJournal.make(
                     owner,
                     RootHookRegistry.fromStatic(LiveHooks.empty[Unit, Unit]),
                     initialUploads = committed
                   )
        context = RootMessageContext[Unit, Unit](metadata, URL.root, journal)
        failedDisallow <-
          (context.uploads.disallow(uploadDef) *> ZIO.fail(Exception("handler failed"))).either
        candidateAfterDisallow <- journal.uploads.get
        commitPlan             <- journal.uploadCommit.get
        beforeRetirement       <- discarded.get
        allowJournal           <- RootTurnJournal.make(
                          owner,
                          RootHookRegistry.fromStatic(LiveHooks.empty[Unit, Unit])
                        )
        allowContext = RootMessageContext[Unit, Unit](metadata, URL.root, allowJournal)
        failedAllow <-
          (allowContext.uploads.allow(uploadDef) *> ZIO.fail(Exception("handler failed"))).either
        candidateAfterAllow <- allowJournal.uploads.get
        committedUpload = committed.get(owner, Epoch.initial, key).toOption.map(_._2)
      yield assertTrue(
        failedDisallow.isLeft,
        failedAllow.isLeft,
        committedUpload.exists(_.ref == UploadRef("committed")),
        candidateAfterDisallow.get(owner, Epoch.initial, key).isLeft,
        candidateAfterAllow.get(owner, Epoch.initial, key).isRight,
        commitPlan.instructions.nonEmpty,
        beforeRetirement == 0
      )
    },
    test("disconnected root uploads are renderable, empty, and do not transfer identity") {
      ZIO.scoped {
        val uploadDef = definition()
        for
          turn <- DisconnectedRootTurn.make[Unit, Unit](LiveHooks.empty, URL.root, Map.empty)
          disconnected <- turn.mountContext.uploads.allow(uploadDef)
          rendered     <- DisconnectedComponentRenderer.renderTurnWith[Unit, Nothing, String](
                        _ => div(disconnected.ref.value),
                        (),
                        turn
                      )((tree, _) => ZIO.succeed(renderedText(tree.root)))
          connectedRef <- Ref.make(Option.empty[UploadRef])
          root = new LiveView.Eventless[Unit]:
                   def mount(ctx: MountContext) =
                     ctx.uploads
                       .allow(uploadDef).flatMap(upload => connectedRef.set(Some(upload.ref)))
                   def view(model: Signal[Unit]) = div()
          outputs    <- Queue.unbounded[ConnectionOutput]
          connection <- RootConnection.start(config, metadata, root, outputs.offer(_).unit)
          _          <- outputs.take
          connected  <- connectedRef.get
        yield assertTrue(
          disconnected.entries.isEmpty,
          rendered == disconnected.ref.value,
          connected.exists(_ != disconnected.ref),
          connection ne null
        )
      }
    },
    test(
      "root and component uploads with the same name are isolated and component removal retires its owner"
    ) {
      ZIO.scoped {
        val uploadDef = definition("shared")
        for
          componentRefs <- Ref.make(Vector.empty[UploadRef])
          rootRefs      <- Ref.make(Vector.empty[UploadRef])
          componentDef = new LiveComponent.Eventless[Unit, LiveUpload[Chunk[Byte]]]:
                           def mount(props: Unit, ctx: MountContext) =
                             ctx.uploads
                               .allow(uploadDef).tap(upload =>
                                 componentRefs.update(_ :+ upload.ref)
                               )
                           def view(
                             props: Signal[Unit],
                             model: Signal[LiveUpload[Chunk[Byte]]],
                             self: ComponentRef[Nothing]
                           ) = span(model.map(_.ref.value))
          instance = component(componentDef, "uploader")
          root     = new LiveView[Boolean, (Boolean, LiveUpload[Chunk[Byte]])]:
                   def mount(ctx: MountContext) =
                     ctx.uploads
                       .allow(uploadDef).tap(upload => rootRefs.update(_ :+ upload.ref)).map(
                         true -> _
                       )
                   def handleMessage(
                     model: (Boolean, LiveUpload[Chunk[Byte]]),
                     ctx: MessageContext
                   ): Boolean => Task[(Boolean, LiveUpload[Chunk[Byte]])] = shown =>
                     ctx.uploads
                       .get(uploadDef).someOrFail(Exception("root upload disappeared")).map(
                         shown -> _
                       )
                   def view(model: Signal[(Boolean, LiveUpload[Chunk[Byte]])]) =
                     div(
                       model.map(_._2.ref.value),
                       model.map(_._1).when(div(instance.render(())))
                     )
          outputs    <- Queue.unbounded[ConnectionOutput]
          connection <- RootConnection.start(config, metadata, root, outputs.offer(_).unit)
          _          <- outputs.take
          _          <- connection.submitInfo(false)
          _          <- connection.submitInfo(true)
          roots      <- rootRefs.get
          components <- componentRefs.get
          current    <- connection.inspectModel
        yield assertTrue(
          roots.size == 1,
          components.size == 2,
          roots.head != components.head,
          components.head != components(1),
          current._2.ref == roots.head
        )
        end for
      }
    },
    test("disconnected sibling components may allow the same upload name") {
      val uploadDef = definition("shared")
      for
        refs <- Ref.make(Vector.empty[UploadRef])
        componentDef = new LiveComponent.Eventless[String, LiveUpload[Chunk[Byte]]]:
                         def mount(props: String, ctx: MountContext) =
                           ctx.uploads.allow(uploadDef).tap(upload => refs.update(_ :+ upload.ref))
                         def view(
                           props: Signal[String],
                           model: Signal[LiveUpload[Chunk[Byte]]],
                           self: ComponentRef[Nothing]
                         ) = span(model.map(_.ref.value))
        first  = component(componentDef, "first")
        second = component(componentDef, "second")
        rendered <- DisconnectedComponentRenderer.renderWith[Unit, Nothing, String](
                      _ => div(first.render("a"), second.render("b")),
                      ()
                    )(tree => ZIO.succeed(renderedText(tree.root)))
        observed <- refs.get
      yield assertTrue(
        observed.size == 2,
        observed.head != observed(1),
        rendered == observed.map(_.value).mkString
      )
    },
    test("cancel and consume expose documented operation errors") {
      val uploadDef = definition()
      for
        lifecycle <- ZIO.fromEither(LifecycleId.fresh())
        owner   = OwnerId.Root(lifecycle)
        key     = UploadKey(uploadDef)
        allowed =
          UploadRegistry.empty.allow(owner, Epoch.initial, key, UploadRef("upload")).toOption.get
        client   = new UploadClientMetadata("a.txt", None, 1L, "text/plain", None, None)
        selected =
          allowed._1
            .preflight(allowed._2, Vector((UploadEntryRef("entry"), client))).toOption.get.registry
        entry = selected.get(owner, Epoch.initial, key).toOption.get._2.entries.head
        journal <- RootTurnJournal.make(
                     owner,
                     RootHookRegistry.fromStatic(LiveHooks.empty[Unit, Unit]),
                     initialUploads = selected
                   )
        uploads  = JournaledUploads(journal)
        inactive = new LiveUploadEntry(
                     UploadEntryRef("inactive"),
                     client,
                     LiveUploadEntryStatus.Selected,
                     None,
                     uploadDef.name
                   )
        cancelInactive  <- uploads.cancel(inactive).either
        consumeInactive <-
          uploads.consume(inactive)(_ => ZIO.succeed(ConsumeDecision.Consume(()))).either
        consumePending <-
          uploads.consume(entry)(_ => ZIO.succeed(ConsumeDecision.Consume(()))).either
        consumeAll <-
          uploads.consumeCompleted(uploadDef)(_ => ZIO.succeed(ConsumeDecision.Consume(()))).either
        emptyJournal <- RootTurnJournal.make(
                          owner,
                          RootHookRegistry.fromStatic(LiveHooks.empty[Unit, Unit])
                        )
        notAllowed <- JournaledUploads(emptyJournal).cancel(entry).either
      yield assertTrue(
        cancelInactive.left.exists(_.isInstanceOf[LiveUploadOperationError.EntryNotActive]),
        consumeInactive.left.exists(_.isInstanceOf[LiveUploadOperationError.EntryNotActive]),
        consumePending.left.exists(_.isInstanceOf[LiveUploadOperationError.EntryNotCompleted]),
        consumeAll.left.exists(_.isInstanceOf[LiveUploadOperationError.EntriesInProgress]),
        notAllowed.left.exists(_.isInstanceOf[LiveUploadOperationError.NotAllowed])
      )
      end for
    }
  )
end UploadContextSpec
