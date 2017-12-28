package fund.cyber.markets.rest.configuration

import fund.cyber.markets.helpers.env
import java.util.*

const val CASSANDRA_HOSTS = "CASSANDRA_HOSTS"
const val CASSANDRA_HOSTS_DEFAULT = "localhost"

const val CASSANDRA_PORT = "CASSANDRA_PORT"
const val CASSANDRA_PORT_DEFAULT = "9042"

const val CORS_ALLOWED_ORIGINS = "CORS_ALLOWED_ORIGINS"
const val CORS_ALLOWED_ORIGINS_DEFAULT = "markets.cyber.fund"

object RestApiConfiguration {

    val allowedCORS: String = env(CORS_ALLOWED_ORIGINS, CORS_ALLOWED_ORIGINS_DEFAULT)

    val cassandraProperties = Properties().apply {
        put("cassandraHost", env(CASSANDRA_HOSTS, CASSANDRA_HOSTS_DEFAULT))
        put("cassandraPort", env(CASSANDRA_PORT, CASSANDRA_PORT_DEFAULT))
    }

}