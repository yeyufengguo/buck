/*
 * Portions Copyright (c) Facebook, Inc. and its affiliates.
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

// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.facebook.buck.core.starlark.compatible;

import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.EvalUtils;
import com.google.devtools.build.lib.syntax.FileOptions;
import com.google.devtools.build.lib.syntax.Module;
import com.google.devtools.build.lib.syntax.Mutability;
import com.google.devtools.build.lib.syntax.NoneType;
import com.google.devtools.build.lib.syntax.ParserInput;
import com.google.devtools.build.lib.syntax.Starlark;
import com.google.devtools.build.lib.syntax.StarlarkList;
import com.google.devtools.build.lib.syntax.StarlarkSemantics;
import com.google.devtools.build.lib.syntax.StarlarkThread;
import com.google.devtools.build.lib.syntax.SyntaxError;
import com.google.devtools.build.lib.syntax.Tuple;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import net.starlark.java.annot.Param;

/** A value class for storing {@link Param} metadata to avoid using Java proxies. */
final class ParamDescriptor {

  private final String name;
  @Nullable private final Object defaultValue;
  private final boolean noneable;
  private final boolean named;
  private final boolean positional;
  private final List<Class<?>> allowedClasses; // non-empty

  private ParamDescriptor(
      String name,
      String defaultExpr,
      boolean noneable,
      boolean named,
      boolean positional,
      List<Class<?>> allowedClasses) {
    this.name = name;
    this.defaultValue = defaultExpr.isEmpty() ? null : evalDefault(name, defaultExpr);
    this.noneable = noneable;
    this.named = named;
    this.positional = positional;
    this.allowedClasses = allowedClasses;
  }

  /**
   * Returns a {@link ParamDescriptor} representing the given raw {@link Param} annotation and the
   * given semantics.
   */
  static ParamDescriptor of(BuckStarlarkParam param) {
    String defaultExpr = param.defaultValue();

    // Compute set of allowed classes.
    List<Class<?>> allowedClasses = new ArrayList<>();
    allowedClasses.add(param.type());
    if (param.noneable()) {
      // A few annotations redundantly declare NoneType.
      if (!allowedClasses.contains(NoneType.class)) {
        allowedClasses.add(NoneType.class);
      }
    }

    return new ParamDescriptor(
        param.name(), defaultExpr, param.noneable(), param.named(), true, allowedClasses);
  }

  /** @see Param#name() */
  public String getName() {
    return name;
  }

  /** Returns a description of allowed argument types suitable for an error message. */
  public String getTypeErrorMessage() {
    return allowedClasses.stream().map(Starlark::classType).collect(Collectors.joining(" or "));
  }

  public List<Class<?>> getAllowedClasses() {
    return allowedClasses;
  }

  /** @see Param#noneable() */
  public boolean isNoneable() {
    return noneable;
  }

  /** @see Param#positional() */
  public boolean isPositional() {
    return positional;
  }

  /** @see Param#named() */
  public boolean isNamed() {
    return named;
  }

  /** Returns the effective default value of this parameter, or null if mandatory. */
  @Nullable
  public Object getDefaultValue() {
    return defaultValue;
  }

  // A memoization of evalDefault, keyed by expression.
  // This cache is manually maintained (instead of using LoadingCache),
  // as default values may sometimes be recursively requested.
  private static final ConcurrentHashMap<String, Object> defaultValueCache =
      new ConcurrentHashMap<>();

  // Evaluates the default value expression for a parameter.
  private static Object evalDefault(String name, String expr) {
    // Values required by defaults of functions in UNIVERSE must
    // be handled without depending on the evaluator, or even
    // on defaultValueCache, because JVM global variable initialization
    // is such a mess. (Specifically, it's completely dynamic,
    // so if two or more variables are mutually dependent, like
    // defaultValueCache and UNIVERSE would be, you have to write
    // code that works in all possible dynamic initialization orders.)
    // Better not to go there.
    if (expr.equals("None")) {
      return Starlark.NONE;
    } else if (expr.equals("True")) {
      return true;
    } else if (expr.equals("False")) {
      return false;
    } else if (expr.equals("unbound")) {
      return Starlark.UNBOUND;
    } else if (expr.equals("0")) {
      return 0;
    } else if (expr.equals("1")) {
      return 1;
    } else if (expr.equals("[]")) {
      return StarlarkList.empty();
    } else if (expr.equals("()")) {
      return Tuple.empty();
    } else if (expr.equals("\" \"")) {
      return " ";
    }

    Object x = defaultValueCache.get(expr);
    if (x != null) {
      return x;
    }

    // We can't evaluate Starlark code until UNIVERSE is bootstrapped.
    if (Starlark.UNIVERSE == null) {
      throw new IllegalStateException("no bootstrap value for " + name + "=" + expr);
    }

    Module module = Module.create();
    try (Mutability mu = Mutability.create("Builtin param default init")) {
      // Note that this Starlark thread ignores command line flags.
      StarlarkThread thread = new StarlarkThread(mu, StarlarkSemantics.DEFAULT);

      x = EvalUtils.eval(ParserInput.fromLines(expr), FileOptions.DEFAULT, module, thread);
    } catch (InterruptedException ex) {
      throw new IllegalStateException(ex); // can't happen
    } catch (SyntaxError.Exception | EvalException ex) {
      throw new IllegalArgumentException(
          String.format(
              "failed to evaluate default value '%s' of parameter '%s': %s",
              expr, name, ex.getMessage()),
          ex);
    }
    defaultValueCache.put(expr, x);
    return x;
  }
}
