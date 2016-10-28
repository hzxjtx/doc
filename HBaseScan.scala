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

package org.apache.spark.sql.execution.datasources.hbase.examples

import java.util

import com.typesafe.config.{Config, ConfigFactory}
import org.apache.spark.sql.{SQLContext, _}
import org.apache.spark.sql.execution.datasources.hbase._
import org.apache.spark.{SparkConf, SparkContext}
import org.bytedeco.javacpp.amjcmatch._

object HBaseScan {
  val cat = s"""{
            |"table":{"namespace":"facedb", "name":"facedb:FaceLog4"},
            |"rowkey":"key",
            |"columns":{
              |"CKey":{"cf":"rowkey", "col":"key", "type":"string"},
              |"CFeature":{"cf":"FEATURE", "col":"feature", "type":"binary"}
            |}
          |}""".stripMargin

  def parseInputPara(args:Array[String]): Config = {
    val map = new util.HashMap[String, String]()
    args.foreach(x =>{
      val key = (x.substring(2).split('=').head)
      val value = (x.substring(2).split('=').tail.head)
      map.put(key, value)
    })
    val cfg = ConfigFactory.parseMap(map)
    cfg
  }

  def main(args: Array[String]) {
    val sparkConf = new SparkConf().setAppName("HBaseScanTest")
    val sc = new SparkContext(sparkConf)
    val sqlContext = new SQLContext(sc)
    val config = parseInputPara(args)

    import sqlContext.implicits._
    val strStartTime = config.getString("StartTime")
    val strEndTime = config.getString("EndTime")
    val inputCameraList = config.getString("CamerList")
    val partionNum = config.getString("PartitionNum")
    println("-----InputParam: startTime[" + strStartTime + "]")
    println("-----InputParam: endTime[" + strEndTime + "]")
    println("-----InputParam: cameraList[" + inputCameraList + "]")

    val localCameraList = {
        if (inputCameraList == "all") {
          (0 to 999).map{i => s"${i}"}.toList
        } else {
          inputCameraList.split(",").map(x => x.toInt).toList
        }
    }
    println("-----InputParam: cameraList[" + localCameraList + "]")

    val startLoadTime = System.currentTimeMillis()
    def withCatalog(cat: String): DataFrame = {
      sqlContext
        .read
        .options(Map(HBaseTableCatalog.tableCatalog->cat))
        .format("org.apache.spark.sql.execution.datasources.hbase")
        .load()
    }

    val df = withCatalog(cat).repartition(partionNum.toInt).cache()
    val totalRecordCnt = df.count()
    val endLoadTime = System.currentTimeMillis()
    println("------------------LLLLLLLLoadTimeUsed:-----TotalCnt:["+ totalRecordCnt + "], ------------timecost["+(endLoadTime - startLoadTime)+"]--------------")

    // 1. get scan total time used
    val startScanTime = System.currentTimeMillis()
    val totalScoreSum = df.map(r => compareFeature(r)).sum()
    val endScanTime = System.currentTimeMillis()
    println("-------------------Scannnnnnnnnnnnnn :["+ totalRecordCnt + "], -----TotalSum[" + totalScoreSum + "]------timecost[" + (endScanTime - startScanTime)+"]--------------")

    // 2. get filter time used
    // 2.1 fiter by camereid && timestamp
    val startFilterTime = System.currentTimeMillis()
    val selectResult = df.select($"CKey".substr(1,3).alias("camerid"), $"CKey".substr(4, 10).alias("timestamp"), $"CKey", $"CFeature")
    println("+++++++++++++++++LocalCameraList[" + localCameraList + "]++++++++++++++++++")

    val filterResult = selectResult.filter(($"camerid".isin(localCameraList:_*)) && ($"timestamp" >= strStartTime && $"timestamp" <= strEndTime)).select($"CKey", $"CFeature")
    val filterScoreNum = filterResult.map(r => compareFeature(r)).sum()
    val endFilterTime = System.currentTimeMillis()
    println("-------------------Filterrrrrrrrrrrrrrrrr TotalCnt:["+ filterResult.count() + "], --FilterSum["+ filterScoreNum + "]----------timecost["+(endFilterTime - startFilterTime)+"]--------------")
  }

  def compareFeature(row: Row/*,feature:Array[Byte]*/):Double = {
    val rFeature = row.getAs[Array[Byte]]("CFeature")
    AmJCMatch(rFeature, rFeature)
  }
}
