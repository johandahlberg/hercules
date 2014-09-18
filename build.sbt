name := """hercules"""

version := "1.0"

scalaVersion := "2.10.2"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-contrib" % "2.3.3",
  "com.typesafe.akka" %% "akka-testkit" % "2.3.3",
  "org.scalatest" %% "scalatest" % "2.1.6" % "test",
  "com.github.scopt" %% "scopt" % "3.2.0",
  "commons-io" % "commons-io" % "2.4",
  "me.lessis" %% "courier" % "0.1.3"
)

instrumentSettings

ScoverageKeys.highlighting := true

parallelExecution in Test := false

parallelExecution in ScoverageTest := false

resolvers += Resolver.sonatypeRepo("public")

resolvers += "softprops-maven" at "http://dl.bintray.com/content/softprops/maven"
