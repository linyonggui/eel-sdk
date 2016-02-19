package io.eels.component.jdbc

import java.sql.{Connection, DriverManager}
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{ArrayBlockingQueue, CountDownLatch, Executors, LinkedBlockingQueue, TimeUnit}

import com.sksamuel.scalax.collection.BlockingQueueConcurrentIterator
import com.sksamuel.scalax.jdbc.ResultSetIterator
import com.typesafe.scalalogging.slf4j.StrictLogging
import io.eels.{Row, FrameSchema, Sink, Writer}

import scala.language.implicitConversions

case class JdbcSink(url: String, table: String, props: JdbcSinkProps = JdbcSinkProps())
  extends Sink
    with StrictLogging {

  private val BufferSize = 1000

  private def tableExists(conn: Connection): Boolean = {
    logger.debug("Fetching tables to detect if table exists")
    val tables = ResultSetIterator(conn.getMetaData.getTables(null, null, null, Array("TABLE"))).toList
    logger.debug(s"${tables.size} tables found")
    tables.map(_.apply(3).toLowerCase) contains table.toLowerCase
  }

  override def writer: Writer = new Writer {

    val dialect = props.dialectFn(url)
    logger.debug(s"Writer will use dialect=$dialect")

    logger.debug(s"Connecting to jdbc sink $url...")
    val conn = DriverManager.getConnection(url)
    logger.debug(s"Connected to $url")

    val created = new AtomicBoolean(false)

    def createTable(row: Row, schema: FrameSchema): Unit = {
      if (!created.get) {
        JdbcSink.this.synchronized {
          if (!created.get && props.createTable && !tableExists(conn)) {
            logger.info(s"Creating sink table $table")

            val sql = dialect.create(schema, table)
            logger.debug(s"Executing [$sql]")

            val stmt = conn.createStatement()
            try {
              stmt.executeUpdate(sql)
            } finally {
              stmt.close()
            }
          }
          created.set(true)
        }
      }
    }

    implicit def toRunnable(thunk: => Unit): Runnable = new Runnable {
      override def run(): Unit = thunk
    }

    var schema: FrameSchema = null
    val queue = new ArrayBlockingQueue[Row](BufferSize)

    val latch = new CountDownLatch(props.threads)
    val executor = Executors.newFixedThreadPool(props.threads)
    for ( k <- 1 to props.threads ) {
      executor.submit {
        BlockingQueueConcurrentIterator(queue, Row.Sentinel)
          .grouped(props.batchSize)
          .withPartial(true)
          .foreach { rows =>
            doBatch(rows, schema)
          }
        latch.countDown()
      }
    }
    executor.submit {
      latch.await(1, TimeUnit.DAYS)
      conn.close()
      logger.info("Closed JDBC Connection")
    }
    executor.shutdown()

    def doBatch(rows: Seq[Row], schema: FrameSchema): Unit = {
      logger.info(s"Inserting batch [${rows.size} rows]")
      val stmt = conn.createStatement()
      rows.map(dialect.insert(_, schema, table)).foreach(stmt.addBatch)
      try {
        stmt.executeBatch()
        logger.info("Batch complete")
      } catch {
        case e: Exception =>
          logger.error("Batch failure", e)
          throw e
      } finally {
        stmt.close()
      }
    }

    override def close(): Unit = {
      queue.put(Row.Sentinel)
      logger.debug("Waiting for sink writer to complete")
      executor.awaitTermination(1, TimeUnit.DAYS)
    }

    override def write(row: Row, schema: FrameSchema): Unit = {
      createTable(row, schema)
      this.schema = schema
      queue.put(row)
    }
  }
}

case class JdbcSinkProps(createTable: Boolean = false,
                         batchSize: Int = 100,
                         dialectFn: String => JdbcDialect = url => JdbcDialect(url),
                         threads: Int = 1)

