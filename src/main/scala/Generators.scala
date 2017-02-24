import slick.codegen.SourceCodeGenerator
import slick.{model => m}

class RowSourceCodeGenerator(
  model: m.Model,
  override val headerComment: String,
  override val imports: String,
  override val schemaName: String
) extends SourceCodeGenerator(model) with RowOutputHelpers {

  override def Table = new Table(_) { table =>
    override def EntityType = new EntityType {
      override def code: String =
        (if (classEnabled) "final " else "") + super.code
    }

    override def code = Seq[Def](EntityType).map(_.docWithCode)
  }

  override def code = tables.map(_.code.mkString("\n")).mkString("\n\n")
}
