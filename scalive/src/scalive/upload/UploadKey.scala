package scalive
package upload

opaque type UploadKey = String

object UploadKey:
  def apply(value: String): UploadKey = value

  extension (key: UploadKey) def value: String = key
