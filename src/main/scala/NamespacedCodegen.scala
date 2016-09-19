import java.io.{FileWriter, File}
import java.net.URI

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.reflect.runtime.currentMirror

import slick.ast.ColumnOption
import slick.backend.DatabaseConfig
import slick.codegen.{AbstractGenerator, SourceCodeGenerator}
import slick.dbio.{DBIO, DBIOAction, NoStream}
import slick.driver.JdbcProfile
import slick.jdbc.meta.MTable
import slick.{model => m}
import slick.model.{Column, Model, Table}
import slick.util.ConfigExtensionMethods.configExtensionMethods

// NamespacedCodegen handles tables within schemas by namespacing them
// within objects here
// (e.g., table a.foo and table b.foo can co-exist, because this code
// generator places the relevant generated classes into separate
// objects--a "a" object, and a "b" object)
object NamespacedCodegen {
  def parseSchemaList(schemaTableNames: List[String]): Map[String, List[String]] =
    schemaTableNames.map(_.split('.'))
      .groupBy(_.head)
      .mapValues(_.flatMap(_.tail))
      .toMap

  def createFilteredModel(driver: JdbcProfile, mappedSchemas: Map[String, List[String]]): DBIO[Model] =
    driver.createModel(Some(
      MTable.getTables.map(_.filter((t: MTable) =>
        t.name.schema.flatMap(mappedSchemas.get).exists(tables =>
          tables.isEmpty || tables.contains(t.name.name))))))

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

