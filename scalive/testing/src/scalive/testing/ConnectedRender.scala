package scalive.testing

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.TimeoutException
import scala.annotation.targetName
import scala.jdk.CollectionConverters.*

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import zio.*
import zio.http.*
import zio.json.ast.Json

import scalive.*
import scalive.protocol.phoenix.*

/** Joins LiveViews through production route admission and connection supervision without starting a
  * network server.
  *
  * See [testing LiveViews](https://scalive.dev/guides/testing).
  */
object ConnectedRender:
  private val DefaultConfig = ZioHttpConfig(
    "scalive-testing-signing-secret-0000000000000000",
    Duration.ofMinutes(30),
    secureCookie = false,
    allowedWebSocketOrigins = Set(WebSocketOrigin.https("scalive.test"))
  ).fold(throw _, identity)

  /** Renders a page, then connects to it as a browser would, and returns the connected view.
    *
    * This form mounts one LiveView at the root route, as in
    * `ConnectedRender.join(CounterLiveView())`. The returned view keeps the LiveView's message type
    * for [[ConnectedView.send]]. Run it inside `ZIO.scoped`, which closes the connection. See [your
    * first test](https://scalive.dev/learn/testing-your-liveview).
    */
  def join[Msg, Model](liveView: LiveView[Msg, Model]): RIO[Scope, ConnectedView[Msg]] =
    join(
      Live.router(scalive.live -> liveView),
      DefaultConfig,
      Request.get(URL.root)
    ).map(_.asInstanceOf[ConnectedView[Msg]])

  /** Renders a page, then connects to it as a browser would, and returns the connected view.
    *
    * This form mounts a routed LiveView on `route`, at the URL that `params` encode, as in
    * `ConnectedRender.join(Routes.search, SearchLiveView(), Some("LiveView"))`. The returned view
    * keeps the LiveView's message type for [[ConnectedView.send]]. Fails with
    * [[LiveLocation.EncodingException]] when `params` cannot be encoded.
    */
  def join[A, Params, Msg, Model](
    route: LiveRoute[A, Params],
    liveView: LiveView.Routed[Msg, Model, Params],
    params: Params
  ): RIO[Scope, ConnectedView[Msg]] =
    for
      location <- ZIO
                    .fromEither(route.locationEither(params))
                    .mapError(new LiveLocation.EncodingException(_))
      url  <- ZIO.fromEither(URL.decode(location.href))
      view <- join(Live.router(route -> liveView), DefaultConfig, Request.get(url))
    yield view.asInstanceOf[ConnectedView[Msg]]

  /** Renders a page, then connects to it as a browser would, and returns the connected view.
    *
    * This form executes `request` against a whole application, validates its bootstrap credentials,
    * and starts its connected lifecycle through the production Phoenix transport session. Fails
    * when the disconnected render or the join fails. Connect parameters are untrusted client JSON;
    * the harness replaces `_mounts` with its own progressing join counter.
    */
  def join[R](
    application: LiveApplication[R],
    config: ZioHttpConfig,
    request: Request,
    connectParams: Map[String, Json] = Map.empty
  ): ZIO[R & Scope, Throwable, ConnectedView[Nothing]] =
    open(application, config, request, connectParams).flatMap(
      _.join.mapError(_.toThrowable)
    )

  /** Executes the disconnected route and returns a stateful client that can join and reconnect with
    * the page's retained bootstrap credentials. Connect parameters are untrusted client JSON; the
    * harness replaces `_mounts` with its own progressing join counter.
    */
  def open[R](
    application: LiveApplication[R],
    config: ZioHttpConfig,
    request: Request,
    connectParams: Map[String, Json] = Map.empty
  ): ZIO[R, Throwable, ConnectedClient[R]] =
    for
      routes    <- ZioHttp.routes(application, config)
      page      <- DisconnectedRender.run(routes, request)
      bootstrap <- bootstrap(page, request.url)
      active    <- Ref.make(Option.empty[ConnectedSession])
      mounts    <- Ref.make(0L)
      gate      <- Semaphore.make(1L)
    yield new ConnectedClient(
      application,
      config,
      request,
      connectParams,
      bootstrap,
      active,
      mounts,
      gate
    )

  private def bootstrap(page: RenderedPage, url: URL): Task[Bootstrap] = ZIO.attempt {
    val document = Jsoup.parse(page.html)
    val roots    = document
      .select("[data-phx-main][data-phx-session][data-phx-static]").asScala.toVector
    val root   = exactlyOne(roots, "LiveView root")
    val rootId = requiredAttribute(root, "id")
    val csrf   = Option(document.selectFirst("meta[name=csrf-token]"))
      .map(requiredAttribute(_, "content"))
      .getOrElse(throw IllegalArgumentException("Missing CSRF meta tag."))
    val csrfCookie = page.response
      .headers(Header.SetCookie).map(_.value)
      .find(_.name == "_scalive_csrf")
      .map(_.content)
      .getOrElse(throw IllegalArgumentException("Missing CSRF response cookie."))
    Bootstrap(
      topic = s"lv:$rootId",
      session = requiredAttribute(root, "data-phx-session"),
      static = requiredAttribute(root, "data-phx-static"),
      csrfToken = csrf,
      csrfCookie = csrfCookie,
      url = url
    )
  }

  private def requiredAttribute(element: Element, name: String): String =
    Option
      .when(element.hasAttr(name))(element.attr(name)).filter(_.nonEmpty)
      .getOrElse(throw IllegalArgumentException(s"Missing $name attribute."))

  private def exactlyOne(elements: Vector[Element], description: String): Element =
    elements match
      case Vector(element) => element
      case _               =>
        throw IllegalArgumentException(s"Expected one $description, found ${elements.size}.")

  final private[testing] case class Bootstrap(
    topic: String,
    session: String,
    static: String,
    csrfToken: String,
    csrfCookie: String,
    url: URL)
