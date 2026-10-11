package scalive

/** Monotonic version of a workflow's editable values. */
opaque type FormRevision = Long

/** The initial revision and accessors for [[FormRevision]]. */
object FormRevision:
  /** Revision assigned when a workflow is created. */
  val initial: FormRevision = 0L

  extension (revision: FormRevision)
    /** Numeric revision for logging or serialization. */
    def value: Long = revision

  private[scalive] def next(revision: FormRevision): FormRevision =
    if revision == Long.MaxValue then throw new IllegalStateException("form revision exhausted")
    revision + 1L

/** Opaque correlation token for exactly one save attempt. */
opaque type FormSubmissionToken = (AnyRef, Long)

/** The companion of [[FormSubmissionToken]]. Only [[FormWorkflow.beginSave]] creates tokens. */
object FormSubmissionToken:
  private[scalive] def apply(workflowIdentity: AnyRef, value: Long): FormSubmissionToken =
    workflowIdentity -> value

/** A definition-owned proof that values decoded successfully.
  *
  * The schema parameter ties [[values]] and [[value]] to the definition that validated them.
  *
  * @param values
  *   The editable values that decoded successfully.
  * @param value
  *   The domain value decoded from `values`.
  */
final class ValidFormSnapshot[Owner, Schema, Domain] private[scalive] (
  val values: FormValues[Owner, Schema],
  val value: Domain)

/** The exact valid edit state sent to one persistence operation.
  *
  * Completion must return [[token]] to the workflow; [[revision]] records whether edits occurred
  * while persistence was in flight.
  *
  * @param token
  *   The token to pass to `saveSucceeded`, `saveFailed`, or `saveCancelled` when persistence ends.
  * @param revision
  *   The workflow revision when the save started.
  * @param snapshot
  *   The valid editable values and domain value to persist.
  */
final class FormSubmission[Owner, Schema, Domain] private[scalive] (
  val token: FormSubmissionToken,
  val revision: FormRevision,
  val snapshot: ValidFormSnapshot[Owner, Schema, Domain]):

  /** The editable values to persist, taken from [[snapshot]]. */
  def values: FormValues[Owner, Schema] = snapshot.values

  /** The domain value to persist, taken from [[snapshot]]. */
  def value: Domain = snapshot.value

/** Current persistence state; at most one submission may be in flight. */
enum FormSaveState[+Submission, +Failure]:
  /** No save is in flight and no failure is stored. */
  case Idle

  /** A save is in flight.
    *
    * @param submission
    *   The submission being persisted.
    */
  case Saving(submission: Submission)

  /** The last save failed.
    *
    * The failure stays until the next save starts, a reset, or [[FormWorkflow.dismissFailure]].
    *
    * @param submission
    *   The submission that failed.
    * @param failure
    *   The failure passed to `saveFailed`.
    */
  case Failed(submission: Submission, failure: Failure)

/** Exhaustive result of attempting to begin a save. */
enum FormSaveStart[+Workflow, +Submission]:
  /** The current form is invalid, so no save started.
    *
    * @param next
    *   The workflow with every error of the current form visible. Store it so the errors render.
    */
  case Invalid(next: Workflow)

  /** A save is already in flight, so no new save started.
    *
    * @param current
    *   The unchanged workflow.
    * @param submission
    *   The submission already in flight.
    */
  case AlreadySaving(current: Workflow, submission: Submission)

  /** A save started.
    *
    * @param next
    *   The workflow in the saving state. Store it in the same turn that starts persistence.
    * @param submission
    *   The new submission. Persist its value and return its token when persistence ends.
    */
  case Started(next: Workflow, submission: Submission)

/** Result of applying a token-correlated asynchronous completion. */
enum FormWorkflowTransition[+Workflow]:
  /** The token matched the save in flight, and the completion was applied.
    *
    * @param workflow
    *   The updated workflow to store.
    */
  case Applied(workflow: Workflow)

  /** The token does not match the save in flight, so nothing changed.
    *
    * Completions for an earlier attempt, for another workflow, or arriving twice end up here.
    *
    * @param workflow
    *   The unchanged workflow.
    */
  case Stale(workflow: Workflow)

