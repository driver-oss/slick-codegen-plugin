import java.net.URI
import java.io.{BufferedWriter, File, FileWriter}
import java.nio.file.Paths

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext.Implicits.global
import slick.backend.DatabaseConfig
import slick.codegen.{OutputHelpers, SourceCodeGenerator, StringGeneratorHelpers}
import slick.dbio.DBIO
import slick.driver.JdbcProfile
import slick.jdbc.meta.MTable
import slick.{model => sModel}
import slick.model.{Column, Model, Table}

object Generator {


  def run(uri: URI, pkg: String, schemaNames: List[String], outputPath: String, manualForeignKeys: Map[(String, String), (String, String)]) = {
    val dc: DatabaseConfig[JdbcProfile] = DatabaseConfig.forURI[JdbcProfile](uri)
    val parsedSchemas: Map[String, List[String]] = SchemaParser.parse(schemaNames)
    val dbModel: Model = Await.result(dc.db.run(SchemaParser.createModel(dc.driver, parsedSchemas)), Duration.Inf)

    val generator = new Generator(uri, pkg, dbModel, outputPath, manualForeignKeys)
    val generatedCode = generator.code
    parsedSchemas.keys.map(schemaName => FileHelpers.schemaOutputPath(outputPath, schemaName))
  }

}

class ImportGenerator(dbModel: Model) extends SourceCodeGenerator(dbModel) {
  val baseImports: String =
    s"""
       |
     |import com.drivergrp.core._
       |import com.drivergrp.core.database._
       |
    |""".stripMargin

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

  override def code: String = baseImports + hlistImports + plainSqlMapperImports
}

class Generator(uri: URI, pkg: String, dbModel: Model, outputPath: String, manualForeignKeys: Map[(String, String), (String, String)]) extends SourceCodeGenerator(dbModel) with OutputHelpers {

  val allImports: String = new ImportGenerator(dbModel).code

  override def code: String = {

    val sortedSchemaTables: List[(String, Seq[TableDef])] = tables
      .groupBy(t => t.model.name.schema.getOrElse("`public`"))
      .toList.sortBy(_._1)


    val schemata: String = sortedSchemaTables.map {
      case (schemaName, tableDefs) =>
        val tableCode = tableDefs.sortBy(_.model.name.table).map(_.code.mkString("\n")) .mkString("\n\n")
        val generatedSchema = s"""
          |object ${schemaName} extends IdColumnTypes {
          |  override val database = com.drivergrp.core.database.Database.fromConfig("${uri.getFragment()}")
          |  import database.profile.api._
          |  // TODO: the name for this implicit should be changed in driver core
          |  implicit val tColType = MappedColumnType.base[com.drivergrp.core.time.Time, Long](time => time.millis, com.drivergrp.core.time.Time(_))
          |  ${tableCode}
          |}
        """.stripMargin

        writeStringToFile(
          allImports + generatedSchema,
          outputPath,
          pkg,
          s"${schemaName}.scala"
        )

        generatedSchema
    }.mkString("\n\n")

    allImports + schemata
  }