end ConnectedRender

/** A protocol-visible reason why a routed connected join did not install a LiveView. */
enum ConnectedJoinFailure:
  /** The signed join or connected mount authorization was rejected. */
  case Unauthorized

  /** The server requires a fresh disconnected render. */
  case Stale

  /** The physical transport closed while the join was pending. */
  case Disconnected

  /** Connected mount requested a redirect instead of installing a view.
    *
    * @param to
    *   The redirect destination.
    */
  case Redirect(to: URL)

  /** The in-process transport failed outside a protocol-visible join outcome.
    *
    * @param error
    *   The underlying failure.
    */
  case Transport(error: Throwable)

  private[testing] def toThrowable: Throwable = this match
    case Unauthorized     => Exception("Connected LiveView join was unauthorized.")
    case Stale            => Exception("Connected LiveView join was stale.")
    case Disconnected     => Exception("Connected LiveView transport closed during join.")
    case Redirect(to)     => Exception(s"Connected LiveView join redirected to ${to.encode}.")
    case Transport(error) => error
end ConnectedJoinFailure

/** The semantic result of one connected browser or server-message action. */
enum ConnectedAction:
  /** The action completed without terminal navigation. */
  case Rendered

  /** The action requested a same-session route replacement that may be followed explicitly.
    *
    * @param navigation
    *   The requested navigation, which [[ConnectedNavigation.follow]] joins.
    */
  case LiveNavigation(navigation: ConnectedNavigation)

  /** The action requested a full redirect and closed the test transport.
    *
    * @param to
    *   The redirect destination.
    */
  case Redirect(to: URL)

  /** The physical transport closed before the action received a reply. */
  case Disconnected

/** A same-session route replacement emitted by a connected action.
  *
  * @param destination
  *   The URL to navigate to.
  * @param replace
  *   Whether the navigation replaces the current browser-history entry instead of adding one.
  */
final class ConnectedNavigation private[testing] (
  val destination: URL,
  val replace: Boolean,
  followEffect: IO[ConnectedJoinFailure, ConnectedView[Nothing]]):
  /** Replaces the root through the production redirect-join admission path. */
  def follow: IO[ConnectedJoinFailure, ConnectedView[Nothing]] = followEffect

/** A routed page bootstrap that can create fresh physical transports for reconnect tests. */
final class ConnectedClient[-R] private[testing] (
  application: LiveApplication[R],
  config: ZioHttpConfig,
  request: Request,
  connectParams: Map[String, Json],
  bootstrap: ConnectedRender.Bootstrap,
  active: Ref[Option[ConnectedSession]],
  mounts: Ref[Long],
  gate: Semaphore):

  /** Opens the initial physical transport and joins the routed root. */
  def join: ZIO[R & Scope, ConnectedJoinFailure, ConnectedView[Nothing]] = connect

  /** Closes any previous transport and rejoins with the retained page credentials. */
  def reconnect: ZIO[R & Scope, ConnectedJoinFailure, ConnectedView[Nothing]] = connect

  /** Closes the currently active physical transport, if any. */
  def disconnect: UIO[Unit] =
    gate.withPermit(active.getAndSet(None).flatMap(ZIO.foreachDiscard(_)(_.close)))

  private def connect: ZIO[R & Scope, ConnectedJoinFailure, ConnectedView[Nothing]] =
    gate.withPermit {
      for
        previous  <- active.getAndSet(None)
        _         <- ZIO.foreachDiscard(previous)(_.close)
        transport <- ZioHttp
                       .inProcessTransport(
                         application,
                         config,
                         request,
                         Some(bootstrap.csrfCookie),
                         Some(bootstrap.csrfToken)
                       ).mapError(ConnectedJoinFailure.Transport.apply)
        session <- ConnectedSession
                     .make(transport, bootstrap, connectParams, mounts)
                     .onExit {
                       case Exit.Success(_) => ZIO.unit
                       case Exit.Failure(_) => transport.close
                     }
                     .mapError(ConnectedJoinFailure.Transport.apply)
        view <- (session.joinRoot <* active.set(Some(session))).onExit {
                  case Exit.Success(_) => ZIO.unit
                  case Exit.Failure(_) => session.close *> active.set(None)
                }
      yield view
    }
end ConnectedClient

/** A semantic handle to one connected root or nested LiveView.
  *
  * Actions resolve bindings from the latest committed HTML and wait for the correlated lifecycle
  * reply. Runtime connection objects and protocol frames remain private implementation details.
  */
