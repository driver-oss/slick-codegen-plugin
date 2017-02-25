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

      def tableReferenceName(tableName: m.QualifiedName) = {
        val schemaObjectName = tableName.schema.getOrElse("`public`")
        val rowTypeName = entityName(tableName.table)
        val idTypeName = idType.getOrElse("Id")
        s"$idTypeName[$schemaObjectName.$rowTypeName]"
      }

      override def rawType: String = {
        // write key columns as Id types
        val (referencedTable, referencedColumn) =
          derefColumn(table.model, column.model)
        if (referencedColumn.options.contains(
              slick.ast.ColumnOption.PrimaryKey))
          tableReferenceName(referencedTable.name)
        else super.rawType
      }
    }
  }
}
