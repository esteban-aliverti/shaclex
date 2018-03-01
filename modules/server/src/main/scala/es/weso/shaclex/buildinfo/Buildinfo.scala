package es.weso.shaclex.buildinfo

import scala.Predef._

/** This object is written by hand but should be generated by sbt-buildinfo once it works for Scala 2.12 */
 
case object BuildInfo {
  val name: String = "shaclex"
  val version: String = "0.0.66"
  val scalaVersion: String = "2.12.3"
  val sbtVersion: String = "1.0.3"
  override val toString: String = "name: %s, version: %s, scalaVersion: %s, sbtVersion: %s" format (name, version, scalaVersion, sbtVersion)
}