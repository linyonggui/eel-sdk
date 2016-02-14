package io.eels.component.hive

import com.typesafe.scalalogging.slf4j.StrictLogging
import io.eels.{FrameSchema, Row}
import org.apache.hadoop.fs.{FileSystem, Path}

trait HiveDialect extends StrictLogging {
  def iterator(path: Path, schema: FrameSchema)(implicit fs: FileSystem): Iterator[Row]
  def writer(schema: FrameSchema, path: Path)(implicit fs: FileSystem): HiveWriter
}

object HiveDialect {
  def apply(format: String): HiveDialect = format match {
    case "org.apache.hadoop.hive.ql.io.parquet.MapredParquetInputFormat" => ParquetHiveDialect
    case "org.apache.hadoop.mapred.TextInputFormat" => TextHiveDialect
    case "org.apache.hadoop.hive.ql.io.avro.AvroContainerInputFormat" => AvroHiveDialect
    case other => sys.error("Unknown hive input format: " + other)
  }
}

trait HiveWriter {
  def write(row: Row): Unit
  def close(): Unit
}