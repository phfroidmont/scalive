package scalive

/** Monotonic version of a workflow's editable values. */
opaque type FormRevision = Long

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

object FormSubmissionToken:
  private[scalive] def apply(workflowIdentity: AnyRef, value: Long): FormSubmissionToken =
    workflowIdentity -> value

/** A definition-owned proof that values decoded successfully.
  *
  * The schema parameter ties [[values]] and [[value]] to the definition that validated them.
  */
final class ValidFormSnapshot[Owner, Schema, Domain] private[scalive] (
  val values: FormValues[Owner, Schema],
  val value: Domain)

/** The exact valid edit state sent to one persistence operation.
  *
  * Completion must return [[token]] to the workflow; [[revision]] records whether edits occurred
  * while persistence was in flight.
  */
final class FormSubmission[Owner, Schema, Domain] private[scalive] (
  val token: FormSubmissionToken,
  val revision: FormRevision,
  val snapshot: ValidFormSnapshot[Owner, Schema, Domain]):
  def values: FormValues[Owner, Schema] = snapshot.values
  def value: Domain                     = snapshot.value

/** Current persistence state; at most one submission may be in flight. */
enum FormSaveState[+Submission, +Failure]:
  case Idle
  case Saving(submission: Submission)
  case Failed(submission: Submission, failure: Failure)

/** Exhaustive result of attempting to begin a save. */
enum FormSaveStart[+Workflow, +Submission]:
  case Invalid(next: Workflow)
  case AlreadySaving(current: Workflow, submission: Submission)
  case Started(next: Workflow, submission: Submission)

/** Result of applying a token-correlated asynchronous completion. */
enum FormWorkflowTransition[+Workflow]:
  case Applied(workflow: Workflow)
  case Stale(workflow: Workflow)

/** Result of requesting reset; an in-flight save blocks reset. */
enum FormWorkflowReset[+Workflow, +Submission]:
  case Reset(workflow: Workflow)
  case Saving(current: Workflow, submission: Submission)

/** Pure baseline, revision, reset, and one-in-flight-save state.
  *
  * Create with [[FormDefinition.workflow]]. Legal transitions are represented by the result enums;
  * callers should store the returned workflow. Completion tokens reject stale callbacks, while a
  * successful save always advances [[baseline]] to the acknowledged snapshot.
  */
final class FormWorkflow[Owner, Schema, Domain, Failure] private[scalive] (
  private val definition: FormDefinition[Owner, Domain],
  private val workflowIdentity: AnyRef,
  val current: Form[Owner, Schema, Domain],
  val baseline: FormValues[Owner, Schema],
  val revision: FormRevision,
  val save: FormSaveState[FormSubmission[Owner, Schema, Domain], Failure],
  private val nextSubmissionGeneration: Long):

  type Submission = FormSubmission[Owner, Schema, Domain]

  /** Whether current editable values differ from the last acknowledged baseline. */
  def isDirty: Boolean = current.values != baseline

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
      case FormSaveState.Saving(submission) => FormWorkflowReset.Saving(this, submission)
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

  /** Acknowledges the submitted snapshot as baseline when `token` is current.
    *
    * Edits made after submission remain current; only an unchanged revision is replaced.
    */
  def saveSucceeded(
    token: FormSubmissionToken
  ): FormWorkflowTransition[FormWorkflow[Owner, Schema, Domain, Failure]] =
    save match
      case FormSaveState.Saving(submission) if submission.token == token =>
        succeed(submission, submission.snapshot)
      case _ => FormWorkflowTransition.Stale(this)

  /** Acknowledges a persistence-supplied canonical snapshot when `token` is current.
    *
    * The canonical snapshot becomes baseline. It replaces current values only if no edit occurred
    * since the corresponding submission.
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
