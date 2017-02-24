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

class TableSourceCodeGenerator(
    pkg: String,
    fullDatabaseModel: Model,
    schemaOnlyModel: Model,
    manualForeignKeys: Map[(String, String), (String, String)],
    parentType: Option[String],
    idType: Option[String],
    typeReplacements: Map[String, String])
    extends TypedIdSourceCodeGenerator(fullDatabaseModel, idType, manualForeignKeys) {

  val defaultIdImplementation =
    """|final case class Id[T](v: Int)
       |trait DefaultIdTypeMapper {
       |  val profile: slick.driver.JdbcProfile
       |  import profile.api._
       |  implicit def idTypeMapper[A]: BaseColumnType[Id[A]] = MappedColumnType.base[Id[A], Int](_.v, Id(_))
       |}
       |""".stripMargin

  override def code = super.code.lines.drop(1).mkString("\n")
  // Drops needless import: `"import slick.model.ForeignKeyAction\n"`.
  // Alias to ForeignKeyAction is in profile.api
  // TODO: fix upstream

  override def Table = new this.TypedIdTable(_) { table =>
    override def TableClass = new TableClass() {
      // We disable the option mapping, as it is a bit more complex to support and we don't appear to need it
      override def optionEnabled = false
    }

    // use hlists all the time
    override def hlistEnabled: Boolean = true

    // if false rows are type aliases to hlists, if true rows are case classes
    override def mappingEnabled: Boolean = true

    // create case class from colums
    override def factory: String =
      if (!hlistEnabled) super.factory
      else {
        val args = columns.zipWithIndex.map("a" + _._2)
        val hlist = args.mkString("::") + ":: HNil"
        val hlistType = columns
            .map(_.actualType)
            .mkString("::") + ":: HNil.type"
        s"((h : $hlistType) => h match {case $hlist => ${TableClass.elementType}(${args.mkString(",")})})"
      }

    // from case class create columns
    override def extractor: String =
      if (!hlistEnabled) super.extractor
      else
        s"(a : ${TableClass.elementType}) => Some(" + columns
          .map("a." + _.name)
          .mkString("::") + ":: HNil)"

    override def EntityType = new EntityType {
      override def enabled = false
    }

    override def Column = new TypedIdColumn(_) {
      override def rawType: String  = {
        typeReplacements.getOrElse(model.tpe, super.rawType)
      }
    }

    override def ForeignKey = new ForeignKey(_) {
      override def code = {
        val fkColumns = compoundValue(referencingColumns.map(_.name))
        val qualifier =
          if (referencedTable.model.name.schema == referencingTable.model.name.schema)
            ""
          else
            referencedTable.model.name.schema.fold("")(sname =>
              s"$pkg.$sname.")

        val qualifiedName = qualifier + referencedTable.TableValue.name
        val pkColumns = compoundValue(referencedColumns.map(c =>
          s"r.${c.name}${if (!c.model.nullable && referencingColumns.forall(_.model.nullable)) ".?"
          else ""}"))
        val fkName = referencingColumns
            .map(_.name)
            .flatMap(_.split("_"))
            .map(_.capitalize)
            .mkString
            .uncapitalize + "Fk"
        s"""lazy val $fkName = foreignKey("$dbName", $fkColumns, $qualifiedName)(r => $pkColumns, onUpdate=$onUpdate, onDelete=$onDelete)"""
      }
    }
  }
}
