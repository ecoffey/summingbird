/*
Copyright 2013 Twitter, Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package com.twitter.summingbird.storm

import backtype.storm.tuple.{Tuple, TupleImpl}
import com.twitter.summingbird.batch.Timestamp
import com.twitter.summingbird.online.TrimmableQueue
import com.twitter.summingbird.storm.option.{AnchorTuples, MaxWaitingFutures, MaxFutureWaitTime}
import scala.collection.mutable.SynchronizedQueue
import com.twitter.util.{Await, Duration, Future, Return, Throw, Try}
import java.util.concurrent.TimeoutException

abstract class AsyncBaseBolt[I, O](metrics: () => TraversableOnce[StormMetric[_]],
  anchorTuples: AnchorTuples,
  maxWaitingFutures: MaxWaitingFutures,
  maxWaitingTime: MaxFutureWaitTime,
  hasDependants: Boolean) extends BaseBolt[I, O](metrics, anchorTuples, hasDependants) {

  /** If you can use Future.value below, do so. The double Future is here to deal with
   * cases that need to complete operations after or before doing a FlatMapOperation or
   * doing a store merge
   */
  def apply(tup: TupleWrapper, in: (Timestamp, I)): Future[Iterable[(List[TupleWrapper], Future[TraversableOnce[(Timestamp, O)]])]]

  def tick: Future[Iterable[(List[TupleWrapper], Future[TraversableOnce[(Timestamp, O)]])]] = Future.value(Nil)

  private lazy val outstandingFutures = new SynchronizedQueue[Future[Unit]] with TrimmableQueue[Future[Unit]]
  private lazy val responses = new SynchronizedQueue[(List[TupleWrapper], Try[TraversableOnce[(Timestamp, O)]])]()

  override def execute(tuple: Tuple) {
    /**
     * System ticks come with a fixed stream id
     */

    // This not a tick tuple so we need to start an async operation
    val wrappedTuple = TupleWrapper(tuple)
    val fut = if(!tuple.getSourceStreamId.equals("__tick")) {
      val tsIn = decoder.invert(tuple.getValues).get // Failing to decode here is an ERROR
      // Don't hold on to the input values
      clearValues(tuple)
      apply(wrappedTuple, tsIn)
    }
    else {
      tick
    }

    fut
      .onSuccess { iter: Iterable[(List[TupleWrapper], Future[TraversableOnce[(Timestamp, O)]])] =>

        // Collect the result onto our responses
        val iterSize = iter.foldLeft(0) { case (iterSize, (tups, res)) =>
          res.respond { t => responses += ((tups, t)) }
          // Make sure there are not too many outstanding:
          if(!res.isDefined) {
            outstandingFutures += res.unit
            iterSize + 1
          } else {
            iterSize
          }
        }

        if(outstandingFutures.size > maxWaitingFutures.get) {
          /*
           * This can happen on large key expansion.
           * May indicate maxWaitingFutures is too low.
           */
          logger.debug(
            "Exceeded maxWaitingFutures({}), put {} futures", maxWaitingFutures.get, iterSize
          )
        }
      }
      .onFailure { thr =>
        throw thr
       responses += ((List(wrappedTuple), Throw(thr))) }

    if(!fut.isDefined) {
      outstandingFutures += fut.unit
    }
    // always empty the responses, even on tick
    emptyQueue
  }

  protected def forceExtraFutures {
    outstandingFutures.dequeueAll(_.isDefined)
    val toForce = outstandingFutures.trimTo(maxWaitingFutures.get)
    if(!toForce.isEmpty) {
      try {
        Await.ready(Future.collect(toForce), maxWaitingTime.get)
      }
      catch {
        case te: TimeoutException =>
          logError("forceExtra failed on %d Futures".format(toForce.size), te)
      }
    }
  }

  /**
   * NOTE: this is the only place where we call finish/fail in this method
   * is only called from execute. This is what makes this code thread-safe with
   * respect to storm.
   */
  protected def emptyQueue = {
    // don't let too many futures build up
    forceExtraFutures
    // Take all results that have been placed for writing to the network
    responses.dequeueAll(_ => true).foreach { case (tups, res) =>
      res match {
        case Return(outs) =>
        finish(tups, outs)
        case Throw(t) => fail(tups, t)
      }
    }
  }

  /** This is clearly not safe, but done to deal with GC issues since
   * storm keeps references to values
   */
  private lazy val valuesField = {
    val tupleClass = classOf[TupleImpl]
    val vf = tupleClass.getDeclaredField("values")
    vf.setAccessible(true)
    vf
  }

  private def clearValues(t: Tuple): Unit = {
    valuesField.set(t, null)
  }
}