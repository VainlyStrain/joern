package io.joern.c2cpg.astcreation

import io.shiftleft.codepropertygraph.generated.nodes.{NewComment, NewFieldIdentifier, NewIdentifier, NewLiteral}
import io.shiftleft.codepropertygraph.generated.{DispatchTypes, Operators}
import io.shiftleft.x2cpg.Ast
import org.eclipse.cdt.core.dom.ast._
import org.eclipse.cdt.internal.core.dom.parser.cpp.CPPASTQualifiedName
import org.eclipse.cdt.internal.core.model.ASTStringUtil

trait AstForPrimitivesCreator {

  this: AstCreator =>

  protected def astForComment(comment: IASTComment): Ast =
    Ast(NewComment().code(nodeSignature(comment)).filename(fileName(comment)).lineNumber(line(comment)))

  protected def astForLiteral(lit: IASTLiteralExpression, order: Int): Ast = {
    val tpe = ASTTypeUtil.getType(lit.getExpressionType)
    val litNode = NewLiteral()
      .typeFullName(registerType(tpe))
      .code(nodeSignature(lit))
      .order(order)
      .argumentIndex(order)
      .lineNumber(line(lit))
      .columnNumber(column(lit))
    Ast(litNode)
  }

  protected def astForIdentifier(ident: IASTNode, order: Int): Ast = {
    val identifierName = ident match {
      case id: IASTIdExpression => ASTStringUtil.getSimpleName(id.getName)
      case _                    => ident.toString
    }
    val variableOption = scope.lookupVariable(identifierName)
    val identifierTypeName = variableOption match {
      case Some((_, variableTypeName)) => variableTypeName
      case None                        => typeFor(ident)
    }

    val cpgIdentifier = NewIdentifier()
      .name(identifierName)
      .typeFullName(registerType(identifierTypeName))
      .code(nodeSignature(ident))
      .order(order)
      .argumentIndex(order)
      .lineNumber(line(ident))
      .columnNumber(column(ident))

    variableOption match {
      case Some((variable, _)) =>
        Ast(cpgIdentifier).withRefEdge(cpgIdentifier, variable)
      case None => Ast(cpgIdentifier)
    }
  }

  protected def astForFieldReference(fieldRef: IASTFieldReference, order: Int): Ast = {
    val op = if (fieldRef.isPointerDereference) Operators.indirectFieldAccess else Operators.fieldAccess
    val ma = newCallNode(fieldRef, op, op, DispatchTypes.STATIC_DISPATCH, order)
    val owner = astForExpression(fieldRef.getFieldOwner, 1)
    val member = NewFieldIdentifier()
      .canonicalName(fieldRef.getFieldName.toString)
      .code(fieldRef.getFieldName.toString)
      .order(2)
      .argumentIndex(2)
      .lineNumber(line(fieldRef.getFieldName))
      .columnNumber(column(fieldRef.getFieldName))
    Ast(ma).withChild(owner).withChild(Ast(member)).withArgEdge(ma, owner.root.get).withArgEdge(ma, member)
  }

  protected def astForInitializerList(l: IASTInitializerList, order: Int): Ast = {
    // TODO re-use from Operators once it there
    val op = "<operator>.arrayInitializer"
    val initCallNode = newCallNode(l, op, op, DispatchTypes.STATIC_DISPATCH, order)

    val MAX_INITIALIZERS = 1000
    val clauses = l.getClauses.slice(0, MAX_INITIALIZERS)

    val args = withOrder(clauses) {
      case (c, o) =>
        astForNode(c, o)
    }
    val validArgs = args.collect { case a if a.root.isDefined => a.root.get }
    val ast = Ast(initCallNode).withChildren(args).withArgEdges(initCallNode, validArgs)

    if (l.getClauses.length > MAX_INITIALIZERS) {
      val placeholder = NewLiteral()
        .typeFullName("ANY")
        .code("<too-many-initializers>")
        .order(MAX_INITIALIZERS)
        .argumentIndex(MAX_INITIALIZERS)
        .lineNumber(line(l))
        .columnNumber(column(l))
      ast.withChild(Ast(placeholder)).withArgEdge(initCallNode, placeholder)
    } else {
      ast
    }
  }

  protected def astForQualifiedName(qualId: CPPASTQualifiedName, order: Int): Ast = {
    val op = Operators.fieldAccess
    val ma = newCallNode(qualId, op, op, DispatchTypes.STATIC_DISPATCH, order)

    def fieldAccesses(names: List[IASTNode], order: Int): Ast = names match {
      case Nil => Ast()
      case head :: Nil =>
        astForNode(head, order)
      case head :: tail =>
        val callNode = newCallNode(head, op, op, DispatchTypes.STATIC_DISPATCH, order)
        val arg1 = astForNode(head, 1)
        val arg2 = fieldAccesses(tail, 2)
        var call =
          Ast(callNode)
            .withChild(arg1)
            .withChild(arg2)
        if (arg1.root.isDefined) call = call.withArgEdge(callNode, arg1.root.get)
        if (arg2.root.isDefined) call = call.withArgEdge(callNode, arg2.root.get)
        call
    }

    val qualifier = fieldAccesses(qualId.getQualifier.toIndexedSeq.toList, 1)

    val owner = if (qualifier != Ast()) {
      qualifier
    } else {
      Ast(NewLiteral().code("<global>").order(1).argumentIndex(1).typeFullName("ANY"))
    }

    val member = NewFieldIdentifier()
      .canonicalName(qualId.getLastName.toString)
      .code(qualId.getLastName.toString)
      .order(2)
      .argumentIndex(2)
      .lineNumber(line(qualId.getLastName))
      .columnNumber(column(qualId.getLastName))
    val ast = Ast(ma).withChild(owner).withChild(Ast(member)).withArgEdge(ma, member)
    owner.root match {
      case Some(value) => ast.withArgEdge(ma, value)
      case None        => ast
    }

  }

}