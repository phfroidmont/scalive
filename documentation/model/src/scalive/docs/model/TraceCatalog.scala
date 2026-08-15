package scalive.docs.model

final case class TraceDefinition(
  id: String,
  title: String,
  description: String,
  participants: Vector[TraceParticipant],
  phases: Vector[TracePhase])

final case class TraceParticipant(id: String, label: String, description: String)

final case class TracePhase(id: String, title: String, steps: Vector[TraceStep])

final case class TraceEvidence(
  label: String,
  summary: String,
  facts: Vector[(String, String)] = Vector.empty,
  code: Option[String] = None,
  producer: Option[String] = None,
  highlights: Vector[String] = Vector.empty,
  correlation: Vector[(String, String)] = Vector.empty,
  metadata: Vector[(String, String)] = Vector.empty,
  projection: Option[TraceEvidenceProjection] = None)

final case class TraceEvidenceProjection(
  typeName: String,
  summary: String,
  fields: Vector[(String, String)])

sealed trait TraceStep

object TraceStep:
  final case class Operation(
    participant: String,
    label: String,
    description: String,
    evidence: Vector[TraceEvidence] = Vector.empty)
      extends TraceStep
  final case class Message(
    from: String,
    to: String,
    label: String,
    description: String,
    evidence: Vector[TraceEvidence] = Vector.empty)
      extends TraceStep
  final case class Boundary(
    label: String,
    description: String,
    evidence: Vector[TraceEvidence] = Vector.empty)
      extends TraceStep

