package scalive.docs.examples

import zio.{Task, ZIO}

import scalive.*

// docs:start form-workflow-example
final class FormWorkflowExample
    extends LiveView[FormWorkflowExample.Msg, FormWorkflowExample.Model]:
  import FormWorkflowExample.*

  def mount(ctx: MountContext): Task[Model] =
    ZIO.succeed(Model.initial)

  def handleMessage(model: Model, ctx: MessageContext) =
    message => ZIO.succeed(update(model, message))

  def view(model: Signal[Model]): HtmlElement[Msg] =
    val workflow = model.map(_.workflow)
    val draft    = workflow.map(_.current)
    val title    = draft.field(Draft.Title)

    sectionTag(
      cls                               := "docs-form-workflow",
      dataAttr("form-workflow")         := "",
      dataAttr("save-state")            := model.map(value => saveState(value.workflow)),
      dataAttr("dirty")                 := workflow.map(_.isDirty.toString),
      dataAttr("revision")              := workflow.map(_.revision.value.toString),
      dataAttr("baseline-advancements") := model.map(_.baselineAdvancements.toString),
      aria.label                        := "Form save workflow",
      form(
        dataAttr("workflow-form") := "",
        idAttr                    := "form-save-workflow",
        Draft.Definition.onChange(Msg.Validate(_)),
        Draft.Definition.onSubmit(Msg.BeginSave(_)),
        label(forId                                        := title.id, "Draft title"),
        title.text(title.validationAttributes, placeholder := "Release notes"),
        title.errorFeedback(
          _ => "A title is required.",
          dataAttr("field-error") := "title"
        ),
        button(typ := "submit", "Begin save")
      ),
      div(
        dataAttr("workflow-controls") := "",
        model.map(activeSubmission).option { submission =>
          div(
            button(
              typ := "button",
              on.click(submission.map(value => Msg.PersistenceSucceeded(value.token))),
              "Simulate success"
            ),
            button(
              typ := "button",
              on.click(submission.map(value => Msg.PersistenceFailed(value.token))),
              "Simulate failure"
            ),
            button(
              typ := "button",
              on.click(submission.map(value => Msg.PersistenceCancelled(value.token))),
              "Simulate cancellation"
            ),
            button(typ := "button", on.click(Msg.BeginSaveAgain), "Begin another save")
          )
        },
        model.map(_.staleToken).option { token =>
          button(
            typ := "button",
            on.click(token.map(Msg.PersistenceSucceeded(_))),
            "Replay stale success"
          )
        },
        button(typ := "button", on.click(Msg.Reset), "Reset to baseline")
      ),
      div(
        dataAttr("workflow-status") := "",
        p(
          dataAttr("workflow-notice") := "",
          role                        := "status",
          aria.live                   := "polite",
          aria.atomic                 := true,
          model.map(_.notice.label)
        ),
        p("Dirty: ", strong(dataAttr("workflow-dirty") := "", workflow.map(_.isDirty.toString))),
        p(
          "Revision: ",
          strong(dataAttr("workflow-revision") := "", workflow.map(_.revision.value.toString))
        ),
        p(
          "Save state: ",
          strong(
            dataAttr("workflow-save-state") := "",
            model.map(value => saveState(value.workflow))
          )
        ),
        p(
          "Baseline advancements: ",
          strong(
            dataAttr("workflow-baseline-advancements") := "",
            model.map(_.baselineAdvancements.toString)
          )
        )
      )
    )
  end view
end FormWorkflowExample

