import com.typesafe.sbt.pgp.PgpKeys
import sbt._
import sbt.Keys._
import xerial.sbt.Pack._

object Build extends Build {

  val org = "io.eels"

  val ScalaVersion = "2.11.7"
  val ScalatestVersion = "3.0.0-M12"
  val Slf4jVersion = "1.7.5"
  val Log4jVersion = "1.2.17"
  val HadoopVersion = "2.6.1"
  val HiveVersion = "1.1.0"
  val Avro4sVersion = "1.2.2"

  val rootSettings = Seq(
    organization := org,
    scalaVersion := ScalaVersion,
    crossScalaVersions := Seq(ScalaVersion, "2.10.6"),
    publishMavenStyle := true,
    resolvers += Resolver.mavenLocal,
    resolvers += "conjars" at "http://conjars.org/repo/",
    publishArtifact in Test := false,
    parallelExecution in Test := false,
    scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8"),
    javacOptions := Seq("-source", "1.7", "-target", "1.7"),
    sbtrelease.ReleasePlugin.autoImport.releasePublishArtifactsAction := PgpKeys.publishSigned.value,
    sbtrelease.ReleasePlugin.autoImport.releaseCrossBuild := true,
    libraryDependencies ++= Seq(
      "com.github.tototoshi"        %% "scala-csv"            % "1.3.0",
      "com.univocity"               % "univocity-parsers"     % "2.0.0",
      "org.scala-lang"              % "scala-reflect"         % scalaVersion.value,
      "com.sksamuel.scalax"         %% "scalax"               % "1.24.1",
      "com.typesafe"                % "config"                % "1.2.1",
      "org.apache.hadoop"           % "hadoop-common"         % HadoopVersion          % "provided",
      "org.apache.hadoop"           % "hadoop-hdfs"           % HadoopVersion          % "provided",
      "com.typesafe.scala-logging"  %% "scala-logging-slf4j"  % "2.1.2",
      "com.google.guava"            % "guava"                 % "18.0",
      "com.h2database"              % "h2"                    % "1.4.191",
      "org.scalatest"               %% "scalatest"            % ScalatestVersion  % "test",
      "org.slf4j"                   % "slf4j-log4j12"         % Slf4jVersion      % "test",
      "log4j"                       % "log4j"                 % Log4jVersion      % "test"
    ),
    publishTo <<= version {
      (v: String) =>
        val nexus = "https://oss.sonatype.org/"
        if (v.trim.endsWith("SNAPSHOT"))
          Some("snapshots" at nexus + "content/repositories/snapshots")
        else
          Some("releases" at nexus + "service/local/staging/deploy/maven2")
    },
    pomExtra := {
      <url>https://github.com/eel-sdk/eel</url>
        <licenses>
          <license>
            <name>MIT</name>
            <url>https://opensource.org/licenses/MIT</url>
            <distribution>repo</distribution>
          </license>
        </licenses>
        <scm>
          <url>git@github.com:eel-sdk/eel.git</url>
          <connection>scm:git@github.com:eel-sdk/eel.git</connection>
        </scm>
        <developers>
          <developer>
            <id>sksamuel</id>
            <name>sksamuel</name>
            <url>http://github.com/sksamuel</url>
          </developer>
        </developers>
    }
  )

  lazy val root = Project("eel", file("."))
    .settings(rootSettings: _*)
    .settings(publish := {})
    .settings(publishArtifact := false)
    .settings(name := "eel")
    .aggregate(core, kafka, mongo, solr, cli, testkit, tests)

