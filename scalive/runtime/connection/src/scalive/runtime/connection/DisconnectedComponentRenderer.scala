package scalive.runtime.connection

import zio.*

import scalive.*
import scalive.render.*
import scalive.runtime.contracts.ComponentInstanceId
import scalive.runtime.contracts.LifecycleId
import scalive.runtime.resources.OwnerId

private[scalive] trait DisconnectedNestedResolver:
  def resolve(requirement: NestedRequirement): Task[NestedResolution]

private[scalive] object DisconnectedNestedResolver:
  val unavailable: DisconnectedNestedResolver = new DisconnectedNestedResolver:
    def resolve(requirement: NestedRequirement): Task[NestedResolution] =
      ZIO.fail(
        IllegalStateException(
          "nested LiveViews are unavailable during disconnected component render"
        )
      )

/** One-shot disconnected rendering. The rendered tree is valid only for the supplied callback. */
private[scalive] object DisconnectedComponentRenderer:
  def renderWith[Input, Msg, A](
    view: Signal[Input] => HtmlElement[Msg],
    input: Input,
    flash: Map[FlashKind, String] = Map.empty
  )(
    consume: EvaluatedTree => Task[A]
  ): Task[A] =
    renderWithState(view, input, flash)((tree, _) => consume(tree))

  def renderWithState[Input, Msg, A](
    view: Signal[Input] => HtmlElement[Msg],
    input: Input,
    flash: Map[FlashKind, String] = Map.empty
  )(
    consume: (EvaluatedTree, Map[FlashKind, String]) => Task[A]
  ): Task[A] =
    for
      journal <- makeJournal(flash)
      result  <- renderWithJournal(view, input, journal)(consume)
    yield result

  def renderTurnWith[Input, Msg, A](
    view: Signal[Input] => HtmlElement[Msg],
    input: Input,
    turn: DisconnectedRootTurn[?, ?],
    nestedResolver: DisconnectedNestedResolver = DisconnectedNestedResolver.unavailable
  )(
    consume: (EvaluatedTree, Map[FlashKind, String]) => Task[A]
  ): Task[A] = renderWithJournal(view, input, turn.componentJournal, nestedResolver)(consume)

  private def renderWithJournal[Input, Msg, A](
    view: Signal[Input] => HtmlElement[Msg],
    input: Input,
    journal: RootTurnJournal,
    nestedResolver: DisconnectedNestedResolver = DisconnectedNestedResolver.unavailable
  )(
    consume: (EvaluatedTree, Map[FlashKind, String]) => Task[A]
  ): Task[A] =
    ZIO
      .fromEither(
        RenderProgram.compile[(Input, Map[FlashKind, String]), Msg](
          value => view(value.map(_._1)),
          _._2
        )
      ).flatMap { program =>
        renderJournaledProgramWith(program, input, journal, nestedResolver)(consume)
      }

  /** Takes ownership of `program` and closes it before this effect completes. */
  def renderProgramWith[Input, Msg, A](
    program: RenderProgram[Input, Msg],
    input: Input,
    flash: Map[FlashKind, String] = Map.empty,
    nestedResolver: DisconnectedNestedResolver = DisconnectedNestedResolver.unavailable
  )(
    consume: EvaluatedTree => Task[A]
  ): Task[A] =
    for
      journal <- makeJournal(flash)
      result  <-
        renderOwnedProgramWith(program, input, journal, nestedResolver)((tree, _) => consume(tree))
    yield result

  private def renderJournaledProgramWith[Input, Msg, A](
    program: RenderProgram[(Input, Map[FlashKind, String]), Msg],
    input: Input,
    journal: RootTurnJournal,
    nestedResolver: DisconnectedNestedResolver
  )(
    consume: (EvaluatedTree, Map[FlashKind, String]) => Task[A]
  ): Task[A] =
    ZIO.scoped {
      for
        environment  <- DisconnectedComponentEnvironment.make(journal, nestedResolver)
        ownedProgram <- ZIO.acquireRelease(ZIO.succeed(program))(_.close)
        stabilized   <- stabilize(ownedProgram, input, journal, environment, None, 0)
        (tree, finalFlash) = stabilized
        result <- consume(tree, finalFlash)
      yield result
    }

  private def makeJournal(flash: Map[FlashKind, String]): Task[RootTurnJournal] =
    for
      lifecycle <-
        ZIO
          .fromEither(LifecycleId.fresh()).mapError(error => IllegalStateException(error.toString))
      journal <- RootTurnJournal.make(
                   OwnerId.Root(lifecycle),
                   RootHookRegistry.fromStatic(LiveHooks.empty[Any, Any]),
                   flash
                 )
    yield journal

  private def stabilize[Input, Msg](
    program: RenderProgram[(Input, Map[FlashKind, String]), Msg],
    input: Input,
    journal: RootTurnJournal,
    environment: DisconnectedComponentEnvironment,
    previous: Option[(Vector[DisconnectedComponentIdentity], Map[FlashKind, String])],
    iteration: Int
  ): ZIO[Scope, Throwable, (EvaluatedTree, Map[FlashKind, String])] =
    if iteration >= 16 then
      ZIO.fail(
        IllegalStateException("disconnected component graph did not stabilize after 16 passes")
      )
    else
      for
        flash       <- journal.flash.get
        candidate   <- ZIO.acquireRelease(program.evaluate(input -> flash))(_.discard)
        _           <- environment.validateRequirements(candidate, journal.streams)
        resolutions <- environment.reconcile(candidate.componentRequirements)
        finalFlash  <- journal.flash.get
        identities = candidate.componentRequirements.map(requirement =>
                       DisconnectedComponentIdentity(
                         requirement.definition.asInstanceOf[AnyRef],
                         requirement.applicationId
                       )
                     )
        signature = identities -> finalFlash
        nestedResolved <- environment.resolveNested(candidate)
        resolved       <- ZIO.fromEither(nestedResolved.resolveComponents(resolutions))
        result <- if previous.contains(signature) then ZIO.succeed(resolved.tree -> finalFlash)
                  else
                    stabilize(program, input, journal, environment, Some(signature), iteration + 1)
      yield result

  private def renderOwnedProgramWith[Input, Msg, A](
    program: RenderProgram[Input, Msg],
    input: Input,
    journal: RootTurnJournal,
    nestedResolver: DisconnectedNestedResolver
  )(
    consume: (EvaluatedTree, Map[FlashKind, String]) => Task[A]
  ): Task[A] =
    ZIO.scoped {
      for
        environment    <- DisconnectedComponentEnvironment.make(journal, nestedResolver)
        ownedProgram   <- ZIO.acquireRelease(ZIO.succeed(program))(_.close)
        candidate      <- ZIO.acquireRelease(ownedProgram.evaluate(input))(_.discard)
        _              <- environment.validateRequirements(candidate, journal.streams)
        resolutions    <- environment.reconcile(candidate.componentRequirements)
        nestedResolved <- environment.resolveNested(candidate)
        resolved       <- ZIO.fromEither(nestedResolved.resolveComponents(resolutions))
        finalFlash     <- journal.flash.get
        result         <- consume(resolved.tree, finalFlash)
      yield result
    }
