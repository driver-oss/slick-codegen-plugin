sbtPlugin := true

organization := "com.drivergrp"

name := "sbt-slick-codegen"

version := "0.1-SNAPSHOT"

val scalaVersionValue = "2.10.6"

scalaVersion := scalaVersionValue

val slickVersionValue = "3.1.1"

libraryDependencies ++= Seq(
  "com.typesafe.slick" %% "slick" % slickVersionValue,
  "com.typesafe.slick" %% "slick-codegen" % slickVersionValue,
  "com.typesafe.slick" %% "slick-hikaricp" % slickVersionValue,
  "org.scala-lang" % "scala-reflect" % scalaVersionValue
)
