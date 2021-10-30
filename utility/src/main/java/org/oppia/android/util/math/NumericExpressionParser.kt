package org.oppia.android.util.math

import org.oppia.android.app.model.MathBinaryOperation
import org.oppia.android.app.model.MathExpression
import org.oppia.android.app.model.MathFunctionCall
import org.oppia.android.app.model.MathUnaryOperation
import org.oppia.android.app.model.Real
import org.oppia.android.util.math.MathTokenizer2.Companion.Token
import org.oppia.android.util.math.MathTokenizer2.Companion.Token.DivideSymbol
import org.oppia.android.util.math.MathTokenizer2.Companion.Token.ExponentiationSymbol
import org.oppia.android.util.math.MathTokenizer2.Companion.Token.FunctionName
import org.oppia.android.util.math.MathTokenizer2.Companion.Token.LeftParenthesisSymbol
import org.oppia.android.util.math.MathTokenizer2.Companion.Token.MinusSymbol
import org.oppia.android.util.math.MathTokenizer2.Companion.Token.MultiplySymbol
import org.oppia.android.util.math.MathTokenizer2.Companion.Token.PlusSymbol
import org.oppia.android.util.math.MathTokenizer2.Companion.Token.PositiveInteger
import org.oppia.android.util.math.MathTokenizer2.Companion.Token.PositiveRealNumber
import org.oppia.android.util.math.MathTokenizer2.Companion.Token.RightParenthesisSymbol
import org.oppia.android.util.math.MathTokenizer2.Companion.Token.SquareRootSymbol

class NumericExpressionParser(private val rawExpression: String) {
  private val tokens: PeekableIterator<Token> by lazy {
    PeekableIterator.fromSequence(MathTokenizer2.tokenize(rawExpression))
  }

  fun parse(): MathExpression {
    return parseNumericExpression().also {
      // Make sure all tokens were consumed (otherwise there are trailing tokens which invalidate the
      // whole expression).
      if (tokens.hasNext()) throw ParseException()
    }
  }

  private fun parseNumericExpression(): MathExpression {
    // numeric_expression = numeric_add_sub_expression ;
    return parseNumericAddSubExpression()
  }

  // TODO: consider consolidating this with other binary parsing to reduce the overall parser.
  private fun parseNumericAddSubExpression(): MathExpression {
    // numeric_add_sub_expression =
    //     numeric_mult_div_expression , { numeric_add_sub_expression_rhs } ;
    var lastLhs = parseNumericMultDivExpression()
    while (hasNextNumericAddSubExpressionRhs()) {
      // numeric_add_sub_expression_rhs =
      //     numeric_add_expression_rhs | numeric_sub_expression_rhs ;
      val (operator, rhs) = when (tokens.peek()) {
        is PlusSymbol -> MathBinaryOperation.Operator.ADD to parseNumericAddExpressionRhs()
        is MinusSymbol -> MathBinaryOperation.Operator.SUBTRACT to parseNumericSubExpressionRhs()
        else -> throw ParseException()
      }

      // Compute the next LHS if there is further addition/subtraction.
      lastLhs = MathExpression.newBuilder().apply {
        binaryOperation = MathBinaryOperation.newBuilder().apply {
          this.operator = operator
          leftOperand = lastLhs
          rightOperand = rhs
        }.build()
      }.build()
    }
    return lastLhs
  }

  private fun hasNextNumericAddSubExpressionRhs() = when (tokens.peek()) {
    is PlusSymbol, is MinusSymbol -> true
    else -> false
  }

  private fun parseNumericAddExpressionRhs(): MathExpression {
    // numeric_add_expression_rhs = plus_operator , numeric_mult_div_expression ;
    consumeTokenOfType { PlusSymbol }
    return parseNumericMultDivExpression()
  }

  private fun parseNumericSubExpressionRhs(): MathExpression {
    // numeric_sub_expression_rhs = minus_operator , numeric_mult_div_expression ;
    consumeTokenOfType { MinusSymbol }
    return parseNumericMultDivExpression()
  }