end DisconnectedComponentRenderer

final private class DisconnectedComponentEnvironment private (
  root: RootTurnJournal,
  nestedResolver: DisconnectedNestedResolver,
  identities: Ref[Set[DisconnectedComponentIdentity]],
  nestedApplicationIds: Ref[Set[String]],
  nestedCache: Ref[Map[String, NestedResolution]],
  cache: Ref[Map[DisconnectedComponentIdentity, DisconnectedCachedComponent]]):

  def validateRequirements(
    candidate: RenderCandidate[?],
    streams: Ref[StreamStore]
  ): Task[Unit] =
    streams.get.flatMap(store => ZIO.attempt(store.validate(candidate.streamRequirements)))

  def resolveNested[Msg](candidate: RenderCandidate[Msg]): Task[RenderCandidate[Msg]] =
    claimNested(candidate.nestedRequirements) *>
      ZIO
        .foreach(candidate.nestedRequirements)(resolveNestedRequirement)
        .flatMap(resolutions => ZIO.fromEither(candidate.resolveNested(resolutions)))

  private def resolveNestedRequirement(requirement: NestedRequirement): Task[NestedResolution] =
    nestedCache.get.flatMap(_.get(requirement.applicationId) match
      case Some(cached) =>
        ZIO.succeed(
          requirement.resolve(
            cached.instanceToken,
            cached.parentDomId,
            cached.topic,
            cached.joinCredential,
            cached.staticCredential,
            cached.loading,
            cached.child
          )
        )
      case None =>
        nestedResolver
          .resolve(requirement).tap(resolution =>
            nestedCache.update(_.updated(requirement.applicationId, resolution))
          ))

  def reconcile[Owner](
    requirements: Vector[ComponentRequirement[Owner]]
  ): ZIO[Scope, Throwable, Vector[ComponentResolution]] =
    identities.set(Set.empty) *> nestedApplicationIds.set(Set.empty) *> stageRequirements(
      requirements
    )

  private def claimNested(requirements: Vector[NestedRequirement]): Task[Unit] =
    nestedApplicationIds
      .modify { current =>
        requirements.foldLeft(Option.empty[String] -> current) {
          case (found @ (Some(_), _), _)          => found
          case ((None, accumulated), requirement) =>
            if accumulated.contains(requirement.applicationId) then
              Some(requirement.applicationId) -> current
            else None                         -> (accumulated + requirement.applicationId)
        }
      }.flatMap {
        case Some(applicationId) =>
          ZIO.fail(IllegalArgumentException(s"Duplicate nested LiveView id '$applicationId'"))
        case None => ZIO.unit
      }

  private def stageRequirements[Owner](requirements: Vector[ComponentRequirement[Owner]]) =
    claim(requirements) *> ZIO.foreach(requirements)(stageComponent)

  private def claim[Owner](requirements: Vector[ComponentRequirement[Owner]]): Task[Unit] =
    val requested = requirements.map(requirement =>
      DisconnectedComponentIdentity(
        requirement.definition.asInstanceOf[AnyRef],
        requirement.applicationId
      )
    )
    identities
      .modify { current =>
        requested.foldLeft(Option.empty[String] -> current) {
          case (found @ (Some(_), _), _)       => found
          case ((None, accumulated), identity) =>
            if accumulated.contains(identity) then Some(identity.applicationId) -> current
            else None -> (accumulated + identity)
        }
      }.flatMap {
        case Some(applicationId) =>
          ZIO.fail(IllegalArgumentException(s"duplicate component identity '$applicationId'"))
        case None => ZIO.unit
      }

  private def stageComponent[Owner](
    requirement: ComponentRequirement[Owner]
  ): ZIO[Scope, Throwable, ComponentResolution] =
    type P = requirement.Props
    type M = requirement.Message
    type A = requirement.Model

    val component = requirement.definition
    val identity  =
      DisconnectedComponentIdentity(component.asInstanceOf[AnyRef], requirement.applicationId)
    cache.get.map(_.get(identity)).flatMap {
      case Some(erased) =>
        val cached = erased.asInstanceOf[DisconnectedCachedComponent.Value[P, M, A]]
        if cached.props == requirement.props then
          for
            children       <- stageRequirements(cached.candidate.componentRequirements)
            nestedResolved <- resolveNested(cached.candidate)
            resolved       <- ZIO.fromEither(nestedResolved.resolveComponents(children))
          yield requirement.resolve(cached.ref, cached.ref.asInstanceOf[Object], resolved)
        else
          for
            updated <- ZIO.suspend(
                         component.update(
                           requirement.props,
                           cached.model,
                           DisconnectedComponentUpdateContext[P, M, A](cached.lifecycle)
                         )
                       )
            flash     <- root.flash.get
            candidate <- ZIO.acquireRelease(
                           cached.program.evaluate((requirement.props, updated, flash))
                         )(_.discard)
            _              <- validateRequirements(candidate, cached.lifecycle.streams)
            children       <- stageRequirements(candidate.componentRequirements)
            nestedResolved <- resolveNested(candidate)
            resolved       <- ZIO.fromEither(nestedResolved.resolveComponents(children))
            hooks          <- cached.lifecycle.registry
            context = DisconnectedComponentAfterRenderContext[P, M, A](cached.lifecycle)
            _ <- ZIO.foreachDiscard(hooks.afterRender)(
                   _.invoke(requirement.props, updated, context)
                 )
            _ <- cached.candidate.discard
            next = cached.copy(props = requirement.props, model = updated, candidate = resolved)
            _ <- cache.update(_ + (identity -> next))
          yield requirement.resolve(cached.ref, cached.ref.asInstanceOf[Object], resolved)
        end if
      case None =>
        for
          lifecycle <- DisconnectedComponentLifecycle.make[P, M, A](component.hooks, root)
          mounted   <- ZIO.suspend(
                       component.mount(
                         requirement.props,
                         DisconnectedComponentMountContext[P, M, A](lifecycle)
                       )
                     )
          updated <- ZIO.suspend(
                       component.update(
                         requirement.props,
                         mounted,
                         DisconnectedComponentUpdateContext[P, M, A](lifecycle)
                       )
                     )
          ref = ComponentRef.runtime[M](new Object())
          program <- ZIO.acquireRelease(
                       ZIO.fromEither(
                         RenderProgram.compile[(P, A, Map[FlashKind, String]), M](
                           input => component.view(input.map(_._1), input.map(_._2), ref),
                           _._3
                         )
                       )
                     )(_.close)
          flash     <- root.flash.get
          candidate <- ZIO.acquireRelease(
                         program.evaluate((requirement.props, updated, flash))
                       )(_.discard)
          _              <- validateRequirements(candidate, lifecycle.streams)
          children       <- stageRequirements(candidate.componentRequirements)
          nestedResolved <- resolveNested(candidate)
          resolved       <- ZIO.fromEither(nestedResolved.resolveComponents(children))
          hooks          <- lifecycle.registry
          context = DisconnectedComponentAfterRenderContext[P, M, A](lifecycle)
          _ <- ZIO.foreachDiscard(hooks.afterRender)(
                 _.invoke(requirement.props, updated, context)
               )
          resolution = requirement.resolve(ref, ref.asInstanceOf[Object], resolved)
          _ <- cache.update(
                 _ + (identity -> DisconnectedCachedComponent.Value(
                   requirement.props,
                   updated,
                   program,
                   ref,
                   lifecycle,
                   resolved
                 ))
               )
        yield resolution
    }
  end stageComponent