/** Result of requesting reset; an in-flight save blocks reset. */
enum FormWorkflowReset[+Workflow, +Submission]:
  /** The form was restored to the baseline.
    *
    * @param workflow
    *   The workflow with a pristine form of the baseline values and no stored failure.
    */
  case Reset(workflow: Workflow)

  /** A save is in flight, so nothing was reset.
    *
    * @param current
    *   The unchanged workflow.
    * @param submission
    *   The in-flight submission that blocked the reset.
    */
  case AlreadySaving(current: Workflow, submission: Submission)

/** Pure baseline, revision, reset, and one-in-flight-save state.
  *
  * Create with [[FormDefinition.workflow]]. Legal transitions are represented by the result enums;
  * callers should store the returned workflow. Completion tokens reject stale callbacks, while a
  * successful save always advances [[baseline]] to the acknowledged snapshot.
  *
  * @param current
  *   The form being edited.
  * @param baseline
  *   The last saved values: the values the workflow was created with, or those acknowledged by the
  *   latest successful save.
  * @param revision
  *   The revision of the editable values. It advances each time the current values change.
  * @param save
  *   The save state: idle, saving, or failed.
  */
final class FormWorkflow[Owner, Schema, Domain, Failure] private[scalive] (
  private val definition: FormDefinition[Owner, Domain],
  private val workflowIdentity: AnyRef,
  val current: Form[Owner, Schema, Domain],
  val baseline: FormValues[Owner, Schema],
  val revision: FormRevision,
  val save: FormSaveState[FormSubmission[Owner, Schema, Domain], Failure],
  private val nextSubmissionGeneration: Long):

  /** The type of the submissions this workflow starts. */
  type Submission = FormSubmission[Owner, Schema, Domain]

  /** Whether current editable values differ from the last acknowledged baseline. */
  def isDirty: Boolean = current.values != baseline

  /** Whether a save is currently in flight. */
  def isSaving: Boolean = save match
    case FormSaveState.Saving(_) => true
    case _                       => false

  /** Failure feedback for the current revision.
    *
    * Feedback-only and identical-value changes retain relevance. Revision-changing edits hide, but
    * do not clear, the stored failure.
    */
  def failureForCurrentRevision: Option[Failure] = save match
    case FormSaveState.Failed(submission, failure) if submission.revision == revision =>
      Some(failure)
    case _ => None

  /** Clears the stored failure back to idle without changing the form, baseline, or revision.
    *
    * Idle and saving workflows are unchanged; dismissal does not cancel persistence.
    */
  def dismissFailure: FormWorkflow[Owner, Schema, Domain, Failure] = save match
    case FormSaveState.Failed(_, _) => recreate(save = FormSaveState.Idle)
    case _                          => this

  /** Installs a same-definition form and advances revision only when editable values changed. */
  def updated(next: Form[Owner, Schema, Domain]): FormWorkflow[Owner, Schema, Domain, Failure] =
    require(next.owningDefinition eq definition, "form belongs to another definition")
    recreate(
      current = next,
      revision = if next.values == current.values then revision else FormRevision.next(revision)
    )

  /** Restores the baseline as a pristine form, unless a save is currently in flight. */
  def reset: FormWorkflowReset[FormWorkflow[Owner, Schema, Domain, Failure], Submission] =
    save match
      case FormSaveState.Saving(submission) => FormWorkflowReset.AlreadySaving(this, submission)
      case _                                =>
        val nextForm = definition
          .fromValues(baseline.asInstanceOf[definition.Values])
          .asInstanceOf[Form[Owner, Schema, Domain]]
        val nextRevision =
          if nextForm.values == current.values then revision else FormRevision.next(revision)
        FormWorkflowReset.Reset(
          recreate(
            current = nextForm,
            revision = nextRevision,
            save = FormSaveState.Idle
          )
        )

  /** Starts persistence only for a valid current form and only when no save is active.
    *
    * An invalid form is returned with all errors visible; a started submission snapshots the exact
    * values and domain value to persist.
    */
  def beginSave: FormSaveStart[FormWorkflow[Owner, Schema, Domain, Failure], Submission] =
    save match
      case FormSaveState.Saving(submission) => FormSaveStart.AlreadySaving(this, submission)
      case _                                =>
        current.validSnapshot match
          case None => FormSaveStart.Invalid(recreate(current = current.withAllErrorsVisible))
          case Some(snapshot) =>
            if nextSubmissionGeneration == Long.MaxValue then
              throw new IllegalStateException("form submission generation exhausted")
            val submission = new FormSubmission(
              FormSubmissionToken(workflowIdentity, nextSubmissionGeneration),
              revision,
              snapshot
            )
            FormSaveStart.Started(
              recreate(
                save = FormSaveState.Saving(submission),
                nextSubmissionGeneration = nextSubmissionGeneration + 1L
              ),
              submission
            )

  /** Applies a successful save when `token` belongs to the save in flight.
    *
    * This form makes the submitted values the new baseline. If the values were not edited since the
    * save started, the current form becomes a pristine form of them; otherwise the newer edits stay
    * current. Any other token returns [[FormWorkflowTransition.Stale]].
    */
  def saveSucceeded(
    token: FormSubmissionToken
  ): FormWorkflowTransition[FormWorkflow[Owner, Schema, Domain, Failure]] =
    save match
      case FormSaveState.Saving(submission) if submission.token == token =>
        succeed(submission, submission.snapshot)
      case _ => FormWorkflowTransition.Stale(this)

  /** Applies a successful save when `token` belongs to the save in flight.
    *
    * This form makes `canonical`, a snapshot supplied by persistence, the new baseline. If the
    * values were not edited since the save started, the current form becomes a pristine form of
    * `canonical`; otherwise the newer edits stay current. Any other token returns
    * [[FormWorkflowTransition.Stale]].
    */
  def saveSucceeded(
    token: FormSubmissionToken,
    canonical: ValidFormSnapshot[Owner, Schema, Domain]
  ): FormWorkflowTransition[FormWorkflow[Owner, Schema, Domain, Failure]] =
    save match
      case FormSaveState.Saving(submission) if submission.token == token =>
        succeed(submission, canonical)
      case _ => FormWorkflowTransition.Stale(this)

  /** Records a failure for the current token; stale completions leave the workflow unchanged. */
  def saveFailed(
    token: FormSubmissionToken,
    failure: Failure
  ): FormWorkflowTransition[FormWorkflow[Owner, Schema, Domain, Failure]] = save match
    case FormSaveState.Saving(submission) if submission.token == token =>
      FormWorkflowTransition.Applied(
        recreate(save = FormSaveState.Failed(submission, failure))
      )
    case _ => FormWorkflowTransition.Stale(this)

  /** Cancels the current token back to idle; stale cancellations are ignored explicitly. */
  def saveCancelled(
    token: FormSubmissionToken
  ): FormWorkflowTransition[FormWorkflow[Owner, Schema, Domain, Failure]] = save match
    case FormSaveState.Saving(submission) if submission.token == token =>
      FormWorkflowTransition.Applied(recreate(save = FormSaveState.Idle))
    case _ => FormWorkflowTransition.Stale(this)

  private def succeed(
    submission: Submission,
    acknowledged: ValidFormSnapshot[Owner, Schema, Domain]
  ): FormWorkflowTransition[FormWorkflow[Owner, Schema, Domain, Failure]] =
    val replaceCurrent = revision == submission.revision
    val nextCurrent    =
      if replaceCurrent then
        definition
          .fromValues(acknowledged.values.asInstanceOf[definition.Values])
          .asInstanceOf[Form[Owner, Schema, Domain]]
      else current
    val nextRevision =
      if nextCurrent.values == current.values then revision else FormRevision.next(revision)
    FormWorkflowTransition.Applied(
      recreate(
        current = nextCurrent,
        baseline = acknowledged.values,
        revision = nextRevision,
        save = FormSaveState.Idle
      )
    )

  private def recreate(
    current: Form[Owner, Schema, Domain] = this.current,
    baseline: FormValues[Owner, Schema] = this.baseline,
    revision: FormRevision = this.revision,
    save: FormSaveState[Submission, Failure] = this.save,
    nextSubmissionGeneration: Long = this.nextSubmissionGeneration
  ): FormWorkflow[Owner, Schema, Domain, Failure] =
    new FormWorkflow(
      definition,
      workflowIdentity,
      current,
      baseline,
      revision,
      save,
      nextSubmissionGeneration
    )
end FormWorkflow

private[scalive] object FormWorkflow:
  def create[Owner, Domain, Failure](
    definition: FormDefinition[Owner, Domain],
    form: definition.Form
  ): definition.Workflow[Failure] =
    new FormWorkflow(
      definition,
      new Object,
      form,
      form.values,
      FormRevision.initial,
      FormSaveState.Idle,
      0L
    )
