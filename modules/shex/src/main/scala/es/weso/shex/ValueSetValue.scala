package es.weso.shex

import es.weso.rdf.PREFIXES._
import es.weso.rdf.nodes._

sealed trait ValueSetValue

sealed trait ObjectValue extends ValueSetValue

case class IRIValue(i: IRI) extends ObjectValue

sealed trait ObjectLiteral extends ObjectValue
case class StringValue(s: String) extends ObjectLiteral
case class DatatypeString(s: String, iri: IRI) extends ObjectLiteral
case class LangString(s: String, lang: Lang) extends ObjectLiteral

object ObjectValue {
  def trueValue: ObjectValue = DatatypeString("true", `xsd:boolean`)
  def falseValue: ObjectValue = DatatypeString("false", `xsd:boolean`)
  def intValue(n: Int, repr: String): ObjectValue =
    DatatypeString(repr, `xsd:integer`)
  def intValue(n: Int): ObjectValue =
    intValue(n, n.toString)
  def doubleValue(d: Double, repr: String): ObjectValue =
    DatatypeString(repr, `xsd:double`)
  def decimalValue(d: BigDecimal, repr: String): ObjectValue =
    DatatypeString(repr, `xsd:decimal`)
  def literalValue(l: Literal): ObjectValue =
    l match {
      case DatatypeLiteral(lex, dt) =>
        if (dt == `xsd:string`) StringValue(lex)
        else DatatypeString(lex, dt)
      case IntegerLiteral(n, repr) => intValue(n,repr)
      case DecimalLiteral(d, repr) => decimalValue(d,repr)
      case DoubleLiteral(d, repr) => doubleValue(d,repr)
      case StringLiteral(s) => StringValue(s) // DatatypeString(s, xsd_string)
      case BooleanLiteral(b) => if (b) trueValue else falseValue
      case LangLiteral(lex, lang) => LangString(lex, lang)
    }
}

case class IRIStem(stem: IRI) extends ValueSetValue
case class IRIStemRange(stem: IRIStemRangeValue,
                        exclusions: Option[List[IRIExclusion]]) extends ValueSetValue

sealed trait IRIStemRangeValue
case class IRIStemValueIRI(iri: IRI) extends IRIStemRangeValue
case class IRIStemWildcard() extends IRIStemRangeValue

sealed trait IRIExclusion
case class IRIRefExclusion(iri: IRI) extends IRIExclusion
case class IRIStemExclusion(iriStem: IRIStem) extends IRIExclusion

case class LanguageStem(stem: Lang) extends ValueSetValue
case class LanguageStemRange(stem: LanguageStemRangeValue,
                             exclusions: Option[List[LanguageExclusion]]) extends ValueSetValue


sealed trait LanguageStemRangeValue
case class LanguageStemRangeLang(stem: Lang) extends LanguageStemRangeValue
case class LanguageStemRangeWildcard() extends LanguageStemRangeValue

sealed trait LanguageExclusion
case class LanguageTagExclusion(lang: Lang) extends LanguageExclusion
case class LanguageStemExclusion(languageStem: LanguageStem) extends LanguageExclusion

case class LiteralStem(stem: String) extends ValueSetValue
case class LiteralStemRange(stem: LiteralStemRangeValue,
                            exclusions: Option[List[LiteralExclusion]]) extends ValueSetValue


sealed trait LiteralStemRangeValue
case class LiteralStemRangeString(str: String) extends LiteralStemRangeValue
case class LiteralStemRangeWildcard() extends LiteralStemRangeValue

sealed trait LiteralExclusion
case class LiteralStringExclusion(str: String) extends LiteralExclusion
case class LiteralStemExclusion(literalStem: LiteralStem) extends LiteralExclusion

case class Language(languageTag: Lang) extends ValueSetValue
