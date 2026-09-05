package scaliveapi

import zio.test.*

import scalive.*

object FormWorkflowSpec extends ZIOSpecDefault:
  private final case class Draft(name: String)
  private val Root = FormRoot("draft")
  private val Name = Root.text("name").required(FieldIssue("Name is required"))
  private val Definition = Root.product[Draft](Tuple1(Name))

  def spec = suite("FormWorkflowSpec")(
    test("tracks exact dirty values and revisions but not interaction") {
      val initial  = Definition.initial(Name.initial("Ada"))
      val workflow = Definition.workflow[Unit](initial)
      val used = Definition
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
    test("rejects invalid and overlapping saves and uses a distinct retry token") {
      val invalid = Definition.workflow[String](Definition.initial())
      val valid   = Definition.workflow[String](Definition.initial(Name.initial("Ada")))
      val invalidStart = invalid.beginSave
      val first = valid.beginSave

      val result = first match
        case FormSaveStart.Started(saving, submission) =>
          val overlap = saving.beginSave
          val failed  = saving.saveFailed(submission.token, "offline")
          val retry = failed match
            case FormWorkflowTransition.Applied(next) => next.beginSave
            case FormWorkflowTransition.Stale(_)      => throw new AssertionError("unexpected stale")
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
              ) => (firstSubmission, secondSaving, secondSubmission)
          case _ => throw new AssertionError("saves did not start")
      val canonical = Definition.initial(Name.initial("Canonical")).validSnapshot.get
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
      val started = workflow.beginSave match
        case value: FormSaveStart.Started[?, ?] => value
        case _                                  => throw new AssertionError("save did not start")
      val saving = started.next.asInstanceOf[Definition.Workflow[String]]
      val submission = started.submission.asInstanceOf[FormSubmission[
        Root.type,
        Definition.type,
        Draft
      ]]
      val edited = saving.updated(saving.current.updated(Name, "Grace"))
      val completed = edited.saveSucceeded(submission.token) match
        case FormWorkflowTransition.Applied(next) => next
        case FormWorkflowTransition.Stale(_)      => throw new AssertionError("unexpected stale")

      assertTrue(
        completed.current.valueOption.contains(Draft("Grace")),
        completed.baseline == submission.values,
        completed.isDirty,
        completed.saveFailed(submission.token, "stale").isInstanceOf[
          FormWorkflowTransition.Stale[?]
        ]
      )
    },
    test("advances a canonical baseline while preserving newer edits") {
      val workflow  = Definition.workflow[String](Definition.initial(Name.initial("Ada")))
      val canonical = Definition.initial(Name.initial("Ada Lovelace")).validSnapshot.get
      val (saving, submission) = workflow.beginSave match
        case FormSaveStart.Started(next, value) => next -> value
        case _ => throw new AssertionError("save did not start")
      val edited = saving.updated(saving.current.updated(Name, "Grace"))
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
        case _ => throw new AssertionError("save did not start")
      val edited = saving.updated(saving.current.updated(Name, "Grace"))
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
        case _ => throw new AssertionError("save did not start")
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
      val workflow = Definition.workflow[String](Definition.initial(Name.initial("Ada")))
      val canonical = Definition.initial(Name.initial("Grace")).validSnapshot.get
      val (saving, submission) = workflow.beginSave match
        case FormSaveStart.Started(next, value) => next -> value
        case _ => throw new AssertionError("save did not start")
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
        case _ => throw new AssertionError("save did not start")
      val blocked = saving.reset
      val cancelled = saving.saveCancelled(submission.token) match
        case FormWorkflowTransition.Applied(next) => next
        case FormWorkflowTransition.Stale(_)      => throw new AssertionError("unexpected stale")
      val reset = cancelled.reset match
        case FormWorkflowReset.Reset(next) => next
        case FormWorkflowReset.Saving(_, _) => throw new AssertionError("reset remained blocked")

      assertTrue(
        blocked.isInstanceOf[FormWorkflowReset.Saving[?, ?]],
        reset.current.valueOption.contains(Draft("Ada")),
        reset.current.interaction == FormInteraction.pristine,
        !reset.isDirty,
        reset.revision.value == 2L
      )
    },
    test("rejects canonical snapshots from another definition at compile time") {
      val errors = scala.compiletime.testing.typeCheckErrors("""
        import scalive.*
        final case class Draft(name: String)
        val root = FormRoot("draft")
        val name = root.text("name")
        val first = root.product[Draft](Tuple1(name))
        val second = root.product[Draft](Tuple1(name))
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
