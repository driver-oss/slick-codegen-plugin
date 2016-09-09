import sbt._
import sbt.Keys._
import complete.DefaultParsers._

object CodegenPlugin extends AutoPlugin {
  override def requires = sbt.plugins.JvmPlugin
  object autoImport {
    lazy val genTables = TaskKey[Seq[File]]("gen-tables")
    lazy val dbConfigURI = SettingKey[String]("uri for the database configuration")
    lazy val pkg = SettingKey[String]("package in which to place generated code")
    lazy val tablesFilename = SettingKey[String]("path for slick table models")
    lazy val rowsFilename = SettingKey[String]("path for row case classes")

    lazy val slickCodeGenTask = Def.task {
      val schemas = "patients,portal,work_queues,confidential,case_accessioning,samples.samples,samples.subsamples,samples.shipment_preps,samples.collection_methods,experiments.experiments,experiments.exp_types,experiments.somatic_snvs_indels_filtered,samples.basic_diagnosis,samples.molecular_tests,samples.sample_pathology,samples.path_molecular_tests"

      val uri = new java.net.URI(dbConfigURI.value)

      codegen.NamespacedCodegen.run(uri, pkg.value, tablesFilename.value, rowsFilename.value, schemas)

      Seq(file(tablesFilename.value), file(rowsFilename.value))
    }
  }
}
