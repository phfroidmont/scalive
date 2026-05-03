import java.util.UUID

import zio.*
import zio.http.URL
import zio.http.codec.HttpCodec
import zio.json.ast.Json

import scalive.*
import scalive.LiveIO.given
import scalive.codecs.BooleanAsAttrPresenceEncoder

class StreamLiveView() extends LiveView[StreamLiveView.Msg, StreamLiveView.Model]:
  import StreamLiveView.*

  override val queryCodec: LiveQueryCodec[Option[String]] = ParamsCodec

  private val onlyChild = htmlAttr("only-child", BooleanAsAttrPresenceEncoder)

  def mount(ctx: MountContext) =
    for
      users          <- ctx.streams.init(UsersStreamDef, InitialUsers)
      admins         <- ctx.streams.init(AdminsStreamDef, InitialAdmins)
      componentUsers <- ctx.streams.init(ComponentUsersStreamDef, InitialUsers)
    yield Model(
      users = users,
      admins = admins,
      componentUsers = componentUsers,
      count = 0,
      extraItemWithId = false
    )

  override def handleParams(model: Model, params: Option[String], _url: URL, ctx: ParamsContext) =
    model.copy(extraItemWithId = params.isDefined)

  def handleMessage(model: Model, ctx: MessageContext) =
    (msg: Msg) => handle(model, msg, ctx.streams)

  override def hooks: LiveHooks[Msg, Model] =
    LiveHooks.empty.rawEvent("sandbox") { (model, event, ctx) =>
      if event.bindingId != "sandbox:eval" then LiveEventHookResult.cont(model)
      else
        evalCode(event.value) match
          case "socket.view.handle_event(\"reset-users\", %{}, socket)" =>
            handle(model, Msg.ResetUsers, ctx.streams)
              .map(next => LiveEventHookResult.haltReply(next, Json.Obj("result" -> Json.Null)))
          case "socket.view.handle_event(\"append-users\", %{}, socket)" =>
            handle(model, Msg.AppendUsers, ctx.streams)
              .map(next => LiveEventHookResult.haltReply(next, Json.Obj("result" -> Json.Null)))
          case _ =>
            E2ESandboxEval.handle(model, event.bindingId, event.value)
    }

  def render(model: Model) =
    div(
      div(
        idAttr       := "users",
        phx.onUpdate := "stream",
        model.users.stream { (domId, user) =>
          div(
            idAttr            := domId,
            dataAttr("count") := model.count.toString,
            user.name,
            button(
              phx.onClick(params => Msg.DeleteUser(params.getOrElse("id", ""))),
              phx.value("id") := domId,
              "delete"
            ),
            button(
              phx.onClick(params => Msg.UpdateUser(params.getOrElse("id", ""))),
              phx.value("id") := domId,
              "update"
            ),
            button(
              phx.onClick(params => Msg.MoveUserToFirst(params.getOrElse("id", ""))),
              phx.value("id") := domId,
              "make first"
            ),
            button(
              phx.onClick(params => Msg.MoveUserToLast(params.getOrElse("id", ""))),
              phx.value("id") := domId,
              "make last"
            ),
            button(
              phx.onClick(JS.hide(to = "#users > *")),
              "JS Hide"
            )
          )
        },
        if model.extraItemWithId then
          div(
            idAttr    := "users-empty",
            onlyChild := true,
            "Empty!"
          )
        else ""
      ),
      div(
        idAttr       := "admins",
        phx.onUpdate := "stream",
        model.admins.stream { (domId, user) =>
          div(
            idAttr            := domId,
            dataAttr("count") := model.count.toString,
            user.name,
            button(
              phx.onClick(params => Msg.DeleteAdmin(params.getOrElse("id", ""))),
              phx.value("id") := domId,
              "delete"
            ),
            button(
              phx.onClick(params => Msg.UpdateAdmin(params.getOrElse("id", ""))),
              phx.value("id") := domId,
              "update"
            ),
            button(
              phx.onClick(params => Msg.MoveAdminToFirst(params.getOrElse("id", ""))),
              phx.value("id") := domId,
              "make first"
            ),
            button(
              phx.onClick(params => Msg.MoveAdminToLast(params.getOrElse("id", ""))),
              phx.value("id") := domId,
              "make last"
            )
          )
        }
      ),
      div(
        idAttr       := "c_users",
        phx.onUpdate := "stream",
        model.componentUsers.stream { (domId, user) =>
          div(
            idAttr := domId,
            user.name,
            button(
              phx.onClick(params => Msg.DeleteComponentUser(params.getOrElse("id", ""))),
              phx.value("id") := domId,
              "delete"
            ),
            button(
              phx.onClick(params => Msg.UpdateComponentUser(params.getOrElse("id", ""))),
              phx.value("id") := domId,
              "update"
            ),
            button(
              phx.onClick(params => Msg.MoveComponentUserToFirst(params.getOrElse("id", ""))),
              phx.value("id") := domId,
              "make first"
            ),
            button(
              phx.onClick(params => Msg.MoveComponentUserToLast(params.getOrElse("id", ""))),
              phx.value("id") := domId,
              "make last"
            )
          )
        }
      ),
      button(
        phx.onClick(Msg.ResetUsers),
        "Reset"
      ),
      button(
        phx.onClick(Msg.ReorderUsers),
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

  private def handle(model: Model, msg: Msg, streams: Streams): LiveIO[Model] =
    msg match
      case Msg.DeleteUser(domId) =>
        streams.deleteByDomId(UsersStreamDef, domId).map(users => model.copy(users = users))
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
      case Msg.DeleteAdmin(domId) =>
        streams.deleteByDomId(AdminsStreamDef, domId).map(admins => model.copy(admins = admins))
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
      case Msg.DeleteComponentUser(domId) =>
        streams
          .deleteByDomId(ComponentUsersStreamDef, domId).map(componentUsers =>
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
          .init(UsersStreamDef, Nil, reset = true)
          .map(users => model.copy(users = users, count = model.count + 1))
      case Msg.ReorderUsers =>
        streams
          .init(
            UsersStreamDef,
            List(
              User("3", "peter"),
              User("1", "chris"),
              User("4", "mona")
            ),
            reset = true
          )
          .map(users => model.copy(users = users, count = model.count + 1))
      case Msg.AppendUsers =>
        streams
          .init(
            UsersStreamDef,
            AppendUsers,
            at = StreamAt.Last
          )
          .map(users => model.copy(users = users))

  private def updateUserInStream(
    model: Model,
    domId: String,
    prefix: String,
    definition: LiveStreamDef[User],
    streams: Streams
  )(
    setStream: LiveStream[User] => Model
  ): LiveIO[Model] =
    domIdToUserId(prefix, domId) match
      case Some(id) =>
        streams
          .insert(definition, User(id, "updated"))
          .map(setStream)
      case None => model

  private def moveUserInStream(
    model: Model,
    domId: String,
    prefix: String,
    definition: LiveStreamDef[User],
    at: StreamAt,
    streams: Streams
  )(
    setStream: LiveStream[User] => Model
  ): LiveIO[Model] =
    domIdToUserId(prefix, domId) match
      case Some(id) =>
        streams
          .deleteByDomId(definition, domId) *>
          streams
            .insert(
              definition,
              User(id, "updated"),
              at = at
            )
            .map(setStream)
      case None => model

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
    case DeleteUser(domId: String)
    case UpdateUser(domId: String)
    case MoveUserToFirst(domId: String)
    case MoveUserToLast(domId: String)
    case DeleteAdmin(domId: String)
    case UpdateAdmin(domId: String)
    case MoveAdminToFirst(domId: String)
    case MoveAdminToLast(domId: String)
    case DeleteComponentUser(domId: String)
    case UpdateComponentUser(domId: String)
    case MoveComponentUserToFirst(domId: String)
    case MoveComponentUserToLast(domId: String)
    case ResetUsers
    case ReorderUsers
    case AppendUsers

  val ParamsCodec: LiveQueryCodec[Option[String]] =
    LiveQueryCodec.fromZioHttp(HttpCodec.query[String]("empty_item").optional)

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

class HealthyLiveView(initialCategory: String)
    extends LiveView[HealthyLiveView.Msg, HealthyLiveView.Model]:
  import HealthyLiveView.*

  def mount(ctx: MountContext) =
    val category = normalizeCategory(initialCategory)
    ctx.streams
      .init(ItemsStreamDef, itemsFor(category))
      .map(items => Model(category = category, items = items))

  def handleMessage(model: Model, ctx: MessageContext) =
    (_: Msg) => model

  override def handleParams(model: Model, _query: queryCodec.Out, url: URL, ctx: ParamsContext) =
    val category = normalizeCategory(categoryFromUrl(url))
    ctx.streams
      .init(
        ItemsStreamDef,
        itemsFor(category),
        reset = true
      )
      .map(items => model.copy(category = category, items = items))

  def render(model: Model) =
    div(
      p(
        link.patch(s"/healthy/${otherCategory(model.category)}", "Switch")
      ),
      h1(model.category.capitalize),
      ul(
        idAttr       := "items",
        phx.onUpdate := "stream",
        model.items.stream { (domId, item) =>
          li(
            idAttr := domId,
            item.name
          )
        }
      )
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

  private def otherCategory(category: String): String =
    if category == "fruits" then "veggies" else "fruits"

  private def categoryFromUrl(url: URL): String =
    url.path.segments.toList match
      case "healthy" :: category :: Nil => category
      case _                            => "fruits"

class StreamResetLiveView() extends LiveView[StreamResetLiveView.Msg, StreamResetLiveView.Model]:
  import StreamResetLiveView.*

  override val queryCodec: LiveQueryCodec[Option[String]] = ParamsCodec

  def mount(ctx: MountContext) =
    ctx.streams
      .init(ItemsStreamDef, InitialItems)
      .map(items => Model(items = items, usePhxRemove = false))

  override def handleParams(model: Model, params: Option[String], _url: URL, ctx: ParamsContext) =
    model.copy(usePhxRemove = params.isDefined)

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Filter =>
      ctx.streams
        .init(ItemsStreamDef, FilteredItems, reset = true)
        .map(items => model.copy(items = items))
    case Msg.Reorder =>
      ctx.streams
        .init(ItemsStreamDef, ReorderedItems, reset = true)
        .map(items => model.copy(items = items))
    case Msg.Reset =>
      ctx.streams
        .init(ItemsStreamDef, InitialItems, reset = true)
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
        .init(
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
        .deleteByDomId(ItemsStreamDef, "items-c") *>
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

  def render(model: Model) =
    div(
      if model.usePhxRemove then streamList(model, withPhxRemove = true) else "",
      if !model.usePhxRemove then streamList(model, withPhxRemove = false) else "",
      button(phx.onClick(Msg.Filter), "Filter"),
      button(phx.onClick(Msg.Reorder), "Reorder"),
      button(phx.onClick(Msg.Reset), "Reset"),
      button(phx.onClick(Msg.Prepend), "Prepend"),
      button(phx.onClick(Msg.Append), "Append"),
      button(phx.onClick(Msg.BulkInsert), "Bulk insert"),
      button(phx.onClick(Msg.InsertAtOne), "Insert at 1"),
      button(phx.onClick(Msg.InsertExistingAtOne), "Insert C at 1"),
      button(phx.onClick(Msg.DeleteInsertExistingAtOne), "Delete C and insert at 1"),
      button(phx.onClick(Msg.PrependExisting), "Prepend C"),
      button(phx.onClick(Msg.AppendExisting), "Append C"),
      button(phx.onClick(Msg.NewUpdateOnly), "Add E (update only)"),
      button(phx.onClick(Msg.ExistingUpdateOnly), "Update C (update only)")
    )

  private def streamList(model: Model, withPhxRemove: Boolean): HtmlElement[Msg] =
    ul(
      idAttr       := "thelist",
      phx.onUpdate := "stream",
      model.items.stream { (domId, item) =>
        if withPhxRemove then
          li(
            idAttr := domId,
            phx.onRemove(JS.hide()),
            item.name
          )
        else
          li(
            idAttr := domId,
            item.name
          )
      }
    )

  private def randomItem(): Item =
    val id = s"a-${UUID.randomUUID().toString}"
    Item(id, UUID.randomUUID().toString)
end StreamResetLiveView

object StreamResetLiveView:
  final case class Item(id: String, name: String)
  final case class Model(items: LiveStream[Item], usePhxRemove: Boolean)

  val ParamsCodec: LiveQueryCodec[Option[String]] =
    LiveQueryCodec.fromZioHttp(HttpCodec.query[String]("phx-remove").optional)

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
      .init(ItemsStreamDef, InitialItems)
      .map(items => Model(items = items))

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.Reorder =>
      ctx.streams
        .init(ItemsStreamDef, ReorderedItems, reset = true)
        .map(items => model.copy(items = items))

  def render(model: Model) =
    div(
      ul(
        idAttr       := "thelist",
        phx.onUpdate := "stream",
        model.items.stream { (domId, item) =>
          li(
            idAttr := domId,
            item.name
          )
        }
      ),
      button(phx.onClick(Msg.Reorder), "Reorder")
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
      .init(
        ItemsStreamDef,
        (1 to 10).toList.map(Item.apply),
        at = streamAt(initialAt),
        limit = streamLimit(initialLimit)
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
        .init(
          ItemsStreamDef,
          (1 to 10).toList.map(Item.apply),
          at = streamAt(nextAt),
          reset = true,
          limit = streamLimit(nextLimit)
        )
        .map(items => model.copy(items = items, at = nextAt, limit = nextLimit, lastId = 10))
    case Msg.Insert10 =>
      val items = (1 to 10).toList.map(index => Item(model.lastId + index))
      ctx.streams
        .init(
          ItemsStreamDef,
          items,
          at = streamAt(model.at),
          limit = streamLimit(model.limit)
        )
        .map(nextItems => model.copy(items = nextItems, lastId = model.lastId + 10))
    case Msg.Insert1 =>
      val item = Item(model.lastId + 1)
      ctx.streams
        .insert(
          ItemsStreamDef,
          item,
          at = streamAt(model.at),
          limit = streamLimit(model.limit)
        )
        .map(nextItems => model.copy(items = nextItems, lastId = model.lastId + 1))
    case Msg.Clear =>
      ctx.streams
        .init(
          ItemsStreamDef,
          Nil,
          reset = true
        )
        .map(nextItems => model.copy(items = nextItems, lastId = 0))
  end handleMessage

  def render(model: Model) =
    div(
      form(
        phx.onSubmit(params =>
          Msg.Configure(
            params.getOrElse("at", "-1"),
            params.getOrElse("limit", "-5")
          )
        ),
        "at: ",
        input(
          typ      := "text",
          nameAttr := "at",
          value    := model.at.toString
        ),
        " limit: ",
        input(
          typ      := "text",
          nameAttr := "limit",
          value    := model.limit.toString
        ),
        button(
          typ := "submit",
          "recreate stream"
        )
      ),
      div(
        "configured with at: ",
        model.at.toString,
        ", limit: ",
        model.limit.toString
      ),
      button(phx.onClick(Msg.Insert10), "add 10"),
      button(phx.onClick(Msg.Insert1), "add 1"),
      button(phx.onClick(Msg.Clear), "clear"),
      ul(
        idAttr       := "items",
        phx.onUpdate := "stream",
        rawHtml("\n"),
        model.items.stream { (domId, item) =>
          li(
            idAttr := domId,
            item.id.toString
          )
        },
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
      a     <- buildParentItem("a", "A", ctx.streams)
      b     <- buildParentItem("b", "B", ctx.streams)
      c     <- buildParentItem("c", "C", ctx.streams)
      d     <- buildParentItem("d", "D", ctx.streams)
      items <- ctx.streams.init(ItemsStreamDef, List(a, b, c, d))
    yield Model(items = items)

  def handleMessage(model: Model, ctx: MessageContext) =
    case Msg.ReorderNested(id) =>
      reorderNested(model, id, ctx.streams)
    case Msg.ReorderParents =>
      reorderParents(model, ctx.streams)

  def render(model: Model) =
    div(
      ul(
        idAttr       := "thelist",
        phx.onUpdate := "stream",
        model.items.stream { (domId, item) =>
          li(
            idAttr := domId,
            item.name,
            div(
              phx.onUpdate := "stream",
              styleAttr    := "display: flex; gap: 4px;",
              item.nested.stream { (nestedDomId, nestedItem) =>
                span(
                  idAttr := nestedDomId,
                  nestedItem.name
                )
              }
            ),
            button(
              phx.onClick(params => Msg.ReorderNested(params.getOrElse("id", ""))),
              phx.value("id") := item.id,
              "Reorder"
            )
          )
        }
      ),
      button(
        idAttr := "parent-reorder",
        phx.onClick(Msg.ReorderParents),
        "Reorder"
      )
    )

  private def reorderNested(
    model: Model,
    id: String,
    streams: Streams
  ): LiveIO[Model] =
    if id.isEmpty then model
    else
      for
        nested <- streams.init(
                    nestedStreamDef(id),
                    reorderedNestedItems,
                    reset = true
                  )
        maybeCurrent = model.items.entries.find(_.value.id == id).map(_.value)
        current <- maybeCurrent match
                     case Some(value) => ZIO.succeed(value)
                     case None        => buildParentItem(id, id.toUpperCase, streams)
        updatedParent = current.copy(nested = nested)
        items <- streams.insert(
                   ItemsStreamDef,
                   updatedParent,
                   updateOnly = true
                 )
      yield model.copy(items = items)

  private def reorderParents(model: Model, streams: Streams): LiveIO[Model] =
    for
      parentA <- model.items.entries.find(_.value.id == "a").map(_.value) match
                   case Some(value) => ZIO.succeed(value)
                   case None        => buildParentItem("a", "A", streams)
      parentE <- buildParentItem("e", "E", streams)
      parentF <- buildParentItem("f", "F", streams)
      parentG <- buildParentItem("g", "G", streams)
      items   <- streams.init(
                 ItemsStreamDef,
                 List(parentE, parentA, parentF, parentG),
                 reset = true
               )
    yield model.copy(items = items)

  private def buildParentItem(
    id: String,
    name: String,
    streams: Streams
  ): LiveIO[ParentItem] =
    streams
      .init(nestedStreamDef(id), defaultNestedItems)
      .map(nested => ParentItem(id = id, name = name, nested = nested))
end StreamNestedComponentResetLiveView

object StreamNestedComponentResetLiveView:
  final case class NestedItem(id: String, name: String)
  final case class ParentItem(id: String, name: String, nested: LiveStream[NestedItem])
  final case class Model(items: LiveStream[ParentItem])

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

  private def nestedStreamDef(parentId: String): LiveStreamDef[NestedItem] =
    LiveStreamDef[NestedItem](
      s"nested-items-$parentId",
      item => s"nested-items-$parentId-${item.id}"
    )
end StreamNestedComponentResetLiveView

class StreamInsideForLiveView
    extends LiveView[StreamInsideForLiveView.Msg, StreamInsideForLiveView.Model]:
  import StreamInsideForLiveView.*

  def mount(ctx: MountContext) =
    ctx.streams
      .init(ItemsStreamDef, InitialItems)
      .map(items => Model(items = items))

  def handleMessage(model: Model, ctx: MessageContext) =
    (_: Msg) => model

  def render(model: Model) =
    div(
      List(1).map(_ =>
        ul(
          idAttr       := "thelist",
          phx.onUpdate := "stream",
          model.items.stream { (domId, item) =>
            li(
              idAttr := domId,
              item.name
            )
          }
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
