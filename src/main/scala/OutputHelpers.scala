trait OutputHelpers extends slick.codegen.OutputHelpers {

  def imports: String

  def headerComment: String = ""

  override def packageCode(profile: String,
                           pkg: String,
                           container: String,
                           parentType: Option[String]): String = {
    s"""|${headerComment.trim().lines.map("// " + _).mkString("\n")}
        |package $pkg
        |
        |$imports
        |
        |/** Stand-alone Slick data model for immediate use */
        |package object $container extends {
        |  val profile = $profile
        |} with Tables
        |
        |/** Slick data model trait for extension, choice of backend or usage in the cake pattern. (Make sure to initialize this late.) */
        |trait Tables${parentType.fold("")(" extends " + _)} {
        |  import profile.api._
        |  ${indent(code)}
        |}""".stripMargin.trim()
  }
}
