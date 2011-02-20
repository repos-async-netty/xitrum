package xitrum.action

trait I18n {
  this: Action =>

  def t(key: String, args: Any*): String = {
    val msg = key
    msg.format(args)
  }
}