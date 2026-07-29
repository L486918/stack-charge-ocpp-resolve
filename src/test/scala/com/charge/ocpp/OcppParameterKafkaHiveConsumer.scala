package com.charge.ocpp

import com.charge.ocpp.resolve.OcppDecoderV3
import org.apache.spark.sql.functions._
import org.apache.spark.sql.streaming.Trigger
import org.apache.spark.sql.types._
import org.apache.spark.sql.{Dataset, Row, SparkSession}
import org.slf4j.LoggerFactory

/**
 * 只消费并解析以下两类参数配置消息：
 *   - DataTransfer：payload.data 为十六进制报文，payload.messageId 为模板标识
 *   - ChangeConfiguration：payload.value 为十六进制报文，payload.key 为模板标识
 *
 * 启动参数：
 *   0 bootstrapServers
 *   1 topicName
 *   2 groupId
 *   3 outputPath
 *   4 triggerIntervalSeconds
 *   5 checkpointPath
 *   6 agreementUrl
 */
object OcppParameterKafkaHiveConsumer {

  private val logger = LoggerFactory.getLogger(getClass)

  private val TargetSubActions = Seq(
    "HostParameterSetting",
    "EnergyParameterSetting",
    "TerminalParameterSetting",
    "EStorageSysBalanceSetting",
    "AccessCardDeleteSetting",
    "AccessCardWriteSetting",
    "EStorageSysParameterSetting",
    "TerminalAuxiliarySetting"
  )

  private val KafkaSchema = StructType(Seq(
    StructField("acceptTime", StringType, nullable = true),
    StructField("funcCode", StringType, nullable = true),
    StructField("hostCode", StringType, nullable = true),
    StructField("hostIp", StringType, nullable = true),
    StructField("message", StringType, nullable = true),
    StructField("version", StringType, nullable = true)
  ))

  private val SignalsType =
    MapType(StringType, DoubleType, valueContainsNull = true)
  private val ExtraInfoType =
    MapType(StringType, StringType, valueContainsNull = true)
  private val MapArrayType =
    MapType(
      StringType,
      ArrayType(DoubleType, containsNull = false),
      valueContainsNull = true
    )

  def main(args: Array[String]): Unit = {
    require(
      args.length >= 7,
      "参数不足：bootstrapServers topicName groupId outputPath " +
        "triggerIntervalSeconds checkpointPath agreementUrl"
    )

    val bootstrapServers = args(0)
    val topicName = args(1)
    val groupId = args(2)
    val outputPath = args(3)
    val triggerInterval = s"${args(4)} seconds"
    val checkpointPath = args(5)
    val agreementUrl = args(6)

    val spark = SparkSession.builder()
      .appName("OcppParameterKafkaHiveConsumer")
      .enableHiveSupport()
      .config("spark.sql.session.timeZone", "UTC")
      .config("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
      .config("spark.streaming.stopGracefullyOnShutdown", "true")
      .getOrCreate()

    /*
     * 在 Driver 端只加载一次协议。Decoder 可序列化，执行解析时由每个
     * mapPartitions task 复用，避免逐条请求配置中心。
     */
    val decoder = new OcppDecoderV3(agreementUrl)

    val rawKafkaDF = spark.readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", bootstrapServers)
      .option("subscribe", topicName)
      .option("groupIdPrefix", groupId)
      .option("kafka.session.timeout.ms", "30000")
      .option("kafka.heartbeat.interval.ms", "10000")
      .option("kafka.request.timeout.ms", "60000")
      .option("kafka.reconnect.backoff.ms", "1000")
      .option("kafka.reconnect.backoff.max.ms", "5000")
      .option("startingOffsets", "latest")
      .option("failOnDataLoss", "false")
      .load()
      .select(
        col("value").cast(StringType).as("raw_value"),
        col("timestamp").as("kafka_time")
      )

    val parsedDF = rawKafkaDF
      .select(
        from_json(col("raw_value"), KafkaSchema).as("data"),
        col("kafka_time")
      )
      .select("data.*", "kafka_time")
      .filter(
        col("acceptTime").isNotNull &&
          col("message").isNotNull &&
          col("version").isNotNull
      )

    val transformedDF = parsedDF
      .select(
        col("hostCode").as("host_code"),
        col("hostIp").as("host_ip"),
        to_timestamp(col("acceptTime"), "yyyy-MM-dd HH:mm:ss").as("accept_time"),
        col("kafka_time"),
        current_timestamp().as("create_time"),
        col("version"),
        get_json_object(col("message"), "$[0]")
          .cast(IntegerType)
          .as("message_type_id"),
        get_json_object(col("message"), "$[1]").as("message_id"),
        col("funcCode").as("action_code"),
        col("message").as("raw_message")
      )
      .withColumn(
        "payload_content",
        when(
          col("message_type_id") === 2,
          get_json_object(col("raw_message"), "$[3]")
        ).when(
          col("message_type_id") === 3,
          get_json_object(col("raw_message"), "$[2]")
        ).when(
          col("message_type_id") === 4,
          get_json_object(col("raw_message"), "$[4]")
        )
      )
      .withColumn(
        "sub_action",
        when(
          col("action_code") === "DataTransfer",
          get_json_object(col("payload_content"), "$.messageId")
        ).when(
          col("action_code") === "ChangeConfiguration",
          get_json_object(col("payload_content"), "$.key")
        )
      )
      .withColumn(
        "decode_message",
        when(
          col("action_code") === "DataTransfer",
          get_json_object(col("payload_content"), "$.data")
        ).when(
          col("action_code") === "ChangeConfiguration",
          get_json_object(col("payload_content"), "$.value")
        )
      )
      .filter(
        col("action_code").isin("DataTransfer", "ChangeConfiguration") &&
          col("sub_action").isin(TargetSubActions: _*) &&
          col("decode_message").isNotNull &&
          length(trim(col("decode_message"))) > 0
      )
      .withColumn("day", date_format(col("accept_time"), "yyyy-MM-dd"))

    val query = transformedDF.writeStream
      .foreachBatch { (batchDF: Dataset[Row], batchId: Long) =>
        val outputSchema = batchDF.schema
          .add("signals", SignalsType, nullable = true)
          .add("extra_info", ExtraInfoType, nullable = true)
          .add("map_array", MapArrayType, nullable = true)

        val decodedRows = batchDF.rdd.mapPartitions { rows =>
          rows.map { row =>
            val result = decoder.decodeForHive(
              row.getAs[String]("decode_message"),
              row.getAs[String]("sub_action"),
              row.getAs[String]("version")
            )

            Row.fromSeq(
              row.toSeq ++ Seq(
                result.getSignals,
                result.getExtraInfo,
                result.getMapArray
              )
            )
          }
        }

        spark.createDataFrame(decodedRows, outputSchema)
          .drop("decode_message")
          .repartition(col("day"), col("action_code"))
          .sortWithinPartitions("accept_time")
          .write
          .format("parquet")
          .option("compression", "gzip")
          .partitionBy("day", "action_code")
          .mode("append")
          .save(outputPath)

        logger.info(s"batchId=$batchId 参数配置数据解析并写入完成")
      }
      .option("checkpointLocation", checkpointPath)
      .trigger(Trigger.ProcessingTime(triggerInterval))
      .start()

    query.awaitTermination()
  }
}
