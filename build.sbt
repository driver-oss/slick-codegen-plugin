sbtPlugin := true

organization := "com.drivergrp"

name := "slick-codegen-plugin"

version := "0.1"

val scalaVersionValue = "2.10.6"

scalaVersion := scalaVersionValue

libraryDependencies ++= Seq(
  "com.typesafe.slick" %% "slick" % "3.1.1",
  "com.typesafe.slick" %% "slick-codegen" % "3.1.1",
  "org.postgresql" % "postgresql" % "9.3-1102-jdbc41",
  "org.scala-lang" % "scala-reflect" % scalaVersionValue
)
