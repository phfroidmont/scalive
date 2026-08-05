package scalive.docs

import java.nio.charset.StandardCharsets
import scala.util.Using

import zio.json.*

import scalive.docs.model.{DocumentationBundle, SearchEntry}

private[docs] object GeneratedDocumentation:
  val ResourcePath       = "scalive/docs/generated/content.json"
  val SearchResourcePath = "public/search-index.json"

  def load(classLoader: ClassLoader): Either[String, DocumentationBundle] =
    // docs:start load-content
    read(classLoader, ResourcePath, "documentation").flatMap(_.fromJson[DocumentationBundle])
    // docs:end load-content

  def loadSearchEntries(classLoader: ClassLoader): Either[String, Vector[SearchEntry]] =
    read(classLoader, SearchResourcePath, "search index").flatMap(_.fromJson[Vector[SearchEntry]])

  private def read(
    classLoader: ClassLoader,
    path: String,
    label: String
  ): Either[String, String] =
    Option(classLoader.getResource(path))
      .toRight(s"Missing generated $label resource: $path")
      .flatMap { resource =>
        Using(resource.openStream()) { stream =>
          String(stream.readAllBytes(), StandardCharsets.UTF_8)
        }.toEither.left.map(error => s"Unable to read generated $label: ${error.getMessage}")
      }
