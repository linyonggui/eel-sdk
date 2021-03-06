package io.eels.component.hive

import com.sksamuel.exts.Logging
import com.sksamuel.exts.io.Using
import io.eels.component.hdfs.{AclSpec, HdfsSource}
import io.eels.component.parquet.{ParquetLogMute, Predicate}
import io.eels.schema.{PartitionConstraint, StructType}
import io.eels.{FilePattern, Part, Source}
import org.apache.hadoop.fs.permission.FsPermission
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.hadoop.hive.metastore.{IMetaStoreClient, TableType}
import org.apache.hadoop.security.UserGroupInformation
import com.sksamuel.exts.OptionImplicits._
import io.eels.util.HdfsIterator

import scala.collection.JavaConverters._

/**
 * @param constraints optional constraits on the partition data to narrow which partitions are read
 * @param projection sets which fields are required by the caller.
 * @param predicate optional predicate which will filter rows at the read level
 *
 */
case class HiveSource(dbName: String,
                      tableName: String,
                      projection: List[String] = Nil,
                      predicate: Option[Predicate] = None,
                      partitionConstraint: Option[PartitionConstraint] = None,
                      principal: Option[String] = None,
                      keytabPath: Option[java.nio.file.Path] = None)
                     (implicit fs: FileSystem,
                      client: IMetaStoreClient) extends Source with Logging with Using {
    ParquetLogMute()

  implicit val conf = fs.getConf
  val ops = new HiveOps(client)

  @deprecated("use withProjection()", "1.1.0")
  def select(first: String, rest: String*): HiveSource = withProjection(first +: rest)
  def withProjection(first: String, rest: String*): HiveSource = withProjection(first +: rest)
  def withProjection(columns: Seq[String]): HiveSource = {
    require(columns.nonEmpty)
    copy(projection = columns.toList)
  }

  /**
    * Returns all the files used by this table. The result is a mapping of partition path to the files contained
    * in that partition.
    */
  def files(): Map[Path, Seq[Path]] = {
    ops.partitions(dbName, tableName).map { p =>
      val location = new Path(p.getSd.getLocation)
      val paths = HdfsIterator(fs.listFiles(location, false)).map(_.getPath).toList
      location -> paths
    }.toMap
  }

  def withPredicate(predicate: Predicate): HiveSource = copy(predicate = predicate.some)
  def withPartitionConstraint(constraint: PartitionConstraint): HiveSource = copy(partitionConstraint = constraint.some)

  def withKeytabFile(principal: String, keytabPath: java.nio.file.Path): HiveSource = {
    login()
    copy(principal = principal.some, keytabPath = keytabPath.some)
  }

  private def login(): Unit = {
    for (user <- principal; path <- keytabPath) {
      UserGroupInformation.loginUserFromKeytab(user, path.toString)
    }
  }

  /**
    * Returns a list of all files used by this hive source.
    *
    * @param includePartitionDirs if true then the partition directories will be included
    * @param includeTableDir      if true then the main table directory will be included
    * @return paths of all files and directories
    */
  def paths(includePartitionDirs: Boolean = false, includeTableDir: Boolean = false): List[Path] = {
    login()

    val files = ops.partitions(dbName, tableName).flatMap { partition =>
      val location = partition.getSd.getLocation
      val files = FilePattern(s"$location/*").toPaths()
      if (includePartitionDirs) {
        files :+ new Path(location)
      } else {
        files
      }
    }
    if (includeTableDir) {
      val location = spec().location
      files :+ new Path(location)
    } else {
      files
    }
  }

  def setPermissions(permission: FsPermission,
                     includePartitionDirs: Boolean = false,
                     includeTableDir: Boolean = false): Unit = {
    login()
    paths(includePartitionDirs, includeTableDir).foreach(fs.setPermission(_, permission))
  }

  /**
    * Sets the acl for all files of this hive source.
    * Even if the files are not located inside the table directory, this function will find them
    * and correctly update the spec.
    *
    * @param acl the acl values to set
    */
  def setAcl(acl: AclSpec,
             includePartitionDirs: Boolean = false,
             includeTableDir: Boolean = false): Unit = {
    login()
    paths(includePartitionDirs, includeTableDir).foreach { path =>
      HdfsSource(path).setAcl(acl)
    }
  }

  // returns the permission of the table location path
  def tablePermission(): FsPermission = {
    login()
    val location = ops.location(dbName, tableName)
    fs.getFileStatus(new Path(location)).getPermission
  }

  /**
    * Returns a TableSpec which contains details of the underlying table.
    * Similar to the Table class in the Hive API but using scala friendly types.
    */
  def spec(): TableSpec = {
    login()
    val table = client.getTable(dbName, tableName)
    val tableType = TableType.values().find(_.name.toLowerCase == table.getTableType.toLowerCase)
      .getOrElse(sys.error("Hive table type is not supported by this version of hive"))
    val params = table.getParameters.asScala.toMap
    TableSpec(
      tableName,
      tableType,
      table.getSd.getLocation,
      table.getSd.getNumBuckets,
      table.getSd.getBucketCols.asScala.toList,
      params,
      table.getSd.getInputFormat,
      table.getSd.getOutputFormat,
      table.getSd.getSerdeInfo.getName,
      table.getRetention,
      table.getCreateTime,
      table.getLastAccessTime,
      table.getOwner
    )
  }

  /**
   * The returned schema should take into account:
   *
   * 1) Any projection. If a projection is set, then it should return the schema in the same order
   * as the projection. If no projection is set then the schema should be driven from the hive metastore.
   *
   * 2) Any partitions set. These should be included in the schema columns.
   */
  override def schema(): StructType = {
    login()
    // if no field names were specified, then we will return the schema as is from the hive database,
    // otherwise we will keep only the requested fields
    val schema = if (projection.isEmpty) metastoreSchema
    else {
      // remember hive is always lower case, so when comparing requested field names with
      // hive fields we need to use lower case everything. And we need to return the schema
      // in the same order as the requested projection
      val columns = projection.map { fieldName =>
        metastoreSchema.fields
          .find(_.name == fieldName.toLowerCase)
          .getOrElse(sys.error(s"Requested field $fieldName does not exist in the hive schema"))
      }
      StructType(columns)
    }

    schema
  }

  // returns the full underlying schema from the metastore including partition partitionKeys
  lazy val metastoreSchema: StructType = {
    login()
    ops.schema(dbName, tableName)
  }

  //def  spec(): HiveSpec = HiveSpecFn.toHiveSpec(dbName, tableName)

  def isPartitionOnlyProjection(): Boolean = {
    val partitionKeyNames = HiveTable(dbName, tableName).partitionKeys().map(_.field.name)
    projection.nonEmpty && projection.map { it => it.toLowerCase() }.forall { it => partitionKeyNames.contains(it) }
  }

  override def parts(): List[Part] = {
    login()

    val table = client.getTable(dbName, tableName)
    val dialect = io.eels.component.hive.HiveDialect(table)
    val partitionKeys = HiveTable(dbName, tableName).partitionKeys()
    val partitionKeyNames = partitionKeys.map(_.field.name)

    // a predicate cannot operate on partitions as it is pushed down into the files
    if (predicate.map(_.fields).getOrElse(Nil).exists(partitionKeyNames.contains))
      sys.error("A predicate cannot operate on partition fields; use a partition constraint")

    // if we requested only partition columns, then we can get this information by scanning the metatstore
    // to see which partitions have been created.
    // if we have a predicate we have to go down to the files
    if (isPartitionOnlyProjection() && predicate.isEmpty) {
      logger.info("Requested projection only uses partitions; reading directly from metastore")
      // we pass in the schema so we can order the results to keep them aligned with the given projection
      List(new HivePartitionPart(dbName, tableName, schema(), partitionKeys, dialect))
    } else {
      val files = HiveFilesFn(table, partitionKeys.map(_.field.name), partitionConstraint)
      logger.debug(s"Found ${files.size} visible hive files from all locations for $dbName:$tableName")

      // for each seperate hive file part we must pass in the metastore schema
      files.map { case (file, spec) =>
        new HiveFilePart(dialect, file, metastoreSchema, schema(), predicate, spec.parts.toList)
      }
    }
  }
}