object FormWorkflowExample:
  final case class Draft(title: String)

  object Draft:
    val Root  = FormRoot("workflow-draft")
    val Title = Root.text("title").map(_.trim).required(FieldIssue("title.required"))

    val Definition = Root.product[Draft](Tuple1(Title))

  enum Notice(val label: String):
    case Ready        extends Notice("Edit the form, then begin a save.")
    case EditRecorded extends Notice("The edit changed the current revision.")
    case InteractionRecorded
        extends Notice("Interaction changed without advancing the values revision.")
    case EditRecordedWhileSaving
        extends Notice(
          "The edit changed the revision while the submitted snapshot remains in flight."
        )
    case InvalidStart  extends Notice("Save did not start because the typed form is invalid.")
    case SavingStarted extends Notice("Save started with a revision-bound submission token.")
    case AlreadySaving
        extends Notice("A second save was rejected because one save is already active.")
    case SaveSucceeded
        extends Notice(
          "Success advanced the baseline; newer edits, if any, remain current and dirty."
        )
    case SaveFailed extends Notice("The correlated save failed and may now be retried.")
    case SaveCancelled
        extends Notice("The correlated save was cancelled without advancing the baseline.")
    case StaleCompletion
        extends Notice("A stale completion was ignored and changed no workflow state.")
    case ResetBlocked  extends Notice("Reset was blocked while a save is active.")
    case ResetComplete extends Notice("Reset restored a pristine form from the current baseline.")

  final case class Model(
    workflow: Draft.Definition.Workflow[String],
    notice: Notice = Notice.Ready,
    staleToken: Option[FormSubmissionToken] = None,
    baselineAdvancements: Int = 0)

  object Model:
    val initial: Model = Model(Draft.Definition.workflow(Draft.Definition.initial()))

  enum Msg:
    case Validate(event: Draft.Definition.Event)
    case BeginSave(event: Draft.Definition.Event)
    case BeginSaveAgain
    case PersistenceSucceeded(token: FormSubmissionToken)
    case PersistenceFailed(token: FormSubmissionToken)
    case PersistenceCancelled(token: FormSubmissionToken)
    case Reset

  private[examples] def update(model: Model, message: Msg): Model = message match
    case Msg.Validate(event) =>
      val wasSaving = activeSubmission(model).nonEmpty
      val next      = model.workflow.updated(event.form)
      model.copy(
        workflow = next,
        notice =
          if next.revision == model.workflow.revision then Notice.InteractionRecorded
          else if wasSaving then Notice.EditRecordedWhileSaving
          else Notice.EditRecorded
      )
    case Msg.BeginSave(event) =>
      beginSave(model.copy(workflow = model.workflow.updated(event.form)))
    case Msg.BeginSaveAgain              => beginSave(model)
    case Msg.PersistenceSucceeded(token) =>
      model.workflow.saveSucceeded(token) match
        case FormWorkflowTransition.Applied(next) =>
          model.copy(
            workflow = next,
            notice = Notice.SaveSucceeded,
            staleToken = Some(token),
            baselineAdvancements = model.baselineAdvancements + 1
          )
        case FormWorkflowTransition.Stale(_) => model.copy(notice = Notice.StaleCompletion)
    case Msg.PersistenceFailed(token) =>
      model.workflow.saveFailed(token, "simulated persistence failure") match
        case FormWorkflowTransition.Applied(next) =>
          model.copy(workflow = next, notice = Notice.SaveFailed, staleToken = Some(token))
        case FormWorkflowTransition.Stale(_) => model.copy(notice = Notice.StaleCompletion)
    case Msg.PersistenceCancelled(token) =>
      model.workflow.saveCancelled(token) match
        case FormWorkflowTransition.Applied(next) =>
          model.copy(workflow = next, notice = Notice.SaveCancelled, staleToken = Some(token))
        case FormWorkflowTransition.Stale(_) => model.copy(notice = Notice.StaleCompletion)
    case Msg.Reset =>
      model.workflow.reset match
        case FormWorkflowReset.Reset(next) =>
          model.copy(workflow = next, notice = Notice.ResetComplete)
        case FormWorkflowReset.Saving(_, _) => model.copy(notice = Notice.ResetBlocked)

  private def beginSave(model: Model): Model = model.workflow.beginSave match
    case FormSaveStart.Invalid(next) => model.copy(workflow = next, notice = Notice.InvalidStart)
    case FormSaveStart.AlreadySaving(current, _) =>
      model.copy(workflow = current, notice = Notice.AlreadySaving)
    case FormSaveStart.Started(next, _) =>
      model.copy(workflow = next, notice = Notice.SavingStarted)

  private def activeSubmission(model: Model): Option[model.workflow.Submission] =
    model.workflow.save match
      case FormSaveState.Saving(submission) => Some(submission)
      case _                                => None

  private def saveState(workflow: Draft.Definition.Workflow[String]): String = workflow.save match
    case FormSaveState.Idle         => "idle"
    case FormSaveState.Saving(_)    => "saving"
    case FormSaveState.Failed(_, _) => "failed"
end FormWorkflowExample
// docs:end form-workflow-example
