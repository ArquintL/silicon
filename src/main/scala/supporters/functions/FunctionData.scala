/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package viper.silicon.supporters.functions

import viper.silver.ast
import viper.silver.ast.utility.Functions
import viper.silicon.{Config, Map, toMap}
import viper.silicon.common.collections.immutable.InsertionOrderedSet
import viper.silicon.interfaces.FatalResult
import viper.silicon.state.{IdentifierFactory, SymbolConvert}
import viper.silicon.state.terms._
import viper.silicon.state.terms.predef._
import viper.silicon.supporters.PredicateData
import viper.silicon.supporters.qps._

class FunctionData(val programFunction: ast.Function,
                   val height: Int,
                   val quantifiedFields: InsertionOrderedSet[ast.Field],
                   val program: ast.Program)
                  (symbolConverter: SymbolConvert,
                   expressionTranslator: HeapAccessReplacingExpressionTranslator,
                   identifierFactory: IdentifierFactory,
                   predicateData: ast.Predicate => PredicateData,
                   config: Config) {

  private[this] var phase = 0

  /*
   * Properties computed from the constructor arguments
   */

  val function: HeapDepFun = symbolConverter.toFunction(programFunction)
  val limitedFunction = FunctionSupporter.limitedVersion(function)
  val statelessFunction = FunctionSupporter.statelessVersion(function)

  val formalArgs: Map[ast.AbstractLocalVar, Var] = toMap(
    for (arg <- programFunction.formalArgs;
         v = arg.localVar)
    yield
      v -> Var(identifierFactory.fresh(v.name),
               symbolConverter.toSort(v.typ)))

  val formalResult = Var(identifierFactory.fresh(programFunction.result.name),
                         symbolConverter.toSort(programFunction.result.typ))

  val arguments = Seq(`?s`) ++ formalArgs.values

  val functionApplication = App(function, `?s` +: formalArgs.values.toSeq)
  val limitedFunctionApplication = App(limitedFunction, `?s` +: formalArgs.values.toSeq)
  val triggerFunctionApplication = App(statelessFunction, formalArgs.values.toSeq)

  val limitedAxiom =
    Forall(arguments,
           limitedFunctionApplication === functionApplication,
           Trigger(functionApplication))

  val triggerAxiom =
    Forall(arguments, triggerFunctionApplication, Trigger(limitedFunctionApplication))

  val fvfGenerators: Map[ast.Field, Fun] = toMap(
    quantifiedFields map (field => {
        val fvfSort = sorts.FieldValueFunction(symbolConverter.toSort(field.typ))
        val id = function.id.rename(name => s"${name}_fvfgen_${field.name}")

        field -> Fun(id, function.argSorts, fvfSort) }))

  /*
   * Data collected during phases 1 (well-definedness checking) and 2 (verification)
   */

  /* TODO: Analogous to fresh FVFs, Nadja records PSFs in the FunctionRecorder,
   *       but they are never used. My guess is, that QP assertions over predicates
   *       are currently not really supported (incomplete? unsound?)
   */

  private[functions] var verificationFailures: Seq[FatalResult] = Vector.empty
  private[functions] var locToSnap: Map[ast.LocationAccess, Term] = Map.empty
  private[functions] var fappToSnap: Map[ast.FuncApp, Term] = Map.empty
  private[this] var freshFvfsAndDomains: InsertionOrderedSet[(FvfDefinition, Seq[Term])] = InsertionOrderedSet.empty
  private[this] var freshPsfsAndDomains: InsertionOrderedSet[(PsfDefinition, Seq[Term])] = InsertionOrderedSet.empty
  private[this] var freshFieldInvs: InsertionOrderedSet[InverseFunction] = InsertionOrderedSet.empty
  private[this] var freshPredInvs: InsertionOrderedSet[PredicateInverseFunction] = InsertionOrderedSet.empty
  private[this] var freshArps: InsertionOrderedSet[(Var, Term)] = InsertionOrderedSet.empty
  private[this] var freshSymbolsAcrossAllPhases: InsertionOrderedSet[Function] = InsertionOrderedSet.empty

  private[functions] def getFreshFieldInvs: InsertionOrderedSet[InverseFunction] = freshFieldInvs
  private[functions] def getFreshPredInvs: InsertionOrderedSet[PredicateInverseFunction] = freshPredInvs
  private[functions] def getFreshArps: InsertionOrderedSet[Var] = freshArps.map(_._1)
  private[functions] def getFreshSymbolsAcrossAllPhases: InsertionOrderedSet[Function] = freshSymbolsAcrossAllPhases

  private[functions] def advancePhase(recorders: Seq[FunctionRecorder]): Unit = {
    assert(0 <= phase && phase <= 1, s"Cannot advance from phase $phase")

    val mergedFunctionRecorder: FunctionRecorder =
      if (recorders.isEmpty)
        NoopFunctionRecorder
      else
        recorders.tail.foldLeft(recorders.head)((summaryRec, nextRec) => summaryRec.merge(nextRec))

    locToSnap = mergedFunctionRecorder.locToSnap
    fappToSnap = mergedFunctionRecorder.fappToSnap
    freshFvfsAndDomains = mergedFunctionRecorder.freshFvfsAndDomains
    freshPsfsAndDomains = mergedFunctionRecorder.freshPsfsAndDomains
    freshFieldInvs = mergedFunctionRecorder.freshFieldInvs
    freshPredInvs = mergedFunctionRecorder.freshPredInvs
    freshArps = mergedFunctionRecorder.freshArps

    freshSymbolsAcrossAllPhases ++= freshArps.map(_._1)
    freshSymbolsAcrossAllPhases ++= freshFieldInvs.map(_.func)
    freshSymbolsAcrossAllPhases ++= freshPredInvs.map(_.func)

    phase += 1
  }

  private def generateNestedDefinitionalAxioms: InsertionOrderedSet[Term] = (
       freshFieldInvs.flatMap(_.definitionalAxioms)
    ++ freshPredInvs.flatMap(_.definitionalAxioms)
    ++ freshFvfsAndDomains.flatMap { case (fvfDef, domDef) =>
         (fvfDef match {
            case fvfDef: SummarisingFvfDefinition => fvfDef.quantifiedValueDefinitions
            case _ => fvfDef.valueDefinitions
          }) ++ domDef
       }
    ++ freshArps.map(_._2)
  )

  private[this] def bindSymbols(innermostBody: Term): Term = {
    val bindings: Map[Var, Term] = (
         Map(formalResult -> limitedFunctionApplication)
      ++ freshFvfsAndDomains.map { case (fvfDef, _) =>
                  fvfDef.fvf -> App(fvfGenerators(fvfDef.field), arguments) })

    Let(toMap(bindings), innermostBody)
  }

  /*
   * Properties resulting from phase 1 (well-definedness checking)
   */

  lazy val translatedPres: Seq[Term] = {
    assert(1 <= phase && phase <= 2, s"Cannot translate precondition in phase $phase")

    expressionTranslator.translatePrecondition(program, programFunction.pres, this)
  }

  lazy val postAxiom: Option[Term] = {
    assert(phase == 1, s"Postcondition axiom must be generated in phase 1, current phase is $phase")

    if (programFunction.posts.nonEmpty) {
      val posts =
        expressionTranslator.translatePostcondition(program, programFunction.posts, this)

      val pre = And(translatedPres)
      val innermostBody = And(generateNestedDefinitionalAxioms ++ List(Implies(pre, And(posts))))
      val body = bindSymbols(innermostBody)

      Some(Forall(arguments, body, Trigger(limitedFunctionApplication)))
    } else
      None
  }

  /*
   * Properties resulting from phase 2 (verification)
   */

  lazy val predicateTriggers: Map[ast.Predicate, App] = {
    val recursiveCallsAndUnfoldings: Seq[(ast.FuncApp, Seq[ast.Unfolding])] =
      Functions.recursiveCallsAndSurroundingUnfoldings(programFunction)

    val outerUnfoldings: Seq[ast.Unfolding] =
      recursiveCallsAndUnfoldings.flatMap(_._2.headOption)

    val predicateAccesses: Seq[ast.PredicateAccess] =
      if (recursiveCallsAndUnfoldings.isEmpty)
        Vector.empty
      //        programFunction.pres flatMap (pre =>
      //          pre.shallowCollect { case predacc: ast.PredicateAccess => predacc })
      else
        outerUnfoldings map (_.acc.loc)

    toMap(predicateAccesses.map(predacc => {
      val predicate = program.findPredicate(predacc.predicateName)
      val triggerFunction = predicateData(predicate).triggerFunction

      /* TODO: Don't use translatePrecondition - refactor expressionTranslator */
      val args = (
           expressionTranslator.getOrFail(locToSnap, predacc, sorts.Snap, programFunction.name)
        +: expressionTranslator.translatePrecondition(program, predacc.args, this))

      val fapp = App(triggerFunction, args)

      predicate -> fapp
    }))
  }

  lazy val definitionalAxiom: Option[Term] = {
    assert(phase == 2, s"Definitional axiom must be generated in phase 2, current phase is $phase")

    val optBody = expressionTranslator.translate(program, programFunction, this)

    optBody.map(translatedBody => {
      val pre = And(translatedPres)
      val nestedDefinitionalAxioms = generateNestedDefinitionalAxioms
      val innermostBody = And(nestedDefinitionalAxioms ++ List(Implies(pre, And(functionApplication === translatedBody))))
      val body = bindSymbols(innermostBody)
      val allTriggers = (
           Seq(Trigger(functionApplication))
        ++ predicateTriggers.values.map(pt => Trigger(Seq(triggerFunctionApplication, pt))))

      Forall(arguments, body, allTriggers)})
  }
}
