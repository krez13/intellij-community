/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.python.psi.types;

import com.intellij.psi.PsiElement;
import com.intellij.psi.util.QualifiedName;
import com.intellij.util.ArrayUtil;
import com.intellij.util.ProcessingContext;
import com.intellij.util.containers.ContainerUtil;
import com.jetbrains.python.PyNames;
import com.jetbrains.python.psi.*;
import com.jetbrains.python.psi.resolve.PyResolveContext;
import com.jetbrains.python.psi.resolve.PyResolveImportUtil;
import com.jetbrains.python.psi.resolve.QualifiedResolveResult;
import com.jetbrains.python.psi.resolve.RatedResolveResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.jetbrains.python.psi.PyFunction.Modifier.STATICMETHOD;
import static com.jetbrains.python.psi.PyUtil.as;

/**
 * Type of a particular function that is represented as a {@link com.jetbrains.python.psi.PyCallable} in the PSI tree.
 *
 * @author vlan
 */
public class PyFunctionTypeImpl implements PyFunctionType {
  @NotNull private final PyCallable myCallable;

  public PyFunctionTypeImpl(@NotNull PyCallable callable) {
    myCallable = callable;
  }

  @Override
  public boolean isCallable() {
    return true;
  }

  @Nullable
  @Override
  public PyType getReturnType(@NotNull TypeEvalContext context) {
    return context.getReturnType(myCallable);
  }

  @Nullable
  @Override
  public PyType getCallType(@NotNull TypeEvalContext context, @NotNull PyCallSiteExpression callSite) {
    return myCallable.getCallType(context, callSite);
  }

  @Nullable
  @Override
  public List<PyCallableParameter> getParameters(@NotNull TypeEvalContext context) {
    final List<PyCallableParameter> result = new ArrayList<>();
    for (PyParameter parameter : myCallable.getParameterList().getParameters()) {
      result.add(new PyCallableParameterImpl(parameter));
    }
    return result;
  }

  @Override
  public List<? extends RatedResolveResult> resolveMember(@NotNull String name,
                                                          @Nullable PyExpression location,
                                                          @NotNull AccessDirection direction,
                                                          @NotNull PyResolveContext resolveContext) {
    final PyClassType delegate = selectCallableType(location, resolveContext.getTypeEvalContext());
    if (delegate == null) {
      return Collections.emptyList();
    }
    return delegate.resolveMember(name, location, direction, resolveContext);
  }

  @Override
  public Object[] getCompletionVariants(String completionPrefix, PsiElement location, ProcessingContext context) {
    final TypeEvalContext typeEvalContext = TypeEvalContext.codeCompletion(location.getProject(), location.getContainingFile());
    final PyClassType delegate;
    if (location instanceof PyReferenceExpression) {
      delegate = selectCallableType(((PyReferenceExpression)location).getQualifier(), typeEvalContext);
    }
    else {
      final PyClass cls = as(PyResolveImportUtil.resolveTopLevelMember(QualifiedName.fromDottedString(PyNames.TYPES_FUNCTION_TYPE),
                                                                       PyResolveImportUtil.fromFoothold(myCallable)), PyClass.class);
      delegate = cls != null ? new PyClassTypeImpl(cls, false) : null;
    }
    if (delegate == null) {
      return ArrayUtil.EMPTY_OBJECT_ARRAY;
    }
    return delegate.getCompletionVariants(completionPrefix, location, context);
  }

  @Nullable
  private PyClassType selectCallableType(@Nullable PyExpression location, @NotNull TypeEvalContext context) {
    final String className;
    if (location instanceof PyReferenceExpression && isBoundMethodReference((PyReferenceExpression)location, context)) {
      className = PyNames.TYPES_METHOD_TYPE;
    }
    else {
      className = PyNames.TYPES_FUNCTION_TYPE;
    }
    final PyClass cls = as(PyResolveImportUtil.resolveTopLevelMember(QualifiedName.fromDottedString(className),
                                                                     PyResolveImportUtil.fromFoothold(myCallable)), PyClass.class);
    return cls != null ? new PyClassTypeImpl(cls, false) : null;
  }

  private boolean isBoundMethodReference(@NotNull PyReferenceExpression location, @NotNull TypeEvalContext context) {
    final PyFunction function = as(getCallable(), PyFunction.class);
    final boolean isNonStaticMethod = function != null && function.getContainingClass() != null && function.getModifier() != STATICMETHOD;
    if (isNonStaticMethod) {
      // In Python 2 unbound methods have __method fake type
      if (LanguageLevel.forElement(location).isOlderThan(LanguageLevel.PYTHON30)) {
        return true;
      }
      final PyExpression qualifier;
      if (location.isQualified()) {
        qualifier = location.getQualifier();
      }
      else {
        final PyResolveContext resolveContext = PyResolveContext.noImplicits().withTypeEvalContext(context);
        qualifier = ContainerUtil.getLastItem(location.followAssignmentsChain(resolveContext).getQualifiers());
      }
      if (qualifier != null) {
        //noinspection ConstantConditions
        final PyType qualifierType = PyTypeChecker.toNonWeakType(context.getType(qualifier), context);
        if (isInstanceType(qualifierType)) {
          return true;
        }
        else if (qualifierType instanceof PyUnionType) {
          for (PyType type : ((PyUnionType)qualifierType).getMembers()) {
            if (isInstanceType(type)) {
              return true;
            }
          }
        }
      }
    }
    return false;
  }

  private static boolean isInstanceType(@Nullable PyType type) {
    return type instanceof PyClassType && !((PyClassType)type).isDefinition();
  }

  @Override
  public String getName() {
    return "function";
  }

  @Override
  public boolean isBuiltin() {
    return false;
  }

  @Override
  public void assertValid(String message) {
  }

  @Override
  @NotNull
  public PyCallable getCallable() {
    return myCallable;
  }

  @Nullable
  public static String getParameterName(@NotNull PyNamedParameter namedParameter) {
    String name = namedParameter.getName();
    if (namedParameter.isPositionalContainer()) {
      name = "*" + name;
    }
    else if (namedParameter.isKeywordContainer()) {
      name = "**" + name;
    }
    return name;
  }
}
