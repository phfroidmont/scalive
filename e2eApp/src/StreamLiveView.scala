import java.util.UUID

import zio.*
import zio.http.URL
import zio.json.ast.Json

import scalive.*
import scalive.codecs.BooleanAsAttrPresenceEncoder

class StreamLiveView()
    extends LiveView.Routed[StreamLiveView.Msg, StreamLiveView.Model, Option[String]]:
  import StreamLiveView.*

  private val onlyChild = htmlAttr("only-child", BooleanAsAttrPresenceEncoder)

  def mount(_params: Option[String], ctx: MountContext) =
    for
      _ <- ctx.streams.create(UsersStreamDef, InitialUsers)
      _ <- ctx.streams.create(AdminsStreamDef, InitialAdmins)
      _ <- ctx.streams.create(ComponentUsersStreamDef, InitialUsers)
    yield Model(count = 0, extraItemWithId = false)

  override def handleParams(model: Model, params: Option[String], _url: URL, ctx: ParamsContext) =
    ZIO.succeed(model.copy(extraItemWithId = params.isDefined))

  def handleMessage(model: Model, ctx: MessageContext) =
    (msg: Msg) => handle(model, msg, ctx.streams)

  override def hooks: LiveHooks[Msg, Model] =
    LiveHooks.empty.onRawEvent { (model, event, ctx) =>
      if event.bindingId != "sandbox:eval" then ZIO.succeed(LiveEventHookResult.cont(model))
      else
        evalCode(event.value) match
          case "socket.view.handle_event(\"reset-users\", %{}, socket)" =>
            handle(model, Msg.ResetUsers, ctx.streams)
              .map(next => LiveEventHookResult.haltReply(next, Json.Obj("result" -> Json.Null)))
          case "socket.view.handle_event(\"append-users\", %{}, socket)" =>
            handle(model, Msg.AppendUsers, ctx.streams)
              .map(next => LiveEventHookResult.haltReply(next, Json.Obj("result" -> Json.Null)))
          case _ => E2ESandboxEval.handle(model, event.bindingId, event.value)
    }

  override def view(model: Signal[Model]) =
    val count = model.map(_.count.toString)

    div(
      div(
        idAttr     := "users",
        phx.update := PhxUpdate.Stream,
        UsersStreamDef.stream { (domId, user) =>
          div(
            idAttr            := domId,
            dataAttr("count") := count,
            user.map(_.name),
            button(
              on.click(user.map(value => Msg.DeleteUser(value.id))),
              phx.value("id") := domId,
              "delete"
            ),
            button(
              on.click(user.map(value => Msg.UpdateUser(s"$UsersPrefix${value.id}"))),
              phx.value("id") := domId,
              "update"
            ),
            button(
              on.click(user.map(value => Msg.MoveUserToFirst(s"$UsersPrefix${value.id}"))),
              phx.value("id") := domId,
              "make first"
            ),
            button(
              on.click(user.map(value => Msg.MoveUserToLast(s"$UsersPrefix${value.id}"))),
              phx.value("id") := domId,
              "make last"
            ),
            button(
              on.click(JS.hide(to = DomSelector.css("#users > *"))),
              "JS Hide"
            )
          )
        },
        Signal.when(model.map(_.extraItemWithId))(
          div(
            idAttr    := "users-empty",
            onlyChild := true,
            "Empty!"
          )
        )
      ),
      div(
        idAttr     := "admins",
        phx.update := PhxUpdate.Stream,
        AdminsStreamDef.stream { (domId, user) =>
          div(
            idAttr            := domId,
            dataAttr("count") := count,
            user.map(_.name),
            button(
              on.click(user.map(value => Msg.DeleteAdmin(value.id))),
              phx.value("id") := domId,
              "delete"
            ),
            button(
              on.click(user.map(value => Msg.UpdateAdmin(s"$AdminsPrefix${value.id}"))),
              phx.value("id") := domId,
              "update"
            ),
            button(
              on.click(user.map(value => Msg.MoveAdminToFirst(s"$AdminsPrefix${value.id}"))),
              phx.value("id") := domId,
              "make first"
            ),
            button(
              on.click(user.map(value => Msg.MoveAdminToLast(s"$AdminsPrefix${value.id}"))),
              phx.value("id") := domId,
              "make last"
            )
          )
        }
      ),
      div(
        idAttr     := "c_users",
        phx.update := PhxUpdate.Stream,
        ComponentUsersStreamDef.stream { (domId, user) =>
          div(
            idAttr := domId,
            user.map(_.name),
            button(
              on.click(
                user.map(value => Msg.DeleteComponentUser(value.id))
              ),
              phx.value("id") := domId,
              "delete"
            ),
            button(
              on.click(
                user.map(value => Msg.UpdateComponentUser(s"$ComponentUsersPrefix${value.id}"))
              ),
              phx.value("id") := domId,
              "update"
            ),
            button(
              on.click(
                user.map(value => Msg.MoveComponentUserToFirst(s"$ComponentUsersPrefix${value.id}"))
              ),
              phx.value("id") := domId,
              "make first"
            ),
            button(
              on.click(
                user.map(value => Msg.MoveComponentUserToLast(s"$ComponentUsersPrefix${value.id}"))
              ),
              phx.value("id") := domId,
              "make last"
            )
          )
        }
      ),
      button(
        on.click(Msg.ResetUsers),
        "Reset"
      ),
      button(
        on.click(Msg.ReorderUsers),
        "Reorder"
      ),
      styleTag(
        "[only-child] {",
        "  display: none;",
        "}",
        "[only-child]:only-child {",
        "  display: block;",
        "}"
      )
    )
  end view

  private def handle(model: Model, msg: Msg, streams: Streams): Task[Model] =
    msg match
      case Msg.DeleteUser(id) =>
        streams.delete(UsersStreamDef, id).as(model)
      case Msg.UpdateUser(domId) =>
        updateUserInStream(model, domId, UsersPrefix, UsersStreamDef, streams)
      case Msg.MoveUserToFirst(domId) =>
        moveUserInStream(model, domId, UsersPrefix, UsersStreamDef, StreamAt.First, streams)
      case Msg.MoveUserToLast(domId) =>
        moveUserInStream(model, domId, UsersPrefix, UsersStreamDef, StreamAt.Last, streams)
      case Msg.DeleteAdmin(id) =>
        streams.delete(AdminsStreamDef, id).as(model)
      case Msg.UpdateAdmin(domId) =>
        updateUserInStream(model, domId, AdminsPrefix, AdminsStreamDef, streams)
      case Msg.MoveAdminToFirst(domId) =>
        moveUserInStream(model, domId, AdminsPrefix, AdminsStreamDef, StreamAt.First, streams)
      case Msg.MoveAdminToLast(domId) =>
        moveUserInStream(model, domId, AdminsPrefix, AdminsStreamDef, StreamAt.Last, streams)
      case Msg.DeleteComponentUser(id) =>
        streams.delete(ComponentUsersStreamDef, id).as(model)
      case Msg.UpdateComponentUser(domId) =>
        updateUserInStream(model, domId, ComponentUsersPrefix, ComponentUsersStreamDef, streams)
      case Msg.MoveComponentUserToFirst(domId) =>
        moveUserInStream(
          model,
          domId,
          ComponentUsersPrefix,
          ComponentUsersStreamDef,
          StreamAt.First,
          streams
        )
      case Msg.MoveComponentUserToLast(domId) =>
        moveUserInStream(
          model,
          domId,
          ComponentUsersPrefix,
          ComponentUsersStreamDef,
          StreamAt.Last,
          streams
        )
      case Msg.ResetUsers =>
        streams
          .reset(UsersStreamDef, Nil)
          .as(model.copy(count = model.count + 1))
      case Msg.ReorderUsers =>
        streams
          .reset(
            UsersStreamDef,
            List(
              User("3", "peter"),
              User("1", "chris"),
              User("4", "mona")
            )
          )
          .as(model.copy(count = model.count + 1))
      case Msg.AppendUsers =>
        streams
          .insertAll(
            UsersStreamDef,
            AppendUsers,
            at = StreamAt.Last
          )
          .as(model)

  private def updateUserInStream(
    model: Model,
    domId: String,
    prefix: String,
    definition: LiveStreamDef[User, String],
    streams: Streams
  ): Task[Model] =
    domIdToUserId(prefix, domId) match
      case Some(id) =>
        streams
          .insert(definition, User(id, "updated"))
          .as(model)
      case None => ZIO.succeed(model)

  private def moveUserInStream(
    model: Model,
    domId: String,
    prefix: String,
    definition: LiveStreamDef[User, String],
    at: StreamAt,
    streams: Streams
  ): Task[Model] =
    domIdToUserId(prefix, domId) match
      case Some(id) =>
        streams
          .delete(definition, id) *>
          streams
            .insert(
              definition,
              User(id, "updated"),
              at = at
            )
            .as(model)
      case None => ZIO.succeed(model)

  private def evalCode(value: Json): String =
    value match
      case Json.Obj(fields) =>
        fields.collectFirst { case ("value", Json.Str(v)) => v }.getOrElse("")
      case _ => ""

  private def domIdToUserId(prefix: String, domId: String): Option[String] =
    Option.when(domId.startsWith(prefix))(domId.drop(prefix.length))
