package xitrum.validator

import xitrum.exception.ValidationError

trait Validator {
  def v(name: String, value: Any): Option[String]

  def e(name: String, value: Any) {
    v(name, value).foreach { message => throw new ValidationError(message) }
  }
}
