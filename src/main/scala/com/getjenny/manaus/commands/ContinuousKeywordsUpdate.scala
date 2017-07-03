package com.getjenny.manaus.commands

/**
  * Created by angelo on 03/07/17.
  */

import com.getjenny.manaus.util._
import com.getjenny.manaus._
import breeze.io.{CSVReader, CSVWriter}
import java.io.{File, FileReader, FileWriter}

import com.getjenny.manaus.commands.UploadKeywords.logger
import com.typesafe.scalalogging.LazyLogging
import scopt.OptionParser

import scala.concurrent.duration._
import scala.io.Source
import scala.collection.SeqView
import scala.concurrent.Await
import scala.util.{Failure, Success, Try}

object ContinuousKeywordsUpdate extends LazyLogging {

  private case class Params(
                             interval_sec: Int = 7200,
                             temp_data_folder: String = "data",
                             word_frequencies: String = "data/english/word_frequency.tsv",
                             minWordsPerSentence: Int = 10,
                             pruneTermsThreshold: Int = 100000,
                             misspell_max_occurrence: Int = 5,
                             output_file: String = "",
                             active_potential_decay: Int = 10,
                             total_info: Boolean = false,
                             active_potential: Boolean = true,

                             type_name: String = "question",
                             query_min_threshold: Double = 0.0,
                             index_name: String = "jenny-en-0",
                             field_name: String = "question.base",
                             cluster_name: String = "starchat",
                             ignore_cluster_name: Boolean = true,
                             index_language: String = "english",
                             host_map: Map[String, Int] = Map("localhost" -> 9300)
                           )

  def doContinuousKeywordsUpdate(params: Params): Unit = {
    // Load the prior occurrences
    val cmd_utils = CommandsUtils

    val priorOccurrences = cmd_utils.readPriorOccurrencesMap(params.word_frequencies)

    // BEGIN get data from ES
    val elastic_client = ElasticClientKB(type_name=params.type_name,
      query_min_threshold = params.query_min_threshold, index_name = params.index_name,
      cluster_name = params.cluster_name, ignore_cluster_name = params.ignore_cluster_name,
      index_language = params.index_name, host_map = params.host_map)
    val search_hits = cmd_utils.searchAndGetTokens(elastic_client = elastic_client, field_name = params.field_name)

    do {

      def out_data = search_hits.map(hit => {
        IndexedSeq[String](hit._1.mkString(" "), hit._2)
      })

      logger.info("data serialization on file")
      val token_temp_file_name = "__data_raw_conversations.csv"
      val token_data_output_file = new File(params.temp_data_folder + "/" + token_temp_file_name)
      val token_data_file_writer = new FileWriter(token_data_output_file)

      //sentence, type, conv_id, sentence_id
      val token_data_csv_writer = CSVWriter.write(output = token_data_file_writer,
        mat = out_data,
        separator = ';',
        quote = '"',
        escape = '\\')
      // END get data from ES

      // BEGIN calculate keywords
      logger.info("getting sentences and observedOccurrences")
      val (sentences, observedOccurrences) =
        cmd_utils.buildObservedOccurrencesMapFromConversationsFormat2(params.temp_data_folder + "/"
          + token_temp_file_name)

      val bags = cmd_utils.extractKeywords(sentences = sentences, observedOccurrences = observedOccurrences,
        minWordsPerSentence = params.minWordsPerSentence,
        pruneTermsThreshold = params.pruneTermsThreshold,
        misspell_max_occurrence = params.misspell_max_occurrence,
        priorOccurrences = priorOccurrences,
        active_potential_decay = params.active_potential_decay,
        active_potential = params.active_potential,
        total_info = params.total_info)

      logger.info("merging sentences with bags")

      val out_keywords = sentences.zip(bags).map(item => {
        val sentence = item._1
        val bag = item._2
        val keywords = bag._2.toSeq.sortBy(-_._2).map(x => x._1 + "|" + x._2.toString).mkString(" ")
        IndexedSeq(sentence._1, keywords)
      })

      logger.info("results serialization on file")
      val keywords_temp_file_name = "__data_keywords.csv"
      val keywords_output_file = new File(params.temp_data_folder + "/" + keywords_temp_file_name)
      val keywords_file_writer = new FileWriter(keywords_output_file)

      //sentence, type, conv_id, sentence_id
      val keywords_csv_writer = CSVWriter.write(output = keywords_file_writer,
        mat = out_keywords,
        separator = ';',
        quote = '"',
        escape = '\\')

      logger.info("keywords calculation completed")
      // END calculate keywords

      // BEGIN upload data on ES
      val keywords_file_item = cmd_utils.getDataFromCSV(params.temp_data_folder + "/" + keywords_temp_file_name)
        .map(line => {
          val document_id = line(0)
          val keywords: List[(String, Double)] = line(1).split(" ").toList.filter(_ != "").map(w => {
            val item = w.split("\\|")
            (item(0), item(1).toDouble)
          })
          (document_id, keywords)
        })

      keywords_file_item.par.foreach(item => {
        val document = KBDocumentUpdate(question_scored_terms = Option {
          item._2
        })
        val result = elastic_client.updateDocument(id = item._1, document = document, elastic_client = elastic_client)
        val result_try: Try[Option[UpdateDocumentResult]] = Await.ready(result, 60.seconds).value.get
        result_try match {
          case Success(t) =>
            logger.info("ID(" + item._1 + ") Document(" + document + ")")
          case Failure(e) =>
            logger.error("ID(" + item._1 + ") Document(" + document + ") Error(" + e.getMessage + ")")
        }
      })
      // END upload data on ES

      Thread.sleep( params.interval_sec * 1000)
    } while(true)
  }