end StreamLiveView

object StreamLiveView:
  final case class User(id: String, name: String)

  final case class Model(count: Int, extraItemWithId: Boolean)

  enum Msg:
    case DeleteUser(id: String)
    case UpdateUser(domId: String)
    case MoveUserToFirst(domId: String)
    case MoveUserToLast(domId: String)
    case DeleteAdmin(id: String)
    case UpdateAdmin(domId: String)
    case MoveAdminToFirst(domId: String)
    case MoveAdminToLast(domId: String)
    case DeleteComponentUser(id: String)
    case UpdateComponentUser(domId: String)
    case MoveComponentUserToFirst(domId: String)
    case MoveComponentUserToLast(domId: String)
    case ResetUsers
    case ReorderUsers
    case AppendUsers

  private val InitialUsers  = List(User("1", "chris"), User("2", "callan"))
  private val InitialAdmins = List(
    User("1", "chris-admin"),
    User("2", "callan-admin")
  )
  private val AppendUsers = List(
    User("4", "foo"),
    User("3", "last_user")
  )

  private val UsersPrefix          = "users-"
  private val AdminsPrefix         = "admins-"
  private val ComponentUsersPrefix = "c_users-"

  private val UsersStreamDef =
    LiveStreamDef.byId[User, String]("users")(_.id)

  private val AdminsStreamDef =
    LiveStreamDef.byId[User, String]("admins")(_.id)

  private val ComponentUsersStreamDef =
    LiveStreamDef.byId[User, String]("c_users")(_.id)
