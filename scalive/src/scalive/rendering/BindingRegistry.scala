package scalive

import scala.reflect.ClassTag

private[scalive] object BindingRegistry:
  type Handler[Msg] = BindingHandler[Msg]

  def collect[Msg: ClassTag](root: HtmlElement[Msg]): Map[String, Handler[Msg]] =
    collect(RenderSnapshot.compile(root))

  def collect[Msg: ClassTag](compiled: RenderSnapshot.Compiled): Map[String, Handler[Msg]] =
    compiled.bindings.iterator.map { case (id, handler) =>
      id -> BindingHandler(payload => toMessage(id, handler(payload)))
    }.toMap
  end collect

  private def toMessage[Msg](
    bindingId: String,
    value: Any
  )(using
    tag: ClassTag[Msg]
  ): Either[String, Msg] =
    tag
      .unapply(value).toRight(
        s"Binding '$bindingId' produced ${valueType(value)}, expected ${tag.runtimeClass.getName}"
      )

  private def valueType(value: Any): String =
    Option(value).map(_.getClass.getName).getOrElse("null")
