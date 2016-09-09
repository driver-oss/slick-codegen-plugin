import sbt._
import sbt.Keys._
import complete.DefaultParsers._

object CodegenPlugin extends AutoPlugin {
  override def requires = sbt.plugins.JvmPlugin
  object autoImport {
    lazy val genTables = TaskKey[Seq[File]]("gen-tables")
    lazy val dbConfigURI = SettingKey[String]("uri for the database configuration")
    lazy val pkg = SettingKey[String]("package in which to place generated code")
    lazy val tablesFilename = SettingKey[String]("path for slick table models")
    lazy val rowsFilename = SettingKey[String]("path for row case classes")
    lazy val schemas = SettingKey[List[String]]("schemas and tables to process")

    lazy val slickCodeGenTask = Def.task {
      val uri = new java.net.URI(dbConfigURI.value)

      codegen.NamespacedCodegen.run(uri, pkg.value, tablesFilename.value, rowsFilename.value, schemas.value)

      Seq(file(tablesFilename.value), file(rowsFilename.value))
    }
  }
}
