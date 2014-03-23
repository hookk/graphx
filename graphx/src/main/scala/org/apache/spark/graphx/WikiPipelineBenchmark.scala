package org.apache.spark.graphx

import org.apache.spark._
// import org.apache.spark.graphx._
import org.apache.spark.graphx.lib._
import org.apache.hadoop.io.{LongWritable, Text}
// import org.apache.hadoop.io.Text
import org.apache.hadoop.conf.Configuration
import org.apache.mahout.text.wikipedia._
import org.apache.spark.rdd.RDD
import org.apache.spark.SparkContext._
import org.apache.spark.Logging
import java.util.{HashSet => JHashSet, TreeSet => JTreeSet}

object WikiPipelineBenchmark extends Logging {


  def main(args: Array[String]) = {

    val host = args(0)
    val process = args(1)

    val sparkconf = new SparkConf()
      .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
      .set("spark.kryo.registrator", "org.apache.spark.graphx.GraphKryoRegistrator")

    val sc = new SparkContext(host, "ETL", sparkconf)

   val start = System.currentTimeMillis
   process match {
     case "pre" => {
       val fname = args(2)
       val outbase = args(3)
       preProcess(sc, fname, outbase)
     }
     case "post" => {
       val rankPath = args(2)
       val attributePath = args(3)
       val result = postProcess(sc, rankPath, attributePath)
       logWarning(result)
     }
     case "graphx" => {
       val rawData = args(2)
       val numIters = args(3).toInt
       val result = benchmarkGraphx(sc, rawData, numIters)
//       logWarning(result)
     }
     case "prep" => {
       val rawData = args(2)
       val outBase = args(3)
       prep(sc, rawData, outBase)
     }

     case _ => throw new IllegalArgumentException("Please provide a valid process")
   }
   logWarning(process + "\tTIMEX: " + (System.currentTimeMillis - start)/1000.0)
   sc.stop()
   System.exit(0)

  }



  def prep(sc: SparkContext, rawData: String, outBase: String) {

    val hadoopconf = new org.apache.hadoop.conf.Configuration
    hadoopconf.set("key.value.separator.in.input.line", " ");
    hadoopconf.set("xmlinput.start", "<page>");
    hadoopconf.set("xmlinput.end", "</page>");

    val vertPath = outBase + "_vertices"
    val rankPath = outBase + "_ranks"

    val xmlRDD = sc.newAPIHadoopFile(rawData, classOf[XmlInputFormat], classOf[LongWritable], classOf[Text], hadoopconf)
      .map(t => t._2.toString)

    val allArtsRDD = xmlRDD.map { raw => new WikiArticle(raw) }

    val wikiRDD = allArtsRDD.filter { art => art.relevant }.repartition(128)
    val vertices: RDD[(VertexId, String)] = wikiRDD.map { art => (art.vertexID, art.title) }
    val verticesToSave = vertices.map {v => v._1 + "\t"+ v._2}
    verticesToSave.saveAsTextFile(vertPath)
    val edges: RDD[Edge[Double]] = wikiRDD.flatMap { art => art.edges }
    val g = Graph(vertices, edges) //TODO what to do about partitionStrategy???
    val pr = PageRank.run(g, 20)
    val prToSave = pr.vertices.map {v => v._1 + "\t"+ v._2}
    prToSave.saveAsTextFile(rankPath)
  }

//   def extractLinkGraph(sc: SparkContext, rawData: String): (RDD[(VertexId, String)], RDD[Edge[Double]]) = {
//     val conf = new Configuration
//     conf.set("key.value.separator.in.input.line", " ")
//     conf.set("xmlinput.start", "<page>")
//     conf.set("xmlinput.end", "</page>")
//
//     logWarning("about to load xml rdd")
//     val xmlRDD = sc.newAPIHadoopFile(rawData, classOf[XmlInputFormat], classOf[LongWritable], classOf[Text], conf)
//       .map(t => t._2.toString)
//     // xmlRDD.count
//     logWarning(s"XML RDD counted. Found ${xmlRDD.count} raw articles.")
//     val repartXMLRDD = xmlRDD.repartition(128)
//     logWarning(s"XML RDD repartitioned. Found ${repartXMLRDD.count} raw articles.")
//
//     val allArtsRDD = repartXMLRDD.map { raw => new WikiArticle(raw) }.cache
//     logWarning(s"Total articles: Found ${allArtsRDD.count} UNPARTITIONED articles.")
//  
//     val wikiRDD = allArtsRDD.filter { art => art.relevant }.cache //.repartition(128)
//     logWarning(s"wikiRDD counted. Found ${wikiRDD.count} relevant articles in ${wikiRDD.partitions.size} partitions")
//
//   }

