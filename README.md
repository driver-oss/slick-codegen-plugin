# _sbt_ plugin for slick schema code generation

Extends/customizes the
[Slick schema code generator](http://slick.lightbend.com/doc/3.1.1/code-generation.html) and wraps it in a parameterized plugin. See [original source code here](https://github.com/slick/slick/tree/master/slick-codegen/src/main/scala/slick/codegen).

## Configuration

### project/plugins.sbt

```sbt
addSbtPlugin("xyz.driver" % "sbt-slick-codegen" % "0.10.2")

// Replace with the appropriate jdbc driver for your database:
libraryDependencies += "org.postgresql" % "postgresql" % "9.3-1104-jdbc41"
```

The jdbc-driver needs to be added separately to `plugins.sbt` and `build.sbt` as a dependency for the project and codegen, respectively.

Version 0.10.x requires sbt 1.0. For sbt 0.13, use 0.9.8

(also note that postgres jdbc driver `9.4.x` will not expose columns in certain metadata "tables", causing generated code not to compile)

### build.sbt

Minimally, define `codegenURI`, `codegenPackage`, and `codegenOutputPath` like so:


```sbt
enablePlugins(CodegenPlugin)

codegenURI := "file:src/main/resources/conf/database.conf#database"

codegenPackage := "xyz.driver.schemas"

codegenOutputPath := (baseDirectory.value / "src" / "main" / "scala").getPath
```

Use `settings -V codegen` to view documentation for all available codegen settings.

## Using

### Local dev/testing instance with sqitch and docker

Code should be committed locally (rather than being placed in a sourceMangaged directory and being generated as part of build), so that it can be read, reviewed, and debugged. This requires having a locally accessible database running from which to generate code. Configure such a dabase like so:

0. Install docker.

1. Start a vanilla docker instance

```
docker run -it -d -p 5432:5432 -e POSTGRES_USER=postgres -e POSTGRES_DB=postgres -e POSTGRES_PASSWORD=postgres postgres
```

add `127.0.0.1 postgres postgres` to `/etc/hosts` to make it accessible locally as "postgres".

2. Configure database in typesafe config

If sqitch is already configured, modify step 1 to make docker instance look like the appropriate database in `application.conf`. For the above docker postgres instance, the following configuration will work:

```
database {
  driver = "slick.driver.PostgresDriver$"
  db {
    url = "jdbc:postgresql://postgres:5432/postgres"
    driver = org.postgresql.Driver
    user = "postgres"
    password = "postgres"

    # other options as appropriate
  }
}
```

In `build.sbt` make sure `codegenURI := "typesafeConfig#database"` where `typesafeConfig` is the url of the typesafe config and `database` is the name of the database configuration in that file to use.

3. Run codegen with `sbt genTables`

Rememeber to run migrations first! If you just added a new schema/table in a migration, be sure to update `codegenSchemaWhitelist`. Other than automated code formatting, the output source shouldn't be modified, since `genTables` should be run every time the schema is updated and will overwrite any manual changes.
