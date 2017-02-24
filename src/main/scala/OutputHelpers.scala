trait OOutputHelpers extends slick.codegen.OutputHelpers {

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

import slick.codegen.{SourceCodeGenerator, OutputHelpers}

trait TableFileGenerator { self: SourceCodeGenerator =>
  def writeTablesToFile(profile: String, folder:String, pkg: String, fileName: String): Unit
}

trait RowFileGenerator { self: SourceCodeGenerator =>
  def writeRowsToFile(folder:String, pkg: String, fileName: String): Unit
}

// Dirty work to hide OutputHelpers
trait TableOutputHelpers extends TableFileGenerator with OutputHelpers { self: SourceCodeGenerator =>

  def headerComment: String
  def schemaName: String
  def imports: String

  def packageTableCode(headerComment: String, pkg: String, schemaName: String, imports: String, profile: String): String =
    s"""|${headerComment.trim().lines.map("// " + _).mkString("\n")}
        |package $pkg
        |package $schemaName
        |
        |$imports
        |
        |/** Stand-alone Slick data model for immediate use */
        |// TODO: change this to `object tables`
        |package object $schemaName extends {
        |  val profile = $profile
        |} with Tables
        |
        |/** Slick data model trait for extension, choice of backend or usage in the cake pattern. (Make sure to initialize this late.) */
        |trait Tables${parentType.fold("")(" extends " + _)} {
        |  import profile.api._
        |  ${indent(code)}       |
        |""".stripMargin.trim()

  def writeTablesToFile(profile: String, folder:String, pkg: String, fileName: String): Unit = {
    writeStringToFile(content = packageTableCode(headerComment, pkg, schemaName, imports, profile), folder = folder, pkg = s"$pkg.$schemaName", fileName = fileName)
  }
}

trait RowOutputHelpers extends RowFileGenerator with OutputHelpers { self: SourceCodeGenerator =>

  def headerComment: String
  def schemaName: String
  def imports: String

  def packageRowCode(headerComment: String, schemaName: String, pkg: String, imports: String): String =
    s"""|${headerComment.trim().lines.map("// " + _).mkString("\n")}
        |/** Definitions for table rows types of database schema $schemaName */
        |package $pkg
        |package $schemaName
        |
        |$imports
        |
        |$code
        |""".stripMargin.trim()

  def writeRowsToFile(folder:String, pkg: String, fileName: String): Unit = {

    writeStringToFile(content = packageRowCode(headerComment, schemaName, pkg, imports), folder = folder, pkg = s"$pkg.$schemaName", fileName = fileName)
  }
}
