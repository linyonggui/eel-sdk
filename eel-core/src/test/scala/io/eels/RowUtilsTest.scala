package io.eels

import io.eels.schema.Field
import io.eels.schema.StructType
import org.scalatest.{Matchers, WordSpec}

class RowUtilsTest extends WordSpec with Matchers {
  "RowUtils.rowAlign" should {
    "rowAlign should reorder in line with target schema" in {
      val row = Row(StructType(Field("a"), Field("b"), Field("c")), "aaaa", "bbb", "ccc")
      val targetSchema = StructType(Field("c"), Field("b"))
      RowUtils.rowAlign(row, targetSchema) shouldBe Row(StructType(Field("c"), Field("b")), "ccc", "bbb")
    }
    "rowAlign should lookup missing data" in {
      val row = Row(StructType(Field("a"), Field("b"), Field("c")), "aaaa", "bbb", "ccc")
      val targetSchema = StructType(Field("c"), Field("d"))
      RowUtils.rowAlign(row, targetSchema, Map("d" -> "ddd")) shouldBe Row(StructType(Field("c"), Field("d")), "ccc", "ddd")
    }
    "rowAlign should throw an error if a field is missing" in {
      val row = Row(StructType(Field("a"), Field("b"), Field("c")), "aaaa", "bbb", "ccc")
      val targetSchema = StructType(Field("c"), Field("d"))
      intercept[RuntimeException] {
        RowUtils.rowAlign(row, targetSchema)
      }
    }
  }
}