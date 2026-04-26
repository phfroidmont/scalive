package scalive

import scala.reflect.ClassTag

import zio.*
import zio.http.URL
import zio.json.*

final private[scalive] case class NestedLiveViewSpec[Msg, Model](
  id: String,
  liveView: LiveView[Msg, Model],
  msgClassTag: ClassTag[Msg])

final private[scalive] case class NestedLiveViewRegistration(
  id: String,
  parentTopic: String,
  topic: String,
  session: String)

trait NestedLiveViewRuntime:
  def register[Msg, Model](
    spec: NestedLiveViewSpec[Msg, Model]
  ): Task[NestedLiveViewRegistration]

object NestedLiveViewRuntime:
  object Disabled extends NestedLiveViewRuntime:
    def register[Msg, Model](
      spec: NestedLiveViewSpec[Msg, Model]
    ): Task[NestedLiveViewRegistration] =
      ZIO.fail(new IllegalStateException("nested LiveViews require a connected LiveView runtime"))

final private[scalive] case class NestedLiveViewEntry(
  parentTopic: String,
  token: String,
  start: (LiveContext, WebSocketMessage.Meta, URL) => RIO[Scope, Socket[?, ?]])

final private[scalive] class SocketNestedLiveViewRuntime(
  parentTopic: String,
  tokenConfig: TokenConfig,
  entriesRef: Ref[Map[String, NestedLiveViewEntry]])
    extends NestedLiveViewRuntime:

  def register[Msg, Model](
    spec: NestedLiveViewSpec[Msg, Model]
  ): Task[NestedLiveViewRegistration] =
    val topic = s"$parentTopic-${spec.id}"
    for
      token = Token.sign(tokenConfig.secret, topic, "nested")
      entry = NestedLiveViewEntry(
                parentTopic = parentTopic,
                token = token,
                start = (ctx, meta, initialUrl) =>
                  Socket.start(
                    topic,
                    token,
                    spec.liveView,
                    ctx,
                    meta,
                    tokenConfig,
                    initialUrl
                  )(using spec.msgClassTag)
              )
      _ <- entriesRef.update(_.updated(topic, entry))
    yield NestedLiveViewRegistration(spec.id, parentTopic, topic, token)
