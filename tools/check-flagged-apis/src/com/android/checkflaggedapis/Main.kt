/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
@file:JvmName("Main")

package com.android.checkflaggedapis

import android.aconfig.Aconfig
import com.android.tools.metalava.model.BaseItemVisitor
import com.android.tools.metalava.model.ClassItem
import com.android.tools.metalava.model.FieldItem
import com.android.tools.metalava.model.Item
import com.android.tools.metalava.model.MethodItem
import com.android.tools.metalava.model.text.ApiFile
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Node

/**
 * Class representing the fully qualified name of a class, method or field.
 *
 * This tool reads a multitude of input formats all of which represents the fully qualified path to
 * a Java symbol slightly differently. To keep things consistent, all parsed APIs are converted to
 * Symbols.
 *
 * All parts of the fully qualified name of the Symbol are separated by a dot, e.g.:
 * <pre>
 *   package.class.inner-class.field
 * </pre>
 */
@JvmInline
internal value class Symbol(val name: String) {
  companion object {
    private val FORBIDDEN_CHARS = listOf('/', '#', '$')

    /** Create a new Symbol from a String that may include delimiters other than dot. */
    fun create(name: String): Symbol {
      var sanitizedName = name
      for (ch in FORBIDDEN_CHARS) {
        sanitizedName = sanitizedName.replace(ch, '.')
      }
      return Symbol(sanitizedName)
    }
  }

  init {
    require(!name.isEmpty()) { "empty string" }
    for (ch in FORBIDDEN_CHARS) {
      require(!name.contains(ch)) { "$name: contains $ch" }
    }
  }

  override fun toString(): String = name.toString()
}

/**
 * Class representing the fully qualified name of an aconfig flag.
 *
 * This includes both the flag's package and name, separated by a dot, e.g.:
 * <pre>
 *   com.android.aconfig.test.disabled_ro
 * <pre>
 */
@JvmInline
internal value class Flag(val name: String) {
  override fun toString(): String = name.toString()
}

internal sealed class ApiError {
  abstract val symbol: Symbol
  abstract val flag: Flag
}

internal data class EnabledFlaggedApiNotPresentError(
    override val symbol: Symbol,
    override val flag: Flag
) : ApiError() {
  override fun toString(): String {
    return "error: enabled @FlaggedApi not present in built artifact: symbol=$symbol flag=$flag"
  }
}

internal data class DisabledFlaggedApiIsPresentError(
    override val symbol: Symbol,
    override val flag: Flag
) : ApiError() {
  override fun toString(): String {
    return "error: disabled @FlaggedApi is present in built artifact: symbol=$symbol flag=$flag"
  }
}

internal data class UnknownFlagError(override val symbol: Symbol, override val flag: Flag) :
    ApiError() {
  override fun toString(): String {
    return "error: unknown flag: symbol=$symbol flag=$flag"
  }
}

class CheckCommand :
    CliktCommand(
        help =
            """
Check that all flagged APIs are used in the correct way.

This tool reads the API signature file and checks that all flagged APIs are used in the correct way.

The tool will exit with a non-zero exit code if any flagged APIs are found to be used in the incorrect way.
""") {
  private val apiSignaturePath by
      option("--api-signature")
          .help(
              """
              Path to API signature file.
              Usually named *current.txt.
              Tip: `m frameworks-base-api-current.txt` will generate a file that includes all platform and mainline APIs.
              """)
          .path(mustExist = true, canBeDir = false, mustBeReadable = true)
          .required()
  private val flagValuesPath by
      option("--flag-values")
          .help(
              """
            Path to aconfig parsed_flags binary proto file.
            Tip: `m all_aconfig_declarations` will generate a file that includes all information about all flags.
            """)
          .path(mustExist = true, canBeDir = false, mustBeReadable = true)
          .required()
  private val apiVersionsPath by
      option("--api-versions")
          .help(
              """
            Path to API versions XML file.
            Usually named xml-versions.xml.
            Tip: `m sdk dist` will generate a file that includes all platform and mainline APIs.
            """)
          .path(mustExist = true, canBeDir = false, mustBeReadable = true)
          .required()

  override fun run() {
    val flaggedSymbols =
        apiSignaturePath.toFile().inputStream().use {
          parseApiSignature(apiSignaturePath.toString(), it)
        }
    val flags = flagValuesPath.toFile().inputStream().use { parseFlagValues(it) }
    val exportedSymbols = apiVersionsPath.toFile().inputStream().use { parseApiVersions(it) }
    val errors = findErrors(flaggedSymbols, flags, exportedSymbols)
    for (e in errors) {
      println(e)
    }
    throw ProgramResult(errors.size)
  }
}

