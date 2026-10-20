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
  /** The workflow to store, whatever the result. */
  def workflow: Workflow

  /** The current form is invalid, so no save started.
    *
    * @param workflow
    *   The workflow with every error of the current form visible. Store it so the errors render.
    */
  case Invalid(workflow: Workflow)

  /** A save is already in flight, so no new save started.
    *
    * @param workflow
    *   The unchanged workflow.
    * @param submission
    *   The submission already in flight.
    */
  case AlreadySaving(workflow: Workflow, submission: Submission)

  /** A save started.
    *
    * @param workflow
    *   The workflow in the saving state. Store it in the same turn that starts persistence.
    * @param submission
    *   The new submission. Persist its value and return its token when persistence ends.
    */
  case Started(workflow: Workflow, submission: Submission)

/** Result of applying a token-correlated asynchronous completion. */
enum FormWorkflowTransition[+Workflow]:
  /** The workflow to store, whatever the result. */
  def workflow: Workflow

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
  /** The workflow to store, whatever the result. */
  def workflow: Workflow

  /** The form was restored to the baseline.
    *
    * @param workflow
    *   The workflow with a pristine form of the baseline values and no stored failure.
    */
  case Reset(workflow: Workflow)

  /** A save is in flight, so nothing was reset.
    *
    * @param workflow
    *   The unchanged workflow.
    * @param submission
    *   The in-flight submission that blocked the reset.
    */
  case AlreadySaving(workflow: Workflow, submission: Submission)

/** Result of applying a save task's [[LiveAsyncResult]] with [[FormWorkflow.complete]].
  *
  * @tparam A
  *   the value the save task produces
  */
enum FormSaveCompletion[+Workflow, +A]:
  /** The workflow to store, whatever the result. */
  def workflow: Workflow

  /** The save in flight succeeded.
    *
    * @param workflow
    *   The workflow with the saved values as its baseline.
    * @param value
    *   The value the save task produced.
    */
  case Saved(workflow: Workflow, value: A)

  /** The save in flight failed or died, and the workflow stores the failure.
    *
    * @param workflow
    *   The workflow in the failed state.
    */
  case Failed(workflow: Workflow)

  /** The save in flight was cancelled, and the workflow is idle again.
    *
    * @param workflow
    *   The idle workflow.
    */
  case Cancelled(workflow: Workflow)

  /** The token does not match the save in flight, so nothing changed.
    *
    * @param workflow
    *   The unchanged workflow.
    */
  case Stale(workflow: Workflow)
end FormSaveCompletion

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

  /** Applies how the save task ended, when `token` belongs to the save in flight.
    *
    * This form keeps the submitted values as the new baseline on success, like
    * `saveSucceeded(token)`. A failure or a defect is stored as `failure(...)`, like `saveFailed`,
    * and a cancellation returns to idle, like `saveCancelled`. Any other token returns
    * [[FormSaveCompletion.Stale]].
    */
  def complete[E, A](
    token: FormSubmissionToken,
    result: LiveAsyncResult[E, A]
  )(
    failure: LiveAsyncResult.Failed[E] | LiveAsyncResult.Died => Failure
  ): FormSaveCompletion[FormWorkflow[Owner, Schema, Domain, Failure], A] =
    result match
      case LiveAsyncResult.Succeeded(value) =>
        completion(saveSucceeded(token), FormSaveCompletion.Saved(_, value))
      case failed: LiveAsyncResult.Failed[E] =>
        completion(saveFailed(token, failure(failed)), FormSaveCompletion.Failed(_))
      case died: LiveAsyncResult.Died =>
        completion(saveFailed(token, failure(died)), FormSaveCompletion.Failed(_))
      case LiveAsyncResult.Cancelled(_) =>
        completion(saveCancelled(token), FormSaveCompletion.Cancelled(_))

  /** Applies how the save task ended, when `token` belongs to the save in flight.
    *
    * This form makes `saved(value)` the new baseline on success. Use it when persistence returns
    * the stored value, for example normalized. As with `saveSucceeded(token, canonical)`, unchanged
    * edits are replaced by a pristine form of it. A saved form that does not validate is a
    * persistence bug: it is stored as `failure` of a [[LiveAsyncResult.Died]] carrying an
    * `IllegalStateException`. Otherwise this behaves like the other `complete`.
    */
  def complete[E, A](
    token: FormSubmissionToken,
    result: LiveAsyncResult[E, A],
    saved: A => Form[Owner, Schema, Domain]
  )(
    failure: LiveAsyncResult.Failed[E] | LiveAsyncResult.Died => Failure
  ): FormSaveCompletion[FormWorkflow[Owner, Schema, Domain, Failure], A] =
    result match
      case LiveAsyncResult.Succeeded(value) =>
        saved(value).validSnapshot match
          case Some(canonical) =>
            completion(saveSucceeded(token, canonical), FormSaveCompletion.Saved(_, value))
          case None =>
            val defect: LiveAsyncResult.Died = LiveAsyncResult.Died(
              IllegalStateException("the saved value does not validate against its form")
            )
            completion(saveFailed(token, failure(defect)), FormSaveCompletion.Failed(_))
      case other => complete(token, other)(failure)

  private def completion[A](
    transition: FormWorkflowTransition[FormWorkflow[Owner, Schema, Domain, Failure]],
    applied: FormWorkflow[Owner, Schema, Domain, Failure] => FormSaveCompletion[
      FormWorkflow[Owner, Schema, Domain, Failure],
      A
    ]
  ): FormSaveCompletion[FormWorkflow[Owner, Schema, Domain, Failure], A] = transition match
    case FormWorkflowTransition.Applied(next) => applied(next)
    case FormWorkflowTransition.Stale(next)   => FormSaveCompletion.Stale(next)

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