end DisconnectedComponentEnvironment

private object DisconnectedComponentEnvironment:
  def make(
    root: RootTurnJournal,
    nestedResolver: DisconnectedNestedResolver
  ): UIO[DisconnectedComponentEnvironment] =
    for
      identities           <- Ref.make(Set.empty[DisconnectedComponentIdentity])
      nestedApplicationIds <- Ref.make(Set.empty[String])
      nestedCache          <- Ref.make(Map.empty[String, NestedResolution])
      cache <- Ref.make(Map.empty[DisconnectedComponentIdentity, DisconnectedCachedComponent])
    yield DisconnectedComponentEnvironment(
      root,
      nestedResolver,
      identities,
      nestedApplicationIds,
      nestedCache,
      cache
    )

final private class DisconnectedComponentIdentity(
  val definition: AnyRef,
  val applicationId: String):
  override def equals(other: Any): Boolean = other match
    case value: DisconnectedComponentIdentity =>
      (definition eq value.definition) && applicationId == value.applicationId
    case _ => false
  override def hashCode(): Int =
    31 * java.lang.System.identityHashCode(definition) + applicationId.hashCode

private object DisconnectedComponentIdentity:
  def apply(definition: AnyRef, applicationId: String): DisconnectedComponentIdentity =
    new DisconnectedComponentIdentity(definition, applicationId)

