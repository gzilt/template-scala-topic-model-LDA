package org.template.classification

import org.apache.predictionio.controller.{PAlgorithm, Params}
import org.apache.predictionio.controller.{PersistentModel, PersistentModelLoader}
import org.apache.spark.SparkContext
import grizzled.slf4j.Logger

import org.apache.spark.mllib.clustering._
import org.apache.spark.rdd.RDD
import breeze.linalg.{argmax, max}
import breeze.linalg.DenseVector
import org.apache.spark.mllib.linalg.{Vector, Vectors}

case class LDAModelWithCorpusAndVocab(
                               ldaModel: DistributedLDAModel,
                               corpus: RDD[(String, (Long,Vector))],
                               vocab : Map[String,Int],
                               sc: SparkContext
                               ) extends PersistentModel[AlgorithmParams] with Serializable {
  def save(id: String, params: AlgorithmParams,
    sc: SparkContext): Boolean = {
      ldaModel.save(sc, s"/tmp/${id}/ldaModel")
      corpus.saveAsObjectFile(s"/tmp/${id}/ldaCorpus")
      sc.parallelize(Seq(vocab)).saveAsObjectFile(s"/tmp/${id}/ldaVocab")
      true
  }
}

object LDAModelWithCorpusAndVocab
  extends PersistentModelLoader[AlgorithmParams, LDAModelWithCorpusAndVocab] {
  def apply(id: String, params: AlgorithmParams,
    sc: Option[SparkContext]) = {
    new LDAModelWithCorpusAndVocab(
      DistributedLDAModel.load(sc.get, s"/tmp/${id}/ldaModel"),
      sc.get.objectFile(s"/tmp/${id}/ldaCorpus"),
      sc.get.objectFile[Map[String,Int]](s"/tmp/${id}/ldaVocab").first,
      sc.get
      )      
  }
 }

case class AlgorithmParams(
  numTopics: Int,
  maxIter: Int,
  docConcentration: Double,
  topicConcentration: Double
) extends Params

// extends PAlgorithm because contains RDD.
// Does not implement save and load, because DistributedLDAModel doesn't support it yet
class LDAAlgorithm(val ap: AlgorithmParams)
  extends PAlgorithm[PreparedData, LDAModelWithCorpusAndVocab, Query, PredictedResult] {

  @transient lazy val logger = Logger[this.type]

  def train(sc: SparkContext, data: PreparedData): LDAModelWithCorpusAndVocab = {
//    require(!data.points.take(1).isEmpty,
//      s"RDD[labeldPoints] in PreparedData cannot be empty." +
//      " Please check if DataSource generates TrainingData" +
//      " and Preprator generates PreparedData correctly.")

    val dataStrings = data.points.map(s => s.text)
    dataStrings.cache
    val (corpus, vocab) = makeDocuments(dataStrings)
    corpus.cache

    val ldaModel = new LDA()
        .setSeed(13457)
        .setK(ap.numTopics)
        .setMaxIterations(ap.maxIter)
        .setCheckpointInterval(10)
        .run(corpus)
        .asInstanceOf[DistributedLDAModel]

    new LDAModelWithCorpusAndVocab(ldaModel, dataStrings zip corpus, vocab, sc)
  }
 
  def predict(ldaModelAndCorpus: LDAModelWithCorpusAndVocab, query: Query): PredictedResult = { 
    val topics = ldaModelAndCorpus.ldaModel.describeTopics(10)
    val topicDists = ldaModelAndCorpus.ldaModel.topicDistributions
    val corpusMap =ldaModelAndCorpus.corpus.collect().toMap

    val maxTopicIndex: Int = getMaxTopicIndex(ldaModelAndCorpus.sc, query, ldaModelAndCorpus.ldaModel)
    val swappedMap = ldaModelAndCorpus.vocab.map(_.swap)
    val topicResults = for( ((indices, weights), outerIndex) <- topics zipWithIndex)
                       yield {outerIndex -> (indices map (x => swappedMap(x)) zip weights)
                         .sortWith((e1, e2) => (e1._2 > e2._2))}

    val topTopic = topicResults.toMap.getOrElse(maxTopicIndex,
                                       throw new scala.Exception("Cannot find topic"))

    new PredictedResult(topTopic, topicResults)
  }

  def getMaxTopicIndex(sc:SparkContext, query: Query, ldaModel: DistributedLDAModel): Int = {

    val text = query.text.trim

    val (corpus, vocab) = makeDocuments(sc.parallelize(Array(text)))

    val actualPredictions = ldaModel.toLocal.topicDistributions(corpus).map { case (id, topics) =>
      // convert results to expectedPredictions format, which only has highest probability topic
      val topicsBz = new DenseVector(topics.toArray)
       (id, (argmax(topicsBz), max(topicsBz)))
    }.sortByKey()
    .values
    .collect()

    actualPredictions.head._1
  }

  //See https://gist.github.com/jkbradley/ab8ae22a8282b2c8ce33
  def makeDocuments(data: RDD[String]): (RDD[(Long, Vector)], Map[String, Int]) = {

    // Split each document into a sequence of terms (words)
    val tokenized: RDD[Seq[String]] =
      data.map(_.toLowerCase.split("\\s")).map(_.filter(_.length > 3)
                                          .filter(_.forall(java.lang.Character.isLetter)))
    // Choose the vocabulary.
    //   termCounts: Sorted list of (term, termCount) pairs
    val termCounts: Array[(String, Long)] =
      tokenized.flatMap(_.map(_ -> 1L)).reduceByKey(_ + _).collect().sortBy(-_._2)
    //   vocabArray: Chosen vocab (removing common terms)
    val numStopwords = termCounts.size / 10
    val vocabArray: Array[String] =
      termCounts.takeRight(termCounts.size - numStopwords).map(_._1)
    //   vocab: Map term -> term index
    val vocab: Map[String, Int] = vocabArray.zipWithIndex.toMap
    // Convert documents into term count vectors
    val documents: RDD[(Long, Vector)] =
      tokenized.zipWithIndex.map { case (tokens, id) =>
        val counts = new scala.collection.mutable.HashMap[Int, Double]()
        tokens.foreach { term =>
          if (vocab.contains(term)) {
            val idx = vocab(term)
            counts(idx) = counts.getOrElse(idx, 0.0) + 1.0
          }
        }
        (id, Vectors.sparse(vocab.size, counts.toSeq))
      }
    (documents, vocab)
  }
}
