package io.vamp.pulse

import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

import io.vamp.common.config.Config
import io.vamp.common.http.OffsetEnvelope
import io.vamp.common.json.{ OffsetDateTimeSerializer, SerializationFormat }
import io.vamp.common.spi.ClassMapper
import io.vamp.common.vitals.{ InfoRequest, StatsRequest }
import io.vamp.model.event.Aggregator.AggregatorType
import io.vamp.model.event._
import io.vamp.pulse.Percolator.{ GetPercolator, RegisterPercolator, UnregisterPercolator }
import io.vamp.pulse.notification._
import org.json4s.ext.EnumNameSerializer
import org.json4s.native.Serialization.{ read, write }
import org.json4s.{ DefaultFormats, Extraction }

import scala.concurrent.Future

class ElasticsearchPulseActorMapper extends ClassMapper {
  val name = "elasticsearch"
  val clazz = classOf[ElasticsearchPulseActor]
}

object ElasticsearchPulseActor {

  val config = PulseActor.config

  val elasticsearchUrl = Config.string(s"$config.elasticsearch.url")

  val indexName = Config.string(s"$config.elasticsearch.index.name")

  val indexTimeFormat: () ⇒ Map[String, String] = () ⇒ Config.entries(s"$config.elasticsearch.index.time-format")().map { case (key, value) ⇒ key → value.toString }
}

class ElasticsearchPulseActor extends ElasticsearchPulseEvent with PulseStats with PulseActor {

  import ElasticsearchClient._
  import PulseActor._

  val url = ElasticsearchPulseActor.elasticsearchUrl()

  val indexName = ElasticsearchPulseActor.indexName()

  val indexTimeFormat = ElasticsearchPulseActor.indexTimeFormat()

  private lazy val es = new ElasticsearchClient(url)

  def receive = {

    case InfoRequest ⇒ reply(info)

    case StatsRequest ⇒ reply(stats)

    case Publish(event, publishEventValue) ⇒ reply((validateEvent andThen percolate(publishEventValue) andThen publish(publishEventValue))(Event.expandTags(event)), classOf[EventIndexError])

    case Query(envelope) ⇒ reply((validateEventQuery andThen eventQuery(envelope.page, envelope.perPage))(envelope.request), classOf[EventQueryError])

    case GetPercolator(name) ⇒ reply(Future.successful(getPercolator(name)))

    case RegisterPercolator(name, tags, kind, message) ⇒ registerPercolator(name, tags, kind, message)

    case UnregisterPercolator(name) ⇒ unregisterPercolator(name)

    case any ⇒ unsupported(UnsupportedPulseRequest(any))
  }

  private def info = es.health map { health ⇒ Map[String, Any]("type" → "elasticsearch", "elasticsearch" → health) }

  private def publish(publishEventValue: Boolean)(event: Event) = {
    implicit val formats = SerializationFormat(OffsetDateTimeSerializer, new EnumNameSerializer(Aggregator))
    val (indexName, typeName) = indexTypeName(event.`type`)
    log.debug(s"Pulse publish an event to index '$indexName/$typeName': ${event.tags}")

    val attachment = (publishEventValue, event.value) match {
      case (true, str: String) ⇒ Map(typeName → str)
      case (true, any)         ⇒ Map("value" → write(any)(DefaultFormats), typeName → (if (typeName == Event.defaultType) "" else any))
      case (false, _)          ⇒ Map("value" → "")
    }

    val data = Extraction.decompose(event) merge Extraction.decompose(attachment)

    es.index[ElasticsearchIndexResponse](indexName, typeName, data) map {
      case response: ElasticsearchIndexResponse ⇒ event
      case other ⇒
        log.error(s"Unexpected index result: ${other.toString}.")
        other
    }
  }

  protected def eventQuery(page: Int, perPage: Int)(query: EventQuery): Future[Any] = {
    log.debug(s"Pulse query: $query")
    query.aggregator match {
      case None                                    ⇒ getEvents(query, page, perPage)
      case Some(Aggregator(Aggregator.`count`, _)) ⇒ countEvents(query)
      case Some(Aggregator(aggregator, field))     ⇒ aggregateEvents(query, aggregator, field)
      case _                                       ⇒ throw new UnsupportedOperationException
    }
  }

