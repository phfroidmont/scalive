package forms

import zio.*

import scalive.*

trait StoredProfiles:
  /** Saves the profile and returns it as stored, for example with a normalized email. */
  def save(profile: Profile): Task[Profile]

object StoredProfileSave:
  val Saved = FlashKind("profile-saved")

  // docs:start forms-workflow-complete-saved
  def completed[Msg, Model](
    workflow: Profile.Definition.Workflow[SaveError],
    token: FormSubmissionToken,
    result: LiveAsyncResult[Throwable, Profile],
    ctx: MessageContext[Msg, Model]
  ): Task[Profile.Definition.Workflow[SaveError]] =
    workflow.complete(token, result, storedForm)(_ =>
      SaveError("The profile could not be saved.")
    ) match
      case FormSaveCompletion.Saved(next, _) => ctx.flash.put(Saved, "Profile saved.").as(next)
      case other                             => ZIO.succeed(other.workflow)

  private def storedForm(profile: Profile): Profile.Definition.Form =
    Profile.Definition.initial(
      Profile.Name.initial(profile.name),
      Profile.Email.initial(profile.email)
    )
  // docs:end forms-workflow-complete-saved
