package fund.cyber.markets.connector

import fund.cyber.markets.connector.configuration.TRADES_TOPIC_PREFIX
import fund.cyber.markets.helpers.MILLIS_TO_HOURS
import fund.cyber.markets.helpers.convert
import fund.cyber.markets.model.TokensPair
import fund.cyber.markets.model.Trade
import fund.cyber.markets.model.TradeType
import info.bitrich.xchangestream.core.ProductSubscription
import info.bitrich.xchangestream.core.StreamingExchange
import info.bitrich.xchangestream.core.StreamingExchangeFactory
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.reactivex.disposables.Disposable
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate

class XchangeConnector: ExchangeConnector {
    private val log = LoggerFactory.getLogger(javaClass)!!
    private lateinit var monitoring: MeterRegistry

    private lateinit var exchange: StreamingExchange
    private val exchangeName by lazy { exchange.exchangeSpecification.exchangeName.toUpperCase() }
    private val exchangeTokensPairs by lazy { exchange.exchangeSymbols }
    private val subscriptions = mutableListOf<Disposable>()

    private val tradesTopicName by lazy { TRADES_TOPIC_PREFIX + exchangeName }
    private lateinit var kafkaTemplate: KafkaTemplate<String, Any>

    private constructor()

    constructor(streamingExchangeClassName: String, kafkaTemplate: KafkaTemplate<String, Any>, meterRegistry: MeterRegistry) : this() {
        exchange = StreamingExchangeFactory.INSTANCE.createExchange(streamingExchangeClassName)
        this.kafkaTemplate = kafkaTemplate
        this.monitoring = meterRegistry
    }

    override fun connect() {
        val subscription = ProductSubscription.create()
        exchangeTokensPairs.forEach { pair ->
            subscription.addTrades(pair)
        }

        exchange.connect(
            subscription.build()
        ).blockingAwait()
        log.info("Connected to the $exchangeName exchange")
    }

    override fun disconnect() {
        subscriptions.forEach { subscription ->
            subscription.dispose()
        }
        exchange.disconnect().subscribe {
            log.info("Disconnected from the $exchangeName exchange")
        }
    }

    override fun isAlive(): Boolean {
        return exchange.isAlive
    }

    override fun subscribeTrades() {
        log.info("Subscribing for trades from $exchangeName exchange")

        val exchangeTag = Tags.of("exchange", exchangeName)

        exchangeTokensPairs.forEach { pair ->
            val exchangePairTag = exchangeTag.and(Tags.of("tokens_pair", pair.base.currencyCode + "_" + pair.counter.currencyCode))
            val tradePerSecondMonitor = monitoring.counter("trade_count", exchangePairTag)

            val tradeSubscription = exchange.streamingMarketDataService
                    .getTrades(pair)
                    .subscribe({ exchangeTrade ->
                        log.debug("$exchangeName trade: {}", exchangeTrade)
                        val trade = convertTrade(exchangeName, exchangeTrade)
                        kafkaTemplate.send(tradesTopicName, trade)
                        tradePerSecondMonitor.increment()
                    }) { throwable ->
                        log.error("Error in subscribing trades for $exchangeName", throwable)
                    }
            subscriptions.add(tradeSubscription)
        }
    }

    override fun updateTokensPairs() {
        log.warn("Update tokens pair for $exchangeName exchange not implemented yet")
    }

    private fun convertTrade(exchangeName: String, exchangeTrade: org.knowm.xchange.dto.marketdata.Trade): Trade {
        return Trade(
                exchangeName.toUpperCase(),
                TokensPair(exchangeTrade.currencyPair.base.currencyCode, exchangeTrade.currencyPair.counter.currencyCode),
                TradeType.valueOf(exchangeTrade.type.name),
                exchangeTrade.timestamp,
                exchangeTrade.timestamp.time convert MILLIS_TO_HOURS,
                exchangeTrade.id,
                exchangeTrade.originalAmount,
                exchangeTrade.originalAmount.multiply(exchangeTrade.price),
                exchangeTrade.price
        )
    }
}