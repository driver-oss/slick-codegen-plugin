import slick.{model => m}

class RowSourceCodeGenerator(
        model: m.Model,
        override val headerComment: String,
        override val imports: String,
        override val schemaName: String,
        fullDatabaseModel: m.Model,
        idType: Option[String],
        manualForeignKeys: Map[(String, String), (String, String)],
        typeReplacements: Map[String, String]
) extends TypedIdSourceCodeGenerator(
      singleSchemaModel = model,
      databaseModel = fullDatabaseModel,
      idType,
      manualForeignKeys
    ) with RowOutputHelpers {

  override def Table = new TypedIdTable(_) { table =>

    override def Column = new TypedIdColumn(_) {
      override def rawType: String = {
        typeReplacements.getOrElse(model.tpe, super.rawType)
      }
    }

    override def EntityType = new EntityType {
      override def code: String =
        (if (classEnabled) "final " else "") + super.code
    }

    override def code = Seq[Def](EntityType).map(_.docWithCode)
  }

  override def code = tables.map(_.code.mkString("\n")).mkString("\n\n")
}

class TableSourceCodeGenerator(schemaOnlyModel: m.Model,
                               override val headerComment: String,
                               override val imports: String,
                               override val schemaName: String,
                               fullDatabaseModel: m.Model,
                               pkg: String,
                               manualForeignKeys: Map[(String, String), (String, String)],
                               override val parentType: Option[String],
                               idType: Option[String],
                               typeReplacements: Map[String, String])
    extends TypedIdSourceCodeGenerator(singleSchemaModel = schemaOnlyModel,
                                       databaseModel = fullDatabaseModel,
                                       idType,
                                       manualForeignKeys) with TableOutputHelpers {

  val defaultIdImplementation =
    """|final case class Id[T](v: Int)
       |trait DefaultIdTypeMapper {
       |  val profile: slick.jdbc.JdbcProfile
       |  import profile.api._
       |  implicit def idTypeMapper[A]: BaseColumnType[Id[A]] = MappedColumnType.base[Id[A], Int](_.v, Id(_))
       |}
       |""".stripMargin

  override def code = {
    // Drops needless import: `"import slick.model.ForeignKeyAction\n"`.
    // Alias to ForeignKeyAction is in profile.api
    // TODO: fix upstream
    val tableCode = super.code.lines.drop(1).mkString("\n")

    val tripleQuote = "\"\"\""
    val namespaceDDL =
      s"""|val createNamespaceSchema = {
       |  implicit val GRUnit = slick.jdbc.GetResult(_ => ())
       |  sql${tripleQuote}CREATE SCHEMA IF NOT EXISTS "$schemaName";${tripleQuote}.as[Unit]
       |}
       |
       |val dropNamespaceSchema = {
       |  implicit val GRUnit = slick.jdbc.GetResult(_ => ())
       |  sql${tripleQuote}DROP SCHEMA "$schemaName" CASCADE;${tripleQuote}.as[Unit]
       |} """

    tableCode + "\n\n" + namespaceDDL
  }

  override def Table = new this.TypedIdTable(_) { table =>
    override def TableClass = new TableClass() {
      // We disable the option mapping, as it is a bit more complex to support and we don't appear to need it
      override def optionEnabled = false
    }

    // use hlists all the time
    override def hlistEnabled: Boolean = true

    // if false rows are type aliases to hlists, if true rows are case classes
    override def mappingEnabled: Boolean = true

    // create case class from colums
    override def factory: String =
      if (!hlistEnabled) super.factory
      else {
        val args  = columns.zipWithIndex.map("a" + _._2)
        val hlist = args.mkString("::") + ":: HNil"
        val hlistType = columns
          .map(_.actualType)
          .mkString("::") + ":: HNil.type"
        s"((h : $hlistType) => h match {case $hlist => ${TableClass.elementType}(${args.mkString(",")})})"
      }

    // from case class create columns
    override def extractor: String =
      if (!hlistEnabled) {
        super.extractor
      } else {
        s"(a : ${TableClass.elementType}) => Some(" + columns
          .map("a." + _.name)
          .mkString("::") + ":: HNil)"
      }

    override def EntityType = new EntityType {
      override def enabled = false
    }

    override def Column = new TypedIdColumn(_) {
      override def rawType: String = {
        typeReplacements.getOrElse(model.tpe, super.rawType)
      }
    }

    override def ForeignKey = new ForeignKey(_) {
      override def code = {
        val fkColumns = compoundValue(referencingColumns.map(_.name))
        val qualifier =
          if (referencedTable.model.name.schema == referencingTable.model.name.schema) {
            ""
          } else {
            referencedTable.model.name.schema.fold("")(sname => s"$pkg.$sname.")
          }

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
