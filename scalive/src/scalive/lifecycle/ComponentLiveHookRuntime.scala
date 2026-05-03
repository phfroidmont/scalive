package scalive

import zio.*

final private[scalive] class ComponentLiveHookRuntime(ref: Ref[LiveHookRuntimeState])
    extends SocketLiveHookRuntime(ref)