  def benchmarkGraphx(sc: SparkContext, rawData: String, numIters: Int) {

    val conf = new Configuration
    conf.set("key.value.separator.in.input.line", " ")
    conf.set("xmlinput.start", "<page>")
    conf.set("xmlinput.end", "</page>")

    logWarning("about to load xml rdd")
    val xmlRDD = sc.newAPIHadoopFile(rawData, classOf[XmlInputFormat], classOf[LongWritable], classOf[Text], conf)
      .map(t => t._2.toString)
    // xmlRDD.count
    logWarning(s"XML RDD counted. Found ${xmlRDD.count} raw articles.")
    val repartXMLRDD = xmlRDD.repartition(128)
    logWarning(s"XML RDD repartitioned. Found ${repartXMLRDD.count} raw articles.")

    val allArtsRDD = repartXMLRDD.map { raw => new WikiArticle(raw) }.cache
    logWarning(s"Total articles: Found ${allArtsRDD.count} UNPARTITIONED articles.")
 
    val wikiRDD = allArtsRDD.filter { art => art.relevant }.cache //.repartition(128)
    logWarning(s"wikiRDD counted. Found ${wikiRDD.count} relevant articles in ${wikiRDD.partitions.size} partitions")


    // val repartAllArtsRDD = allArtsRDD.repartition(128)
    // logWarning(s"Total articles: Found ${repartAllArtsRDD.count} PARTITIONED articles.")
    // val wikiRDD = unpartWikiRDD.repartition(128).cache
    // val wikiRDD = unpartWikiRDD.coalesce(128, false).cache
    // logWarning(s"WikiRDD partitions size: ${wikiRDD.partitions.size}")

    // val wikiRDD = allArtsRDD.filter { art => art.relevant }.repartition(128)

    // val wikiRDDCount = wikiRDD.count
    // logWarning(s"wikiRDD counted. Found ${wikiRDDCount} relevant articles.")
    // logWarning("Counting differently")


    val vertices: RDD[(VertexId, String)] = wikiRDD.map { art => (art.vertexID, art.title) }
    val edges: RDD[Edge[Double]] = wikiRDD.flatMap { art => art.edges }
    logWarning("creating graph")
    val g = Graph(vertices, edges)
    val cleanG = g.subgraph(x => true, (vid, vd) => vd != null).cache
    logWarning(s"DIRTY graph has ${g.triplets.count()} EDGES, ${g.vertices.count()} VERTICES")
    logWarning(s"CLEAN graph has ${cleanG.triplets.count()} EDGES, ${cleanG.vertices.count()} VERTICES")
    val resultG = pagerankConnComponentsAlt(numIters, cleanG)
    logWarning(s"ORIGINAL graph has ${cleanG.triplets.count()} EDGES, ${cleanG.vertices.count()} VERTICES")
    logWarning(s"FINAL graph has ${resultG.triplets.count()} EDGES, ${resultG.vertices.count()} VERTICES")

  }

