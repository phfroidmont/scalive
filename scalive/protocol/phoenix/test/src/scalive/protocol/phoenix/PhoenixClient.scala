package scalive.protocol.phoenix

import zio.json.ast.Json

/** How the bundled Phoenix LiveView client (`rendered.js`, `view.ts`) applies rendered diffs to the
  * page.
  *
  * It follows `mergeDiff`, `mutableMerge`, `cloneMerge`, `mergeKeyed`, and `toOutputBuffer`,
  * including how rendering resolves shared `p` templates in place and drops them. Like the client,
  * it skips a root, a component or a template marked `r`, that the diff did not reach, and the page
  * keeps that element as it was, so a change rendered inside it is lost. A diff that holds only
  * components patches the outermost changed ones in the page instead of rendering the whole tree.
  *
  * A dynamic the client would render as `undefined`, or a skipped element the page does not hold,
  * fails instead. Streams are out of scope: the client hands their rows to DOM patching rather than
  * keeping them in the rendered tree.
  */
final class PhoenixClient private ():
  import PhoenixClient.*

  private var root       = Json.Obj()
  private var components = Map.empty[String, Json.Obj]
  // The content of each element the page holds, by its magic ID or component, and of the page.
  private var page    = Map.empty[String, Vector[Fragment]]
  private var magicId = 0

  /** The page's HTML. */
  def html: String = expand(Page)

  /** Applies a diff the server sent after the join, as `View.update` does. */
  def patch(diff: Json.Obj): Unit =
    if diff.nonEmpty then
      val changed = merge(diff)
      if diff.fields.forall(_._1 == "c") then
        // findExistingParentCIDs: the changed components in the page, without those inside one.
        val present = changed.filter(cid => reachable(Page).contains(componentKey(cid)))
        val inside  = present.flatMap(cid => reachable(componentKey(cid)) - componentKey(cid))
        present.filterNot(cid => inside.contains(componentKey(cid))).foreach { cid =>
          val _ = renderComponent(cid, onlyCids = None, discarded = false)
        }
      else renderPage(Some(changed))

  private def renderPage(onlyCids: Option[Set[String]]): Unit =
    val (fragments, resolved) = render(root, None, onlyCids, discarded = false)
    root = resolved
    page = page.updated(Page, fragments)

  /** Merges a diff and returns the components it holds. */
  private def merge(diff: Json.Obj): Set[String] =
    val rest = Json.Obj(diff.fields.filterNot(_._1 == "c")*)
    // A diff with root statics replaces the tree, components included.
    if has(diff, "s") then components = Map.empty
    root = if has(rest, "s") then rest else doMutableMerge(root, rest)
    val changed = diff.get("c").map(obj).fold(Map.empty[String, Json.Obj])(_.fields.map {
      case (cid, value) => cid -> obj(value)
    }.toMap)
    // cachedFindComponent: every component of the diff is resolved against those held before it.
    val resolved = collection.mutable.Map.empty[String, Json.Obj]
    def find(cid: String): Json.Obj = resolved.getOrElseUpdate(
      cid, {
        val componentDiff = changed.getOrElse(cid, fail(s"no component diff for CID $cid"))
        componentDiff.get("s") match
          // Statics shared with a component of this diff, or with one held before it.
          case Some(Json.Num(shared)) =>
            val source =
              if shared.intValue > 0 then find(shared.intValue.toString)
              else components.getOrElse((-shared.intValue).toString, fail(s"no CID $shared"))
            cloneMerge(source, componentDiff, pruneMagicId = true).put("s", source.get("s").get)
          case Some(_) => componentDiff
          case None    =>
            components.get(cid).fold(componentDiff)(cloneMerge(_, componentDiff))
      }
    )
    changed.keys.foreach(find)
    components = components ++ resolved
    changed.keySet

  private def renderComponent(
    cid: String,
    onlyCids: Option[Set[String]],
    discarded: Boolean
  ): Vector[Fragment] =
    val component = components.getOrElse(cid, fail(s"no component for CID $cid"))
    // recursiveCIDToString: a component the diff did not change is skipped.
    val skipped              = onlyCids.exists(!_.contains(cid))
    val marked               = component.put(NewRender, Json.Bool(!skipped)).put(MagicId, key(cid))
    val (fragments, rendered) = render(marked, None, onlyCids, discarded)
    components = components.updated(cid, rendered)
    fragments

  private def render(
    node: Json.Obj,
    inherited: Option[Json.Obj],
    onlyCids: Option[Set[String]],
    discarded: Boolean
  ): (Vector[Fragment], Json.Obj) =
    if has(node, "k") then renderComprehension(node, inherited, onlyCids, discarded)
    else
      // A nested `p` replaces the inherited templates.
      val templates = node.get("p").map(obj).orElse(inherited)
      val statics   = resolveStatics(node, templates)
      val resolved  = without(node, "p").put("s", Json.Arr(statics.map(Json.Str(_))*))
      if !has(node, "r") then renderDynamics(resolved, statics, templates, onlyCids, discarded)
      else
        val tracked = node.get(MagicId) match
          case Some(_) => resolved
          case None    =>
            magicId += 1
            resolved.put(MagicId, Json.Str(s"m$magicId")).put(NewRender, Json.Bool(true))
        val elementKey = string(tracked.get(MagicId).get)
        val skipped    = !tracked.get(NewRender).contains(Json.Bool(true))
        val (fragments, rendered) =
          renderDynamics(tracked, statics, templates, onlyCids, discarded || skipped)
        if skipped && !discarded && !page.contains(elementKey) then
          fail(s"skipped element $elementKey is not in the page")
        if !skipped && !discarded then page = page.updated(elementKey, fragments)
        Vector(Fragment.Element(elementKey)) -> rendered.put(NewRender, Json.Bool(false))

  private def renderComprehension(
    node: Json.Obj,
    inherited: Option[Json.Obj],
    onlyCids: Option[Set[String]],
    discarded: Boolean
  ): (Vector[Fragment], Json.Obj) =
    if has(node, "stream") then fail("streams are not modeled")
    // Unlike a nested `p`, a comprehension's own `p` only applies when none is inherited.
    val templates = inherited.orElse(node.get("p").map(obj))
    val statics   = resolveStatics(node, inherited)
    val keyed     = obj(node.get("k"))
    val count     = int(keyed.get("kc"))
    val rendered  = (0 until count).map { index =>
      val entry = obj(keyed.get(index.toString))
      renderDynamics(entry, statics, templates, onlyCids, discarded)
    }
    val entries = rendered.zipWithIndex.map { case ((_, entry), index) => index.toString -> entry }
    rendered.flatMap(_._1).toVector -> without(node, "p")
      .put("s", Json.Arr(statics.map(Json.Str(_))*))
      .put("k", Json.Obj((entries :+ ("kc" -> Json.Num(count)))*))

  private def renderDynamics(
    node: Json.Obj,
    statics: Vector[String],
    templates: Option[Json.Obj],
    onlyCids: Option[Set[String]],
    discarded: Boolean
  ): (Vector[Fragment], Json.Obj) =
    val fragments = Vector.newBuilder[Fragment]
    var resolved  = node
    statics.indices.foreach { index =>
      fragments += Fragment.Text(statics(index))
      if index < statics.length - 1 then
        val key = index.toString
        node.get(key) match
          case Some(Json.Str(value)) => fragments += Fragment.Text(value)
          case Some(Json.Num(cid))   =>
            fragments ++= renderComponent(cid.toString, onlyCids, discarded)
          case Some(child: Json.Obj) =>
            val (html, renderedChild) = render(child, templates, onlyCids, discarded)
            fragments ++= html
            resolved = resolved.put(key, renderedChild)
          case other => fail(s"dynamic $key renders as undefined: $other")
    }
    fragments.result() -> resolved

  private def resolveStatics(node: Json.Obj, templates: Option[Json.Obj]): Vector[String] =
    node.get("s") match
      case Some(Json.Arr(values)) => values.map(string).toVector
      case Some(Json.Num(index))  =>
        val table = templates.getOrElse(fail("a template reference has no templates"))
        table.get(index.toString) match
          case Some(Json.Arr(values)) => values.map(string).toVector
          case _                      => fail(s"unknown template $index")
      case other => fail(s"rendered object has no statics: $other")

  // How the root merges a diff: only what the diff reaches renders again.
  private def doMutableMerge(target: Json.Obj, source: Json.Obj): Json.Obj =
    val merged =
      if has(source, "k") then mergeKeyed(target, source)
      else
        source.fields.foldLeft(target) { case (merged, (key, value)) =>
          (value, merged.get(key)) match
            case (child: Json.Obj, Some(previous: Json.Obj)) if !has(child, "s") =>
              merged.put(key, doMutableMerge(previous, child))
            case _ => merged.put(key, value)
        }
    if has(target, "r") then merged.put(NewRender, Json.Bool(true)) else merged

  // How a component merges a diff: everything in it renders again, except the keyed entries a
  // keyed diff does not reach. A component sharing another's statics drops what tracked its
  // elements in the page.
  private def cloneMerge(
    target: Json.Obj,
    source: Json.Obj,
    pruneMagicId: Boolean = false
  ): Json.Obj =
    val merged =
      if has(source, "k") then mergeKeyed(target, source)
      else
        val keys = (target.fields.map(_._1) ++ source.fields.map(_._1)).distinct
        Json.Obj(keys.map { key =>
          key -> ((source.get(key), target.get(key)) match
            case (Some(child: Json.Obj), Some(previous: Json.Obj)) if !has(child, "s") =>
              cloneMerge(previous, child, pruneMagicId)
            case (None, Some(previous: Json.Obj)) =>
              cloneMerge(previous, Json.Obj(), pruneMagicId)
            case (Some(value), _)    => value
            case (None, Some(value)) => value
            case (None, None)        => fail(s"no value for $key")
          )
        }*)
    if pruneMagicId then without(without(merged, MagicId), NewRender)
    else if has(target, "r") then merged.put(NewRender, Json.Bool(true))
    else merged

  private def mergeKeyed(target: Json.Obj, source: Json.Obj): Json.Obj =
    val previous = obj(target.get("k"))
    val changes  = obj(source.get("k"))
    val count    = int(changes.get("kc"))
    val merged   = changes.fields.foldLeft(previous) {
      case (entries, ("kc" | "km", _))                                       => entries
      case (entries, (index, Json.Arr(Seq(Json.Num(from), diff: Json.Obj)))) =>
        entries.put(index, doMutableMerge(obj(previous.get(from.toString)), diff))
      case (entries, (index, Json.Num(from))) =>
        entries.put(index, obj(previous.get(from.toString)))
      case (entries, (index, diff: Json.Obj)) =>
        val current = entries.get(index).map(obj).getOrElse(Json.Obj())
        entries.put(index, doMutableMerge(current, diff))
      case (_, (index, value)) => fail(s"unexpected keyed entry $index: $value")
    }
    val kept = merged.fields.filter { case (key, _) =>
      key == "kc" || key.toIntOption.exists(_ < count)
    }
    val result = target.put("k", Json.Obj(kept*).put("kc", Json.Num(count)))
    source.get("p").fold(result)(templates => result.put("p", templates))

  // The page elements inside `key`, itself included.
  private def reachable(key: String): Set[String] =
    page.getOrElse(key, Vector.empty).foldLeft(Set(key)) {
      case (keys, Fragment.Element(inner)) => keys ++ reachable(inner)
      case (keys, Fragment.Text(_))        => keys
    }

  private def expand(key: String): String =
    page.getOrElse(key, fail(s"the page holds no element $key")).map {
      case Fragment.Text(value)   => value
      case Fragment.Element(item) => expand(item)
    }.mkString
