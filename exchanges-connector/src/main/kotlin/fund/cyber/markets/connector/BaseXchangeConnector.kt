package fund.cyber.markets.connector

import fund.cyber.markets.connector.configuration.ORDERS_TOPIC_PREFIX
import fund.cyber.markets.connector.configuration.ORDER_BOOKS_TOPIC_PREFIX
import fund.cyber.markets.connector.configuration.TRADES_TOPIC_PREFIX
import fund.cyber.markets.helpers.MILLIS_TO_HOURS
import fund.cyber.markets.helpers.convert
import fund.cyber.markets.model.Order
import fund.cyber.markets.model.TokensPair
import fund.cyber.markets.model.Trade
import fund.cyber.markets.model.TradeType
import info.bitrich.xchangestream.core.ProductSubscription
import info.bitrich.xchangestream.core.StreamingExchange
import io.reactivex.disposables.Disposable
import org.knowm.xchange.dto.marketdata.OrderBook
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate

abstract class BaseXchangeConnector: ExchangeConnector {
    private val log = LoggerFactory.getLogger(javaClass)!!

    abstract val exchange: StreamingExchange
    private val exchangeName by lazy { exchange.exchangeSpecification.exchangeName.toUpperCase() }
    private val exchangeTokensPairs by lazy { exchange.exchangeSymbols }
    private val subscriptions = mutableListOf<Disposable>()

    private val tradesTopicName by lazy { TRADES_TOPIC_PREFIX + exchangeName }
    private val ordersTopicName by lazy { ORDERS_TOPIC_PREFIX + exchangeName }
    private val orderBooksTopicName by lazy { ORDER_BOOKS_TOPIC_PREFIX + exchangeName }

    abstract val tradeKafkaTemplate: KafkaTemplate<String, Trade>
    abstract val orderKafkaTemplate: KafkaTemplate<String, Order>
    abstract val orderBookKafkaTemplate: KafkaTemplate<String, OrderBook>

    override fun connect() {
        val subscription = ProductSubscription.create()
        exchangeTokensPairs.forEach { pair ->
            subscription.addTrades(pair)
            subscription.addOrderbook(pair)
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
        exchangeTokensPairs.forEach { pair ->
            val tradeSubscription = exchange.streamingMarketDataService
                    .getTrades(pair)
                    .subscribe({ exchangeTrade ->
                        log.debug("$exchangeName trade: {}", exchangeTrade)
                        val trade = convertTrade(exchangeName, exchangeTrade)
                        tradeKafkaTemplate.send(tradesTopicName, trade)
                    }) { throwable ->
                        log.error("Error in subscribing trades for $exchangeName", throwable)
                    }
            subscriptions.add(tradeSubscription)
        }
    }

    override fun subscribeOrders() {
        //todo: implement orders
    }

    override fun subscribeOrderBook() {
        exchangeTokensPairs.forEach { pair ->
            val orderBookSubscription = exchange.streamingMarketDataService
                    .getOrderBook(pair)
                    .subscribe({ orderBook ->
                        log.debug("Order_book TS:{}, bid_size:{}, ask_size:{}", orderBook.timeStamp, orderBook.bids.size, orderBook.asks.size)

                        //todo: save only snapshots of orderbook
                        orderBookKafkaTemplate.send(orderBooksTopicName, orderBook)
                    }) { throwable ->
                        log.error("Error in subscribing order book for $exchangeName.", throwable)
                    }
            subscriptions.add(orderBookSubscription)
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