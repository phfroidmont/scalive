package scalive

import zio.*
import zio.stream.*

final case class LiveContext(staticChanged: Boolean)
object LiveContext:
  def staticChanged: URIO[LiveContext, Boolean] = ZIO.serviceWith[LiveContext](_.staticChanged)

trait LiveView[Msg, Model]:
  def init: Model | RIO[LiveContext, Model]
  def update(model: Model): Msg => Model | RIO[LiveContext, Model]
  def view(model: Dyn[Model]): HtmlElement
  def subscriptions(model: Model): ZStream[LiveContext, Nothing, Msg]
