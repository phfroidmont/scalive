package scalive.codecs

class Encoder[ScalaType, DomType](val encode: ScalaType => DomType)

def AsIsEncoder[V](): Encoder[V, V] = Encoder(identity)

val StringAsIsEncoder: Encoder[String, String] = AsIsEncoder()

val IntAsIsEncoder: Encoder[Int, Int] = AsIsEncoder()

lazy val IntAsStringEncoder: Encoder[Int, String] = Encoder[Int, String](_.toString)

lazy val DoubleAsIsEncoder: Encoder[Double, Double] = AsIsEncoder()

lazy val DoubleAsStringEncoder: Encoder[Double, String] =
  Encoder[Double, String](_.toString)

lazy val BooleanAsStringEncoder: Encoder[Boolean, String] =
  Encoder[Boolean, String](_.toString)

val BooleanAsIsEncoder: Encoder[Boolean, Boolean] = AsIsEncoder()

lazy val BooleanAsAttrPresenceEncoder: Encoder[Boolean, String] =
  Encoder[Boolean, String](if _ then "" else null)

lazy val BooleanAsTrueFalseStringEncoder: Encoder[Boolean, String] =
  Encoder[Boolean, String](if _ then "true" else "false")

lazy val BooleanAsYesNoStringEncoder: Encoder[Boolean, String] =
  Encoder[Boolean, String](if _ then "yes" else "no")

lazy val BooleanAsOnOffStringEncoder: Encoder[Boolean, String] =
  Encoder[Boolean, String](if _ then "on" else "off")
