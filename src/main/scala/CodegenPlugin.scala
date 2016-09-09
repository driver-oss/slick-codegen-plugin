import sbt._
import sbt.Keys._
import complete.DefaultParsers._

object CodegenPlugin extends AutoPlugin {
  override def requires = sbt.plugins.JvmPlugin
  type TableColumn = (String, String)

  object autoImport {
    lazy val genTables = TaskKey[Seq[File]]("gen-tables")
    lazy val uri = SettingKey[String]("uri for the database configuration")
    lazy val pkg = SettingKey[String]("package in which to place generated code")
    lazy val tablesFilename = SettingKey[String]("path for slick table models")
    lazy val rowsFilename = SettingKey[String]("path for row case classes")
    lazy val schemas = SettingKey[List[String]]("schemas and tables to process")
    lazy val manualForeignKeys = SettingKey[Map[TableColumn, TableColumn]]("foreign key references to data models add manually")

    lazy val slickCodeGenTask = Def.task {
      codegen.NamespacedCodegen.run(
        new java.net.URI(uri.value),
        pkg.value,
        tablesFilename.value,
        rowsFilename.value,
        schemas.value,
        manualForeignKeys.value)

      Seq(file(tablesFilename.value), file(rowsFilename.value))
    }
  }
}
