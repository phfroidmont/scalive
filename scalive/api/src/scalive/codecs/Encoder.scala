package scalive.codecs

/** Converts a Scala value into the representation expected by a DOM definition.
  *
  * For a custom [[scalive.HtmlAttr]], `DomType` is `String`. The attribute calls [[encode]] when
  * `:=` is evaluated, and the HTML renderer escapes that encoded string later. An encoder should
  * therefore describe representation only: it neither needs to pre-escape HTML nor implies semantic
  * sanitization of URLs, CSS, JavaScript, or custom attribute values.
  *
  * Custom attribute definitions can pair an encoder with `htmlAttr`:
  *
  * {{{
  * val priority = htmlAttr("data-priority", Encoder[Int, String](_.toString))
  * div(priority := 2)
  * }}}
  *
  * The attribute constructor validates the custom name. The encoder controls only its value and
  * must return a non-null string for a string-valued custom attribute. The library's
  * [[BooleanAsAttrPresenceEncoder]] is a specialized exception interpreted by [[scalive.HtmlAttr]].
  *
  * @param encode
  *   conversion applied to each assigned Scala value
  * @tparam ScalaType
  *   input type accepted by the DSL definition
  * @tparam DomType
  *   encoded representation consumed by that definition
  */
class Encoder[ScalaType, DomType](val encode: ScalaType => DomType)

/** Creates an encoder that returns each value unchanged. */
def AsIsEncoder[V](): Encoder[V, V] = Encoder(identity)

/** Identity encoder for string-valued DOM definitions.
  *
  * When used by an [[scalive.HtmlAttr]], the unchanged string is still HTML-escaped during
  * rendering.
  */
val StringAsIsEncoder: Encoder[String, String] = AsIsEncoder()

/** Identity encoder for integer-valued DOM definitions. */
val IntAsIsEncoder: Encoder[Int, Int] = AsIsEncoder()

/** Encodes an integer with `Int.toString`, suitable for an HTML attribute. */
lazy val IntAsStringEncoder: Encoder[Int, String] = Encoder[Int, String](_.toString)

/** Identity encoder for double-valued DOM definitions. */
lazy val DoubleAsIsEncoder: Encoder[Double, Double] = AsIsEncoder()

/** Encodes a double with `Double.toString`, suitable for an HTML attribute. */
lazy val DoubleAsStringEncoder: Encoder[Double, String] =
  Encoder[Double, String](_.toString)

/** Encodes a boolean with `Boolean.toString`, yielding `"true"` or `"false"`. */
lazy val BooleanAsStringEncoder: Encoder[Boolean, String] =
  Encoder[Boolean, String](_.toString)

/** Identity encoder for boolean-valued DOM definitions. */
val BooleanAsIsEncoder: Encoder[Boolean, Boolean] = AsIsEncoder()

/** Encodes HTML boolean-attribute presence.
  *
  * [[scalive.HtmlAttr]] recognizes this specific singleton: assigning `true` emits the bare
  * attribute name, while assigning `false` omits it. Its `null` false result is an internal
  * sentinel, so this encoder is intended for `HtmlAttr` definitions rather than direct calls to
  * `encode`.
  */
lazy val BooleanAsAttrPresenceEncoder: Encoder[Boolean, String] =
  Encoder[Boolean, String](if _ then "" else null)

/** Encodes booleans as the exact strings `"true"` and `"false"`. */
lazy val BooleanAsTrueFalseStringEncoder: Encoder[Boolean, String] =
  Encoder[Boolean, String](if _ then "true" else "false")

/** Encodes booleans as the exact strings `"yes"` and `"no"`. */
lazy val BooleanAsYesNoStringEncoder: Encoder[Boolean, String] =
  Encoder[Boolean, String](if _ then "yes" else "no")

/** Encodes booleans as the exact strings `"on"` and `"off"`. */
lazy val BooleanAsOnOffStringEncoder: Encoder[Boolean, String] =
  Encoder[Boolean, String](if _ then "on" else "off")
