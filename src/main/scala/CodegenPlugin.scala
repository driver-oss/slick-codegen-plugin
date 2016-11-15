import sbt._
import sbt.Keys._
import complete.DefaultParsers._

object CodegenPlugin extends AutoPlugin {
  override def requires = sbt.plugins.JvmPlugin
  type TableColumn = (String, String)

  object autoImport {
    lazy val codegen = TaskKey[Unit]("gen-tables", "generate slick database schema")

    lazy val codegenURI = SettingKey[String]("codegen-uri", "uri for the database configuration")
    lazy val codegenPackage = SettingKey[String]("codegen-package", "package in which to place generated code")
    lazy val codegenOutputPath = SettingKey[String]("codegen-output-path", "directory to with the generated code will be written")
    lazy val codegenSchemaWhitelist = SettingKey[List[String]]("codegen-schema-whitelist", "schemas and tables to process")
    lazy val codegenForeignKeys = SettingKey[Map[TableColumn, TableColumn]]("codegen-foreign-keys", "foreign key references to data models add manually")

    lazy val slickCodeGenTask = Def.task {
      Generator.run(
        new java.net.URI(codegenURI.value),
        codegenPackage.value,
        Option(codegenSchemaWhitelist.value),
        codegenOutputPath.value,
        codegenForeignKeys.value
      )
    }
  }
}
