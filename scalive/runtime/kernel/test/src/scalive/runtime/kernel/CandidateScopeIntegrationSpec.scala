package scalive.runtime.kernel

import zio.ZIO
import zio.test.*

import scalive.*
import scalive.render.*

object CandidateScopeIntegrationSpec extends ZIOSpecDefault:
  override def spec = suite("CandidateScopeIntegrationSpec")(
    test("lets the runtime attach and close candidate-owned resources") {
      var finalized = false

      for
        program <- ZIO.fromEither(
          RenderProgram.compile[Int, Nothing](model => div(model.map(_.toString)))
        )
        scope <- CandidateScope.make
        _     <- scope.addFinalizer(ZIO.succeed { finalized = true })
        candidate <- program.evaluateIn(1, None, scope)
        committed = candidate.commit
        _ <- committed.scope.closeFromOwner
      yield assertTrue(committed.scope.isClosed, finalized)
    },
    test("rejects owner-closed and retained scope reuse") {
      for
        program <- ZIO.fromEither(
          RenderProgram.compile[Int, Nothing](model => div(model.map(_.toString)))
        )
        closedScope <- CandidateScope.make
        _           <- closedScope.closeFromOwner
        closedResult <- program.evaluateIn(1, None, closedScope).exit
        retainedScope <- CandidateScope.make
        candidate     <- program.evaluateIn(1, None, retainedScope)
        committed = candidate.commit
        retainedResult <- program.evaluateIn(2, None, retainedScope).exit
        retainedOpen = !committed.scope.isClosed
        _ <- committed.close
      yield assertTrue(
        closedResult.isFailure,
        retainedResult.isFailure,
        retainedOpen
      )
    },
    test("owner close wins an in-progress evaluation") {
      for
        scope <- CandidateScope.make
        began = scope.beginEvaluation()
        _     <- scope.closeFromOwner
        completed = scope.completeEvaluation()
      yield assertTrue(began.isRight, completed.isLeft, scope.isClosed)
    },
    test("failed scope reuse cannot close the active candidate") {
      for
        program <- ZIO.fromEither(
          RenderProgram.compile[Int, Nothing](model => div(model.map(_.toString)))
        )
        readyScope <- CandidateScope.make
        candidate  <- program.evaluateIn(1, None, readyScope)
        readyReuse <- program.evaluateIn(2, None, readyScope).exit
        committed = candidate.commit
        evaluatingScope <- CandidateScope.make
        began = evaluatingScope.beginEvaluation()
        evaluatingReuse <- program.evaluateIn(3, None, evaluatingScope).exit
        completed = evaluatingScope.completeEvaluation()
        _ <- evaluatingScope.closeFromOwner
        _ <- committed.close
      yield assertTrue(
        readyReuse.isFailure,
        evaluatingReuse.isFailure,
        began.isRight,
        completed.isRight
      )
    },
    test("claims the scope only when each evaluation effect runs") {
      for
        program <- ZIO.fromEither(
          RenderProgram.compile[Int, Nothing](model => div(model.map(_.toString)))
        )
        unusedScope <- CandidateScope.make
        unusedEffect = program.evaluateIn(1, None, unusedScope)
        constructed = unusedEffect != null
        unusedClaim = unusedScope.beginEvaluation()
        _ <- unusedScope.closeFromOwner
        repeatedScope <- CandidateScope.make
        repeatedEffect = program.evaluateIn(2, None, repeatedScope)
        first  <- repeatedEffect
        second <- repeatedEffect.exit
        committed = first.commit
        remainedOpen = !committed.scope.isClosed
        _ <- committed.close
      yield assertTrue(constructed, unusedClaim.isRight, second.isFailure, remainedOpen)
    },
    test("validates program and committed scope lifetimes when the effect runs") {
      for
        closedProgram <- ZIO.fromEither(
          RenderProgram.compile[Int, Nothing](model => div(model.map(_.toString)))
        )
        programScope <- CandidateScope.make
        programEffect = closedProgram.evaluateIn(1, None, programScope)
        _             <- closedProgram.close
        programResult <- programEffect.exit
        activeProgram <- ZIO.fromEither(
          RenderProgram.compile[Int, Nothing](model => div(model.map(_.toString)))
        )
        initial   <- activeProgram.evaluate(1)
        committed = initial.commit
        candidateScope <- CandidateScope.make
        candidateEffect = activeProgram.evaluateIn(2, Some(committed), candidateScope)
        _               <- committed.close
        candidateResult <- candidateEffect.exit
      yield assertTrue(programResult.isFailure, candidateResult.isFailure)
    }
  )