  override def Table = new Table(_) { table =>

    // need this in order to use our own TableClass generator
    override def definitions = Seq[Def]( EntityType, PlainSqlMapper, TableClassRef, TableValue )

    def TableClassRef = new TableClass() {
      // We disable the option mapping, as it is a bit more complex to support and we don't appear to need it
      override def option = "" // if(hlistEnabled) "" else super.option
    }

    // use hlists all the time
    override def hlistEnabled: Boolean = true

    // if false rows are type aliases to hlists, if true rows are case classes
    override def mappingEnabled: Boolean = true

    // create case class from colums
    override def factory: String   =
    if(!hlistEnabled) super.factory
    else {
      val args = columns.zipWithIndex.map("a"+_._2)
      val hlist = args.mkString("::") + ":: HNil"
      val hlistType = columns.map(_.actualType).mkString("::") + ":: HNil.type"
      s"((h : $hlistType) => h match {case $hlist => ${TableClass.elementType}(${args.mkString(",")})})"
    }

    // from case class create columns
    override def extractor: String =
    if(!hlistEnabled) super.extractor
    else s"(a : ${TableClass.elementType}) => Some(" + columns.map("a."+_.name ).mkString("::") + ":: HNil)"


    override def Column = new Column(_) {
      column =>

      val manualReferences = SchemaParser.references(dbModel, manualForeignKeys)

      // work out the destination of the foreign key
      def derefColumn(table: sModel.Table, column: sModel.Column): (sModel.Table, sModel.Column) = {
        val referencedColumn: Seq[(sModel.Table, sModel.Column)] = table.foreignKeys
          .filter(tableFk => tableFk.referencingColumns.forall(_ == column))
          .filter(columnFk => columnFk.referencedColumns.length == 1)
          .flatMap(_.referencedColumns
            .map(c => (dbModel.tablesByName(c.table), c)))
        assert(referencedColumn.distinct.length <= 1, referencedColumn)

        referencedColumn.headOption
          .orElse(manualReferences.get((table.name.asString, column.name)))
          .map((derefColumn _).tupled)
          .getOrElse((table, column))
      }

      // re-write ids, and time types
      override def rawType: String = {
        val (t, c) = derefColumn(table.model, column.model)
        if (c.options.contains(slick.ast.ColumnOption.PrimaryKey)) TypeGenerator.idType(pkg, t)
        else model.tpe match {
          // TODO: There should be a way to add adhoc custom time mappings
          case "java.sql.Time" => "com.drivergrp.core.time.Time"
          case "java.sql.Timestamp" => "com.drivergrp.core.time.Time"
          case _ => super.rawType
        }
      }
    }

    override def ForeignKey = new ForeignKey(_) {
      override def code = {
        val fkColumns = compoundValue(referencingColumns.map(_.name))
        val qualifier =
          if (referencedTable.model.name.schema == referencingTable.model.name.schema) ""
          else referencedTable.model.name.schema.fold("")(sname => s"$pkg.$sname.")

        val qualifiedName = qualifier + referencedTable.TableValue.name
        val pkColumns = compoundValue(referencedColumns.map(c => s"r.${c.name}${if (!c.model.nullable && referencingColumns.forall(_.model.nullable)) ".?" else ""}"))
        val fkName = referencingColumns.map(_.name).flatMap(_.split("_")).map(_.capitalize).mkString.uncapitalize + "Fk"
        s"""lazy val $fkName = foreignKey("$dbName", $fkColumns, $qualifiedName)(r => $pkColumns, onUpdate=$onUpdate, onDelete=$onDelete)"""
      }
    }

  }

}

object SchemaParser {
  def references(dbModel: Model, tcMappings: Map[(String, String), (String, String)]): Map[(String, String), (Table, Column)] = {
    def getTableColumn(tc: (String, String)) : (Table, Column) = {
      val (tableName, columnName) = tc
      val table = dbModel.tables.find(_.name.asString == tableName)
        .getOrElse(throw new RuntimeException("No table " + tableName))
      val column = table.columns.find(_.name == columnName)
        .getOrElse(throw new RuntimeException("No column " + columnName + " in table " + tableName))
      (table, column)
    }

    tcMappings.map{case (from, to) => ({getTableColumn(from); from}, getTableColumn(to))}
  }

  def parse(schemaTableNames: List[String]): Map[String, List[String]] =
    schemaTableNames.map(_.split('.'))
      .groupBy(_.head)
      .mapValues(_.flatMap(_.tail))

  def createModel(jdbcProfile: JdbcProfile, mappedSchemas: Map[String, List[String]]): DBIO[Model] = {
    val allTables: DBIO[Vector[MTable]] = MTable.getTables

    val filteredTables: DBIO[Vector[MTable]] = allTables.map(
      (tables: Vector[MTable]) => tables.filter(table =>
        table.name.schema.flatMap(mappedSchemas.get).exists(ts =>
          ts.isEmpty || ts.contains(table.name.name))
      )
    )
    jdbcProfile.createModel(Some(filteredTables))
  }

}

object TypeGenerator extends StringGeneratorHelpers {
  // generate the id types
  def idType(pkg: String, t: sModel.Table): String = {
    val header = s"Id["
    val schemaName = t.name.schema.fold("")(_ + ".")
    val tableName = (t.name.table).toCamelCase
    val footer = "]"
    s"${header}${pkg}.${schemaName}${tableName}Row${footer}"
  }
}


object FileHelpers {
  def schemaOutputPath(path: String, schemaName: String): String =
    Paths.get(path, s"${schemaName}.scala").toAbsolutePath().toString()
}