  def pagerankConnComponentsAlt(numRepetitions: Int, g: Graph[String, Double]): Graph[String, Double] = {
    var currentGraph = g
    logWarning("starting iterations")
    for (i <- 0 to numRepetitions) {
      currentGraph.cache
      val startTime = System.currentTimeMillis
      logWarning("starting pagerank")
      val pr = PageRank.run(currentGraph, 20).cache
      pr.vertices.count
      logWarning("Pagerank completed")
      val prAndTitle = currentGraph.outerJoinVertices(pr.vertices)({(id: VertexId, title: String, rank: Option[Double]) => (title, rank.getOrElse(0.0))}).cache
      prAndTitle.vertices.count
      logWarning("join completed.")
      val top20 = prAndTitle.vertices.top(20)(Ordering.by((entry: (VertexId, (String, Double))) => entry._2._2))
      logWarning(s"Top20 for iteration $i:\n${top20.mkString("\n")}")
      val top20verts = top20.map(_._1).toSet
      // filter out top 20 vertices
      val filterTop20 = {(v: VertexId, d: String) =>
        !top20verts.contains(v)
      }
      val newGraph = currentGraph.subgraph(x => true, filterTop20).cache
      val ccGraph = ConnectedComponents.run(newGraph).cache
//       val zeroVal = new mutable.HashSet[VertexId]()
//       val seqOp = (s: mutable.HashSet[VertexId], vtuple: (VertexId, VertexId)) => {
//         s.add(vtuple._2)
//         s
//       }
//       val combOp = (s1: mutable.HashSet[VertexId], s2: mutable.HashSet[VertexId]) => { s1 union s2}
//       val numCCs = ccGraph.vertices.aggregate(zeroVal)(seqOp, combOp)


      val zeroVal = new JTreeSet[VertexId]()
      val seqOp = (s: JTreeSet[VertexId], vtuple: (VertexId, VertexId)) => {
        s.add(vtuple._2)
        s
      }
      val combOp = (s1: JTreeSet[VertexId], s2: JTreeSet[VertexId]) => {
        s1.addAll(s2)
        s1
      }
      val numCCs = ccGraph.vertices.aggregate(zeroVal)(seqOp, combOp).size()


      //(new mutable.HashSet[Int]())((s: mutable.HashSet[Int], vtuple: (VertexId, Int)) => { s.add(vtuple._2); s },(s1: mutable.HashSet[Int], s2: mutable.HashSet[Int]) => { s1 union s2})

      //(((set, vtuple) => set.add(vtuple._2)), ((set1, set2) => set1 union set2)).size
      logWarning(s"Number of connected components for iteration $i: $numCCs")
      logWarning(s"TIMEX iter $i ${(System.currentTimeMillis - startTime)/1000.0}")
      // TODO will this result in too much memory overhead???
      currentGraph = newGraph
    }
    currentGraph
  }

  // parse wikipedia xml dump and
  def preProcess(sc: SparkContext, rawData: String, outBase: String) = {

    val conf = new Configuration
    conf.set("key.value.separator.in.input.line", " ");
    conf.set("xmlinput.start", "<page>");
    conf.set("xmlinput.end", "</page>");
    val xmlRDD = sc.newAPIHadoopFile(rawData, classOf[XmlInputFormat], classOf[LongWritable], classOf[Text], conf)
      .map(t => t._2.toString)
    val wikiRDD = xmlRDD.map { raw => new WikiArticle(raw) }
      .filter { art => art.relevant }.repartition(128)
    val vertices: RDD[(VertexId, String)] = wikiRDD.map { art => (art.vertexID, art.title) }
    val verticesToSave = vertices.map {v => v._1 + "\t"+ v._2}
    verticesToSave.saveAsTextFile(outBase + "_vertices")
    val edges: RDD[Edge[Double]] = wikiRDD.flatMap { art => art.edges }
    val edgesToSave = edges.map(e => e.srcId + "\t" + e.dstId)
    edgesToSave.saveAsTextFile(outBase + "_edges")

  }


  def postProcess(sc: SparkContext, rankPath: String, attrPath: String): String = {
    val ranks = GraphLoader.loadVertices(sc, rankPath).map {v => (v._1, v._2.toDouble)}
    val attrs = GraphLoader.loadVertices(sc, attrPath)

    // slightly cheating, but not really
    val ranksAndAttrs = ranks.join(attrs)
    val top20 = ranksAndAttrs.top(20)(Ordering.by((entry: (VertexId, (Double, String))) => entry._2._1))
    top20.mkString("\n")
  }
}

class MakeString(tup: (LongWritable, Text)) {
  val str = tup._2.toString
}
