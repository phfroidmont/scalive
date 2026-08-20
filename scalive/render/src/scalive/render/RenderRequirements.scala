package scalive.render

import scalive.ComponentRef
import scalive.HtmlElement
import scalive.LiveComponent
import scalive.LiveView
import scalive.Signal
import scalive.streams.LiveStream

/** One evaluated component declaration awaiting runtime instance resolution. */
sealed trait ComponentRequirement[+OwnerMsg]:
  type Props
  type Message
  type Model
  type Output

  def location: TemplateId
  def applicationId: String
  def definition: LiveComponent[Props, Message, Model]
  def props: Props
  def outputMapper: Option[Output => OwnerMsg]

  final def resolve(
    ref: ComponentRef[Message],
    instanceToken: Object,
    child: RenderCandidate[Message]
  ): ComponentResolution =
    ComponentResolution.Value(location, applicationId, ref, instanceToken, child.tree)

object ComponentRequirement:
  final private[render] case class Plain[Props0, Message0, Model0](
    location: TemplateId,
    applicationId: String,
    definition: LiveComponent[Props0, Message0, Model0],
    props: Props0)
      extends ComponentRequirement[Nothing]:
    type Props   = Props0
    type Message = Message0
    type Model   = Model0
    type Output  = Nothing
    val outputMapper: Option[Nothing => Nothing] = None

  final private[render] case class WithOutput[Props0, Message0, Model0, Output0, OwnerMsg](
    location: TemplateId,
    applicationId: String,
    definition: LiveComponent.WithOutput[Props0, Message0, Model0, Output0],
    props: Props0,
    mapper: Output0 => OwnerMsg)
      extends ComponentRequirement[OwnerMsg]:
    type Props   = Props0
    type Message = Message0
    type Model   = Model0
    type Output  = Output0
    val outputMapper: Option[Output0 => OwnerMsg] = Some(mapper)

/** A typed runtime answer for one component requirement. */
sealed trait ComponentResolution:
  type Message
  def location: TemplateId
  def applicationId: String
  def ref: ComponentRef[Message]
  def instanceToken: Object
  def child: EvaluatedTree

object ComponentResolution:
  final private[render] case class Value[Message0](
    location: TemplateId,
    applicationId: String,
    ref: ComponentRef[Message0],
    instanceToken: Object,
    child: EvaluatedTree)
      extends ComponentResolution:
    type Message = Message0

/** One nested LiveView declaration. Runtime code owns construction and topology. */
sealed trait NestedRequirement:
  type Message
  type Model
  def location: TemplateId
  def applicationId: String
  def sticky: Boolean
  def linkParentOnCrash: Boolean
  def create(): LiveView[Message, Model]

object NestedRequirement:
  final private[render] case class Value[Message0, Model0](
    location: TemplateId,
    applicationId: String,
    sticky: Boolean,
    linkParentOnCrash: Boolean,
    factory: () => LiveView[Message0, Model0])
      extends NestedRequirement:
    type Message = Message0
    type Model   = Model0
    def create(): LiveView[Message0, Model0] = factory()

/** Opaque stream handle plus the declaration projection retained for later runtime integration. */
sealed trait StreamRequirement[+OwnerMsg]:
  type Item
  def location: TemplateId
  def stream: LiveStream[Item]
  def staticProject: Option[(String, Item) => HtmlElement[OwnerMsg]]
  def signalProject: Option[(String, Signal[Item]) => HtmlElement[OwnerMsg]]

object StreamRequirement:
  final private[render] case class Static[Item0, OwnerMsg](
    location: TemplateId,
    stream: LiveStream[Item0],
    project: (String, Item0) => HtmlElement[OwnerMsg])
      extends StreamRequirement[OwnerMsg]:
    type Item = Item0
    val staticProject = Some(project)
    val signalProject = None

  final private[render] case class SignalBacked[Item0, OwnerMsg](
    location: TemplateId,
    stream: LiveStream[Item0],
    project: (String, Signal[Item0]) => HtmlElement[OwnerMsg])
      extends StreamRequirement[OwnerMsg]:
    type Item = Item0
    val staticProject = None
    val signalProject = Some(project)