sealed private trait DisconnectedCachedComponent

private object DisconnectedCachedComponent:
  final case class Value[P, M, A](
    props: P,
    model: A,
    program: RenderProgram[(P, A, Map[FlashKind, String]), M],
    ref: ComponentRef[M],
    lifecycle: DisconnectedComponentLifecycle[P, M, A],
    candidate: RenderCandidate[M])
      extends DisconnectedCachedComponent

final private class DisconnectedComponentLifecycle[P, M, A] private (
  val root: RootTurnJournal,
  val streams: Ref[StreamStore],
  hooks: Ref[ComponentHookRegistry[Any, Any, Any]])
    extends ComponentHookJournal:
  def registry: UIO[ComponentHookRegistry[P, M, A]] =
    hooks.get.map(_.asInstanceOf[ComponentHookRegistry[P, M, A]])

  def updateHooks[P0, M0, A0](
    f: ComponentHookRegistry[P0, M0, A0] => ComponentHookRegistry[P0, M0, A0]
  ): UIO[Unit] = hooks.update(current =>
    f(current.asInstanceOf[ComponentHookRegistry[P0, M0, A0]])
      .asInstanceOf[ComponentHookRegistry[Any, Any, Any]]
  )

private object DisconnectedComponentLifecycle:
  def make[P, M, A](
    hooks: ComponentLiveHooks[P, M, A],
    root: RootTurnJournal
  ): Task[DisconnectedComponentLifecycle[P, M, A]] =
    for
      id <- ZIO
              .fromEither(ComponentInstanceId.fresh())
              .mapError(error => IllegalStateException(error.toString))
      streams  <- Ref.make(StreamStore.empty)
      registry <- Ref.make(
                    ComponentHookRegistry
                      .fromStatic(hooks).asInstanceOf[ComponentHookRegistry[Any, Any, Any]]
                  )
      componentRoot = root.owner match
                        case OwnerId.Root(lifecycle) =>
                          root.scoped(OwnerId.Component(lifecycle, id))
                        case OwnerId.Component(lifecycle, _) =>
                          root.scoped(OwnerId.Component(lifecycle, id))
    yield DisconnectedComponentLifecycle(componentRoot, streams, registry)