  def run(
    uri: URI,
    pkg: String,
    filename: String,
    typesFilename: String,
    schemaTableNames: List[String],
    manualForeignKeys: Map[(String, String), (String, String)]
  ): Unit = {
    val dc = DatabaseConfig.forURI[JdbcProfile](uri)
    val slickDriver = if(dc.driverIsObject) dc.driverName else "new " + dc.driverName
    val mappedSchemas = parseSchemaList(schemaTableNames)
    val dbModel = Await.result(dc.db.run(createFilteredModel(dc.driver, mappedSchemas)), Duration.Inf)
    //finally dc.db.close

    val manualReferences = references(dbModel, manualForeignKeys)

    def codegen(typeFile: Boolean) = new SourceCodeGenerator(dbModel){

      def derefColumn(table: m.Table, column: m.Column): (m.Table, m.Column) = {
        val referencedColumn: Seq[(m.Table, m.Column)] = table.foreignKeys
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

      // Is this compatible with ***REMOVED*** Id? How do we make it generic?
      def idType(t: m.Table) : String =
        "Id["+ t.name.schema.fold("public")(_ + ".") + t.name.table.toCamelCase+"Row]"

      override def code = {
        //imports is copied right out of
        //scala.slick.model.codegen.AbstractSourceCodeGenerator
        // Why can't we simply re-use?

        var imports =
          "import slick.model.ForeignKeyAction\n" +
        ( if(tables.exists(_.hlistEnabled)){
          "import slick.collection.heterogeneous._\n" +
          "import slick.collection.heterogeneous.syntax._\n" +
          "import com.drivergrp.core._\n" +
          "import com.drivergrp.core.database._\n"
        } else ""
        ) +
        ( if(tables.exists(_.PlainSqlMapper.enabled)){
          "import slick.jdbc.{GetResult => GR}\n"+
          "// NOTE: GetResult mappers for plain SQL are only generated for tables where Slick knows how to map the types of all columns.\n"
        } else ""
        ) + "\n\n" // We didn't copy ddl though

        val sortedSchemaTables: List[(String, Seq[TableDef])] = tables
          .groupBy(t => t.model.name.schema.getOrElse("`public`"))
          .toList.sortBy(_._1)

        val schemata: String = sortedSchemaTables.map {
          case (schemaName, tables) =>
            val tableCode = tables
              .sortBy(_.model.name.table)
              .map(_.code.mkString("\n"))
              .mkString("\n\n")
            indent(s"object $schemaName extends CoreDBMappers {\n$tableCode")+"\n}\n"
        }.mkString("\n\n")

        val mapperTrait: String = """trait CoreDBMappers extends com.drivergrp.core.database.IdColumnTypes { override val database = com.drivergrp.core.database.Database.fromConfig("slick.db.default") }"""

        imports + mapperTrait + "\n\n" + schemata
      }

      // This is overridden to output classfiles elsewhere
      override def Table = new Table(_) {
        table =>
        // case classes go in the typeFile (but not types based on hlists)
        override def definitions =
          if (typeFile) Seq[Def](EntityType)
          else Seq[Def](EntityTypeRef, PlainSqlMapper, TableClassRef, TableValue)


        def EntityTypeRef = new EntityType() {
          override def code =
            s"type $name = $pkg.rows.${model.name.schema.get}.$name\n" ++
          s"val $name = $pkg.rows.${model.name.schema.get}.$name"
        }

        /** Creates a compound type from a given sequence of types.
          *  Uses HList if hlistEnabled else tuple.
          */
        override def compoundType(types: Seq[String]): String =
          /** Creates a compound value from a given sequence of values.
            *  Uses HList if hlistEnabled else tuple.
            */
          // Yes! This is part of Slick now, yes?
          if (hlistEnabled){
            def mkHList(types: List[String]): String = types match {
              case Nil => "HNil"
              case e :: tail => s"HCons[$e," + mkHList(tail) + "]"
            }
            mkHList(types.toList)
          }
          else compoundValue(types)

        //why?
        override def mappingEnabled = true

        override def compoundValue(values: Seq[String]): String =
          if (hlistEnabled) values.mkString(" :: ") + " :: HNil"
          else if (values.size == 1) values.head
          else if(values.size <= 22) s"""(${values.mkString(", ")})"""
          else throw new Exception("Cannot generate tuple for > 22 columns, please set hlistEnable=true or override compound.")

        def TableClassRef = new TableClass() {
          // We disable the option mapping for >22 columns, as it is a bit more complex to support and we don't appear to need it
          override def option = if(columns.size <= 22) super.option else ""
        }

        override def factory   =
          if(columns.size <= 22) super.factory
          else {
            val args = columns.zipWithIndex.map("a"+_._2)
            val hlist = args.mkString("::") + ":: HNil"
            val hlistType = columns.map(_.actualType).mkString("::") + ":: HNil.type"
            s"((h : $hlistType) => h match {case $hlist => ${TableClass.elementType}(${args.mkString(",")})})"
          }
        override def extractor =
          if(columns.size <= 22) super.extractor
          else s"(a : ${TableClass.elementType}) => Some(" + columns.map("a."+_.name ).mkString("::") + ":: HNil)"

        // make foreign keys refer to namespaced referents
        // if the referent is in a different namespace
        override def ForeignKey = new ForeignKey(_) {
          override def code = {
            val fkColumns = compoundValue(referencingColumns.map(_.name))
            // Add the schema name to qualify the referenced table name if:
            // 1. it's in a different schema from referencingTable, and
            // 2. it's not None
            val qualifier = if (referencedTable.model.name.schema
              != referencingTable.model.name.schema) {
              referencedTable.model.name.schema match {
                case Some(schema) => schema + "."
                case None => ""
              }
            } else {
              ""
            }
            val qualifiedName = qualifier + referencedTable.TableValue.name
            val pkColumns = compoundValue(referencedColumns.map(c => s"r.${c.name}${if (!c.model.nullable && referencingColumns.forall(_.model.nullable)) ".?" else ""}"))
            val fkName = referencingColumns.map(_.name).flatMap(_.split("_")).map(_.capitalize).mkString.uncapitalize + "Fk"
            s"""lazy val $fkName = foreignKey("$dbName", $fkColumns, $qualifiedName)(r => $pkColumns, onUpdate=${onUpdate}, onDelete=${onDelete})"""
          }
        }

        override def Column = new Column(_) { column =>
          // customize db type -> scala type mapping, pls adjust it according to your environment

          override def rawType = {
            val (t, c) = derefColumn(table.model, column.model)
            //System.out.print(s"${table.model.name.asString}:${column.model.name} -> ${t.name.asString}:${c.name}\n")
            if (c.options.exists(_.toString.contains("PrimaryKey"))) idType(t)
            // ^ahahaha This is hacky
            // This should be customizeable by client
            
            else model.tpe match {
              // how does this type work out?
              // There should be a way to add adhoc custom time mappings
              case "java.sql.Time" => "com.drivergrp.core.time.Time"
              case "java.sql.Timestamp" => "com.drivergrp.core.time.Time"
              case _ => super.rawType
            }
          }
        }
      }
    }

    def write(c: String, name: String) = {
      (new File(name).getParentFile).mkdirs()
      val fw = new FileWriter(name)
      fw.write(c)
      fw.close()
    }
    val disableScalaStyle = "// scalastyle:off\n"
    val tablesSource = codegen(false).packageCode(slickDriver, pkg, "Tables", None)
    val rowsSource = s"package $pkg.rows\n\n" + codegen(true).code

    write(disableScalaStyle + tablesSource, filename)
    write(disableScalaStyle + rowsSource, typesFilename)
  }
}

