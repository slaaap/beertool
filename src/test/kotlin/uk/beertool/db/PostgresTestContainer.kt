package uk.beertool.db

import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

object PostgresTestContainer {
    val instance: PostgreSQLContainer by lazy {
        PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine")).apply { start() }
    }
}
