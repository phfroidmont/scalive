package scaliveapi

import zio.test.*

import scalive.*

object FormWorkflowSpec extends ZIOSpecDefault:
  final private case class Draft(name: String)
  final private case class Settings(enabled: Boolean)
  private val Root       = FormRoot("draft")
  private val Name       = Root.text("name").required(FieldIssue("Name is required"))
  private val Definition = Root.product[Draft]((name = Name))

  def spec = suite("FormWorkflowSpec")(
    test("keeps absent false checkboxes clean and tracks checked then unchecked revisions") {
      val root       = FormRoot("settings")
      val enabled    = root.field("enabled", FieldInput.checkbox())
      val definition = root.product[Settings]((enabled = enabled))
      val initial    = definition.initial(enabled.initial(false))
      val workflow   = definition.workflow[Unit](initial)
      val absent     = definition.event(FormData.empty, FormEventKind.Submitted).form
      val interacted = workflow.updated(absent)
      val submitted  = definition
        .event(FormData(Vector(enabled.name -> "true")), FormEventKind.Submitted)
        .form
      val checked   = interacted.updated(submitted)
      val unchecked = checked.updated(absent)

      assertTrue(
        !workflow.isDirty,
        workflow.revision == FormRevision.initial,
        interacted.current.valueOption.contains(Settings(false)),
        interacted.current.values == initial.values,
        interacted.current.interaction.visibility == ErrorVisibility.All,
        !interacted.isDirty,
        interacted.revision == FormRevision.initial,
        checked.current.valueOption.contains(Settings(true)),
        checked.isDirty,
        checked.revision.value == 1L,
        unchecked.current.valueOption.contains(Settings(false)),
        unchecked.current.values == initial.values,
        !unchecked.isDirty,
        unchecked.revision.value == 2L
      )
    },
    test("tracks exact dirty values and revisions but not interaction") {
      val initial  = Definition.initial(Name.initial("Ada"))
      val workflow = Definition.workflow[Unit](initial)
      val used     = Definition
        .event(FormData(Vector(Name.name -> "Ada")), FormEventKind.Submitted)
        .form
      val interacted = workflow.updated(used)
      val edited     = interacted.updated(used.updated(Name, "Grace"))

      assertTrue(
        !workflow.isDirty,
        !interacted.isDirty,
        interacted.revision == FormRevision.initial,
        edited.isDirty,
        edited.revision.value == 1L
      )
    },
    test("reports idle, saving, and revision-relevant failure states") {
      val idle = Definition.workflow[String](Definition.initial(Name.initial("Ada")))
      val (saving, submission) = idle.beginSave match
        case FormSaveStart.Started(next, value) => next -> value
        case _                                  => throw new AssertionError("save did not start")
      val failed = saving.saveFailed(submission.token, "offline") match
        case FormWorkflowTransition.Applied(next) => next
        case FormWorkflowTransition.Stale(_)      => throw new AssertionError("unexpected stale")
      val editedAfterFailure = failed.updated(failed.current.updated(Name, "Grace"))

      assertTrue(
        !idle.isSaving,
        idle.failureForCurrentRevision.isEmpty,
        idle.dismissFailure eq idle,
        saving.isSaving,
        saving.failureForCurrentRevision.isEmpty,
        saving.dismissFailure eq saving,
        !failed.isSaving,
        failed.failureForCurrentRevision.contains("offline"),
        editedAfterFailure.save == FormSaveState.Failed(submission, "offline"),
        editedAfterFailure.failureForCurrentRevision.isEmpty
      )
    },
    test("keeps failure relevance across feedback-only and identical-value updates") {
      val workflow = Definition.workflow[String](Definition.initial(Name.initial("Ada")))
      val (saving, submission) = workflow.beginSave match
        case FormSaveStart.Started(next, value) => next -> value
        case _                                  => throw new AssertionError("save did not start")
      val failed = saving.saveFailed(submission.token, "offline") match
        case FormWorkflowTransition.Applied(next) => next
        case FormWorkflowTransition.Stale(_)      => throw new AssertionError("unexpected stale")
      val identical = failed.updated(failed.current.updated(Name, "Ada"))
      val submitted = Definition
        .event(FormData(Vector(Name.name -> "Ada")), FormEventKind.Submitted)
        .form
      val interacted = identical.updated(submitted)

      assertTrue(
        identical.revision == failed.revision,
        identical.failureForCurrentRevision.contains("offline"),
        interacted.revision == failed.revision,
        interacted.current.interaction.visibility == ErrorVisibility.All,
        interacted.failureForCurrentRevision.contains("offline")
      )
    },
    test("hides but retains a failure after edits, including editing away and back") {
      val workflow = Definition.workflow[String](Definition.initial(Name.initial("Ada")))
      val (saving, submission) = workflow.beginSave match
        case FormSaveStart.Started(next, value) => next -> value
        case _                                  => throw new AssertionError("save did not start")
      val edited = saving.updated(saving.current.updated(Name, "Grace"))
      val failed = edited.saveFailed(submission.token, "offline") match
        case FormWorkflowTransition.Applied(next) => next
        case FormWorkflowTransition.Stale(_)      => throw new AssertionError("unexpected stale")
      val back = failed.updated(failed.current.updated(Name, "Ada"))

      assertTrue(
        failed.save == FormSaveState.Failed(submission, "offline"),
        failed.failureForCurrentRevision.isEmpty,
        back.revision.value == 2L,
        !back.isDirty,
        back.save == FormSaveState.Failed(submission, "offline"),
        back.failureForCurrentRevision.isEmpty
      )
    },
    test("dismisses an obsolete failure without changing the invalid form, baseline, or revision") {
      val workflow = Definition.workflow[String](Definition.initial(Name.initial("Ada")))
      val (saving, submission) = workflow.beginSave match
        case FormSaveStart.Started(next, value) => next -> value
        case _                                  => throw new AssertionError("save did not start")
      val invalid = Definition
        .event(FormData(Vector(Name.name -> "")), FormEventKind.Submitted)
        .form
      val edited = saving.updated(invalid)
      val failed = edited.saveFailed(submission.token, "offline") match
        case FormWorkflowTransition.Applied(next) => next
        case FormWorkflowTransition.Stale(_)      => throw new AssertionError("unexpected stale")
      val dismissed = failed.dismissFailure

      assertTrue(
        dismissed.current eq failed.current,
        dismissed.current.errors == failed.current.errors,
        dismissed.current.errors.nonEmpty,
        dismissed.current.interaction == failed.current.interaction,
        dismissed.current.interaction.visibility == ErrorVisibility.All,
        dismissed.baseline == failed.baseline,
        dismissed.revision == failed.revision,
        dismissed.isDirty == failed.isDirty,
        dismissed.failureForCurrentRevision.isEmpty,
        !dismissed.isSaving,
        dismissed.save == FormSaveState.Idle
      )
    },
    test("dismissal preserves retry correlation and makes the old token stale") {
      val workflow  = Definition.workflow[String](Definition.initial(Name.initial("Ada")))
      val canonical = Definition.initial(Name.initial("Canonical")).validSnapshot.get
      val (firstSaving, firstSubmission) = workflow.beginSave match
        case FormSaveStart.Started(next, value) => next -> value
        case _                                  => throw new AssertionError("save did not start")
      val failed = firstSaving.saveFailed(firstSubmission.token, "offline") match
        case FormWorkflowTransition.Applied(next) => next
        case FormWorkflowTransition.Stale(_)      => throw new AssertionError("unexpected stale")
      val dismissed                      = failed.dismissFailure
      val (retrySaving, retrySubmission) = dismissed.beginSave match
        case FormSaveStart.Started(next, value) => next -> value
        case _                                  => throw new AssertionError("retry did not start")
      val rejectsOldCompletions = Vector(dismissed, retrySaving).forall { workflow =>
        Vector(
          workflow.saveSucceeded(firstSubmission.token),
          workflow.saveSucceeded(firstSubmission.token, canonical),
          workflow.saveFailed(firstSubmission.token, "late failure"),
          workflow.saveCancelled(firstSubmission.token)
        ).forall {
          case FormWorkflowTransition.Stale(current) => current eq workflow
          case FormWorkflowTransition.Applied(_)     => false
        }
      }
      val retryCancellation = retrySaving.dismissFailure.saveCancelled(retrySubmission.token)

      assertTrue(
        failed.failureForCurrentRevision.contains("offline"),
        dismissed.failureForCurrentRevision.isEmpty,
        dismissed.save == FormSaveState.Idle,
        dismissed.revision == failed.revision,
        retrySubmission.token != firstSubmission.token,
        retrySaving.isSaving,
        retrySaving.dismissFailure eq retrySaving,
        rejectsOldCompletions,
        retryCancellation.isInstanceOf[FormWorkflowTransition.Applied[?]]
      )
    },
    test("rejects invalid and overlapping saves and uses a distinct retry token") {
      val invalid      = Definition.workflow[String](Definition.initial())
      val valid        = Definition.workflow[String](Definition.initial(Name.initial("Ada")))
      val invalidStart = invalid.beginSave
      val first        = valid.beginSave

      val result = first match
        case FormSaveStart.Started(saving, submission) =>
          val overlap = saving.beginSave
          val failed  = saving.saveFailed(submission.token, "offline")
          val retry   = failed match
            case FormWorkflowTransition.Applied(next) => next.beginSave
            case FormWorkflowTransition.Stale(_) => throw new AssertionError("unexpected stale")
          overlap -> retry
        case _ => throw new AssertionError("valid form did not begin saving")

      assertTrue(
        invalidStart.isInstanceOf[FormSaveStart.Invalid[?, ?]],
        result._1.isInstanceOf[FormSaveStart.AlreadySaving[?, ?]],
        result._2 match
          case FormSaveStart.Started(_, retry) =>
            first match
              case FormSaveStart.Started(_, original) => retry.token != original.token
              case _                                  => false
          case _ => false
      )
    },
    test("rejects a token from another workflow") {
      val first  = Definition.workflow[String](Definition.initial(Name.initial("Ada")))
      val second = Definition.workflow[String](Definition.initial(Name.initial("Grace")))
      val (firstSubmission, secondSaving, secondSubmission) =
        (first.beginSave, second.beginSave) match
          case (
                FormSaveStart.Started(_, firstSubmission),
                FormSaveStart.Started(secondSaving, secondSubmission)
              ) =>
            (firstSubmission, secondSaving, secondSubmission)
          case _ => throw new AssertionError("saves did not start")
      val canonical   = Definition.initial(Name.initial("Canonical")).validSnapshot.get
      val completions = Vector(
        secondSaving.saveSucceeded(firstSubmission.token),
        secondSaving.saveSucceeded(firstSubmission.token, canonical),
        secondSaving.saveFailed(firstSubmission.token, "foreign failure"),
        secondSaving.saveCancelled(firstSubmission.token)
      )

      assertTrue(
        firstSubmission.token != secondSubmission.token,
        completions.forall {
          case FormWorkflowTransition.Stale(current) => current eq secondSaving
          case FormWorkflowTransition.Applied(_)     => false
        },
        secondSaving.save == FormSaveState.Saving(secondSubmission)
      )
    },
    test("advances the baseline while preserving edits made during save") {
      val workflow = Definition.workflow[String](Definition.initial(Name.initial("Ada")))
      val started  = workflow.beginSave match
        case value: FormSaveStart.Started[?, ?] => value
        case _                                  => throw new AssertionError("save did not start")
      val saving     = started.workflow.asInstanceOf[Definition.Workflow[String]]
      val submission = started.submission.asInstanceOf[FormSubmission[
        Root.type,
        Definition.type,
        Draft
      ]]
      val edited    = saving.updated(saving.current.updated(Name, "Grace"))
      val completed = edited.saveSucceeded(submission.token) match
        case FormWorkflowTransition.Applied(next) => next
        case FormWorkflowTransition.Stale(_)      => throw new AssertionError("unexpected stale")

      assertTrue(
        completed.current.valueOption.contains(Draft("Grace")),
        completed.baseline == submission.values,
        completed.isDirty,
        completed
          .saveFailed(submission.token, "stale").isInstanceOf[
            FormWorkflowTransition.Stale[?]
          ]
      )
    },
    test("advances a canonical baseline while preserving newer edits") {
      val workflow  = Definition.workflow[String](Definition.initial(Name.initial("Ada")))
      val canonical = Definition.initial(Name.initial("Ada Lovelace")).validSnapshot.get
      val (saving, submission) = workflow.beginSave match
        case FormSaveStart.Started(next, value) => next -> value
        case _                                  => throw new AssertionError("save did not start")
      val edited    = saving.updated(saving.current.updated(Name, "Grace"))
      val completed = edited.saveSucceeded(submission.token, canonical) match
        case FormWorkflowTransition.Applied(next) => next
        case FormWorkflowTransition.Stale(_)      => throw new AssertionError("unexpected stale")

      assertTrue(
        completed.current.valueOption.contains(Draft("Grace")),
        completed.baseline == canonical.values,
        completed.isDirty,
        completed.save == FormSaveState.Idle
      )
    },
    test("becomes clean when newer edits equal the canonical baseline") {
      val workflow  = Definition.workflow[String](Definition.initial(Name.initial("Ada")))
      val canonical = Definition.initial(Name.initial("Grace")).validSnapshot.get
      val (saving, submission) = workflow.beginSave match
        case FormSaveStart.Started(next, value) => next -> value
        case _                                  => throw new AssertionError("save did not start")
      val edited    = saving.updated(saving.current.updated(Name, "Grace"))
      val completed = edited.saveSucceeded(submission.token, canonical) match
        case FormWorkflowTransition.Applied(next) => next
        case FormWorkflowTransition.Stale(_)      => throw new AssertionError("unexpected stale")

      assertTrue(
        completed.current eq edited.current,
        completed.baseline == canonical.values,
        !completed.isDirty,
        completed.revision == edited.revision
      )
    },
    test("retains newer edits and the submitted snapshot after save failure") {
      val workflow = Definition.workflow[String](Definition.initial(Name.initial("Ada")))
      val (saving, submission) = workflow.beginSave match
        case FormSaveStart.Started(next, value) => next -> value
        case _                                  => throw new AssertionError("save did not start")
      val edited = saving.updated(saving.current.updated(Name, "Grace"))
      val failed = edited.saveFailed(submission.token, "offline") match
        case FormWorkflowTransition.Applied(next) => next
        case FormWorkflowTransition.Stale(_)      => throw new AssertionError("unexpected stale")

      assertTrue(
        failed.current.valueOption.contains(Draft("Grace")),
        failed.baseline == workflow.baseline,
        failed.isDirty,
        failed.save == FormSaveState.Failed(submission, "offline")
      )
    },
    test("applies canonical success to unchanged edits and rejects duplicate completion") {
      val workflow  = Definition.workflow[String](Definition.initial(Name.initial("Ada")))
      val canonical = Definition.initial(Name.initial("Grace")).validSnapshot.get
      val (saving, submission) = workflow.beginSave match
        case FormSaveStart.Started(next, value) => next -> value
        case _                                  => throw new AssertionError("save did not start")
      val completed = saving.saveSucceeded(submission.token, canonical) match
        case FormWorkflowTransition.Applied(next) => next
        case FormWorkflowTransition.Stale(_)      => throw new AssertionError("unexpected stale")

      assertTrue(
        completed.current.valueOption.contains(Draft("Grace")),
        completed.current.interaction == FormInteraction.pristine,
        completed.baseline == canonical.values,
        !completed.isDirty,
        completed.saveSucceeded(submission.token).isInstanceOf[FormWorkflowTransition.Stale[?]]
      )
    },
    test("blocks reset while saving and restores a pristine exact baseline after cancellation") {
      val workflow = Definition.workflow[String](Definition.initial(Name.initial("Ada")))
      val edited   = workflow.updated(workflow.current.updated(Name, "Grace"))
      val (saving, submission) = edited.beginSave match
        case FormSaveStart.Started(next, value) => next -> value
        case _                                  => throw new AssertionError("save did not start")
      val blocked   = saving.reset
      val cancelled = saving.saveCancelled(submission.token) match
        case FormWorkflowTransition.Applied(next) => next
        case FormWorkflowTransition.Stale(_)      => throw new AssertionError("unexpected stale")
      val reset = cancelled.reset match
        case FormWorkflowReset.Reset(next)  => next
        case FormWorkflowReset.AlreadySaving(_, _) => throw new AssertionError("reset remained blocked")

      assertTrue(
        blocked.isInstanceOf[FormWorkflowReset.AlreadySaving[?, ?]],
        reset.current.valueOption.contains(Draft("Ada")),
        reset.current.interaction == FormInteraction.pristine,
        !reset.isDirty,
        reset.revision.value == 2L
      )
    },
    test("every start, transition and reset result exposes the workflow to store") {
      val workflow = Definition.workflow[String](Definition.initial(Name.initial("Ada")))
      val invalid  = Definition.workflow[String](Definition.initial(Name.initial(""))).beginSave
      val started  = workflow.beginSave
      val saving   = started.workflow
      val again    = saving.beginSave
      val blocked  = saving.reset
      val token    = started match
        case FormSaveStart.Started(_, submission) => submission.token
        case _                                    => throw new AssertionError("save did not start")
      val applied = saving.saveCancelled(token)
      val stale   = applied.workflow.saveCancelled(token)

      assertTrue(
        invalid.isInstanceOf[FormSaveStart.Invalid[?, ?]],
        invalid.workflow.current.interaction.visibility == ErrorVisibility.All,
        saving.isSaving,
        again.workflow eq saving,
        blocked.workflow eq saving,
        !applied.workflow.isSaving,
        stale.workflow eq applied.workflow,
        applied.workflow.reset.workflow.revision == applied.workflow.revision
      )
    },
    test("complete applies each async result to the save in flight") {
      val workflow = Definition.workflow[String](
        Definition.initial(Name.initial("Ada"))
      ).updated(Definition.initial(Name.initial("Grace")))
      val (saving, submission) = workflow.beginSave match
        case FormSaveStart.Started(next, value) => next -> value
        case _                                  => throw new AssertionError("save did not start")
      val token = submission.token
      val failure: LiveAsyncResult.Failed[String] | LiveAsyncResult.Died => String =
        case LiveAsyncResult.Failed(error) => s"failed: $error"
        case LiveAsyncResult.Died(defect)  => s"died: ${defect.getMessage}"
      val saved     = saving.complete(token, LiveAsyncResult.Succeeded(42))(failure)
      val failed    = saving.complete(token, LiveAsyncResult.Failed("offline"))(failure)
      val died      = saving.complete(token, LiveAsyncResult.Died(RuntimeException("boom")))(failure)
      val cancelled = saving.complete(token, LiveAsyncResult.Cancelled(None))(failure)
      val stale     = saved.workflow.complete(token, LiveAsyncResult.Succeeded(43))(failure)

      assertTrue(
        saved match
          case FormSaveCompletion.Saved(next, value) =>
            value == 42 && next.baseline == submission.values && !next.isDirty
          case _ => false,
        failed match
          case FormSaveCompletion.Failed(next) =>
            next.failureForCurrentRevision.contains("failed: offline")
          case _ => false,
        died match
          case FormSaveCompletion.Failed(next) =>
            next.failureForCurrentRevision.contains("died: boom")
          case _ => false,
        cancelled match
          case FormSaveCompletion.Cancelled(next) => next.save == FormSaveState.Idle
          case _                                  => false,
        stale match
          case FormSaveCompletion.Stale(next) => next eq saved.workflow
          case _                              => false
      )
    },
    test("complete makes the saved value the baseline, and fails an invalid one") {
      val workflow = Definition.workflow[String](Definition.initial(Name.initial("Ada")))
      val (saving, submission) = workflow.beginSave match
        case FormSaveStart.Started(next, value) => next -> value
        case _                                  => throw new AssertionError("save did not start")
      val failure: LiveAsyncResult.Failed[Nothing] | LiveAsyncResult.Died => String =
        case LiveAsyncResult.Failed(_)     => "failed"
        case LiveAsyncResult.Died(defect)  => defect.getClass.getSimpleName
      val canonical = saving.complete(
        submission.token,
        LiveAsyncResult.Succeeded(Draft("Grace")),
        (saved: Draft) => Definition.initial(Name.initial(saved.name))
      )(failure)
      val invalid = saving.complete(
        submission.token,
        LiveAsyncResult.Succeeded(Draft("")),
        (saved: Draft) => Definition.initial(Name.initial(saved.name))
      )(failure)

      assertTrue(
        canonical match
          case FormSaveCompletion.Saved(next, value) =>
            value == Draft("Grace") && next.current.valueOption.contains(Draft("Grace")) &&
            !next.isDirty
          case _ => false,
        invalid match
          case FormSaveCompletion.Failed(next) =>
            next.failureForCurrentRevision.contains("IllegalStateException") &&
            next.baseline == workflow.baseline
          case _ => false
      )
    },
    test("rejects a saved form from another definition at compile time") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        final case class Draft(name: String)
        val root = FormRoot("draft")
        val name = root.text("name")
        val first = root.product[Draft]((name = name))
        val second = root.product[Draft]((name = name))
        val workflow = first.workflow[Unit](first.initial(name.initial("Ada")))
        workflow.beginSave match
          case FormSaveStart.Started(saving, submission) =>
            saving.complete(
              submission.token,
              LiveAsyncResult.Succeeded(Draft("Grace")),
              (saved: Draft) => second.initial(name.initial(saved.name))
            )(_ => ())
          case _ => ()
      """)

      assertTrue(errors.nonEmpty)
    },
    test("rejects canonical snapshots from another definition at compile time") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        final case class Draft(name: String)
        val root = FormRoot("draft")
        val name = root.text("name")
        val first = root.product[Draft]((name = name))
        val second = root.product[Draft]((name = name))
        val workflow = first.workflow[String](first.initial(name.initial("Ada")))
        val snapshot = second.initial(name.initial("Grace")).validSnapshot.get
        workflow.beginSave match
          case FormSaveStart.Started(saving, submission) =>
            saving.saveSucceeded(submission.token, snapshot)
          case _ => ()
      """)

      assertTrue(errors.nonEmpty)
    }
  )
end FormWorkflowSpec
