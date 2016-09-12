import sbt._
import sbt.Keys._
import complete.DefaultParsers._

object CodegenPlugin extends AutoPlugin {
  override def requires = sbt.plugins.JvmPlugin
  type TableColumn = (String, String)

  object autoImport {
    lazy val codegen = TaskKey[Seq[File]]("gen-tables", "generate slick database schema")

    lazy val codegenURI = SettingKey[String]("uri for the database configuration")
    lazy val codegenPackage = SettingKey[String]("package in which to place generated code")
    lazy val codegenTablesFile = SettingKey[String]("path for slick table models")
    lazy val codegenRowsFile = SettingKey[String]("path for row case classes")
    lazy val codegenSchemaWhitelist = SettingKey[List[String]]("schemas and tables to process")
    lazy val codegenForeignKeys = SettingKey[Map[TableColumn, TableColumn]]("foreign key references to data models add manually")

    lazy val slickCodeGenTask = Def.task {
      NamespacedCodegen.run(
        new java.net.URI(codegenURI.value),
        codegenPackage.value,
        codegenTablesFile.value,
        codegenRowsFile.value,
        codegenSchemaWhitelist.value,
        codegenForeignKeys.value
      )

      Seq(file(codegenTablesFile.value), file(codegenRowsFile.value))
    }
  }
}