  private fun parseNumericMultDivExpression(): MathExpression {
    // numeric_mult_div_expression =
    //     numeric_exp_expression , { numeric_mult_div_expression_rhs } ;
    var lastLhs = parseNumericExpExpression()
    while (hasNextNumericMultDivExpressionRhs()) {
      // numeric_mult_div_expression_rhs =
      //     numeric_mult_expression_rhs | numeric_div_expression_rhs ;
      val (operator, rhs) = when (tokens.peek()) {
        is MultiplySymbol ->
          MathBinaryOperation.Operator.MULTIPLY to parseNumericMultExpressionRhs()
        is DivideSymbol -> MathBinaryOperation.Operator.DIVIDE to parseNumericDivExpressionRhs()
        else -> throw ParseException()
      }

      // Compute the next LHS if there is further multiplication/division.
      lastLhs = MathExpression.newBuilder().apply {
        binaryOperation = MathBinaryOperation.newBuilder().apply {
          this.operator = operator
          leftOperand = lastLhs
          rightOperand = rhs
        }.build()
      }.build()
    }
    return lastLhs
  }

  private fun hasNextNumericMultDivExpressionRhs() = when (tokens.peek()) {
    is MultiplySymbol, is DivideSymbol -> true
    else -> false
  }

  private fun parseNumericMultExpressionRhs(): MathExpression {
    // numeric_mult_expression_rhs =
    //     multiplication_operator , numeric_exp_expression ;
    consumeTokenOfType { MultiplySymbol }
    return parseNumericExpExpression()
  }

  private fun parseNumericDivExpressionRhs(): MathExpression {
    // numeric_div_expression_rhs = division_operator , numeric_exp_expression ;
    consumeTokenOfType { DivideSymbol }
    return parseNumericExpExpression()
  }

  private fun parseNumericExpExpression(): MathExpression {
    // numeric_exp_expression = numeric_term , [ numeric_exp_expression_tail ] ;
    val possibleLhs = parseNumericTerm()
    return if (tokens.peek() is ExponentiationSymbol) {
      parseNumericExpExpressionTail(possibleLhs)
    } else possibleLhs
  }

  // Use tail recursion so that the last exponentiation is evaluated first, and right-to-left
  // associativity can be kept via backtracking.
  private fun parseNumericExpExpressionTail(lhs: MathExpression): MathExpression {
    // numeric_exp_expression_tail =
    //     exponentiation_operator , numeric_exp_expression ;
    consumeTokenOfType { ExponentiationSymbol }
    return MathExpression.newBuilder().apply {
      binaryOperation = MathBinaryOperation.newBuilder().apply {
        operator = MathBinaryOperation.Operator.EXPONENTIATE
        leftOperand = lhs
        rightOperand = parseNumericExpExpression()
      }.build()
    }.build()
  }

  private fun parseNumericTerm(): MathExpression {
    // numeric_term =
    //     implicitly_multipliable_with_number_term | numeric_plus_minus_unary_term ;
    return if (hasNextImplicitlyMultipliableWithNumberTerm()) {
      parseImplicitlyMultipliableWithNumberTerm()
    } else parseNumericPlusMinusUnaryTerm()
  }

  private fun parseImplicitlyMultipliableWithNumberTerm(): MathExpression {
    // implicitly_multipliable_with_number_term =
    //     number_with_implicit_multiplication
    //     | implicitly_multipliable_without_number_term ;
    return when (tokens.peek()) {
      is PositiveInteger, is PositiveRealNumber -> parseNumberWithImplicitMultiplication()
      else -> parseImplicitlyMultipliableWithoutNumberTerm()
    }
  }

  private fun hasNextImplicitlyMultipliableWithNumberTerm(): Boolean = when (tokens.peek()) {
    is PositiveInteger, is PositiveRealNumber, is FunctionName, is LeftParenthesisSymbol,
    is SquareRootSymbol -> true
    else -> false
  }