end StreamLiveView

class HealthyLiveView extends LiveView.Routed[HealthyLiveView.Msg, HealthyLiveView.Model, String]:
  import HealthyLiveView.*

  def mount(params: String, ctx: MountContext) =
    val category = normalizeCategory(params)
    ctx.streams
      .create(ItemsStreamDef, itemsFor(category))
      .as(Model(category = category))

  def handleMessage(model: Model, ctx: MessageContext) =
    (_: Msg) => ZIO.succeed(model)

  override def handleParams(model: Model, params: String, url: URL, ctx: ParamsContext) =
    val category = normalizeCategory(params)
    ctx.streams
      .reset(
        ItemsStreamDef,
        itemsFor(category)
      )
      .as(model.copy(category = category))

  override def view(model: Signal[Model]) =
    div(
      p(
        model
          .map(_.category).choose(
            "fruits"  -> link.pushPatch(E2ERoutes.healthy.location("veggies"), "Switch"),
            "veggies" -> link.pushPatch(E2ERoutes.healthy.location("fruits"), "Switch")
          )
      ),
      h1(model.map(_.category.capitalize)),
      ItemsStreamDef.renderIn(ul)(item => li(item.map(_.name)))
    )

end HealthyLiveView

object HealthyLiveView:
  final case class Item(id: Int, name: String)
  final case class Model(category: String)

  enum Msg:
    case Noop

  private val ItemsStreamDef = LiveStreamDef.byId[Item, Int]("items")(_.id)

  private val HealthyStuff = Map(
    "fruits"  -> List(Item(1, "Apples"), Item(2, "Oranges")),
    "veggies" -> List(Item(3, "Carrots"), Item(4, "Tomatoes"))
  )

  private def normalizeCategory(value: String): String =
    value match
      case "fruits" | "veggies" => value
      case _                    => "fruits"

  private def itemsFor(category: String): List[Item] =
    HealthyStuff.getOrElse(normalizeCategory(category), HealthyStuff("fruits"))

