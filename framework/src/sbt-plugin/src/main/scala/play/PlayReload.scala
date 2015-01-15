/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package play

import sbt._
import sbt.Keys._

import play.api.PlayException
import play.PlayExceptions._
import play.runsupport.Reloader.{ CompileResult, CompileSuccess, CompileFailure, Source, SourceMap }

object PlayReload {

  def compile(reloadCompile: () => Result[sbt.inc.Analysis], classpath: () => Result[Classpath], streams: () => Option[Streams]): CompileResult = {
    reloadCompile().toEither
      .left.map(compileFailure(streams()))
      .right.map { analysis =>
        classpath().toEither
          .left.map(compileFailure(streams()))
          .right.map { classpath =>
            CompileSuccess(sourceMap(analysis), classpath.files)
          }.fold(identity, identity)
      }.fold(identity, identity)
  }

  def sourceMap(analysis: sbt.inc.Analysis): SourceMap = {
    analysis.apis.internal.foldLeft(Map.empty[String, Source]) {
      case (sourceMap, (file, source)) => sourceMap ++ {
        source.api.definitions map { d => d.name -> Source(file, originalSource(file)) }
      }
    }
  }

  def originalSource(file: File): Option[File] = {
    play.twirl.compiler.MaybeGeneratedSource.unapply(file).map(_.file)
  }

  def compileFailure(streams: Option[Streams])(incomplete: Incomplete): CompileResult = {
    CompileFailure(taskFailureHandler(incomplete, streams))
  }

  def taskFailureHandler(incomplete: Incomplete, streams: Option[Streams]): PlayException = {
    Incomplete.allExceptions(incomplete).headOption.map {
      case e: PlayException => e
      case e: xsbti.CompileFailed =>
        getProblems(incomplete, streams)
          .find(_.severity == xsbti.Severity.Error)
          .map(CompilationException)
          .getOrElse(UnexpectedException(Some("The compilation failed without reporting any problem!"), Some(e)))
      case e: Exception => UnexpectedException(unexpected = Some(e))
    }.getOrElse {
      UnexpectedException(Some("The compilation task failed without any exception!"))
    }
  }

  def getProblems(incomplete: Incomplete, streams: Option[Streams]): Seq[xsbti.Problem] = {
    (allProblems(incomplete) ++ {
      Incomplete.linearize(incomplete).filter(i => i.node.isDefined && i.node.get.isInstanceOf[ScopedKey[_]]).flatMap { i =>
        val JavacError = """\[error\]\s*(.*[.]java):(\d+):\s*(.*)""".r
        val JavacErrorInfo = """\[error\]\s*([a-z ]+):(.*)""".r
        val JavacErrorPosition = """\[error\](\s*)\^\s*""".r

        streams.map { streamsManager =>
          var first: (Option[(String, String, String)], Option[Int]) = (None, None)
          var parsed: (Option[(String, String, String)], Option[Int]) = (None, None)
          Output.lastLines(i.node.get.asInstanceOf[ScopedKey[_]], streamsManager, None).map(_.replace(scala.Console.RESET, "")).map(_.replace(scala.Console.RED, "")).collect {
            case JavacError(file, line, message) => parsed = Some((file, line, message)) -> None
            case JavacErrorInfo(key, message) => parsed._1.foreach { o =>
              parsed = Some((parsed._1.get._1, parsed._1.get._2, parsed._1.get._3 + " [" + key.trim + ": " + message.trim + "]")) -> None
            }
            case JavacErrorPosition(pos) =>
              parsed = parsed._1 -> Some(pos.size)
              if (first == ((None, None))) {
                first = parsed
              }
          }
          first
        }.collect {
          case (Some(error), maybePosition) => new xsbti.Problem {
            def message = error._3
            def category = ""
            def position = new xsbti.Position {
              def line = xsbti.Maybe.just(error._2.toInt)
              def lineContent = ""
              def offset = xsbti.Maybe.nothing[java.lang.Integer]
              def pointer = maybePosition.map(pos => xsbti.Maybe.just((pos - 1).asInstanceOf[java.lang.Integer])).getOrElse(xsbti.Maybe.nothing[java.lang.Integer])
              def pointerSpace = xsbti.Maybe.nothing[String]
              def sourceFile = xsbti.Maybe.just(file(error._1))
              def sourcePath = xsbti.Maybe.just(error._1)
            }
            def severity = xsbti.Severity.Error
          }
        }

      }
    }).map(remapProblemForGeneratedSources)
  }

  def allProblems(inc: Incomplete): Seq[xsbti.Problem] = {
    allProblems(inc :: Nil)
  }

  def allProblems(incs: Seq[Incomplete]): Seq[xsbti.Problem] = {
    problems(Incomplete.allExceptions(incs).toSeq)
  }

  def problems(es: Seq[Throwable]): Seq[xsbti.Problem] = {
    es flatMap {
      case cf: xsbti.CompileFailed => cf.problems
      case _ => Nil
    }
  }

  def remapProblemForGeneratedSources(problem: xsbti.Problem) = {
    val mappedPosition = play.Play.playPositionMapper(problem.position)
    mappedPosition.map { pos =>
      new xsbti.Problem {
        def message = problem.message
        def category = ""
        def position = pos
        def severity = problem.severity
      }
    } getOrElse problem
  }

}
