import slick.codegen.SourceCodeGenerator
import slick.{model => m}

class TypedIdSourceCodeGenerator(
    singleSchemaModel: m.Model,
    databaseModel: m.Model,
    idType: Option[String],
    manualForeignKeys: Map[(String, String), (String, String)]
) extends SourceCodeGenerator(singleSchemaModel) {
  val manualReferences =
    SchemaParser.references(databaseModel, manualForeignKeys)

  val modelTypeToColumnMaper = Map(
    "java.util.UUID" -> "uuidKeyMapper",
    "String" -> "naturalKeyMapper",
    "Int" -> "serialKeyMapper"
  )

  def derefColumn(table: m.Table, column: m.Column): (m.Table, m.Column) = {
    val referencedColumn: Seq[(m.Table, m.Column)] =
      table.foreignKeys
        .filter(tableFk => tableFk.referencingColumns.forall(_ == column))
        .filter(columnFk => columnFk.referencedColumns.length == 1)
        .flatMap(_.referencedColumns.map(c =>
          (databaseModel.tablesByName(c.table), c)))
    assert(referencedColumn.distinct.length <= 1, referencedColumn)

    referencedColumn.headOption
      .orElse(manualReferences.get((table.name.asString, column.name)))
      .map((derefColumn _).tupled)
      .getOrElse((table, column))
  }

  class TypedIdTable(model: m.Table) extends Table(model) { table =>
    class TypedIdColumn(override val model: m.Column) extends Column(model) {
      column =>

      def rowTypeFor(tableName: m.QualifiedName) = {
        val schemaObjectName = tableName.schema.getOrElse("`public`")
        val rowTypeName = entityName(tableName.table)
        s"$schemaObjectName.$rowTypeName"
      }

      override def code = {
        val (referencedTable, referencedColumn) =
          derefColumn(table.model, column.model)
        if (referencedColumn.options.contains(
          slick.ast.ColumnOption.PrimaryKey))
          s"""|implicit val ${name}KeyMapper: BaseColumnType[${rawType}] =
              |  ${modelTypeToColumnMaper(model.tpe)}[${rowTypeFor(referencedTable.name)}]\n
              |${super.code}"""
        else
          super.code
      }

      override def rawType: String = {
        // write key columns as Id types
        val (referencedTable, referencedColumn) =
          derefColumn(table.model, column.model)
        if (referencedColumn.options.contains(
          slick.ast.ColumnOption.PrimaryKey)) {
          val idTypeName = idType.getOrElse("Id")
          s"$idTypeName[${rowTypeFor(referencedTable.name)}]"
        }
        else super.rawType
      }
    }
  }
}