class StreamResetLiveView()
    extends LiveView.Routed[StreamResetLiveView.Msg, StreamResetLiveView.Model, Option[String]]:
  import StreamResetLiveView.*

  def mount(_params: Option[String], ctx: MountContext) =
    ctx.streams
      .create(ItemsStreamDef, InitialItems)
      .as(Model(usePhxRemove = false))

  override def handleParams(model: Model, params: Option[String], _url: URL, ctx: ParamsContext) =
    ZIO.succeed(model.copy(usePhxRemove = params.isDefined))

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Filter =>
      ctx.streams
        .reset(ItemsStreamDef, FilteredItems)
        .as(model)
    case Msg.Reorder =>
      ctx.streams
        .reset(ItemsStreamDef, ReorderedItems)
        .as(model)
    case Msg.Reset =>
      ctx.streams
        .reset(ItemsStreamDef, InitialItems)
        .as(model)
    case Msg.Prepend =>
      ctx.streams
        .insert(
          ItemsStreamDef,
          randomItem(),
          at = StreamAt.First
        )
        .as(model)
    case Msg.Append =>
      ctx.streams
        .insert(
          ItemsStreamDef,
          randomItem(),
          at = StreamAt.Last
        )
        .as(model)
    case Msg.BulkInsert =>
      ctx.streams
        .insertAll(
          ItemsStreamDef,
          List(
            Item("g", "G"),
            Item("f", "F"),
            Item("e", "E")
          ),
          at = StreamAt.Index(1)
        )
        .as(model)
    case Msg.InsertAtOne =>
      ctx.streams
        .insert(
          ItemsStreamDef,
          randomItem(),
          at = StreamAt.Index(1)
        )
        .as(model)
    case Msg.InsertExistingAtOne =>
      ctx.streams
        .insert(
          ItemsStreamDef,
          Item("c", "C"),
          at = StreamAt.Index(1)
        )
        .as(model)
    case Msg.DeleteInsertExistingAtOne =>
      (ctx.streams
        .delete(ItemsStreamDef, "c") *>
        ctx.streams.insert(
          ItemsStreamDef,
          Item("c", "C"),
          at = StreamAt.Index(1)
        )).as(model)
    case Msg.PrependExisting =>
      ctx.streams
        .insert(
          ItemsStreamDef,
          Item("c", "C"),
          at = StreamAt.First
        )
        .as(model)
    case Msg.AppendExisting =>
      ctx.streams
        .insert(
          ItemsStreamDef,
          Item("c", "C"),
          at = StreamAt.Last
        )
        .as(model)
    case Msg.NewUpdateOnly =>
      ctx.streams
        .insert(
          ItemsStreamDef,
          Item("e", "E"),
          updateOnly = true
        )
        .as(model)
    case Msg.ExistingUpdateOnly =>
      ctx.streams
        .insert(
          ItemsStreamDef,
          Item("c", s"C ${UUID.randomUUID().toString}"),
          updateOnly = true
        )
        .as(model)
  end handleMessage

  override def view(model: Signal[Model]) =
    div(
      model
        .map(_.usePhxRemove).choose(
          streamList(withPhxRemove = true),
          streamList(withPhxRemove = false)
        ),
      button(on.click(Msg.Filter), "Filter"),
      button(on.click(Msg.Reorder), "Reorder"),
      button(on.click(Msg.Reset), "Reset"),
      button(on.click(Msg.Prepend), "Prepend"),
      button(on.click(Msg.Append), "Append"),
      button(on.click(Msg.BulkInsert), "Bulk insert"),
      button(on.click(Msg.InsertAtOne), "Insert at 1"),
      button(on.click(Msg.InsertExistingAtOne), "Insert C at 1"),
      button(on.click(Msg.DeleteInsertExistingAtOne), "Delete C and insert at 1"),
      button(on.click(Msg.PrependExisting), "Prepend C"),
      button(on.click(Msg.AppendExisting), "Append C"),
      button(on.click(Msg.NewUpdateOnly), "Add E (update only)"),
      button(on.click(Msg.ExistingUpdateOnly), "Update C (update only)")
    )

  private def streamList(withPhxRemove: Boolean): HtmlElement[Msg] =
    ul(
      idAttr     := "thelist",
      phx.update := PhxUpdate.Stream,
      ItemsStreamDef.stream { (domId, item) =>
        if withPhxRemove then
          li(
            idAttr := domId,
            dom.onRemove(JS.hide()),
            item.map(_.name)
          )
        else li(idAttr := domId, item.map(_.name))
      }
    )

  private def randomItem(): Item =
    val id = s"a-${UUID.randomUUID().toString}"
    Item(id, UUID.randomUUID().toString)
