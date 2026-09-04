package scalive.docs.examples

import org.jsoup.Jsoup
import zio.*
import zio.test.*

import scalive.*
import scalive.testing.{ConnectedRender, ConnectedView}

object FormWorkflowExampleSpec extends ZIOSpecDefault:
  private val example = FormWorkflowExample
  private val validData = Vector(example.Draft.Title.name -> "Release notes")

  private def document(harness: ConnectedView[?]) =
    harness.html.map(Jsoup.parseBodyFragment)

  override def spec = suite("FormWorkflowExampleSpec")(
    test("does not claim that unchanged values advanced the revision") {
      val initial = example.Model.initial
      val event = example.Draft.Definition.event(FormData.empty, FormEventKind.Recovered)
      val next  = example.update(initial, example.Msg.Validate(event))

      assertTrue(
        next.workflow.revision == initial.workflow.revision,
        next.notice == example.Notice.InteractionRecorded
      )
    },
    test("renders invalid and overlapping starts without exposing submitted values in status") {
      ZIO.scoped {
        for
          harness <- ConnectedRender.join(new FormWorkflowExample)
          _       <- harness.submitForm("[data-workflow-form]", Vector(example.Draft.Title.name -> ""))
          invalid <- document(harness)
          _       <- harness.submitForm("[data-workflow-form]", validData)
          _       <- harness.clickButton("Begin another save")
          overlap <- document(harness)
        yield assertTrue(
          invalid.select("[data-workflow-notice]").text().contains("invalid"),
          invalid.select("[data-field-error] .form-error").text() == "A title is required.",
          overlap.select("[data-workflow-save-state]").text() == "saving",
          overlap.select("[data-workflow-notice]").text().contains("already active"),
          !overlap.select("[role=status]").text().contains("Release notes")
        )
      }
    },
    test("preserves edits during success, ignores stale completion, and resets to the advanced baseline") {
      ZIO.scoped {
        for
          harness <- ConnectedRender.join(new FormWorkflowExample)
          _       <- harness.submitForm("[data-workflow-form]", validData)
          _ <- harness.changeForm(
                 "[data-workflow-form]",
                 Vector(example.Draft.Title.name -> "Newer local edit"),
                 target = Some(example.Draft.Title.name)
               )
          _       <- harness.clickButton("Reset to baseline")
          blocked <- document(harness)
          _       <- harness.clickButton("Simulate success")
          saved   <- document(harness)
          _       <- harness.clickButton("Replay stale success")
          stale   <- document(harness)
          _       <- harness.clickButton("Reset to baseline")
          reset   <- document(harness)
        yield assertTrue(
          blocked.select("[data-workflow-notice]").text().contains("blocked"),
          blocked.select("[data-workflow-revision]").text() == "2",
          saved.select("[data-workflow-dirty]").text() == "true",
          saved.select("[data-workflow-baseline-advancements]").text() == "1",
          saved.select(s"[name='${example.Draft.Title.name}']").attr("value") == "Newer local edit",
          stale.select("[data-workflow-notice]").text().contains("stale completion"),
          stale.select("[data-workflow-baseline-advancements]").text() == "1",
          reset.select("[data-workflow-dirty]").text() == "false",
          reset.select(s"[name='${example.Draft.Title.name}']").attr("value") == "Release notes"
        )
      }
    },
    test("correlates failure and cancellation callbacks with generated submission tokens") {
      val validWorkflow = example.Model.initial.workflow.updated(
        example.Model.initial.workflow.current.updated(example.Draft.Title, "Release notes")
      )
      val started = example.update(
        example.Model.initial.copy(workflow = validWorkflow),
        example.Msg.BeginSaveAgain
      )
      val firstToken = started.workflow.save match
        case FormSaveState.Saving(submission) => submission.token
        case _                                => throw new AssertionError("save did not start")
      val failed = example.update(started, example.Msg.PersistenceFailed(firstToken))
      val retried = example.update(failed, example.Msg.BeginSaveAgain)
      val retryToken = retried.workflow.save match
        case FormSaveState.Saving(submission) => submission.token
        case _                                => throw new AssertionError("retry did not start")
      val stale = example.update(retried, example.Msg.PersistenceCancelled(firstToken))
      val cancelled = example.update(stale, example.Msg.PersistenceCancelled(retryToken))

      assertTrue(
        failed.workflow.save.isInstanceOf[FormSaveState.Failed[?, ?]],
        retryToken != firstToken,
        stale.notice == example.Notice.StaleCompletion,
        stale.workflow.save.isInstanceOf[FormSaveState.Saving[?, ?]],
        cancelled.notice == example.Notice.SaveCancelled,
        cancelled.workflow.save == FormSaveState.Idle,
        cancelled.baselineAdvancements == 0
      )
    }
  )
end FormWorkflowExampleSpec
