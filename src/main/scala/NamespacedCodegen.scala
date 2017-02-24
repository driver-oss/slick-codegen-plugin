import java.net.URI
import java.nio.file.Paths

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext.Implicits.global
import slick.backend.DatabaseConfig
import slick.codegen.{SourceCodeGenerator, StringGeneratorHelpers}
import slick.driver.JdbcProfile
import slick.{model => sModel}
import slick.model.{Column, Model, Table, QualifiedName}

object Generator {

  def outputSchemaCode(schemaName: String, profile: String, folder: String, pkg: String, tableGen: TableFileGenerator, rowGen: RowFileGenerator): Unit = {
    val camelSchemaName = schemaName.split('_').map(_.capitalize).mkString("")

    tableGen.writeTablesToFile(profile: String, folder: String, pkg: String, fileName = s"${camelSchemaName}Tables.scala")
    rowGen.writeRowsToFile(folder: String, pkg: String, fileName = s"{camelSchemaName}Rows.scala")
  }

  def run(uri: URI,
          pkg: String,
          schemaNames: Option[List[String]],
          outputPath: String,
          manualForeignKeys: Map[(String, String), (String, String)],
          parentType: Option[String],
          idType: Option[String],
          header: String,
          schemaImports: List[String],
          typeReplacements: Map[String, String]) = {
    val dc: DatabaseConfig[JdbcProfile] =
      DatabaseConfig.forURI[JdbcProfile](uri)
    val parsedSchemasOpt: Option[Map[String, List[String]]] =
      schemaNames.map(SchemaParser.parse)

    try {
      val dbModel: Model = Await.result(
        dc.db.run(SchemaParser.createModel(dc.driver, parsedSchemasOpt)),
        Duration.Inf)

      parsedSchemasOpt.getOrElse(Map.empty).foreach {
        case (schemaName, tables) =>
          val profile =
            s"""slick.backend.DatabaseConfig.forConfig[slick.driver.JdbcProfile]("${uri
              .getFragment()}").driver"""

          val schemaOnlyModel = Await.result(
            dc.db.run(
              SchemaParser.createModel(dc.driver,
                                       Some(Map(schemaName -> tables)))),
            Duration.Inf)

          val rowGenerator = new RowSourceCodeGenerator(
            schemaOnlyModel,
            headerComment = header,
            imports = schemaImports.map("import " + _).mkString("\n"),
            schemaName = schemaName,
            dbModel,
            idType,
            manualForeignKeys
          )
          /*
          val tableGenerator: TableFileGenerator = ???

          outputSchemaCode(
            schemaName = schemaName,
            profile = profile,
            folder = outputPath,
            pkg = pkg,
            tableGen = tableGenerator,
            rowGen = rowGenerator)
           */
      }
    } finally {
      dc.db.close()
    }
  }
}