  private fun parseNumberWithImplicitMultiplication(): MathExpression {
    // number_with_implicit_multiplication =
    //     number , [ implicitly_multipliable_without_number_term ] ;
    // TODO: add specification for implicit multiplication (for reconstruction).
    val realOperand = parseNumber()
    return if (hasNextImplicitlyMultipliableWithoutNumberTerm()) {
      MathExpression.newBuilder().apply {
        binaryOperation = MathBinaryOperation.newBuilder().apply {
          operator = MathBinaryOperation.Operator.MULTIPLY
          leftOperand = realOperand
          rightOperand = parseImplicitlyMultipliableWithoutNumberTerm()
        }.build()
      }.build()
    } else realOperand
  }

  private fun parseImplicitlyMultipliableWithoutNumberTerm(): MathExpression {
    // implicitly_multipliable_without_number_term =
    //     numeric_function_expression_with_implicit_multiplication
    //     | numeric_group_expression_with_implicit_multiplication
    //     | numeric_rooted_term_with_implicit_multiplication ;
    return when (tokens.peek()) {
      is FunctionName -> parseNumericFunctionExpressionWithImplicitMultiplication()
      is LeftParenthesisSymbol -> parseNumericGroupExpressionWithImplicitMultiplication()
      is SquareRootSymbol -> parseNumericRootedTermWithImplicitMultiplication()
      // TODO: add error that one of the above was expected. Other error handling should maybe
      //  happen in the same way.
      else -> throw ParseException()
    }
  }

  private fun hasNextImplicitlyMultipliableWithoutNumberTerm(): Boolean = when (tokens.peek()) {
    is FunctionName, is LeftParenthesisSymbol, SquareRootSymbol -> true
    else -> false
  }

  private fun parseNumber(): MathExpression {
    // number = positive_real_number | positive_integer ;
    return MathExpression.newBuilder().apply {
      constant = when (
        val numberToken = consumeNextTokenMatching {
          it is PositiveInteger || it is PositiveRealNumber
        }
      ) {
        is PositiveInteger -> Real.newBuilder().apply {
          integer = numberToken.parsedValue
        }.build()
        is PositiveRealNumber -> Real.newBuilder().apply {
          irrational = numberToken.parsedValue
        }.build()
        else -> throw ParseException() // Something went wrong.
      }
    }.build()
  }

  // TODO: consider consolidating this with other similar implict mult functions to reduce parser.
  private fun parseNumericFunctionExpressionWithImplicitMultiplication(): MathExpression {
    // numeric_function_expression_with_implicit_multiplication =
    //     numeric_function_expression
    //     , [ implicitly_multipliable_with_number_term ] ;
    val possibleLhs = parseNumericFunctionExpression()
    return if (hasNextImplicitlyMultipliableWithNumberTerm()) {
      MathExpression.newBuilder().apply {
        binaryOperation = MathBinaryOperation.newBuilder().apply {
          operator = MathBinaryOperation.Operator.MULTIPLY
          leftOperand = possibleLhs
          rightOperand = parseImplicitlyMultipliableWithNumberTerm()
        }.build()
      }.build()
    } else possibleLhs
  }

  private fun parseNumericFunctionExpression(): MathExpression {
    // numeric_function_expression =
    //     function_name , left_paren , numeric_expression , right_paren ;
    return MathExpression.newBuilder().apply {
      val functionName = expectNextTokenWithType<FunctionName>()
      if (functionName.parsedName != "sqrt") throw ParseException()
      consumeTokenOfType { LeftParenthesisSymbol }
      functionCall = MathFunctionCall.newBuilder().apply {
        functionType = MathFunctionCall.FunctionType.SQUARE_ROOT
        argument = parseNumericExpression()
      }.build()
      consumeTokenOfType { RightParenthesisSymbol }
    }.build()
  }

