package io.joern.dataflowengineoss.language

import io.shiftleft.codepropertygraph.generated.nodes.{CfgNode, MethodParameterIn}
import io.shiftleft.semanticcpg.language._
import org.apache.commons.lang.StringUtils
import overflowdb.traversal.help.Table

case class Path(elements: List[CfgNode]) {
  def resultPairs(): List[(String, Option[Integer])] = {
    val pairs = elements.map {
      case point: MethodParameterIn =>
        val method      = point.method
        val method_name = method.name
        val code        = s"$method_name(${method.parameter.l.sortBy(_.order).map(_.code).mkString(", ")})"
        (code, point.lineNumber)
      case point => (point.statement.repr, point.lineNumber)
    }
    pairs.headOption.map(x => x :: pairs.sliding(2).collect { case Seq(a, b) if a != b => b }.toList).getOrElse(List())
  }
}

object Path {

  implicit val show: Show[Path] = { path =>
    Table(
      columnNames = Array("nodeType", "tracked", "lineNumber", "method", "file"),
      rows = path.elements.map { cfgNode =>
        val nodeType   = cfgNode.getClass.getSimpleName
        val method     = cfgNode.method
        val methodName = method.name
        val lineNumber = cfgNode.lineNumber.getOrElse("N/A").toString
        val fileName   = method.file.name.headOption.getOrElse("N/A")
        val statement = cfgNode match {
          case _: MethodParameterIn =>
            val paramsPretty = method.parameter.toList.sortBy(_.index).map(_.code).mkString(", ")
            s"$methodName($paramsPretty)"
          case _ => cfgNode.statement.repr
        }
        val tracked = StringUtils.normalizeSpace(StringUtils.abbreviate(statement, 20))
        Array(nodeType, tracked, lineNumber, methodName, fileName)
      }
    ).render
  }

}
