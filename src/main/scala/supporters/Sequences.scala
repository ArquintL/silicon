/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package viper.silicon.supporters

import viper.silver.ast
import viper.silicon.common.collections.immutable.InsertionOrderedSet
import viper.silicon.interfaces.{PreambleContributor, PreambleReader}
import viper.silicon.interfaces.decider.{ProverLike, TermConverter}
import viper.silicon.state.SymbolConvert
import viper.silicon.state.terms.{SortDecl, sorts}

trait SequencesContributor[SO, SY, AX] extends PreambleContributor[SO, SY, AX]

class DefaultSequencesContributor(preambleReader: PreambleReader[String, String],
                                  symbolConverter: SymbolConvert,
                                  termConverter: TermConverter[String, String, String])
    extends SequencesContributor[sorts.Seq, String, String] {

  /* PreambleBlock = Comment x Lines */
  private type PreambleBlock = (String, Iterable[String])

  private var collectedSorts: InsertionOrderedSet[sorts.Seq] = InsertionOrderedSet.empty
  private var collectedGeneralFunctionDecls: Iterable[PreambleBlock] = Seq.empty
  private var collectedIntFunctionDecls: Iterable[PreambleBlock] = Seq.empty
  private var collectedGeneralAxioms: Iterable[PreambleBlock] = Seq.empty
  private var collectedIntAxioms: Iterable[PreambleBlock] = Seq.empty

  /* Lifetime */

  def reset() {
    collectedSorts = InsertionOrderedSet.empty
    collectedGeneralFunctionDecls = Seq.empty
    collectedIntFunctionDecls = Seq.empty
    collectedGeneralAxioms = Seq.empty
    collectedIntAxioms = Seq.empty
  }

  def start() {}
  def stop() {}

  /* Functionality */

  def analyze(program: ast.Program) {
    var sequenceTypes = InsertionOrderedSet[ast.SeqType]()

    program visit { case t: ast.Typed =>
      /* Process the type itself and its type constituents, but ignore types
       * that use type parameters. The assumption is that the latter are
       * handled by the domain emitter.
       */
      t.typ :: ast.utility.Types.typeConstituents(t.typ) filter (_.isConcrete) foreach {
        case s: ast.SeqType =>
          sequenceTypes += s
//        case s: ast.MultisetType =>
//          /* Sequences depend on multisets */
//          sequenceTypes += ast.SeqType(s.elementType)
        case _ =>
        /* Ignore other types */
      }
    }

    collectedSorts = sequenceTypes map (st => symbolConverter.toSort(st).asInstanceOf[sorts.Seq])
    collectedGeneralFunctionDecls = generateGeneralFunctionDecls
    collectedIntFunctionDecls = generateIntFunctionDecls
    collectedGeneralAxioms = generateGeneralAxioms
    collectedIntAxioms = generateIntAxioms
  }

  private def generateGeneralFunctionDecls: Iterable[PreambleBlock] = {
    val templateFile = "/dafny_axioms/sequences_declarations_dafny.smt2"

    collectedSorts map {s =>
      val substitutions = Map("$S$" -> termConverter.convert(s.elementsSort))
      val declarations = preambleReader.readParametricPreamble(templateFile, substitutions)

      (s"$templateFile [${s.elementsSort}]", declarations)
    }
  }

  private def generateIntFunctionDecls: Iterable[PreambleBlock] = {
    if (collectedSorts contains sorts.Seq(sorts.Int)) {
      val substitutions = Map("$S$" -> termConverter.convert(sorts.Int))
      val templateFile = "/dafny_axioms/sequences_int_declarations_dafny.smt2"
      val declarations = preambleReader.readParametricPreamble(templateFile, substitutions)

      Seq((templateFile, declarations))
    } else
      Seq.empty
  }

  private def generateGeneralAxioms: Iterable[PreambleBlock] = {
    val templateFile = "/dafny_axioms/sequences_axioms_dafny.smt2"

    collectedSorts map { s =>
      val substitutions = Map("$S$" -> termConverter.convert(s.elementsSort))
      val declarations = preambleReader.readParametricPreamble(templateFile, substitutions)

      (s"$templateFile [${s.elementsSort}]", declarations)
    }
  }

  private def generateIntAxioms: Iterable[PreambleBlock] = {
    if (collectedSorts contains sorts.Seq(sorts.Int)) {
      val templateFile = "/dafny_axioms/sequences_int_axioms_dafny.smt2"
      val substitutions = Map("$S$" -> termConverter.convert(sorts.Int))
      val declarations = preambleReader.readParametricPreamble(templateFile, substitutions)

      Seq((templateFile, declarations))
    } else
      Seq.empty
  }

  private def extractPreambleLines(from: Iterable[PreambleBlock]*): Iterable[String] =
    from.flatten.flatMap(_._2)

  private def emitPreambleLines(sink: ProverLike, from: Iterable[PreambleBlock]*): Unit = {
    from.flatten foreach { case (comment, declarations) =>
      sink.comment(comment)
      sink.emit(declarations)
    }
  }

  def sortsAfterAnalysis: InsertionOrderedSet[sorts.Seq] = collectedSorts

  def declareSortsAfterAnalysis(sink: ProverLike): Unit = {
    sortsAfterAnalysis foreach (s => sink.declare(SortDecl(s)))
  }

  def symbolsAfterAnalysis: Iterable[String] =
    extractPreambleLines(collectedGeneralFunctionDecls, collectedIntFunctionDecls)

  def declareSymbolsAfterAnalysis(sink: ProverLike): Unit =
    emitPreambleLines(sink, collectedGeneralFunctionDecls, collectedIntFunctionDecls)

  def axiomsAfterAnalysis: Iterable[String] =
    extractPreambleLines(collectedGeneralAxioms, collectedIntAxioms)

  def emitAxiomsAfterAnalysis(sink: ProverLike): Unit =
    emitPreambleLines(sink, collectedGeneralAxioms, collectedIntAxioms)
}