end StreamResetLiveView

object StreamResetLiveView:
  final case class Item(id: String, name: String)
  final case class Model(usePhxRemove: Boolean)

  enum Msg:
    case Filter
    case Reorder
    case Reset
    case Prepend
    case Append
    case BulkInsert
    case InsertAtOne
    case InsertExistingAtOne
    case DeleteInsertExistingAtOne
    case PrependExisting
    case AppendExisting
    case NewUpdateOnly
    case ExistingUpdateOnly

  private val ItemsStreamDef = LiveStreamDef.byId[Item, String]("items")(_.id)

  private val InitialItems = List(
    Item("a", "A"),
    Item("b", "B"),
    Item("c", "C"),
    Item("d", "D")
  )

  private val FilteredItems = List(
    Item("b", "B"),
    Item("c", "C"),
    Item("d", "D")
  )

  private val ReorderedItems = List(
    Item("b", "B"),
    Item("a", "A"),
    Item("c", "C"),
    Item("d", "D")
  )
end StreamResetLiveView

class StreamResetLCLiveView extends LiveView[StreamResetLCLiveView.Msg, Unit]:
  import StreamResetLCLiveView.*

  def mount(ctx: MountContext) =
    ctx.streams.create(ItemsStreamDef, InitialItems)

  def handleMessage(model: Unit, ctx: MessageContext) =
    case Msg.Reorder =>
      ctx.streams.reset(ItemsStreamDef, ReorderedItems)

  override def view(model: Signal[Unit]) =
    div(
      ul(
        idAttr     := "thelist",
        phx.update := PhxUpdate.Stream,
        ItemsStreamDef.stream((domId, item) => li(idAttr := domId, item.map(_.name)))
      ),
      button(on.click(Msg.Reorder), "Reorder")
    )

object StreamResetLCLiveView:
  final case class Item(id: String, name: String)

  enum Msg:
    case Reorder

  private val ItemsStreamDef = LiveStreamDef.byId[Item, String]("items")(_.id)

  private val InitialItems = List(
    Item("a", "A"),
    Item("b", "B"),
    Item("c", "C"),
    Item("d", "D")
  )

  private val ReorderedItems = List(
    Item("e", "E"),
    Item("a", "A"),
    Item("f", "F"),
    Item("g", "G")
  )

