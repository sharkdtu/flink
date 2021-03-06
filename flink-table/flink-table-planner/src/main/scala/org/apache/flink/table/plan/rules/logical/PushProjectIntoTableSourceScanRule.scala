/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.plan.rules.logical

import org.apache.flink.table.api.{TableException, TableSchema}
import org.apache.flink.table.plan.nodes.logical.{FlinkLogicalCalc, FlinkLogicalTableSourceScan}
import org.apache.flink.table.plan.util.{RexProgramExtractor, RexProgramRewriter}
import org.apache.flink.table.sources.TableSourceUtil.getRowtimeAttributeDescriptor
import org.apache.flink.table.sources._
import org.apache.flink.table.types.logical.utils.LogicalTypeChecks
import org.apache.flink.table.types.utils.DataTypeUtils
import org.apache.flink.table.typeutils.TimeIndicatorTypeInfo
import org.apache.flink.table.utils.TypeMappingUtils

import org.apache.calcite.plan.RelOptRule.{none, operand}
import org.apache.calcite.plan.{RelOptRule, RelOptRuleCall}

import java.util.function.{Function => JFunction}

import scala.collection.JavaConverters._

class PushProjectIntoTableSourceScanRule extends RelOptRule(
  operand(classOf[FlinkLogicalCalc],
    operand(classOf[FlinkLogicalTableSourceScan], none)),
  "PushProjectIntoTableSourceScanRule") {

  override def matches(call: RelOptRuleCall): Boolean = {
    val scan: FlinkLogicalTableSourceScan = call.rel(1).asInstanceOf[FlinkLogicalTableSourceScan]

    // only continue if we haven't pushed down a projection yet.
    scan.selectedFields.isEmpty
  }

  override def onMatch(call: RelOptRuleCall) {
    val calc: FlinkLogicalCalc = call.rel(0).asInstanceOf[FlinkLogicalCalc]
    val scan: FlinkLogicalTableSourceScan = call.rel(1).asInstanceOf[FlinkLogicalTableSourceScan]
    val source = scan.tableSource

    val accessedLogicalFields = RexProgramExtractor.extractRefInputFields(calc.getProgram)

    val nameMapping: JFunction[String, String] = source match {
      case mapping: DefinedFieldMapping if mapping.getFieldMapping != null =>
        new JFunction[String, String] {
          override def apply(t: String): String = mapping.getFieldMapping.get(t)
        }
      case _ => JFunction.identity()
    }

    val accessedIndices = TypeMappingUtils.computePhysicalIndicesOrTimeAttributeMarkers(
      source,
      accessedLogicalFields.map(scan.tableSchema.getTableColumn(_).get()).toList.asJava,
      source.isInstanceOf[StreamTableSource[_]],
      nameMapping
    )
    val physicalFields = expandTimeAttributes(
      source,
      if (LogicalTypeChecks.isCompositeType(source.getProducedDataType.getLogicalType)) {
        TableSchema.fromResolvedSchema(
          DataTypeUtils.expandCompositeTypeToSchema(source.getProducedDataType))
      } else {
        null
      },
      accessedIndices)

    // only continue if fields are projected or reordered.
    // eager reordering can remove a calc operator.
    if (!(0 until scan.getRowType.getFieldCount).toArray.sameElements(accessedLogicalFields)) {

      // try to push projection of physical fields to TableSource
      val (newTableSource, isProjectSuccess) = source match {
        case nested: NestedFieldsProjectableTableSource[_] =>
          val nestedFields = RexProgramExtractor
            .extractRefNestedInputFields(calc.getProgram, physicalFields)
          (nested.projectNestedFields(physicalFields, nestedFields), true)
        case projecting: ProjectableTableSource[_] =>
          (projecting.projectFields(physicalFields), true)
        case nonProjecting: TableSource[_] =>
          // projection cannot be pushed to TableSource
          (nonProjecting, false)
      }

      if (isProjectSuccess
        && newTableSource.explainSource().equals(scan.tableSource.explainSource())) {
        throw new TableException("Failed to push project into table source! "
          + "table source with pushdown capability must override and change "
          + "explainSource() API to explain the pushdown applied!")
      }

      // check that table schema of the new table source is identical to original
      if (source.getTableSchema != newTableSource.getTableSchema) {
        throw new TableException("TableSchema of ProjectableTableSource must not be modified " +
          "by projectFields() call. This is a bug in the implementation of the TableSource " +
          s"${source.getClass.getCanonicalName}.")
      }

      // Apply the projection during the input conversion of the scan.
      val newScan = scan.copy(
        scan.getTraitSet,
        scan.tableSchema,
        newTableSource,
        Some(accessedLogicalFields))
      val newCalcProgram = RexProgramRewriter.rewriteWithFieldProjection(
        calc.getProgram,
        newScan.getRowType,
        calc.getCluster.getRexBuilder,
        accessedLogicalFields)

      if (newCalcProgram.isTrivial) {
        // drop calc if the transformed program merely returns its input and doesn't exist filter
        call.transformTo(newScan)
      } else {
        val newCalc = calc.copy(calc.getTraitSet, newScan, newCalcProgram)
        call.transformTo(newCalc)
      }
    }
  }

  private def expandTimeAttributes(
      tableSource: TableSource[_],
      physicalSchema: TableSchema,
      physicalIndices: Array[Int]): Array[Int] = {

      physicalIndices
      // resolve time indicator markers to physical indexes
      .flatMap {
        case TimeIndicatorTypeInfo.PROCTIME_STREAM_MARKER =>
          // proctime field do not access a physical field
          Seq()
        case TimeIndicatorTypeInfo.ROWTIME_STREAM_MARKER =>
          // rowtime field is computed.
          // get names of fields which are accessed by the expression to compute the rowtime field.
          val rowtimeAttributeDescriptor = getRowtimeAttributeDescriptor(tableSource, None)
          val accessedFields = if (rowtimeAttributeDescriptor.isDefined) {
            rowtimeAttributeDescriptor.get.getTimestampExtractor.getArgumentFields
          } else {
            throw new TableException("Computed field mapping includes a rowtime marker but the " +
              "TableSource does not provide a RowtimeAttributeDescriptor. " +
              "This is a bug and should be reported.")
          }
          // resolve field names to physical fields
          accessedFields.map(name => {
            if (physicalSchema != null) {
              mapToCompositeType(tableSource, physicalSchema, name)
            } else {
              0
            }
          })
        case idx =>
          Seq(idx)
      }
  }

  private def mapToCompositeType(
      tableSource: TableSource[_],
      physicalSchema: TableSchema,
      name: String)
    : Int = {
    val fieldName = tableSource match {
      case mapping: DefinedFieldMapping if mapping.getFieldMapping != null =>
        mapping.getFieldMapping.get(name)
      case _ =>
        name
    }
    physicalSchema.getFieldNames.indexWhere(_.equals(fieldName)) // logical accessed field
  }
}

object PushProjectIntoTableSourceScanRule {
  val INSTANCE: RelOptRule = new PushProjectIntoTableSourceScanRule
}
