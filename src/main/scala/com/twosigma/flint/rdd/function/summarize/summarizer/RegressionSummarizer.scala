/*
 *  Copyright 2017 TWO SIGMA OPEN SOURCE, LLC
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.twosigma.flint.rdd.function.summarize.summarizer

import breeze.linalg.{ DenseVector, DenseMatrix }

case class RegressionRow(
  time: Long,
  x: Array[Double],
  y: Double,
  weight: Double
)

object RegressionSummarizer {
  /**
   *  Transform a [[RegressionRow]] into sqrt-weighted predictor, sqrt-weighted response, original response, and
   *  original weight.
   *
   * @param r               A [[RegressionRow]].
   * @param shouldIntercept If true, it will prepend 1.0 to the predictor.
   * @param isWeighted      If true, both of response and predictor will be multiplied by a factor of square root
   *                        of weight for the weighted regression purpose.
   * @return  sqrt-weighted x, sqrt-weighted y,  original y, original weight,
   */
  protected[summarizer] def transform(
    r: RegressionRow,
    shouldIntercept: Boolean,
    isWeighted: Boolean
  ): (Array[Double], Double, (Double, Double)) = {
    val w = if (isWeighted) r.weight else 1.0
    val sqrtW = Math.sqrt(w)
    if (shouldIntercept) {
      // Prepend the 1.0 at the beginning.
      val x = new Array[Double](r.x.length + 1)
      var i = 0
      while (i < r.x.length) {
        x(i + 1) = r.x(i) * sqrtW
        i += 1
      }
      x(0) = sqrtW
      (x, r.y * sqrtW, (r.y, w))
    } else {
      val x = new Array[Double](r.x.length)
      var i = 0
      while (i < r.x.length) {
        x(i) = r.x(i) * sqrtW
        i += 1
      }
      (x, r.y * sqrtW, (r.y, w))
    }
  }

  protected[summarizer] def transform(
    rows: Seq[RegressionRow],
    shouldIntercept: Boolean,
    isWeighted: Boolean
  ): (DenseMatrix[Double], DenseVector[Double], Array[(Double, Double)]) = {
    val (weightedX, weightedY, yw) =
      rows.map(transform(_, shouldIntercept, isWeighted)).unzip3
    (DenseMatrix(weightedX: _*), DenseVector(weightedY.toArray), yw.toArray)
  }

  /**
   * Find the value of the Gaussian log-likelihood function at the data
   * We use the statsmodels' implementation as a reference:
   * [[http://statsmodels.sourceforget.net/stable/_modules/statsmodels/regression/linear_model.html#WLS.loglike loglike]]
   */
  protected[summarizer] def computeLogLikelihood(
    count: Long,
    sumOfLogWeights: Double,
    residualSumOfSquares: Double
  ): Double = {
    val nOver2: Double = count.toDouble / 2.0
    var logLikelihood: Double = -nOver2 * math.log(residualSumOfSquares)
    logLikelihood += -nOver2 * (1.0 + math.log(math.Pi / nOver2))
    logLikelihood += 0.5 * sumOfLogWeights
    logLikelihood
  }

  /**
   * Find the Bayes information criterion of the data given the fitted model
   * [[https://en.wikipedia.org/wiki/Bayesian_information_criterion Bayesian information criterion]]
   */
  protected[summarizer] def computeBayesIC(
    beta: DenseVector[Double],
    logLikelihood: Double,
    count: Long,
    shouldIntercept: Boolean
  ): Double = {
    val k = beta.length
    -2.0 * logLikelihood + k * math.log(count.toDouble)
  }

  /**
   * Find the Akaike information criterion of the data given the fitted model
   * [[https://en.wikipedia.org/wiki/Akaike_information_criterion Akaike information criterion]]
   */
  protected[summarizer] def computeAkaikeIC(
    beta: DenseVector[Double],
    logLikelihood: Double,
    shouldIntercept: Boolean
  ): Double = {
    val k = beta.length
    -2.0 * logLikelihood + 2.0 * k
  }

  protected[summarizer] def computeResidualSumOfSquares(
    beta: DenseVector[Double],
    sumOfYSquared: Double,
    vectorOfXY: DenseVector[Double],
    matrixOfXX: DenseMatrix[Double]
  ): Double = {
    val k = beta.length
    require(matrixOfXX.rows == matrixOfXX.cols && vectorOfXY.length == k && matrixOfXX.cols == k)
    var residualSumOfSquares = sumOfYSquared
    var i = 0
    while (i < beta.length) {
      var rss = -2.0 * vectorOfXY(i)
      rss += beta(i) * matrixOfXX(i, i)
      var j = 0
      while (j < i) {
        rss += 2.0 * beta(j) * matrixOfXX(i, j)
        j = j + 1
      }
      residualSumOfSquares += rss * beta(i)
      i = i + 1
    }
    residualSumOfSquares
  }

  protected[summarizer] def computeRSquared(
    sumOfYSquared: Double,
    sumOfWeights: Double,
    sumOfY: Double,
    residualSumOfSquares: Double,
    shouldIntercept: Boolean
  ): Double = if (sumOfYSquared == 0.0 || sumOfWeights == 0.0) {
    Double.NaN
  } else {
    val meanOfY = sumOfY / sumOfWeights
    var varianceOfY = sumOfYSquared / sumOfWeights
    if (shouldIntercept) {
      varianceOfY -= meanOfY * meanOfY
    }
    (varianceOfY - residualSumOfSquares / sumOfWeights) / varianceOfY
  }

}
