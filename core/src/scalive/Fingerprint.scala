package scalive

import java.nio.ByteBuffer
import java.security.MessageDigest

final case class Fingerprint(value: Long, nested: Seq[Fingerprint])

object Fingerprint:
  val empty                                  = Fingerprint(0, Seq.empty)
  def apply(rendered: Rendered): Fingerprint =
    Fingerprint(
      rendered.fingerprint,
      rendered.dynamic.flatMap(render => apply(render(false)))
    )
  def apply(comp: Comprehension): Fingerprint      = Fingerprint(comp.fingerprint, Seq.empty)
  def apply(dyn: RenderedDyn): Option[Fingerprint] =
    dyn match
      case Some(r: Rendered)      => Some(apply(r))
      case Some(c: Comprehension) => Some(apply(c))
      case Some(_: String)        => None
      case None                   => None

  val dynText                = digest("text")
  val dynAttr                = digest("attr")
  val dynAttrValueAsPresence = digest("attrValueAsPresence")
  val dynWhen                = digest("when")
  val dynSplit               = digest("split")

  def digest(s: String): Array[Byte] =
    MessageDigest.getInstance("MD5").digest(s.getBytes)

  def digest(el: HtmlElement): Array[Byte] =
    val md = MessageDigest.getInstance("MD5")
    el.static.foreach(s => md.update(s.getBytes))
    el.mods.foreach {
      case Mod.Text(_)                         => ()
      case Mod.StaticAttr(_, _)                => ()
      case Mod.StaticAttrValueAsPresence(_, _) => ()
      case Mod.DynAttr(_, _)                   => md.update(Fingerprint.dynAttr)
      case Mod.DynAttrValueAsPresence(_, _)    => md.update(Fingerprint.dynAttrValueAsPresence)
      case Mod.DynText(_)                      => md.update(Fingerprint.dynText)
      case Mod.When(_, _)                      => md.update(Fingerprint.dynWhen)
      case Mod.Split(_, project)               => md.update(digest(project(Dyn.dummy)))
      case Mod.Tag(el)                         => Right(digest(el))
    }
    md.digest()

  def apply(el: HtmlElement): Long = digestToFingerprint(digest(el))

  private def digestToFingerprint(digest: Array[Byte]): Long =
    ByteBuffer.wrap(digest, 0, 8).getLong

end Fingerprint
