import slick.codegen.SourceCodeGenerator
import slick.{model => m}

class RowSourceCodeGenerator(
  model: m.Model,
  override val headerComment: String,
  override val imports: String,
  override val schemaName: String,
  fullDatabaseModel: m.Model,
  idType: Option[String],
  manualForeignKeys: Map[(String, String), (String, String)]
) extends TypedIdSourceCodeGenerator(
  fullDatabaseModel,
  idType,
  manualForeignKeys
) with RowOutputHelpers {

  override def Table = new TypedIdTable(_) { table =>
    override def Column = new TypedIdColumn(_) { }
    override def EntityType = new EntityType {
      override def code: String =
        (if (classEnabled) "final " else "") + super.code
    }

    override def code = Seq[Def](EntityType).map(_.docWithCode)
  }

  override def code = tables.map(_.code.mkString("\n")).mkString("\n\n")
}