final class ConnectedView[-Msg] private[testing] (
  session: ConnectedSession,
  state: ConnectedViewState):

  /** Stable protocol topic text for diagnostics. It is not a runtime handle. */
  val topic: String = state.topic

  /** Returns the latest committed semantic HTML projection. */
  def html: UIO[String] = state.html

  /** Returns the first observed HTML satisfying `predicate`, checking the current snapshot before
    * waiting for uncorrelated output. One overall deadline bounds the wait; a timeout reports the
    * description and last observed HTML. The deadline uses the current ZIO clock. Predicate
    * exceptions and view closure fail the task.
    *
    * The predicate must be quick and nonblocking. Complete actions before waiting, and do not run
    * concurrent `awaitHtml` or `awaitDiff` calls on this view: they consume the same notifications.
    * This observes the latest server projection, not every intermediate state or the browser DOM.
    */
  def awaitHtml(description: String, timeout: Duration)(predicate: String => Boolean)
    : Task[String] =
    state.awaitHtml(description, timeout)(predicate)

  /** Returns the text of exactly one element matching `selector`. */
  def text(selector: String): Task[String] =
    html.flatMap(value => ZIO.attempt(ConnectedDom.selectOne(value, selector).text()))

  /** Returns the text of exactly one element matching `selector`.
    *
    * This form selects the element with the ID `ref`, which the view also renders.
    */
  @targetName("textOfRef")
  def text(ref: DomRef): Task[String] = text(s"#${ref.value}")

  /** Dispatches the `phx-click` binding on exactly one matching element. */
  def click(selector: String): Task[ConnectedAction] =
    dispatchClick(ConnectedDom.selectOne(_, selector))

  /** Dispatches the `phx-click` binding on exactly one matching element.
    *
    * This form clicks the element with the ID `ref`, which the view also renders.
    */
  @targetName("clickRef")
  def click(ref: DomRef): Task[ConnectedAction] = click(s"#${ref.value}")

  /** Dispatches the `phx-click` binding on exactly one button with the given text. */
  def clickButton(label: String): Task[ConnectedAction] =
    dispatchClick(ConnectedDom.buttonByLabel(_, label))

  /** Dispatches a form's `phx-change` binding.
    *
    * This form sends exactly `fields` to the form matching `selector`, with `target` as `_target`.
    * Use it for forms without a [[FormDefinition]], or to send a payload a browser would not.
    */
  def changeForm(
    selector: String,
    fields: Vector[(String, String)],
    target: Option[String] = None
  ): Task[ConnectedAction] =
    dispatchForm(selector, "phx-change", fields, target)

  /** Dispatches a form's `phx-change` binding.
    *
    * This form edits the one form that renders fields of `definition`, as a visitor changing the
    * assigned fields would, as in `view.changeForm(Signup.Definition)(Signup.Name.initial("Ada"))`.
    * The payload starts from the form's rendered values, with the assigned fields replaced. Like
    * the browser, it marks fields the visitor has neither edited nor submitted as unused, so only
    * edited fields show their errors. With one field assignment, `_target` names that field.
    */
  def changeForm[Owner](
    definition: FormDefinition[Owner, ?]
  )(
    assignments: FormInitial[Owner]*
  ): Task[ConnectedAction] =
    dispatchTypedForm(definition, assignments.toVector, submitter = None, submitting = false)

  /** Dispatches a form's `phx-submit` binding.
    *
    * This form sends exactly `fields`, then `submitter`, to the form matching `selector`. Use it
    * for forms without a [[FormDefinition]], or to send a payload a browser would not.
    */
  def submitForm(
    selector: String,
    fields: Vector[(String, String)],
    submitter: Option[RawFormSubmitter] = None
  ): Task[ConnectedAction] =
    val submitted = fields ++ submitter.map(value => value.name -> value.value)
    dispatchForm(selector, "phx-submit", submitted, None)

  /** Dispatches a form's `phx-submit` binding.
    *
    * This form submits the one form that renders fields of `definition`, with the assigned fields
    * replacing its rendered values, as in
    * `view.submitForm(Signup.Definition)(Signup.Name.initial("Ada"))`. Submitting marks every field
    * of the form as used, as the browser does.
    */
  def submitForm[Owner](
    definition: FormDefinition[Owner, ?]
  )(
    assignments: FormInitial[Owner]*
  ): Task[ConnectedAction] =
    dispatchTypedForm(definition, assignments.toVector, submitter = None, submitting = true)

  /** Dispatches a form's `phx-submit` binding.
    *
    * This form also sends the submit button that `submitter` describes, as in
    * `view.submitForm(Profile.Definition, Profile.Submitter.raw(Intent.Save))(...)`.
    */
  def submitForm[Owner](
    definition: FormDefinition[Owner, ?],
    submitter: RawFormSubmitter
  )(
    assignments: FormInitial[Owner]*
  ): Task[ConnectedAction] =
    dispatchTypedForm(definition, assignments.toVector, Some(submitter), submitting = true)

  /** Sends one typed server message and waits for its committed output. */
  def send(message: Msg): Task[ConnectedAction] = session.send(state, message)

  /** Waits for the next uncorrelated async, subscription, or component output. */
  def awaitDiff: Task[Unit] = state.awaitDiff

  /** Waits for the next uncorrelated navigation or physical disconnect. */
  def awaitAction: Task[ConnectedAction] = state.awaitAction

  /** Joins a nested LiveView registered in this view's latest committed HTML. */
  def joinNested(instanceId: String): RIO[Scope, ConnectedView[Nothing]] =
    session.joinNested(state, instanceId)

  /** Uploads one file through the file input that `definition` renders, as a visitor choosing it.
    *
    * It runs the upload's preflight and streams the whole file. The input must be rendered with
    * `liveFileInput`, and the upload must be hosted by the server.
    */
  def upload(
    definition: LiveUploadDef[?],
    fileName: String,
    mediaType: String,
    bytes: Chunk[Byte]
  ): Task[Unit] =
    for
      current <- html
      ref     <- ZIO.attempt(ConnectedDom.uploadRef(current, definition.name))
      _       <- session.upload(state, ref, fileName, mediaType, bytes)
    yield ()

  /** Returns the route URL currently owned by this exact joined generation. */
  def currentUrl: Task[URL] = state.currentUrl

  /** Reports whether this exact topic is still installed in the production supervisor. */
  def isJoined: UIO[Boolean] = state.isJoined

  /** Waits until this view's physical transport has closed. */
  def awaitDisconnected: UIO[Unit] = session.awaitClosed

  /** Leaves this lifecycle through the production supervisor. */
  def leave: Task[Unit] = session.leave(state)

  private def dispatchClick(findElement: String => Element): Task[ConnectedAction] =
    for
      current <- html
      event   <- ZIO.attempt {
                 val element = findElement(current)
                 val binding = ConnectedDom.requiredBinding(element, "phx-click")
                 val cid     = ConnectedDom.eventTarget(element)
                 RootEvent(
                   eventType = "click",
                   event = binding,
                   value = ConnectedDom.clickValue(element),
                   cid = cid
                 )
               }
      result <- session.dispatch(state, event)
    yield result

  private def dispatchForm(
    selector: String,
    attribute: String,
    fields: Vector[(String, String)],
    target: Option[String]
  ): Task[ConnectedAction] =
    for
      current <- html
      event   <- ZIO.attempt {
                 val element = ConnectedDom.selectOne(current, selector)
                 val binding = ConnectedDom.requiredBinding(element, attribute)
                 val cid     = ConnectedDom.eventTarget(element)
                 val meta    = target.map(value => Json.Obj("_target" -> Json.Str(value)))
                 RootEvent(
                   eventType = "form",
                   event = binding,
                   value = Json.Str(ConnectedDom.urlEncoded(fields)),
                   cid = cid,
                   meta = meta
                 )
               }
      result <- session.dispatch(state, event)
    yield result

  private def dispatchTypedForm[Owner](
    definition: FormDefinition[Owner, ?],
    assignments: Vector[FormInitial[Owner]],
    submitter: Option[RawFormSubmitter],
    submitting: Boolean
  ): Task[ConnectedAction] =
    val attribute = if submitting then "phx-submit" else "phx-change"
    for
      current  <- html
      used     <- state.usedFields.get
      prepared <- ZIO.attempt {
                    val form             = ConnectedDom.formFor(current, definition)
                    val binding          = ConnectedDom.requiredBinding(form, attribute)
                    val controls         = ConnectedDom.controls(form)
                    val (data, assigned) =
                      definition.overlay(FormData(controls.successful), assignments)
                    // The browser marks every field of a submitted form as used.
                    val nowUsed =
                      used ++ assigned ++ (if submitting then controls.names else Set.empty)
                    val fields = data.raw.flatMap { (name, value) =>
                      val unused = !nowUsed(name) && !controls.hiddenOnly(name)
                      Option.when(unused)(ConnectedDom.unusedName(name) -> "").toVector :+
                        (name -> value)
                    } ++ submitter.map(value => value.name -> value.value)
                    val target = assignments match
                      case Vector(field: FormFieldAssignment[Owner]) if !submitting =>
                        Some(field.field.path.name)
                      case _ => None
                    val event = RootEvent(
                      eventType = "form",
                      event = binding,
                      value = Json.Str(ConnectedDom.urlEncoded(fields)),
                      cid = ConnectedDom.eventTarget(form),
                      meta = target.map(name => Json.Obj("_target" -> Json.Str(name)))
                    )
                    event -> nowUsed
                  }
      (event, nowUsed) = prepared
      _      <- state.usedFields.set(nowUsed)
      result <- session.dispatch(state, event)
    yield result
    end for
  end dispatchTypedForm
