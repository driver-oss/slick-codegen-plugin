import slick.codegen.SourceCodeGenerator
import slick.{model => m}

class TypedIdSourceCodeGenerator(
    singleSchemaModel: m.Model,
    databaseModel: m.Model,
    idType: Option[String],
    manualForeignKeys: Map[(String, String), (String, String)]
) extends SourceCodeGenerator(singleSchemaModel) {
  val manualReferences =
    ModelTransformation.references(databaseModel, manualForeignKeys)

  val modelTypeToColumnMaper = Map(
    "java.util.UUID" -> "uuidKeyMapper",
    "String" -> "naturalKeyMapper",
    "Int" -> "serialKeyMapper",
    "Long" -> "serialKeyMapper"
  )

  val keyReferences: Map[m.Column, m.Column] = {
    val pks = databaseModel.tables
      .flatMap(_.columns)
      .filter(_.options.contains(slick.ast.ColumnOption.PrimaryKey))
      .map(c => (c -> c))

    val fks: Seq[(m.Column, m.Column)] = databaseModel.tables
      .flatMap(_.foreignKeys)
      .filter(_.referencedColumns.length == 1)
      .filter(_.referencedColumns.forall(
        _.options.contains(slick.ast.ColumnOption.PrimaryKey)))
      .flatMap(fk =>
        fk.referencingColumns.flatMap(from =>
          fk.referencedColumns.headOption.map(to => (from -> to))))

    (pks ++ fks).toMap
  }

  def pKeyTypeTag(columnRef: m.Column): String = {
    val schemaName = columnRef.table.schema.getOrElse("`public`")
    val tableName = entityName(columnRef.table.table)
    s"$schemaName.$tableName"
  }

  def pKeyType(columnRef: m.Column): String = {
    s"${idType.getOrElse("Id")}[${pKeyTypeTag(columnRef)}]"
  }

  class TypedIdTable(model: m.Table) extends Table(model) { table =>
    override def definitions =
      Seq[Def](EntityType,
               PlainSqlMapper,
               TableClass,
               TableValue,
               PrimaryKeyMapper)

    class TypedIdColumn(override val model: m.Column) extends Column(model) {
      override def rawType: String = {
        keyReferences.get(model).fold(super.rawType)(pKeyType)
      }
    }

    type PrimaryKeyMapper = PrimaryKeyMapperDef

    def PrimaryKeyMapper = new PrimaryKeyMapper {}

    class PrimaryKeyMapperDef extends TermDef {
      def primaryKeyColumn: Option[Column] = {
        table.model.columns
          .flatMap(c => keyReferences.get(c).filter(_ == c))
          .headOption
          .map(c => table.columnsByName(c.name))
      }

      override def enabled = primaryKeyColumn.isDefined

      override def doc =
        s"Implicit for mapping primary key of ${tableName(table.model.name.table)} to a base column"

      override def rawName = tableName(table.model.name.table) + "KeyMapper"

      override def code = primaryKeyColumn.fold("") { column =>
        val tpe = s"BaseColumnType[${column.rawType}]"
        s"""|implicit def $name: $tpe = 
            |${modelTypeToColumnMaper(column.model.tpe)}[${pKeyTypeTag(
             column.model)}]
            |""".stripMargin.lines.mkString("").trim
      }
    }
  }
}
