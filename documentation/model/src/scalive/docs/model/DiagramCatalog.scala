package scalive.docs.model

import zio.json.*

final case class DiagramIntrinsicSize(width: Int, height: Int) derives JsonCodec

final case class DiagramAsset(
  label: String,
  filename: String,
  intrinsicSize: DiagramIntrinsicSize)
    derives JsonCodec

enum DiagramLayout derives JsonCodec:
  case Single(asset: DiagramAsset)
  case Comparison(left: DiagramAsset, right: DiagramAsset)

final case class DiagramDefinition(
  id: String,
  caption: String,
  description: String,
  layout: DiagramLayout)
    derives JsonCodec:
  def assets: Vector[DiagramAsset] = layout match
    case DiagramLayout.Single(asset)           => Vector(asset)
    case DiagramLayout.Comparison(left, right) => Vector(left, right)

object DiagramCatalog:
  val RuntimeOwnership = DiagramDefinition(
    id = "runtime-ownership",
    caption =
      "Runtime ownership. HTTP rendering is request-scoped. Connected roots and nested LiveViews are independently supervised within one physical WebSocket scope.",
    description =
      "A browser uses a one-shot HTTP request scope for disconnected mount, rendering, full HTML, and bootstrap data; that scope then closes without retaining a server model. A separate WebSocket scope owns signed join admission, PhoenixProtocol, one physical writer, and a connection supervisor. The supervisor owns a root lifecycle and zero or more nested lifecycles. Each lifecycle has bounded epoch-qualified ingress, a single-owner SessionKernel, committed model and render state, components, resources, and ordered output.",
    layout = DiagramLayout.Comparison(
      left = DiagramAsset(
        label = "Disconnected HTTP",
        filename = "runtime-disconnected-lifetime.svg",
        intrinsicSize = DiagramIntrinsicSize(width = 480, height = 760)
      ),
      right = DiagramAsset(
        label = "Connected WebSocket",
        filename = "runtime-connected-lifetime.svg",
        intrinsicSize = DiagramIntrinsicSize(width = 480, height = 760)
      )
    )
  )

  val RuntimeConnectedTurn = DiagramDefinition(
    id = "runtime-connected-turn",
    caption =
      "One connected turn. Revision N remains active while the kernel prepares provisional state. Only a successful commit installs N+1; encoding and network publication happen afterward.",
    description =
      "A Phoenix event becomes a SessionCommand and is dequeued by the SessionKernel. While revision N remains active, a provisional turn resolves the typed target, runs hooks and the handler, evaluates the render, validates and prepares resources and child topology, reserves ordered output, then runs after-render work, validates continuations, and computes the diff. Failure discards the candidate without committing it and terminates the lifecycle. The interruption-masked commit replaces framework state, marks retired owners stale, activates resources and child topology, and fills the reserved output slot. Only after N+1 is active does RootConnection drain output through PhoenixRenderedEncoder and the bounded SerialWriter; write failure does not roll back N+1.",
    layout = DiagramLayout.Single(
      DiagramAsset(
        label = "Connected turn",
        filename = "runtime-connected-turn.svg",
        intrinsicSize = DiagramIntrinsicSize(width = 520, height = 1250)
      )
    )
  )

  val entries: Vector[DiagramDefinition] =
    Vector(RuntimeOwnership, RuntimeConnectedTurn)

  def get(id: String): Option[DiagramDefinition] = entries.find(_.id == id)

  def validate(definitions: Vector[DiagramDefinition] = entries): Vector[String] =
    val errors = Vector.newBuilder[String]
    definitions.groupBy(_.id).foreach { case (id, matches) =>
      if matches.sizeIs > 1 then errors += s"duplicate diagram id '$id'."
    }
    definitions.flatMap(_.assets).groupBy(_.filename).foreach { case (filename, matches) =>
      if matches.sizeIs > 1 then errors += s"duplicate diagram asset filename '$filename'."
    }
    definitions.foreach { diagram =>
      if !diagram.id.matches("[a-z0-9]+(?:-[a-z0-9]+)*") then
        errors += s"invalid diagram id '${diagram.id}'; expected lowercase kebab-case."
      if diagram.caption.trim.isEmpty then
        errors += s"diagram '${diagram.id}' caption must not be blank."
      if diagram.description.trim.isEmpty then
        errors += s"diagram '${diagram.id}' description must not be blank."
      diagram.assets.foreach { asset =>
        if asset.label.trim.isEmpty then
          errors += s"diagram '${diagram.id}' asset label must not be blank."
        if !asset.filename.matches("[a-z0-9]+(?:-[a-z0-9]+)*\\.svg") then
          errors += s"diagram '${diagram.id}' asset filename must be a lowercase kebab-case SVG filename."
        if asset.intrinsicSize.width <= 0 || asset.intrinsicSize.height <= 0 then
          errors += s"diagram '${diagram.id}' intrinsic dimensions must be positive."
      }
    }
    errors.result().distinct.sorted

  def prose(diagram: DiagramDefinition): String =
    Vector(diagram.id, diagram.caption, diagram.description).mkString(" ")
end DiagramCatalog