  private fun parseNumericGroupExpressionWithImplicitMultiplication(): MathExpression {
    // numeric_group_expression_with_implicit_multiplication =
    //     numeric_group_expression , [ implicitly_multipliable_with_number_term ] ;
    val possibleLhs = parseNumericGroupExpression()
    return if (hasNextImplicitlyMultipliableWithNumberTerm()) {
      MathExpression.newBuilder().apply {
        binaryOperation = MathBinaryOperation.newBuilder().apply {
          operator = MathBinaryOperation.Operator.MULTIPLY
          leftOperand = possibleLhs
          rightOperand = parseImplicitlyMultipliableWithNumberTerm()
        }.build()
      }.build()
    } else possibleLhs
  }

  private fun parseNumericGroupExpression(): MathExpression {
    // numeric_group_expression = left_paren , numeric_expression , right_paren ;
    consumeTokenOfType { LeftParenthesisSymbol }
    return parseNumericExpression().also {
      consumeTokenOfType { RightParenthesisSymbol }
    }
  }

  private fun parseNumericPlusMinusUnaryTerm(): MathExpression {
    // numeric_plus_minus_unary_term = numeric_negated_term | numeric_positive_term ;
    return if (tokens.peek() is MinusSymbol) {
      parseNumericNegatedTerm()
    } else parseNumericPositiveTerm()
  }

  private fun parseNumericNegatedTerm(): MathExpression {
    // numeric_negated_term = minus_operator , numeric_term ;
    consumeTokenOfType { MinusSymbol }
    return MathExpression.newBuilder().apply {
      unaryOperation = MathUnaryOperation.newBuilder().apply {
        operator = MathUnaryOperation.Operator.NEGATE
        operand = parseNumericTerm()
      }.build()
    }.build()
  }

  private fun parseNumericPositiveTerm(): MathExpression {
    // numeric_positive_term = plus_operator , numeric_term ;
    consumeTokenOfType { PlusSymbol }
    return MathExpression.newBuilder().apply {
      unaryOperation = MathUnaryOperation.newBuilder().apply {
        operator = MathUnaryOperation.Operator.POSITIVE
        operand = parseNumericTerm()
      }.build()
    }.build()
  }

  private fun parseNumericRootedTermWithImplicitMultiplication(): MathExpression {
    // numeric_rooted_term_with_implicit_multiplication =
    //     numeric_rooted_term , [ implicitly_multipliable_with_number_term ] ;
    val possibleLhs = parseNumericRootedTerm()
    return if (hasNextImplicitlyMultipliableWithNumberTerm()) {
      MathExpression.newBuilder().apply {
        binaryOperation = MathBinaryOperation.newBuilder().apply {
          operator = MathBinaryOperation.Operator.MULTIPLY
          leftOperand = possibleLhs
          rightOperand = parseImplicitlyMultipliableWithNumberTerm()
        }.build()
      }.build()
    } else possibleLhs
  }

  private fun parseNumericRootedTerm(): MathExpression {
    // numeric_rooted_term = square_root_operator , numeric_term ;
    consumeTokenOfType { SquareRootSymbol }
    return MathExpression.newBuilder().apply {
      functionCall = MathFunctionCall.newBuilder().apply {
        functionType = MathFunctionCall.FunctionType.SQUARE_ROOT
        argument = parseNumericTerm()
      }.build()
    }.build()
  }

  private inline fun <reified T : Token> expectNextTokenWithType(): T {
    return (tokens.next() as? T) ?: throw ParseException()
  }

  private inline fun <reified T : Token> consumeTokenOfType(noinline expected: () -> T): T {
    return (tokens.expectNextValue(expected) as? T) ?: throw ParseException()
  }

  private fun consumeNextTokenMatching(predicate: (Token) -> Boolean): Token {
    return tokens.expectNextMatches(predicate) ?: throw ParseException()
  }

  // TODO: do error handling better than this (& in a way that works better with the types of errors
  //  that we want to show users).
  class ParseException : Exception()
}