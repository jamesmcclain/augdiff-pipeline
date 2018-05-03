package osmdiff

import org.apache.log4j.{Level, Logger}
import org.apache.spark._
import org.apache.spark.graphx._
import org.apache.spark.rdd.RDD
import org.apache.spark.sql._
import org.apache.spark.sql.expressions.Window
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types.StructType
import org.apache.spark.storage.StorageLevel


object ComputeIndex {

  private val logger = {
    val logger = Logger.getLogger(this.getClass)
    logger.setLevel(Level.INFO)
    logger
  }

  private def edgesFromRows(rows: DataFrame): DataFrame = {
    val halfEdgesFromNodes =
      rows
        .filter(col("type") === "way")
        .select(
          Common.pairToLongUdf(col("id"), col("type")).as("b"),
          explode(col("nds")).as("nds"))
        .select(
          Common.pairToLongUdf(col("nds.ref"), lit("node")).as("a"),
          col("b"))
        .select(Common.edgeColumns: _*)
    val halfEdgesFromRelations =
      rows
        .filter(col("type") === "relation")
        .select(
          Common.pairToLongUdf(col("id"), col("type")).as("b"),
          explode(col("members")).as("members"))
        .select(
          Common.pairToLongUdf(col("members.ref"), col("members.type")).as("a"),
          col("b"))
        .select(Common.edgeColumns: _*)

    halfEdgesFromNodes.union(halfEdgesFromRelations)
  }

  def apply(
    rows: DataFrame,
    persistence: Option[StorageLevel],
    partitions: Option[Int] = None
  ): DataFrame = {
    logger.info(s"◻ Computing Index")

    val edgeDf = edgesFromRows(rows)
    val as: RDD[(VertexId, Any)] = edgeDf.rdd.map({ row => (row.getLong(0), null) })
    val bs: RDD[(VertexId, Any)] = edgeDf.rdd.map({ row => (row.getLong(1), null) })
    val vertices: RDD[(VertexId, Any)] = as.union(bs).distinct
    val edges: RDD[Edge[Any]] = edgeDf.rdd.map({ row => Edge(row.getLong(0), row.getLong(1), null) })
    val defaultVertex = (-1L, null)
    val graph = Graph(vertices, edges, defaultVertex)
    val components = graph.connectedComponents.vertices

    rows.sparkSession.createDataFrame(
      components.map({ case (v, component) => Row(v, component) }),
      StructType(Common.indexSchema))
  }

}
