package es.weso.shaclex

import org.rogach.scallop._
import org.rogach.scallop.exceptions._
import com.typesafe.scalalogging._
import es.weso.rdf.PrefixMap
import es.weso.rdf.jena.Endpoint
import es.weso.rdf.nodes.IRI
import es.weso.schemaInfer.SchemaInfer
import es.weso.shapeMaps.NodeSelector

import scala.io.Source
// import es.weso.server._
import es.weso.schema._
import es.weso.rdf.jena.RDFAsJenaModel
import scala.concurrent.duration._
import es.weso.utils.FileUtils
import scala.util._
import java.nio.file._
import es.weso.rdf.RDFReader

object Main extends App with LazyLogging {
    try {
      run(args)
    } catch {
      case (e: Exception) => {
        println(s"Error: ${e.getMessage}")
      }
    }

  private def run(args: Array[String]): Unit = {
    val opts = new MainOpts(args, errorDriver)
    opts.verify()

    val baseFolder: Path = if (opts.baseFolder.isDefined) {
      Paths.get(opts.baseFolder())
    } else {
      Paths.get(".")
    }

    val startTime = System.nanoTime()

    val base = Some(FileUtils.currentFolderURL)

    if (opts.shapeInfer.isDefined) {
      shapeInfer(opts,baseFolder)
    }

    val validateOptions: Either[String, (RDFReader, Schema, ValidationTrigger)] = for {
      rdf <- getRDFReader(opts, baseFolder)
      schema <- getSchema(opts, baseFolder, rdf)
      triggerName = opts.trigger.toOption.getOrElse(ValidationTrigger.default.name)
      shapeMapStr <- getShapeMapStr(opts)
      trigger <- ValidationTrigger.findTrigger(triggerName, shapeMapStr, base,
        opts.node.toOption, opts.shapeLabel.toOption,
        rdf.getPrefixMap(), schema.pm)
    } yield (rdf, schema, trigger)

    validateOptions match {
      case Left(e) => {
        println(s"Error: $e")
      }
      case Right((rdf, schema, trigger)) => {
        if (opts.showData()) {
          // If not specified uses the input schema format
          val outDataFormat = opts.outDataFormat.getOrElse(opts.dataFormat())
          rdf.serialize(outDataFormat) match {
            case Left(msg) => println(s"Error serializing to $outDataFormat: $msg")
            case Right(str) => println(str)
          }
        }
        if (opts.showSchema() || opts.outSchemaFile.isDefined) {
          schema.convert(opts.outSchemaFormat.toOption, opts.outEngine.toOption) match {
                case Right(str) => {
                  if (opts.showSchema()) println(str)
                  if (opts.outSchemaFile.isDefined) {
                    FileUtils.writeFile(opts.outSchemaFile(), str)
                  }
                }
                case Left(e) => println(s"Error showing schema $schema with format ${opts.outSchemaFormat}: $e")
          }
        }

        if (opts.showShapeMap()) {
          println(s"Trigger shapemap: ${trigger.shapeMap}")
          println(s"ShapeMap: ${trigger.shapeMap.serialize(opts.outShapeMapFormat())}")
          println(s"Trigger json: ${trigger.toJson.spaces2}")
        }



        if (opts.clingoFile.isDefined || opts.showClingo()) {
          val maybeStr = for {
            str <- schema.toClingo(rdf, trigger.shapeMap)
          } yield str
          maybeStr.fold(e => println(s"Error converting to clingo: $e"), str => {
            if (opts.showClingo()) { println(s"$str") }
            if (opts.clingoFile.isDefined) FileUtils.writeFile(opts.clingoFile(), str)
          })
        }

        val result = schema.validate(rdf, trigger)

        if (opts.showLog()) {
          logger.info("Show log info = true")
          logger.info(s"JSON result: ${result.toJsonString2spaces}")
        }

        if (opts.showResult() || opts.outputFile.isDefined) {
          val resultSerialized = result.serialize(opts.resultFormat())
          if (opts.showResult()) println(resultSerialized)
          if (opts.outputFile.isDefined)
            FileUtils.writeFile(opts.outputFile(), resultSerialized)
        }

        if (opts.showValidationReport()) {
          val vr = result.validationReport
          val eitherReport = for {
            rdf <- vr
            str <- rdf.serialize(opts.validationReportFormat())
          } yield str
          eitherReport.fold(
            e => println(s"Error: $e"),
            println(_)
          )
        }


        if (opts.time()) {
          val endTime = System.nanoTime()
          val time: Long = endTime - startTime
          printTime("Time", opts, time)
        }

      }
    }

  }

