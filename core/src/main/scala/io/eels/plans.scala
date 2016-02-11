package io.eels

import com.sksamuel.scalax.io.Using
import scala.concurrent.ExecutionContext.Implicits.global

class HeadPlan(frame: Frame) extends Plan[Option[Row]] with Using {
  override def run: Option[Row] = {
    using(frame.buffer(1)) { buffer =>
      buffer.iterator.take(1).toList.headOption
    }
  }
}

class ExistsPlan(frame: Frame, p: (Row) => Boolean) extends Plan[Boolean] with Using {
  override def run: Boolean = {
    using(frame.buffer(1)) { buffer =>
      buffer.iterator.exists(p)
    }
  }
}

class FindPlan(frame: Frame, p: (Row) => Boolean) extends Plan[Option[Row]] with Using {
  override def run: Option[Row] = {
    using(frame.buffer(1)) { buffer =>
      buffer.iterator.find(p)
    }
  }
}

class ToListPlan(frame: Frame) extends Plan[List[Row]] with Using {
  override def run: List[Row] = {
    using(frame.buffer(1)) { buffer =>
      buffer.iterator.toList
    }
  }
}

class ForallPlan(frame: Frame, p: Row => Boolean) extends Plan[Boolean] with Using {
  override def run: Boolean = {
    using(frame.buffer(1)) { buffer =>
      buffer.iterator.forall(p)
    }
  }
}

class ToSizePlan(frame: Frame) extends Plan[Long] with Using {
  override def run: Long = {
    using(frame.buffer(1)) { buffer =>
      buffer.iterator.size
    }
  }
}