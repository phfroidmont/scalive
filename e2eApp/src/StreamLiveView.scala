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
      users          <- ctx.streams.create(UsersStreamDef, InitialUsers)
      admins         <- ctx.streams.create(AdminsStreamDef, InitialAdmins)
      componentUsers <- ctx.streams.create(ComponentUsersStreamDef, InitialUsers)
    yield Model(
      users = users,
      admins = admins,
      componentUsers = componentUsers,
      count = 0,
      extraItemWithId = false
    )

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
        model.map(_.users).stream { (domId, user) =>
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
        model.map(_.admins).stream { (domId, user) =>
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
        model.map(_.componentUsers).stream { (domId, user) =>
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
        streams.delete(UsersStreamDef, id).map(users => model.copy(users = users))
      case Msg.UpdateUser(domId) =>
        updateUserInStream(model, domId, UsersPrefix, UsersStreamDef, streams)(users =>
          model.copy(users = users)
        )
      case Msg.MoveUserToFirst(domId) =>
        moveUserInStream(
          model,
          domId,
          UsersPrefix,
          UsersStreamDef,
          StreamAt.First,
          streams
        )(users => model.copy(users = users))
      case Msg.MoveUserToLast(domId) =>
        moveUserInStream(
          model,
          domId,
          UsersPrefix,
          UsersStreamDef,
          StreamAt.Last,
          streams
        )(users => model.copy(users = users))
      case Msg.DeleteAdmin(id) =>
        streams.delete(AdminsStreamDef, id).map(admins => model.copy(admins = admins))
      case Msg.UpdateAdmin(domId) =>
        updateUserInStream(model, domId, AdminsPrefix, AdminsStreamDef, streams)(admins =>
          model.copy(admins = admins)
        )
      case Msg.MoveAdminToFirst(domId) =>
        moveUserInStream(
          model,
          domId,
          AdminsPrefix,
          AdminsStreamDef,
          StreamAt.First,
          streams
        )(admins => model.copy(admins = admins))
      case Msg.MoveAdminToLast(domId) =>
        moveUserInStream(
          model,
          domId,
          AdminsPrefix,
          AdminsStreamDef,
          StreamAt.Last,
          streams
        )(admins => model.copy(admins = admins))
      case Msg.DeleteComponentUser(id) =>
        streams
          .delete(ComponentUsersStreamDef, id).map(componentUsers =>
            model.copy(componentUsers = componentUsers)
          )
      case Msg.UpdateComponentUser(domId) =>
        updateUserInStream(model, domId, ComponentUsersPrefix, ComponentUsersStreamDef, streams)(
          componentUsers => model.copy(componentUsers = componentUsers)
        )
      case Msg.MoveComponentUserToFirst(domId) =>
        moveUserInStream(
          model,
          domId,
          ComponentUsersPrefix,
          ComponentUsersStreamDef,
          StreamAt.First,
          streams
        )(componentUsers => model.copy(componentUsers = componentUsers))
      case Msg.MoveComponentUserToLast(domId) =>
        moveUserInStream(
          model,
          domId,
          ComponentUsersPrefix,
          ComponentUsersStreamDef,
          StreamAt.Last,
          streams
        )(componentUsers => model.copy(componentUsers = componentUsers))
      case Msg.ResetUsers =>
        streams
          .reset(UsersStreamDef, Nil)
          .map(users => model.copy(users = users, count = model.count + 1))
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
          .map(users => model.copy(users = users, count = model.count + 1))
      case Msg.AppendUsers =>
        streams
          .insertAll(
            UsersStreamDef,
            AppendUsers,
            at = StreamAt.Last
          )
          .map(users => model.copy(users = users))

  private def updateUserInStream(
    model: Model,
    domId: String,
    prefix: String,
    definition: LiveStreamDef[User, String],
    streams: Streams
  )(
    setStream: LiveStream[User] => Model
  ): Task[Model] =
    domIdToUserId(prefix, domId) match
      case Some(id) =>
        streams
          .insert(definition, User(id, "updated"))
          .map(setStream)
      case None => ZIO.succeed(model)

  private def moveUserInStream(
    model: Model,
    domId: String,
    prefix: String,
    definition: LiveStreamDef[User, String],
    at: StreamAt,
    streams: Streams
  )(
    setStream: LiveStream[User] => Model
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
            .map(setStream)
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

  final case class Model(
    users: LiveStream[User],
    admins: LiveStream[User],
    componentUsers: LiveStream[User],
    count: Int,
    extraItemWithId: Boolean)

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
      .map(items => Model(category = category, items = items))

  def handleMessage(model: Model, ctx: MessageContext) =
    (_: Msg) => ZIO.succeed(model)

  override def handleParams(model: Model, params: String, url: URL, ctx: ParamsContext) =
    val category = normalizeCategory(params)
    ctx.streams
      .reset(
        ItemsStreamDef,
        itemsFor(category)
      )
      .map(items => model.copy(category = category, items = items))

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
      model.map(_.items).renderIn(ul)(item => li(item.map(_.name)))
    )

end HealthyLiveView

object HealthyLiveView:
  final case class Item(id: Int, name: String)
  final case class Model(category: String, items: LiveStream[Item])

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
      .map(items => Model(items = items, usePhxRemove = false))

  override def handleParams(model: Model, params: Option[String], _url: URL, ctx: ParamsContext) =
    ZIO.succeed(model.copy(usePhxRemove = params.isDefined))

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Filter =>
      ctx.streams
        .reset(ItemsStreamDef, FilteredItems)
        .map(items => model.copy(items = items))
    case Msg.Reorder =>
      ctx.streams
        .reset(ItemsStreamDef, ReorderedItems)
        .map(items => model.copy(items = items))
    case Msg.Reset =>
      ctx.streams
        .reset(ItemsStreamDef, InitialItems)
        .map(items => model.copy(items = items))
    case Msg.Prepend =>
      ctx.streams
        .insert(
          ItemsStreamDef,
          randomItem(),
          at = StreamAt.First
        )
        .map(items => model.copy(items = items))
    case Msg.Append =>
      ctx.streams
        .insert(
          ItemsStreamDef,
          randomItem(),
          at = StreamAt.Last
        )
        .map(items => model.copy(items = items))
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
        .map(items => model.copy(items = items))
    case Msg.InsertAtOne =>
      ctx.streams
        .insert(
          ItemsStreamDef,
          randomItem(),
          at = StreamAt.Index(1)
        )
        .map(items => model.copy(items = items))
    case Msg.InsertExistingAtOne =>
      ctx.streams
        .insert(
          ItemsStreamDef,
          Item("c", "C"),
          at = StreamAt.Index(1)
        )
        .map(items => model.copy(items = items))
    case Msg.DeleteInsertExistingAtOne =>
      (ctx.streams
        .delete(ItemsStreamDef, "c") *>
        ctx.streams.insert(
          ItemsStreamDef,
          Item("c", "C"),
          at = StreamAt.Index(1)
        )).map(items => model.copy(items = items))
    case Msg.PrependExisting =>
      ctx.streams
        .insert(
          ItemsStreamDef,
          Item("c", "C"),
          at = StreamAt.First
        )
        .map(items => model.copy(items = items))
    case Msg.AppendExisting =>
      ctx.streams
        .insert(
          ItemsStreamDef,
          Item("c", "C"),
          at = StreamAt.Last
        )
        .map(items => model.copy(items = items))
    case Msg.NewUpdateOnly =>
      ctx.streams
        .insert(
          ItemsStreamDef,
          Item("e", "E"),
          updateOnly = true
        )
        .map(items => model.copy(items = items))
    case Msg.ExistingUpdateOnly =>
      ctx.streams
        .insert(
          ItemsStreamDef,
          Item("c", s"C ${UUID.randomUUID().toString}"),
          updateOnly = true
        )
        .map(items => model.copy(items = items))
  end handleMessage

  override def view(model: Signal[Model]) =
    val items = model.map(_.items)

    div(
      model
        .map(_.usePhxRemove).choose(
          streamList(items, withPhxRemove = true),
          streamList(items, withPhxRemove = false)
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

  private def streamList(items: Signal[LiveStream[Item]], withPhxRemove: Boolean)
    : HtmlElement[Msg] =
    ul(
      idAttr     := "thelist",
      phx.update := PhxUpdate.Stream,
      items.stream { (domId, item) =>
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
  final case class Model(items: LiveStream[Item], usePhxRemove: Boolean)

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

class StreamResetLCLiveView
    extends LiveView[StreamResetLCLiveView.Msg, StreamResetLCLiveView.Model]:
  import StreamResetLCLiveView.*

  def mount(ctx: MountContext) =
    ctx.streams
      .create(ItemsStreamDef, InitialItems)
      .map(items => Model(items = items))

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Reorder =>
      ctx.streams
        .reset(ItemsStreamDef, ReorderedItems)
        .map(items => model.copy(items = items))

  override def view(model: Signal[Model]) =
    div(
      ul(
        idAttr     := "thelist",
        phx.update := PhxUpdate.Stream,
        model.map(_.items).stream((domId, item) => li(idAttr := domId, item.map(_.name)))
      ),
      button(on.click(Msg.Reorder), "Reorder")
    )

object StreamResetLCLiveView:
  final case class Item(id: String, name: String)
  final case class Model(items: LiveStream[Item])

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
      .map(items =>
        Model(
          items = items,
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
        .map(items => model.copy(items = items, at = nextAt, limit = nextLimit, lastId = 10))
    case Msg.Insert10 =>
      val items = (1 to 10).toList.map(index => Item(model.lastId + index))
      ctx.streams
        .insertAll(
          ItemsStreamDef.withLimit(streamLimit(model.limit)),
          items,
          at = streamAt(model.at)
        )
        .map(nextItems => model.copy(items = nextItems, lastId = model.lastId + 10))
    case Msg.Insert1 =>
      val item = Item(model.lastId + 1)
      ctx.streams
        .insert(
          ItemsStreamDef.withLimit(streamLimit(model.limit)),
          item,
          at = streamAt(model.at)
        )
        .map(nextItems => model.copy(items = nextItems, lastId = model.lastId + 1))
    case Msg.Clear =>
      ctx.streams
        .reset(
          ItemsStreamDef,
          Nil
        )
        .map(nextItems => model.copy(items = nextItems, lastId = 0))
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
        model.map(_.items).stream((domId, item) => li(idAttr := domId, item.map(_.id.toString))),
        rawHtml("\n")
      )
    )

  private def parseIntOrDefault(raw: String, default: Int): Int =
    raw.toIntOption.getOrElse(default)
end StreamLimitLiveView

object StreamLimitLiveView:
  final case class Item(id: Int)
  final case class Model(items: LiveStream[Item], at: Int, limit: Int, lastId: Int)

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
    extends LiveView[
      StreamNestedComponentResetLiveView.Msg,
      StreamNestedComponentResetLiveView.Model
    ]:
  import StreamNestedComponentResetLiveView.*

  def mount(ctx: MountContext) =
    for
      a <- buildParentItem("a", "A", ctx.streams)
      b <- buildParentItem("b", "B", ctx.streams)
      c <- buildParentItem("c", "C", ctx.streams)
      d <- buildParentItem("d", "D", ctx.streams)
      parents = List(a, b, c, d)
      items <- ctx.streams.create(ItemsStreamDef, parents)
    yield Model(
      items = items,
      parentsById = parents.iterator.map(parent => parent.id -> parent).toMap
    )

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.ReorderNested(id) =>
      reorderNested(model, id, ctx.streams)
    case Msg.ReorderParents =>
      reorderParents(model, ctx.streams)

  override def view(model: Signal[Model]) =
    div(
      ul(
        idAttr     := "thelist",
        phx.update := PhxUpdate.Stream,
        model.map(_.items).stream { (domId, item) =>
          li(
            idAttr := domId,
            item.map(_.name),
            div(
              phx.update := PhxUpdate.Stream,
              styleAttr  := "display: flex; gap: 4px;",
              item
                .map(_.nested).stream((nestedDomId, nestedItem) =>
                  span(idAttr := nestedDomId, nestedItem.map(_.name))
                )
            ),
            button(
              on.click(item.map(value => Msg.ReorderNested(value.id))),
              phx.value("id") := item.map(_.id),
              "Reorder"
            )
          )
        }
      ),
      button(
        idAttr := "parent-reorder",
        on.click(Msg.ReorderParents),
        "Reorder"
      )
    )

  private def reorderNested(
    model: Model,
    id: String,
    streams: Streams
  ): Task[Model] =
    if id.isEmpty then ZIO.succeed(model)
    else
      model.parentsById.get(id) match
        case None          => ZIO.succeed(model)
        case Some(current) =>
          for
            nested <- streams.reset(
                        current.nestedDefinition,
                        reorderedNestedItems
                      )
            updatedParent = current.copy(nested = nested)
            items <- streams.insert(
                       ItemsStreamDef,
                       updatedParent,
                       updateOnly = true
                     )
          yield model.copy(
            items = items,
            parentsById = model.parentsById.updated(id, updatedParent)
          )

  private def reorderParents(model: Model, streams: Streams): Task[Model] =
    for
      parentA <- model.parentsById.get("a") match
                   case Some(value) => ZIO.succeed(value)
                   case None        => buildParentItem("a", "A", streams)
      parentE <- buildParentItem("e", "E", streams)
      parentF <- buildParentItem("f", "F", streams)
      parentG <- buildParentItem("g", "G", streams)
      parents = List(parentE, parentA, parentF, parentG)
      items <- streams.reset(ItemsStreamDef, parents)
    yield model.copy(
      items = items,
      parentsById = parents.iterator.map(parent => parent.id -> parent).toMap
    )

  private def buildParentItem(
    id: String,
    name: String,
    streams: Streams
  ): Task[ParentItem] =
    val definition = nestedStreamDef(id)
    streams
      .create(definition, defaultNestedItems)
      .map(nested => ParentItem(id, name, definition, nested))
end StreamNestedComponentResetLiveView

object StreamNestedComponentResetLiveView:
  final case class NestedItem(id: String, name: String)
  final case class ParentItem(
    id: String,
    name: String,
    nestedDefinition: LiveStreamDef[NestedItem, String],
    nested: LiveStream[NestedItem])
  final case class Model(
    items: LiveStream[ParentItem],
    parentsById: Map[String, ParentItem])

  enum Msg:
    case ReorderNested(id: String)
    case ReorderParents

  private val ItemsStreamDef = LiveStreamDef.byId[ParentItem, String]("items")(_.id)

  private val defaultNestedItems = List(
    NestedItem("a", "N-A"),
    NestedItem("b", "N-B"),
    NestedItem("c", "N-C"),
    NestedItem("d", "N-D")
  )

  private val reorderedNestedItems = List(
    NestedItem("e", "N-E"),
    NestedItem("a", "N-A"),
    NestedItem("f", "N-F"),
    NestedItem("g", "N-G")
  )

  private def nestedStreamDef(parentId: String): LiveStreamDef[NestedItem, String] =
    LiveStreamDef[NestedItem, String](
      s"nested-items-$parentId",
      _.id,
      id => s"nested-items-$parentId-$id"
    )
end StreamNestedComponentResetLiveView

class StreamInsideForLiveView
    extends LiveView[StreamInsideForLiveView.Msg, StreamInsideForLiveView.Model]:
  import StreamInsideForLiveView.*

  def mount(ctx: MountContext) =
    ctx.streams
      .create(ItemsStreamDef, InitialItems)
      .map(items => Model(items = items))

  def handleMessage(model: Model, ctx: MessageContext) =
    (_: Msg) => ZIO.succeed(model)

  override def view(model: Signal[Model]) =
    div(
      List(1).map(_ =>
        ul(
          idAttr     := "thelist",
          phx.update := PhxUpdate.Stream,
          model.map(_.items).stream((domId, item) => li(idAttr := domId, item.map(_.name)))
        )
      )
    )

object StreamInsideForLiveView:
  final case class Item(id: String, name: String)
  final case class Model(items: LiveStream[Item])

  enum Msg:
    case Noop

  private val ItemsStreamDef = LiveStreamDef.byId[Item, String]("items")(_.id)

  private val InitialItems = List(
    Item("a", "A"),
    Item("b", "B"),
    Item("c", "C"),
    Item("d", "D")
  )