  lazy val core = Project("eel-core", file("eel-core"))
    .settings(rootSettings: _*)
    .settings(libraryDependencies ++= Seq(
      "io.dropwizard.metrics" %  "metrics-core"     % "3.1.2",
      "io.dropwizard.metrics" %  "metrics-jvm"      % "3.1.2",
      "org.apache.parquet"    % "parquet-avro"      % "1.8.1",
      "org.apache.hadoop"     % "hadoop-common"     % HadoopVersion,
      "org.apache.hadoop"     % "hadoop-client"     % HadoopVersion,
      "org.apache.hadoop"     % "hadoop-hdfs"       % HadoopVersion,
      "org.apache.hadoop"     % "hadoop-mapreduce"  % HadoopVersion,
      "org.apache.hadoop"     % "hadoop-mapreduce-client" % HadoopVersion,
      "org.apache.hive"       % "hive-common"       % HiveVersion,
      "org.apache.hive"       % "hive-exec"         % HiveVersion exclude("org.pentaho", "pentaho-aggdesigner-algorithm"),
      "com.fasterxml.jackson.core"      %  "jackson-databind"           % "2.7.2",
      "com.fasterxml.jackson.module"    %% "jackson-module-scala"       % "2.7.2",
      "mysql"                           % "mysql-connector-java"        % "5.1.38"
    ))
    .settings(name := "eel-core")

  lazy val kafka = Project("eel-kafka", file("components/kafka"))
    .settings(rootSettings: _*)
    .settings(name := "eel-kafka")
    .settings(libraryDependencies ++= Seq(
      "org.apache.kafka"              %  "kafka-clients"        % "0.9.0.0",
      "com.sksamuel.kafka.embedded"   %% "embedded-kafka"       % "0.21.0",
      "com.fasterxml.jackson.core"    % "jackson-databind"      % "2.7.0"
    ))
    .dependsOn(core)

  lazy val mongo = Project("eel-mongo", file("components/mongo"))
    .settings(rootSettings: _*)
    .settings(name := "eel-mongo")
    .settings(libraryDependencies ++= Seq(
       "org.mongodb" % "mongo-java-driver" % "3.2.2"
    ))
    .dependsOn(core)

  lazy val jms = Project("eel-jms", file("components/jms"))
    .settings(rootSettings: _*)
    .settings(name := "eel-jms")
    .settings(libraryDependencies ++= Seq(
      "javax.jms" % "jms" % "1.1",
      "org.apache.activemq" % "activemq-broker"       % "5.13.1" % "test",
      "org.apache.activemq" % "activemq-kahadb-store" % "5.13.1" % "test"
    ))
    .dependsOn(core)

  lazy val solr = Project("eel-solr", file("components/solr"))
    .settings(rootSettings: _*)
    .settings(name := "eel-solr")
    .settings(libraryDependencies ++= Seq(
      "org.apache.solr" % "solr-solrj" % "5.4.1"
    ))
    .dependsOn(core)

  lazy val elasticsearch = Project("eel-elasticsearch", file("components/elasticsearch"))
    .settings(rootSettings: _*)
    .settings(name := "eel-elasticsearch")
    .settings(libraryDependencies ++= Seq(
      "com.sksamuel.elastic4s"    %% "elastic4s-core"     % "2.1.1",
      "org.json4s"                %% "json4s-native"      % "3.3.0",
      "org.elasticsearch"         % "elasticsearch"       % "2.1.1"     % "test"
    ))
    .dependsOn(core)

  lazy val testkit = Project("eel-testkit", file("eel-testkit"))
    .settings(rootSettings: _*)
    .settings(name := "eel-testkit")
    .settings(libraryDependencies ++= Seq(
    ))
    .dependsOn(core)

  lazy val tests = Project("eel-tests", file("eel-tests"))
    .settings(rootSettings: _*)
    .settings(name := "eel-tests")
    .settings(libraryDependencies ++= Seq(
    ))
    .dependsOn(core, testkit)

  lazy val cli = Project("eel-cli", file("eel-cli"))
    .settings(rootSettings: _*)
    .settings(packAutoSettings: _*)
    .settings(
      name := "eel-cli",
      packMain := Map("eel" -> "io.eels.cli.Main"),
      packExtraClasspath := Map("eel" -> Seq("${HADOOP_HOME}/etc/hadoop", "${HIVE_HOME}/conf")),
      packGenerateWindowsBatFile := true,
      packJarNameConvention := "default"
    )
    .settings(libraryDependencies ++= Seq(
      "com.github.scopt" %% "scopt" % "3.3.0"
    ))
    .dependsOn(core)
}
