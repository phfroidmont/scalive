package scalive.docs.examples

import java.nio.charset.StandardCharsets
import java.util.Base64
import scala.reflect.ClassTag

import scalive.*
import scalive.docs.model.{ExampleCatalog, ExampleDescriptor}
import scalive.docs.trace.{ProjectedScalaValue, ProjectedScalaValueFormatter}

final private[docs] case class ExampleTraceValue(
  typeName: String,
  summary: String,
  fields: Vector[(String, String)] = Vector.empty,
  scalaValue: Option[String] = None):
  require(scalaValue.exists(_.trim.nonEmpty), "Example trace values require Scala code")

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
  import ProjectedScalaValue.*

  private val asyncReport =
    new ExampleEntry[AsyncReportExample.Msg, AsyncReportExample.Model](
      descriptor = ExampleCatalog.AsyncReport,
      factory = instanceId => new AsyncReportExample(instanceId),
      reset = ExampleReset(AsyncReportExample.Msg.Reset, "Reset async report"),
      traces = ExampleTraceProjectors(
        message = new ExampleTraceProjector[AsyncReportExample.Msg]:
          def project(value: AsyncReportExample.Msg) = value match
            case AsyncReportExample.Msg.RunSuccess =>
              traced(
                "AsyncReportExample.Msg",
                "Start the successful report",
                "AsyncReportExample.Msg.RunSuccess"
              )
            case AsyncReportExample.Msg.RunFailure =>
              traced(
                "AsyncReportExample.Msg",
                "Start the failing report",
                "AsyncReportExample.Msg.RunFailure"
              )
            case AsyncReportExample.Msg.Replace =>
              traced(
                "AsyncReportExample.Msg",
                "Replace active report work",
                "AsyncReportExample.Msg.Replace"
              )
            case AsyncReportExample.Msg.Retry =>
              traced("AsyncReportExample.Msg", "Retry report work", "AsyncReportExample.Msg.Retry")
            case AsyncReportExample.Msg.Cancel =>
              traced(
                "AsyncReportExample.Msg",
                "Cancel active report work",
                "AsyncReportExample.Msg.Cancel"
              )
            case AsyncReportExample.Msg.Reset =>
              traced(
                "AsyncReportExample.Msg",
                "Reset async report state",
                "AsyncReportExample.Msg.Reset"
              )
            case AsyncReportExample.Msg.ReportCompleted(result) =>
              projected(
                "AsyncReportExample.Msg.ReportCompleted",
                asyncResultLabel(result),
                constructor("Msg.ReportCompleted", asyncResultScala(result))
              ),
        model = new ExampleTraceProjector[AsyncReportExample.Model]:
          def project(value: AsyncReportExample.Model) =
            projected(
              "AsyncReportExample.Model",
              "Current async report state",
              constructor("Model", field("report", asyncValueScala(value.report))),
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
              traced(
                "ActivityStreamExample.Msg",
                "Insert one activity",
                "ActivityStreamExample.Msg.Add"
              )
            case ActivityStreamExample.Msg.Delete(activity) =>
              projected(
                "ActivityStreamExample.Msg.Delete",
                "Delete one activity",
                constructor(
                  "Msg.Delete",
                  constructor(
                    "Activity",
                    field("id", number(activity.id)),
                    field("category", wildcard),
                    field("summary", wildcard)
                  )
                ),
                Vector("activityId" -> activity.id.toString)
              )
            case ActivityStreamExample.Msg.Reset =>
              traced(
                "ActivityStreamExample.Msg",
                "Reset the activity stream",
                "ActivityStreamExample.Msg.Reset"
              ),
        model = new ExampleTraceProjector[ActivityStreamExample.Model]:
          def project(value: ActivityStreamExample.Model) =
            projected(
              "ActivityStreamExample.Model",
              "Current durable activity state",
              constructor(
                "Model",
                field("activities", wildcard),
                field("activityStream", wildcard),
                field("nextId", number(value.nextId))
              ),
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
            traced("CounterExample.Msg", "Decrease the count", "CounterExample.Msg.Decrement")
          case CounterExample.Msg.Increment =>
            traced("CounterExample.Msg", "Increase the count", "CounterExample.Msg.Increment")
          case CounterExample.Msg.Reset =>
            traced("CounterExample.Msg", "Reset the count", "CounterExample.Msg.Reset"),
      model = new ExampleTraceProjector[CounterExample.Model]:
        def project(value: CounterExample.Model) =
          projected(
            "CounterExample.Model",
            "Current counter state",
            constructor("Model", field("count", number(value.count))),
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
              traced(
                "BrowserInteropExample.Msg",
                "Request a browser clipboard write",
                "BrowserInteropExample.Msg.CopySample"
              )
            case BrowserInteropExample.Msg.Reset =>
              traced(
                "BrowserInteropExample.Msg",
                "Reset browser integration",
                "BrowserInteropExample.Msg.Reset"
              ),
        model = new ExampleTraceProjector[BrowserInteropExample.Model]:
          def project(value: BrowserInteropExample.Model) =
            projected(
              "BrowserInteropExample.Model",
              "Current browser operation state",
              constructor(
                "Model",
                field("requestNumber", number(value.requestNumber)),
                field("operation", browserOperationScala(value.operation))
              ),
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
              projected(
                "ShoppingCartExample.Msg.Add",
                "Add one product",
                constructor("Msg.Add", shoppingProductScala(product)),
                Vector("product" -> product.sku)
              )
            case ShoppingCartExample.Msg.Remove(product) =>
              projected(
                "ShoppingCartExample.Msg.Remove",
                "Remove one product",
                constructor("Msg.Remove", shoppingProductScala(product)),
                Vector("product" -> product.sku)
              )
            case ShoppingCartExample.Msg.Clear =>
              traced("ShoppingCartExample.Msg", "Clear the cart", "ShoppingCartExample.Msg.Clear"),
        model = new ExampleTraceProjector[ShoppingCartExample.Model]:
          def project(value: ShoppingCartExample.Model) =
            val quantities =
              value.lines.map(line => s"${line.product.sku}=${line.quantity}").mkString(", ")
            projected(
              "ShoppingCartExample.Model",
              "Current cart state",
              shoppingModelScala(value),
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
              traced(
                "SubscriptionClockExample.Msg",
                "Start the clock subscription",
                "SubscriptionClockExample.Msg.Start"
              )
            case SubscriptionClockExample.Msg.Replace =>
              traced(
                "SubscriptionClockExample.Msg",
                "Replace the clock subscription",
                "SubscriptionClockExample.Msg.Replace"
              )
            case SubscriptionClockExample.Msg.Cancel =>
              traced(
                "SubscriptionClockExample.Msg",
                "Cancel the clock subscription",
                "SubscriptionClockExample.Msg.Cancel"
              )
            case SubscriptionClockExample.Msg.Reset =>
              traced(
                "SubscriptionClockExample.Msg",
                "Reset the clock subscription",
                "SubscriptionClockExample.Msg.Reset"
              )
            case SubscriptionClockExample.Msg.Tick(_) =>
              projected(
                "SubscriptionClockExample.Msg.Tick",
                "Receive one clock tick",
                constructor("Msg.Tick", field("at", wildcard))
              ),
        model = new ExampleTraceProjector[SubscriptionClockExample.Model]:
          def project(value: SubscriptionClockExample.Model) =
            projected(
              "SubscriptionClockExample.Model",
              "Current clock subscription state",
              constructor(
                "Model",
                field("mode", clockModeScala(value.mode)),
                field("lastTick", wildcard),
                field("tickCount", number(value.tickCount))
              ),
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
              projected(
                "ReportsExample.Msg.Select",
                "Select a report supplied by the service",
                constructor(
                  "Msg.Select",
                  constructor(
                    "Report",
                    field("id", number(report.id)),
                    field("title", wildcard),
                    field("summary", wildcard)
                  )
                ),
                Vector("reportId" -> report.id.toString)
              )
            case ReportsExample.Msg.ResetSelection =>
              traced(
                "ReportsExample.Msg",
                "Reset the selected report",
                "ReportsExample.Msg.ResetSelection"
              )
            case ReportsExample.Msg.Refresh =>
              traced(
                "ReportsExample.Msg",
                "Refresh reports from the service",
                "ReportsExample.Msg.Refresh"
              ),
        model = new ExampleTraceProjector[ReportsExample.Model]:
          def project(value: ReportsExample.Model) = value match
            case ReportsExample.Model.Loaded(reports, selected) =>
              projected(
                "ReportsExample.Model.Loaded",
                "Loaded reports with one selected report",
                constructor(
                  "Model.Loaded",
                  field("reports", wildcard),
                  field("selected", wildcard)
                ),
                Vector(
                  "reportCount"      -> reports.size.toString,
                  "selectedReportId" -> selected.id.toString
                )
              )
            case ReportsExample.Model.Empty =>
              traced("ReportsExample.Model", "No reports available", "ReportsExample.Model.Empty")
            case ReportsExample.Model.Failed =>
              traced("ReportsExample.Model", "Report loading failed", "ReportsExample.Model.Failed")
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
              traced(
                "LifecycleExample.Msg",
                "Put a keyed notification",
                "LifecycleExample.Msg.PutNotification"
              )
            case LifecycleExample.Msg.ClearNotification =>
              traced(
                "LifecycleExample.Msg",
                "Clear the keyed notification",
                "LifecycleExample.Msg.ClearNotification"
              )
            case LifecycleExample.Msg.RequestAttention =>
              traced(
                "LifecycleExample.Msg",
                "Change the projected page title",
                "LifecycleExample.Msg.RequestAttention"
              )
            case LifecycleExample.Msg.Reset =>
              traced("LifecycleExample.Msg", "Reset lifecycle state", "LifecycleExample.Msg.Reset"),
        model = new ExampleTraceProjector[LifecycleExample.Model]:
          def project(value: LifecycleExample.Model) =
            projected(
              "LifecycleExample.Model",
              "Current lifecycle state",
              constructor(
                "Model",
                field("connectedMount", boolean(value.connectedMount)),
                field("currentTitle", string(value.currentTitle))
              ),
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
              formEventTrace("Validate the profile form", "Validate", event)
            case ProfileFormExample.Msg.Save(event) =>
              formEventTrace("Submit the profile form", "Save", event)
            case ProfileFormExample.Msg.Reset =>
              traced("ProfileFormExample.Msg", "Reset the form", "ProfileFormExample.Msg.Reset"),
        model = new ExampleTraceProjector[ProfileFormExample.Model]:
          def project(value: ProfileFormExample.Model) =
            projected(
              "ProfileFormExample.Model",
              "Current profile form state",
              constructor(
                "Model",
                field("form", wildcard),
                field("saved", wildcard)
              ),
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
              projected(
                "NavigationExample.Msg.Select",
                "Select typed search parameters",
                constructor("Msg.Select", navigationPresetScala(query)),
                Vector("preset" -> query.label)
              )
            case NavigationExample.Msg.Reset =>
              traced(
                "NavigationExample.Msg",
                "Reset navigation state",
                "NavigationExample.Msg.Reset"
              ),
        model = new ExampleTraceProjector[NavigationExample.Model]:
          def project(value: NavigationExample.Model) =
            projected(
              "NavigationExample.Model",
              "Current typed search destination",
              constructor("Model", field("query", navigationPresetScala(value.query))),
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
              projected(
                "VotingComponentsExample.Msg.ComponentReported",
                "Component reported a vote count",
                constructor(
                  "Msg.ComponentReported",
                  field("id", string(id)),
                  field("votes", number(votes))
                ),
                Vector("componentId" -> id, "votes" -> votes.toString)
              )
            case VotingComponentsExample.Msg.UpdateScalaProps =>
              traced(
                "VotingComponentsExample.Msg",
                "Parent updated component props",
                "VotingComponentsExample.Msg.UpdateScalaProps"
              )
            case VotingComponentsExample.Msg.Reset =>
              traced(
                "VotingComponentsExample.Msg",
                "Reset voting components",
                "VotingComponentsExample.Msg.Reset"
              ),
        model = new ExampleTraceProjector[VotingComponentsExample.Model]:
          def project(value: VotingComponentsExample.Model) =
            projected(
              "VotingComponentsExample.Model",
              "Current parent component state",
              constructor(
                "Model",
                field("scalaRevision", number(value.scalaRevision)),
                field("resetEpoch", number(value.resetEpoch)),
                field("status", wildcard)
              ),
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
              traced(
                "TextUploadExample.Msg",
                "Validate selected upload metadata",
                "TextUploadExample.Msg.Validate"
              )
            case TextUploadExample.Msg.Progress =>
              traced(
                "TextUploadExample.Msg",
                "Refresh upload progress",
                "TextUploadExample.Msg.Progress"
              )
            case TextUploadExample.Msg.Cancel(_) =>
              projected(
                "TextUploadExample.Msg.Cancel",
                "Cancel one upload entry",
                constructor("Msg.Cancel", field("entry", wildcard))
              )
            case TextUploadExample.Msg.Summarize =>
              traced(
                "TextUploadExample.Msg",
                "Summarize completed text",
                "TextUploadExample.Msg.Summarize"
              )
            case TextUploadExample.Msg.Reset =>
              traced("TextUploadExample.Msg", "Reset upload state", "TextUploadExample.Msg.Reset"),
        model = new ExampleTraceProjector[TextUploadExample.Model]:
          def project(value: TextUploadExample.Model) =
            projected(
              "TextUploadExample.Model",
              "Current upload lifecycle state",
              constructor(
                "Model",
                field("upload", wildcard),
                field("summaries", wildcard),
                field("notice", wildcard)
              ),
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

  def traceViewerInstanceId(pageRoute: String, directiveId: String): String =
    instanceId(pageRoute, directiveId).replace("docs-example-", "docs-trace-")

  def traceViewerTopic(pageRoute: String, directiveId: String): String =
    s"lv:${traceViewerInstanceId(pageRoute, directiveId)}"

  private def money(cents: Int): String =
    val dollars   = cents / 100
    val remainder = cents % 100
    f"$$$dollars%d.$remainder%02d"

  private def formEventTrace(
    summary: String,
    caseName: String,
    event: FormEvent[ProfileFormExample.Profile]
  ): ExampleTraceValue =
    projected(
      s"ProfileFormExample.Msg.$caseName",
      summary,
      constructor(s"Msg.$caseName", field("event", wildcard)),
      Vector(
        "valid"      -> event.isValid.toString,
        "submitted"  -> event.submitted.toString,
        "target"     -> event.target.fold("none")(_.name),
        "usedFields" -> event.state.used.size.toString
      )
    )

  private def traced(typeName: String, summary: String, scalaValue: String): ExampleTraceValue =
    val owner = typeName.takeWhile(_ != '.')
    projected(scalaValue, summary, name(scalaValue.stripPrefix(s"$owner.")))

  private def projected(
    typeName: String,
    summary: String,
    value: ProjectedScalaValue,
    fields: Vector[(String, String)] = Vector.empty
  ): ExampleTraceValue =
    ExampleTraceValue(
      typeName,
      summary,
      fields,
      scalaValue = Some(ProjectedScalaValueFormatter.format(value))
    )

  private def asyncResultScala(result: LiveAsyncResult[?]): ProjectedScalaValue = result match
    case LiveAsyncResult.Succeeded(_) => constructor("LiveAsyncResult.Succeeded", wildcard)
    case LiveAsyncResult.Failed(_)    => constructor("LiveAsyncResult.Failed", wildcard)
    case LiveAsyncResult.Cancelled(_) => constructor("LiveAsyncResult.Cancelled", wildcard)

  private def asyncValueScala(value: AsyncValue[?]): ProjectedScalaValue = value match
    case AsyncValue.Empty           => name("AsyncValue.Empty")
    case AsyncValue.Loading(_)      => constructor("AsyncValue.Loading", wildcard)
    case AsyncValue.Ok(_)           => constructor("AsyncValue.Ok", wildcard)
    case AsyncValue.Failed(_, _)    => constructor("AsyncValue.Failed", wildcard, wildcard)
    case AsyncValue.Cancelled(_, _) => constructor("AsyncValue.Cancelled", wildcard, wildcard)

  private def browserOperationScala(
    value: BrowserInteropExample.CopyOperation
  ): ProjectedScalaValue =
    value match
      case BrowserInteropExample.CopyOperation.Idle =>
        name("CopyOperation.Idle")
      case BrowserInteropExample.CopyOperation.Pending(_) =>
        constructor("CopyOperation.Pending", wildcard)
      case BrowserInteropExample.CopyOperation.Succeeded =>
        name("CopyOperation.Succeeded")
      case BrowserInteropExample.CopyOperation.Failed =>
        name("CopyOperation.Failed")

  private def shoppingProductScala(value: ShoppingCartExample.Product): ProjectedScalaValue =
    value match
      case ShoppingCartExample.Product.Coffee   => name("Product.Coffee")
      case ShoppingCartExample.Product.Notebook => name("Product.Notebook")
      case ShoppingCartExample.Product.Sticker  => name("Product.Sticker")

  private def shoppingModelScala(value: ShoppingCartExample.Model): ProjectedScalaValue =
    val lines = value.lines.map { line =>
      constructor(
        "Line",
        shoppingProductScala(line.product),
        field("quantity", number(line.quantity))
      )
    }
    constructor("Model", field("lines", vector(lines*)))

  private def clockModeScala(value: SubscriptionClockExample.Mode): ProjectedScalaValue =
    value match
      case SubscriptionClockExample.Mode.Stopped =>
        name("Mode.Stopped")
      case SubscriptionClockExample.Mode.EverySecond =>
        name("Mode.EverySecond")
      case SubscriptionClockExample.Mode.FourTimesPerSecond =>
        name("Mode.FourTimesPerSecond")

  private def navigationPresetScala(
    value: NavigationExample.SearchPreset
  ): ProjectedScalaValue = value match
    case NavigationExample.SearchPreset.LiveView =>
      name("SearchPreset.LiveView")
    case NavigationExample.SearchPreset.Streams =>
      name("SearchPreset.Streams")
    case NavigationExample.SearchPreset.TypedForms =>
      name("SearchPreset.TypedForms")

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
