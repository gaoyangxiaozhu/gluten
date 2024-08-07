/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.gluten.planner.cost

import org.apache.gluten.GlutenConfig
import org.apache.gluten.extension.columnar.enumerated.RemoveFilter
import org.apache.gluten.extension.columnar.transition.{ColumnarToRowLike, RowToColumnarLike}
import org.apache.gluten.planner.plan.GlutenPlanModel.GroupLeafExec
import org.apache.gluten.ras.{Cost, CostModel}
import org.apache.gluten.utils.PlanUtil

import org.apache.spark.internal.Logging
import org.apache.spark.sql.execution.{ColumnarToRowExec, RowToColumnarExec, SparkPlan}
import org.apache.spark.sql.utils.ReflectionUtil

object GlutenCostModel extends Logging {
  def find(): CostModel[SparkPlan] = {
    val aliases: Map[String, Class[_ <: CostModel[SparkPlan]]] = Map(
      "rough" -> classOf[RoughCostModel])
    val aliasOrClass = GlutenConfig.getConf.rasCostModel
    val clazz: Class[_ <: CostModel[SparkPlan]] = if (aliases.contains(aliasOrClass)) {
      aliases(aliasOrClass)
    } else {
      val userModel = ReflectionUtil.classForName(aliasOrClass)
      logInfo(s"Using user cost model: $aliasOrClass")
      userModel
    }
    val ctor = clazz.getDeclaredConstructor()
    ctor.setAccessible(true)
    val model = ctor.newInstance()
    model
  }

  def rough(): CostModel[SparkPlan] = new RoughCostModel()

  private class RoughCostModel extends CostModel[SparkPlan] {
    private val infLongCost = Long.MaxValue

    override def costOf(node: SparkPlan): GlutenCost = node match {
      case _: GroupLeafExec => throw new IllegalStateException()
      case _ => GlutenCost(longCostOf(node))
    }

    private def longCostOf(node: SparkPlan): Long = node match {
      case n =>
        val selfCost = selfLongCostOf(n)

        // Sum with ceil to avoid overflow.
        def safeSum(a: Long, b: Long): Long = {
          assert(a >= 0)
          assert(b >= 0)
          val sum = a + b
          if (sum < a || sum < b) Long.MaxValue else sum
        }

        (n.children.map(longCostOf).toList :+ selfCost).reduce(safeSum)
    }

    // A very rough estimation as of now. The cost model basically considers any
    // fallen back ops as having extreme high cost so offloads computations as
    // much as possible.
    private def selfLongCostOf(node: SparkPlan): Long = {
      node match {
        case _: RemoveFilter.NoopFilter =>
          // To make planner choose the tree that has applied rule PushFilterToScan.
          0L
        case ColumnarToRowExec(child) => 10L
        case RowToColumnarExec(child) => 10L
        case ColumnarToRowLike(child) => 10L
        case RowToColumnarLike(child) => 10L
        case p if PlanUtil.isGlutenColumnarOp(p) => 10L
        case p if PlanUtil.isVanillaColumnarOp(p) => 1000L
        // Other row ops. Usually a vanilla row op.
        case _ => 1000L
      }
    }

    override def costComparator(): Ordering[Cost] = Ordering.Long.on {
      case GlutenCost(value) => value
      case _ => throw new IllegalStateException("Unexpected cost type")
    }

    override def makeInfCost(): Cost = GlutenCost(infLongCost)
  }
}
