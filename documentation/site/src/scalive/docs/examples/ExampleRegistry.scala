package scalive.docs.examples

import java.nio.charset.StandardCharsets
import java.util.Base64
import scala.reflect.ClassTag

import scalive.*
import scalive.docs.model.{ExampleCatalog, ExampleDescriptor}

final private[docs] case class ExampleTraceValue(
  typeName: String,
  summary: String,
  fields: Vector[(String, String)] = Vector.empty)

private[docs] trait ExampleTraceProjector[-A]:
  def project(value: A): ExampleTraceValue

final private[docs] case class ExampleTraceProjectors[Msg, Model](
  message: ExampleTraceProjector[Msg],
  model: ExampleTraceProjector[Model])

final private[docs] case class ExampleReset[Msg](message: Msg, controlLabel: String)

sealed private[docs] trait RegisteredExample:
  def descriptor: ExampleDescriptor
  def resetMessage: Any
  def resetControlLabel: String
  def render(instanceId: String): Mod[Nothing]
  def projectMessage(value: Any): Option[ExampleTraceValue]
  def projectModel(value: Any): Option[ExampleTraceValue]

final private class ExampleEntry[Msg: LiveMessageTag, Model: ClassTag](
  val descriptor: ExampleDescriptor,
  factory: String => LiveView[Msg, Model],
  reset: ExampleReset[Msg],
  traces: ExampleTraceProjectors[Msg, Model])
    extends RegisteredExample:
  private val messageTag = summon[LiveMessageTag[Msg]].classTag
  private val modelTag   = summon[ClassTag[Model]]

  val resetControlLabel: String = reset.controlLabel
  val resetMessage: Any         = reset.message

  def render(instanceId: String): Mod[Nothing] =
    liveView(instanceId, factory(instanceId), sticky = false)

  def projectMessage(value: Any): Option[ExampleTraceValue] =
    messageTag.unapply(value).map(traces.message.project)

  def projectModel(value: Any): Option[ExampleTraceValue] =
    modelTag.unapply(value).map(traces.model.project)

