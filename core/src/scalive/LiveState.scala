package scalive

final case class LiveState private (val data: Map[LiveState.Key, LiveState.Entry[Any]]):
  def get(k: LiveState.Key): Option[LiveState.Entry[k.Type]] =
    data.get(k).asInstanceOf[Option[LiveState.Entry[k.Type]]]
  def set(k: LiveState.Key, v: k.Type): LiveState =
    copy(data = data.updated(k, LiveState.Entry(true, v)))
  def apply(k: LiveState.Key): LiveState.Entry[k.Type]              = get(k).get
  def update(k: LiveState.Key, update: k.Type => k.Type): LiveState =
    copy(data =
      data.updatedWith(k)(
        _.asInstanceOf[Option[LiveState.Entry[k.Type]]]
          .map(e => LiveState.Entry(true, update(e.value)))
      )
    )
  def remove(k: LiveState.Key): LiveState = copy(data = data - k)
  def setAllUnchanged: LiveState          =
    LiveState(data.view.mapValues(_.copy(changed = false)).toMap)

object LiveState:
  final case class Entry[T](changed: Boolean, value: T)

  val empty = LiveState(Map.empty)

  class Key:
    type Type
    def id: Dyn[Type]                  = Dyn(this, identity)
    def apply[T](f: Type => T): Dyn[T] = Dyn(this, f)
  object Key:
    def apply[T] = new Key:
      type Type = T
