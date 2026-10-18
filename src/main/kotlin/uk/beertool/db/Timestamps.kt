package uk.beertool.db

import java.time.Instant
import java.time.temporal.ChronoUnit

// Postgres keeps microseconds, so a timestamp only round-trips equal once it is cut to that precision.
fun Instant.forDb(): Instant = truncatedTo(ChronoUnit.MICROS)