private[docs] object ExampleRegistry:
  private val asyncReport =
    new ExampleEntry[AsyncReportExample.Msg, AsyncReportExample.Model](
      descriptor = ExampleCatalog.AsyncReport,
      factory = instanceId => new AsyncReportExample(instanceId),
      reset = ExampleReset(AsyncReportExample.Msg.Reset, "Reset async report"),
      traces = ExampleTraceProjectors(
        message = new ExampleTraceProjector[AsyncReportExample.Msg]:
          def project(value: AsyncReportExample.Msg) = value match
            case AsyncReportExample.Msg.RunSuccess =>
              ExampleTraceValue("AsyncReportExample.Msg", "Start the successful report")
            case AsyncReportExample.Msg.RunFailure =>
              ExampleTraceValue("AsyncReportExample.Msg", "Start the failing report")
            case AsyncReportExample.Msg.Replace =>
              ExampleTraceValue("AsyncReportExample.Msg", "Replace active report work")
            case AsyncReportExample.Msg.Retry =>
              ExampleTraceValue("AsyncReportExample.Msg", "Retry report work")
            case AsyncReportExample.Msg.Cancel =>
              ExampleTraceValue("AsyncReportExample.Msg", "Cancel active report work")
            case AsyncReportExample.Msg.Reset =>
              ExampleTraceValue("AsyncReportExample.Msg", "Reset async report state")
            case AsyncReportExample.Msg.ReportCompleted(result) =>
              ExampleTraceValue("AsyncReportExample.Msg", asyncResultLabel(result)),
        model = new ExampleTraceProjector[AsyncReportExample.Model]:
          def project(value: AsyncReportExample.Model) =
            ExampleTraceValue(
              "AsyncReportExample.Model",
              "Current async report state",
              Vector("state" -> asyncValueLabel(value.report))
            )
      )
    )

  private val activityStream =
    new ExampleEntry[ActivityStreamExample.Msg, ActivityStreamExample.Model](
      descriptor = ExampleCatalog.ActivityStream,
      factory = _ => new ActivityStreamExample,
      reset = ExampleReset(ActivityStreamExample.Msg.Reset, "Reset activity stream"),
      traces = ExampleTraceProjectors(
        message = new ExampleTraceProjector[ActivityStreamExample.Msg]:
          def project(value: ActivityStreamExample.Msg) = value match
            case ActivityStreamExample.Msg.Add =>
              ExampleTraceValue("ActivityStreamExample.Msg", "Insert one activity")
            case ActivityStreamExample.Msg.Delete(activity) =>
              ExampleTraceValue(
                "ActivityStreamExample.Msg",
                "Delete one activity",
                Vector("activityId" -> activity.id.toString)
              )
            case ActivityStreamExample.Msg.Reset =>
              ExampleTraceValue("ActivityStreamExample.Msg", "Reset the activity stream"),
        model = new ExampleTraceProjector[ActivityStreamExample.Model]:
          def project(value: ActivityStreamExample.Model) =
            ExampleTraceValue(
              "ActivityStreamExample.Model",
              "Current durable activity state",
              Vector(
                "activityCount" -> value.activities.size.toString,
                "nextId"        -> value.nextId.toString
              )
            )
      )
    )

  private val counter = new ExampleEntry[CounterExample.Msg, CounterExample.Model](
    descriptor = ExampleCatalog.Counter,
    factory = _ => new CounterExample,
    reset = ExampleReset(CounterExample.Msg.Reset, "Reset"),
    traces = ExampleTraceProjectors(
      message = new ExampleTraceProjector[CounterExample.Msg]:
        def project(value: CounterExample.Msg) = value match
          case CounterExample.Msg.Decrement =>
            ExampleTraceValue("CounterExample.Msg", "Decrease the count")
          case CounterExample.Msg.Increment =>
            ExampleTraceValue("CounterExample.Msg", "Increase the count")
          case CounterExample.Msg.Reset =>
            ExampleTraceValue("CounterExample.Msg", "Reset the count"),
      model = new ExampleTraceProjector[CounterExample.Model]:
        def project(value: CounterExample.Model) =
          ExampleTraceValue(
            "CounterExample.Model",
            "Current counter state",
            Vector("count" -> value.count.toString)
          )
    )
  )

  private val browserIntegration =
    new ExampleEntry[BrowserInteropExample.Msg, BrowserInteropExample.Model](
      descriptor = ExampleCatalog.BrowserIntegration,
      factory = instanceId => new BrowserInteropExample(instanceId),
      reset = ExampleReset(
        BrowserInteropExample.Msg.Reset,
        "Reset browser integration"
      ),
      traces = ExampleTraceProjectors(
        message = new ExampleTraceProjector[BrowserInteropExample.Msg]:
          def project(value: BrowserInteropExample.Msg) = value match
            case BrowserInteropExample.Msg.CopySample =>
              ExampleTraceValue(
                "BrowserInteropExample.Msg",
                "Request a browser clipboard write"
              )
            case BrowserInteropExample.Msg.Reset =>
              ExampleTraceValue("BrowserInteropExample.Msg", "Reset browser integration"),
        model = new ExampleTraceProjector[BrowserInteropExample.Model]:
          def project(value: BrowserInteropExample.Model) =
            ExampleTraceValue(
              "BrowserInteropExample.Model",
              "Current browser operation state",
              Vector(
                "requestNumber" -> value.requestNumber.toString,
                "operation"     -> value.operation.traceLabel
              )
            )
      )
    )

  private val shoppingCart =
    new ExampleEntry[ShoppingCartExample.Msg, ShoppingCartExample.Model](
      descriptor = ExampleCatalog.ShoppingCart,
      factory = _ => new ShoppingCartExample,
      reset = ExampleReset(ShoppingCartExample.Msg.Clear, "Clear"),
      traces = ExampleTraceProjectors(
        message = new ExampleTraceProjector[ShoppingCartExample.Msg]:
          def project(value: ShoppingCartExample.Msg) = value match
            case ShoppingCartExample.Msg.Add(product) =>
              ExampleTraceValue(
                "ShoppingCartExample.Msg",
                "Add one product",
                Vector("product" -> product.sku)
              )
            case ShoppingCartExample.Msg.Remove(product) =>
              ExampleTraceValue(
                "ShoppingCartExample.Msg",
                "Remove one product",
                Vector("product" -> product.sku)
              )
            case ShoppingCartExample.Msg.Clear =>
              ExampleTraceValue("ShoppingCartExample.Msg", "Clear the cart"),
        model = new ExampleTraceProjector[ShoppingCartExample.Model]:
          def project(value: ShoppingCartExample.Model) =
            val quantities =
              value.lines.map(line => s"${line.product.sku}=${line.quantity}").mkString(", ")
            ExampleTraceValue(
              "ShoppingCartExample.Model",
              "Current cart state",
              Vector(
                "itemCount" -> value.itemCount.toString,
                "total"     -> money(value.totalInCents),
                "lines"     -> quantities
              )
            )
      )
    )

  private val subscriptionClock =
    new ExampleEntry[SubscriptionClockExample.Msg, SubscriptionClockExample.Model](
      descriptor = ExampleCatalog.SubscriptionClock,
      factory = instanceId => new SubscriptionClockExample(instanceId),
      reset = ExampleReset(SubscriptionClockExample.Msg.Reset, "Reset clock"),
      traces = ExampleTraceProjectors(
        message = new ExampleTraceProjector[SubscriptionClockExample.Msg]:
          def project(value: SubscriptionClockExample.Msg) = value match
            case SubscriptionClockExample.Msg.Start =>
              ExampleTraceValue("SubscriptionClockExample.Msg", "Start the clock subscription")
            case SubscriptionClockExample.Msg.Replace =>
              ExampleTraceValue("SubscriptionClockExample.Msg", "Replace the clock subscription")
            case SubscriptionClockExample.Msg.Cancel =>
              ExampleTraceValue("SubscriptionClockExample.Msg", "Cancel the clock subscription")
            case SubscriptionClockExample.Msg.Reset =>
              ExampleTraceValue("SubscriptionClockExample.Msg", "Reset the clock subscription")
            case SubscriptionClockExample.Msg.Tick(_) =>
              ExampleTraceValue("SubscriptionClockExample.Msg", "Receive one clock tick"),
        model = new ExampleTraceProjector[SubscriptionClockExample.Model]:
          def project(value: SubscriptionClockExample.Model) =
            ExampleTraceValue(
              "SubscriptionClockExample.Model",
              "Current clock subscription state",
              Vector(
                "mode"      -> value.mode.label,
                "tickCount" -> value.tickCount.toString
              )
            )
      )
    )

  private val serviceInjection =
    new ExampleEntry[ReportsExample.Msg, ReportsExample.Model](
      descriptor = ExampleCatalog.ServiceInjection,
      factory = _ => ReportsExamplePreview(),
      reset = ExampleReset(ReportsExample.Msg.ResetSelection, "Reset selected report"),
      traces = ExampleTraceProjectors(
        message = new ExampleTraceProjector[ReportsExample.Msg]:
          def project(value: ReportsExample.Msg) = value match
            case ReportsExample.Msg.Select(report) =>
              ExampleTraceValue(
                "ReportsExample.Msg",
                "Select a report supplied by the service",
                Vector("reportId" -> report.id.toString)
              )
            case ReportsExample.Msg.ResetSelection =>
              ExampleTraceValue("ReportsExample.Msg", "Reset the selected report")
            case ReportsExample.Msg.Refresh =>
              ExampleTraceValue("ReportsExample.Msg", "Refresh reports from the service"),
        model = new ExampleTraceProjector[ReportsExample.Model]:
          def project(value: ReportsExample.Model) = value match
            case ReportsExample.Model.Loaded(reports, selected) =>
              ExampleTraceValue(
                "ReportsExample.Model",
                "Loaded reports with one selected report",
                Vector(
                  "reportCount"      -> reports.size.toString,
                  "selectedReportId" -> selected.id.toString
                )
              )
            case ReportsExample.Model.Empty =>
              ExampleTraceValue("ReportsExample.Model", "No reports available")
            case ReportsExample.Model.Failed =>
              ExampleTraceValue("ReportsExample.Model", "Report loading failed")
      )
    )

  private val lifecycle =
    new ExampleEntry[LifecycleExample.Msg, LifecycleExample.Model](
      descriptor = ExampleCatalog.Lifecycle,
      factory = _ => new LifecycleExample,
      reset = ExampleReset(LifecycleExample.Msg.Reset, "Reset example"),
      traces = ExampleTraceProjectors(
        message = new ExampleTraceProjector[LifecycleExample.Msg]:
          def project(value: LifecycleExample.Msg) = value match
            case LifecycleExample.Msg.PutNotification =>
              ExampleTraceValue("LifecycleExample.Msg", "Put a keyed notification")
            case LifecycleExample.Msg.ClearNotification =>
              ExampleTraceValue("LifecycleExample.Msg", "Clear the keyed notification")
            case LifecycleExample.Msg.RequestAttention =>
              ExampleTraceValue("LifecycleExample.Msg", "Change the projected page title")
            case LifecycleExample.Msg.Reset =>
              ExampleTraceValue("LifecycleExample.Msg", "Reset lifecycle state"),
        model = new ExampleTraceProjector[LifecycleExample.Model]:
          def project(value: LifecycleExample.Model) =
            ExampleTraceValue(
              "LifecycleExample.Model",
              "Current lifecycle state",
              Vector(
                "connectedMount" -> value.connectedMount.toString,
                "currentTitle"   -> value.currentTitle
              )
            )
      )
    )

  private val profileForm =
    new ExampleEntry[ProfileFormExample.Msg, ProfileFormExample.Model](
      descriptor = ExampleCatalog.ProfileForm,
      factory = _ => new ProfileFormExample,
      reset = ExampleReset(ProfileFormExample.Msg.Reset, "Reset form"),
      traces = ExampleTraceProjectors(
        message = new ExampleTraceProjector[ProfileFormExample.Msg]:
          def project(value: ProfileFormExample.Msg) = value match
            case ProfileFormExample.Msg.Validate(event) =>
              formEventTrace("Validate the profile form", event)
            case ProfileFormExample.Msg.Save(event) =>
              formEventTrace("Submit the profile form", event)
            case ProfileFormExample.Msg.Reset =>
              ExampleTraceValue("ProfileFormExample.Msg", "Reset the form"),
        model = new ExampleTraceProjector[ProfileFormExample.Model]:
          def project(value: ProfileFormExample.Model) =
            ExampleTraceValue(
              "ProfileFormExample.Model",
              "Current profile form state",
              Vector(
                "valid"      -> value.form.state.isValid.toString,
                "submitted"  -> value.form.state.submitted.toString,
                "usedFields" -> value.form.state.used.size.toString,
                "saved"      -> value.saved.nonEmpty.toString
              )
            )
      )
    )

  private val navigation =
    new ExampleEntry[NavigationExample.Msg, NavigationExample.Model](
      descriptor = ExampleCatalog.Navigation,
      factory = _ => new NavigationExample,
      reset = ExampleReset(NavigationExample.Msg.Reset, "Reset navigation"),
      traces = ExampleTraceProjectors(
        message = new ExampleTraceProjector[NavigationExample.Msg]:
          def project(value: NavigationExample.Msg) = value match
            case NavigationExample.Msg.Select(query) =>
              ExampleTraceValue(
                "NavigationExample.Msg",
                "Select typed search parameters",
                Vector("preset" -> query.label)
              )
            case NavigationExample.Msg.Reset =>
              ExampleTraceValue("NavigationExample.Msg", "Reset navigation state"),
        model = new ExampleTraceProjector[NavigationExample.Model]:
          def project(value: NavigationExample.Model) =
            ExampleTraceValue(
              "NavigationExample.Model",
              "Current typed search destination",
              Vector("preset" -> value.query.label)
            )
      )
    )

  private val votingComponents =
    new ExampleEntry[VotingComponentsExample.Msg, VotingComponentsExample.Model](
      descriptor = ExampleCatalog.VotingComponents,
      factory = _ => new VotingComponentsExample,
      reset = ExampleReset(VotingComponentsExample.Msg.Reset, "Reset voting components"),
      traces = ExampleTraceProjectors(
        message = new ExampleTraceProjector[VotingComponentsExample.Msg]:
          def project(value: VotingComponentsExample.Msg) = value match
            case VotingComponentsExample.Msg.ComponentReported(id, votes) =>
              ExampleTraceValue(
                "VotingComponentsExample.Msg",
                "Component reported a vote count",
                Vector("componentId" -> id, "votes" -> votes.toString)
              )
            case VotingComponentsExample.Msg.UpdateScalaProps =>
              ExampleTraceValue("VotingComponentsExample.Msg", "Parent updated component props")
            case VotingComponentsExample.Msg.Reset =>
              ExampleTraceValue("VotingComponentsExample.Msg", "Reset voting components"),
        model = new ExampleTraceProjector[VotingComponentsExample.Model]:
          def project(value: VotingComponentsExample.Model) =
            ExampleTraceValue(
              "VotingComponentsExample.Model",
              "Current parent component state",
              Vector(
                "scalaRevision" -> value.scalaRevision.toString,
                "resetEpoch"    -> value.resetEpoch.toString
              )
            )
      )
    )

  private val textUpload =
    new ExampleEntry[TextUploadExample.Msg, TextUploadExample.Model](
      descriptor = ExampleCatalog.TextUpload,
      factory = _ => new TextUploadExample,
      reset = ExampleReset(TextUploadExample.Msg.Reset, "Reset text upload"),
      traces = ExampleTraceProjectors(
        message = new ExampleTraceProjector[TextUploadExample.Msg]:
          def project(value: TextUploadExample.Msg) = value match
            case TextUploadExample.Msg.Validate =>
              ExampleTraceValue("TextUploadExample.Msg", "Validate selected upload metadata")
            case TextUploadExample.Msg.Progress =>
              ExampleTraceValue("TextUploadExample.Msg", "Refresh upload progress")
            case TextUploadExample.Msg.Cancel(_) =>
              ExampleTraceValue("TextUploadExample.Msg", "Cancel one upload entry")
            case TextUploadExample.Msg.Summarize =>
              ExampleTraceValue("TextUploadExample.Msg", "Summarize completed text")
            case TextUploadExample.Msg.Reset =>
              ExampleTraceValue("TextUploadExample.Msg", "Reset upload state"),
        model = new ExampleTraceProjector[TextUploadExample.Model]:
          def project(value: TextUploadExample.Model) =
            ExampleTraceValue(
              "TextUploadExample.Model",
              "Current upload lifecycle state",
              Vector(
                "activeEntries" -> value.upload.entries.size.toString,
                "summaries"     -> value.summaries.size.toString,
                "totalBytes"    -> value.summaries.map(_.bytes).sum.toString
              )
            )
      )
    )

  val entries: Vector[RegisteredExample] =
    Vector(
      activityStream,
      asyncReport,
      browserIntegration,
      counter,
      lifecycle,
      navigation,
      profileForm,
      serviceInjection,
      shoppingCart,
      subscriptionClock,
      textUpload,
      votingComponents
    )

  private val byId = entries.map(entry => entry.descriptor.id -> entry).toMap

  def get(id: String): Option[RegisteredExample] = byId.get(id)

  def instanceId(pageRoute: String, directiveId: String): String =
    val route = Base64.getUrlEncoder.withoutPadding.encodeToString(
      pageRoute.getBytes(StandardCharsets.UTF_8)
    )
    s"docs-example-$directiveId-$route"

  def topic(pageRoute: String, directiveId: String): String =
    s"lv:${instanceId(pageRoute, directiveId)}"

  def inspectorInstanceId(pageRoute: String, directiveId: String): String =
    instanceId(pageRoute, directiveId).replace("docs-example-", "docs-xray-")

  def inspectorTopic(pageRoute: String, directiveId: String): String =
    s"lv:${inspectorInstanceId(pageRoute, directiveId)}"

  private def money(cents: Int): String =
    val dollars   = cents / 100
    val remainder = cents % 100
    f"$$$dollars%d.$remainder%02d"

  private def formEventTrace(
    summary: String,
    event: FormEvent[ProfileFormExample.Profile]
  ): ExampleTraceValue =
    ExampleTraceValue(
      "ProfileFormExample.Msg",
      summary,
      Vector(
        "valid"      -> event.isValid.toString,
        "submitted"  -> event.submitted.toString,
        "target"     -> event.target.fold("none")(_.name),
        "usedFields" -> event.state.used.size.toString
      )
    )

  private def asyncResultLabel(result: LiveAsyncResult[?]): String = result match
    case LiveAsyncResult.Succeeded(_) => "Async report succeeded"
    case LiveAsyncResult.Failed(_)    => "Async report failed"
    case LiveAsyncResult.Cancelled(_) => "Async report was cancelled"

  private def asyncValueLabel(value: AsyncValue[?]): String = value match
    case AsyncValue.Empty           => "empty"
    case AsyncValue.Loading(_)      => "loading"
    case AsyncValue.Ok(_)           => "succeeded"
    case AsyncValue.Failed(_, _)    => "failed"
    case AsyncValue.Cancelled(_, _) => "cancelled"

  def validationErrors: Vector[String] =
    val duplicateIds = entries.groupBy(_.descriptor.id).collect {
      case (id, matches) if matches.sizeIs > 1 => s"duplicate runtime example id '$id'."
    }
    val projectionIds = ExampleCatalog.entries.map(_.id).toSet
    val runtimeIds    = entries.map(_.descriptor.id).toSet
    val catalogErrors =
      (projectionIds -- runtimeIds).toVector.sorted.map(id => s"missing runtime example '$id'.") ++
        (runtimeIds -- projectionIds).toVector.sorted.map(id =>
          s"unexpected runtime example '$id'."
        )
    val metadataErrors = entries.flatMap { entry =>
      Vector(
        Option.when(entry.resetControlLabel.trim.isEmpty)(
          s"example '${entry.descriptor.id}' has no reset control."
        )
      ).flatten
    }
    (duplicateIds ++ catalogErrors ++ metadataErrors).toVector.sorted
end ExampleRegistry
