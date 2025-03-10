/*
 * Copyright (c) 2012 - 2020 Splice Machine, Inc.
 *
 * This file is part of Splice Machine.
 * Splice Machine is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either
 * version 3, or (at your option) any later version.
 * Splice Machine is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Affero General Public License for more details.
 * You should have received a copy of the GNU Affero General Public License along with Splice Machine.
 * If not, see <http://www.gnu.org/licenses/>.
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * This file is part of Splice Machine.
 * Splice Machine is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either
 * version 3, or (at your option) any later version.
 * Splice Machine is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Affero General Public License for more details.
 * You should have received a copy of the GNU Affero General Public License along with Splice Machine.
 * If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.splicemachine.spark2

import org.apache.spark.sql.execution.datasources.jdbc.{SplicemachineDialect2, SplicemachineDialectNoTime2}
import org.apache.spark.sql.jdbc.{JdbcDialect, JdbcDialects, JdbcType}

import org.apache.spark.sql.{DataFrame, DataFrameReader, DataFrameWriter, SparkSession}
import org.apache.spark.sql.types.{DataType, StructType, TimeType}
import org.apache.spark.sql.execution.datasources.jdbc.JdbcUtils

package object splicemachine {

  val dialect = new SplicemachineDialect2
  val dialectNoTime = new SplicemachineDialectNoTime2
  
  var quoteIdentifier: String => String = _
  var norm: String => String = _
  
  columnNamesCaseSensitive(false)

  def columnNamesCaseSensitive(caseSensitive: Boolean): Unit = {
    SparkSession.builder.getOrCreate.sqlContext.setConf("spark.sql.caseSensitive", caseSensitive.toString)
    if( caseSensitive ) {
      quoteIdentifier = dialect.quoteIdentifier
      norm = identity
    } else {
      quoteIdentifier = identity
      norm = s => s.toUpperCase
    }
  }

  /**
    * Adds a method, `splicemachine`, to DataFrameReader that allows you to read SpliceMachine tables using
    * the DataFrameReader.
    */
  implicit class SplicemachineDataFrameReader(reader: DataFrameReader) {
    def splicemachine: DataFrame = reader.format("com.splicemachine.spark2.splicemachine").load
  }

  /**
    * Adds a method, `splicemachine`, to DataFrameWriter that allows writes to Splicemachine using
    * the DataFileWriter
    */
  implicit class SplicemachineDataFrameWriter[T](writer: DataFrameWriter[T]) {
    def splicemachine = writer.format("com.splicemachine.spark2.splicemachine").save
  }

  def modifySchema(schema: StructType, tableSchemaStr: String): StructType = {
    def hasTimeColumn: String => Boolean = schStr => schStr.contains("TIME,") || schStr.endsWith("TIME")
    if( hasTimeColumn(tableSchemaStr) ) {
      val tableColumns = tableSchemaStr.split(", ")
      var i = 0
      var timeSchema = StructType(Nil)
      for (field <- schema.iterator) {
        val dataType = if( hasTimeColumn(tableColumns(i)) ) { TimeType } else { field.dataType }
        timeSchema = timeSchema.add(field.name, dataType, field.nullable)
        i += 1
      }
      timeSchema
    } else {
      schema
    }
  }

  def schemaStringWithoutNullable(schema: StructType, url: String): String = 
    SpliceJDBCUtil.schemaWithoutNullableString(schema, url).replace("\"","")

  /** Schema string built from JDBC metadata. */
  def schemaString(columnInfo: Array[Seq[String]], schema: StructType = new StructType()): String = {
    val info = columnInfo
      .map(i => {
        val colName = quoteIdentifier(i(0))
        val sqlType = i(1)
        val size = sqlType match {
          case "VARCHAR" => s"(${i(2)})"
          case "DECIMAL" | "NUMERIC" => s"(${i(2)},${i(3)})"
          case _ => ""
        }
        s"$colName $sqlType$size"
      })

    if( schema.isEmpty ) {
      info.mkString(", ")
    } else {
      schema.map( field => {
        info.find( col => norm(col).startsWith( norm(quoteIdentifier(field.name))+" " ) )
          .getOrElse(
            throw new Exception( s"No column named ${field.name} found in the table. Verify column name and case sensitivity." )
          )
      }).mkString(", ")
    }
  }

  /**
   *
   * Generate the schema string for create table.
   *
   * @param schema
   * @param url
   * @return
   */
  def schemaString(schema: StructType, url: String): String = {
    val sb = new StringBuilder()
    val dialect = JdbcDialects.get(url)
    schema.fields foreach { field =>
      val name = quoteIdentifier(field.name)
      val typ: String = getJdbcType(field.dataType, dialect).databaseTypeDefinition
      val nullable = if (field.nullable) "" else "NOT NULL"
      sb.append(s", $name $typ $nullable")
    }
    if (sb.length < 2) "" else sb.substring(2)
  }

  /**
   *
   * Retrieve the JDBC type based on the Spark DataType and JDBC Dialect.
   *
   * @param dt
   * @param dialect
   * @return
   */
  def getJdbcType(dt: DataType, dialect: JdbcDialect): JdbcType =
    dialect.getJDBCType(dt).orElse(JdbcUtils.getCommonJDBCType(dt)).getOrElse(
      throw new IllegalArgumentException(s"Can't get JDBC type for ${dt.simpleString}"))

}