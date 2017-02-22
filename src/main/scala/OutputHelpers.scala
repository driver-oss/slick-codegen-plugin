trait OutputHelpers extends slick.codegen.OutputHelpers {

  def imports: String

  def headerComment: String = ""

  override def packageCode(profile: String,
                           pkg: String,
                           container: String,
                           parentType: Option[String]): String = {
    val traitName = container.capitalize + "SchemaDef"
    s"""|package $pkg
        |$imports
        |
        |// AUTO-GENERATED Slick data model
        |
        |/** Stand-alone Slick data model for immediate use */
        |object $container extends {
        |  val profile = $profile
        |} with $traitName
        |
        |/** Slick data model trait for extension, choice of backend or usage in the cake pattern. (Make sure to initialize this late.) */
        |trait $traitName${parentType.fold("")(" extends " + _)} {
        |  import profile.api._
        |  ${indent(code)}
        |}""".stripMargin.trim()
  }
}
