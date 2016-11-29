import java.net.URI
import java.nio.file.Paths

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext.Implicits.global
import slick.backend.DatabaseConfig
import slick.codegen.{
  OutputHelpers,
  SourceCodeGenerator,
  StringGeneratorHelpers
}
import slick.dbio.DBIO
import slick.driver.JdbcProfile
import slick.jdbc.meta.MTable
import slick.{model => sModel}
import slick.model.{Column, Model, Table, QualifiedName}

object Generator {

  def run(uri: URI,
          pkg: String,
          schemaNames: Option[List[String]],
          outputPath: String,
          manualForeignKeys: Map[(String, String), (String, String)],
          schemaBaseClass: String,
          idType: Option[String],
          schemaImports: List[String]) = {
    val dc: DatabaseConfig[JdbcProfile] =
      DatabaseConfig.forURI[JdbcProfile](uri)
    val parsedSchemasOpt: Option[Map[String, List[String]]] =
      schemaNames.map(SchemaParser.parse)
    val dbModel: Model = Await.result(
      dc.db.run(SchemaParser.createModel(dc.driver, parsedSchemasOpt)),
      Duration.Inf)

    val generator = new Generator(uri,
                                  pkg,
                                  dbModel,
                                  outputPath,
                                  manualForeignKeys,
                                  schemaBaseClass,
                                  idType,
                                  schemaImports)
    generator.code // Yes... Files are written as a side effect
    parsedSchemasOpt
      .getOrElse(Map())
      .keys
      .map(schemaName => FileHelpers.schemaOutputPath(outputPath, schemaName))
  }

}

class PackageNameGenerator(pkg: String, dbModel: Model)
    extends SourceCodeGenerator(dbModel) {
  override def code: String =
    s"""
       |// format: OFF
       |// scalastyle:off
       |package ${pkg}
       |
       |""".stripMargin
}

class ImportGenerator(dbModel: Model, schemaImports: List[String])
    extends SourceCodeGenerator(dbModel) {

  val baseImports: String = schemaImports.map("import " + _).mkString("\n")

  val hlistImports: String =
    if (tables.exists(_.hlistEnabled))
      """
        |import slick.collection.heterogeneous._
        |import slick.collection.heterogeneous.syntax._
        |
        |""".stripMargin
    else ""

  val plainSqlMapperImports: String =
    if (tables.exists(_.PlainSqlMapper.enabled))
      """
        |import slick.jdbc.{GetResult => GR}
        |//NOTE: GetResult mappers for plain SQL are only generated for tables where Slick knows how to map the types of all columns.\n
        |
        |""".stripMargin
    else ""

  override def code: String =
    baseImports + hlistImports + plainSqlMapperImports
}

