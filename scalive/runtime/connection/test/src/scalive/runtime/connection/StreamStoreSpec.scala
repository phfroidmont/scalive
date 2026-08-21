package scalive.runtime.connection

import scala.util.Try

import scalive.streams.*
import zio.test.*

object StreamStoreSpec extends ZIOSpecDefault:
  final case class Item(id: String, value: Int)

  private def definition(
    name: String = "items",
    limit: Option[StreamLimit] = None
  ): LiveStreamDef[Item] =
    LiveStreamDef(name, _.id, limit)

  private def values(stream: LiveStream[Item]): Vector[(String, Int)] =
    stream.entries.map(entry => entry.domId -> entry.value.value)

  private def fails(body: => Any): Boolean = Try(body).isFailure

  override def spec = suite("StreamStoreSpec")(
    test("create builds an identity, snapshot, and deduplicated insertion journal") {
      val streamDef = definition()
      val created = StreamStore.empty.create(
        streamDef,
        List(Item("a", 1), Item("b", 2), Item("a", 3))
      )

      assertTrue(
        created.stream.name == "items",
        created.stream.generation == 1L,
        values(created.stream) == Vector("a" -> 3, "b" -> 2),
        created.stream.inserted.map(_.entry.domId) == Vector("b", "a"),
        created.stream.inserted.forall(_.at == StreamAt.Last),
        created.stream.deleted.isEmpty,
        !created.stream.reset,
        created.store.get(streamDef).contains(created.stream)
      )
    },
    test("new ids use positions while existing ids update without moving") {
      val streamDef = definition()
      val created = StreamStore.empty.create(streamDef, List(Item("a", 1), Item("b", 2)))
      val first   = created.store.insert(streamDef, Item("c", 3), StreamAt.First)
      val indexed = first.store.insert(streamDef, Item("d", 4), StreamAt.Index(2))
      val updated = indexed.store.insert(streamDef, Item("a", 9), StreamAt.Last)

      assertTrue(
        values(first.stream) == Vector("c" -> 3, "a" -> 1, "b" -> 2),
        values(indexed.stream) == Vector("c" -> 3, "a" -> 1, "d" -> 4, "b" -> 2),
        values(updated.stream) == Vector("c" -> 3, "a" -> 9, "d" -> 4, "b" -> 2),
        updated.stream.inserted.last.at == StreamAt.Last
      )
    },
    test("bulk fixed-position insertion is repeated insertion and newest duplicate wins") {
      val streamDef = definition()
      val created   = StreamStore.empty.create(streamDef, Nil)
      val inserted = created.store.insertAll(
        streamDef,
        List(Item("a", 1), Item("b", 2), Item("a", 3)),
        StreamAt.First
      )

      assertTrue(
        values(inserted.stream) == Vector("b" -> 2, "a" -> 3),
        inserted.stream.inserted.map(_.entry.value.value) == Vector(2, 3),
        inserted.stream.inserted.forall(_.at == StreamAt.First)
      )
    },
    test("limits retain the requested side and preserve semantic operations") {
      val firstDef = definition("first", Some(StreamLimit.KeepFirst(2)))
      val lastDef  = definition("last", Some(StreamLimit.KeepLast(2)))
      val first    = StreamStore.empty.create(firstDef, List(Item("a", 1), Item("b", 2), Item("c", 3)))
      val last     = StreamStore.empty.create(lastDef, List(Item("a", 1), Item("b", 2), Item("c", 3)))

      assertTrue(
        values(first.stream) == Vector("a" -> 1, "b" -> 2),
        values(last.stream) == Vector("b" -> 2, "c" -> 3),
        first.stream.inserted.map(_.entry.domId) == Vector("a", "b", "c"),
        last.stream.inserted.map(_.entry.domId) == Vector("a", "b", "c"),
        first.stream.inserted.last.limit.contains(StreamLimit.KeepFirst(2)),
        last.stream.inserted.last.limit.contains(StreamLimit.KeepLast(2))
      )
    },
    test("reset and deletes replace handles and advance one generation per operation") {
      val streamDef = definition()
      val created = StreamStore.empty.create(streamDef, List(Item("a", 1), Item("b", 2)))
      val reset   = created.store.reset(streamDef, List(Item("c", 3)), StreamAt.First)
      val deleted = reset.store.delete(streamDef, Item("c", 0))
      val byId    = deleted.store.deleteByDomId(streamDef, "missing")

      assertTrue(
        created.stream.generation == 1L,
        reset.stream.generation == 2L,
        deleted.stream.generation == 3L,
        byId.stream.generation == 4L,
        created.stream.identity.eq(byId.stream.identity),
        reset.stream.reset,
        values(deleted.stream).isEmpty,
        byId.stream.deleted == Vector("c", "missing")
      )
    },
    test("delete followed by insert retains both operations so the browser can reorder the id") {
      val streamDef = definition()
      val created = StreamStore.empty.create(
        streamDef,
        List(Item("a", 1), Item("b", 2), Item("c", 3))
      )
      val committed = created.store.prune(streamDef)
      val deleted  = committed.store.deleteByDomId(streamDef, "b")
      val inserted = deleted.store.insert(streamDef, Item("b", 4), StreamAt.First)

      assertTrue(
        values(inserted.stream) == Vector("b" -> 4, "a" -> 1, "c" -> 3),
        inserted.stream.deleted == Vector("b"),
        inserted.stream.inserted.map(_.entry.domId) == Vector("b")
      )
    },
    test("updateOnly ignores a missing id but updates one already present") {
      val streamDef = definition()
      val created = StreamStore.empty.create(streamDef, List(Item("a", 1)))
      val missing = created.store.insert(streamDef, Item("b", 2), updateOnly = true)
      val existing = missing.store.insert(streamDef, Item("a", 3), updateOnly = true)

      assertTrue(
        values(missing.stream) == Vector("a" -> 1),
        missing.stream.generation == 2L,
        values(existing.stream) == Vector("a" -> 3),
        existing.stream.generation == 3L
      )
    },
    test("prune clears journals without changing identity, generation, or snapshots") {
      val streamDef = definition()
      val created = StreamStore.empty.create(streamDef, List(Item("a", 1)))
      val changed = created.store.insert(streamDef, Item("b", 2)).store.deleteByDomId(streamDef, "a")
      val pruned  = changed.store.prune(streamDef)
      val all     = changed.store.prune.get(streamDef).get

      assertTrue(
        pruned.stream.identity.eq(changed.stream.identity),
        pruned.stream.generation == changed.stream.generation,
        values(pruned.stream) == values(changed.stream),
        pruned.stream.inserted.isEmpty,
        pruned.stream.deleted.isEmpty,
        !pruned.stream.reset,
        all.identity.eq(changed.stream.identity),
        values(all) == values(changed.stream)
      )
    },
    test("definitions are DOM-id coherent and stores reject duplicate or missing targets") {
      val coherent     = definition()
      val incompatible = LiveStreamDef[Item]("items", item => s"other-${item.id}")
      val created      = StreamStore.empty.create(coherent, Nil)
      val limited = created.store.insert(
        coherent.keepFirst(1),
        Item("a", 1)
      )

      assertTrue(
        fails(created.store.create(coherent, Nil)),
        fails(created.store.insert(incompatible, Item("a", 1))),
        fails(StreamStore.empty.insert(coherent, Item("a", 1))),
        created.store.get(coherent).contains(created.stream),
        fails(created.store.get(incompatible)),
        values(limited.stream) == Vector("a" -> 1)
      )
    },
    test("validates names, ids, positions, limits, and throwing id functions") {
      val emptyName   = definition("")
      val emptyId     = LiveStreamDef[Item]("items", _ => "")
      val throwingId  = LiveStreamDef[Item]("items", _ => throw RuntimeException("boom"))
      val badFirst    = definition(limit = Some(StreamLimit.KeepFirst(0)))
      val badLast     = definition(limit = Some(StreamLimit.KeepLast(Int.MinValue)))
      val streamDef   = definition()
      val created     = StreamStore.empty.create(streamDef, Nil)

      assertTrue(
        fails(StreamStore.empty.create(emptyName, Nil)),
        fails(StreamStore.empty.create(emptyId, List(Item("", 1)))),
        fails(StreamStore.empty.create(throwingId, List(Item("a", 1)))),
        fails(StreamStore.empty.create(badFirst, Nil)),
        fails(StreamStore.empty.create(badLast, Nil)),
        fails(created.store.insert(streamDef, Item("a", 1), StreamAt.Index(-1))),
        fails(created.store.deleteByDomId(streamDef, "")),
        values(created.stream).isEmpty
      )
    }
  )
end StreamStoreSpec
