/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.core.starlark.compatible;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.syntax.Dict;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.Location;
import com.google.devtools.build.lib.syntax.Printer;
import com.google.devtools.build.lib.syntax.Starlark;
import com.google.devtools.build.lib.syntax.StarlarkCallable;
import com.google.devtools.build.lib.syntax.StarlarkSemantics;
import com.google.devtools.build.lib.syntax.StarlarkThread;
import com.google.devtools.build.lib.syntax.Tuple;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.WrongMethodTypeException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.spelling.SpellChecker;

/**
 * Marker class that makes some method exposable to skylark.
 *
 * <p>This class currently doesn't handle optionals and other java/skylark object coercing.
 */
public abstract class BuckStarlarkFunction implements StarlarkCallable {

  private final MethodDescriptor methodDescriptor;
  private static final MethodHandles.Lookup lookup = MethodHandles.lookup();
  private final MethodHandle method;

  /**
   * Creates a new skylark callable function of the given name that invokes the method handle. The
   * named parameters for skylark is the list of namedParams, which is mapped in order to the end of
   * the parameter list for the method handle.
   *
   * @param methodName the function name exposed to skylark
   * @param constructor the constructor that we will call as a method
   * @param namedParams a list of named parameters for skylark. The names are mapped in order to the
   *     parameters of {@code constructor}
   * @param defaultSkylarkValues a list of default values for parameters in skylark. The names are
   *     mapped in order to the parameters of {@code constructor}
   */
  public BuckStarlarkFunction(
      String methodName,
      Constructor<?> constructor,
      List<String> namedParams,
      List<String> defaultSkylarkValues,
      Set<String> noneableParams) {
    try {
      this.method = lookup.unreflectConstructor(constructor);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException("Unable to access the supplied constructor", e);
    }
    this.methodDescriptor =
        inferMethodDescriptor(
            methodName, method, namedParams, defaultSkylarkValues, noneableParams);
  }

  /**
   * Creates a new skylark callable function of the given name that invokes the method handle. The
   * named parameters for skylark is the list of namedParams, which is mapped in order to the end of
   * the parameter list for the method handle.
   *
   * @param methodName the function name exposed to skylark
   * @param method a method that will eventually be called in {@link #fastcall(StarlarkThread,
   *     Object[], Object[])}
   * @param namedParams a list of named parameters for skylark. The names are mapped in order to the
   *     parameters of {@code method}
   * @param defaultSkylarkValues a list of default values for parameters in skylark. The values are
   *     mapped in order to the parameters of {@code method}
   */
  public BuckStarlarkFunction(
      String methodName,
      Method method,
      List<String> namedParams,
      List<String> defaultSkylarkValues,
      Set<String> noneableParams) {
    try {
      this.method = lookup.unreflect(method);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException("Unable to access the supplied method", e);
    }
    this.methodDescriptor =
        inferMethodDescriptor(
            methodName, this.method, namedParams, defaultSkylarkValues, noneableParams);
  }

  /**
   * Creates a new skylark callable function of the given name that invokes the method handle. The
   * named parameters for skylark is the list of namedParams, which is mapped in order to the end of
   * the parameter list for the method handle.
   *
   * @param methodName the function name exposed to skylark, which will be looked up via reflection
   * @param namedParams a list of named parameters for skylark. The names are mapped in order to the
   *     end of the parameters of the actual method.
   */
  @VisibleForTesting
  BuckStarlarkFunction(
      String methodName,
      ImmutableList<String> namedParams,
      ImmutableList<String> defaultSkylarkValues,
      Set<String> noneableParams)
      throws Throwable {
    this.method = lookup.unreflect(findMethod(methodName)).bindTo(this);
    this.methodDescriptor =
        inferMethodDescriptor(
            methodName, method, namedParams, defaultSkylarkValues, noneableParams);
  }

