// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.plugins.groovy.lang.typing

import com.intellij.openapi.util.RecursionManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiType
import org.jetbrains.plugins.groovy.lang.psi.api.auxiliary.GrListOrMap
import org.jetbrains.plugins.groovy.lang.psi.api.statements.arguments.GrSpreadArgument
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrExpression
import org.jetbrains.plugins.groovy.lang.psi.impl.GrTupleType
import org.jetbrains.plugins.groovy.lang.psi.impl.statements.expressions.TypesUtil

open class ListLiteralType(val expressions: List<GrExpression>, private val context: PsiElement) : GrTupleType(context) {

  constructor(literal: GrListOrMap) : this(literal.initializers.toList(), literal)

  override fun isValid(): Boolean = context.isValid

  override fun inferComponents(): List<PsiType?> {
    return expressions.flatMap {
      doGetComponentTypes(it) ?: return emptyList()
    }
  }

  private fun doGetComponentTypes(initializer: GrExpression): Collection<PsiType>? {
    return RecursionManager.doPreventingRecursion(initializer, false) {
      if (initializer is GrSpreadArgument) {
        (initializer.argument.type as? GrTupleType)?.componentTypes
      }
      else {
        TypesUtil.boxPrimitiveType(initializer.type, initializer.manager, initializer.resolveScope)?.let { listOf(it) }
      }
    }
  }

  override fun toString(): String = context.text
}
