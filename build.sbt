// sbtPlugin := true

val scalaVersionValue = "2.11.8"

scalaVersion := scalaVersionValue

libraryDependencies ++= Seq(
  "com.typesafe.slick" %% "slick" % "3.1.1",
  "com.typesafe.slick" %% "slick-codegen" % "3.1.1",
  "org.scala-lang" % "scala-reflect" % scalaVersionValue,
  "org.postgresql" % "postgresql" % "9.3-1102-jdbc41"
)