  /**
   * we infer a "fake" MethodDescriptor to be able to piggy back off some args and type processing
   * in skylark
   */
  private MethodDescriptor inferMethodDescriptor(
      String methodName,
      MethodHandle method,
      List<String> namedParams,
      List<String> defaultSkylarkValues,
      Set<String> noneableParams) {

    try {
      return MethodDescriptor.of(
          BuckStarlarkFunction.class.getDeclaredMethod(
              "fake"), /* we hand a fake reflective method since we only use the MethodDescriptor to
                       piggy back off skylark's parameter handling. We don't actually have a
                       Method object to use in many cases (e.g. if the MethodHandle is a
                       constructor). */
          inferSkylarkCallableAnnotationFromMethod(
              methodName, method, namedParams, defaultSkylarkValues, noneableParams));
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException();
    }
  }

  @Override
  public Object fastcall(StarlarkThread thread, Object[] positional, Object[] named)
      throws EvalException, InterruptedException {
    // this is the effectively the same as bazel's {@BuiltInCallable}
    Object[] javaArguments = getArgumentVector(thread, methodDescriptor, positional, named);

    // TODO: deal with Optionals and some java/skylark object coercing
    ImmutableList<Object> argsForReflectionBuilder = ImmutableList.copyOf(javaArguments);

    // The below is adapted from bazel's MethodDescriptor.call, but for method handles
    try {
      Object result = method.invokeWithArguments(argsForReflectionBuilder);
      if (method.type().returnType().equals(Void.TYPE)) {
        return Starlark.NONE;
      }
      if (result == null) {
        if (methodDescriptor.isAllowReturnNones()) {
          return Starlark.NONE;
        } else {
          throw new EvalException(
              "method invocation returned None, please file a bug report: " + getName() + "(...)");
        }
      }
      if (!Starlark.valid(result)) {
        throw new EvalException(
            String.format(
                "method '%s' returns an object of invalid type %s",
                getName(), result.getClass().getName()));
      }
      return result;
    } catch (WrongMethodTypeException e) {
      throw new EvalException("Method invocation failed: " + e);
    } catch (Throwable e) {
      if (e.getCause() instanceof EvalException) {
        throw (EvalException) e.getCause();
      } else if (e.getCause() != null) {
        Throwables.throwIfInstanceOf(e.getCause(), InterruptedException.class);
        throw new EvalException(null, "method invocation failed: " + e, e.getCause());
      } else {
        // This is unlikely to happen
        throw new EvalException("method invocation failed: " + e);
      }
    }
  }

  @Override
  public void repr(Printer printer) {
    printer.append("<built-in function " + methodDescriptor.getName() + ">");
  }

  private Method findMethod(String methodName) {
    @Nullable Method result = null;
    for (Method m : getClass().getDeclaredMethods()) {
      if (m.getName().equals(methodName) && Modifier.isPublic(m.getModifiers())) {
        if (result != null) {
          throw new IllegalArgumentException(
              String.format(
                  "%s contains more than one public %s(...) method", getClass(), methodName));
        }
        result = m;
      }
    }
    if (result == null) {
      throw new IllegalArgumentException(
          String.format("%s does not contain a public %s(...) method", getClass(), methodName));
    }
    result.setAccessible(true); // by pass security checks
    return result;
  }

  @Override
  public Location getLocation() {
    return Location.BUILTIN;
  }

  @Override
  public String getName() {
    return getMethodDescriptor().getName();
  }

  @VisibleForTesting
  MethodDescriptor getMethodDescriptor() {
    return methodDescriptor;
  }

  private StarlarkMethod inferSkylarkCallableAnnotationFromMethod(
      String methodName,
      MethodHandle method,
      List<String> namedParams,
      List<String> defaultSkylarkValues,
      Set<String> noneableParams) {
    return BuckStarlarkCallable.fromMethod(
        methodName, method, namedParams, defaultSkylarkValues, noneableParams);
  }

