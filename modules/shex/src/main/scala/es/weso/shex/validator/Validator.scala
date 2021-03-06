package es.weso.shex.validator

import cats._
import data._
import implicits._
import com.typesafe.scalalogging.LazyLogging
import es.weso.shex._
import es.weso.rdf._
import es.weso.rdf.nodes._
import es.weso.collection.Bag
import es.weso.rbe.interval.IntervalChecker
import es.weso.rbe.{BagChecker, Empty, Rbe}
import es.weso.rbe.BagChecker._
import es.weso.utils.SeqUtils
import es.weso.shex.implicits.showShEx._
import ShExError._
import es.weso.rdf.PREFIXES._
import es.weso.shex.validator.Table._
import ShExChecker._
import es.weso.shapeMaps.{BNodeLabel => BNodeMapLabel, IRILabel => IRIMapLabel, Start => StartMapLabel, _}
import es.weso.shex.actions.TestSemanticAction

import Function.tupled

/**
 * ShEx validator
 */
case class Validator(schema: Schema,
                     externalResolver: ExternalResolver = NoAction
                    ) extends ShowValidator(schema) with LazyLogging {

  type ShapeChecker = ShapeExpr => CheckTyping
  type NodeShapeChecker = (RDFNode, Shape) => CheckTyping
  type NodeChecker = Attempt => RDFNode => CheckTyping
  type Neighs = List[Arc]
  type Candidate = (Arc, Set[ConstraintRef])
  type Candidates = List[Candidate]
  type NoCandidates = List[Arc]
  type Bag_ = Bag[ConstraintRef]
  type Rbe_ = Rbe[ConstraintRef]
  type BagChecker_ = BagChecker[ConstraintRef]
  type Result = Either[String, ResultShapeMap]

  lazy val sh_targetNode = sh + "targetNode"

  lazy val ignoredPathsClosed: List[Path] =
    List(Inverse(sh_targetNode))

  private[validator] def checkTargetNodeDeclarations: CheckTyping = for {
    rdf <- getRDF
    nodeLabels <- getTargetNodeDeclarations(rdf)
    ts <- checkAll(
      nodeLabels.map {
        case (node, label) => checkNodeLabel(node, label)
      })
    t <- combineTypings(ts)
  } yield t

  private[validator] def checkShapeMap(shapeMap: FixedShapeMap): CheckTyping = {
    for {
      rdf <- getRDF
      t <- checkNodesShapes(shapeMap)
    } yield t
  }

  private[validator] def getTargetNodeDeclarations(rdf: RDFReader): Check[List[(RDFNode, ShapeLabel)]] =
    for {
    ts <- fromEitherString(rdf.triplesWithPredicate(sh_targetNode))
    r <- checkAll(ts.
      map(t => (t.obj, mkShapeLabel(t.subj))).
      toList.map(checkPair2nd))
  } yield r

  private[validator] def checkNodesShapes(fixedMap: FixedShapeMap): CheckTyping = for {
    ts <- checkAll(fixedMap.shapeMap.toList.map(tupled(checkNodeShapesMap)))
    t <- combineTypings(ts)
  } yield t

  private[validator] def checkNodeShapeMapLabel(
                                                 node: RDFNode,
                                                 label: ShapeMapLabel,
                                                 info: Info
                                               ): CheckTyping =
    info.status match {
    case Conformant => label match {
      case StartMapLabel => checkNodeStart(node)
      case IRIMapLabel(iri) => checkNodeShapeLabel(node, IRILabel(iri))
      case BNodeMapLabel(b) => checkNodeShapeLabel(node, BNodeLabel(b))
    }
    case NonConformant => errStr(s"checkNodeShapeMapLabel: Not implemented negative info yet. Node: $node, label: $label")
    case Undefined => errStr(s"checkNodeShapeMapLabel: Not implemented undefined status yet. Node: $node, label: $label")
  }

  private[validator] def checkNodeShapesMap(node: RDFNode,
                                            shapesMap: Map[ShapeMapLabel, Info]
                                           ): CheckTyping = {
  for {
//      _ <- checkOptSemActs(node,schema.startActs)
      ts <- checkAll(shapesMap.map {
        case (label, info) => {
          checkNodeShapeMapLabel(node, label, info)
        }
      }.toList)
      t <- combineTypings(ts)
    } yield t
  }

  private[validator] def mkShapeLabel(n: RDFNode): Check[ShapeLabel] = {
    n match {
      case i: IRI => ok(IRILabel(i))
      case b: BNode => ok(BNodeLabel(b))
      case _ => {
        errStr(s"mkShapeLabel: Node ${n.show} can't be a shape")
      }
    }
  }

  private[validator] def getShape(label: ShapeLabel): Check[ShapeExpr] =
    schema.getShape(label) match {
      case Left(e) => errStr[ShapeExpr](e)
      case Right(shape) => ok(shape)
    }

  private[validator] def checkNodeShapeLabel(node: RDFNode, shape: ShapeLabel): CheckTyping = {
    cond(getShapeLabel(shape),
      (shapeLabel: ShapeLabel) => checkNodeLabel(node, shapeLabel),
      err => for {
      t <- getTyping
    } yield t.addNotEvidence(node, ShapeType(ShapeExpr.fail, Some(shape), schema), err))
  }

  private[validator] def checkNodeStart(node: RDFNode): CheckTyping = {
    schema.start match {
      case None => errStr(s"checking node $node against start declaration of schema. No start declaration found")
      case Some(shape) => {
        logger.debug(s"nodeStart. Node: ${node.show}")
        val shapeType = ShapeType(shape, Some(Start), schema)
        val attempt = Attempt(NodeShape(node, shapeType), None)
        runLocalSafeTyping(checkNodeShapeExpr(attempt, node, shape),_.addType(node,shapeType),
          (err, t) => {
            t.addNotEvidence(node, shapeType, err)
          })
      }
    }
  }

  private[validator] def getShapeLabel(label: ShapeLabel): Check[ShapeLabel] = {
    if (schema.labels contains label) ok(label)
    else errStr(s"Schema does not contain label '$label'\nAvailable labels: ${schema.labels}")
  }

  private[validator] def checkNodeLabelSafe(node: RDFNode,
                                            label: ShapeLabel,
                                            shape: ShapeExpr): CheckTyping = {
    val shapeType = ShapeType(shape, Some(label), schema)
    val attempt = Attempt(NodeShape(node, shapeType), None)
    for {
      typing <- getTyping
      t <- {
        runLocalSafeTyping(
          bind(
            checkOptSemActs(node,schema.startActs),
            checkNodeShapeExpr(attempt, node, shape)
          ),
          _.addType(node, shapeType),
          (err, t) => {
            t.addNotEvidence(node, shapeType, err)
          })
      }
    } yield {
      t
    }
  }

  private[validator] def checkNodeLabel(node: RDFNode, label: ShapeLabel): CheckTyping = {

    def addNot(typing: ShapeTyping)(err: ShExError): CheckTyping = {
      val shapeType = ShapeType(ShapeExpr.fail, Some(label), schema)
      ok(typing.addNotEvidence(node, shapeType, err))
    }

    for {
      typing <- getTyping
      newTyping <- if (typing.hasInfoAbout(node, label)) {
        ok(typing)
      } else
        cond(getShape(label),
             (shape: ShapeExpr) => checkNodeLabelSafe(node, label, shape),
             addNot(typing)
        )
    } yield newTyping
  }

  private[validator] def checkNodeShapeExpr(attempt: Attempt,
                                            node: RDFNode,
                                            s: ShapeExpr): CheckTyping = {
    s match {
      case so: ShapeOr => checkOr(attempt, node, so.shapeExprs)
      case sa: ShapeAnd => checkAnd(attempt, node, sa.shapeExprs)
      case sn: ShapeNot => checkNot(attempt, node, sn.shapeExpr)
      case nc: NodeConstraint => checkNodeConstraint(attempt, node, nc)
      case s: Shape => checkShape(attempt, node, s)
      case sr: ShapeRef => checkRef(attempt, node, sr.reference)
      case se: ShapeExternal => {
        for {
          externalShape <- se.id match {
            case None        =>
              errStr(s"No label in external shape")
            case Some(label) =>
              fromEitherString(externalResolver.getShapeExpr(label, se.annotations))
          }
          newAttempt = Attempt(NodeShape(node,ShapeType(externalShape,se.id,schema)),attempt.path)
          t <- checkNodeShapeExpr(newAttempt,node,externalShape)
        } yield t
      }
    }
  }

  private[validator] def checkAnd(
    attempt: Attempt,
    node: RDFNode,
    ses: List[ShapeExpr]): CheckTyping = for {
    ts <- checkAll(ses.map(se => checkNodeShapeExpr(attempt, node, se)))
    t <- combineTypings(ts)
  } yield t

  private[validator] def checkOr(
    attempt: Attempt,
    node: RDFNode,
    ses: List[ShapeExpr]): CheckTyping = {
    val vs = ses.map(se => checkNodeShapeExpr(attempt, node, se))
    for {
      t1 <- checkSome(
        vs,
        ShExError.msgErr(s"None of the alternatives of OR(${ses.map(_.showPrefixMap(schema.prefixMap)).mkString(",")}) is valid for node ${node.show}"))
      t2 <- addEvidence(attempt.nodeShape, s"${node.show} passes OR")
      t3 <- combineTypings(Seq(t1, t2))
    } yield t3
  }

  private[validator] def checkNot(
    attempt: Attempt,
    node: RDFNode,
    s: ShapeExpr): CheckTyping = {
    val parentShape = attempt.nodeShape.shape
    val check: CheckTyping = checkNodeShapeExpr(attempt, node, s)
    val handleError: ShExError => Check[ShapeTyping] = e => for {
      t1 <- addNotEvidence(NodeShape(node, ShapeType(s, None, schema)), e,
        s"${node.show} does not satisfy ${s.show}. Negation declared in ${parentShape.show}. Error: $e")
      t2 <- addEvidence(attempt.nodeShape, s"${node.show} satisfies not(${{ s.show }})")
      t <- combineTypings(List(t1, t2))
    } yield t
    val handleNotError: ShapeTyping => Check[ShapeTyping] = t =>
      errStr(s"Failed NOT(${s.show}) because node ${node.show} satisfies it")
    cond(check, handleNotError, handleError)
  }

  private[validator] def checkRef(
    attempt: Attempt,
    node: RDFNode,
    ref: ShapeLabel): CheckTyping = for {
    t <- checkNodeLabel(node, ref)
    _ <- if (t.hasNoType(node, ref)) {
      t.getTypingResult(node, ref) match {
        case None => errStr(s"Node ${node.show} has no shape ${ref.show}. Attempt: $attempt")
        case Some(tr) => tr.getErrors match {
          case None => errStr(s"Node ${node.show} has no shape ${ref.show}\nReason typing result ${tr.show} with no errors")
          case Some(es) => errStr(s"Node ${node.show} has no shape ${ref.show}\nErrors: ${es.map(_.show).mkString("\n")}")
        }
      }
    } else ok(())
  } yield t

  private[validator] def checkNodeConstraint(
    attempt: Attempt,
    node: RDFNode,
    s: NodeConstraint): CheckTyping =
    for {
      t1 <- optCheck(s.nodeKind, checkNodeKind(attempt, node), getTyping)
      t2 <- optCheck(s.values, checkValues(attempt, node), getTyping)
      t3 <- optCheck(s.datatype, checkDatatype(attempt, node), getTyping)
      t4 <- checkXsFacets(attempt, node)(s.xsFacets)
      t <- combineTypings(List(t1, t2, t3, t4))
    } yield {
      t
    }

  private[validator] def checkValues(attempt: Attempt, node: RDFNode)(values: List[ValueSetValue]): CheckTyping = {
    val cs: List[CheckTyping] =
      values.map(v => ValueChecker(schema).checkValue(attempt, node)(v))
    checkSome(cs,
      ShExError.msgErr(s"${node.show} does not belong to [${values.map(_.show).mkString(",")}]")
    )
  }

  private[validator] def checkDatatype(attempt: Attempt, node: RDFNode)(datatype: IRI): CheckTyping = for {
    rdf <- getRDF
    check <- rdf.checkDatatype(node, datatype) match {
      case Left(s) => errStr(s"${attempt.show}\n${node.show} does not have datatype ${datatype.show}\nDetails: $s")
      case Right(false) => errStr(s"${attempt.show}\n${node.show} does not have datatype ${datatype.show}")
      case Right(true) => addEvidence(attempt.nodeShape, s"${node.show} has datatype ${datatype.show}")
    }
  } yield check

  private[validator] def checkXsFacets(attempt: Attempt, node: RDFNode)(xsFacets: List[XsFacet]): CheckTyping = {
    if (xsFacets.isEmpty) getTyping
    else {
      FacetChecker(schema).checkFacets(attempt, node)(xsFacets)
    }
  }

  private[validator] def checkNodeKind(attempt: Attempt, node: RDFNode)(nk: NodeKind): CheckTyping = {
    nk match {
      case IRIKind => {
        checkCond(node.isIRI, attempt,
          msgErr(s"${node.show} is not an IRI"), s"${node.show} is an IRI")
      }
      case BNodeKind =>
        checkCond(node.isBNode, attempt,
          msgErr(s"${node.show} is not a BlankNode"), s"${node.show} is a BlankNode")
      case NonLiteralKind =>
        checkCond(!node.isLiteral, attempt,
          msgErr(s"${node.show} is a literal but should be a NonLiteral"),
          s"${node.show} is NonLiteral")
      case LiteralKind =>
        checkCond(node.isLiteral, attempt,
          msgErr(s"${node.show} is not an Literal"),
          s"${node.show} is a Literal")
    }
  }

  private[validator] def checkShape(attempt: Attempt, node: RDFNode, s: Shape): CheckTyping = {
    // println(s"CheckNodeShape $node\nShape: $s\n---\n")
    if (s.isEmpty) {
      // println(s"Shape is empty")
      addEvidence(attempt.nodeShape,s"Node $node matched empty shape")
    }
    else
    for {
      typing <- getTyping
      paths <- fromEither(s.paths(schema).leftMap(msgErr(_)))
      neighs <- getNeighPaths(node, paths)
      rdf <- getRDF
      // _ <- { println(s"N-Triples of RDF\n${rdf.serialize("N-TRIPLES").getOrElse("")}"); ok(()) }
      // _ <- { println(s"node: $node"); ok(()) }
      // _ <- { println(s"neighs: $paths"); ok(()) }
      extendedExpr <- fromEither(s.extendExpression(schema).leftMap(msgErr(_)))
      tableRbe <- {
        mkTable(extendedExpr, s.extra.getOrElse(List()))
      }
      (cTable, rbe) = tableRbe
      bagChecker = IntervalChecker(rbe)
      csRest <- {
        calculateCandidates(neighs, cTable)
      }
      (candidates, rest) = csRest
      _ <- {
        // println(s"Rests: $rest\nCandidates: $candidates\nNeighs: $neighs\nClosed: ${s.isClosed}")
        checkRests(rest, s.extraPaths, s.isClosed, ignoredPathsClosed)
      }
      _ <- { if (s.isClosed) {checkNoStrangeProperties(node, paths)} else ok(())      }
      typing <- {
        // println(s"Before checkCandidates: $candidates\nTable:$cTable\n(end Table)\n")
        checkCandidates(attempt, bagChecker, cTable)(candidates)
      }
      _ <- {
        // println(s"checkOptSemActs: ${s.actions}")
        checkOptSemActs(node,s.actions)
      }
    } yield {
      // println(s"End of checkShape(attempt=${attempt.show},node=${node.show},shape=${s.show})=${typing.show}")
      typing
    }
  }

  private def checkNoStrangeProperties(node: RDFNode, paths: List[Path]): Check[Unit] = for {
    rdf <- getRDF
    s <- getNotAllowedPredicates(node, paths)
    check <- if (s.isEmpty) ok(())
             else errStr(s"Closed shape does not allow properties: ${s.map(_.show).mkString(",")}")
  } yield check

  private[validator] def checkOptSemActs(node: RDFNode, maybeActs: Option[List[SemAct]]): Check[Unit] =
    maybeActs match {
      case None => ok(())
      case Some(as) => checkSemActs(node,as)
  }

  private[validator] def checkSemActs(node: RDFNode,
                                      as: List[SemAct]
                                     ): Check[Unit] = for {
    _ <- checkAll(as.map(checkSemAct(node,_)))
  } yield ()

  private[validator] def checkSemAct(node: RDFNode,
                                     a: SemAct
                                    ): Check[Unit] = for {
    rdf <- getRDF
    _ <- runAction(a.name,a.code,node,rdf)
  } yield ()

  private[validator] def runAction(name: IRI, code: Option[String], node: RDFNode, rdf: RDFReader): Check[Unit] = {
    // println(s"Semantic action: $name/$code")
    for {
      _ <- name match {
        case TestSemanticAction.`iri` => {
          // println(s"Running semantic action: $code")
          TestSemanticAction.runAction(code.getOrElse(""), node, rdf).fold(e => errStr(e), _ => ok(()))
        }
        case _ => {
          logger.info(s"Unsupported semantic action processor: $name")
          addLog(List(Action(name, code)))
        }
      }
    } yield ()
  }

  private[validator] def checkRests(rests: List[Arc],
                                    extras: List[Path],
                                    isClosed: Boolean,
                                    ignoredPathsClosed: List[Path]): Check[Unit] = {
    val zero: Either[String,Unit] = Right(())
    def combine(step: Either[String,Unit],current: Either[String,Unit]): Either[String,Unit] =
      (step,current) match {
       case (Left(str1),_) => Left(str1)
       case (_, Left(str2)) => Left(str2)
       case (Right(()),Right(())) => Right(())
    }
    val ts: List[Either[String,Unit]] = rests.map(checkRest(_, extras, isClosed, ignoredPathsClosed))
    val r: Either[String, Unit] = ts.foldLeft(zero)(combine)
    r.fold(e => errStr(e), _ => ok(()))
  }

  private[validator] def checkRest(rest: Arc,
                                   extras: List[Path],
                                   isClosed: Boolean,
                                   ignoredPathsClosed: List[Path]
                                  ): Either[String,Unit] = {
    val restPath = rest.path
	  // Ignore extra predicates if they are inverse
    if (isClosed && restPath.isDirect) {
      // TODO: Review if the extra.contains(restpath) check is necessary
      // Extra has been implemented as a negation
      if (ignoredPathsClosed.contains(restPath) || extras.contains(restPath)) {
        Right(())
      } else {
        Left(s"Closed shape. But rest ${restPath.show} is not in ${ignoredPathsClosed.map(_.show).mkString(",")} or ${extras.map(_.show).mkString(",")}")
      }
    } else Right(())
  }

  private[validator] def mkTable(maybeTe: Option[TripleExpr],
                                 extra: List[IRI]
                                ): Check[(CTable, Rbe_)] = {
    maybeTe match {
      case None => ok((CTable.empty, Empty))
      case Some(te) => fromEitherString(
        for {
         tem <- schema.eitherResolvedTripleExprMap
         pair <- CTable.mkTable(te,extra,tem.getOrElse(Map()))
        } yield pair
      )
    }
  }

  /**
   * Calculates the sequence of candidates
   * Example: Neighs (p,x1),(p,x2),(q,x2),(r,x3)
   *   Table: { constraints: C1 -> IRI, C2 -> ., paths: p -> List(C1,C2), q -> C1 }
   *   Result: x1
   * @param neighs
   * @param table
   * @return a tuple (cs,rs) where cs is the list of candidates and rs is the nodes that didn't match any
   */
  private[validator] def calculateCandidates(
    neighs: Neighs,
    table: CTable): Check[(Candidates, NoCandidates)] = {
    val candidates = table.neighs2Candidates(neighs)
    val (cs, rs) = candidates.partition(matchable)
    ok((cs, rs.map(getArc(_))))
  }

  private def getArc(c: Candidate): Arc = {
    c._1
  }

  private def matchable(c: Candidate):Boolean = {
    val (_,constraintSet) = c
    !constraintSet.isEmpty
  }

  private[validator] def checkCandidates(
    attempt: Attempt,
    bagChecker: BagChecker_,
    table: CTable)(cs: Candidates): CheckTyping = {
    val as: List[CandidateLine] = SeqUtils.transpose(cs).map(CandidateLine(_))
    as.length match {
      case 1 => { // Deterministic
        checkCandidateLine(attempt, bagChecker, table)(as.head)
      }
      case 0 => {
        errStr(s"${attempt.show} Empty list of candidates")
      }
      case n => {
        val checks: List[CheckTyping] = as.map(checkCandidateLine(attempt, bagChecker, table)(_))
        checkSome(checks,
          ShExError.msgErr(
            s"""|None of the candidates matched. Attempt: ${attempt.show}
                |Bag: ${bagChecker.show}
                |Candidate lines:${as.map(_.show).mkString(",")}
                |""".stripMargin)
        )
      }
    }
  }

  private[validator] def showListCandidateLine(ls: List[CandidateLine]): String = {
    ls.map(_.show).mkString("\n")
  }

  private[validator] def checkCandidateLine(attempt: Attempt,
                                            bagChecker: BagChecker_,
                                            table: CTable
                                           )(cl: CandidateLine): CheckTyping = {
    // println(s"checkCandidateLine: ${cl}")
    // println(s"Table: $table")
    val bag = cl.mkBag
    bagChecker.check(bag, false).fold(
      e => {
        errStr(s"${attempt.show} Candidate line ${cl.show} which corresponds to ${bag} does not match ${Rbe.show(
          bagChecker.rbe)}\nTable:${table.show}\nErr: $e")
      },
      bag => {
        // println(s"Matches RBE...")
        val nodeConstraints = cl.nodeConstraints(table)
        val checkNodeConstraints: List[CheckTyping] =
          nodeConstraints.map {
            case (node, pair) => {
              val (shapeExpr, maybeSemActs) = pair
              // println(s"Checking $node with $shapeExpr")
              for {
              t <- checkNodeShapeExpr(attempt, node, shapeExpr)
              _ <- checkOptSemActs(node,maybeSemActs)
            } yield t
          }}
        for {
          typing <- getTyping
          ts <- checkAll(checkNodeConstraints)
          t <- combineTypings(typing :: ts)
        } yield {
          t
        }
      }
    )
  }

  private[validator] def getNeighs(node: RDFNode): Check[Neighs] =
  for {
    rdf <- getRDF
    outTriples <- fromEitherString(rdf.triplesWithSubject(node))
    outgoing = outTriples.map(t => Arc(Direct(t.pred), t.obj)).toList
    inTriples <- fromEitherString(rdf.triplesWithObject(node))
    incoming = inTriples.map(t => Arc(Inverse(t.pred), t.subj)).toList
  } yield {
    val neighs = outgoing ++ incoming
    neighs
  }

  private[validator] def getNeighPaths(node: RDFNode, paths: List[Path]): Check[Neighs] = {
    val outgoingPredicates = paths.collect { case Direct(p) => p }
    for {
      rdf        <- getRDF
      outTriples <- fromEitherString(rdf.triplesWithSubjectPredicates(node, outgoingPredicates))
      outgoing = outTriples.map(t => Arc(Direct(t.pred), t.obj)).toList
      inTriples <- fromEitherString(rdf.triplesWithObject(node))
      incoming = inTriples.map(t => Arc(Inverse(t.pred), t.subj)).toList
    } yield {
      val neighs = outgoing ++ incoming
      neighs
    }
  }

  private[validator] def getNotAllowedPredicates(node: RDFNode, paths: List[Path]): Check[Set[IRI]] = for {
    rdf <- getRDF
    ts <- fromEitherString(rdf.triplesWithSubject(node))
  } yield {
    val allowedPreds = paths.collect { case Direct(p) => p }
    ts.collect {
      case s if !(allowedPreds contains s.pred) => s.pred
    }
  }



  lazy val emptyTyping: ShapeTyping = Monoid[ShapeTyping].empty

  def validateNodeDecls(rdf: RDFReader): Result = {
    runValidator(checkTargetNodeDeclarations, rdf)
  }

  def validateNodeShape(rdf: RDFReader, node: IRI, shape: String): Result = {
    ShapeLabel.fromString(shape).fold(
      e => Left(s"Can not obtain label from $shape"),
      label => runValidator(checkNodeShapeLabel(node, label), rdf)
    )
  }

  def validateNodeStart(rdf: RDFReader, node: IRI): Result = {
    runValidator(checkNodeStart(node), rdf)
  }

  def validateShapeMap(rdf: RDFReader, shapeMap: FixedShapeMap): Result = {
    runValidator(checkShapeMap(shapeMap), rdf)
  }

  def runValidator(chk: Check[ShapeTyping], rdf: RDFReader): Result = {
    cnvResult(runCheck(chk, rdf),rdf)
  }

  private def cnvResult(r: CheckResult[ShExError, ShapeTyping, Log],
                rdf: RDFReader): Result = for {
    shapeTyping <- r.toEither.leftMap(_.msg)
    result <- shapeTyping.toShapeMap(rdf.getPrefixMap, schema.prefixMap)
  } yield result

}

object Validator {

  def empty = Validator(schema = Schema.empty)

  type Result[A] = Either[NonEmptyList[ShExError], List[(A, Evidences)]]

  def isOK[A](r: Result[A]): Boolean =
    r.isRight && r.toList.isEmpty == false

  def validate(schema: Schema, fixedShapeMap: FixedShapeMap, rdf: RDFReader): Either[String, ResultShapeMap] = {
    val validator = Validator(schema)
    validator.validateShapeMap(rdf, fixedShapeMap)
  }

}

