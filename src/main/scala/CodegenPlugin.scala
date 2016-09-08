import sbt._
import sbt.Keys._
import complete.DefaultParsers._

object CodegenPlugin extends AutoPlugin {
  override def requires = sbt.plugins.JvmPlugin
  object autoImport {
    lazy val genTables = TaskKey[Seq[File]]("gen-tables")
    lazy val slickCodeGenTask = (baseDirectory, //sourceManaged in Compile,
      dependencyClasspath in Compile,
      runner in Compile, streams) map {
      (dir, cp, r, s) =>
      // TODO Move this block into application.conf#slick.db.default.codegen
      val pkg = "dbmodels"
      val outputDir = (dir / "app" / pkg).getPath
      val fname = outputDir + "/Tables.scala"
      val typesfname = (file("shared") / "src" / "main" / "scala" / pkg / "rows" / "TableTypes.scala").getPath
      val schemas = "patients,portal,work_queues,confidential,case_accessioning,samples.samples,samples.subsamples,samples.shipment_preps,samples.collection_methods,experiments.experiments,experiments.exp_types,experiments.somatic_snvs_indels_filtered,samples.basic_diagnosis,samples.molecular_tests,samples.sample_pathology,samples.path_molecular_tests"

      val uri = new java.net.URI("file:src/main/resources/application.conf#slick.db.default")

      codegen.NamespacedCodegen.run(uri, Some(outputDir), fname, typesfname, schemas)

      Seq(file(fname))
    }
  }
}
