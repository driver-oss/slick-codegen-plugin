sbtPlugin := true

organization := "com.drivergrp"

name := "sbt-slick-codegen"

publishTo := {
  val jfrog = "https://drivergrp.jfrog.io/drivergrp/"
  if (isSnapshot.value) Some("snapshots" at jfrog + "snapshots")
  else Some("releases" at jfrog + "releases")
}

credentials += Credentials("Artifactory Realm", "drivergrp.jfrog.io", "sbt-publisher", "***REMOVED***")

val scalaVersionValue = "2.10.6"

scalaVersion := scalaVersionValue

val slickVersionValue = "3.1.1"

libraryDependencies ++= Seq(
  "com.typesafe.slick" %% "slick" % slickVersionValue,
  "com.typesafe.slick" %% "slick-codegen" % slickVersionValue,
  "com.typesafe.slick" %% "slick-hikaricp" % slickVersionValue,
  "org.scala-lang" % "scala-reflect" % scalaVersionValue
)
