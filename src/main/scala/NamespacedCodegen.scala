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
          parentType: Option[String],
          idType: Option[String],
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

          val generator = new Generator(pkg,
                                        dbModel,
                                        schemaOnlyModel,
                                        manualForeignKeys,
                                        idType,
                                        schemaImports,
                                        typeReplacements)
          generator.writeStringToFile(
            content = generator.packageCode(profile = profile,
                                            pkg = pkg,
                                            container = schemaName,
                                            parentType = parentType),
            folder = outputPath,
            pkg = pkg,
            fileName = s"${schemaName}.scala")
          // TODO: Use generator.writeToFile, after figuring out `parentType`
          // parentType in scope used here is defaulted to None and needs to be overriden
      }
    } finally {
      dc.db.close()
    }
  }

}

class ImportGenerator(dbModel: Model, schemaImports: List[String])
    extends SourceCodeGenerator(dbModel) {
  override def code: String =
    schemaImports.map("import " + _).mkString("\n") + "\n"
}

class Generator(pkg: String,
                fullDatabaseModel: Model,
                schemaOnlyModel: Model,
                manualForeignKeys: Map[(String, String), (String, String)],
                idType: Option[String],
                schemaImports: List[String],
                typeReplacements: Map[String, String])
    extends SourceCodeGenerator(schemaOnlyModel)
    with OutputHelpers {

  val allImports: String =
    new ImportGenerator(fullDatabaseModel, schemaImports).code

  val defaultIdImplementation =
    """|final case class Id[T](v: Int)
       |trait DefaultIdTypeMapper {
       |  val profile: slick.driver.JdbcProfile
       |  import profile.api._
       |  implicit def idTypeMapper[A]: BaseColumnType[Id[A]] = MappedColumnType.base[Id[A], Int](_.v, Id(_))
       |}
       |""".stripMargin

  val filteredCode = code.lines.drop(1).mkString
  // Drops needless import: `"import slick.model.ForeignKeyAction\n"`.
  // Alias to ForeignKeyAction is in profile.api
  // TODO: fix upstream

  override def packageCode(profile: String,
                           pkg: String,
                           container: String,
                           parentType: Option[String]): String = {
    s"""|package $pkg
        |$allImports
        |object ${container} extends {
        |  val profile = $profile
        |} with ${parentType.getOrElse("AnyRef")} {
        |  import profile.api._
        |  ${filteredCode}
        |}""".stripMargin
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
      // use fullDatabasemodel model here for cross-schema foreign keys
      val manualReferences =
        SchemaParser.references(fullDatabaseModel, manualForeignKeys)

      // work out the destination of the foreign key
      def derefColumn(table: sModel.Table,
                      column: sModel.Column): (sModel.Table, sModel.Column) = {
        val referencedColumn: Seq[(sModel.Table, sModel.Column)] =
          table.foreignKeys
            .filter(tableFk => tableFk.referencingColumns.forall(_ == column))
            .filter(columnFk => columnFk.referencedColumns.length == 1)
            .flatMap(_.referencedColumns.map(c =>
              (fullDatabaseModel.tablesByName(c.table), c)))
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

      // re-write ids other custom types
      override def rawType: String = {
        val (referencedTable, referencedColumn) =
          derefColumn(table.model, column.model)
        if (referencedColumn.options.contains(
              slick.ast.ColumnOption.PrimaryKey))
          tableReferenceName(referencedTable.name)
        else typeReplacements.getOrElse(model.tpe, model.tpe)
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
    import slick.jdbc.meta.MQName

    val filteredTables = mappedSchemasOpt.map { mappedSchemas =>
      MTable.getTables.map { (tables: Vector[MTable]) =>
        mappedSchemas.flatMap {
          case (schemaName, tableNames) =>
            tableNames.map(
              tableName =>
                tables
                  .find(table =>
                    table.name match {
                      case MQName(_, Some(`schemaName`), `tableName`) => true
                      case _ => false
                  })
                  .getOrElse(throw new IllegalArgumentException(
                    s"$schemaName.$tableName does not exist in the connected database.")))
        }.toList
      }
    }

    jdbcProfile.createModel(filteredTables)
  }
}