class StreamLimitLiveView extends LiveView[StreamLimitLiveView.Msg, StreamLimitLiveView.Model]:
  import StreamLimitLiveView.*

  def mount(ctx: MountContext) =
    val initialAt    = -1
    val initialLimit = -5
    ctx.streams
      .create(
        ItemsStreamDef.withLimit(streamLimit(initialLimit)),
        (1 to 10).toList.map(Item.apply)
      )
      .as(
        Model(
          at = initialAt,
          limit = initialLimit,
          lastId = 10
        )
      )

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Configure(atRaw, limitRaw) =>
      val nextAt    = parseIntOrDefault(atRaw, model.at)
      val nextLimit = parseIntOrDefault(limitRaw, model.limit)
      ctx.streams
        .reset(
          ItemsStreamDef.withLimit(streamLimit(nextLimit)),
          (1 to 10).toList.map(Item.apply),
          at = streamAt(nextAt)
        )
        .as(model.copy(at = nextAt, limit = nextLimit, lastId = 10))
    case Msg.Insert10 =>
      val items = (1 to 10).toList.map(index => Item(model.lastId + index))
      ctx.streams
        .insertAll(
          ItemsStreamDef.withLimit(streamLimit(model.limit)),
          items,
          at = streamAt(model.at)
        )
        .as(model.copy(lastId = model.lastId + 10))
    case Msg.Insert1 =>
      val item = Item(model.lastId + 1)
      ctx.streams
        .insert(
          ItemsStreamDef.withLimit(streamLimit(model.limit)),
          item,
          at = streamAt(model.at)
        )
        .as(model.copy(lastId = model.lastId + 1))
    case Msg.Clear =>
      ctx.streams
        .reset(
          ItemsStreamDef,
          Nil
        )
        .as(model.copy(lastId = 0))
  end handleMessage

  override def view(model: Signal[Model]) =
    div(
      form(
        on.submit(params =>
          Msg.Configure(
            params.getOrElse("at", "-1"),
            params.getOrElse("limit", "-5")
          )
        ),
        "at: ",
        input(
          typ      := "text",
          nameAttr := "at",
          value    := model.map(_.at.toString)
        ),
        " limit: ",
        input(
          typ      := "text",
          nameAttr := "limit",
          value    := model.map(_.limit.toString)
        ),
        button(
          typ := "submit",
          "recreate stream"
        )
      ),
      div(
        "configured with at: ",
        model.map(_.at.toString),
        ", limit: ",
        model.map(_.limit.toString)
      ),
      button(on.click(Msg.Insert10), "add 10"),
      button(on.click(Msg.Insert1), "add 1"),
      button(on.click(Msg.Clear), "clear"),
      ul(
        idAttr     := "items",
        phx.update := PhxUpdate.Stream,
        rawHtml("\n"),
        ItemsStreamDef.stream((domId, item) => li(idAttr := domId, item.map(_.id.toString))),
        rawHtml("\n")
      )
    )

  private def parseIntOrDefault(raw: String, default: Int): Int =
    raw.toIntOption.getOrElse(default)
end StreamLimitLiveView

object StreamLimitLiveView:
  final case class Item(id: Int)
  final case class Model(at: Int, limit: Int, lastId: Int)

  enum Msg:
    case Configure(at: String, limit: String)
    case Insert10
    case Insert1
    case Clear

  private val ItemsStreamDef = LiveStreamDef.byId[Item, Int]("items")(_.id)

  private def streamAt(value: Int): StreamAt =
    if value == -1 then StreamAt.Last
    else if value == 0 then StreamAt.First
    else StreamAt.Index(value)

  private def streamLimit(value: Int): Option[StreamLimit] =
    if value > 0 then Some(StreamLimit.KeepFirst(value))
    else if value < 0 then Some(StreamLimit.KeepLast(-value))
    else None