end PhoenixClient

object PhoenixClient:
  /** Joins with an initial rendered payload, as the client does on mount. */
  def join(initial: Json.Obj): PhoenixClient =
    val client = PhoenixClient()
    val _      = client.merge(initial)
    client.renderPage(None)
    client

  private enum Fragment:
    case Text(value: String)
    // An element the page holds, by key.
    case Element(key: String)

  private val Page      = "page"
  private val MagicId   = "magicId"
  private val NewRender = "newRender"

  private def componentKey(cid: String): String = s"c$cid"

  private def key(cid: String): Json.Str = Json.Str(componentKey(cid))

  // Json.Obj#add deep-merges objects; the client assigns them.
  extension (node: Json.Obj)
    private def put(key: String, value: Json): Json.Obj =
      Json.Obj((node.fields.filterNot(_._1 == key) :+ (key -> value))*)

  private def has(node: Json.Obj, key: String): Boolean = node.get(key).nonEmpty

  private def without(node: Json.Obj, key: String): Json.Obj =
    Json.Obj(node.fields.filterNot(_._1 == key)*)

  private def obj(value: Json): Json.Obj = value match
    case value: Json.Obj => value
    case other           => fail(s"expected an object: $other")

  private def obj(value: Option[Json]): Json.Obj = obj(value.getOrElse(fail("missing object")))

  private def int(value: Option[Json]): Int = value match
    case Some(Json.Num(number)) => number.intValue
    case other                  => fail(s"expected a number: $other")

  private def string(value: Json): String = value match
    case Json.Str(text) => text
    case other          => fail(s"expected a string: $other")

  private def fail(message: String): Nothing = throw AssertionError(message)
end PhoenixClient
