package com.splicemachine.spark2.splicemachine

object ThisVersionSpecificItems {
  val schema = SparkVersionSpecificItems.schemaWithMetadata
  val jdbcBadDriverNameException = SparkVersionSpecificItems.connectionNotCreated
}