class StreamNestedComponentResetLiveView
    extends LiveView[StreamNestedComponentResetLiveView.Msg, Unit]:
  import StreamNestedComponentResetLiveView.*

  def mount(ctx: MountContext) =
    ctx.streams.create(ItemsStreamDef, InitialItems)

  def handleMessage(model: Unit, ctx: MessageContext) =
    case Msg.ReorderParents => ctx.streams.reset(ItemsStreamDef, ReorderedItems)

  override def view(model: Signal[Unit]) =
    div(
      ul(
        idAttr     := "thelist",
        phx.update := PhxUpdate.Stream,
        ItemsStreamDef.stream { (domId, item) =>
          val nested = component(NestedItemsComponent, domId)
          li(
            idAttr := domId,
            item.map(_.name),
            nested.render(domId),
            button(on.click.to(nested)(NestedItemsComponent.Msg.Reorder), "Reorder")
          )
        }
      ),
      button(
        idAttr := "parent-reorder",
        on.click(Msg.ReorderParents),
        "Reorder"
      )
    )
end StreamNestedComponentResetLiveView

object StreamNestedComponentResetLiveView:
  final case class ParentItem(id: String, name: String)
  final case class NestedItem(id: String, name: String)

  enum Msg:
    case ReorderParents

  private val ItemsStreamDef = LiveStreamDef.byId[ParentItem, String]("items")(_.id)

  private val InitialItems = List(
    ParentItem("a", "A"),
    ParentItem("b", "B"),
    ParentItem("c", "C"),
    ParentItem("d", "D")
  )

  private val ReorderedItems = List(
    ParentItem("e", "E"),
    ParentItem("a", "A"),
    ParentItem("f", "F"),
    ParentItem("g", "G")
  )

  object NestedItemsComponent extends LiveComponent[String, NestedItemsComponent.Msg, Unit]:
    enum Msg:
      case Reorder

    private val NestedStreamDef = LiveStreamDef.byId[NestedItem, String]("nested")(_.id)

    def mount(parentDomId: String, ctx: MountContext) =
      val suffixes =
        if ctx.connection.isConnected then List("a", "b", "c", "d")
        else List("e", "f", "g", "h")
      ctx.streams.create(NestedStreamDef, nestedItems(parentDomId, suffixes))

    def handleMessage(parentDomId: String, model: Unit, ctx: MessageContext) =
      case Msg.Reorder =>
        ctx.streams.reset(NestedStreamDef, nestedItems(parentDomId, List("e", "a", "f", "g")))

    override def view(parentDomId: Signal[String], model: Signal[Unit]) =
      div(
        idAttr     := parentDomId.map(id => s"$id-nested"),
        phx.update := PhxUpdate.Stream,
        styleAttr  := "display: flex; gap: 4px;",
        NestedStreamDef.stream((domId, item) => span(idAttr := domId, item.map(_.name)))
      )

    private def nestedItems(parentDomId: String, suffixes: List[String]): List[NestedItem] =
      suffixes.map(suffix => NestedItem(s"$parentDomId-$suffix", s"N-${suffix.toUpperCase}"))
end StreamNestedComponentResetLiveView

class StreamInsideForLiveView extends LiveView[StreamInsideForLiveView.Msg, Unit]:
  import StreamInsideForLiveView.*

  def mount(ctx: MountContext) =
    ctx.streams.create(ItemsStreamDef, InitialItems)

  def handleMessage(model: Unit, ctx: MessageContext) =
    (_: Msg) => ZIO.unit

  override def view(model: Signal[Unit]) =
    div(
      List(1).map(_ =>
        ul(
          idAttr     := "thelist",
          phx.update := PhxUpdate.Stream,
          ItemsStreamDef.stream((domId, item) => li(idAttr := domId, item.map(_.name)))
        )
      )
    )

object StreamInsideForLiveView:
  final case class Item(id: String, name: String)

  enum Msg:
    case Noop

  private val ItemsStreamDef = LiveStreamDef.byId[Item, String]("items")(_.id)

  private val InitialItems = List(
    Item("a", "A"),
    Item("b", "B"),
    Item("c", "C"),
    Item("d", "D")
  )