  def main(args: Array[String]) {
    val defaultParams = Params()
    val parser = new OptionParser[Params]("Continuous keywords calculation tool") {
      head("Calculate and upload keywords on ES")
      help("help").text("prints this usage text")
      opt[String]("temp_data_folder").required()
        .text(s"the path of the folder for storing temporary data" +
          s"  default: ${defaultParams.temp_data_folder}")
        .action((x, c) => c.copy(temp_data_folder = x))
      opt[String]("word_frequencies").required()
        .text(s"the file with word frequencies" +
          s"  default: ${defaultParams.word_frequencies}")
        .action((x, c) => c.copy(word_frequencies = x))
      opt[Int]("min_words_in_sentence")
        .text(s"discard the sentences with less that N words" +
          s"  default: ${defaultParams.minWordsPerSentence}")
        .action((x, c) => c.copy(minWordsPerSentence = x))
      opt[Int]("prune_sentence_threshold")
        .text(s"threshold on the number of terms for trigger pruning" +
          s"  default: ${defaultParams.pruneTermsThreshold}")
        .action((x, c) => c.copy(pruneTermsThreshold = x))
      opt[Int]("mispell_max_occurrence")
        .text(s"given a big enough sample, min freq beyond what we consider the token a misspell" +
          s"  default: ${defaultParams.misspell_max_occurrence}")
        .action((x, c) => c.copy(misspell_max_occurrence = x))
      opt[Int]("active_potential_decay")
        .text(s"introduce a penalty on active potential for words which does not occur enough" +
          s"  default: ${defaultParams.active_potential_decay}")
        .action((x, c) => c.copy(active_potential_decay = x))
      opt[Boolean]("total_info")
        .text(s"normalize the information by total informations" +
          s"  default: ${defaultParams.total_info}")
        .action((x, c) => c.copy(total_info = x))
      opt[Boolean]("active_potential")
        .text(s"weight bags with active potential" +
          s"  default: ${defaultParams.active_potential}")
        .action((x, c) => c.copy(active_potential = x))
      opt[Int]("interval_sec")
        .text(s"the interval in seconds between a calculation and another" +
          s"  default: ${defaultParams.interval_sec}")
        .action((x, c) => c.copy(interval_sec = x))
      opt[String]("type_name")
        .text(s"the type name on ElasticSearch" +
          s"  default: ${defaultParams.type_name}")
        .action((x, c) => c.copy(type_name = x))
      opt[String]("field_name")
        .text(s"the field_name" +
          s"  default: ${defaultParams.type_name}")
        .action((x, c) => c.copy(type_name = x))
      opt[Double]("query_min_threshold")
        .text(s"a min threshdold for search" +
          s"  default: ${defaultParams.query_min_threshold}")
        .action((x, c) => c.copy(query_min_threshold = x))
      opt[String]("index_name")
        .text(s"the name of the index on ElasticSearch" +
          s"  default: ${defaultParams.index_name}")
        .action((x, c) => c.copy(index_name = x))
      opt[String]("cluster_name")
        .text(s"the name of the cluster on ElasticSearch" +
          s"  default: ${defaultParams.cluster_name}")
        .action((x, c) => c.copy(cluster_name = x))
      opt[String]("index_language")
        .text(s"the language of the datatype on ElasticSearch" +
          s"  default: ${defaultParams.index_language}")
        .action((x, c) => c.copy(index_language = x))
      opt[Boolean]("ignore_cluster_name")
        .text(s"tell if ElasticSearch cluster name should be ignored" +
          s"  default: ${defaultParams.ignore_cluster_name}")
        .action((x, c) => c.copy(ignore_cluster_name = x))
      opt[Map[String, Int]]("host_map")
        .text(s"a list of ElasticSearch nodes" +
          s"  default: ${defaultParams.host_map}")
        .action((x, c) => c.copy(host_map = x))
    }

    parser.parse(args, defaultParams) match {
      case Some(params) =>
        doContinuousKeywordsUpdate(params)
      case _ =>
        sys.exit(1)
    }
  }

}