  // a fake method to hand to the MethodDescriptor that this uses.
  @SuppressWarnings("unused")
  private void fake() {}

  private Object[] getArgumentVector(
      StarlarkThread thread,
      MethodDescriptor desc, // intentionally shadows this.desc
      Object[] positional,
      Object[] named)
      throws EvalException {

    // Overview of steps:
    // - allocate vector of actual arguments of correct size.
    // - process positional arguments, accumulating surplus ones into *args.
    // - process named arguments, accumulating surplus ones into **kwargs.
    // - set default values for missing optionals, and report missing mandatory parameters.
    // - set special parameters.
    // The static checks ensure that positional parameters appear before named,
    // and mandatory positionals appear before optional.
    // No additional memory allocation occurs in the common (success) case.
    // Flag-disabled parameters are skipped during argument matching, as if they do not exist. They
    // are instead assigned their flag-disabled values.

    ParamDescriptor[] parameters = desc.getParameters();

    // Allocate argument vector.
    int n = parameters.length;
    if (desc.acceptsExtraArgs()) {
      n++;
    }
    if (desc.acceptsExtraKwargs()) {
      n++;
    }
    if (desc.isUseStarlarkThread()) {
      n++;
    }
    Object[] vector = new Object[n];

    // positional arguments
    int paramIndex = 0;
    int argIndex = 0;
    for (; argIndex < positional.length && paramIndex < parameters.length; paramIndex++) {
      ParamDescriptor param = parameters[paramIndex];
      if (!param.isPositional()) {
        break;
      }

      // disabled?
      if (param.disabledByFlag() != null) {
        // Skip disabled parameter as if not present at all.
        // The default value will be filled in below.
        continue;
      }

      Object value = positional[argIndex++];
      checkParamValue(param, value);
      vector[paramIndex] = value;
    }

    // *args
    Tuple<Object> varargs = null;
    if (desc.acceptsExtraArgs()) {
      varargs = Tuple.wrap(Arrays.copyOfRange(positional, argIndex, positional.length));
    } else if (argIndex < positional.length) {
      if (argIndex == 0) {
        throw Starlark.errorf("%s() got unexpected positional argument", getName());
      } else {
        throw Starlark.errorf(
            "%s() accepts no more than %d positional argument%s but got %d",
            getName(), argIndex, plural(argIndex), positional.length);
      }
    }

    // named arguments
    LinkedHashMap<String, Object> kwargs = desc.acceptsExtraKwargs() ? new LinkedHashMap<>() : null;
    for (int i = 0; i < named.length; i += 2) {
      String name = (String) named[i]; // safe
      Object value = named[i + 1];

      // look up parameter
      int index = desc.getParameterIndex(name);
      // unknown parameter?
      if (index < 0) {
        // spill to **kwargs
        if (kwargs == null) {
          List<String> allNames =
              Arrays.stream(parameters)
                  .map(ParamDescriptor::getName)
                  .collect(ImmutableList.toImmutableList());
          throw Starlark.errorf(
              "%s() got unexpected keyword argument '%s'%s",
              getName(), name, SpellChecker.didYouMean(name, allNames));
        }

        // duplicate named argument?
        if (kwargs.put(name, value) != null) {
          throw Starlark.errorf(
              "%s() got multiple values for keyword argument '%s'", getName(), name);
        }
        continue;
      }
      ParamDescriptor param = parameters[index];

      // positional-only param?
      if (!param.isNamed()) {
        // spill to **kwargs
        if (kwargs == null) {
          throw Starlark.errorf(
              "%s() got named argument for positional-only parameter '%s'", getName(), name);
        }

        // duplicate named argument?
        if (kwargs.put(name, value) != null) {
          throw Starlark.errorf(
              "%s() got multiple values for keyword argument '%s'", getName(), name);
        }
        continue;
      }

      // disabled?
      String flag = param.disabledByFlag();
      if (flag != null) {
        // spill to **kwargs
        if (kwargs == null) {
          throw Starlark.errorf(
              "in call to %s(), parameter '%s' is %s",
              getName(), param.getName(), disabled(flag, thread.getSemantics()));
        }

        // duplicate named argument?
        if (kwargs.put(name, value) != null) {
          throw Starlark.errorf(
              "%s() got multiple values for keyword argument '%s'", getName(), name);
        }
        continue;
      }

      checkParamValue(param, value);

      // duplicate?
      if (vector[index] != null) {
        throw Starlark.errorf("%s() got multiple values for argument '%s'", getName(), name);
      }

      vector[index] = value;
    }

    // Set default values for missing parameters,
    // and report any that are still missing.
    List<String> missingPositional = null;
    List<String> missingNamed = null;
    for (int i = 0; i < parameters.length; i++) {
      if (vector[i] == null) {
        ParamDescriptor param = parameters[i];
        vector[i] = param.getDefaultValue();
        if (vector[i] == null) {
          if (param.isPositional()) {
            if (missingPositional == null) {
              missingPositional = new ArrayList<>();
            }
            missingPositional.add(param.getName());
          } else {
            if (missingNamed == null) {
              missingNamed = new ArrayList<>();
            }
            missingNamed.add(param.getName());
          }
        }
      }
    }
    if (missingPositional != null) {
      throw Starlark.errorf(
          "%s() missing %d required positional argument%s: %s",
          getName(),
          missingPositional.size(),
          plural(missingPositional.size()),
          Joiner.on(", ").join(missingPositional));
    }
    if (missingNamed != null) {
      throw Starlark.errorf(
          "%s() missing %d required named argument%s: %s",
          getName(),
          missingNamed.size(),
          plural(missingNamed.size()),
          Joiner.on(", ").join(missingNamed));
    }

    // special parameters
    int i = parameters.length;
    if (desc.acceptsExtraArgs()) {
      vector[i++] = varargs;
    }
    if (desc.acceptsExtraKwargs()) {
      vector[i++] = Dict.wrap(thread.mutability(), kwargs);
    }
    if (desc.isUseStarlarkThread()) {
      vector[i++] = thread;
    }

    return vector;
  }

