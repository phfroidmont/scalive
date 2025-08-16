package scalive.codecs

class Codec[ScalaType, DomType](val encode: ScalaType => DomType, val decode: DomType => ScalaType)

def AsIsCodec[V](): Codec[V, V] = Codec(identity, identity)

val StringAsIsCodec: Codec[String, String] = AsIsCodec()

val IntAsIsCodec: Codec[Int, Int] = AsIsCodec()

lazy val IntAsStringCodec: Codec[Int, String] = Codec[Int, String](_.toString, _.toInt)

lazy val DoubleAsIsCodec: Codec[Double, Double] = AsIsCodec()

lazy val DoubleAsStringCodec: Codec[Double, String] = Codec[Double, String](_.toString, _.toDouble)

val BooleanAsIsCodec: Codec[Boolean, Boolean] = AsIsCodec()

lazy val BooleanAsAttrPresenceCodec: Codec[Boolean, String] =
  Codec[Boolean, String](if _ then "" else null, _ != null)

lazy val BooleanAsTrueFalseStringCodec: Codec[Boolean, String] =
  Codec[Boolean, String](if _ then "true" else "false", _ == "true")

lazy val BooleanAsYesNoStringCodec: Codec[Boolean, String] =
  Codec[Boolean, String](if _ then "yes" else "no", _ == "yes")

lazy val BooleanAsOnOffStringCodec: Codec[Boolean, String] =
  Codec[Boolean, String](if _ then "on" else "off", _ == "on")