  protected def getEvents(query: EventQuery, page: Int, perPage: Int) = {
    implicit val formats = SerializationFormat(OffsetDateTimeSerializer, new EnumNameSerializer(Aggregator))
    val (p, pp) = OffsetEnvelope.normalize(page, perPage, EventRequestEnvelope.maxPerPage)

    es.search[ElasticsearchSearchResponse](indexName, constructSearch(query, p, pp)) map {
      case ElasticsearchSearchResponse(hits) ⇒
        EventResponseEnvelope(hits.hits.flatMap(hit ⇒ Some(read[Event](write(hit._source)))), hits.total, p, pp)
      case other ⇒ reportException(EventQueryError(other))
    }
  }

  protected def countEvents(eventQuery: EventQuery): Future[Any] = es.count(indexName, constructQuery(eventQuery)) map {
    case ElasticsearchCountResponse(count) ⇒ LongValueAggregationResult(count)
    case other                             ⇒ reportException(EventQueryError(other))
  }

  private def constructSearch(eventQuery: EventQuery, page: Int, perPage: Int): Map[Any, Any] = {
    constructQuery(eventQuery) +
      ("from" → ((page - 1) * perPage)) +
      ("size" → perPage) +
      ("sort" → Map("timestamp" → Map("order" → "desc")))
  }

  private def constructQuery(eventQuery: EventQuery): Map[Any, Any] = {
    Map("query" →
      Map("filtered" →
        Map(
          "query" → Map("match_all" → Map()),
          "filter" → Map("bool" →
            Map("must" → List(constructTagQuery(eventQuery.tags), constructTypeQuery(eventQuery.`type`), constructTimeRange(eventQuery.timestamp)).filter(_.isDefined).map(_.get)))
        )))
  }

  private def constructTagQuery(tags: Set[String]): Option[List[Map[String, Any]]] = {
    if (tags.nonEmpty) Option((for (tag ← tags) yield Map("term" → Map("tags" → tag))).toList) else None
  }

  private def constructTypeQuery(`type`: Option[String]): Option[Map[String, Any]] = {
    if (`type`.nonEmpty) Option(Map("term" → Map("type" → `type`.get))) else None
  }

  private def constructTimeRange(timeRange: Option[TimeRange]): Option[Map[Any, Any]] = timeRange match {
    case Some(tr) ⇒
      val query = Map(
        "lt" → tr.lt,
        "lte" → tr.lte,
        "gt" → tr.gt,
        "gte" → tr.gte
      ).filter(_._2.isDefined).map { case (k, v) ⇒ k → v.get }
      if (query.isEmpty) None else Some(Map("range" → Map("timestamp" → query)))

    case _ ⇒ None
  }

  protected def aggregateEvents(eventQuery: EventQuery, aggregator: AggregatorType, field: Option[String]) = {
    es.aggregate(indexName, constructAggregation(eventQuery, aggregator, field)) map {
      case ElasticsearchAggregationResponse(ElasticsearchAggregations(ElasticsearchAggregationValue(value))) ⇒ DoubleValueAggregationResult(value)
      case other ⇒ reportException(EventQueryError(other))
    }
  }

  private def constructAggregation(eventQuery: EventQuery, aggregator: AggregatorType, field: Option[String]): Map[Any, Any] = {
    val aggregation = aggregator match {
      case Aggregator.average ⇒ "avg"
      case _                  ⇒ aggregator.toString
    }

    constructQuery(eventQuery) +
      ("size" → 0) +
      ("aggs" → Map("aggregation" → Map(s"$aggregation" → Map("field" → field.getOrElse("value")))))
  }
}

trait ElasticsearchPulseEvent {

  def indexName: String

  def indexTimeFormat: Map[String, String]

  def indexTypeName(schema: String = Event.defaultType): (String, String) = {
    val base = schema.toLowerCase
    val format = indexTimeFormat.getOrElse(base, indexTimeFormat.getOrElse(Event.defaultType, "YYYY-MM-dd"))
    val time = OffsetDateTime.now().format(DateTimeFormatter.ofPattern(format))
    s"$indexName-$base-$time" → base
  }
}