end ConnectedView

private object ConnectedDom:
  def selectOne(html: String, selector: String): Element =
    exactlyOne(
      Jsoup.parseBodyFragment(html).select(selector).asScala.toVector,
      s"'$selector' element"
    )

  def buttonByLabel(html: String, label: String): Element =
    val buttons = Jsoup
      .parseBodyFragment(html)
      .select("button").asScala.toVector.filter(_.text() == label)
    exactlyOne(buttons, s"button labelled '$label'")

  def requiredBinding(element: Element, attribute: String): String =
    Option
      .when(element.hasAttr(attribute))(element.attr(attribute)).filter(_.nonEmpty)
      .getOrElse(throw IllegalArgumentException(s"Element has no $attribute binding."))

  def clickValue(element: Element): Json.Obj =
    val values = element.attributes().asList().asScala.toVector.collect {
      case attribute if attribute.getKey.startsWith("phx-value-") =>
        attribute.getKey.stripPrefix("phx-value-") -> Json.Str(attribute.getValue)
    } ++ Option.when(element.hasAttr("value"))("value" -> Json.Str(element.attr("value")))
    Json.Obj(values*)

  /** Returns the component that the Phoenix client sends `element`'s events to, or `None` for the
    * LiveView. Like the client, it reads `phx-target` from `element` only: a component ID, or a
    * selector whose match belongs to its closest component.
    */
  def eventTarget(element: Element): Option[Long] =
    Option.when(element.hasAttr("phx-target"))(element.attr("phx-target")).flatMap { target =>
      if target.matches("0|[1-9][0-9]*") then Some(target.toLong)
      else
        val matches = element.ownerDocument().select(target).asScala.toVector
        val matched = exactlyOne(matches, s"element matching the phx-target selector '$target'")
        Option(matched.closest("[data-phx-component]")).map(_.attr("data-phx-component").toLong)
    }

  /** The one rendered form owning a field that `definition` declares. */
  def formFor(html: String, definition: FormDefinition[?, ?]): Element =
    val forms = Jsoup.parseBodyFragment(html).select("form").asScala.toVector.filter { form =>
      FormOwner.controls(form).exists(control => definition.declares(control.attr("name")))
    }
    exactlyOne(forms, s"form rendering fields of the '${definition.root.name}' form definition")

  /** The named controls of one form, read as the browser's `FormData` reads them. */
  final case class FormControls(
    successful: Vector[(String, String)],
    names: Set[String],
    hiddenOnly: Set[String])

  def controls(form: Element): FormControls =
    val named      = FormOwner.controls(form).filter(_.attr("name").nonEmpty)
    val successful = named.filterNot(disabled).flatMap { element =>
      val name = element.attr("name")
      element.tagName match
        case "select" =>
          val options  = element.select("option").asScala.toVector
          val selected = options.filter(_.hasAttr("selected"))
          val chosen   =
            if element.hasAttr("multiple") then selected
            else selected.lastOption.orElse(options.headOption).toVector
          chosen.map(option =>
            name -> (if option.hasAttr("value") then option.attr("value") else option.text())
          )
        case "textarea" => Vector(name -> element.`val`())
        case "button"   => Vector.empty
        case _          =>
          element.attr("type").toLowerCase(java.util.Locale.ROOT) match
            case "checkbox" | "radio" =>
              Option
                .when(element.hasAttr("checked"))(
                  name -> (if element.hasAttr("value") then element.attr("value") else "on")
                ).toVector
            case "submit" | "button" | "reset" | "image" | "file" => Vector.empty
            case _ => Vector(name -> element.attr("value"))
    }
    val byName = named.groupBy(_.attr("name"))
    FormControls(
      successful,
      byName.keySet,
      byName.collect {
        case (name, elements) if elements.forall(_.attr("type").equalsIgnoreCase("hidden")) => name
      }.toSet
    )
  end controls

  /** The name Phoenix's client sends for an unused field, as in `profile[_unused_email]`. */
  def unusedName(name: String): String =
    val array   = name.endsWith("[]")
    val base    = if array then name.dropRight(2) else name
    val bracket = base.lastIndexOf('[')
    val marked  =
      if base.endsWith("]") && bracket >= 0 then
        base.take(bracket + 1) + "_unused_" + base.drop(bracket + 1)
      else "_unused_" + base
    if array then marked + "[]" else marked

  def uploadRef(html: String, name: String): String =
    val inputs = Jsoup
      .parseBodyFragment(html)
      .select("input[type=file][data-phx-upload-ref]").asScala.toVector
      .filter(_.attr("name") == name)
    exactlyOne(inputs, s"file input for the '$name' upload").attr("data-phx-upload-ref")

  /** Like the browser, a disabled fieldset disables every descendant except those inside its first
    * `legend` child, whether or not the fieldset is inside the control's form.
    */
  private def disabled(element: Element): Boolean =
    val ancestors = element.parents().asScala.toVector

    def disabledBy(fieldset: Element): Boolean =
      val firstLegend = fieldset.children().asScala.find(_.tagName == "legend")
      fieldset.hasAttr("disabled") && !firstLegend.exists(legend => ancestors.exists(_ eq legend))
    element.hasAttr("disabled") || ancestors.filter(_.tagName == "fieldset").exists(disabledBy)

  def urlEncoded(fields: Vector[(String, String)]): String =
    fields.map { case (name, value) => s"${encode(name)}=${encode(value)}" }.mkString("&")

  private def encode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8)

  private def exactlyOne(elements: Vector[Element], description: String): Element =
    elements match
      case Vector(element) => element
      case _               =>
        throw IllegalArgumentException(s"Expected one $description, found ${elements.size}.")