object TraceCatalog:
  val HttpGet = TraceDefinition(
    id = "http-get",
    title = "Disconnected HTTP render",
    description =
      "One HTTP request produces useful HTML, then releases its temporary model before LiveSocket connects.",
    participants = Vector(
      TraceParticipant("browser", "Browser", "Initiates navigation and receives the response."),
      TraceParticipant("runtime", "Scalive runtime", "Owns the HTTP request lifecycle."),
      TraceParticipant("live-view", "Your LiveView", "Builds the temporary model and typed HTML.")
    ),
    phases = Vector(
      TracePhase(
        "request",
        "Request",
        Vector(
          TraceStep.Operation("browser", "Navigate", "Starts navigation to the Live route."),
          TraceStep.Message("browser", "runtime", "HTTP GET", "Requests the typed Live route."),
          TraceStep.Operation(
            "runtime",
            "Prepare lifecycle",
            "Decodes the route and prepares the request-scoped lifecycle."
          )
        )
      ),
      TracePhase(
        "disconnected-lifecycle",
        "Disconnected lifecycle",
        Vector(
          TraceStep.Message(
            "runtime",
            "live-view",
            "mount (disconnected)",
            "Creates model A with connected = false.",
            Vector(
              TraceEvidence(
                "Mount context",
                "This mount runs during the HTTP request, before a live connection exists.",
                Vector("connected" -> "false", "model lifetime" -> "this HTTP request")
              )
            )
          ),
          TraceStep.Message(
            "live-view",
            "runtime",
            "Model A",
            "Returns temporary immutable state for the first render."
          ),
          TraceStep.Message(
            "runtime",
            "live-view",
            "render(model A)",
            "Projects the disconnected model into typed HTML."
          ),
          TraceStep.Message(
            "live-view",
            "runtime",
            "Typed HTML tree",
            "Returns page content before layouts are applied."
          ),
          TraceStep.Operation(
            "runtime",
            "Assemble document",
            "Applies layouts, creates the live root, and embeds the signed session and CSRF metadata."
          )
        )
      ),
      TracePhase(
        "response-and-teardown",
        "Response and teardown",
        Vector(
          TraceStep.Message(
            "runtime",
            "browser",
            "HTML response",
            "Returns a useful HTTP response; the document can display before LiveSocket joins."
          ),
          TraceStep.Boundary(
            "End request lifecycle",
            "When LiveSocket connects, Scalive invokes mount again to create a fresh connected model.",
            Vector(
              TraceEvidence(
                "Lifecycle boundary",
                "The disconnected model is not transferred into the future socket lifecycle.",
                Vector(
                  "model A"    -> "released with the request",
                  "next model" -> "fresh connected mount"
                )
              )
            )
          )
        )
      )
    )
  )

  val LiveSocketJoin = TraceDefinition(
    id = "live-socket-join",
    title = "Connected LiveSocket mount",
    description =
      "A LiveSocket join validates the document's bootstrap data, creates Model B, and reconciles the existing DOM.",
    participants = Vector(
      TraceParticipant(
        "browser",
        "Browser",
        "Discovers the live root, joins, and reconciles the DOM."
      ),
      TraceParticipant(
        "runtime",
        "Scalive runtime",
        "Validates the join and owns the socket lifecycle."
      ),
      TraceParticipant("live-view", "Your LiveView", "Builds the connected model and typed HTML.")
    ),
    phases = Vector(
      TracePhase(
        "connect-and-join",
        "Connect and join",
        Vector(
          TraceStep.Operation(
            "browser",
            "Discover live root",
            "Reads the root id, signed session, and CSRF metadata from the disconnected document."
          ),
          TraceStep.Message(
            "browser",
            "runtime",
            "Open LiveSocket",
            "Presents the browser-bound CSRF token and opens the WebSocket transport."
          ),
          TraceStep.Message(
            "browser",
            "runtime",
            "phx_join",
            "Sends the current URL, signed session, static tracking, and untrusted connect parameters."
          ),
          TraceStep.Operation(
            "runtime",
            "Validate join",
            "Checks CSRF authorization, the topic-bound session, route, live session, mount claims, and root layout.",
            Vector(
              TraceEvidence(
                "Join inputs",
                "Signed server data establishes authority; browser-provided connect parameters do not.",
                Vector(
                  "signed session"     -> "signature, age, and topic verified",
                  "route and layout"   -> "must match the disconnected render",
                  "connect parameters" -> "untrusted input"
                )
              )
            )
          )
        )
      ),
      TracePhase(
        "connected-lifecycle",
        "Connected lifecycle",
        Vector(
          TraceStep.Message(
            "runtime",
            "live-view",
            "mount (connected)",
            "Runs connected mount aspects, decodes route parameters, and invokes mount with connected = true.",
            Vector(
              TraceEvidence(
                "Connected mount",
                "This is a fresh lifecycle; the disconnected model is unavailable.",
                Vector(
                  "connected"           -> "true",
                  "previous model"      -> "unavailable",
                  "model lifetime"      -> "this socket lifecycle",
                  "socket capabilities" -> "available during mount"
                )
              )
            )
          ),
          TraceStep.Message(
            "live-view",
            "runtime",
            "Model B",
            "Returns fresh immutable state rebuilt from route, session, and durable inputs."
          ),
          TraceStep.Message(
            "runtime",
            "live-view",
            "render(model B)",
            "Projects the connected model into typed HTML."
          ),
          TraceStep.Message(
            "live-view",
            "runtime",
            "Initial live tree",
            "Returns the connected tree, bindings, and page metadata."
          ),
          TraceStep.Operation(
            "runtime",
            "Commit Model B",
            "After render hooks succeed, stores Model B and its rendered snapshot, then computes the initial diff.",
            Vector(
              TraceEvidence(
                "Commit boundary",
                "The connected model becomes current only after the initial render path succeeds.",
                Vector(
                  "before render succeeds" -> "Model B is proposed",
                  "after render succeeds"  -> "Model B and its tree are committed"
                )
              )
            )
          )
        )
      ),
      TracePhase(
        "join-response",
        "Join response",
        Vector(
          TraceStep.Message(
            "runtime",
            "browser",
            "Initial rendered diff",
            "Replies with response.rendered for the connected tree, not a second HTML document."
          ),
          TraceStep.Operation(
            "browser",
            "Reconcile DOM",
            "Patches the existing disconnected DOM and marks the LiveView connected."
          )
        )
      )
    )
  )

  val entries: Vector[TraceDefinition] = Vector(HttpGet, LiveSocketJoin)

  def get(id: String): Option[TraceDefinition] = entries.find(_.id == id)

  def validate(definitions: Vector[TraceDefinition] = entries): Vector[String] =
    val errors = Vector.newBuilder[String]
    definitions.groupBy(_.id).foreach { case (id, matches) =>
      if matches.sizeIs > 1 then errors += s"duplicate trace id '$id'."
    }
    definitions.foreach { trace =>
      if !isKebabCase(trace.id) then
        errors += s"invalid trace id '${trace.id}'; expected lowercase kebab-case."
      if trace.title.trim.isEmpty then errors += s"trace '${trace.id}' title must not be blank."
      if trace.description.trim.isEmpty then
        errors += s"trace '${trace.id}' description must not be blank."
      if trace.participants.isEmpty then errors += s"trace '${trace.id}' must have participants."
      if trace.phases.isEmpty then errors += s"trace '${trace.id}' must have phases."
      trace.participants.groupBy(_.id).foreach { case (id, matches) =>
        if matches.sizeIs > 1 then errors += s"trace '${trace.id}' has duplicate participant '$id'."
      }
      trace.phases.groupBy(_.id).foreach { case (id, matches) =>
        if matches.sizeIs > 1 then errors += s"trace '${trace.id}' has duplicate phase '$id'."
      }
      val participantIds = trace.participants.map(_.id).toSet
      trace.participants.foreach { participant =>
        if !isKebabCase(participant.id) then
          errors += s"trace '${trace.id}' has invalid participant id '${participant.id}'."
        if participant.label.trim.isEmpty || participant.description.trim.isEmpty then
          errors += s"trace '${trace.id}' participant '${participant.id}' must have label and description."
      }
      trace.phases.foreach { phase =>
        if !isKebabCase(phase.id) then
          errors += s"trace '${trace.id}' has invalid phase id '${phase.id}'."
        if phase.title.trim.isEmpty || phase.steps.isEmpty then
          errors += s"trace '${trace.id}' phase '${phase.id}' must have title and steps."
        phase.steps.foreach {
          case TraceStep.Operation(participant, label, description, evidence) =>
            if !participantIds(participant) then
              errors += s"trace '${trace.id}' operation references unknown participant '$participant'."
            if label.trim.isEmpty || description.trim.isEmpty then
              errors += s"trace '${trace.id}' operation must have label and description."
            validateEvidence(trace.id, evidence, errors)
          case TraceStep.Message(from, to, label, description, evidence) =>
            Vector(from, to).filterNot(participantIds).distinct.foreach { participant =>
              errors += s"trace '${trace.id}' message references unknown participant '$participant'."
            }
            if from == to then errors += s"trace '${trace.id}' message must cross participants."
            if label.trim.isEmpty || description.trim.isEmpty then
              errors += s"trace '${trace.id}' message must have label and description."
            validateEvidence(trace.id, evidence, errors)
          case TraceStep.Boundary(label, description, evidence) =>
            if label.trim.isEmpty || description.trim.isEmpty then
              errors += s"trace '${trace.id}' boundary must have label and description."
            validateEvidence(trace.id, evidence, errors)
        }
      }
    }
    errors.result().distinct.sorted
  end validate

  def prose(trace: TraceDefinition): String =
    (Vector(trace.id, trace.title, trace.description) ++
      trace.participants.flatMap(participant =>
        Vector(participant.id, participant.label, participant.description)
      ) ++
      trace.phases.flatMap(phase =>
        Vector(phase.id, phase.title) ++ phase.steps.flatMap {
          case TraceStep.Operation(_, label, description, evidence) =>
            Vector(label, description) ++ evidence.flatMap(evidenceProse)
          case TraceStep.Message(_, _, label, description, evidence) =>
            Vector(label, description) ++ evidence.flatMap(evidenceProse)
          case TraceStep.Boundary(label, description, evidence) =>
            Vector(label, description) ++ evidence.flatMap(evidenceProse)
        }
      )).mkString(" ")

  private def evidenceProse(evidence: TraceEvidence): Vector[String] =
    Vector(evidence.label, evidence.summary) ++ evidence.producer.toVector ++ evidence.highlights ++
      evidence.facts.flatMap { case (name, value) => Vector(name, value) } ++
      evidence.correlation.flatMap { case (name, value) => Vector(name, value) } ++
      evidence.metadata.flatMap { case (name, value) => Vector(name, value) } ++
      evidence.projection.toVector.flatMap(value =>
        Vector(value.typeName, value.summary) ++ value.fields.flatMap { case (name, field) =>
          Vector(name, field)
        }
      ) ++ evidence.code.toVector

  private def validateEvidence(
    traceId: String,
    evidence: Vector[TraceEvidence],
    errors: scala.collection.mutable.Builder[String, Vector[String]]
  ): Unit =
    evidence.foreach { value =>
      if value.label.trim.isEmpty || value.summary.trim.isEmpty then
        errors += s"trace '$traceId' evidence must have label and summary."
      val namedValues = value.facts ++ value.correlation ++ value.metadata ++
        value.projection.toVector.flatMap(_.fields)
      val blankProjection = value.projection.exists(projection =>
        projection.typeName.trim.isEmpty || projection.summary.trim.isEmpty
      )
      if namedValues.exists { case (name, fact) => name.trim.isEmpty || fact.trim.isEmpty } ||
        value.producer.exists(_.trim.isEmpty) || value.highlights.exists(_.trim.isEmpty) ||
        blankProjection
      then errors += s"trace '$traceId' evidence facts must not be blank."
    }

  private def isKebabCase(value: String): Boolean =
    value.matches("[a-z0-9]+(?:-[a-z0-9]+)*")
end TraceCatalog
