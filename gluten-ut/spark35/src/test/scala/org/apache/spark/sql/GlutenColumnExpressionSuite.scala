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
package org.apache.spark.sql

import org.apache.spark.SparkException
import org.apache.spark.sql.execution.ProjectExec
import org.apache.spark.sql.functions.{assert_true, expr, input_file_name, lit, raise_error}
import org.apache.spark.sql.types._

class GlutenColumnExpressionSuite extends ColumnExpressionSuite with GlutenSQLTestsTrait {
  import testImplicits._
  testGluten("raise_error") {
    val strDf = Seq(("hello")).toDF("a")

    val e1 = intercept[SparkException] {
      strDf.select(raise_error(lit(null.asInstanceOf[String]))).collect()
    }
    assert(e1.getCause.isInstanceOf[RuntimeException])

    val e2 = intercept[SparkException] {
      strDf.select(raise_error($"a")).collect()
    }
    assert(e2.getCause.isInstanceOf[RuntimeException])
    assert(e2.getCause.getMessage contains "hello")
  }

  testGluten("assert_true") {
    // assert_true(condition, errMsgCol)
    val booleanDf = Seq((true), (false)).toDF("cond")
    checkAnswer(
      booleanDf.filter("cond = true").select(assert_true($"cond")),
      Row(null) :: Nil
    )
    val e1 = intercept[SparkException] {
      booleanDf.select(assert_true($"cond", lit(null.asInstanceOf[String]))).collect()
    }
    assert(e1.getCause.isInstanceOf[RuntimeException])

    val nullDf = Seq(("first row", None), ("second row", Some(true))).toDF("n", "cond")
    checkAnswer(
      nullDf.filter("cond = true").select(assert_true($"cond", $"cond")),
      Row(null) :: Nil
    )
    val e2 = intercept[SparkException] {
      nullDf.select(assert_true($"cond", $"n")).collect()
    }
    assert(e2.getCause.isInstanceOf[RuntimeException])
    assert(e2.getCause.getMessage contains "first row")

    // assert_true(condition)
    val intDf = Seq((0, 1)).toDF("a", "b")
    checkAnswer(intDf.select(assert_true($"a" < $"b")), Row(null) :: Nil)
    val e3 = intercept[SparkException] {
      intDf.select(assert_true($"a" > $"b")).collect()
    }
    assert(e3.getCause.isInstanceOf[RuntimeException])
    assert(e3.getCause.getMessage contains "'('a > 'b)' is not true!")
  }

  testGluten("input_file_name with scan is fallback") {
    withTempPath {
      dir =>
        val rawData = Seq(
          Row(1, "Alice", Seq(Row(Seq(1, 2, 3)))),
          Row(2, "Bob", Seq(Row(Seq(4, 5)))),
          Row(3, "Charlie", Seq(Row(Seq(6, 7, 8, 9))))
        )
        val schema = StructType(
          Array(
            StructField("id", IntegerType, nullable = false),
            StructField("name", StringType, nullable = false),
            StructField(
              "nested_column",
              ArrayType(
                StructType(Array(
                  StructField("array_in_struct", ArrayType(IntegerType), nullable = true)
                ))),
              nullable = true)
          ))
        val data: DataFrame = spark.createDataFrame(sparkContext.parallelize(rawData), schema)
        data.write.parquet(dir.getCanonicalPath)

        val q =
          spark.read.parquet(dir.getCanonicalPath).select(input_file_name(), expr("nested_column"))
        val firstRow = q.head()
        assert(firstRow.getString(0).contains(dir.toURI.getPath))
        val project = q.queryExecution.executedPlan.collect { case p: ProjectExec => p }
        assert(project.size == 1)
    }
  }
}