internal fun parseApiSignature(path: String, input: InputStream): Set<Pair<Symbol, Flag>> {
  // TODO(334870672): add support for metods
  val output = mutableSetOf<Pair<Symbol, Flag>>()
  val visitor =
      object : BaseItemVisitor() {
        override fun visitClass(cls: ClassItem) {
          getFlagOrNull(cls)?.let { flag ->
            val symbol = Symbol.create(cls.baselineElementId())
            output.add(Pair(symbol, flag))
          }
        }

        override fun visitField(field: FieldItem) {
          getFlagOrNull(field)?.let { flag ->
            val symbol = Symbol.create(field.baselineElementId())
            output.add(Pair(symbol, flag))
          }
        }

        override fun visitMethod(method: MethodItem) {
          getFlagOrNull(method)?.let { flag ->
            val name = buildString {
              append(method.containingClass().qualifiedName())
              append(".")
              append(method.name())
              append("(")
              // TODO(334870672): replace this early return with proper parsing of the command line
              // arguments, followed by translation to Lname/of/class; + III format
              if (!method.parameters().isEmpty()) {
                return
              }
              append(")")
            }
            val symbol = Symbol.create(name)
            output.add(Pair(symbol, flag))
          }
        }

        private fun getFlagOrNull(item: Item): Flag? {
          return item.modifiers
              .findAnnotation("android.annotation.FlaggedApi")
              ?.findAttribute("value")
              ?.value
              ?.let { Flag(it.value() as String) }
        }
      }
  val codebase = ApiFile.parseApi(path, input)
  codebase.accept(visitor)
  return output
}

internal fun parseFlagValues(input: InputStream): Map<Flag, Boolean> {
  val parsedFlags = Aconfig.parsed_flags.parseFrom(input).getParsedFlagList()
  return parsedFlags.associateBy(
      { Flag("${it.getPackage()}.${it.getName()}") },
      { it.getState() == Aconfig.flag_state.ENABLED })
}

internal fun parseApiVersions(input: InputStream): Set<Symbol> {
  fun Node.getAttribute(name: String): String? = getAttributes()?.getNamedItem(name)?.getNodeValue()

  val output = mutableSetOf<Symbol>()
  val factory = DocumentBuilderFactory.newInstance()
  val parser = factory.newDocumentBuilder()
  val document = parser.parse(input)

  val classes = document.getElementsByTagName("class")
  // ktfmt doesn't understand the `..<` range syntax; explicitly call .rangeUntil instead
  for (i in 0.rangeUntil(classes.getLength())) {
    val cls = classes.item(i)
    val className =
        requireNotNull(cls.getAttribute("name")) {
          "Bad XML: <class> element without name attribute"
        }
    output.add(Symbol.create(className))
  }

  val fields = document.getElementsByTagName("field")
  // ktfmt doesn't understand the `..<` range syntax; explicitly call .rangeUntil instead
  for (i in 0.rangeUntil(fields.getLength())) {
    val field = fields.item(i)
    val fieldName =
        requireNotNull(field.getAttribute("name")) {
          "Bad XML: <field> element without name attribute"
        }
    val className =
        requireNotNull(field.getParentNode()) { "Bad XML: top level <field> element" }
            .getAttribute("name")
    output.add(Symbol.create("$className.$fieldName"))
  }

  val methods = document.getElementsByTagName("method")
  // ktfmt doesn't understand the `..<` range syntax; explicitly call .rangeUntil instead
  for (i in 0.rangeUntil(methods.getLength())) {
    val method = methods.item(i)
    val methodSignature =
        requireNotNull(method.getAttribute("name")) {
          "Bad XML: <method> element without name attribute"
        }
    val methodSignatureParts = methodSignature.split(Regex("\\(|\\)"))
    if (methodSignatureParts.size != 3) {
      throw Exception("Bad XML: method signature '$methodSignature': debug $methodSignatureParts")
    }
    var (methodName, methodArgs, methodReturnValue) = methodSignatureParts
    val packageAndClassName =
        requireNotNull(method.getParentNode()?.getAttribute("name")) {
          "Bad XML: top level <method> element, or <class> element missing name attribute"
        }
    if (methodName == "<init>") {
      methodName = packageAndClassName.split("/").last()
    }
    output.add(Symbol.create("$packageAndClassName.$methodName($methodArgs)"))
  }

  return output
}

/**
 * Find errors in the given data.
 *
 * @param flaggedSymbolsInSource the set of symbols that are flagged in the source code
 * @param flags the set of flags and their values
 * @param symbolsInOutput the set of symbols that are present in the output
 * @return the set of errors found
 */
internal fun findErrors(
    flaggedSymbolsInSource: Set<Pair<Symbol, Flag>>,
    flags: Map<Flag, Boolean>,
    symbolsInOutput: Set<Symbol>
): Set<ApiError> {
  val errors = mutableSetOf<ApiError>()
  for ((symbol, flag) in flaggedSymbolsInSource) {
    try {
      if (flags.getValue(flag)) {
        if (!symbolsInOutput.contains(symbol)) {
          errors.add(EnabledFlaggedApiNotPresentError(symbol, flag))
        }
      } else {
        if (symbolsInOutput.contains(symbol)) {
          errors.add(DisabledFlaggedApiIsPresentError(symbol, flag))
        }
      }
    } catch (e: NoSuchElementException) {
      errors.add(UnknownFlagError(symbol, flag))
    }
  }
  return errors
}

fun main(args: Array<String>) = CheckCommand().main(args)
