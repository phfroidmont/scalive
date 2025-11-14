package scalive

import zio.*
import zio.stream.*

trait LiveView[Msg, Model]:
  def init: Model | Task[Model]
  def update(model: Model): Msg => Model | Task[Model]
  def view(model: Dyn[Model]): HtmlElement
  def subscriptions(model: Model): ZStream[Any, Nothing, Msg]
