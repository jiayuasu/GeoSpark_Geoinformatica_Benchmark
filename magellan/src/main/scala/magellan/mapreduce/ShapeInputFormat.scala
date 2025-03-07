/**
 * Copyright 2015 Ram Sriharsha
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package magellan.mapreduce

import com.google.common.base.Stopwatch
import magellan.io.{ShapeKey, ShapeWritable}
import org.apache.commons.logging.LogFactory
import org.apache.hadoop.fs.{LocatedFileStatus, Path}
import org.apache.hadoop.mapreduce.lib.input._
import org.apache.hadoop.mapreduce.{InputSplit, JobContext, TaskAttemptContext}

import scala.collection.JavaConversions._
import scala.collection.mutable.ListBuffer

private[magellan] class ShapeInputFormat
  extends FileInputFormat[ShapeKey, ShapeWritable] {

  private val log = LogFactory.getLog(classOf[ShapeInputFormat])

  override def createRecordReader(inputSplit: InputSplit,
    taskAttemptContext: TaskAttemptContext) = {
    new ShapefileReader
  }

  override def isSplitable(context: JobContext, filename: Path): Boolean = true

  override def getSplits(job: JobContext): java.util.List[InputSplit] = {
    val splitInfos = SplitInfos.SPLIT_INFO_MAP.get()
    computeSplits(job, splitInfos)
  }

  private def computeSplits(
       job: JobContext,
       splitInfos: scala.collection.Map[String, Array[Long]]) = {

    val sw = new Stopwatch().start
    val splits = ListBuffer[InputSplit]()
    val files = listStatus(job)
    for (file <- files) {
      val path = file.getPath
      val length = file.getLen
      val blkLocations = if (file.isInstanceOf[LocatedFileStatus]) {
        file.asInstanceOf[LocatedFileStatus].getBlockLocations
      } else {
        val fs = path.getFileSystem(job.getConfiguration)
        fs.getFileBlockLocations(file, 0, length)
      }
      val key = path.getName.split("\\.shp$")(0)
      if (splitInfos == null || !splitInfos.containsKey(key)) {
        val blkIndex = getBlockIndex(blkLocations, 0)
        splits.+= (makeSplit(path, 0, length, blkLocations(blkIndex).getHosts,
          blkLocations(blkIndex).getCachedHosts))
      } else {
        val s = splitInfos(key).toSeq
        val start = s
        val end = s.drop(1) ++ Seq(length)
        start.zip(end).foreach { case (startOffset: Long, endOffset: Long) =>
          val blkIndex = getBlockIndex(blkLocations, startOffset)
          splits.+=(makeSplit(path, startOffset, endOffset - startOffset, blkLocations(blkIndex).getHosts,
            blkLocations(blkIndex).getCachedHosts))
        }
      }
    }
    sw.stop
    if (log.isDebugEnabled) {
      //log.debug("Total # of splits generated by getSplits: " + splits.size + ", TimeTaken: " + sw.elapsedMillis)
    }
    splits
  }
}

object SplitInfos {

  // TODO: Can we get rid of this hack to pass split calculation to the Shapefile Reader?
  val SPLIT_INFO_MAP = new ThreadLocal[scala.collection.Map[String, Array[Long]]]

}