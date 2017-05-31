import sbt._
import sbt.Keys._
import complete.DefaultParsers._

object CodegenPlugin extends AutoPlugin {
  override def requires = sbt.plugins.JvmPlugin
  type TableColumn = (String, String)

  object autoImport {

    /**
      * Parameters for a run of codegen on a single database
      * configuration. This is useful when you want to run the same code
      * generator on multiple databases:
      *
      * @param databaseUri uri for the database configuration
      * @param outputPackage package in which to place generated code
      * @param outputPath directory to which the code will be written
      * @param codegenSchemaWhitelist schemas and tables to process
      * @param foreignKeys foreign key references to data models add manually
      */
    case class CodegenDatabase(
        databaseURI: String,
        outputPackage: String,
        outputPath: String,
        schemaWhitelist: List[String] = List.empty,
        foreignKeys: Map[TableColumn, TableColumn] = Map.empty
    )

    lazy val codegenDatabaseConfigs = SettingKey[List[CodegenDatabase]](
      "codegen-database-configs",
      "configurations for each database and its generated code")

    lazy val codegenSchemaBaseClassParts = SettingKey[List[String]](
      "codegen-schema-base-class-parts",
      "parts inherited by each generated schema object")
    lazy val codegenIdType = SettingKey[Option[String]](
      "codegen-id-type",
      "The in-scope type `T` of kind `T[TableRow]` to apply in place T for id columns"
    )
    lazy val codegenSchemaImports = SettingKey[List[String]](
      "codegen-schema-imports",
      "A list of things to import into each schema definition"
    )
    lazy val codegenSchemaRowImports = SettingKey[Option[List[String]]](
      "codegen-schema-imports",
      "An optional list of things to import for table row definitions of each schema. Uses schemaImports otherwise."
    )
    lazy val codegenTypeReplacements = SettingKey[Map[String, String]](
      "codegen-type-replacements",
      "A map of types to find and replace"
    )
    lazy val codegenHeader = SettingKey[String](
      "codegen-header",
      "Comments that go at the top of generated files; notices and tooling directives."
    )

    lazy val slickCodeGenTask =
      TaskKey[Unit]("gen-tables", "generate the table definitions")
  }

  import autoImport._

  override lazy val projectSettings = Seq(
    codegenSchemaBaseClassParts := List.empty,
    codegenIdType := Option.empty,
    codegenSchemaImports := List.empty,
    codegenTypeReplacements := Map.empty,
    codegenHeader := "AUTO-GENERATED Slick data model",
    slickCodeGenTask := Def.taskDyn {
      Def.task {
        codegenDatabaseConfigs.value.foreach {
          config =>
            Generator.run(
              new java.net.URI(config.databaseURI),
              config.outputPackage,
              Some(config.schemaWhitelist).filter(_.nonEmpty),
              config.outputPath,
              config.foreignKeys, {
                val parts =
                  (if (codegenIdType.value.isEmpty)
                     codegenSchemaBaseClassParts.value :+ "DefaultIdTypeMapper"
                   else
                     codegenSchemaBaseClassParts.value)

                Some(parts).filter(_.nonEmpty).map(_.mkString(" with "))
              },
              codegenIdType.value,
              codegenHeader.value,
              codegenSchemaImports.value,
              codegenSchemaRowImports.value getOrElse codegenSchemaImports.value,
              codegenTypeReplacements.value
            )
        }
      }
    }.value
  )

}
