// docs:start forms-workflow
package forms

import zio.*

import scalive.*

final case class SaveError(message: String)

final class ProfileSaveLiveView(profiles: Profiles)
    extends LiveView[ProfileSaveLiveView.Msg, ProfileSaveLiveView.Model]:
  import ProfileSaveLiveView.*

  def mount(ctx: MountContext): Task[Model] =
    ZIO.succeed(Model(Profile.Definition.workflow(Profile.Definition.initial())))

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Validate(event) =>
      ZIO.succeed(model.copy(workflow = model.workflow.updated(event.form)))

    // docs:start forms-workflow-start
    case Msg.Save(event) =>
      model.workflow.updated(event.form).beginSave match
        case FormSaveStart.Invalid(next) =>
          ZIO.succeed(model.copy(workflow = next))
        case FormSaveStart.AlreadySaving(current, _) =>
          ZIO.succeed(model.copy(workflow = current))
        case FormSaveStart.Started(next, submission) =>
          val token = submission.token
          ctx.async
            .start(SaveKey)(profiles.save(submission.value))(Msg.Saved(token, _))
            .as(model.copy(workflow = next))
    // docs:end forms-workflow-start

    // docs:start forms-workflow-complete
    case Msg.Saved(token, result) =>
      val transition = result match
        case LiveAsyncResult.Succeeded(_) => model.workflow.saveSucceeded(token)
        case LiveAsyncResult.Failed(_)    =>
          model.workflow.saveFailed(token, SaveError("The profile could not be saved."))
        case LiveAsyncResult.Cancelled(_) => model.workflow.saveCancelled(token)
      transition match
        case FormWorkflowTransition.Applied(next) => ZIO.succeed(model.copy(workflow = next))
        case FormWorkflowTransition.Stale(_)      => ZIO.succeed(model)
    // docs:end forms-workflow-complete

    // docs:start forms-workflow-failure
    case Msg.DismissFailure =>
      ZIO.succeed(model.copy(workflow = model.workflow.dismissFailure))
    // docs:end forms-workflow-failure

    // docs:start forms-workflow-reset
    case Msg.Reset =>
      model.workflow.reset match
        case FormWorkflowReset.Reset(next)         => ZIO.succeed(model.copy(workflow = next))
        case FormWorkflowReset.AlreadySaving(_, _) => ZIO.succeed(model)
    // docs:end forms-workflow-reset
  end handleMessage

  def view(model: Signal[Model]): HtmlElement[Msg] =
    val workflow = model.map(_.workflow)
    val name     = workflow.map(_.current).field(Profile.Name)
    val email    = workflow.map(_.current).field(Profile.Email)

    mainTag(
      form(
        idAttr := "profile-form",
        Profile.Definition.onChange(Msg.Validate(_)),
        Profile.Definition.onSubmit(Msg.Save(_)),
        label(forId := name.id, "Name"),
        name.text(name.validationAttributes),
        label(forId := email.id, "Email"),
        email.email(email.validationAttributes),
        button(
          typ      := "submit",
          disabled := workflow.map(_.isSaving),
          workflow.map(current => if current.isSaving then "Saving..." else "Save")
        ),
        button(typ := "button", on.click(Msg.Reset), "Reset")
      ),
      workflow.map(_.failureForCurrentRevision).option { failure =>
        div(
          role := "alert",
          p(failure.map(_.message)),
          button(typ := "button", on.click(Msg.DismissFailure), "Dismiss")
        )
      },
      workflow.map(_.isDirty).when(p("You have unsaved changes."))
    )
  end view
end ProfileSaveLiveView

object ProfileSaveLiveView:
  val SaveKey = AsyncKey[Unit]("save-profile")

  final case class Model(workflow: Profile.Definition.Workflow[SaveError])

  enum Msg:
    case Validate(event: Profile.Definition.Event)
    case Save(event: Profile.Definition.Event)
    case Saved(token: FormSubmissionToken, result: LiveAsyncResult[Unit])
    case DismissFailure
    case Reset
// docs:end forms-workflow