end ConnectedDom

final private class ConnectedSession(
  transport: ZioHttp.InProcessTransport,
  bootstrap: ConnectedRender.Bootstrap,
  baseConnectParams: Map[String, Json],
  mounts: Ref[Long],
  nextReference: Ref[Long],
  pending: Ref[Map[String, Promise[Throwable, PhoenixEnvelope]]],
  states: Ref[Map[(String, String), ConnectedViewState]]):

  def joinRoot: IO[ConnectedJoinFailure, ConnectedView[Nothing]] =
    for
      params <- nextConnectParams
      joined <- join(
                  bootstrap.topic,
                  RootJoin(
                    url = Some(bootstrap.url.encode),
                    redirect = None,
                    flash = None,
                    session = bootstrap.session,
                    static = Some(bootstrap.static),
                    params = params,
                    sticky = false
                  )
                )
    yield joined

  def joinNested(parent: ConnectedViewState, instanceId: String): Task[ConnectedView[Nothing]] =
    for
      parentHtml <- parent.html
      element    <- ZIO.attempt {
                   val value = Jsoup.parseBodyFragment(parentHtml).getElementById(instanceId)
                   if value == null then
                     throw new java.util.NoSuchElementException(
                       s"No nested LiveView with id '$instanceId'."
                     )
                   else value
                 }
      sessionToken <- ZIO.attempt(ConnectedDom.requiredBinding(element, "data-phx-session"))
      topic = s"lv:$instanceId"
      joined <- join(
                  topic,
                  RootJoin(
                    url = None,
                    redirect = None,
                    flash = None,
                    session = sessionToken,
                    static = Option
                      .when(element.hasAttr("data-phx-static"))(
                        element.attr("data-phx-static")
                      ).filter(_.nonEmpty),
                    params = Map.empty,
                    sticky = element.hasAttr("data-phx-sticky")
                  )
                ).mapError(_.toThrowable)
    yield joined

  def dispatch(state: ConnectedViewState, event: RootEvent): Task[ConnectedAction] =
    request(
      state.joinRef,
      state.topic,
      "event",
      Json.Obj(
        "type"    -> Json.Str(event.eventType),
        "event"   -> Json.Str(event.event),
        "value"   -> event.value,
        "cid"     -> event.cid.fold[Json](Json.Null)(value => Json.Num(BigDecimal(value))),
        "uploads" -> event.uploads.fold[Json](Json.Obj.empty)(identity),
        "meta"    -> event.meta.fold[Json](Json.Obj.empty)(identity)
      )
    ).flatMap(action).catchSome { case _: ZioHttp.InProcessTransportClosed =>
      ZIO.succeed(ConnectedAction.Disconnected)
    }

  def send(state: ConnectedViewState, message: Any): Task[ConnectedAction] =
    (for
      ref      <- freshReference
      response <- awaitReply(ref)(transport.sendMessage(state.topic, state.joinRef, ref, message))
      result   <- action(response)
    yield result).catchSome { case _: ZioHttp.InProcessTransportClosed =>
      ZIO.succeed(ConnectedAction.Disconnected)
    }

  def upload(
    state: ConnectedViewState,
    uploadRef: String,
    fileName: String,
    mediaType: String,
    bytes: Chunk[Byte]
  ): Task[Unit] =
    for
      entryRef  <- freshReference.map(_.value)
      preflight <- request(
                     state.joinRef,
                     state.topic,
                     "allow_upload",
                     Json.Obj(
                       "ref"     -> Json.Str(uploadRef),
                       "entries" -> Json.Arr(
                         Json.Obj(
                           "ref"           -> Json.Str(entryRef),
                           "name"          -> Json.Str(fileName),
                           "relative_path" -> Json.Null,
                           "size"          -> Json.Num(BigDecimal(bytes.length)),
                           "type"          -> Json.Str(mediaType),
                           "last_modified" -> Json.Null,
                           "meta"          -> Json.Null
                         )
                       ),
                       "cid" -> Json.Null
                     )
                   )
      preflightResponse <- successfulResponse(preflight)
      entries           <- requiredObject(preflightResponse, "entries")
      clientConfig      <- requiredObject(preflightResponse, "config")
      chunkSize         <- requiredPositiveInt(clientConfig, "chunk_size")
      token             <- entries.fields.toMap.get(entryRef) match
                 case Some(Json.Str(value)) => ZIO.succeed(value)
                 case Some(_)               =>
                   ZIO.fail(Exception("The connected test harness supports hosted uploads only."))
                 case None => ZIO.fail(Exception(s"Upload preflight omitted entry '$entryRef'."))
      uploadJoinRef <- freshReference
      uploadTopic = s"lvu:$entryRef"
      left <- Ref.make(false)
      _    <- (for
             uploadJoin <- request(
                             uploadJoinRef,
                             uploadTopic,
                             "phx_join",
                             Json.Obj("token" -> Json.Str(token))
                           )
             _ <- successfulResponse(uploadJoin)
             _ <- ZIO.foreachDiscard(uploadChunks(bytes, chunkSize)) { chunk =>
                    for
                      chunkRef <- freshReference
                      frame    <- ZIO.fromEither(
                                 PhoenixUploadProtocol
                                   .encodeBinary(
                                     PhoenixUploadBinaryFrame(
                                       uploadJoinRef.value,
                                       chunkRef.value,
                                       uploadTopic,
                                       "chunk",
                                       chunk
                                     )
                                   ).left.map(Exception(_))
                               )
                      chunkReply <- awaitReply(chunkRef)(transport.sendBinary(frame))
                      _          <- successfulResponse(chunkReply)
                    yield ()
                  }
             _ <- request(uploadJoinRef, uploadTopic, "phx_leave", Json.Obj.empty)
             _ <- left.set(true)
           yield ()).ensuring(
             left.get.flatMap(completed =>
               ZIO.unless(completed)(sendUploadLeave(uploadJoinRef, uploadTopic).forkDaemon.unit)
             )
           )
    yield ()

  def leave(state: ConnectedViewState): Task[Unit] =
    request(state.joinRef, state.topic, "phx_leave", Json.Obj.empty).unit
      .ensuring(retire(state) *> retireMissingStates)

  def awaitClosed: UIO[Unit] = transport.awaitClosed

  def close: UIO[Unit] = transport.close

  private def join(
    topic: String,
    payload: RootJoin
  ): IO[ConnectedJoinFailure, ConnectedView[Nothing]] =
    (for
      joinRef <- freshReference
      state   <- ConnectedViewState.make(transport, topic, joinRef)
      _       <- states.update(_.updated(state.key, state))
      reply   <- request(
                 joinRef,
                 topic,
                 "phx_join",
                 Json.Obj(
                   "url"      -> payload.url.fold[Json](Json.Null)(Json.Str(_)),
                   "redirect" -> payload.redirect.fold[Json](Json.Null)(Json.Str(_)),
                   "flash"    -> payload.flash.fold[Json](Json.Null)(Json.Str(_)),
                   "session"  -> Json.Str(payload.session),
                   "static"   -> payload.static.fold[Json](Json.Null)(Json.Str(_)),
                   "params"   -> Json.Obj(payload.params.toVector*),
                   "sticky"   -> Json.Bool(payload.sticky)
                 )
               ).onError(_ => states.update(_ - state.key) *> state.close)
      _ <- joinResponse(reply).onError(_ => states.update(_ - state.key) *> state.close)
      _ <- state.markJoined
    yield new ConnectedView(this, state)).mapError {
      case failure: ConnectedJoinFailureException => failure.failure
      case _: ZioHttp.InProcessTransportClosed    => ConnectedJoinFailure.Disconnected
      case error                                  => ConnectedJoinFailure.Transport(error)
    }

  private def followRoot(
    destination: String,
    flash: Option[String]
  ): IO[ConnectedJoinFailure, ConnectedView[Nothing]] =
    for
      params <- nextConnectParams
      joined <- join(
                  bootstrap.topic,
                  RootJoin(
                    url = None,
                    redirect = Some(destination),
                    flash = flash,
                    session = bootstrap.session,
                    static = Some(bootstrap.static),
                    params = params,
                    sticky = false
                  )
                )
      _ <- retireMissingStates
    yield joined

  private def action(envelope: PhoenixEnvelope): Task[ConnectedAction] =
    for
      response <- successfulResponse(envelope)
      result   <- response.fields.toMap.get("live_redirect") match
                  case Some(value: Json.Obj) => liveNavigation(value)
                  case _                     =>
                    response.fields.toMap.get("redirect") match
                      case Some(value: Json.Obj) => redirect(value).tap(_ => transport.close)
                      case _                     => ZIO.succeed(ConnectedAction.Rendered)
    yield result

  private def liveNavigation(value: Json.Obj): Task[ConnectedAction] =
    for
      destinationText <- requiredString(value, "to")
      destination     <- decodeUrl(destinationText)
      kind       = value.fields.toMap.get("kind").flatMap(_.asString)
      flash      = value.fields.toMap.get("flash").flatMap(_.asString)
      navigation = new ConnectedNavigation(
                     destination,
                     replace = kind.contains("replace"),
                     followRoot(destinationText, flash)
                   )
    yield ConnectedAction.LiveNavigation(navigation)

  private def redirect(value: Json.Obj): Task[ConnectedAction] =
    requiredString(value, "to")
      .flatMap(decodeUrl)
      .map(ConnectedAction.Redirect(_))

  private def request(
    joinRef: PhoenixRef.Value,
    topic: String,
    event: String,
    payload: Json
  ): Task[PhoenixEnvelope] =
    for
      ref <- freshReference
      envelope = PhoenixEnvelope(joinRef, ref, topic, event, payload)
      reply <- awaitReply(ref)(transport.send(envelope))
    yield reply

  private def sendUploadLeave(joinRef: PhoenixRef.Value, topic: String): UIO[Unit] =
    freshReference.flatMap(ref =>
      transport.send(PhoenixEnvelope(joinRef, ref, topic, "phx_leave", Json.Obj.empty)).ignore
    )

  private def awaitReply(
    ref: PhoenixRef.Value
  )(
    send: Task[Unit]
  ): Task[PhoenixEnvelope] =
    for
      response <- Promise.make[Throwable, PhoenixEnvelope]
      _        <- pending.update(_.updated(ref.value, response))
      _        <- send.onError(error => pending.update(_ - ref.value) *> response.failCause(error))
      reply    <-
        response.await
          .timeoutFail(Exception("Timed out waiting for connected transport reply."))(5.seconds)
          .ensuring(pending.update(_ - ref.value))
    yield reply

  private def freshReference: UIO[PhoenixRef.Value] =
    nextReference.modify(value => PhoenixRef.Value(value.toString) -> (value + 1L))

  private def nextConnectParams: UIO[Map[String, Json]] =
    mounts
      .getAndUpdate(_ + 1L).map(value =>
        baseConnectParams.updated("_mounts", Json.Num(BigDecimal(value)))
      )

  private def joinResponse(envelope: PhoenixEnvelope): Task[Unit] =
    reply(envelope).flatMap { case (status, response) =>
      if status == "ok" then ZIO.unit
      else
        val fields  = response.fields.toMap
        val failure = fields.get("reason").flatMap(_.asString) match
          case Some("unauthorized") => ConnectedJoinFailure.Unauthorized
          case Some("stale")        => ConnectedJoinFailure.Stale
          case _                    =>
            fields.get("redirect").orElse(fields.get("live_redirect")) match
              case Some(value: Json.Obj) =>
                value.fields.toMap
                  .get("to").flatMap(_.asString).flatMap(URL.decode(_).toOption)
                  .fold[ConnectedJoinFailure](
                    ConnectedJoinFailure.Transport(Exception("Invalid connected join redirect."))
                  )(ConnectedJoinFailure.Redirect.apply)
              case _ => ConnectedJoinFailure.Transport(Exception("Connected LiveView join failed."))
        ZIO.fail(ConnectedJoinFailureException(failure))
    }

  private def successfulResponse(envelope: PhoenixEnvelope): Task[Json.Obj] =
    reply(envelope).flatMap { case (status, response) =>
      if status == "ok" then ZIO.succeed(response)
      else ZIO.fail(Exception(s"Connected transport request failed: $response"))
    }

  private def reply(envelope: PhoenixEnvelope): Task[(String, Json.Obj)] = envelope.payload match
    case Json.Obj(rawFields) =>
      val fields = rawFields.toMap
      (fields.get("status"), fields.get("response")) match
        case (Some(Json.Str(status)), Some(response: Json.Obj)) => ZIO.succeed(status -> response)
        case _ => ZIO.fail(Exception("Phoenix reply has an invalid payload."))
    case _ => ZIO.fail(Exception("Phoenix reply payload is not an object."))

  private def requiredObject(value: Json.Obj, name: String): Task[Json.Obj] =
    value.fields.toMap.get(name) match
      case Some(result: Json.Obj) => ZIO.succeed(result)
      case _                      => ZIO.fail(Exception(s"Missing object field '$name'."))

  private def requiredString(value: Json.Obj, name: String): Task[String] =
    value.fields.toMap.get(name).flatMap(_.asString) match
      case Some(result) => ZIO.succeed(result)
      case None         => ZIO.fail(Exception(s"Missing string field '$name'."))

  private def requiredPositiveInt(value: Json.Obj, name: String): Task[Int] =
    value.fields.toMap.get(name) match
      case Some(Json.Num(number)) =>
        ZIO
          .attempt(number.intValueExact()).filterOrFail(_ > 0)(
            Exception(s"Field '$name' must be a positive integer.")
          )
      case _ => ZIO.fail(Exception(s"Missing positive integer field '$name'."))

  private def uploadChunks(bytes: Chunk[Byte], chunkSize: Int): Vector[Chunk[Byte]] =
    if bytes.isEmpty then Vector(Chunk.empty)
    else
      Vector.tabulate((bytes.length + chunkSize - 1) / chunkSize) { index =>
        val start = index * chunkSize
        bytes.slice(start, math.min(bytes.length, start + chunkSize))
      }

  private def decodeUrl(value: String): Task[URL] =
    ZIO.fromEither(URL.decode(value).left.map(error => Exception(error.getMessage)))

  private def handleOutput(envelope: PhoenixEnvelope): UIO[Unit] =
    envelope.ref match
      case PhoenixRef.Value(value) =>
        pending.modify(current => current.get(value) -> (current - value)).flatMap {
          case Some(response) => response.succeed(envelope).unit
          case None           => signal(envelope)
        }
      case PhoenixRef.Null => signal(envelope)

  private def signal(envelope: PhoenixEnvelope): UIO[Unit] = envelope.joinRef match
    case value: PhoenixRef.Value =>
      states.get.flatMap { current =>
        current.get(envelope.topic -> value.value) match
          case Some(state) =>
            envelope.event match
              case "diff" | "live_patch" => state.signalDiff
              case "live_redirect"       =>
                envelope.payload match
                  case payload: Json.Obj =>
                    liveNavigation(payload).orDie.flatMap(state.signalAction)
                  case _ => ZIO.dieMessage("Live navigation payload is not an object.")
              case "redirect" =>
                envelope.payload match
                  case payload: Json.Obj =>
                    redirect(payload).orDie.flatMap(action =>
                      state.signalAction(action) *> transport.close
                    )
                  case _ => ZIO.dieMessage("Redirect payload is not an object.")
              case "phx_close" | "phx_error" => retire(state) *> retireMissingStates
              case _                         => ZIO.unit
          case None => ZIO.unit
      }
    case PhoenixRef.Null => ZIO.unit

  private def failAll(error: Throwable): UIO[Unit] =
    pending
      .getAndSet(Map.empty).flatMap(values =>
        ZIO.foreachDiscard(values.values)(_.fail(error).unit)
      ) *> states
      .getAndSet(Map.empty).flatMap(values =>
        ZIO.foreachDiscard(values.values)(state =>
          state.signalAction(ConnectedAction.Disconnected) *> state.close
        )
      )

  private def retire(state: ConnectedViewState): UIO[Unit] =
    states.update(_ - state.key) *> state.close

  private def retireMissingStates: UIO[Unit] =
    states.get.flatMap(current =>
      ZIO.foreachDiscard(current.values)(state =>
        state.isMissing.flatMap(missing => ZIO.when(missing)(retire(state)))
      )
    )

  private def runOutput: UIO[Unit] =
    transport.receive.flatMap(handleOutput).forever.catchAll(failAll)