  private void checkParamValue(ParamDescriptor param, Object value) throws EvalException {
    // Value must belong to one of the specified classes.
    boolean ok = false;
    for (Class<?> cls : param.getAllowedClasses()) {
      if (cls.isInstance(value)) {
        ok = true;
        break;
      }
    }
    if (!ok) {
      throw Starlark.errorf(
          "in call, parameter '%s' got value of type '%s', want '%s'",
          param.getName(), Starlark.type(value), param.getTypeErrorMessage());
    }

    // None is valid if and only if the parameter is marked noneable,
    // in which case the above check passes as the list of classes will include NoneType.
    // The reason for this check is to ensure that merely having type=Object.class
    // does not allow None as an argument value; I'm not sure why, that but that's the
    // historical behavior.
    //
    // We do this check second because the first check prints a better error
    // that enumerates the allowed types.
    if (value == Starlark.NONE && !param.isNoneable()) {
      throw Starlark.errorf("in call, parameter '%s' cannot be None", param.getName());
    }
  }

  private static String plural(int n) {
    return n == 1 ? "" : "s";
  }

  // Returns a phrase meaning "disabled" appropriate to the specified flag.
  private static String disabled(String flag, StarlarkSemantics semantics) {
    // If the flag is True, it must be a deprecation flag. Otherwise it's an experimental flag.
    // TODO(adonovan): is that assumption sound?
    if (semantics.flagValue(flag)) {
      return String.format(
          "deprecated and will be removed soon. It may be temporarily re-enabled by setting"
              + " --%s=false",
          flag);
    } else {
      return String.format(
          "experimental and thus unavailable with the current flags. It may be enabled by setting"
              + " --%s",
          flag);
    }
  }
}
