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
  def behaviorTestId: String
  def resetMessage: Any
  def resetControlLabel: String
  def render(instanceId: String): Mod[Nothing]
  def projectMessage(value: Any): Option[ExampleTraceValue]
  def projectModel(value: Any): Option[ExampleTraceValue]

final private class ExampleEntry[Msg: LiveMessageTag, Model: ClassTag](
  val descriptor: ExampleDescriptor,
  factory: () => LiveView[Msg, Model],
  reset: ExampleReset[Msg],
  traces: ExampleTraceProjectors[Msg, Model],
  val behaviorTestId: String)
    extends RegisteredExample:
  private val messageTag = summon[LiveMessageTag[Msg]].classTag
  private val modelTag   = summon[ClassTag[Model]]

  val resetControlLabel: String = reset.controlLabel
  val resetMessage: Any         = reset.message

  def render(instanceId: String): Mod[Nothing] =
    liveView(instanceId, factory(), sticky = false)

  def projectMessage(value: Any): Option[ExampleTraceValue] =
    messageTag.unapply(value).map(traces.message.project)

  def projectModel(value: Any): Option[ExampleTraceValue] =
    modelTag.unapply(value).map(traces.model.project)

private[docs] object ExampleRegistry:
  private val counter = new ExampleEntry[CounterExample.Msg, CounterExample.Model](
    descriptor = ExampleCatalog.Counter,
    factory = () => new CounterExample,
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
    ),
    behaviorTestId = "counter-behavior"
  )

  private val shoppingCart =
    new ExampleEntry[ShoppingCartExample.Msg, ShoppingCartExample.Model](
      descriptor = ExampleCatalog.ShoppingCart,
      factory = () => new ShoppingCartExample,
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
      ),
      behaviorTestId = "shopping-cart-behavior"
    )

  val entries: Vector[RegisteredExample] = Vector(counter, shoppingCart)

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
        Option.when(entry.behaviorTestId.trim.isEmpty)(
          s"example '${entry.descriptor.id}' has no behavior test id."
        ),
        Option.when(entry.resetControlLabel.trim.isEmpty)(
          s"example '${entry.descriptor.id}' has no reset control."
        )
      ).flatten
    }
    (duplicateIds ++ catalogErrors ++ metadataErrors).toVector.sorted
end ExampleRegistry