  private def printTime(msg: String, opts: MainOpts, nanos: Long): Unit = {
    if (opts.time()) {
      val time = Duration(nanos, NANOSECONDS).toMillis
      println(f"$msg%s, $time%10d")
    }
  }

  private def errorDriver(e: Throwable, scallop: Scallop) = e match {
    case Help(s) => {
      println("Help: " + s)
      scallop.printHelp
      sys.exit(0)
    }
    case _ => {
      println("Error: %s".format(e.getMessage))
      scallop.printHelp
      sys.exit(1)
    }
  }

  private def getShapeMapStr(opts: MainOpts): Either[String, String] = {
    if (opts.shapeMap.isDefined) {
      // val shapeMapFormat = opts.shapeMapFormat.toOption.getOrElse("COMPACT")
      for {
        // TODO: Allow different shapeMap formats
        content <- FileUtils.getContents(opts.shapeMap())
      } yield content.toString
    } else Right("")
  }

  private def getNodeSelector(opts:MainOpts, pm: PrefixMap): Either[String, NodeSelector] = {
    if (opts.shapeInferNode.isDefined) {
      NodeSelector.fromString(opts.shapeInferNode(),None,pm)
    } else
      Left(s"schemaInfer option requires also schemaInferNode to specify a node selector")
  }

  private def getRDFReader(opts: MainOpts, baseFolder: Path): Either[String, RDFReader] = {
    val base = Some(FileUtils.currentFolderURL)
    if (opts.data.isDefined || opts.dataUrl.isDefined) {
      for {
        rdf <- if (opts.data.isDefined) {
          val path = baseFolder.resolve(opts.data())
          RDFAsJenaModel.fromFile(path.toFile(), opts.dataFormat(), base.map(IRI(_)))
        } else {
          RDFAsJenaModel.fromURI(opts.dataUrl(), opts.dataFormat(), base.map(IRI(_)))
        }
      } yield {
        if (opts.inference.isDefined) {
          rdf.applyInference(opts.inference()) match {
            case Right(newRdf) => newRdf
            case Left(msg) => {
              logger.info(s"Error applying inference: $msg")
              rdf
            }
          }
        } else rdf
      }
    } else if (opts.endpoint.isDefined) {
      Endpoint.fromString(opts.endpoint())
    } else {
      logger.info("RDF Data option not specified")
      Right(RDFAsJenaModel.empty)
    }
  }

  private def getSchema(opts: MainOpts, baseFolder: Path, rdf: RDFReader): Either[String, Schema] = {
    val base = Some(FileUtils.currentFolderURL)
    if (opts.schema.isDefined) {
      val path = baseFolder.resolve(opts.schema())
      Schemas.fromFile(path.toFile(), opts.schemaFormat(), opts.engine(), base)
    } else if (opts.schemaUrl.isDefined) {
      val str = Source.fromURL(opts.schemaUrl()).mkString
      Schemas.fromString(str,opts.schemaFormat(),opts.engine(),base)
    }
    else {
      logger.info("Schema not specified. Extracting schema from data")
      Schemas.fromRDF(rdf, opts.engine())
    }

  }


  private def shapeInfer(opts: MainOpts, baseFolder: Path): Unit = {
    val dataOptions = for {
     rdf <- getRDFReader(opts, baseFolder)
     nodeSelector <- getNodeSelector(opts,rdf.getPrefixMap())
     shapeLabel = opts.shapeInferLabel()
     _ <- { println(s"shapeLabel: $shapeLabel"); Right(()) }
     shapeLabelIri <- IRI.fromString(shapeLabel,None)
     _ <- { println(s"shapeLabelIri: $shapeLabelIri"); Right(()) }
     result <- SchemaInfer.runInferSchema(rdf, nodeSelector, opts.shapeInferEngine(), shapeLabelIri)
     (schema,resultMap) = result
     str <- schema.serialize(opts.shapeInferFormat())
     println(s"\nResultMap:\n${resultMap.serialize("COMPACT")}")
    } yield (rdf,schema,str)
    dataOptions.fold(e => println(s"shapeInfer: Error $e"), values => {
      val (rdf,schema,str) = values
      println(s"Shape infered: $str")
    })
  }
}