end ConnectedSession

private object ConnectedSession:
  def make(
    transport: ZioHttp.InProcessTransport,
    bootstrap: ConnectedRender.Bootstrap,
    connectParams: Map[String, Json],
    mounts: Ref[Long]
  ): RIO[Scope, ConnectedSession] =
    for
      nextReference <- Ref.make(1L)
      pending       <- Ref.make(Map.empty[String, Promise[Throwable, PhoenixEnvelope]])
      states        <- Ref.make(Map.empty[(String, String), ConnectedViewState])
      session = new ConnectedSession(
                  transport,
                  bootstrap,
                  connectParams,
                  mounts,
                  nextReference,
                  pending,
                  states
                )
      _ <- session.runOutput.forkScoped
    yield session

final private case class ConnectedJoinFailureException(failure: ConnectedJoinFailure)
    extends Exception(failure.toString)

final private class ConnectedViewState(
  transport: ZioHttp.InProcessTransport,
  val topic: String,
  val joinRef: PhoenixRef.Value,
  diffs: Queue[Unit],
  actions: Queue[ConnectedAction],
  joined: Ref[Boolean],
  closed: Promise[Nothing, Unit],
  val usedFields: Ref[Set[String]]):

  val key: (String, String) = topic -> joinRef.value

  def html: UIO[String] = transport.html(topic, joinRef).orDie

  def currentUrl: Task[URL] = transport.currentUrl(topic, joinRef)

  def isJoined: UIO[Boolean] = transport.isJoined(topic, joinRef)

  def isMissing: UIO[Boolean] =
    joined.get.flatMap(installed => ZIO.ifZIO(isJoined)(ZIO.succeed(false), ZIO.succeed(installed)))

  def awaitHtml(description: String, timeout: Duration)(predicate: String => Boolean)
    : Task[String] =
    Ref.make(Option.empty[String]).flatMap { lastObserved =>
      val closedFailure = ZIO.fail(
        Exception(s"Connected LiveView '$topic' closed while waiting for '$description'.")
      )

      def loop: Task[String] =
        for
          alreadyClosed <- closed.isDone.zipWith(transport.isClosed)(_ || _)
          _             <- ZIO.when(alreadyClosed)(closedFailure)
          current       <- transport.html(topic, joinRef)
          _             <- lastObserved.set(Some(current))
          matches       <- ZIO.attempt(predicate(current))
          result        <- if matches then ZIO.succeed(current) else diffs.take *> loop
        yield result

      val awaitClose = closed.await.raceFirst(transport.awaitClosed) *> closedFailure
      loop.raceFirst(awaitClose).timeout(timeout).flatMap {
        case Some(matchingHtml) => ZIO.succeed(matchingHtml)
        case None               =>
          lastObserved.get.flatMap { last =>
            ZIO.fail(
              new TimeoutException(
                s"Timed out after $timeout waiting for '$description' on connected LiveView '$topic'.\n" +
                  s"Last observed HTML:\n${last.getOrElse("<no HTML observed>")}"
              )
            )
          }
      }
    }

  def awaitDiff: Task[Unit] =
    diffs.take.timeoutFail(Exception("Timed out waiting for connected output."))(5.seconds)

  def awaitAction: Task[ConnectedAction] =
    actions.take.timeoutFail(Exception("Timed out waiting for connected action."))(5.seconds)

  def signalDiff: UIO[Unit] = diffs.offer(()).unit

  def signalAction(action: ConnectedAction): UIO[Unit] = actions.offer(action).unit

  def markJoined: UIO[Unit] = joined.set(true)

  def close: UIO[Unit] = closed.succeed(()).unit
end ConnectedViewState

private object ConnectedViewState:
  def make(
    transport: ZioHttp.InProcessTransport,
    topic: String,
    joinRef: PhoenixRef.Value
  ): UIO[ConnectedViewState] =
    for
      diffs   <- Queue.sliding[Unit](64)
      actions <- Queue.sliding[ConnectedAction](16)
      joined  <- Ref.make(false)
      closed  <- Promise.make[Nothing, Unit]
      used    <- Ref.make(Set.empty[String])
    yield new ConnectedViewState(transport, topic, joinRef, diffs, actions, joined, closed, used)
