/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import sbt._
import Keys._
import scala.xml.{Node, Elem, NodeSeq}
import scala.xml.transform.{RewriteRule, RuleTransformer}


object Dependencies {
  // Core
  val log4j = "log4j" % "log4j" % "1.2.15" 
  val jopt = "net.sf.jopt-simple" % "jopt-simple" % "3.2"
  val coreDeps = Seq(log4j, jopt)

  // Hadoop
  val avro = "org.apache.avro" % "avro" % "1.4.0"
  val commonsLogging = "commons-logging" % "commons-logging" % "1.0.4"
  val jacksonCore = "org.codehaus.jackson" % "jackson-core-asl" % "1.5.5"
  val jacksonMapper = "org.codehaus.jackson" % "jackson-mapper-asl" % "1.5.5"
  val hadoop = "org.apache.hadoop" % "hadoop-core" % "0.20.2" excludeAll(
    ExclusionRule(name="junit")
  )
  val hadoopDeps = Seq(avro, commonsLogging, jacksonCore, jacksonMapper, hadoop)
  
  // Compression
  val snappy = "org.xerial.snappy" % "snappy-java" % "1.0.4.1"	
  val compressionDeps = Seq(snappy)

  // ZooKeeper
  val zookeeper = "org.apache.zookeeper" % "zookeeper" % "3.3.4" excludeAll(
    ExclusionRule(organization = "log4j"),
    ExclusionRule(organization = "jline")
  )
  val zkClient = "com.github.sgroschupf" % "zkclient" % "0.1" intransitive()
  val zkDeps = Seq(zookeeper, zkClient)

  // Test
  val easymock = "org.easymock" % "easymock" % "3.0" % "test"
  val junit = "junit" % "junit" % "4.1" % "test"
  val scalaTest = "org.scalatest" % "scalatest" % "1.2" % "test"
  val testDeps = Seq(easymock, junit, scalaTest) 

  // Apache Rat
  val apacheRat = "org.apache.rat" % "apache-rat" % "0.8" intransitive()

  // Joda Time
  val jodaTime = "joda-time" % "joda-time" % "1.6"
}

object KafkaBuild extends Build {
  import Dependencies._

  lazy val root = Project(
    id = "root", 
    base = file("."),
    settings = standardSettings ++ Seq(
      name := "kafka", 

      libraryDependencies += apacheRat,
      
      publishArtifact in (Compile, packageBin) := false,

      runRat
    )
  ) aggregate(core, examples, contrib, perf)

  lazy val core: Project = Project(
    id = "core-kafka", 
    base = file("core"), 
    settings = standardSettings ++ ivyExclusions ++ Seq(
      name := "kafka",

      libraryDependencies ++= coreDeps,
      libraryDependencies ++= testDeps,
      libraryDependencies ++= compressionDeps,
      libraryDependencies ++= zkDeps,

      // e.g. "kafka-0.7.jar"
      artifactName := { (config: String, module: ModuleID, artifact: Artifact) =>
        artifact.name + "-" + module.revision + "." + artifact.extension
      },

      javacOptions ++= Seq("-source", "1.5")
    )
  )

  lazy val examples: Project = Project(
    id = "java-examples",
    base = file("examples"),
    settings = standardSettings ++ ivyExclusions ++ Seq(
      name := "kafka-java-examples",

      libraryDependencies ++= coreDeps,

      // e.g. "kafka-java-examples-0.7.jar"
      artifactName := { (config: String, module: ModuleID, artifact: Artifact) =>
        artifact.name + "-" + module.revision + "." + artifact.extension
      },

      javacOptions += "-Xlint:unchecked"
    )
  ) dependsOn(core)

  lazy val perf: Project = Project(
    id = "perf",
    base = file("perf"),
    settings = standardSettings ++ ivyExclusions ++ Seq(
      name := "kafka-perf",

      libraryDependencies ++= coreDeps,

      // e.g. "kafka-perf-0.7.jar"
      artifactName := { (config: String, module: ModuleID, artifact: Artifact) =>
        artifact.name + "-" + module.revision + "." + artifact.extension
      },

      javacOptions += "-Xlint:unchecked"
    )
  ) dependsOn(core)

  lazy val contrib: Project = Project(
    id = "contrib",
    base = file("contrib"),
    settings = standardSettings ++ Seq(
      publishArtifact in (Compile, packageBin) := false
    )
  ) aggregate (hadoopProducer, hadoopConsumer)

  lazy val hadoopProducer: Project = Project(
    id = "hadoop-producer",
    base = file("contrib/hadoop-producer"),
    settings = standardSettings ++ ivyExclusions ++ Seq(
      libraryDependencies ++= coreDeps,
      libraryDependencies ++= hadoopDeps
    )
  ) dependsOn(core)

