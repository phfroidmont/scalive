package scalive.docs

import java.nio.charset.StandardCharsets
import scala.util.Using

import zio.json.*

import scalive.docs.model.DocumentationBundle

private[docs] object GeneratedDocumentation:
  val ResourcePath = "scalive/docs/generated/content.json"

  def load(classLoader: ClassLoader): Either[String, DocumentationBundle] =
    // docs:start load-content
    Option(classLoader.getResource(ResourcePath))
      .toRight(s"Missing generated documentation resource: $ResourcePath")
      .flatMap { resource =>
        Using(resource.openStream()) { stream =>
          String(stream.readAllBytes(), StandardCharsets.UTF_8)
        }.toEither.left.map(error => s"Unable to read generated documentation: ${error.getMessage}")
      }.flatMap(_.fromJson[DocumentationBundle])
    // docs:end load-content
