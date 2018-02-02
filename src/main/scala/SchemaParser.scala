import scala.concurrent.ExecutionContext.Implicits.global

import slick.dbio.DBIO
import slick.jdbc.JdbcProfile
import slick.jdbc.meta.MTable
import slick.relational.RelationalProfile.ColumnOption.Length
import slick.sql.SqlProfile.ColumnOption.SqlType
import slick.{model => m}

object ModelTransformation {

  def citextColumnNoLength(column: m.Column): m.Column =
    if (column.options contains SqlType("citext")) {
      column.copy(options = column.options.filter {
        case _: Length => false
        case _         => true
      })
    } else column

  def citextNoLength(dbModel: m.Model): m.Model =
    dbModel.copy(
      tables = dbModel.tables.map(table =>
        table.copy(
          primaryKey = table.primaryKey.map(pk => pk.copy(columns = pk.columns.map(citextColumnNoLength))),
          columns = table.columns.map(citextColumnNoLength),
          indices = table.indices.map(index => index.copy(columns = index.columns.map(citextColumnNoLength))),
          foreignKeys = table.foreignKeys.map { fk =>
            fk.copy(
              referencedColumns = fk.referencedColumns.map(citextColumnNoLength),
              referencingColumns = fk.referencingColumns.map(citextColumnNoLength)
            )
          }
      )))

  def references(
      dbModel: m.Model,
      tcMappings: Map[(String, String), (String, String)]): Map[(String, String), (m.Table, m.Column)] = {
    def getTableColumn(tc: (String, String)): (m.Table, m.Column) = {
      val (tableName, columnName) = tc
      val table = dbModel.tables
        .find(_.name.asString == tableName)
        .getOrElse(throw new RuntimeException("No table " + tableName))
      val column = table.columns
        .find(_.name == columnName)
        .getOrElse(throw new RuntimeException("No column " + columnName + " in table " + tableName))
      (table, column)
    }

    tcMappings.map {
      case (from, to) => ({ getTableColumn(from); from }, getTableColumn(to))
    }
  }

  def parseSchemaList(schemaTableNames: List[String]): Map[String, List[String]] =
    schemaTableNames
      .map(_.split('.'))
      .groupBy(_.head)
      .mapValues(_.flatMap(_.tail))

  def createModel(jdbcProfile: JdbcProfile, mappedSchemasOpt: Option[Map[String, List[String]]]): DBIO[m.Model] = {
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
                      case _                                          => false
                  })
                  .getOrElse(throw new IllegalArgumentException(
                    s"$schemaName.$tableName does not exist in the connected database.")))
        }.toList
      }
    }

    jdbcProfile.createModel(filteredTables).map(citextNoLength)
  }
}