class Generator(uri: URI,
                pkg: String,
                dbModel: Model,
                outputPath: String,
                manualForeignKeys: Map[(String, String), (String, String)],
                schemaBaseClass: String,
                idType: Option[String],
                schemaImports: List[String])
    extends SourceCodeGenerator(dbModel)
    with OutputHelpers {

  val packageName = new PackageNameGenerator(pkg, dbModel).code
  val allImports: String = new ImportGenerator(dbModel, schemaImports).code

  val defaultIdImplementation =
    """|final case class Id[T](v: Int)
       |trait DefaultIdTypeMapper {
       |  val profile: slick.driver.JdbcProfile
       |  import profile.api._
       |  implicit def idTypeMapper[A]: BaseColumnType[Id[A]] = MappedColumnType.base[Id[A], Int](_.v, Id(_))
       |}
       |""".stripMargin

  override def code: String = {

    val sortedSchemaTables: List[(String, Seq[TableDef])] = tables
      .groupBy(t => t.model.name.schema.getOrElse("`public`"))
      .toList
      .sortBy(_._1)

    val schemata: String = sortedSchemaTables.map {
      case (schemaName, tableDefs) =>
        val tableCode = tableDefs
          .sortBy(_.model.name.table)
          .map(_.code.mkString("\n"))
          .mkString("\n\n")
        val generatedSchema = s"""
          |  val profile = slick.backend.DatabaseConfig.forConfig("${uri.getFragment()}").driver
          |  import profile.api._
          |  ${tableCode}
          |
          |}
          |// scalastyle:on""".stripMargin

        writeStringToFile(
          packageName + allImports + generatedSchema,
          outputPath,
          pkg,
          s"${schemaName}.scala"
        )

        if (idType.isEmpty) {
          writeStringToFile(packageName + defaultIdImplementation,
                            outputPath,
                            pkg,
                            "Id.scala")
        }

        generatedSchema
    }.mkString("\n\n")

    allImports + schemata
  }

  override def Table = new Table(_) { table =>

    // need this in order to use our own TableClass generator
    override def definitions =
      Seq[Def](EntityTypeRef, PlainSqlMapper, TableClassRef, TableValue)

    def TableClassRef = new TableClass() {
      // We disable the option mapping, as it is a bit more complex to support and we don't appear to need it
      override def option = "" // if(hlistEnabled) "" else super.option
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

    def EntityTypeRef = new EntityTypeDef {
      override def code: String =
        (if (classEnabled) "final " else "") + super.code
    }

    override def Column = new Column(_) { column =>

      val manualReferences =
        SchemaParser.references(dbModel, manualForeignKeys)

      // work out the destination of the foreign key
      def derefColumn(table: sModel.Table,
                      column: sModel.Column): (sModel.Table, sModel.Column) = {
        val referencedColumn: Seq[(sModel.Table, sModel.Column)] =
          table.foreignKeys
            .filter(tableFk => tableFk.referencingColumns.forall(_ == column))
            .filter(columnFk => columnFk.referencedColumns.length == 1)
            .flatMap(_.referencedColumns.map(c =>
              (dbModel.tablesByName(c.table), c)))
        assert(referencedColumn.distinct.length <= 1, referencedColumn)

        referencedColumn.headOption
          .orElse(manualReferences.get((table.name.asString, column.name)))
          .map((derefColumn _).tupled)
          .getOrElse((table, column))
      }

      def tableReferenceName(tableName: QualifiedName) = {
        val schemaObjectName = tableName.schema.getOrElse("`public`")
        val rowTypeName = entityName(tableName.table)
        val idTypeName = idType.getOrElse("Id")
        s"$idTypeName[$schemaObjectName.$rowTypeName]"
      }

      // re-write ids, and time types
      override def rawType: String = {
        val (referencedTable, referencedColumn) =
          derefColumn(table.model, column.model)
        if (referencedColumn.options.contains(
              slick.ast.ColumnOption.PrimaryKey))
          tableReferenceName(referencedTable.name)
        else
          model.tpe match {
            // TODO: There should be a way to add adhoc custom time mappings
            case "java.sql.Time" => "xyz.driver.core.time.Time"
            case "java.sql.Timestamp" => "xyz.driver.core.time.Time"
            case _ => super.rawType
          }
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

object SchemaParser {
  def references(dbModel: Model,
                 tcMappings: Map[(String, String), (String, String)])
    : Map[(String, String), (Table, Column)] = {
    def getTableColumn(tc: (String, String)): (Table, Column) = {
      val (tableName, columnName) = tc
      val table = dbModel.tables
        .find(_.name.asString == tableName)
        .getOrElse(throw new RuntimeException("No table " + tableName))
      val column = table.columns
        .find(_.name == columnName)
        .getOrElse(throw new RuntimeException(
          "No column " + columnName + " in table " + tableName))
      (table, column)
    }

    tcMappings.map {
      case (from, to) => ({ getTableColumn(from); from }, getTableColumn(to))
    }
  }

  def parse(schemaTableNames: List[String]): Map[String, List[String]] =
    schemaTableNames
      .map(_.split('.'))
      .groupBy(_.head)
      .mapValues(_.flatMap(_.tail))

  def createModel(
      jdbcProfile: JdbcProfile,
      mappedSchemasOpt: Option[Map[String, List[String]]]): DBIO[Model] = {
    val allTables: DBIO[Vector[MTable]] = MTable.getTables

    val filteredTables = mappedSchemasOpt.map(
      mappedSchemas =>
        allTables.map(
          (tables: Vector[MTable]) =>
            tables.filter(
              table =>
                table.name.schema
                  .flatMap(mappedSchemas.get)
                  .exists(ts => ts.isEmpty || ts.contains(table.name.name)))))

    jdbcProfile.createModel(filteredTables orElse Some(allTables))
  }

}

object FileHelpers {
  def schemaOutputPath(path: String, schemaName: String): String =
    Paths.get(path, s"${schemaName}.scala").toAbsolutePath().toString()
}