  lazy val hadoopConsumer: Project = Project(
    id = "hadoop-consumer",
    base = file("contrib/hadoop-consumer"),
    settings = standardSettings ++ ivyExclusions ++ Seq(
      libraryDependencies ++= coreDeps,
      libraryDependencies ++= hadoopDeps,
      libraryDependencies += jodaTime
    ) 
  ) dependsOn(core)

  lazy val standardSettings = Defaults.defaultSettings ++ Seq(
    version := "0.7",

    scalaVersion := "2.8.0"
  )

  lazy val ivyExclusions = Seq(
    ivyXML := 
      <dependencies>
        <exclude module="javax"/>
        <exclude module="jmxri"/>
        <exclude module="jmxtools"/>
        <exclude module="mail"/>
        <exclude module="jms"/>
      </dependencies>
  )

  val runRatKey = TaskKey[Unit]("rat", "Runs Apache rat on Kafka")
     
  def runRat = runRatKey <<= managedClasspath.in(Compile) map { cp =>
    // full path to apache-rat-0.8.jar
    val ratJar = cp.collectFirst { 
      case attr if attr.data.name.startsWith("apache-rat-") => attr.data.toString
    } getOrElse ""

    // pass the jar's path to the shell script
    ("bin/run-rat.sh %s".format(ratJar)) !
  }

//  lazy val contrib = project("contrib", "contrib", new ContribProject(_))
//  lazy val perf = project("perf", "perf", new KafkaPerfProject(_))
//
//  lazy val releaseZipTask = core.packageDistTask
//
//  val releaseZipDescription = "Compiles every sub project, runs unit tests, creates a deployable release zip file with dependencies, config, and scripts."
//  lazy val releaseZip = releaseZipTask dependsOn(core.corePackageAction, core.test, examples.examplesPackageAction,
//    contrib.producerPackageAction, contrib.consumerPackageAction) describedAs releaseZipDescription
//
//  val runRatDescription = "Runs Apache rat on Kafka"
//  lazy val runRatTask = task {
//    Runtime.getRuntime().exec("bin/run-rat.sh")
//    None
//  } describedAs runRatDescription
//
//  val rat = "org.apache.rat" % "apache-rat" % "0.8"
//
//  class CoreKafkaProject(info: ProjectInfo) extends DefaultProject(info)
//     with IdeaProject with CoreDependencies with TestDependencies with CompressionDependencies {
//   val corePackageAction = packageAllAction
//
//  //The issue is going from log4j 1.2.14 to 1.2.15, the developers added some features which required
//  // some dependencies on various sun and javax packages.
//   override def ivyXML =
//    <dependencies>
//      <exclude module="javax"/>
//      <exclude module="jmxri"/>
//      <exclude module="jmxtools"/>
//      <exclude module="mail"/>
//      <exclude module="jms"/>
//      <dependency org="org.apache.zookeeper" name="zookeeper" rev="3.3.4">
//        <exclude module="log4j"/>
//        <exclude module="jline"/>
//      </dependency>
//      <dependency org="com.github.sgroschupf" name="zkclient" rev="0.1">
//      </dependency>
//    </dependencies>
//
//    override def artifactID = "kafka"
//    override def filterScalaJars = false
//
//    // build the executable jar's classpath.
//    // (why is it necessary to explicitly remove the target/{classes,resources} paths? hm.)
//    def dependentJars = {
//      val jars =
//      publicClasspath +++ mainDependencies.scalaJars --- mainCompilePath --- mainResourcesOutputPath
//      if (jars.get.find { jar => jar.name.startsWith("scala-library-") }.isDefined) {
//        // workaround bug in sbt: if the compiler is explicitly included, don't include 2 versions
//        // of the library.
//        jars --- jars.filter { jar =>
//          jar.absolutePath.contains("/boot/") && jar.name == "scala-library.jar"
//        }
//      } else {
//        jars
//      }
//    }
//
//    def dependentJarNames = dependentJars.getFiles.map(_.getName).filter(_.endsWith(".jar"))
//    override def manifestClassPath = Some(dependentJarNames.map { "libs/" + _ }.mkString(" "))
//
//    def distName = (artifactID + "-" + projectVersion.value)
//    def distPath = "dist" / distName ##
//
//    def configPath = "config" ##
//    def configOutputPath = distPath / "config"
//
//    def binPath = "bin" ##
//    def binOutputPath = distPath / "bin"
//
//    def distZipName = {
//      "%s-%s.zip".format(artifactID, projectVersion.value)
//    }
//
//    lazy val packageDistTask = task {
//      distPath.asFile.mkdirs()
//      (distPath / "libs").asFile.mkdirs()
//      binOutputPath.asFile.mkdirs()
//      configOutputPath.asFile.mkdirs()
//
//      FileUtilities.copyFlat(List(jarPath), distPath, log).left.toOption orElse
//              FileUtilities.copyFlat(dependentJars.get, distPath / "libs", log).left.toOption orElse
//              FileUtilities.copy((configPath ***).get, configOutputPath, log).left.toOption orElse
//              FileUtilities.copy((binPath ***).get, binOutputPath, log).left.toOption orElse
//              FileUtilities.zip((("dist" / distName) ##).get, "dist" / distZipName, true, log)
//      None
//    }
//
//    val PackageDistDescription = "Creates a deployable zip file with dependencies, config, and scripts."
//    lazy val packageDist = packageDistTask dependsOn(`package`, `test`) describedAs PackageDistDescription
//
//    val cleanDist = cleanTask("dist" ##) describedAs("Erase any packaged distributions.")
//    override def cleanAction = super.cleanAction dependsOn(cleanDist)
//
//    override def javaCompileOptions = super.javaCompileOptions ++
//      List(JavaCompileOption("-source"), JavaCompileOption("1.5"))
//
//    override def packageAction = super.packageAction dependsOn (testCompileAction)
//
//  }
//
//  class KafkaPerfProject(info: ProjectInfo) extends DefaultProject(info)
//     with IdeaProject
//     with CoreDependencies {
//    val perfPackageAction = packageAllAction
//    val dependsOnCore = core
//
//  //The issue is going from log4j 1.2.14 to 1.2.15, the developers added some features which required
//  // some dependencies on various sun and javax packages.
//   override def ivyXML =
//    <dependencies>
//      <exclude module="javax"/>
//      <exclude module="jmxri"/>
//      <exclude module="jmxtools"/>
//      <exclude module="mail"/>
//      <exclude module="jms"/>
//    </dependencies>
//
//    override def artifactID = "kafka-perf"
//    override def filterScalaJars = false
//    override def javaCompileOptions = super.javaCompileOptions ++
//      List(JavaCompileOption("-Xlint:unchecked"))
//  }
//
//  class KafkaExamplesProject(info: ProjectInfo) extends DefaultProject(info)
//     with IdeaProject
//     with CoreDependencies {
//    val examplesPackageAction = packageAllAction
//    val dependsOnCore = core
//  //The issue is going from log4j 1.2.14 to 1.2.15, the developers added some features which required
//  // some dependencies on various sun and javax packages.
//   override def ivyXML =
//    <dependencies>
//      <exclude module="javax"/>
//      <exclude module="jmxri"/>
//      <exclude module="jmxtools"/>
//      <exclude module="mail"/>
//      <exclude module="jms"/>
//    </dependencies>
//
//    override def artifactID = "kafka-java-examples"
//    override def filterScalaJars = false
//    override def javaCompileOptions = super.javaCompileOptions ++
//      List(JavaCompileOption("-Xlint:unchecked"))
//  }
//
//  class ContribProject(info: ProjectInfo) extends ParentProject(info) with IdeaProject {
//    lazy val hadoopProducer = project("hadoop-producer", "hadoop producer",
//                                      new HadoopProducerProject(_), core)
//    lazy val hadoopConsumer = project("hadoop-consumer", "hadoop consumer",
//                                      new HadoopConsumerProject(_), core)
//
//    val producerPackageAction = hadoopProducer.producerPackageAction
//    val consumerPackageAction = hadoopConsumer.consumerPackageAction
//
//    class HadoopProducerProject(info: ProjectInfo) extends DefaultProject(info)
//      with IdeaProject
//      with CoreDependencies with HadoopDependencies {
//      val producerPackageAction = packageAllAction
//      override def ivyXML =
//       <dependencies>
//         <exclude module="netty"/>
//           <exclude module="javax"/>
//           <exclude module="jmxri"/>
//           <exclude module="jmxtools"/>
//           <exclude module="mail"/>
//           <exclude module="jms"/>
//         <dependency org="org.apache.hadoop" name="hadoop-core" rev="0.20.2">
//           <exclude module="junit"/>
//         </dependency>
//       </dependencies>
//
//    }
//
//    class HadoopConsumerProject(info: ProjectInfo) extends DefaultProject(info)
//      with IdeaProject
//      with CoreDependencies {
//      val consumerPackageAction = packageAllAction
//      override def ivyXML =
//       <dependencies>
//         <exclude module="netty"/>
//           <exclude module="javax"/>
//           <exclude module="jmxri"/>
//           <exclude module="jmxtools"/>
//           <exclude module="mail"/>
//           <exclude module="jms"/>
//           <exclude module=""/>
//         <dependency org="org.apache.hadoop" name="hadoop-core" rev="0.20.2">
//           <exclude module="junit"/>
//         </dependency>
//       </dependencies>
//
//      val jodaTime = "joda-time" % "joda-time" % "1.6"
//    }
//  }
}