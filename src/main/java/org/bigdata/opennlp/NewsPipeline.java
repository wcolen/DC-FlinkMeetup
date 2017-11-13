package org.bigdata.opennlp;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.flink.api.common.functions.FilterFunction;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.examples.news.AnnotationInputFormat;
import org.apache.flink.examples.news.NewsArticle;
import org.apache.flink.examples.news.NewsArticleAnnotationFactory;
import org.apache.flink.streaming.api.collector.selector.OutputSelector;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.datastream.SplitStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.connectors.elasticsearch5.ElasticsearchSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import opennlp.tools.chunker.ChunkerModel;
import opennlp.tools.namefind.TokenNameFinderModel;
import opennlp.tools.postag.POSModel;
import opennlp.tools.sentdetect.SentenceModel;
import opennlp.tools.tokenize.TokenizerModel;

public class NewsPipeline {

  private static final DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");

  private static final Logger LOG = LoggerFactory.getLogger(NewsPipeline.class);

  private static SentenceModel engSentenceModel;
  private static TokenizerModel engTokenizerModel;
  private static POSModel engPosModel;
  private static ChunkerModel engChunkModel;
  private static TokenNameFinderModel engNerPersonModel;

  /**
   * --parallelism <n>, default=1
   * --file <newsfile.gz>
   * @param args
   */
  public static void main(String[] args) throws Exception {

    LOG.info("Started: " + dtf.format(LocalDateTime.now()));

    initializeModels();

    LOG.info("Models loaded: " + dtf.format(LocalDateTime.now()));

    ParameterTool parameterTool = ParameterTool.fromArgs(args);

    final StreamExecutionEnvironment env =
            StreamExecutionEnvironment.getExecutionEnvironment()
            .setParallelism(parameterTool.getInt("parallelism", 1)).setMaxParallelism(10);

    DataStream<Annotation<NewsArticle>> rawStream =
            env.readFile(new AnnotationInputFormat(NewsArticleAnnotationFactory.getFactory()),
                parameterTool.getRequired("file"))
                    .map(new LanguageDetectorFunction<>());

    SplitStream<Annotation<NewsArticle>> articleStream = rawStream.split(new LanguageSelector());

    // english news articles
    SingleOutputStreamOperator<Annotation<NewsArticle>> eng = articleStream.select("eng")
        .map(new SentenceDetectorFunction<>(engSentenceModel))
        .map(new TokenizerFunction<>(engTokenizerModel));

    SingleOutputStreamOperator<Annotation<NewsArticle>> analyzedEng = eng.map(new POSTaggerFunction<>(engPosModel))
        .map(new ChunkerFunction<>(engChunkModel))
        .map(new NameFinderFunction<>(engNerPersonModel));

    // elastic search
    Map<String,String> config = new HashMap<>();
    config.put("cluster.name", "docker-cluster");
    config.put("bulk.flush.max.actions", "1000");
    config.put("bulk.flush.interval.ms", "10000");

    List<InetSocketAddress> transportAddresses = Stream.of(
            new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 9300),
            new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 9301))
        .collect(Collectors.toList());

    analyzedEng.addSink(new ElasticsearchSink<>(config, transportAddresses, new ESSinkFunction()));

    // Write all articles in non-analyzed languages to ES
    articleStream.filter(new FilterFunction<Annotation<NewsArticle>>() {
      @Override
      public boolean filter(Annotation<NewsArticle> value) throws Exception {
        return !"eng".equalsIgnoreCase(value.getLanguage()) &&
            !"por".equalsIgnoreCase(value.getLanguage());
      }
    })
    .addSink(new ElasticsearchSink<>(config, transportAddresses, new ESSinkFunction()));


    env.execute();

    LOG.info("Done: " + dtf.format(LocalDateTime.now()));

  }

  private static class LanguageSelector<T> implements OutputSelector<Annotation<T>> {
    @Override
    public Iterable<String> select(Annotation<T> annotation) {
      return Collections.singletonList(annotation.getLanguage());
    }
  }

  private static void initializeModels() throws IOException {
    engSentenceModel = new SentenceModel(NewsPipeline.class.getResource("/opennlp-models/en-sent.bin"));
    engTokenizerModel = new TokenizerModel(NewsPipeline.class.getResource("/opennlp-models/en-token.bin"));
    engPosModel= new POSModel(NewsPipeline.class.getResource("/opennlp-models/en-pos-perceptron.bin"));
    engChunkModel = new ChunkerModel(NewsPipeline.class.getResource("/opennlp-models/en-chunker.bin"));
    engNerPersonModel = new TokenNameFinderModel(NewsPipeline.class.getResource("/opennlp-models/en-ner-person.bin"));

  }
}
