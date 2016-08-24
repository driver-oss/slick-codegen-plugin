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
      val url = "jdbc:postgresql://postgres/ctig"
      val jdbcDriver = "org.postgresql.Driver"
      val slickDriver = "slick.driver.PostgresDriver"
      val pkg = "dbmodels"
      val outputDir = (dir / "app" / pkg).getPath
      val fname = outputDir + "/Tables.scala"
      // TODO: typesfname should be a parameter
      val typesfname = (file("shared") / "src" / "main" / "scala" / pkg / "rows" / "TableTypes.scala").getPath
      val schemas = "patients,portal,work_queues,confidential,case_accessioning,samples.samples,samples.subsamples,samples.shipment_preps,samples.collection_methods,experiments.experiments,experiments.exp_types,experiments.somatic_snvs_indels_filtered,samples.basic_diagnosis,samples.molecular_tests,samples.sample_pathology,samples.path_molecular_tests"
      val user = "ctig_portal"
      val password = "coolnurseconspiracyhandbook"
      codegen.NamespacedCodegen.main(
        Array( slickDriver, jdbcDriver, url, pkg, schemas, fname, typesfname, user, password))
      Seq(file(fname))
    }
  }
}
