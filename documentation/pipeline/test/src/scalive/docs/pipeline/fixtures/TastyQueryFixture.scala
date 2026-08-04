package scalive.docs.pipeline.fixtures

/** Fixture owner documentation. */
trait TastyQueryFixture extends TastyQueryInherited:
  /** Integer overload documentation. */
  def overloaded(value: Int): String

  /** String overload documentation. */
  def overloaded(value: String): String

  /** Extension documentation. */
  extension (value: String) def fixtureExtension: String = value

  protected def protectedMember: Unit = ()
  private[fixtures] def scopedPrivateMember: Unit = ()

/** Inherited member owner documentation. */
trait TastyQueryInherited:
  /** Inherited member documentation. */
  def inheritedMember: Int = 1

object TastyQueryExported:
  /** Exported member documentation. */
  def exportedMember: String = "exported"

object TastyQueryExports:
  export TastyQueryExported.*

opaque type TastyQueryOpaque = String

object TastyQueryOpaque:
  def apply(value: String): TastyQueryOpaque = value
