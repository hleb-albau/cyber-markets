package fund.cyber.markets.rest.handler

import fund.cyber.markets.cassandra.repository.TickerRepository
import fund.cyber.markets.common.Durations
import fund.cyber.markets.common.booleanValue
import fund.cyber.markets.common.stringValue
import fund.cyber.markets.dto.TokensPair
import fund.cyber.markets.rest.common.CrossConversion
import fund.cyber.markets.rest.configuration.AppContext
import io.undertow.server.HttpHandler
import io.undertow.server.HttpServerExchange
import java.math.BigDecimal

class PriceMultiHandler(
    private val tickerRepository: TickerRepository = AppContext.tickerRepository
) : AbstractHandler(), HttpHandler {

    override fun handleRequest(httpExchange: HttpServerExchange) {

        val params = httpExchange.queryParameters
        val bases = params["fsyms"]?.stringValue()?.split(",")
        val quotes = params["tsyms"]?.stringValue()?.split(",")
        val exchange = params["e"]?.stringValue() ?: "ALL"
        val tryConversion = params["tryConversion"]?.booleanValue() ?: true

        if (bases == null || quotes == null) {
            handleBadRequest("Bad parameters", httpExchange)
            return
        }

        val windowDuration = Durations.MINUTE
        val timestamp = System.currentTimeMillis() / windowDuration * windowDuration
        val result = mutableMapOf<String, MutableMap<String, BigDecimal>>()

        for (base in bases) {
            val quoteMap = mutableMapOf<String, BigDecimal>()
            for (quote in quotes) {
                val ticker = tickerRepository.getMinuteTicker(TokensPair(base, quote), exchange, timestamp)
                if (ticker != null) {
                    quoteMap.put(quote, ticker.price)
                } else if (tryConversion) {
                    val conversion = CrossConversion(tickerRepository, base, quote, exchange, windowDuration, timestamp).calculate()
                    if (conversion.success) {
                        quoteMap.put(quote, conversion.value!!)
                    }
                }
            }
            result.put(base, quoteMap)
        }

        if (result.isEmpty()) {
            handleNoData(httpExchange)
            return
        }

        send(result, httpExchange)
    }

}