final private case class DisconnectedComponentMountContext[P, M, A](
  lifecycle: DisconnectedComponentLifecycle[P, M, A])
    extends ComponentMountContext[P, M, A]:
  val connection = Connection.Disconnected
  val flash      = JournaledFlash(lifecycle.root)
  val uploads    = JournaledUploads(lifecycle.root)
  val streams    = JournaledStreams(lifecycle.streams, applyLimits = false)
  val hooks      = JournaledComponentHooks[P, M, A](lifecycle)

final private case class DisconnectedComponentUpdateContext[P, M, A](
  lifecycle: DisconnectedComponentLifecycle[P, M, A])
    extends ComponentUpdateContext[P, M, A]:
  val connection = Connection.Disconnected
  val flash      = JournaledFlash(lifecycle.root)
  val uploads    = JournaledUploads(lifecycle.root)
  val streams    = JournaledStreams(lifecycle.streams, applyLimits = false)
  val hooks      = JournaledComponentHooks[P, M, A](lifecycle)

final private case class DisconnectedComponentAfterRenderContext[P, M, A](
  lifecycle: DisconnectedComponentLifecycle[P, M, A])
    extends ComponentAfterRenderContext[P, M, A]:
  val connection = Connection.Disconnected
  val hooks      = JournaledComponentHooks[P, M, A](lifecycle)
