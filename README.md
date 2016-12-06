# _sbt_ plugin for slick schema code generation

Extends/customizes the
[Slick schema code generator](http://slick.lightbend.com/doc/3.1.1/code-generation.html) and wraps it in a parameterized plugin. See [original source code here](https://github.com/slick/slick/tree/master/slick-codegen/src/main/scala/slick/codegen).

## TL;DR

### project/plugins.sbt

```sbt
resolvers += "releases" at "https://drivergrp.jfrog.io/drivergrp/releases"
credentials += Credentials("Artifactory Realm", "drivergrp.jfrog.io", "sbt-publisher", "***REMOVED***")

addSbtPlugin("xyz.driver" % "sbt-slick-codegen" % "0.8")

// Replace with the appropriate jdbc driver for your database:
libraryDependencies += "org.postgresql" % "postgresql" % "9.4.1212"
```

### build.sbt

Minimally, define `codegenUri`, `codegenPackage`, and `codegenOutputPath` like so:


```sbt
enablePlugins(CodegenPlugin)

codegenURI := "file:src/main/resources/conf/database.conf#database"

codegenPackage := "xyz.driver.schemas"

codegenOutputPath := (baseDirectory.value / "src" / "main" / "scala").getPath
```

Use `settings -V codegen` to view documentation for all available codegen settings.
